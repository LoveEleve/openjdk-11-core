# ========================================
# 史诗级JVM初始化完整对象追踪脚本
# 目标：追踪init_globals()中每个对象的创建过程
# 验证环境：-Xms8g -Xmx8g -XX:+UseG1GC Linux系统
# ========================================

set pagination off
set print pretty on
set logging file /data/workspace/openjdk11-core/md/init_globals_epic_output.log
set logging on

echo ========================================\n
echo 史诗级JVM初始化完整对象追踪开始\n
echo 目标：追踪init_globals()中每个对象的创建\n
echo ========================================\n

# ========================================
# 第一阶段：基础设施初始化
# ========================================

# 1. Management初始化 - JMX对象创建
break management_init
commands
    echo \n=== PHASE 1: Management初始化 ===\n
    echo 断点位置：management_init()\n
    continue
end

break Management::init
commands
    echo \n--- Management::init() 对象创建 ---\n
    print "Management对象地址："
    print &Management::_jmm_version
    print "JMX版本："
    print Management::_jmm_version
    continue
end

# 2. 字节码表初始化
break bytecodes_init
commands
    echo \n=== PHASE 2: 字节码表初始化 ===\n
    echo 断点位置：bytecodes_init()\n
    continue
end

break Bytecodes::initialize
commands
    echo \n--- Bytecodes::initialize() 对象创建 ---\n
    print "字节码表地址："
    print &Bytecodes::_flags
    print "字节码数量："
    print Bytecodes::number_of_codes
    continue
end

# 3. 类加载器初始化
break classLoader_init1
commands
    echo \n=== PHASE 3: 类加载器初始化 ===\n
    echo 断点位置：classLoader_init1()\n
    continue
end

break ClassLoader::initialize
commands
    echo \n--- ClassLoader::initialize() 对象创建 ---\n
    print "ClassLoader对象地址："
    print &ClassLoader::_first_append_entry
    print "Bootstrap类路径："
    print ClassLoader::_first_append_entry
    continue
end

# 4. 编译策略初始化
break compilationPolicy_init
commands
    echo \n=== PHASE 4: 编译策略初始化 ===\n
    echo 断点位置：compilationPolicy_init()\n
    continue
end

# 5. 代码缓存初始化
break codeCache_init
commands
    echo \n=== PHASE 5: 代码缓存初始化 ===\n
    echo 断点位置：codeCache_init()\n
    continue
end

break CodeCache::initialize
commands
    echo \n--- CodeCache::initialize() 对象创建 ---\n
    print "CodeCache对象地址："
    print &CodeCache::_heap
    print "代码缓存堆地址："
    print CodeCache::_heap
    continue
end

# ========================================
# 第二阶段：Universe核心初始化（最重要）
# ========================================

break universe_init
commands
    echo \n=== PHASE 6: Universe核心初始化（最重要）===\n
    echo 断点位置：universe_init()\n
    continue
end

# Universe堆初始化
break Universe::initialize_heap
commands
    echo \n--- Universe::initialize_heap() 堆对象创建 ---\n
    print "Universe对象地址："
    print &Universe::_collectedHeap
    print "堆对象地址："
    print Universe::_collectedHeap
    continue
end

# G1堆创建
break G1CollectedHeap::G1CollectedHeap
commands
    echo \n--- G1CollectedHeap构造函数 ---\n
    print "G1堆对象地址："
    print this
    print "G1堆策略地址："
    print this->_g1_policy
    continue
end

# Metaspace初始化
break Metaspace::global_initialize
commands
    echo \n--- Metaspace::global_initialize() 元空间对象创建 ---\n
    print "Metaspace类数据地址："
    print &Metaspace::_class_space_list
    continue
end

# SymbolTable创建
break SymbolTable::create_table
commands
    echo \n--- SymbolTable::create_table() 符号表对象创建 ---\n
    print "SymbolTable地址："
    print &SymbolTable::_the_table
    print "符号表大小："
    print SymbolTable::_the_table->_table_size
    continue
end

# StringTable创建
break StringTable::create_table
commands
    echo \n--- StringTable::create_table() 字符串表对象创建 ---\n
    print "StringTable地址："
    print &StringTable::_the_table
    print "字符串表大小："
    print StringTable::_the_table->_table_size
    continue
end

# ========================================
# 第三阶段：解释器和运行时初始化
# ========================================

break interpreter_init
commands
    echo \n=== PHASE 7: 解释器初始化 ===\n
    echo 断点位置：interpreter_init()\n
    continue
end

break templateTable_init
commands
    echo \n=== PHASE 8: 模板表初始化 ===\n
    echo 断点位置：templateTable_init()\n
    continue
end

break universe2_init
commands
    echo \n=== PHASE 9: Universe第二阶段初始化 ===\n
    echo 断点位置：universe2_init()\n
    continue
end

break javaClasses_init
commands
    echo \n=== PHASE 10: Java类初始化 ===\n
    echo 断点位置：javaClasses_init()\n
    continue
end

# ========================================
# 第四阶段：编译器初始化
# ========================================

break compileBroker_init
commands
    echo \n=== PHASE 11: 编译代理初始化 ===\n
    echo 断点位置：compileBroker_init()\n
    continue
end

break universe_post_init
commands
    echo \n=== PHASE 12: Universe后初始化 ===\n
    echo 断点位置：universe_post_init()\n
    continue
end

# 运行程序
echo 开始运行JVM，追踪init_globals()完整对象创建过程...\n
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -XX:+UnlockDiagnosticVMOptions -XX:+TraceClassLoading -cp /data/workspace/demo/out HelloWorld

echo \n========================================\n
echo 史诗级JVM初始化完整对象追踪完成！\n
echo ========================================\n

quit