#!/usr/bin/env python3

heap_base = 0x0000000600000000
heap_size = 8 * 1024 * 1024 * 1024
shift = 3

print('=== 8GB G1堆压缩指针验证 ===')
print(f'堆基地址: 0x{heap_base:016x}')
print(f'堆大小: {heap_size // (1024*1024*1024)}GB')
print(f'位移量: {shift}')

test_addresses = [
    ('堆起始', heap_base),
    ('第一个对象', heap_base + 8),
    ('1MB位置', heap_base + 1024*1024),
    ('4GB位置', heap_base + 4*1024*1024*1024),
    ('堆结束-8', heap_base + heap_size - 8)
]

print('\n地址压缩映射:')
for name, addr in test_addresses:
    compressed = (addr - heap_base) >> shift
    decompressed = heap_base + (compressed << shift)
    print(f'{name:12s}: 0x{addr:016x} → 0x{compressed:08x} → 0x{decompressed:016x}')

max_compressed = 0xFFFFFFFF
max_addressable = heap_base + (max_compressed << shift)
max_heap_size = (max_compressed << shift) // (1024*1024*1024)

print(f'\n32位压缩指针寻址能力:')
print(f'最大压缩值: 0x{max_compressed:08x}')
print(f'最大可寻址: 0x{max_addressable:016x}')
print(f'最大堆大小: {max_heap_size}GB')
print(f'当前堆利用率: {8/max_heap_size*100:.1f}%')