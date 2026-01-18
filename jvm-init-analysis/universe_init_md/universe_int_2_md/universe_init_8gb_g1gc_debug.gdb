# ===================================================================
# GDB调试脚本：深度分析8GB G1GC配置下的universe_init()函数
# 配置：-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages
# ===================================================================

set confirm off
set pagination off
set print pretty on
set print array on
set print elements 0

# 启动程序并传递8GB G1GC参数
file /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java
set args -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -XX:+PrintGCDetails -XX:+UnlockDiagnosticVMOptions -XX:+LogVMOutput HelloWorld

# 设置断点
break universe_init
break Universe::initialize_heap
break G1CollectedHeap::initialize
break Universe::reserve_heap
break JavaClasses::compute_hard_coded_offsets

echo ===== 启动JVM并运行到universe_init() =====\n
run

echo ===== 1. universe_init()函数入口分析 =====\n
echo 函数地址: 
p &universe_init
echo 当前栈帧:
bt 3

echo ===== 2. 分析JavaClasses::compute_hard_coded_offsets() =====\n
continue
echo 硬编码偏移量计算前的状态:
p java_lang_String::value_offset
p java_lang_String::hash_offset
p java_lang_ref_Reference::referent_offset

# 执行硬编码偏移量计算
next
echo 硬编码偏移量计算后的状态:
p java_lang_String::value_offset
p java_lang_String::hash_offset
p java_lang_ref_Reference::referent_offset

echo ===== 3. 分析Universe::initialize_heap()调用 =====\n
continue
echo 堆初始化前的Universe状态:
p Universe::_collectedHeap
p Universe::_heap_base
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift

echo ===== 4. 深度分析G1CollectedHeap::initialize() =====\n
continue
echo G1堆初始化参数:
p this
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
echo 内存预留参数:
echo 请求的堆大小: 
p max_byte_size
echo 堆对齐要求:
p heap_alignment

# 执行内存预留
next
echo 预留内存结果:
p heap_rs
p heap_rs.base()
p heap_rs.size()

echo ===== 6. 分析压缩指针配置(8GB堆) =====\n
# 8GB堆应该使用ZeroBased模式
echo 压缩指针配置分析:
p UseCompressedOops
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift
p Universe::_narrow_oop_use_implicit_null_checks

echo ===== 7. 分析G1CollectedHeap对象创建 =====\n
# 继续执行到堆对象完全初始化
continue
finish
echo G1CollectedHeap对象状态:
p Universe::_collectedHeap
p ((G1CollectedHeap*)Universe::_collectedHeap)->_hrm
p ((G1CollectedHeap*)Universe::_collectedHeap)->_g1_policy

echo ===== 8. 分析LatestMethodCache创建(6个缓存) =====\n
# 回到universe_init继续执行
continue
echo LatestMethodCache创建前:
p Universe::_finalizer_register_cache
p Universe::_loader_addClass_cache
p Universe::_pd_implies_cache

# 执行到LatestMethodCache创建完成
next 10
echo LatestMethodCache创建后:
p Universe::_finalizer_register_cache
p Universe::_loader_addClass_cache  
p Universe::_pd_implies_cache
p Universe::_throw_illegal_access_error_cache
p Universe::_throw_no_such_method_error_cache
p Universe::_do_stack_walk_cache

echo ===== 9. 分析SystemDictionary和OopStorage =====\n
echo SystemDictionary OopStorage状态:
p SystemDictionary::_vm_weak_oop_storage
p SystemDictionary::_vm_global_oop_storage

echo ===== 10. 分析Metaspace全局初始化 =====\n
echo Metaspace配置:
p MetaspaceGC::_capacity_until_GC
p Metaspace::_class_space_list
p CompressedClassSpaceSize

echo ===== 11. 分析符号表和字符串表创建 =====\n
echo 符号表状态:
p SymbolTable::_the_table
echo 字符串表状态:
p StringTable::_the_table

echo ===== 12. 最终状态检查 =====\n
continue
echo universe_init()完成后的最终状态:
p Universe::_fully_initialized
p Universe::_collectedHeap
p Universe::_heap_base
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift

echo ===== 13. 内存布局分析 =====\n
echo 堆内存布局:
info proc mappings
echo 堆基地址:
p Universe::_heap_base
echo 压缩指针基址:
p Universe::_narrow_oop_base

echo ===== 14. 性能计数器检查 =====\n
echo G1GC性能计数器:
p PerfDataManager::_all

echo ===== 调试完成 =====\n
quit