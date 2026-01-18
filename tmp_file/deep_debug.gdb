# 深度JVM调试脚本
set pagination off
set print pretty on
set confirm off
set logging enabled on
set logging file debug_output.txt

# 1. JVM初始化关键断点
break init_globals
commands
    silent
    printf "\n🚀 === JVM INIT_GLOBALS 开始 ===\n"
    printf "线程ID: %d\n", $_thread
    backtrace 3
    continue
end

# 2. Universe初始化
break universe_init
commands
    silent
    printf "\n🌌 === UNIVERSE_INIT 开始 ===\n"
    printf "当前线程: %d\n", $_thread
    continue
end

# 3. 堆初始化完成
break init.cpp:167
commands
    silent
    printf "\n✅ === INIT_GLOBALS 即将完成 ===\n"
    printf "Universe::_collectedHeap: %p\n", Universe::_collectedHeap
    if Universe::_collectedHeap != 0
        printf "🎯 堆已成功初始化: %s\n", Universe::_collectedHeap->name()
        printf "堆容量: %lu bytes\n", Universe::_collectedHeap->capacity()
    end
    continue
end

# 4. G1CollectedHeap构造
break G1CollectedHeap::G1CollectedHeap
commands
    silent
    printf "\n🔥 === G1 HEAP 构造开始 ===\n"
    continue
end

# 5. 类加载器初始化
break classLoader_init1
commands
    silent
    printf "\n📚 === CLASS LOADER 初始化 ===\n"
    continue
end

# 6. 代码缓存初始化
break codeCache_init
commands
    silent
    printf "\n💾 === CODE CACHE 初始化 ===\n"
    continue
end

# 7. 解释器初始化
break interpreter_init
commands
    silent
    printf "\n🔄 === INTERPRETER 初始化 ===\n"
    continue
end

# 8. JIT编译器初始化
break compileBroker_init
commands
    silent
    printf "\n⚡ === JIT COMPILER 初始化 ===\n"
    continue
end

printf "🎯 开始调试JVM初始化过程...\n"
run
quit