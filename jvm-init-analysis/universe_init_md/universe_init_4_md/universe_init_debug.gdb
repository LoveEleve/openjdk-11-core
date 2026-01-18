# ============================================================================
# universe_init() 深度调试脚本
# 环境: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages
# ============================================================================

set pagination off
set print pretty on
set print object on
set print static-members on
set print vtbl on
set print demangle on
set print asm-demangle on
set confirm off
set height 0
set width 0

# 设置日志
set logging file /data/workspace/openjdk11-core/md/universe_init_md/universe_init_4_md/universe_init_debug_output.txt
set logging overwrite on
set logging on

echo \n============================================================================\n
echo universe_init() 8GB G1GC 深度调试分析\n
echo 条件: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages\n
echo ============================================================================\n\n

# ============================================================================
# 断点设置
# ============================================================================

# 核心函数断点
break universe_init
break Universe::initialize_heap
break Universe::create_heap
break Universe::reserve_heap

# 子系统初始化断点
break JavaClasses::compute_hard_coded_offsets
break SystemDictionary::initialize_oop_storage
break Metaspace::global_initialize
break SymbolTable::create_table
break StringTable::create_table
break ResolvedMethodTable::create_table
break ClassLoaderData::init_null_class_loader_data

# G1 堆初始化
break G1CollectedHeap::initialize
break HeapRegion::setup_heap_region_size

# 压缩指针设置
break Universe::set_narrow_oop_base
break Universe::set_narrow_oop_shift

echo \n断点设置完成，开始运行...\n\n

run

# ============================================================================
# universe_init() 入口分析
# ============================================================================
echo \n============================================================================\n
echo === universe_init() 入口 ===\n
echo ============================================================================\n

# 打印当前位置
echo \n当前断点位置:\n
where 1
echo \n

# 检查 Universe 初始化状态
echo === Universe 初始化前状态 ===\n
echo _fully_initialized (应为false): \n
print Universe::_fully_initialized
echo \n_collectedHeap (应为NULL): \n
print Universe::_collectedHeap
echo \n

continue

# ============================================================================
# JavaClasses::compute_hard_coded_offsets
# ============================================================================
echo \n============================================================================\n
echo === JavaClasses::compute_hard_coded_offsets() ===\n
echo ============================================================================\n
echo 功能: 计算JVM需要直接访问的Java类字段偏移量\n
echo 这些偏移量在JVM内部硬编码，用于直接访问Java对象字段\n\n

where 1
continue

# ============================================================================
# Universe::initialize_heap
# ============================================================================
echo \n============================================================================\n
echo === Universe::initialize_heap() ===\n
echo ============================================================================\n
echo 功能: 初始化Java堆内存（G1CollectedHeap）\n\n

where 1
echo \n当前线程:\n
info threads

echo \n=== 堆初始化前状态 ===\n
echo _collectedHeap: \n
print Universe::_collectedHeap
echo \n

continue

# ============================================================================
# Universe::create_heap
# ============================================================================
echo \n============================================================================\n
echo === Universe::create_heap() ===\n
echo ============================================================================\n
echo 功能: 创建G1CollectedHeap对象实例\n\n

where 1

continue

# ============================================================================
# HeapRegion::setup_heap_region_size
# ============================================================================
echo \n============================================================================\n
echo === HeapRegion::setup_heap_region_size() ===\n
echo ============================================================================\n
echo 功能: 计算HeapRegion大小\n
echo 公式: region_size = MAX(heap_size / 2048, 1MB)\n
echo 对于8GB堆: region_size = 8GB / 2048 = 4MB\n\n

where 1

# 打印输入参数
echo \n=== 输入参数 ===\n
echo initial_heap_size:\n
print initial_heap_size
echo \nmax_heap_size:\n
print max_heap_size

continue

# ============================================================================
# G1CollectedHeap::initialize
# ============================================================================
echo \n============================================================================\n
echo === G1CollectedHeap::initialize() ===\n
echo ============================================================================\n
echo 功能: 初始化G1堆的所有组件\n\n

where 1

# 打印 HeapRegion 参数
echo \n=== HeapRegion 静态配置 ===\n
echo HeapRegion::GrainBytes (Region大小):\n
print HeapRegion::GrainBytes
echo \nHeapRegion::LogOfHRGrainBytes:\n
print HeapRegion::LogOfHRGrainBytes
echo \nHeapRegion::GrainWords:\n
print HeapRegion::GrainWords
echo \nHeapRegion::CardsPerRegion:\n
print HeapRegion::CardsPerRegion

continue

# ============================================================================
# Universe::reserve_heap
# ============================================================================
echo \n============================================================================\n
echo === Universe::reserve_heap() ===\n
echo ============================================================================\n
echo 功能: 预留堆内存空间（通过mmap系统调用）\n\n

where 1

echo \n=== 参数 ===\n
echo heap_size:\n
print heap_size
echo \nalignment:\n
print alignment

continue

# ============================================================================
# 压缩指针设置
# ============================================================================
echo \n============================================================================\n
echo === 压缩指针设置 ===\n
echo ============================================================================\n

echo \n=== narrow_oop 设置 ===\n

# 继续到 set_narrow_oop_base
continue

echo narrow_oop base:\n
print Universe::_narrow_oop._base
echo \n

continue

echo narrow_oop shift:\n
print Universe::_narrow_oop._shift
echo \n

# ============================================================================
# SystemDictionary::initialize_oop_storage
# ============================================================================
echo \n============================================================================\n
echo === SystemDictionary::initialize_oop_storage() ===\n
echo ============================================================================\n
echo 功能: 初始化OopStorage用于存储VM弱引用\n\n

continue

where 1

continue

# ============================================================================
# Metaspace::global_initialize
# ============================================================================
echo \n============================================================================\n
echo === Metaspace::global_initialize() ===\n
echo ============================================================================\n
echo 功能: 初始化元空间（类元数据存储区域）\n\n

where 1

continue

# ============================================================================
# SymbolTable::create_table
# ============================================================================
echo \n============================================================================\n
echo === SymbolTable::create_table() ===\n
echo ============================================================================\n
echo 功能: 创建符号表（存储类名、方法名等符号）\n\n

where 1

# 打印符号表信息
echo \n=== SymbolTable 配置 ===\n
echo SymbolTableSize:\n
print SymbolTableSize

continue

# ============================================================================
# StringTable::create_table
# ============================================================================
echo \n============================================================================\n
echo === StringTable::create_table() ===\n
echo ============================================================================\n
echo 功能: 创建字符串常量池\n\n

where 1

continue

# ============================================================================
# ResolvedMethodTable::create_table
# ============================================================================
echo \n============================================================================\n
echo === ResolvedMethodTable::create_table() ===\n
echo ============================================================================\n
echo 功能: 创建已解析方法表\n\n

where 1

continue

# ============================================================================
# ClassLoaderData::init_null_class_loader_data
# ============================================================================
echo \n============================================================================\n
echo === ClassLoaderData::init_null_class_loader_data() ===\n
echo ============================================================================\n
echo 功能: 初始化启动类加载器数据\n\n

where 1

continue

# ============================================================================
# 最终状态验证
# ============================================================================
echo \n============================================================================\n
echo === universe_init() 完成后状态验证 ===\n
echo ============================================================================\n

# 设置断点在 universe_init 返回后
break universe.cpp:755
continue

echo \n=== Universe 全局对象状态 ===\n

echo \n--- CollectedHeap (G1堆) ---\n
print Universe::_collectedHeap
echo \n堆类型:\n
print *Universe::_collectedHeap

echo \n--- 压缩指针配置 ---\n
echo narrow_oop._base:\n
print Universe::_narrow_oop._base
echo \nnarrow_oop._shift:\n
print Universe::_narrow_oop._shift
echo \nnarrow_oop._use_implicit_null_checks:\n
print Universe::_narrow_oop._use_implicit_null_checks

echo \n--- narrow_klass 配置 ---\n
echo narrow_klass._base:\n
print Universe::_narrow_klass._base
echo \nnarrow_klass._shift:\n
print Universe::_narrow_klass._shift

echo \n--- LatestMethodCache (6个缓存) ---\n
echo _finalizer_register_cache:\n
print Universe::_finalizer_register_cache
echo \n_loader_addClass_cache:\n
print Universe::_loader_addClass_cache
echo \n_pd_implies_cache:\n
print Universe::_pd_implies_cache
echo \n_throw_illegal_access_error_cache:\n
print Universe::_throw_illegal_access_error_cache
echo \n_throw_no_such_method_error_cache:\n
print Universe::_throw_no_such_method_error_cache
echo \n_do_stack_walk_cache:\n
print Universe::_do_stack_walk_cache

echo \n--- 初始化状态标志 ---\n
echo _fully_initialized:\n
print Universe::_fully_initialized
echo \n_bootstrapping:\n
print Universe::_bootstrapping

echo \n============================================================================\n
echo === 调试完成 ===\n
echo ============================================================================\n

set logging off
quit
