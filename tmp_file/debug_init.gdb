# JVM初始化调试脚本
set pagination off
set print pretty on
set confirm off

# 设置断点在init_globals函数
break init_globals
commands
    silent
    printf "\n=== JVM初始化开始 ===\n"
    printf "进入 init_globals() 函数\n"
    continue
end

# 设置断点在init_globals返回前
break init.cpp:167
commands
    silent
    printf "\n=== init_globals() 即将完成 ===\n"
    printf "Universe::_collectedHeap: %p\n", Universe::_collectedHeap
    if Universe::_collectedHeap != 0
        printf "堆已初始化!\n"
        printf "堆类型: %s\n", Universe::_collectedHeap->name()
    else
        printf "堆尚未初始化\n"
    end
    continue
end

# 设置断点在universe_init
break universe_init
commands
    silent
    printf "\n=== Universe初始化开始 ===\n"
    continue
end

# 运行程序
run
quit