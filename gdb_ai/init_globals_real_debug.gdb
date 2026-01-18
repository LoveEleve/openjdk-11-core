# init_globals() 真实调试脚本
# 获取每个初始化函数的真实运行时数据

set pagination off
set print pretty on
set print object on
set confirm off

# 设置日志
set logging file /data/workspace/openjdk11-core/md/init_global_md/debug_output.log
set logging overwrite on
set logging on

file /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/images/jdk/bin/java

# ==========================================
# 第一阶段：基础设施初始化
# ==========================================

# 断点1: init_globals入口
break init_globals
commands
    silent
    printf "\n========================================\n"
    printf "=== init_globals() 入口 ===\n"
    printf "========================================\n"
    printf "函数地址: %p\n", init_globals
    printf "当前线程: %p\n", $rdi
    continue
end

# 断点2: management_init
break management_init
commands
    silent
    printf "\n--- [1] management_init() ---\n"
    printf "函数地址: %p\n", management_init
    continue
end

# 断点3: Management::init 内部
break Management::init
commands
    silent
    printf "\n--- [1.1] Management::init() 内部 ---\n"
    continue
end

# 断点4: bytecodes_init
break bytecodes_init
commands
    silent
    printf "\n--- [2] bytecodes_init() ---\n"
    printf "函数地址: %p\n", bytecodes_init
    continue
end

# 断点5: Bytecodes::initialize
break Bytecodes::initialize
commands
    silent
    printf "\n--- [2.1] Bytecodes::initialize() 内部 ---\n"
    continue
end

# 断点6: compilationPolicy_init
break compilationPolicy_init
commands
    silent
    printf "\n--- [3] compilationPolicy_init() ---\n"
    continue
end

# 断点7: codeCache_init
break codeCache_init
commands
    silent
    printf "\n--- [4] codeCache_init() ---\n"
    continue
end

# 断点8: CodeCache::initialize
break CodeCache::initialize
commands
    silent
    printf "\n--- [4.1] CodeCache::initialize() 内部 ---\n"
    continue
end

# ==========================================
# 第二阶段：Universe初始化（最核心）
# ==========================================

# 断点9: universe_init
break universe_init
commands
    silent
    printf "\n========================================\n"
    printf "=== [5] universe_init() 核心初始化 ===\n"
    printf "========================================\n"
    printf "函数地址: %p\n", universe_init
    continue
end

# 断点10: Universe::initialize_heap
break Universe::initialize_heap
commands
    silent
    printf "\n--- [5.1] Universe::initialize_heap() ---\n"
    printf "开始创建Java堆...\n"
    continue
end

# 断点10.1: G1CollectedHeap构造函数
break G1CollectedHeap::G1CollectedHeap
commands
    silent
    printf "\n--- [5.1.1] G1CollectedHeap构造函数 ---\n"
    printf "G1CollectedHeap this指针: %p\n", $rdi
    printf "对象大小: %d bytes\n", sizeof(G1CollectedHeap)
    continue
end

# 断点10.2: G1CollectedHeap::initialize
break G1CollectedHeap::initialize
commands
    silent
    printf "\n--- [5.1.2] G1CollectedHeap::initialize() ---\n"
    printf "this指针: %p\n", $rdi
    continue
end

# 断点11: Metaspace::global_initialize
break Metaspace::global_initialize
commands
    silent
    printf "\n--- [5.2] Metaspace::global_initialize() ---\n"
    continue
end

# 断点12: SymbolTable::create_table
break SymbolTable::create_table
commands
    silent
    printf "\n--- [5.3] SymbolTable::create_table() ---\n"
    continue
end

# 断点13: StringTable::create_table
break StringTable::create_table
commands
    silent
    printf "\n--- [5.4] StringTable::create_table() ---\n"
    continue
end

# ==========================================
# 第三阶段：解释器和编译器初始化
# ==========================================

# 断点14: interpreter_init
break interpreter_init
commands
    silent
    printf "\n--- [6] interpreter_init() ---\n"
    continue
end

# 断点15: TemplateInterpreter::initialize
break TemplateInterpreter::initialize
commands
    silent
    printf "\n--- [6.1] TemplateInterpreter::initialize() ---\n"
    continue
end

# 断点16: templateTable_init
break templateTable_init
commands
    silent
    printf "\n--- [7] templateTable_init() ---\n"
    continue
end

# 断点17: universe2_init
break universe2_init
commands
    silent
    printf "\n--- [8] universe2_init() ---\n"
    continue
end

# 断点18: javaClasses_init
break javaClasses_init
commands
    silent
    printf "\n--- [9] javaClasses_init() ---\n"
    continue
end

# 断点19: compileBroker_init
break compileBroker_init
commands
    silent
    printf "\n--- [10] compileBroker_init() ---\n"
    continue
end

# 断点20: universe_post_init
break universe_post_init
commands
    silent
    printf "\n========================================\n"
    printf "=== [11] universe_post_init() ===\n"
    printf "========================================\n"
    continue
end

# ==========================================
# 第四阶段：init_globals完成后获取所有对象状态
# ==========================================

# 在init_globals返回前设置断点
break init.cpp:167
commands
    silent
    printf "\n========================================\n"
    printf "=== init_globals() 完成，获取所有对象状态 ===\n"
    printf "========================================\n"
    
    printf "\n=== Universe 核心静态变量 ===\n"
    printf "Universe::_collectedHeap: %p\n", Universe::_collectedHeap
    printf "Universe::_main_thread_group: %p\n", Universe::_main_thread_group
    printf "Universe::_system_thread_group: %p\n", Universe::_system_thread_group
    
    printf "\n=== 基本类型Klass对象 ===\n"
    printf "Universe::_boolArrayKlassObj: %p\n", Universe::_boolArrayKlassObj
    printf "Universe::_byteArrayKlassObj: %p\n", Universe::_byteArrayKlassObj
    printf "Universe::_charArrayKlassObj: %p\n", Universe::_charArrayKlassObj
    printf "Universe::_intArrayKlassObj: %p\n", Universe::_intArrayKlassObj
    printf "Universe::_longArrayKlassObj: %p\n", Universe::_longArrayKlassObj
    printf "Universe::_floatArrayKlassObj: %p\n", Universe::_floatArrayKlassObj
    printf "Universe::_doubleArrayKlassObj: %p\n", Universe::_doubleArrayKlassObj
    printf "Universe::_objectArrayKlassObj: %p\n", Universe::_objectArrayKlassObj
    
    printf "\n=== 堆内存信息 ===\n"
    if Universe::_collectedHeap != 0
        printf "堆类型: G1CollectedHeap\n"
        printf "堆地址: %p\n", Universe::_collectedHeap
    end
    
    printf "\n=== Metaspace信息 ===\n"
    printf "Metaspace::_class_space_list: %p\n", Metaspace::_class_space_list
    printf "Metaspace::_space_list: %p\n", Metaspace::_space_list
    
    printf "\n=== 压缩指针信息 ===\n"
    printf "Universe::_narrow_oop._base: %p\n", Universe::_narrow_oop._base
    printf "Universe::_narrow_oop._shift: %d\n", Universe::_narrow_oop._shift
    printf "Universe::_narrow_klass._base: %p\n", Universe::_narrow_klass._base
    printf "Universe::_narrow_klass._shift: %d\n", Universe::_narrow_klass._shift
    
    printf "\n=== CodeCache信息 ===\n"
    printf "CodeCache::_heaps地址: %p\n", &CodeCache::_heaps
    
    printf "\n========================================\n"
    printf "=== 调试完成 ===\n"
    printf "========================================\n"
    
    quit
end

# 运行
run -version
