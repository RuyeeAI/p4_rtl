#!/usr/bin/env python3
"""golden 对拍第一级：官方 p4c-bm2-ss 编译 + bmv2 simple_switch 无头仿真。

收编自 ../p4x 的 p4xlib（2026-10-02，p4x 目录删除前并入本工程；修复了原实现
「以输出目录为 cwd 却传相对 bmv2 JSON 路径」的 bug —— 这里统一用绝对路径）。

产出 golden 参考：同一报文进出 bmv2 后的实际帧（作为 XLS/RTL 侧的对拍基准）。
无需 root / 真实网卡：bmv2 `--use-files` 模式，port N@X 读写 X_in.pcap / X_out.pcap。

⚠️ 输入必须是**官方 v1model 语法可编译**的 P4 —— 本工程 testcases/p4/ 下的样本
   是自研子集（顶层 Register/Counter extern 等），官方 p4c 会报错。

用法：
  scripts/golden_sim.py <in.p4> [-o DIR] [--packet HEX] [--ports N] [--wait N] [--check]

工具链定位（高 → 低）：
  1. 环境变量 P4X_GOLDEN_DIST / P4X_DIST
  2. ../third_party/dist（工程外同级；packaging/ 配方可重建到此处）
  3. PATH 回退
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

EXIT_OK, EXIT_COMPILE, EXIT_SIM, EXIT_TOOLING = 0, 2, 3, 5

PCAP_MAGIC = 0xA1B2C3D4
LINKTYPE_ETHERNET = 1
SNAPLEN = 65535
PCAP_HEADER_LEN = 24

# 默认注入帧：dst 00:00:00:00:00:02 / src 00:00:00:00:00:01 / ethertype 0x0800 + 40B 载荷
DEFAULT_FRAME = bytes.fromhex("0000000000020000000000010800") + bytes(range(0x45, 0x45 + 40))


def err(msg: str) -> None:
    print("golden_sim: " + msg, file=sys.stderr)


# ---------------- 工具链定位（原 p4xlib/config.py 的精简版） ----------------

def find_dist() -> Path | None:
    for c in (os.environ.get("P4X_GOLDEN_DIST"), os.environ.get("P4X_DIST"),
              str(REPO.parent / "third_party" / "dist")):
        if c and Path(c).is_dir():
            return Path(c)
    return None


class Toolchain:
    def __init__(self):
        self.dist = find_dist()
        self.p4c_bm2ss = self._tool("P4X_P4C_BM2SS", "bin/p4c-bm2-ss", "p4c-bm2-ss")
        self.bmv2_switch = self._tool("P4X_BMV2_SWITCH", "bin/simple_switch", "simple_switch")
        self.p4include = (self.dist / "share" / "p4c" / "p4include") if self.dist else None
        if self.p4include is not None and not self.p4include.is_dir():
            self.p4include = None

    def _tool(self, env_var: str, dist_rel: str, name: str) -> Path | None:
        cands = [os.environ.get(env_var),
                 str(self.dist / dist_rel) if self.dist else None,
                 shutil.which(name)]
        for c in cands:
            if c and Path(c).exists():
                return Path(c)
        return None

    def child_env(self) -> dict:
        env = dict(os.environ)
        if self.p4include:
            env["P4C_16_INCLUDE_PATH"] = str(self.p4include)
        return env

    def status(self) -> dict:
        return {"dist": str(self.dist) if self.dist else None,
                "p4c-bm2-ss": str(self.p4c_bm2ss) if self.p4c_bm2ss else None,
                "simple_switch": str(self.bmv2_switch) if self.bmv2_switch else None,
                "p4include": str(self.p4include) if self.p4include else None}


# ---------------- pcap 读写（原 p4xlib/sim.py） ----------------

def write_pcap(path, frames):
    with Path(path).open("wb") as f:
        f.write(struct.pack("<IHHIIII", PCAP_MAGIC, 2, 4, 0, 0, SNAPLEN, LINKTYPE_ETHERNET))
        for i, frame in enumerate(frames):
            f.write(struct.pack("<IIII", i, 0, len(frame), len(frame)))
            f.write(frame)


def read_pcap(path):
    data = Path(path).read_bytes()
    if len(data) < PCAP_HEADER_LEN:
        return []
    magic = struct.unpack("<I", data[:4])[0]
    endian = "<" if magic == PCAP_MAGIC else ">" if magic == 0xD4C3B2A1 else None
    if endian is None:
        raise ValueError(f"不是 pcap 文件: {path}")
    frames, off = [], PCAP_HEADER_LEN
    while off + 16 <= len(data):
        _, _, incl_len, _ = struct.unpack(endian + "IIII", data[off:off + 16])
        off += 16
        if off + incl_len > len(data):
            break
        frames.append(data[off:off + incl_len])
        off += incl_len
    return frames


def _hexframe(s: str) -> bytes:
    s = s.strip().replace(":", "").replace(" ", "").replace("0x", "")
    if len(s) % 2:
        raise ValueError("--packet 需为偶数个十六进制字符")
    return bytes.fromhex(s)


def _wait_for_outputs(out_pcaps, settle_s=0.5, timeout_s=20.0):
    """轮询直到任一输出 pcap 出现且大小稳定；返回是否见到过输出。"""
    deadline = time.time() + timeout_s
    last = {p: -1 for p in out_pcaps}
    stable_since = None
    seen = False
    while time.time() < deadline:
        cur = {}
        for p in out_pcaps:
            try:
                cur[p] = p.stat().st_size
            except OSError:
                cur[p] = 0
        if any(sz > PCAP_HEADER_LEN for sz in cur.values()):
            seen = True
        if cur == last and seen:
            if stable_since is None:
                stable_since = time.time()
            elif time.time() - stable_since >= settle_s:
                return True
        else:
            stable_since = None
        time.sleep(0.1)
    return seen


# ---------------- 主流程（原 p4x sim，已修相对路径 bug） ----------------

def cmd_sim(tc: Toolchain, args) -> int:
    if not tc.p4c_bm2ss or not tc.bmv2_switch:
        err("缺少 p4c-bm2-ss 或 simple_switch；检查 P4X_GOLDEN_DIST / ../third_party/dist，"
            "或用 packaging/ 配方重建")
        return EXIT_TOOLING

    p4 = Path(args.p4).resolve()   # ⚠️ 绝对路径：p4c/simple_switch 都以 out 为 cwd
    if not p4.exists():
        err(f"输入文件不存在: {args.p4}")
        return EXIT_COMPILE

    out = Path(args.out).resolve()   # ⚠️ 必须绝对：simple_switch 以 out 为 cwd
    out.mkdir(parents=True, exist_ok=True)
    stem = p4.stem
    bmv2_json = out / f"{stem}.bmv2.json"
    ir_json = out / f"{stem}.ir.json"

    # 1) 编译（--toJSON 同时产出 IR JSON）
    proc = subprocess.run([str(tc.p4c_bm2ss), "--toJSON", str(ir_json), "-o", str(bmv2_json), str(p4)],
                          cwd=str(out), env=tc.child_env(), capture_output=True, text=True)
    if proc.returncode != 0 or not bmv2_json.exists():
        sys.stderr.write(proc.stdout or "")
        sys.stderr.write(proc.stderr or "")
        err(f"p4c-bm2-ss 编译失败 (exit {proc.returncode})")
        return EXIT_COMPILE

    # 2) 各端口输入 pcap（bmv2 要求每个已配置端口的 _in.pcap 都存在）
    try:
        frame = _hexframe(args.packet) if args.packet else DEFAULT_FRAME
    except ValueError as e:
        err(str(e))
        return EXIT_COMPILE
    prefixes = [out / f"port{i}" for i in range(args.ports)]
    for i, pfx in enumerate(prefixes):
        write_pcap(str(pfx) + "_in.pcap", [frame] if i == 0 else [])
        outp = Path(str(pfx) + "_out.pcap")
        if outp.exists():
            outp.unlink()  # 清掉上一轮结果，避免误读

    # 3) 启动 simple_switch（--use-files：无需 root/NIC；不会自行退出，主动终止）
    argv = [str(tc.bmv2_switch), "--use-files", str(args.wait)]
    for i, pfx in enumerate(prefixes):
        argv += ["-i", f"{i}@{pfx}"]
    argv.append(str(bmv2_json))      # 绝对路径（原 p4x 的 bug 就在这里）

    log_path = out / "simple_switch.log"
    with log_path.open("w") as log:
        child = subprocess.Popen(argv, cwd=str(out), env=tc.child_env(),
                                 stdout=log, stderr=subprocess.STDOUT)

    out_pcaps = [Path(str(pfx) + "_out.pcap") for pfx in prefixes]
    try:
        _wait_for_outputs(out_pcaps, timeout_s=args.wait + 20.0)
    finally:
        child.terminate()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=5)

    # 4) 汇报
    print(f"[sim] bmv2 json : {bmv2_json}")
    print(f"[sim] ir json   : {ir_json}")
    print(f"[sim] in        : {frame.hex()}")
    total = 0
    for i, pfx in enumerate(prefixes):
        outp = Path(str(pfx) + "_out.pcap")
        if not outp.exists():
            continue
        for j, f in enumerate(read_pcap(outp)):
            print(f"[sim] port{i} out{j}: {f.hex()}")
            total += 1
    if total == 0:
        print("[sim] 无输出帧（报文被丢弃或程序未转发）")
        print(f"[sim] 交换机日志: {log_path}")
    else:
        print(f"[sim] 输出 pcap: {out}")
    return EXIT_OK


def main() -> int:
    ap = argparse.ArgumentParser(description="golden 对拍第一级：官方 p4c + bmv2 无头仿真")
    ap.add_argument("p4", nargs="?", help="官方 v1model 语法的 P4 文件")
    ap.add_argument("-o", "--out", default="out/golden")
    ap.add_argument("--packet", help="注入 port0 的报文（十六进制，不含前导 0x）")
    ap.add_argument("--wait", type=int, default=1, help="simple_switch --use-files 的等待秒数")
    ap.add_argument("--ports", type=int, default=2, help="映射为文件的端口数")
    ap.add_argument("--check", action="store_true", help="只检查工具链完整性")
    args = ap.parse_args()

    tc = Toolchain()
    if args.check or not args.p4:
        print(json.dumps(tc.status(), ensure_ascii=False, indent=2))
        return EXIT_OK if all(tc.status().values()) else EXIT_TOOLING
    return cmd_sim(tc, args)


if __name__ == "__main__":
    sys.exit(main())
