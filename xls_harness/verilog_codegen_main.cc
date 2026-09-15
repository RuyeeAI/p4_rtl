// xls_harness/verilog_codegen_main.cc
//
// 自建最小 Verilog codegen 驱动（M0 用）。
//
// ============================ 为什么不用官方 codegen_main ============================
// 一路踩下来的依赖链是：
//
//   //xls/tools:codegen_main        ← 官方用户工具
//     → //xls/tools:codegen          ← lib
//       → //xls/fdo:synthesizer      ← 反馈驱动优化（FDO）
//         → //xls/synthesis/yosys:yosys_synthesis_service
//           → @at_clifford_yosys//:json11   ← 需要 Yosys 与 json11
//
// 也就是说，只要链接 `//xls/tools:codegen`，就会被迫引入 Yosys。
//
// **真正的干净入口在 `//xls/codegen_v_1_5:codegen`** —— 实测它的 BUILD 里
// fdo / yosys / llvm / jit 出现次数**均为 0**，而且它正好是新式 proc
// （proc-scoped channels）走的 codegen 路径。所以本驱动直接链接它。
//
// 这也是 A1 已验证有效的模式：**不碰 //xls/tools 的 *_main，自己写最小驱动。**
//
// ============================ 关于 delay estimator ============================
// `xls::codegen::Codegen()` 需要一个 `const DelayEstimator*`，且内部没有判空。
// 官方路径经 `GetDelayEstimator(name)` 工厂拿，而那个工厂会拉进各种工艺模型
// （正是需要 PDK 数据的部分）。
//
// 本驱动**自己实现一个 unit delay estimator**（每个 op 记 1ps），
// 与 M0 的目标一致：只关心"时序契约是否成立"，不关心面积/延迟数值。
//
// ============================ 用法 ============================
//   verilog_codegen_main <input.ir> --top=<name> --out=<path>
//                        [--stages=N] [--wct=N]
//
//   --stages=N  显式指定流水级数（不指定则由 XLS 自行推导）
//   --wct=N     显式指定 worst_case_throughput（启动间隔 II 的上界）
//               注意上游的坑：0 = 不约束（让调度器自选），nullopt = 必须为 1
//   --clock-period-ps=N  显式指定时钟周期。不指定时 XLS 会用"关键路径"作为
//               周期并走「最小周期搜索」路径（正是我们遇到的那条报错路径）
//   --v=N       透传给 absl 的日志级别（VLOG）
//
// 为什么需要 --wct：proc 里 send→receive 串在一个 token 链上并回写 state，
// 调度器会判定 II=1 不可达 —— 报 "cannot achieve full throughput.
// Try `--worst_case_throughput=2`"。这是 M0 要实测的现象之一。

#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "absl/status/status.h"
#include "absl/strings/string_view.h"
#include "absl/status/statusor.h"
#include "xls/codegen/codegen_options.h"
#include "xls/codegen/codegen_result.h"
#include "xls/codegen_v_1_5/codegen.h"
#include "xls/common/init_xls.h"
#include "xls/estimators/delay_model/delay_estimator.h"
#include "xls/ir/ir_parser.h"
#include "xls/ir/node.h"
#include "xls/ir/package.h"
#include "xls/scheduling/scheduling_options.h"

namespace {

// 自实现的 unit delay estimator：每个 op 记 1ps。
//
// 为什么不用 xls::GetDelayEstimator("unit")：
//   那个工厂会把各工艺模型（含需要 PDK 数据的）一起拉进来。
//   而 M0 只需要"有个合法值让调度器跑起来"，1ps 与真实值无差别。
class UnitDelayEstimator : public xls::DelayEstimator {
 public:
  UnitDelayEstimator() : xls::DelayEstimator("unit_by_p4xls") {}

  absl::StatusOr<int64_t> GetOperationDelayInPs(xls::Node* node) const override {
    (void)node;
    return 1;
  }
};

bool ReadFileText(const std::string& path, std::string* out, std::string* err) {
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    *err = "无法打开文件: " + path;
    return false;
  }
  std::ostringstream ss;
  ss << in.rdbuf();
  if (in.bad()) {
    *err = "读取出错: " + path;
    return false;
  }
  *out = ss.str();
  return true;
}

bool WriteFileText(const std::string& path, const std::string& text,
                   std::string* err) {
  std::ofstream out(path, std::ios::binary);
  if (!out) {
    *err = "无法写入文件: " + path;
    return false;
  }
  out << text;
  if (!out.good()) {
    *err = "写入出错: " + path;
    return false;
  }
  return true;
}

constexpr absl::string_view kUsage =
    "verilog_codegen_main <input.ir> --top=<name> --out=<path>\n"
    "  [--stages=N] [--wct=N] [--clock-period-ps=N] [--v=N]";

void PrintUsage(const char* argv0) {
  std::cerr << "用法: " << argv0
            << " <input.ir> --top=<name> --out=<path> [--stages=N] [--wct=N]\n";
}

}  // namespace

int main(int argc, char** argv) {
  std::string in_path;
  std::string top;
  std::string out_path;
  std::optional<int64_t> stages;             // 不指定则由 XLS 推导
  std::optional<int64_t> wct;                // worst_case_throughput
  std::optional<int64_t> clock_period_ps;    // 显式时钟周期

  // 先解析本驱动的参数，再把**只属于 absl 的**日志开关（--v 等）透传给
  // xls::InitXls()。顺序很关键：absl::ParseCommandLine 遇到不认识的非
  // absl flag（如 --top=）会直接报错退出，所以不能把整个 argv 丢给它。
  std::vector<std::string> absl_flags;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    if (a.rfind("--top=", 0) == 0) {
      top = a.substr(6);
    } else if (a.rfind("--out=", 0) == 0) {
      out_path = a.substr(6);
    } else if (a.rfind("--stages=", 0) == 0) {
      stages = std::stoll(a.substr(9));
    } else if (a.rfind("--wct=", 0) == 0) {
      wct = std::stoll(a.substr(6));
    } else if (a.rfind("--clock-period-ps=", 0) == 0) {
      clock_period_ps = std::stoll(a.substr(18));
    } else if (a == "-h" || a == "--help") {
      PrintUsage(argv[0]);
      return 0;
    } else if (a.rfind("--v=", 0) == 0 || a == "--logtostderr" ||
               a.rfind("--stderrthreshold=", 0) == 0 ||
               a.rfind("--vmodule=", 0) == 0) {
      absl_flags.push_back(a);  // 透传给 absl
    } else if (a.rfind("--", 0) == 0) {
      std::cerr << "未知参数: " << a << "\n";
      PrintUsage(argv[0]);
      return 2;
    } else {
      in_path = a;
    }
  }

  {
    std::vector<char*> passthrough;
    passthrough.push_back(argv[0]);
    for (std::string& s : absl_flags) {
      passthrough.push_back(s.data());
    }
    xls::InitXls(kUsage, static_cast<int>(passthrough.size()),
                 passthrough.data());
  }

  if (in_path.empty() || top.empty() || out_path.empty()) {
    PrintUsage(argv[0]);
    return 2;
  }

  std::string text;
  std::string err;
  if (!ReadFileText(in_path, &text, &err)) {
    std::cerr << "❌ " << err << "\n";
    return 1;
  }

  auto pkg_or = xls::Parser::ParsePackage(text, in_path);
  if (!pkg_or.ok()) {
    std::cerr << "❌ 解析失败: " << pkg_or.status().message() << "\n";
    return 1;
  }
  std::unique_ptr<xls::Package> pkg = std::move(pkg_or).value();

  // **必须显式设 top**：xls::codegen::Codegen() 内部走的是
  //   package->GetTop()  → GetTopAsBlock()  → ConvertBlockToVerilog()
  // 而 IR 文本里的 proc 并没有被标记为 top（除非写 `top proc ...`）。
  // 漏掉这一步 GetTop() 返回 nullopt，后面会在解引用时空指针崩溃
  // （实测 SIGSEGV，且没有任何错误信息，很难定位）。
  // 官方测试同样都是显式 SetTop / SetTopByName。
  if (absl::Status st = pkg->SetTopByName(top); !st.ok()) {
    std::cerr << "❌ 设置 top 失败（IR 里没有名为 " << top
              << " 的 fn/proc/block）: " << st.message() << "\n";
    return 1;
  }

  // 新式 proc（通道写在 proc 头的 chan_interface 里）需要 codegen v1.5。
  // 走 //xls/codegen_v_1_5:codegen 这条路正好就是 v1.5。
  const bool proc_scoped = pkg->ChannelsAreProcScoped();

  xls::verilog::CodegenOptions cg;
  cg.entry(top);              // 顶层实体名（proc 名）
  cg.module_name(top);        // 生成的 Verilog module 名
  cg.use_system_verilog(false);
  // **clock_name 必填**：codegen_v_1_5 的 scheduled_block_conversion pass
  // 对流水化的 block 会检查它，缺了直接报
  //   Clock name must be specified when generating a pipelined block
  // （scheduled_block_conversion_pass.cc:56-61）。
  // 官方 codegen_main 不报是因为它的 flag handler registry 会填默认值。
  cg.clock_name("clk");
  // 复位：同步、高有效。reset_data_path=true 表示数据通路寄存器也复位
  // （proc 的 state 寄存器需要复位到 init 值）。
  cg.reset("rst", /*asynchronous=*/false, /*active_low=*/false,
           /*reset_data_path=*/true);
  // proc 的 receive 需要门控（gate_recvs），否则 codegen 会把
  // receive 的数据无条件当作有效值使用。
  cg.gate_recvs(true);
  // 注意：不要在这里设 emit_as_pipeline(true)。xls::codegen::Codegen()
  // 检测到 top 是 proc 时会强制 emit_as_pipeline(false)（upstream 注释：
  // "Force using non-pretty printed Verilog when generating procs"），
  // 设了也会被覆盖，只会让意图混乱。

  xls::SchedulingOptions sched;
  if (stages.has_value()) {
    sched.pipeline_stages(*stages);
  }
  if (wct.has_value()) {
    sched.worst_case_throughput(*wct);
  }
  if (clock_period_ps.has_value()) {
    sched.clock_period_ps(*clock_period_ps);
  }

  UnitDelayEstimator delay_estimator;

  auto res_or = xls::codegen::Codegen(pkg.get(), cg, sched, &delay_estimator);
  if (!res_or.ok()) {
    std::cerr << "❌ codegen 失败: " << res_or.status().message() << "\n";
    return 1;
  }

  if (!WriteFileText(out_path, res_or->verilog_text, &err)) {
    std::cerr << "❌ " << err << "\n";
    return 1;
  }

  std::cout << "✅ Verilog 已生成: " << out_path << "  ("
            << res_or->verilog_text.size() << " 字节"
            << ", stages=" << (stages.has_value() ? std::to_string(*stages) : "auto")
            << ", wct=" << (wct.has_value() ? std::to_string(*wct) : "unset")
            << ", proc_scoped=" << (proc_scoped ? "true" : "false")
            << ", delay=unit)\n";
  return 0;
}
