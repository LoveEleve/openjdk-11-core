#!/usr/bin/env python3
"""
最终验证G1CMBitMap大小计算
"""

# 常量
HeapWordSize = 8  # bytes
LogHeapWordSize = 3
ObjectAlignmentInBytes = 8
MinObjAlignmentInBytes = ObjectAlignmentInBytes  # 8
MinObjAlignment = MinObjAlignmentInBytes // HeapWordSize  # 1
LogMinObjAlignmentInBytes = 3  # log2(8)
LogMinObjAlignment = LogMinObjAlignmentInBytes - LogHeapWordSize  # 3 - 3 = 0
BitsPerByte = 8

heap_size_bytes = 8 * 1024 * 1024 * 1024  # 8GB
heap_size_words = heap_size_bytes // HeapWordSize  # 1G words

print("=== 常量验证 ===")
print(f"HeapWordSize = {HeapWordSize}")
print(f"MinObjAlignmentInBytes = {MinObjAlignmentInBytes}")
print(f"MinObjAlignment = {MinObjAlignment}")
print(f"LogMinObjAlignment = {LogMinObjAlignment}")
print()

# G1CMBitMap构造函数中: _shifter(LogMinObjAlignment)
_shifter = LogMinObjAlignment  # 0

# initialize函数中:
# _bm = BitMapView(..., _covered.word_size() >> _shifter)
# 位图位数 = heap_word_size >> _shifter = heap_word_size >> 0 = heap_word_size
bitmap_bits = heap_size_words >> _shifter
bitmap_bytes = bitmap_bits // 8

print("=== 位图大小计算 (从initialize函数) ===")
print(f"heap_size_words = {heap_size_words:,} = {heap_size_words / 1024 / 1024 / 1024:.0f}G words")
print(f"_shifter = {_shifter}")
print(f"位图位数 = heap_size_words >> {_shifter} = {bitmap_bits:,} bits")
print(f"位图字节数 = {bitmap_bytes:,} bytes = {bitmap_bytes / 1024 / 1024:.0f} MB")
print()

# compute_size函数验证
# mark_distance() = MinObjAlignmentInBytes * BitsPerByte = 8 * 8 = 64
# compute_size(heap_size) = heap_size / mark_distance() = 8GB / 64 = 128MB
mark_distance = MinObjAlignmentInBytes * BitsPerByte  # 64
compute_size_result = heap_size_bytes // mark_distance

print("=== compute_size计算 ===")
print(f"mark_distance() = {MinObjAlignmentInBytes} * {BitsPerByte} = {mark_distance}")
print(f"compute_size(8GB) = 8GB / {mark_distance} = {compute_size_result:,} bytes = {compute_size_result / 1024 / 1024:.0f} MB")
print()

# 验证两种计算是否一致
print("=== 验证一致性 ===")
print(f"从initialize计算: {bitmap_bytes:,} bytes = {bitmap_bytes / 1024 / 1024:.0f} MB")
print(f"从compute_size计算: {compute_size_result:,} bytes = {compute_size_result / 1024 / 1024:.0f} MB")
print(f"一致性: {'✓' if bitmap_bytes == compute_size_result else '✗'}")
print()

# 最终GC辅助结构大小
cardtable = 16 * 1024 * 1024
bot = 16 * 1024 * 1024
cardcounts = 16 * 1024 * 1024
bitmap = compute_size_result

print("=== G1 GC辅助数据结构 (8GB堆) ===")
print(f"CardTable:     {cardtable:>15,} bytes = {cardtable/1024/1024:>6.0f} MB")
print(f"BOT:           {bot:>15,} bytes = {bot/1024/1024:>6.0f} MB")
print(f"CardCounts:    {cardcounts:>15,} bytes = {cardcounts/1024/1024:>6.0f} MB")
print(f"PrevBitmap:    {bitmap:>15,} bytes = {bitmap/1024/1024:>6.0f} MB")
print(f"NextBitmap:    {bitmap:>15,} bytes = {bitmap/1024/1024:>6.0f} MB")
print("-" * 50)
total = cardtable + bot + cardcounts + bitmap * 2
print(f"固定总计:      {total:>15,} bytes = {total/1024/1024:>6.0f} MB")
print(f"占堆比例:      {total * 100 / heap_size_bytes:>14.2f}%")
print()

# NMT验证
print("=== NMT验证 ===")
nmt_gc_mmap = 353128448
print(f"NMT GC mmap:   {nmt_gc_mmap:>15,} bytes = {nmt_gc_mmap/1024/1024:>6.2f} MB")
print(f"我们计算:      {total:>15,} bytes = {total/1024/1024:>6.0f} MB")
print(f"差异:          {nmt_gc_mmap - total:>15,} bytes = {(nmt_gc_mmap-total)/1024/1024:>6.2f} MB")
print(f"差异用于: HeapRegion对象、管理结构、对齐填充等")
