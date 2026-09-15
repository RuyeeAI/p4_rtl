// 诊断用最小目标：不依赖任何 //xls/* 包。
//
// 目的：判断 `@llvm-project` 的 label 解析错误到底来自哪里。
//   - 若本目标能构建成功 → 问题出在某个 //xls/* 包的 BUILD 文件
//   - 若本目标也失败     → 问题在外部 module / toolchain，与 XLS 源码无关
int main() { return 0; }
