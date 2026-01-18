# GDB调试脚本：深度分析universe_init()函数
# 目标：验证universe_init()的每个初始化步骤
# 环境：Linux x86_64, -Xms8g -Xmx8g, HelloWorld.class

set confirm off
set pagination off
set print pretty on
set print elements 0

# 设置断点在universe_init()函数入口
break universe_init
commands
    printf "\n=== universe_init() 函数开始执行 ===\n"
    printf "函数地址: %p\n", universe_init
    printf "当前线程: %d\n", $_thread
    printf "Universe::_fully_initialized: %d\n", Universe::_fully_initialized
    continue
end

# 1. 断点：JavaClasses::compute_hard_coded_offsets()
break JavaClasses::compute_hard_coded_offsets
commands
    printf "\n=== 1. JavaClasses::compute_hard_coded_offsets() ===\n"
    printf "函数地址: %p\n", JavaClasses::compute_hard_coded_offsets
    printf "作用：计算JVM需要直接访问的Java类字段偏移量\n"
    continue
end

# 断点：compute_hard_coded_offsets执行后
break javaClasses.cpp:4473
commands
    printf "硬编码偏移量计算完成:\n"
    printf "  java_lang_boxing_object::value_offset = %d\n", java_lang_boxing_object::value_offset
    printf "  java_lang_ref_Reference::referent_offset = %d\n", java_lang_ref_Reference::referent_offset
    printf "  java_lang_ref_Reference::queue_offset = %d\n", java_lang_ref_Reference::queue_offset
    continue
end

# 2. 断点：Universe::initialize_heap()
break Universe::initialize_heap
commands
    printf "\n=== 2. Universe::initialize_heap() ===\n"
    printf "函数地址: %p\n", Universe::initialize_heap
    printf "作用：初始化JVM堆内存\n"
    printf "当前_collectedHeap: %p\n", Universe::_collectedHeap
    continue
end

# 断点：create_heap()调用
break Universe::create_heap
commands
    printf "\n--- Universe::create_heap() ---\n"
    printf "函数地址: %p\n", Universe::create_heap
    printf "作用：创建G1CollectedHeap对象\n"
    continue
end

# 断点：G1CollectedHeap::initialize()
break G1CollectedHeap::initialize
commands
    printf "\n--- G1CollectedHeap::initialize() ---\n"
    printf "函数地址: %p\n", G1CollectedHeap::initialize
    printf "G1堆对象地址: %p\n", this
    printf "作用：真正初始化G1堆内存\n"
    continue
end

# 断点：initialize_heap完成后
break universe.cpp:813
commands
    printf "堆初始化完成:\n"
    printf "  _collectedHeap: %p\n", Universe::_collectedHeap
    printf "  堆名称: %s\n", Universe::_collectedHeap->name()
    printf "  最大TLAB大小: %lu\n", Universe::heap()->max_tlab_size()
    continue
end

# 3. 断点：SystemDictionary::initialize_oop_storage()
break SystemDictionary::initialize_oop_storage
commands
    printf "\n=== 3. SystemDictionary::initialize_oop_storage() ===\n"
    printf "函数地址: %p\n", SystemDictionary::initialize_oop_storage
    printf "作用：初始化系统字典的OOP存储\n"
    continue
end

# 4. 断点：Metaspace::global_initialize()
break Metaspace::global_initialize
commands
    printf "\n=== 4. Metaspace::global_initialize() ===\n"
    printf "函数地址: %p\n", Metaspace::global_initialize
    printf "作用：全局初始化元空间\n"
    continue
end

# 5. 断点：MetaspaceCounters::initialize_performance_counters()
break MetaspaceCounters::initialize_performance_counters
commands
    printf "\n=== 5. MetaspaceCounters::initialize_performance_counters() ===\n"
    printf "函数地址: %p\n", MetaspaceCounters::initialize_performance_counters
    printf "作用：初始化元空间性能计数器\n"
    continue
end

# 6. 断点：AOTLoader::universe_init()
break AOTLoader::universe_init
commands
    printf "\n=== 6. AOTLoader::universe_init() ===\n"
    printf "函数地址: %p\n", AOTLoader::universe_init
    printf "作用：AOT编译器初始化\n"
    continue
end

# 7. 断点：ClassLoaderData::init_null_class_loader_data()
break ClassLoaderData::init_null_class_loader_data
commands
    printf "\n=== 7. ClassLoaderData::init_null_class_loader_data() ===\n"
    printf "函数地址: %p\n", ClassLoaderData::init_null_class_loader_data
    printf "作用：初始化空类加载器数据\n"
    continue
end

# 8. 断点：LatestMethodCache创建
break universe.cpp:720
commands
    printf "\n=== 8. LatestMethodCache对象创建 ===\n"
    printf "创建6个LatestMethodCache对象:\n"
    continue
end

# 断点：每个LatestMethodCache创建后
break universe.cpp:721
commands
    printf "  _finalizer_register_cache: %p\n", Universe::_finalizer_register_cache
    continue
end

break universe.cpp:722
commands
    printf "  _loader_addClass_cache: %p\n", Universe::_loader_addClass_cache
    continue
end

break universe.cpp:723
commands
    printf "  _pd_implies_cache: %p\n", Universe::_pd_implies_cache
    continue
end

break universe.cpp:724
commands
    printf "  _throw_illegal_access_error_cache: %p\n", Universe::_throw_illegal_access_error_cache
    continue
end

break universe.cpp:725
commands
    printf "  _throw_no_such_method_error_cache: %p\n", Universe::_throw_no_such_method_error_cache
    continue
end

break universe.cpp:726
commands
    printf "  _do_stack_walk_cache: %p\n", Universe::_do_stack_walk_cache
    continue
end

# 9. 断点：SymbolTable::create_table()
break SymbolTable::create_table
commands
    printf "\n=== 9. SymbolTable::create_table() ===\n"
    printf "函数地址: %p\n", SymbolTable::create_table
    printf "作用：创建符号表\n"
    continue
end

# 10. 断点：StringTable::create_table()
break StringTable::create_table
commands
    printf "\n=== 10. StringTable::create_table() ===\n"
    printf "函数地址: %p\n", StringTable::create_table
    printf "作用：创建字符串表\n"
    continue
end

# 11. 断点：ResolvedMethodTable::create_table()
break ResolvedMethodTable::create_table
commands
    printf "\n=== 11. ResolvedMethodTable::create_table() ===\n"
    printf "函数地址: %p\n", ResolvedMethodTable::create_table
    printf "作用：创建已解析方法表\n"
    continue
end

# 断点：universe_init()函数返回
break universe.cpp:754
commands
    printf "\n=== universe_init() 函数执行完成 ===\n"
    printf "返回状态: JNI_OK (%d)\n", 0
    printf "Universe初始化完成！\n"
    continue
end

# 运行程序
run -Xms8g -Xmx8g HelloWorld

# 退出
quit