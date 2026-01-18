# ===================================================================
# GDB调试脚本：准确分析8GB G1GC配置下的universe_init()函数
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
break JavaClasses::compute_hard_coded_offsets

echo ===== 启动JVM并运行到universe_init() =====\n
run

echo ===== 1. universe_init()函数入口详细分析 =====\n
echo 函数地址: 
p &universe_init
echo 当前调用栈:
bt 5
echo 当前线程信息:
info threads

echo ===== 2. JavaClasses::compute_hard_coded_offsets()详细分析 =====\n
continue
echo 硬编码偏移量计算开始...
echo 关键Java类字段偏移量:
# 这些偏移量在运行时计算
echo String类字段偏移量:
p java_lang_String::value_offset
p java_lang_String::hash_offset
echo Object类字段偏移量:
p java_lang_Object::klass_offset_in_bytes()
echo Class类字段偏移量:
p java_lang_Class::klass_offset
p java_lang_Class::array_klass_offset

echo ===== 3. Universe::initialize_heap()堆初始化分析 =====\n
continue
echo 堆初始化前Universe状态:
p Universe::_collectedHeap
p Universe::_heap_base
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift

echo ===== 4. HeapRegion::setup_heap_region_size()关键分析 =====\n
continue
echo HeapRegion大小设置参数:
p initial_heap_size
p max_heap_size
echo 计算平均堆大小:
p average_heap_size
echo HeapRegionBounds常量:
p HeapRegionBounds::min_size()
p HeapRegionBounds::max_size() 
p HeapRegionBounds::target_number()
echo 计算得出的region_size:
p region_size
next 10
echo 最终设置的HeapRegion参数:
p HeapRegion::GrainBytes
p HeapRegion::LogOfHRGrainBytes
p HeapRegion::GrainWords
p HeapRegion::CardsPerRegion

echo ===== 5. G1CollectedHeap::initialize()详细分析 =====\n
continue
echo G1堆初始化参数:
p this
echo 堆大小配置:
p init_byte_size
p max_byte_size
p heap_alignment
echo G1特有配置:
echo 计算总区域数:
set $total_regions = max_byte_size / HeapRegion::GrainBytes
p $total_regions
echo 区域管理器状态:
p this->_hrm
echo G1策略配置:
p this->_g1_policy

echo ===== 6. 压缩指针详细配置分析 =====\n
echo 压缩指针状态:
p UseCompressedOops
p UseCompressedClassPointers
echo 压缩指针参数:
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift
p Universe::_narrow_oop_use_implicit_null_checks
echo 压缩类指针参数:
p Universe::_narrow_klass_base
p Universe::_narrow_klass_shift

echo ===== 7. 继续完成堆初始化 =====\n
continue
finish
echo 堆初始化完成后状态:
p Universe::_collectedHeap
echo G1CollectedHeap对象详细信息:
p *((G1CollectedHeap*)Universe::_collectedHeap)
echo 堆区域管理器详细信息:
p ((G1CollectedHeap*)Universe::_collectedHeap)->_hrm
echo 区域数量和状态:
p ((G1CollectedHeap*)Universe::_collectedHeap)->_hrm._num_committed

echo ===== 8. SystemDictionary初始化分析 =====\n
continue
echo SystemDictionary初始化...
next 5
echo SystemDictionary状态:
p SystemDictionary::_dictionary
p SystemDictionary::_vm_weak_oop_storage
p SystemDictionary::_vm_global_oop_storage

echo ===== 9. Metaspace全局初始化分析 =====\n
continue
echo Metaspace初始化...
next 5
echo Metaspace配置:
p MetaspaceSize
p MaxMetaspaceSize
p CompressedClassSpaceSize
echo Metaspace状态:
p Metaspace::_class_space_list
p Metaspace::_chunk_manager_class

echo ===== 10. LatestMethodCache创建详细分析 =====\n
continue
echo LatestMethodCache创建前:
p Universe::_finalizer_register_cache
p Universe::_loader_addClass_cache
p Universe::_pd_implies_cache
p Universe::_throw_illegal_access_error_cache
p Universe::_throw_no_such_method_error_cache
p Universe::_do_stack_walk_cache

# 执行LatestMethodCache创建
next 10
echo LatestMethodCache创建后:
p Universe::_finalizer_register_cache
p Universe::_loader_addClass_cache
p Universe::_pd_implies_cache
p Universe::_throw_illegal_access_error_cache
p Universe::_throw_no_such_method_error_cache
p Universe::_do_stack_walk_cache

echo 每个LatestMethodCache对象的详细信息:
echo finalizer_register_cache详情:
p *Universe::_finalizer_register_cache
echo loader_addClass_cache详情:
p *Universe::_loader_addClass_cache

echo ===== 11. 符号表和字符串表创建分析 =====\n
continue
echo 符号表创建...
next 5
echo SymbolTable状态:
p SymbolTable::_the_table
echo StringTable状态:
p StringTable::_the_table

echo ===== 12. 最终状态完整检查 =====\n
continue
echo universe_init()完成后的完整状态:
echo Universe全局状态:
p Universe::_fully_initialized
p Universe::_collectedHeap
p Universe::_heap_base
p Universe::_narrow_oop_base
p Universe::_narrow_oop_shift

echo G1CollectedHeap完整状态:
set $g1_heap = (G1CollectedHeap*)Universe::_collectedHeap
p $g1_heap->_hrm._regions._length
p $g1_heap->_hrm._num_committed
p $g1_heap->_g1_policy
p $g1_heap->_summary_bytes_used

echo HeapRegion配置验证:
echo Region大小(字节): 
p HeapRegion::GrainBytes
echo Region大小(MB):
set $region_size_mb = HeapRegion::GrainBytes / (1024 * 1024)
p $region_size_mb
echo 总区域数:
set $total_regions = 8 * 1024 * 1024 * 1024 / HeapRegion::GrainBytes
p $total_regions

echo ===== 13. 内存布局验证 =====\n
echo 进程内存映射:
shell cat /proc/$(pgrep -f HelloWorld)/maps | head -20

echo ===== 调试完成 =====\n
quit