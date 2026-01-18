#!/usr/bin/env python3
"""
验证8GB G1堆的实际计算
基于实际调试结果：Region大小 = 4MB
"""

def verify_g1_calculations():
    print("=== 基于实际调试的G1堆计算验证 ===")
    
    # 实际验证的数据
    heap_size_gb = 8
    heap_size_bytes = heap_size_gb * 1024 * 1024 * 1024
    region_size_mb = 4  # 实际验证：4MB
    region_size_bytes = region_size_mb * 1024 * 1024
    
    print(f"堆大小: {heap_size_gb}GB = {heap_size_bytes:,} bytes")
    print(f"Region大小: {region_size_mb}MB = {region_size_bytes:,} bytes")
    
    # 计算Region数量
    total_regions = heap_size_bytes // region_size_bytes
    print(f"总Region数量: {total_regions:,}")
    
    # 验证
    total_heap_from_regions = total_regions * region_size_bytes
    print(f"验证: {total_regions} × {region_size_mb}MB = {total_heap_from_regions // (1024*1024*1024)}GB")
    
    # G1内存管理开销计算
    print("\n=== G1内存管理开销分析 ===")
    
    # Region管理结构
    region_metadata_size = 64  # 每个Region的元数据大小(估算)
    total_region_metadata = total_regions * region_metadata_size
    print(f"Region元数据: {total_regions} × {region_metadata_size}B = {total_region_metadata:,}B = {total_region_metadata/1024:.1f}KB")
    
    # Card Table (每512字节一个card)
    card_size = 512
    cards_per_region = region_size_bytes // card_size
    total_cards = total_regions * cards_per_region
    card_table_size = total_cards  # 每个card 1字节
    print(f"Card Table: {total_cards:,} cards × 1B = {card_table_size:,}B = {card_table_size/1024/1024:.1f}MB")
    
    # Remembered Set (粗略估算)
    rs_overhead_per_region = 1024  # 每个Region的RS开销
    total_rs_overhead = total_regions * rs_overhead_per_region
    print(f"Remembered Set: {total_regions} × {rs_overhead_per_region}B = {total_rs_overhead:,}B = {total_rs_overhead/1024/1024:.1f}MB")
    
    # 总开销
    total_overhead = total_region_metadata + card_table_size + total_rs_overhead
    overhead_percentage = (total_overhead / heap_size_bytes) * 100
    print(f"\n总G1管理开销: {total_overhead:,}B = {total_overhead/1024/1024:.1f}MB")
    print(f"开销百分比: {overhead_percentage:.3f}%")
    
    # 压缩指针分析
    print("\n=== 压缩指针分析 ===")
    heap_base = 0x0000000600000000  # 从调试输出获得
    print(f"堆基地址: 0x{heap_base:016x}")
    print(f"堆结束地址: 0x{heap_base + heap_size_bytes:016x}")
    print(f"地址范围: {heap_size_bytes/1024/1024/1024:.0f}GB")
    print("压缩指针模式: Zero based (从调试输出确认)")
    print("Oop shift amount: 3 (从调试输出确认)")
    
    return {
        'total_regions': total_regions,
        'region_size_mb': region_size_mb,
        'total_overhead_mb': total_overhead/1024/1024,
        'overhead_percentage': overhead_percentage
    }

if __name__ == "__main__":
    result = verify_g1_calculations()
    print(f"\n=== 关键数据总结 ===")
    print(f"Region数量: {result['total_regions']}")
    print(f"Region大小: {result['region_size_mb']}MB")
    print(f"管理开销: {result['total_overhead_mb']:.1f}MB ({result['overhead_percentage']:.3f}%)")