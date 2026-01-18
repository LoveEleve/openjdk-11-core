#!/usr/bin/env python3
# JVM 8GB堆内存参数精确计算

heap_size = 8 * 1024 * 1024 * 1024  # 8GB

# Region相关
region_size = 4 * 1024 * 1024  # 4MB
region_count = heap_size // region_size

# CardTable: 每512字节堆内存对应1字节
card_size = 512
cardtable_size = heap_size // card_size

# BOT: 与CardTable相同
bot_size = heap_size // card_size

# CMBitMap计算重点分析:
# mark_distance() = MinObjAlignmentInBytes * BitsPerByte = 8 * 8 = 64 bytes
# compute_size(heap_size) = heap_size / mark_distance()
# 
# 关键理解: 每64字节堆内存对应1位标记位
# 总位数 = heap_size / 64 = 8GB / 64 = 134,217,728 bits
# 字节数 = 总位数 / 8 = 16,777,216 bytes = 16MB
#
# 等价计算: heap_size / mark_distance = heap_size / 64 = 128MB (这是字节数!)
# 源码注释中说的128M是指 8GB / 64B = 128M，这里的M是数量单位，不是MB
# 实际: 128M / 8 = 16MB

mark_distance = 8 * 8  # 64 bytes
# 这里的关键是：compute_size返回的是字节数
# heap_size / mark_distance 返回的就是字节数(因为mark_distance表示多少字节对应一位)
bitmap_size_bytes = heap_size // mark_distance

# Card Counts: 与CardTable相同
card_counts_size = heap_size // card_size

print('=== JVM 8GB堆内存参数精确计算 ===')
print()
print(f'堆大小: {heap_size:,} bytes ({heap_size/1024/1024/1024:.2f} GB)')
print()
print('=== Region参数 ===')
print(f'Region大小: {region_size:,} bytes ({region_size/1024/1024:.0f} MB)')
print(f'Region数量: {region_count:,}')
print(f'LogOfHRGrainBytes: {int(region_size).bit_length() - 1}')
print(f'CardsPerRegion: {region_size // card_size:,}')
print()
print('=== 辅助数据结构 ===')
print(f'CardTable: {cardtable_size:,} bytes ({cardtable_size/1024/1024:.0f} MB)')
print(f'BOT: {bot_size:,} bytes ({bot_size/1024/1024:.0f} MB)')
print(f'Card Counts: {card_counts_size:,} bytes ({card_counts_size/1024/1024:.0f} MB)')
print()
print(f'CMBitMap mark_distance: {mark_distance} bytes')
print(f'CMBitMap compute_size结果: {bitmap_size_bytes:,} bytes ({bitmap_size_bytes/1024/1024:.0f} MB)')
print(f'CMBitMap 两个总计: {bitmap_size_bytes*2:,} bytes ({bitmap_size_bytes*2/1024/1024:.0f} MB)')
print()
print('=== 固定开销总计 ===')
fixed_overhead = cardtable_size + bot_size + bitmap_size_bytes*2 + card_counts_size
print(f'CardTable + BOT + CMBitMap×2 + CardCounts = {fixed_overhead:,} bytes ({fixed_overhead/1024/1024:.0f} MB)')
print(f'占堆比例: {fixed_overhead * 100 / heap_size:.3f}%')
print()
print('=== NMT验证对比 ===')
print('NMT GC mmap部分: 353,128,448 bytes (336.75 MB)')
print('NMT GC malloc部分: 39,498,639 bytes (37.68 MB)')
print('NMT GC总计: 392,627,087 bytes (374.43 MB)')
print()
print(f'我们计算的固定开销: {fixed_overhead:,} bytes ({fixed_overhead/1024/1024:.0f} MB)')
print(f'NMT mmap - 我们计算 = {353128448 - fixed_overhead:,} bytes')
