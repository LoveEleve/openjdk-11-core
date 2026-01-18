#!/usr/bin/env python3
"""
验证G1CMBitMap::compute_size的计算逻辑
"""

heap_size = 8 * 1024 * 1024 * 1024  # 8GB

# mark_distance() = MinObjAlignmentInBytes * BitsPerByte = 8 * 8 = 64
# 这表示：每64字节堆内存对应1个标记位

# compute_size(heap_size) = heap_size / mark_distance()
# = 8GB / 64 = 134,217,728

# 关键问题：这个结果是什么？
# 源码注释说"每64字节对应1位"，那么：
# 8GB / 64B = 134,217,728 位 (bits)
# 转换为字节: 134,217,728 / 8 = 16,777,216 bytes = 16MB

# 但是！compute_size直接返回 heap_size / mark_distance()
# 让我们看看这个值：
mark_distance = 64
result = heap_size // mark_distance

print(f"heap_size / mark_distance = {result:,}")
print(f"以字节为单位: {result:,} bytes = {result / 1024 / 1024:.0f} MB")
print()

# 重新分析：
# mark_distance的含义是"两个标记之间的距离"（字节）
# compute_size返回的是位图大小（字节）
# 
# 如果每64字节对应1位：
#   总位数 = heap_size / 64 = 134,217,728 bits
#   字节数 = 134,217,728 / 8 = 16,777,216 bytes = 16MB
#
# 但源码直接返回 heap_size / 64 = 134,217,728
# 这意味着返回的是字节数，也就是每64字节对应1字节？
# 
# 不对！让我看看源码注释：
# "Returns the amount of bytes on the heap between two marks in the bitmap"
# mark_distance返回的是堆上两个标记之间的字节数
# 
# 如果每64字节对应1位：compute_size应该返回 (heap_size / 64) / 8
# 如果每64字节对应1字节：compute_size应该返回 heap_size / 64

# 从源码注释"128M"来看，似乎是直接返回 heap_size / 64 = 128M
# 这说明每64字节堆对应1字节位图？这不太合理...

# 让我从NMT验证：
# GC mmap: 353,128,448 bytes (337MB)
# CardTable: 16MB
# BOT: 16MB
# CardCounts: 16MB
# 固定开销: 48MB
# 剩余: 337 - 48 = 289MB
# 如果两个bitmap各128MB: 256MB, 还剩33MB用于其他

print("=== 根据NMT反推 ===")
nmt_gc_mmap = 353128448
cardtable = 16 * 1024 * 1024
bot = 16 * 1024 * 1024
cardcounts = 16 * 1024 * 1024
others = nmt_gc_mmap - cardtable - bot - cardcounts
print(f"NMT GC mmap: {nmt_gc_mmap:,} bytes ({nmt_gc_mmap/1024/1024:.2f} MB)")
print(f"CardTable + BOT + CardCounts: {(cardtable+bot+cardcounts):,} bytes ({(cardtable+bot+cardcounts)/1024/1024:.0f} MB)")
print(f"剩余给Bitmap等: {others:,} bytes ({others/1024/1024:.2f} MB)")
print()
print(f"如果两个bitmap各16MB: 32MB")
print(f"如果两个bitmap各128MB: 256MB")
print(f"实际剩余: {others/1024/1024:.2f} MB")
print()

# 验证结论：
# 实际剩余 ~289MB，接近256MB+一些其他开销
# 说明bitmap确实是128MB每个
print("=== 结论 ===")
print("G1CMBitMap::compute_size(8GB) = 128MB")
print("mark_distance=64意味着每64字节堆内存对应1字节位图")
print("(不是1位，是1字节！)")
print()
print("重新计算固定开销:")
bitmap_size = 128 * 1024 * 1024
fixed = cardtable + bot + cardcounts + bitmap_size * 2
print(f"CardTable: 16MB")
print(f"BOT: 16MB")
print(f"CardCounts: 16MB")
print(f"Bitmap × 2: 256MB")
print(f"总计: {fixed/1024/1024:.0f}MB ({fixed * 100 / heap_size:.2f}%)")
