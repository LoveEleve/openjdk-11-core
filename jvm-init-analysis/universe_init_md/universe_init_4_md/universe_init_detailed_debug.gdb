# ============================================================================
# universe_init() 详细调试脚本
# 环境: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages
# ============================================================================

set pagination off
set print pretty on
set confirm off

# 设置日志
set logging file /data/workspace/openjdk11-core/md/universe_init_md/universe_init_4_md/universe_init_detailed_output.txt
set logging overwrite on
set logging enabled on

echo ============================================================================\n
echo universe_init() 8GB G1GC 详细调试\n
echo ============================================================================\n

# 关键断点
break universe_init
break universe.cpp:754

echo 开始运行...\n
run

# universe_init 入口
echo \n=== [阶段1] universe_init() 入口 ===\n
echo 当前位置:\n
where 1
echo \n初始化前状态:\n
echo _fully_initialized = 
print Universe::_fully_initialized
echo _collectedHeap = 
print Universe::_collectedHeap
echo _narrow_oop._base = 
print Universe::_narrow_oop._base
echo _narrow_oop._shift = 
print Universe::_narrow_oop._shift

continue

# universe_init 即将返回
echo \n=== [阶段2] universe_init() 完成 (返回前) ===\n

echo \n--- 堆信息 ---\n
echo _collectedHeap 地址 = 
print Universe::_collectedHeap
echo \n_collectedHeap->_reserved._start = 
print Universe::_collectedHeap->_reserved._start
echo _collectedHeap->_reserved._word_size = 
print Universe::_collectedHeap->_reserved._word_size

# 计算堆地址范围
echo \n堆起始地址 (十六进制):
print/x Universe::_collectedHeap->_reserved._start
echo 堆大小 (字节): 
print Universe::_collectedHeap->_reserved._word_size * 8

echo \n--- HeapRegion 静态配置 ---\n
echo GrainBytes (Region大小) = 
print HeapRegion::GrainBytes
echo LogOfHRGrainBytes = 
print HeapRegion::LogOfHRGrainBytes
echo GrainWords = 
print HeapRegion::GrainWords
echo CardsPerRegion = 
print HeapRegion::CardsPerRegion

echo \n--- 压缩对象指针 (Compressed Oops) ---\n
echo _narrow_oop._base = 
print Universe::_narrow_oop._base
echo _narrow_oop._base (十六进制) = 
print/x Universe::_narrow_oop._base
echo _narrow_oop._shift = 
print Universe::_narrow_oop._shift
echo _narrow_oop._use_implicit_null_checks = 
print Universe::_narrow_oop._use_implicit_null_checks

echo \n--- 压缩类指针 (Compressed Class Pointers) ---\n
echo _narrow_klass._base = 
print Universe::_narrow_klass._base
echo _narrow_klass._base (十六进制) = 
print/x Universe::_narrow_klass._base
echo _narrow_klass._shift = 
print Universe::_narrow_klass._shift
echo _narrow_klass_range = 
print Universe::_narrow_klass_range

echo \n--- LatestMethodCache (6个方法缓存) ---\n
echo _finalizer_register_cache 地址 = 
print Universe::_finalizer_register_cache
echo _loader_addClass_cache 地址 = 
print Universe::_loader_addClass_cache
echo _pd_implies_cache 地址 = 
print Universe::_pd_implies_cache
echo _throw_illegal_access_error_cache 地址 = 
print Universe::_throw_illegal_access_error_cache
echo _throw_no_such_method_error_cache 地址 = 
print Universe::_throw_no_such_method_error_cache
echo _do_stack_walk_cache 地址 = 
print Universe::_do_stack_walk_cache

echo \n--- LatestMethodCache 详细内容 ---\n
echo _finalizer_register_cache 内容:\n
print *Universe::_finalizer_register_cache
echo \n_loader_addClass_cache 内容:\n
print *Universe::_loader_addClass_cache

echo \n--- 初始化状态 ---\n
echo _fully_initialized = 
print Universe::_fully_initialized
echo _bootstrapping = 
print Universe::_bootstrapping
echo _module_initialized = 
print Universe::_module_initialized

echo \n--- 基本类型数组Klass ---\n
echo _boolArrayKlassObj = 
print Universe::_boolArrayKlassObj
echo _byteArrayKlassObj = 
print Universe::_byteArrayKlassObj
echo _intArrayKlassObj = 
print Universe::_intArrayKlassObj

echo \n============================================================================\n
echo 调试完成\n
echo ============================================================================\n

set logging enabled off
quit
