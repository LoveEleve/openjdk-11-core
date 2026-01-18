# JVM初始化第一阶段：基础设施对象追踪
set pagination off
set print pretty on
set logging file /data/workspace/openjdk11-core/md/init_phase1_output.log
set logging on

echo ========================================\n
echo JVM初始化第一阶段：基础设施对象追踪\n
echo ========================================\n

# 断点到init_globals入口
break init_globals
commands
    echo \n=== 进入init_globals()函数 ===\n
    print "init_globals函数地址："
    print &init_globals
    continue
end

# Management初始化
break management_init
commands
    echo \n=== PHASE 1: Management初始化 ===\n
    echo "创建JMX管理对象..."
    backtrace 3
    continue
end

# 字节码表初始化
break bytecodes_init
commands
    echo \n=== PHASE 2: 字节码表初始化 ===\n
    echo "创建字节码表对象..."
    backtrace 3
    continue
end

# 类加载器初始化
break classLoader_init1
commands
    echo \n=== PHASE 3: 类加载器初始化 ===\n
    echo "创建ClassLoader对象..."
    backtrace 3
    continue
end

# 编译策略初始化
break compilationPolicy_init
commands
    echo \n=== PHASE 4: 编译策略初始化 ===\n
    echo "创建编译策略对象..."
    backtrace 3
    continue
end

# 代码缓存初始化
break codeCache_init
commands
    echo \n=== PHASE 5: 代码缓存初始化 ===\n
    echo "创建CodeCache对象..."
    backtrace 3
    continue
end

# Universe初始化（最重要）
break universe_init
commands
    echo \n=== PHASE 6: Universe核心初始化（最重要）===\n
    echo "创建Universe核心对象..."
    backtrace 3
    continue
end

echo 开始第一阶段追踪...\n
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -cp /data/workspace/demo/out HelloWorld

echo \n第一阶段追踪完成！\n
quit