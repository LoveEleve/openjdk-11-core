# ============================================================================
# universe_init() 最终调试脚本
# 环境: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages
# ============================================================================

set pagination off
set print pretty on
set confirm off

echo ============================================================================\n
echo universe_init() 8GB G1GC 详细调试\n
echo ============================================================================\n

# 关键断点 - 使用函数名
break universe_init
break Universe::initialize_heap
break HeapRegion::setup_heap_region_size
break Metaspace::global_initialize
break SymbolTable::create_table
break StringTable::create_table

echo 开始运行...\n
run

# === universe_init 入口 ===
echo \n=== [1] universe_init() 入口 ===\n
echo 位置:\n
where 1
echo \n初始状态:\n
print Universe::_fully_initialized
print Universe::_collectedHeap
print Universe::_narrow_oop

continue

# === Universe::initialize_heap ===
echo \n=== [2] Universe::initialize_heap() ===\n
where 1
continue

# === HeapRegion::setup_heap_region_size ===
echo \n=== [3] HeapRegion::setup_heap_region_size ===\n
where 1
echo 输入参数:\n
print initial_heap_size
print max_heap_size

# 执行完函数后查看结果
finish

echo \nHeapRegion静态配置(计算后):\n
print HeapRegion::GrainBytes
print HeapRegion::LogOfHRGrainBytes
print HeapRegion::GrainWords
print HeapRegion::CardsPerRegion

continue

# === Metaspace::global_initialize ===
echo \n=== [4] Metaspace::global_initialize() ===\n
where 1
continue

# === SymbolTable::create_table ===
echo \n=== [5] SymbolTable::create_table() ===\n
where 1
print SymbolTableSize
continue

# === StringTable::create_table ===
echo \n=== [6] StringTable::create_table() ===\n
where 1
continue

# 删除所有断点，运行完成
delete breakpoints

# 在 init.cpp 的调用点后设置断点
break init.cpp:120

continue

echo \n=== [7] universe_init() 完成后状态 ===\n

echo \n--- 堆对象 ---\n
print Universe::_collectedHeap
print/x Universe::_collectedHeap

echo \n--- 压缩指针 ---\n
print Universe::_narrow_oop
print/x Universe::_narrow_oop._base

echo \n--- 压缩类指针 ---\n
print Universe::_narrow_klass
print/x Universe::_narrow_klass._base

echo \n--- LatestMethodCache ---\n
print Universe::_finalizer_register_cache
print Universe::_loader_addClass_cache
print Universe::_pd_implies_cache
print Universe::_throw_illegal_access_error_cache
print Universe::_throw_no_such_method_error_cache
print Universe::_do_stack_walk_cache

echo \n--- 初始化标志 ---\n
print Universe::_fully_initialized
print Universe::_bootstrapping

echo \n============================================================================\n
echo 调试完成\n
echo ============================================================================\n

continue
quit
