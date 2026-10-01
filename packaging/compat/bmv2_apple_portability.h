// bmv2 在 macOS/arm64 上的可移植性垫片（不改动 p4lang 上游源码）。
//
// 问题：include/bm/bm_sim/dynamic_bitset.h 里
//     using Block = unsigned long;
// 但只特化了 find_lowest_bit<uint32_t> / <uint64_t>。Apple clang 下
// `uint64_t` 是 `unsigned long long`，与 `unsigned long` 不是同一类型，
// 于是 `find_lowest_bit<unsigned long>` 只有声明没有定义 → 链接期
// "Undefined symbols ... find_lowest_bit<unsigned long>"。
// （Linux 上 `uint64_t` 恰为 `unsigned long`，故不触发；这是上游 macOS 构建缺陷。）
//
// 做法：用 -include 强制先包含本文件，提前提供缺失的特化。因为在 dynamic_bitset.h
// 之前声明，且语义等价（最低置位下标），后续使用会命中本特化。
// 仅在 `unsigned long` 与 `uint64_t` 确实不同的平台上启用（macOS），
// 避免在 Linux 上与上游特化冲突。
#if defined(__APPLE__) && !defined(__LP64_TYPE_SHIM_GUARD)
#define __LP64_TYPE_SHIM_GUARD

#include <cstdint>
#include <type_traits>

namespace bm {

template <typename T>
inline int find_lowest_bit(T v);

// 仅当 unsigned long 与 uint64_t 不同型时才补特化（macOS/arm64 成立）
static_assert(!std::is_same<unsigned long, std::uint64_t>::value,
              "this shim is only needed when unsigned long != uint64_t");

template <>
inline int find_lowest_bit<unsigned long>(unsigned long v) {
  return __builtin_ctzl(v);
}

}  // namespace bm

#endif  // __APPLE__
