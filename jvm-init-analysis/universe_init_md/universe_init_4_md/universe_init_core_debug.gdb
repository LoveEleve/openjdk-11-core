# ============================================================================
# universe_init() 核心调试脚本 (精简版)
# 环境: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages
# ============================================================================

set pagination off
set print pretty on
set confirm off

# 核心断点
break universe_init
break HeapRegion::setup_heap_region_size
break Universe::reserve_heap
break G1CollectedHeap::initialize

echo \n=== 开始调试 ===\n
run

# universe_init 入口
echo \n=== 1. universe_init() 入口 ===\n
print Universe::_fully_initialized
print Universe::_collectedHeap
continue

# HeapRegion 大小设置
echo \n=== 2. HeapRegion::setup_heap_region_size ===\n
print initial_heap_size
print max_heap_size
continue

# 打印 HeapRegion 计算后的静态值
echo \n=== 3. HeapRegion 静态配置 (计算后) ===\n
print HeapRegion::GrainBytes
print HeapRegion::LogOfHRGrainBytes
print HeapRegion::GrainWords
print HeapRegion::CardsPerRegion

continue

# Universe::reserve_heap
echo \n=== 4. Universe::reserve_heap ===\n
print heap_size
print alignment
continue

# G1CollectedHeap::initialize
echo \n=== 5. G1CollectedHeap::initialize ===\n
continue

# 删除所有断点，在 universe_init 返回前设置断点
delete breakpoints
break universe.cpp:754

continue

echo \n=== 6. universe_init() 完成时状态 ===\n

echo \n--- 堆对象 ---\n
print Universe::_collectedHeap
print Universe::_collectedHeap->_reserved

echo \n--- 压缩指针 (narrow_oop) ---\n
print Universe::_narrow_oop._base
print Universe::_narrow_oop._shift
print Universe::_narrow_oop._use_implicit_null_checks

echo \n--- 压缩类指针 (narrow_klass) ---\n
print Universe::_narrow_klass._base
print Universe::_narrow_klass._shift

echo \n--- LatestMethodCache (6个) ---\n
print Universe::_finalizer_register_cache
print Universe::_loader_addClass_cache
print Universe::_pd_implies_cache
print Universe::_throw_illegal_access_error_cache
print Universe::_throw_no_such_method_error_cache
print Universe::_do_stack_walk_cache

echo \n--- 初始化标志 ---\n
print Universe::_fully_initialized
print Universe::_bootstrapping

echo \n=== 调试完成 ===\n
quit
