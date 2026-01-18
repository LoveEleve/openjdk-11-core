# Universe对象深度追踪脚本
set pagination off
set print pretty on

echo ========================================\n
echo Universe对象深度追踪\n
echo ========================================\n

# Universe初始化入口
break universe_init
commands
    echo \n=== Universe初始化开始 ===\n
    print "universe_init函数地址："
    print &universe_init
    continue
end

# 堆初始化
break Universe::initialize_heap
commands
    echo \n=== Universe::initialize_heap() ===\n
    print "Universe类静态变量地址："
    print &Universe::_collectedHeap
    continue
end

# G1堆创建
break G1CollectedHeap::G1CollectedHeap
commands
    echo \n=== G1CollectedHeap构造函数 ===\n
    print "G1堆对象this指针："
    print this
    print "G1堆对象大小："
    print sizeof(G1CollectedHeap)
    continue
end

# Metaspace初始化
break Metaspace::global_initialize
commands
    echo \n=== Metaspace全局初始化 ===\n
    print "Metaspace类地址："
    print &Metaspace::_class_space_list
    continue
end

# SymbolTable创建
break SymbolTable::create_table
commands
    echo \n=== SymbolTable创建 ===\n
    print "SymbolTable静态变量地址："
    print &SymbolTable::_the_table
    continue
end

# StringTable创建
break StringTable::create_table
commands
    echo \n=== StringTable创建 ===\n
    print "StringTable静态变量地址："
    print &StringTable::_the_table
    continue
end

echo 开始Universe深度追踪...\n
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -cp /data/workspace/demo/out HelloWorld

echo \nUniverse深度追踪完成！\n
quit