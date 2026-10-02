# packaging/ —— 黄金对拍工具链（官方 p4c + bmv2）的构建配方

**来源**：2026-10-01 从同级工程 `../p4x/packaging/` 收编（郝宇确认「检查 p4x
可取之处并合并」）。p4x 是非 git 的原型工程，收编后由本工程版本管理。
路径已适配本工程的**工程外同级 `../third_party/`** 布局。

**为什么保留**：A5 三级对拍（官方 p4c+bmv2 golden / IR 解释器 / XLS RTL）
的第一级依赖官方工具链。这套脚本固化了 macOS/arm64 上构建 p4c 与 bmv2 的
全部踩坑（见下），换机/重建时是唯一可复现的路径。

## 用法

```bash
# 前置：Homebrew 依赖
brew install cmake ninja bison autoconf automake libtool ccache jsoncpp \
  libevent nanomsg thrift xxhash libpcap bdw-gc boost@1.85 protobuf abseil gmp

./packaging/vendor-deps.sh          # 镜像预置 p4runtime（离线构建用）
./packaging/build-bmv2.sh           # behavioral-model  -> ../third_party/dist
./packaging/build-p4c.sh            # 官方 p4c + p4include -> ../third_party/dist
./packaging/make-dist.sh            # 写 VERSION / toolchains.json + 可重定位检查
```

源码来自 `../third_party/{p4c,behavioral-model}`，构建目录 `../third_party/.build/`，
安装前缀 `../third_party/dist/`（均在工程外，不进 IDE 索引、不进 git）。

## 固化下来的坑（脚本内已处理）

| 坑 | 处理 |
|---|---|
| bmv2 的 `dynamic_bitset.h` 只特化了 `find_lowest_bit<uint32_t/uint64_t>`；Apple clang 下 `uint64_t` 是 `unsigned long long`，与 `Block=unsigned long` 不同类型 → 链接期未定义符号 | `compat/bmv2_apple_portability.h`，用 `-include` 强制前置（上游 macOS 缺陷） |
| bmsim 是 OBJECT 库，PUBLIC 依赖不传到 bmall dylib，macOS ld 不允许 dylib 有未定义符号 | 显式把 `-lxxhash -ljsoncpp -lgmp -lpcap` 挂到 SHARED/EXE 链接行 |
| `ENABLE_BMV2` 依赖 `ENABLE_CONTROL_PLANE`，p4runtime/protobuf/abseil 绕不开 | 保留 CONTROL_PLANE=ON，p4runtime 走镜像预置 + `FETCHCONTENT_SOURCE_DIR_P4RUNTIME` |
| 系统 bison 版本过低 | `brew --prefix bison` 显式指定 `BISON_EXECUTABLE` |
| 调用官方 p4c 不需要 Python 驱动 | 注入 `P4C_16_INCLUDE_PATH`（见 `scripts/golden_sim.sh`） |
| dist 二进制引用 `/opt/homebrew` 绝对路径，不可移植 | `make-dist.sh` 输出非 `@rpath/@loader_path` 依赖清单，供 dylibbundler 收口 |

## 没收录的部分

- `build-p4chisel-jar.sh`：打的是旧 P4C 的 `p4chisel.jar`，本工程的 fat jar
  （`sbt cli/assembly` → `p4xls.jar`）已取代，**不再需要**。
- ~~`dist/` 预编译二进制（75MB）~~：原留在 `../p4x/dist`；**2026-10-02 p4x 目录删除，
  dist 迁至 `../third_party/dist`**（本工程通过 `P4X_GOLDEN_DIST` 引用；驱动脚本
  `scripts/golden_sim.{sh,py}` 已自包含）。需要时用上面的脚本在此处重建。
