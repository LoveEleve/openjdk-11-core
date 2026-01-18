# ===================================================================
# GDB调试脚本：准确分析8GB G1GC配置下的universe_init()函数 (修复版)
# 重点验证HeapRegion大小和所有初始化对象的详细属性
# 配置：-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages
# ===================================================================

set confirm off
set pagination off
set print pretty on
set print array on
set print elements 0

# 启动程序并传递8GB G1GC参数
file /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java
set args -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages HelloWorld

# 设置关键断点
break universe_init
break Universe::initialize_heap
break G1CollectedHeap::initialize
break HeapRegion::setup_heap_region_size

echo ===== 启动JVM并运行到universe_init() =====\n
run

echo ===== 1. universe_init()函数入口详细分析 =====\n
echo 函数地址: 
p &universe_init
echo 当前调用栈:
bt 3

echo ===== 2. 执行JavaClasses::compute_hard_coded_offsets() =====\n
next 10
echo 硬编码偏移量计算完成

echo ===== 3. Universe::initialize_heap()堆初始化分析 =====\n
continue
echo 堆初始化开始...

echo ===== 4. HeapRegion::setup_heap_region_size()关键分析 =====\n
continue
echo HeapRegion大小设置参数:
p initial_heap_size
p max_heap_size
echo 计算平均堆大小:
set $average_heap_size = (initial_heap_size + max_heap_size) / 2
p $average_heap_size

# 继续执行到region_size计算完成
next 15
echo 最终设置的HeapRegion参数:
p HeapRegion::GrainBytes
p HeapRegion::LogOfHRGrainBytes
echo Region大小(MB):
set $region_size_mb = HeapRegion::GrainBytes / (1024 * 1024)
p $region_size_mb
echo 8GB堆的总区域数:
set $total_regions = (8 * 1024 * 1024 * 1024) / HeapRegion::GrainBytes
p $total_regions

echo ===== 5. G1CollectedHeap::initialize()详细分析 =====\n
continue
echo G1堆初始化参数:
next 5
echo 堆大小配置:
p init_byte_size
p max_byte_size
p heap_alignment

echo ===== 6. 继续完成堆初始化 =====\n
continue
finish
echo 堆初始化完成后状态:
p Universe::_collectedHeap

echo ===== 7. 压缩指针详细配置分析 =====\n
echo 压缩指针状态:
p UseCompressedOops
echo 压缩指针参数:
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift

echo ===== 8. 继续执行universe_init的其余部分 =====\n
continue
echo SystemDictionary初始化...
next 5

echo Metaspace初始化...
next 5

echo LatestMethodCache创建...
next 10
echo LatestMethodCache创建完成:
p Universe::_finalizer_register_cache
p Universe::_loader_addClass_cache
p Universe::_pd_implies_cache

echo 符号表创建...
next 10

echo ===== 9. 最终状态完整检查 =====\n
continue
echo universe_init()完成后的完整状态:
echo Universe全局状态:
p Universe::_collectedHeap

echo HeapRegion配置最终验证:
echo Region大小(字节): 
p HeapRegion::GrainBytes
echo Region大小(MB):
set $region_size_mb = HeapRegion::GrainBytes / (1024 * 1024)
p $region_size_mb
echo 8GB堆总区域数:
set $total_regions = (8ULL * 1024 * 1024 * 1024) / HeapRegion::GrainBytes
p $total_regions

echo ===== 调试完成 =====\n
quit