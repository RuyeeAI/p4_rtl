// xls_harness/ir_check_main.cc
//
// A1 验证件：用 XLS 官方 parser 校验 IR 文本。
//
// 为什么需要它
//   在此之前的 A0 门禁用的是纯 Python 复刻的 XLS scanner/parser 规则
//   （scripts/xls_ir_lint.py），准确率在 fn 子集上 94.9%。那是权宜之计。
//   本工具直接链接 XLS 的 //xls/ir:ir_parser，是**权威判定**：
//   解析通过 = XLS 一定能吃；解析失败 = XLS 一定不吃。
//
// 依赖裁剪
//   刻意只链接 ir_parser + ir，不碰 //xls/tools（那条链会拉 jit → LLVM，
//   首次数小时）。xls/ir/BUILD 与 xls/common/BUILD 中 llvm 出现 0 次，
//   所以这条闭包是轻的。
//
//   同理**不用** //xls/common/file:filesystem —— 该包的 BUILD 文件里有一个
//   cc_test 引用了 @llvm-project//clang:builtin_headers_gen。虽然那个测试
//   我们并不构建，但 Bazel 在**加载 BUILD 文件**时就会解析其中所有 label
//   的 repo 映射，于是会直接报：
//       No repository visible as '@llvm-project' from main repository
//   读文件用标准库 <fstream> 就够了，不为此引入 LLVM 依赖。
//
// 用法
//   ir_check_main [--json] [--quiet] [--roundtrip] <file.ir>...
//
//   退出码：0 = 全部通过；1 = 有失败；2 = 用法错误
//
// 片段处理
//   XLS 自带的 xls/ir/testdata/*.ir 是**无 package 头的片段**（对应
//   Parser::ParseFunction 的输入）。P4C 产出的是完整 package。本工具
//   先试整包，失败再按 function → proc → block 顺序试片段。

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_format.h"
#include "xls/common/init_xls.h"
#include "xls/ir/block.h"
#include "xls/ir/function.h"
#include "xls/ir/ir_parser.h"
#include "xls/ir/package.h"
#include "xls/ir/proc.h"

namespace {

struct CheckResult {
  std::string path;
  bool ok = false;
  bool roundtrip_ok = false;
  std::string roundtrip_note;
  std::string kind;  // package / function / proc / block
  std::string error;
  int64_t n_functions = 0;
  int64_t n_procs = 0;
  int64_t n_blocks = 0;
  int64_t n_channels = 0;
  int64_t dumped_bytes = 0;
  std::vector<std::string> function_names;
};

// 自实现的 JSON 字符串转义。
// 不用 absl::CEscape：它在 absl/strings/escaping.h 里，引入它就得多挂一个
// Bazel 依赖（@abseil-cpp//absl/strings:escaping）；而这里只需要处理
// JSON 必须转义的那几个字符，自己写反而更省。
std::string JsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (unsigned char c : s) {
    switch (c) {
      case '"':
        out += "\\\"";
        break;
      case '\\':
        out += "\\\\";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      case '\t':
        out += "\\t";
        break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  return out;
}

void FillFromPackage(const xls::Package& p, CheckResult* r) {
  r->n_functions = static_cast<int64_t>(p.functions().size());
  r->n_procs = static_cast<int64_t>(p.procs().size());
  r->n_blocks = static_cast<int64_t>(p.blocks().size());
  r->n_channels = static_cast<int64_t>(p.channels().size());
  for (const auto& f : p.functions()) {
    r->function_names.push_back(std::string(f->name()));
  }
  for (const auto& pr : p.procs()) {
    r->function_names.push_back("proc:" + std::string(pr->name()));
  }
}

// 解析后 dump 再解析，比对两次 dump 是否一致。
// 不一致说明 IR 在往返过程中丢信息，下游 codegen 会有隐患。
void DoRoundTrip(const xls::Package& p, CheckResult* r) {
  std::string dumped = p.DumpIr();
  r->dumped_bytes = static_cast<int64_t>(dumped.size());
  auto pkg2 = xls::Parser::ParsePackage(dumped, r->path + "(roundtrip)");
  if (!pkg2.ok()) {
    r->roundtrip_ok = false;
    r->roundtrip_note = "二次解析失败: " + std::string(pkg2.status().message());
    return;
  }
  std::string dumped2 = (*pkg2)->DumpIr();
  if (dumped != dumped2) {
    r->roundtrip_ok = false;
    r->roundtrip_note =
        absl::StrFormat("dump 不稳定：第一次 %d 字节，第二次 %d 字节",
                        dumped.size(), dumped2.size());
    return;
  }
  r->roundtrip_ok = true;
}

// 用标准库读文件。不用 xls::GetFileContents 是为了避开 //xls/common/file
// 那个包（原因见文件头注释）。
bool ReadFileText(const std::string& path, std::string* out, std::string* err) {
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    *err = "无法打开文件";
    return false;
  }
  std::ostringstream ss;
  ss << in.rdbuf();
  if (in.bad()) {
    *err = "读取过程中出错";
    return false;
  }
  *out = ss.str();
  return true;
}

CheckResult CheckOne(const std::string& path, bool want_roundtrip) {
  CheckResult r;
  r.path = path;

  std::string text;
  std::string read_err;
  if (!ReadFileText(path, &text, &read_err)) {
    r.error = "读取文件失败: " + read_err;
    return r;
  }

  // ---- 1) 先按完整 package 解析 ----
  auto pkg_or = xls::Parser::ParsePackage(text, path);
  if (pkg_or.ok()) {
    r.ok = true;
    r.kind = "package";
    FillFromPackage(**pkg_or, &r);
    if (want_roundtrip) DoRoundTrip(**pkg_or, &r);
    return r;
  }
  std::string pkg_err = std::string(pkg_or.status().message());

  // ---- 2) 退回片段：function → proc → block ----
  auto frag = std::make_unique<xls::Package>("fragment");
  auto f_or = xls::Parser::ParseFunction(text, frag.get());
  if (f_or.ok()) {
    r.ok = true;
    r.kind = "function";
    FillFromPackage(*frag, &r);
    if (want_roundtrip) DoRoundTrip(*frag, &r);
    return r;
  }
  auto pr_or = xls::Parser::ParseProc(text, frag.get());
  if (pr_or.ok()) {
    r.ok = true;
    r.kind = "proc";
    FillFromPackage(*frag, &r);
    if (want_roundtrip) DoRoundTrip(*frag, &r);
    return r;
  }
  auto b_or = xls::Parser::ParseBlock(text, frag.get());
  if (b_or.ok()) {
    r.ok = true;
    r.kind = "block";
    FillFromPackage(*frag, &r);
    if (want_roundtrip) DoRoundTrip(*frag, &r);
    return r;
  }

  // 官方新增的 scheduled_* 顶层形态（带调度信息的 IR 变体）。
  // P4C 不生成这种形态，但 XLS 自带测试里有，补上以免误报。
  auto sf_or = xls::Parser::ParseScheduledFunction(text, frag.get());
  if (sf_or.ok()) {
    r.ok = true;
    r.kind = "scheduled_fn";
    FillFromPackage(*frag, &r);
    if (want_roundtrip) DoRoundTrip(*frag, &r);
    return r;
  }
  auto sp_or = xls::Parser::ParseScheduledProc(text, frag.get());
  if (sp_or.ok()) {
    r.ok = true;
    r.kind = "scheduled_proc";
    FillFromPackage(*frag, &r);
    if (want_roundtrip) DoRoundTrip(*frag, &r);
    return r;
  }
  auto sb_or = xls::Parser::ParseScheduledBlock(text, frag.get());
  if (sb_or.ok()) {
    r.ok = true;
    r.kind = "scheduled_block";
    FillFromPackage(*frag, &r);
    if (want_roundtrip) DoRoundTrip(*frag, &r);
    return r;
  }

  // 全失败：报整包错误（对完整文件更相关），并附片段错误
  r.error = absl::StrCat(pkg_err, " | 片段尝试: fn=",
                         f_or.status().message(), " proc=",
                         pr_or.status().message(),
                         " block=", b_or.status().message(),
                         " sfn=", sf_or.status().message(),
                         " sproc=", sp_or.status().message(),
                         " sblock=", sb_or.status().message());
  return r;
}

void PrintUsage(const char* argv0) {
  std::cerr << "用法: " << argv0
            << " [--json] [--quiet] [--roundtrip] <file.ir>...\n"
            << "  --json       以 JSON 输出结果\n"
            << "  --quiet      只输出汇总\n"
            << "  --roundtrip  额外做 dump→reparse 往返一致性检查\n";
}

}  // namespace

int main(int argc, char** argv) {
  std::vector<std::string> files;
  bool json = false;
  bool quiet = false;
  bool roundtrip = false;

  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    if (a == "--json") {
      json = true;
    } else if (a == "--quiet") {
      quiet = true;
    } else if (a == "--roundtrip") {
      roundtrip = true;
    } else if (a == "-h" || a == "--help") {
      PrintUsage(argv[0]);
      return 0;
    } else if (a.rfind("--", 0) == 0) {
      std::cerr << "未知参数: " << a << "\n";
      PrintUsage(argv[0]);
      return 2;
    } else {
      files.push_back(a);
    }
  }

  if (files.empty()) {
    PrintUsage(argv[0]);
    return 2;
  }

  std::vector<CheckResult> results;
  results.reserve(files.size());
  for (const std::string& f : files) {
    results.push_back(CheckOne(f, roundtrip));
  }

  int64_t n_ok = 0;
  for (const CheckResult& r : results) {
    if (r.ok) ++n_ok;
  }

  if (json) {
    std::cout << "[\n";
    for (size_t i = 0; i < results.size(); ++i) {
      const CheckResult& r = results[i];
      std::cout << "  {\n"
                << "    \"path\": \"" << JsonEscape(r.path) << "\",\n"
                << "    \"ok\": " << (r.ok ? "true" : "false") << ",\n"
                << "    \"kind\": \"" << r.kind << "\",\n"
                << "    \"functions\": " << r.n_functions << ",\n"
                << "    \"procs\": " << r.n_procs << ",\n"
                << "    \"blocks\": " << r.n_blocks << ",\n"
                << "    \"channels\": " << r.n_channels << ",\n"
                << "    \"dumped_bytes\": " << r.dumped_bytes << ",\n"
                << "    \"roundtrip_ok\": "
                << (r.roundtrip_ok ? "true" : "false") << ",\n"
                << "    \"roundtrip_note\": \"" << JsonEscape(r.roundtrip_note)
                << "\",\n"
                << "    \"error\": \"" << JsonEscape(r.error) << "\"\n"
                << "  }" << (i + 1 < results.size() ? "," : "") << "\n";
    }
    std::cout << "]\n";
  } else {
    for (const CheckResult& r : results) {
      if (quiet && r.ok) continue;
      if (r.ok) {
        std::string rt = "";
        if (roundtrip) {
          rt = r.roundtrip_ok ? "  roundtrip=OK" : "  roundtrip=FAIL";
        }
        std::cout << absl::StrFormat(
                         "OK    %-60s kind=%-8s fn=%d proc=%d blk=%d chan=%d "
                         "dump=%dB%s\n",
                         r.path.substr(r.path.find_last_of('/') + 1),
                         r.kind, r.n_functions, r.n_procs, r.n_blocks,
                         r.n_channels, r.dumped_bytes, rt);
        if (roundtrip && !r.roundtrip_ok && !r.roundtrip_note.empty()) {
          std::cout << "      └─ " << r.roundtrip_note << "\n";
        }
      } else {
        std::cout << absl::StrFormat(
                         "FAIL  %-60s\n",
                         r.path.substr(r.path.find_last_of('/') + 1));
        std::cout << "      └─ " << r.error << "\n";
      }
    }
  }

  if (!quiet) {
    std::cout << "\n通过 " << n_ok << "/" << results.size() << "\n";
  } else {
    std::cout << "通过 " << n_ok << "/" << results.size() << "\n";
  }

  return n_ok == static_cast<int64_t>(results.size()) ? 0 : 1;
}
