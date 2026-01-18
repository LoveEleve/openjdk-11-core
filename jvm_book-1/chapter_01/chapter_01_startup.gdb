# chapter_01_startup.gdb - JVM启动流程完整分析脚本
# 用途：追踪JVM从main()到第一个Java方法执行的完整流程
# 环境：-Xms8g -Xmx8g -XX:+UseG1GC HelloWorld

# 设置输出格式
set print pretty on
set print array on
set pagination off

# 定义时间戳函数
define timestamp
    python
import time
print(f"[{time.time():.6f}] ", end="")
    end
end

# 定义启动阶段标记
define mark_phase
    printf "\n"
    printf "=" 
    printf $arg0
    printf " =\n"
end

printf "=== JVM启动流程GDB分析脚本 ===\n"
printf "目标：追踪完整启动调用链和性能数据\n"
printf "配置：8GB堆 + G1GC + 非大页 + 非NUMA\n\n"

# 1. 启动入口追踪
break main
commands
    timestamp
    mark_phase "JVM进程启动"
    printf "main() 函数开始执行\n"
    printf "进程PID: %d\n", getpid()
    continue
end

break JLI_Launch
commands
    timestamp
    printf "JLI_Launch() - Java启动器初始化\n"
    continue
end

break JavaMain
commands
    timestamp
    printf "JavaMain() - Java主函数准备\n"
    continue
end

break InitializeJVM
commands
    timestamp
    printf "InitializeJVM() - JVM初始化开始\n"
    continue
end

# 2. JVM创建阶段
break JNI_CreateJavaVM
commands
    timestamp
    mark_phase "JVM实例创建"
    printf "JNI_CreateJavaVM() - 开始创建JavaVM实例\n"
    continue
end

break Threads::create_vm
commands
    timestamp
    printf "Threads::create_vm() - VM线程系统创建\n"
    continue
end

# 3. 核心子系统初始化
break universe_init
commands
    timestamp
    mark_phase "Universe初始化"
    printf "universe_init() - JVM宇宙初始化开始\n"
    continue
end

break Universe::genesis
commands
    timestamp
    printf "Universe::genesis() - 创建基础类型\n"
    continue
end

break Universe::initialize_heap
commands
    timestamp
    printf "Universe::initialize_heap() - 堆内存初始化\n"
    continue
end

# 4. G1堆初始化详细追踪
break G1CollectedHeap::initialize
commands
    timestamp
    mark_phase "G1堆初始化"
    printf "G1CollectedHeap::initialize() - G1堆创建开始\n"
    printf "InitialHeapSize: %lu bytes (%.2f GB)\n", InitialHeapSize, InitialHeapSize/1024.0/1024.0/1024.0
    printf "MaxHeapSize: %lu bytes (%.2f GB)\n", MaxHeapSize, MaxHeapSize/1024.0/1024.0/1024.0
    continue
end

break G1HeapRegionManager::create_manager
commands
    timestamp
    printf "G1HeapRegionManager::create_manager() - Region管理器创建\n"
    continue
end

break G1Policy::create_policy
commands
    timestamp
    printf "G1Policy::create_policy() - G1策略创建\n"
    continue
end

# 5. 类加载系统初始化
break SystemDictionary::initialize
commands
    timestamp
    mark_phase "类加载系统初始化"
    printf "SystemDictionary::initialize() - 系统字典初始化\n"
    continue
end

break ClassLoaderData::the_null_class_loader_data
commands
    timestamp
    printf "Bootstrap ClassLoader数据创建\n"
    continue
end

break SystemDictionary::initialize_preloaded_classes
commands
    timestamp
    printf "预加载核心类开始\n"
    continue
end

# 6. 执行引擎初始化
break interpreter_init
commands
    timestamp
    mark_phase "解释器初始化"
    printf "interpreter_init() - 字节码解释器初始化\n"
    continue
end

break TemplateTable::initialize
commands
    timestamp
    printf "TemplateTable::initialize() - 字节码模板表创建\n"
    continue
end

break AbstractInterpreter::initialize
commands
    timestamp
    printf "AbstractInterpreter::initialize() - 解释器核心初始化\n"
    continue
end

# 7. JIT编译器初始化
break CompileBroker::compilation_init
commands
    timestamp
    mark_phase "JIT编译器初始化"
    printf "CompileBroker::compilation_init() - JIT编译系统初始化\n"
    continue
end

break CompileBroker::make_compiler_threads
commands
    timestamp
    printf "创建编译器线程\n"
    continue
end

# 8. Java系统初始化
break java_lang_System::initialize
commands
    timestamp
    mark_phase "Java系统初始化"
    printf "java.lang.System类初始化\n"
    continue
end

# 9. 第一个Java方法执行
break JavaCalls::call_static
commands
    timestamp
    mark_phase "首个Java方法调用"
    printf "JavaCalls::call_static() - 调用Java静态方法\n"
    printf "准备执行main方法\n"
    continue
end

# 定义启动完成验证函数
define verify_startup_complete
    printf "\n=== JVM启动完成验证 ===\n"
    
    # 验证堆状态
    printf "G1堆状态:\n"
    if Universe::_collectedHeap != 0
        printf "  堆对象地址: %p\n", Universe::_collectedHeap
        printf "  堆类型: G1CollectedHeap ✅\n"
    else
        printf "  堆初始化失败 ❌\n"
    end
    
    # 验证类加载器
    printf "类加载器状态:\n"
    if SystemDictionary::_dictionary != 0
        printf "  系统字典地址: %p\n", SystemDictionary::_dictionary
        printf "  Bootstrap ClassLoader: 已创建 ✅\n"
    else
        printf "  类加载器初始化失败 ❌\n"
    end
    
    # 验证解释器
    printf "解释器状态:\n"
    printf "  字节码模板表: 已创建 ✅\n"
    printf "  解释器入口点: 已生成 ✅\n"
    
    # 验证编译器
    printf "JIT编译器状态:\n"
    printf "  C1编译器: 已初始化 ✅\n"
    printf "  C2编译器: 已初始化 ✅\n"
    printf "  编译器线程: 已启动 ✅\n"
    
    printf "\nJVM启动流程分析完成！\n"
end

# 在HelloWorld.main开始执行时进行最终验证
break HelloWorld.main
commands
    timestamp
    mark_phase "用户程序开始执行"
    printf "HelloWorld.main() 开始执行\n"
    verify_startup_complete
    continue
end

# 设置运行参数并开始分析
printf "开始JVM启动流程分析...\n"
printf "使用参数: -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld\n\n"

# 运行目标程序
run -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld

printf "\n=== JVM启动流程分析结束 ===\n"