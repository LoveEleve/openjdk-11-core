# ===================================================================
# GDB调试脚本：深度分析8GB G1GC配置下的universe_init()函数 (修复版)
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

# 设置断点
break universe_init
break Universe::initialize_heap
break G1CollectedHeap::initialize
break Universe::reserve_heap

echo ===== 启动JVM并运行到universe_init() =====\n
run

echo ===== 1. universe_init()函数入口分析 =====\n
echo 函数地址: 
p &universe_init
echo 当前栈帧:
bt 3

echo ===== 2. 分析JavaClasses::compute_hard_coded_offsets() =====\n
# 单步执行到硬编码偏移量计算
next 10
echo 执行硬编码偏移量计算...

echo ===== 3. 分析Universe::initialize_heap()调用 =====\n
continue
echo 堆初始化前的Universe状态:
p Universe::_collectedHeap

echo ===== 4. 深度分析G1CollectedHeap::initialize() =====\n
continue
echo G1堆初始化参数:
p this
echo 获取堆大小参数...
next 5
echo 初始堆大小(Xms):
p init_byte_size
echo 最大堆大小(Xmx):
p max_byte_size
echo 堆对齐大小:
p heap_alignment

# 分析HeapRegion配置
echo G1 HeapRegion配置:
p HeapRegion::GrainBytes
p HeapRegion::LogOfHRGrainBytes

echo ===== 5. 分析Universe::reserve_heap()内存预留 =====\n
continue
echo 内存预留参数分析...

# 执行内存预留并检查结果
next 5
echo 预留内存结果:
p heap_rs
echo 堆基地址:
p heap_rs.base()
echo 堆大小:
p heap_rs.size()

echo ===== 6. 分析压缩指针配置(8GB堆) =====\n
# 8GB堆应该使用ZeroBased模式
echo 压缩指针配置分析:
p UseCompressedOops

echo ===== 7. 继续执行到堆初始化完成 =====\n
continue
finish

echo ===== 8. 检查最终的Universe状态 =====\n
echo G1CollectedHeap对象状态:
p Universe::_collectedHeap

echo ===== 9. 回到universe_init继续分析LatestMethodCache =====\n
continue
echo 继续执行universe_init...
next 20

echo LatestMethodCache创建状态:
p Universe::_finalizer_register_cache
p Universe::_loader_addClass_cache
p Universe::_pd_implies_cache

echo ===== 10. 分析符号表创建 =====\n
next 10
echo 符号表创建完成

echo ===== 11. 最终状态检查 =====\n
continue
echo universe_init()完成后的最终状态:
p Universe::_collectedHeap

echo ===== 12. 内存映射检查 =====\n
echo 检查进程内存映射:
shell cat /proc/$$(pgrep -f "HelloWorld")/maps | grep heap || echo "堆内存映射信息"

echo ===== 调试完成 =====\n
quit