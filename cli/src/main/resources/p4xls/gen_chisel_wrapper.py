#!/usr/bin/env python3
"""gen_chisel_wrapper.py —— 路线 3：XLS 生成的 Verilog 原样封装成 Chisel BlackBox。

对每个输入的 .v 文件（XLS codegen 产物，单 top module）生成一个 .scala：

  1. BlackBox 类   —— 端口 1:1 映射（clk/rst 显式暴露），desiredName 指向
                      Verilog module 名。firtool 链接时与同名 Verilog 模块对接。
  2. Shell 类      —— 友好外壳（普通 chisel3.Module）：隐式时钟/复位自动接线，
                      其余端口 1:1 直连，下游像用普通 Chisel 模块一样实例化。

Chisel 版本口径：**chisel 7.15.0 / scala 2.13.16，与 ../HardwareDesign 对齐**。
生成代码只用了 BlackBox/Module/IO/Bundle/UInt/Bool/Clock 与 `reset.asBool`，
在 chisel 7 下全部现行（HardwareDesign 自身即此用法）；不依赖 chiseltest。

`--with-sim` 追加生成 `<Cls>Sim.scala` —— svsim 仿真入口，**跑起来直接出 FST
波形**（chisel 7 起 svsim 的 Verilator 后端支持 TraceKind.Fst，5.3/6.x 只有 VCD）：
  - DUT Verilog 经 `ExtModule.setInline` **内嵌**进 .scala（自包含，无需外部 .v）；
    `--sim-extra` 可附加更多源文件（如 proc 网络依赖的 xls_fifo_wrapper.sv），
    与 DUT 拼接成一份 inline；
  - `sbt "rtl/runMain p4xlsrtl.<Cls>Sim" [拍数]`：复位 5 拍 + 空转 N 拍，
    FST 落 `<workspace>/workdir-verilator/trace.fst`（surfer / GTKWave 直接可读）；
  - 激励是骨架（只复位+空转），功能激励在 main 的 TODO 处补 `poke/peek`。

用法：
  scripts/gen_chisel_wrapper.py -o out/flow/<样本>/chisel out/flow/<样本>/verilog/*.v
    # 输入 = p4flow 拆分后的「一 module 一文件」产物，每个 .v 生成一个 .scala
    #（BlackBox + Shell，文件名/类名都从 module 名派生）。
  scripts/gen_chisel_wrapper.py -o rtl/src/main/scala/p4xlsrtl --with-sim \\
      --top Ingress_pipeline --sim-stem demo12-l2l3-switch \\
      --sim-extra <xls_fifo_wrapper.sv> out/flow/<样本>/verilog/*.v

自检（生成后自动执行）：BB 的 io 字段数 == Verilog 端口数；Shell 连线语句数
== 端口数 - 2（clk/rst 隐式）。

边界与假设：
  - 一个 .v 恰好一个 module（p4flow 已按模块拆分），多 module 报错退出；
  - 端口名必须是合法 Scala 标识符且非保留字（XLS 的 chan 名满足；检查到
    冲突立即报错，不做静默改名）；
  - 仅支持 `input/output wire [H:L] name` 形式（XLS 输出形态），不支持
    reg 端口 / 参数化模块 / 多维端口。
"""

import argparse
import pathlib
import re
import sys

# Scala/chisel3 常见保留字与易冲突名（端口名撞上即报错，提示手动处理）
RESERVED = {
    "abstract", "case", "catch", "class", "def", "do", "else", "extends",
    "false", "final", "finally", "for", "if", "implicit", "import", "lazy",
    "match", "new", "null", "object", "override", "package", "private",
    "protected", "return", "sealed", "super", "this", "throw", "trait",
    "true", "try", "type", "val", "var", "while", "with", "yield",
    "clone", "eq", "notify", "wait", "getClass", "hashCode", "toString",
}

MOD_RE = re.compile(r"^\s*module\s+(\w+)\s*\(")
PORT_RE = re.compile(
    r"^\s*(input|output)\s+(?:wire|reg)\s*(?:\[\s*(\d+)\s*:\s*(\d+)\s*\])?\s*(\w+)\s*,?\s*$")


class Port:
    def __init__(self, direction: str, width: int, name: str):
        self.direction = direction  # "input" | "output"
        self.width = width
        self.name = name

    @property
    def is_clk(self) -> bool:
        return self.name == "clk"

    @property
    def is_rst(self) -> bool:
        return self.name == "rst"


def parse_module(text: str, path: str):
    """返回 (module 名, [Port])。要求文件恰好一个 module（p4flow 已按模块拆分）。"""
    mods = []
    cur = None  # (name, [raw port lines])
    for lineno, line in enumerate(text.splitlines(), 1):
        m = MOD_RE.match(line)
        if m and cur is None:
            cur = (m.group(1), [])
            continue
        if cur is not None:
            if line.strip().startswith(")"):
                mods.append(cur)
                cur = None
                continue
            cur[1].append((lineno, line))
    if not mods:
        raise SystemExit(f"{path}: 未找到 module 声明")
    if len(mods) > 1 or cur is not None:
        raise SystemExit(f"{path}: 期望单 module（发现 {len(mods) + (1 if cur is not None else 0)} 个）"
                         "；p4flow 产物应已按「一 module 一文件」拆分")

    name, raws = mods[0]
    ports = []
    for lineno, line in raws:
        m = PORT_RE.match(line)
        if not m:
            if line.strip():
                raise SystemExit(f"{path}:{lineno}: 无法解析端口行（仅支持 input/output wire）：{line.strip()}")
            continue
        direction, hi, lo, pname = m.groups()
        width = (int(hi) - int(lo) + 1) if hi is not None else 1
        if pname in RESERVED:
            raise SystemExit(f"{path}:{lineno}: 端口名 '{pname}' 是 Scala 保留字，需手动处理")
        ports.append(Port(direction, width, pname))
    if not ports:
        raise SystemExit(f"{path}: module {name} 无端口")
    return name, ports


def chisel_io_line(p: Port) -> str:
    if p.is_clk:
        return f"    val {p.name} = Input(Clock())"
    if p.is_rst:
        return f"    val {p.name} = Input(Bool())"
    t = f"UInt({p.width}.W)" if p.width > 1 else "Bool()"
    d = "Input" if p.direction == "input" else "Output"
    return f"    val {p.name} = {d}({t})"


def pascal(stem: str) -> str:
    parts = re.split(r"[^0-9a-zA-Z]+", stem)
    joined = "".join(p[:1].upper() + p[1:] for p in parts if p)
    if not joined or not (joined[0].isalpha() or joined[0] == "_"):
        joined = "_" + joined
    return joined


def emit(verilog_path: pathlib.Path, out_dir: pathlib.Path) -> pathlib.Path:
    text = verilog_path.read_text(encoding="utf-8")
    mod_name, ports = parse_module(text, str(verilog_path))
    cls = pascal(mod_name)  # 类名从 module 名派生（文件名=module 名）

    data_ports = [p for p in ports if not p.is_clk and not p.is_rst]
    has_clk = any(p.is_clk for p in ports)
    has_rst = any(p.is_rst for p in ports)
    if not (has_clk and has_rst):
        raise SystemExit(f"{verilog_path}: 缺少 clk/rst 端口（XLS proc 产物应自带）")

    b = []
    b.append("// Generated by scripts/gen_chisel_wrapper.py (route 3). DO NOT EDIT.")
    b.append(f"// Wraps XLS-generated Verilog: {verilog_path.name} (module {mod_name})")
    b.append("//")
    b.append("// Chisel 口径：chisel 7.15.0 / scala 2.13.16（与 ../HardwareDesign 对齐）。")
    b.append("// 使用方式：把本文件与对应 .v 一起交给 Chisel 构建（BB 与同名 Verilog")
    b.append("// 模块链接），或在综合流程里把 .v 作为附加源文件。实例化 Shell 类即可，")
    b.append("// 隐式时钟/复位已自动接线：")
    b.append(f"//   val dut = Module(new {cls})")
    b.append("//   dut.io.<port> := ...")
    b.append("package p4xlsrtl")
    b.append("")
    b.append("import chisel3._")
    b.append("import chisel3.util._")
    b.append("")
    b.append(f"/** BlackBox：与 Verilog module `{mod_name}` 端口 1:1 对应。 */")
    b.append(f"final class {cls}BlackBox extends BlackBox {{")
    b.append("  override def desiredName: String = \"" + mod_name + "\"")
    b.append("  val io = IO(new Bundle {")
    for p in ports:
        b.append(chisel_io_line(p))
    b.append("  })")
    b.append("}")
    b.append("")
    b.append(f"/** 友好外壳：隐式时钟/复位自动接线，数据端口 1:1 直连。 */")
    b.append(f"final class {cls} extends Module {{")
    b.append("  val io = IO(new Bundle {")
    for p in data_ports:
        b.append(chisel_io_line(p))
    b.append("  })")
    b.append(f"  private val bb = Module(new {cls}BlackBox)")
    b.append("  bb.io.clk := clock")
    b.append("  bb.io.rst := reset.asBool")
    for p in data_ports:
        if p.direction == "input":
            b.append(f"  bb.io.{p.name} := io.{p.name}")
    for p in data_ports:
        if p.direction == "output":
            b.append(f"  io.{p.name} := bb.io.{p.name}")
    b.append("}")
    b.append("")

    out_path = out_dir / f"{cls}.scala"
    out_path.write_text("\n".join(b) + "\n", encoding="utf-8")
    return out_path


def emit_sim(verilog_paths: list[pathlib.Path], out_dir: pathlib.Path, top: str,
             extra_paths: list[pathlib.Path], stem: str) -> pathlib.Path:
    """生成 <TopCls>Sim.scala：svsim 仿真入口（chisel 7，Verilator 后端 + FST 波形）。

    DUT = 顶层模块（ExtModule.setInline 内嵌）；全部模块文件 + extra 源
    （xls_fifo_wrapper.sv 等）拼成同一份 inline blob，仿真自包含。
    激励为骨架：复位 5 拍 + 空转 N 拍，波形 FST 落盘。
    """
    cls = pascal(top)
    parts = [p.read_text(encoding="utf-8") for p in verilog_paths]
    for p in extra_paths:
        parts.append(p.read_text(encoding="utf-8"))
    blob = "\n\n".join(parts)
    if '"""' in blob:
        raise SystemExit(f"{verilog_paths[0]}: Verilog 含三引号序列，无法内嵌 Scala 字符串，需手动处理")

    top_path = next(p for p in verilog_paths
                    if parse_module(p.read_text(encoding="utf-8"), str(p))[0] == top)
    ports = parse_module(top_path.read_text(encoding="utf-8"), str(top_path))[1]
    data_ports = [p for p in ports if not p.is_clk and not p.is_rst]

    L: list[str] = []
    L.append("// Generated by scripts/gen_chisel_wrapper.py (--with-sim). DO NOT EDIT.")
    L.append(f"// {cls} 的 svsim 仿真入口（chisel 7.15.0 / scala 2.13.16，同 ../HardwareDesign 工具链）。")
    L.append("//")
    L.append("// 用法：sbt \"rtl/runMain p4xlsrtl." + cls + "Sim\" [拍数]  # 默认 100 拍")
    L.append("// 跑完 FST 波形在 <workspace>/workdir-verilator/trace.fst（surfer / GTKWave 可读）。")
    L.append("// ⚠️ 激励目前只是「复位 5 拍 + 空转 N 拍」的骨架 —— 功能激励在 main 的 TODO 处补。")
    L.append("// ⚠️ simulate() 会吞异常，返回 digest 后必须取 .result（本类已代取）。")
    L.append("package p4xlsrtl")
    L.append("")
    L.append("import chisel3._")
    L.append("import chisel3.simulator.PeekPokeAPI._")
    L.append("import chisel3.simulator.{Randomization, Settings, SimulatedModule, Simulator}")
    L.append("import svsim.verilator.Backend")
    L.append("")
    L.append("/** DUT：XLS 产物 Verilog 经 ExtModule 内嵌（自包含，无需外部 .v）。")
    L.append("  * ⚠️ ExtModule 端口默认带 io_ 前缀，与 XLS Verilog 裸端口名对不上 ——")
    L.append("  * 必须用 FlatIO（等价于 BlackBox 的裸端口名行为）。 */")
    L.append(f"final class {cls}SimExt extends ExtModule {{")
    L.append(f"  override def desiredName: String = \"{top}\"")
    L.append("  val io = FlatIO(new Bundle {")
    for p in ports:
        L.append(chisel_io_line(p))
    L.append("  })")
    L.append(f"  setInline(\"{top}.sv\", {cls}VerilogBlob.text)")
    L.append("}")
    L.append("")
    L.append("/** 内嵌 Verilog（DUT" + (" + 附加源" if extra_paths else "") + "），单份 blob 交给 firtool。 */")
    L.append(f"private object {cls}VerilogBlob {{")
    # JVM 常量池单字符串上限 64KB —— 大 Verilog 按行切成 ≤16KB 的块；
    # ⚠️ 不能用 `+` 拼：scalac 会把常量折叠回一个大字面量再次超限，须运行期 mkString
    chunks: list[str] = []
    cur: list[str] = []
    cur_len = 0
    for ln in blob.split("\n"):
        if cur and cur_len + len(ln) + 1 > 16000:
            chunks.append("\n".join(cur) + "\n")
            cur, cur_len = [], 0
        cur.append(ln)
        cur_len += len(ln) + 1
    if cur:
        chunks.append("\n".join(cur) + "\n")
    L.append("  private val parts: Seq[String] = Seq(")
    L.append("    " + ",\n    ".join('"""\n' + c + '"""' for c in chunks))
    L.append("  )")
    L.append("  val text: String = parts.mkString")
    L.append("}")
    L.append("")
    L.append("/** 仿真外壳：隐式时钟/复位，数据端口 1:1 直连（与 Shell 同构）。 */")
    L.append(f"final class {cls}SimDut extends Module {{")
    L.append("  val io = IO(new Bundle {")
    for p in data_ports:
        L.append(chisel_io_line(p))
    L.append("  })")
    L.append(f"  private val ext = Module(new {cls}SimExt)")
    L.append("  ext.io.clk := clock")
    L.append("  ext.io.rst := reset.asBool")
    for p in data_ports:
        if p.direction == "input":
            L.append(f"  ext.io.{p.name} := io.{p.name}")
    for p in data_ports:
        if p.direction == "output":
            L.append(f"  io.{p.name} := ext.io.{p.name}")
    L.append("}")
    L.append("")
    L.append("/** svsim 仿真器：Verilator 后端 + FST 波形（chisel 7 起支持 FST，5.3/6.x 只有 VCD）。 */")
    L.append(f"final class {cls}Sim(val workspacePath: String) extends Simulator[Backend] {{")
    L.append("  val backend = Backend.initializeFromProcessEnvironment()")
    L.append("  val tag = \"verilator\"")
    L.append("  val commonCompilationSettings = svsim.CommonCompilationSettings()")
    L.append("  val backendSpecificCompilationSettings = Backend.CompilationSettings.default")
    L.append("    // XLS codegen / xls_fifo_wrapper 的参数位宽写法触发 WIDTHEXPAND；")
    L.append("    // svsim 默认 warning=fatal，这里按 p4flow sim 的口径放宽常见告警。")
    L.append("    .withDisabledWarnings(Seq(")
    L.append("      \"WIDTHEXPAND\", \"WIDTHTRUNC\", \"UNUSEDSIGNAL\",")
    L.append("      \"UNUSEDPARAM\", \"UNDRIVEN\", \"DECLFILENAME\", \"VARHIDDEN\"))")
    L.append("    .withTraceStyle(Some(Backend.CompilationSettings.TraceStyle(")
    L.append("      kind = Backend.CompilationSettings.TraceKind.Fst())))")
    L.append("")
    L.append("  def tracePath: String = s\"$workspacePath/workdir-$tag/trace.fst\"")
    L.append("")
    L.append("  /** 跑一轮仿真；randomization 固定 uninitialized（chisel7 默认上电随机，")
    L.append("    * XLS proc 依赖复位清零，沿用 chisel5 语义）。 */")
    L.append(f"  def run[U](cycles: Int)(body: SimulatedModule[{cls}SimDut] => U): U =")
    L.append(f"    simulate(new {cls}SimDut,")
    L.append(f"      settings = Settings.defaultRaw[{cls}SimDut].copy(")
    L.append("        randomization = Randomization.uninitialized))(body).result")
    L.append("}")
    L.append("")
    L.append(f"object {cls}Sim {{")
    L.append("  def main(args: Array[String]): Unit = {")
    L.append("    val cycles = if (args.nonEmpty) args(0).toInt else 100")
    L.append(f"    val ws = sys.props.getOrElse(\"p4xls.rtl.workspace\", \"out/rtl/{stem}\")")
    L.append(f"    val sim = new {cls}Sim(ws)")
    L.append("    sim.run(cycles) { m =>")
    L.append("      m.controller.setTraceEnabled(true)")
    L.append("      val dut = m.wrapped")
    L.append("      dut.reset.poke(true.B)")
    L.append("      dut.clock.step(5)")
    L.append("      dut.reset.poke(false.B)")
    L.append("      // TODO 功能激励：dut.io.<port>.poke(...) / peek(...)（端口见上方 Bundle）")
    L.append("      dut.clock.step(cycles)")
    L.append("      println(s\"[sim] 复位 5 拍 + 空转 $cycles 拍完成\")")
    L.append("      println(s\"[sim] FST 波形：${sim.tracePath}\")")
    L.append("    }")
    L.append("  }")
    L.append("}")
    L.append("")

    out_path = out_dir / f"{cls}Sim.scala"
    out_path.write_text("\n".join(L) + "\n", encoding="utf-8")
    return out_path


def self_check(scala_path: pathlib.Path, ports) -> None:
    """结构自检：BB 与 Shell 的 io 字段数、clk/rst、Shell 连线数。失败抛异常。"""
    t = scala_path.read_text(encoding="utf-8")
    data_ports = [p for p in ports if not p.is_clk and not p.is_rst]
    shell_idx = t.index("/** 友好外壳")
    bb_part, shell_part = t[:shell_idx], t[shell_idx:]

    def count_fields(part: str) -> int:
        return len(re.findall(r"^\s+val \w+ = (?:Input|Output)\(", part, re.M))

    if count_fields(bb_part) != len(ports):
        raise SystemExit(f"{scala_path}: 自检失败 BB io {count_fields(bb_part)} != {len(ports)}")
    if count_fields(shell_part) != len(data_ports):
        raise SystemExit(f"{scala_path}: 自检失败 Shell io {count_fields(shell_part)} != {len(data_ports)}")
    links = len(re.findall(r"^\s+(?:bb\.io\.\w+ := |io\.\w+ := bb\.io\.\w+)", t, re.M))
    expect_links = len(ports)  # clk/rst 各 1 + 数据端口各 1
    if links != expect_links:
        raise SystemExit(f"{scala_path}: 自检失败连线 {links} != {expect_links}")
    if "extends BlackBox" not in t or "extends Module" not in t:
        raise SystemExit(f"{scala_path}: 自检失败 缺 BlackBox/Module")


def main() -> int:
    ap = argparse.ArgumentParser(description="XLS Verilog -> Chisel BlackBox wrapper（路线 3，一 module 一文件）")
    ap.add_argument("-o", "--out-dir", required=True, help="输出目录")
    ap.add_argument("--top", default=None,
                    help="--with-sim 时指明顶层 module 名（SimExt 对接顶层端口）；"
                         "单文件时可省略（取该文件唯一 module）")
    ap.add_argument("--with-sim", action="store_true",
                    help="追加生成 <TopCls>Sim.scala：svsim 仿真入口（Verilator + FST 波形），"
                         "全部输入模块 + --sim-extra 拼成一份内嵌 Verilog，自包含")
    ap.add_argument("--sim-extra", action="append", default=[],
                    help="--with-sim 时附加的内嵌 Verilog 源文件（可多次，如 xls_fifo_wrapper.sv）")
    ap.add_argument("--sim-stem", default=None,
                    help="--with-sim 的默认 workspace 名（out/rtl/<stem>），缺省取首个输入文件 stem")
    ap.add_argument("verilogs", nargs="+", help="XLS 生成的 .v 文件（一 module 一文件）")
    args = ap.parse_args()

    out_dir = pathlib.Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    ok = 0
    tops: list[str] = []
    vps: list[pathlib.Path] = []
    for v in args.verilogs:
        vp = pathlib.Path(v)
        if not vp.exists():
            print(f"❌ {v}: 文件不存在", file=sys.stderr)
            return 1
        vps.append(vp)
        scala = emit(vp, out_dir)
        name, ports = parse_module(vp.read_text(encoding="utf-8"), v)
        self_check(scala, ports)
        data = sum(1 for p in ports if not p.is_clk and not p.is_rst)
        print(f"✅ {vp.name} (module {name}, {len(ports)} 端口) -> {scala} "
              f"[BB io={len(ports)}, Shell 数据端口={data}]")
        tops.append(name)
        ok += 1

    if args.with_sim:
        if len(vps) > 1 and not args.top:
            raise SystemExit("--with-sim 多文件时必须 --top 指定顶层 module")
        top = args.top or tops[0]
        if top not in tops:
            raise SystemExit(f"--top {top} 不在输入模块里（有：{', '.join(tops)}）")
        extras = [pathlib.Path(x) for x in args.sim_extra]
        for x in extras:
            if not x.exists():
                print(f"❌ --sim-extra {x}: 文件不存在", file=sys.stderr)
                return 1
        stem = args.sim_stem or vps[0].stem
        sim = emit_sim(vps, out_dir, top, extras, stem)
        print(f"✅ -> {sim} [svsim FST 仿真入口（top={top}）：sbt \"rtl/runMain p4xlsrtl.{pascal(top)}Sim\"]")

    print(f"共 {ok} 个 wrapper，全部通过自检")
    return 0


if __name__ == "__main__":
    sys.exit(main())
