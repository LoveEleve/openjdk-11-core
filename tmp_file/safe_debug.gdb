# 安全的JVM调试脚本 - 避免触发断言
set pagination off
set print pretty on
set confirm off

# 在JVM完全启动后检查状态
break JavaMain
commands
    silent
    printf "\n🎯 === JAVA MAIN 开始执行 ===\n"
    printf "JVM已完全初始化，开始执行Java程序\n"
    
    printf "\n=== JVM状态检查 ===\n"
    printf "Universe::_collectedHeap: %p\n", Universe::_collectedHeap
    if Universe::_collectedHeap != 0
        printf "堆类型: %s\n", Universe::_collectedHeap->name()
    end
    
    # 检查类加载器
    printf "\nSystemDictionary状态检查:\n"
    printf "已加载的类数量统计...\n"
    
    continue
end

# 监控类加载
break SystemDictionary::resolve_or_fail
commands
    silent
    printf "🔍 正在加载类: %s\n", $arg0->as_C_string()
    continue
end

# 监控方法编译
break CompileBroker::compile_method
commands
    silent
    printf "⚡ JIT编译方法触发\n"
    continue
end

printf "🚀 开始安全调试模式...\n"
run
quit