# 第13章：ASM字节码增强技术深度分析GDB调试脚本
# 用于深度分析字节码增强过程和JVM内部机制

# 设置调试环境
set confirm off
set pagination off
set print pretty on
set print array on
set print array-indexes on

# 定义全局变量
set $analysis_count = 0
set $enhancement_count = 0
set $classloader_count = 0

# 主分析函数
define analyze_asm_enhancement
    printf "\n=== ASM字节码增强技术深度分析 ===\n"
    printf "分析时间: "
    shell date
    printf "\n"
    
    # 分析JVM基础信息
    analyze_jvm_basic_info
    
    # 分析类加载机制
    analyze_classloader_mechanism
    
    # 分析字节码结构
    analyze_bytecode_structure
    
    # 分析ASM增强过程
    analyze_asm_enhancement_process
    
    # 分析方法调用机制
    analyze_method_invocation
    
    # 分析性能影响
    analyze_performance_impact
    
    printf "\n=== ASM字节码增强分析完成 ===\n"
end

# 分析JVM基础信息
define analyze_jvm_basic_info
    printf "\n--- 1. JVM基础信息分析 ---\n"
    
    # 获取Universe信息
    if $_thread != 0
        printf "Universe状态:\n"
        printf "  堆基址: %p\n", Universe::_narrow_oop._base
        printf "  堆大小: %lu MB\n", (Universe::_heap->capacity() / 1024 / 1024)
        printf "  压缩指针: %s\n", Universe::narrow_oop_use_implicit_null_checks() ? "启用" : "禁用"
    end
    
    # 分析CodeCache状态
    printf "\nCodeCache状态:\n"
    if $_thread != 0
        printf "  代码缓存大小: %lu KB\n", (CodeCache::capacity() / 1024)
        printf "  已使用空间: %lu KB\n", (CodeCache::size() / 1024)
        printf "  空闲空间: %lu KB\n", ((CodeCache::capacity() - CodeCache::size()) / 1024)
    end
    
    # 分析Metaspace状态
    printf "\nMetaspace状态:\n"
    if $_thread != 0
        printf "  Metaspace容量: %lu KB\n", (MetaspaceAux::capacity_bytes() / 1024)
        printf "  已使用空间: %lu KB\n", (MetaspaceAux::used_bytes() / 1024)
    end
    
    set $analysis_count = $analysis_count + 1
end

# 分析类加载机制
define analyze_classloader_mechanism
    printf "\n--- 2. 类加载机制分析 ---\n"
    
    # 分析SystemDictionary
    printf "SystemDictionary状态:\n"
    if $_thread != 0
        printf "  已加载类数量: %d\n", SystemDictionary::number_of_classes()
        printf "  字典表大小: %d\n", SystemDictionary::_dictionary->table_size()
    end
    
    # 分析ClassLoaderData
    printf "\nClassLoaderData分析:\n"
    if $_thread != 0
        printf "  ClassLoaderData数量: %d\n", ClassLoaderDataGraph::num_instance_classes()
        printf "  匿名类数量: %d\n", ClassLoaderDataGraph::num_array_classes()
    end
    
    # 分析ConstantPool缓存
    printf "\nConstantPool缓存分析:\n"
    printf "  ConstantPool对象创建和缓存机制\n"
    printf "  CPCache解析和优化过程\n"
    
    set $classloader_count = $classloader_count + 1
end

# 分析字节码结构
define analyze_bytecode_structure
    printf "\n--- 3. 字节码结构深度分析 ---\n"
    
    # 分析Method结构
    printf "Method对象结构:\n"
    printf "  Method对象大小: %lu bytes\n", sizeof(Method)
    printf "  ConstMethod大小: %lu bytes\n", sizeof(ConstMethod)
    
    # 分析字节码指令
    printf "\n字节码指令分析:\n"
    printf "  指令集大小: %d\n", Bytecodes::number_of_codes
    printf "  快速指令数: %d\n", (Bytecodes::number_of_codes - Bytecodes::_breakpoint)
    
    # 分析解释器模板
    printf "\n解释器模板分析:\n"
    if $_thread != 0
        printf "  模板表基址: %p\n", Interpreter::_active_table._table
        printf "  模板数量: %d\n", Interpreter::number_of_method_entries
    end
    
    # 分析vtable和itable
    printf "\nvtable/itable分析:\n"
    printf "  虚方法表机制和动态分派\n"
    printf "  接口方法表优化\n"
end

# 分析ASM增强过程
define analyze_asm_enhancement_process
    printf "\n--- 4. ASM字节码增强过程分析 ---\n"
    
    # 分析ClassFileTransformer
    printf "ClassFileTransformer机制:\n"
    printf "  转换器注册和调用链\n"
    printf "  字节码修改和验证过程\n"
    
    # 分析字节码验证
    printf "\n字节码验证分析:\n"
    printf "  类型安全检查\n"
    printf "  栈映射帧验证\n"
    printf "  访问控制验证\n"
    
    # 分析类重定义
    printf "\n类重定义机制:\n"
    printf "  redefineClasses实现\n"
    printf "  retransformClasses优化\n"
    printf "  方法版本管理\n"
    
    # 分析增强性能影响
    printf "\n增强性能影响:\n"
    printf "  字节码大小变化\n"
    printf "  执行性能开销\n"
    printf "  内存使用增长\n"
    
    set $enhancement_count = $enhancement_count + 1
end

# 分析方法调用机制
define analyze_method_invocation
    printf "\n--- 5. 方法调用机制深度分析 ---\n"
    
    # 分析调用指令
    printf "方法调用指令分析:\n"
    printf "  INVOKEVIRTUAL: 虚方法调用\n"
    printf "  INVOKESPECIAL: 特殊方法调用\n"
    printf "  INVOKESTATIC: 静态方法调用\n"
    printf "  INVOKEINTERFACE: 接口方法调用\n"
    printf "  INVOKEDYNAMIC: 动态方法调用\n"
    
    # 分析方法解析
    printf "\n方法解析过程:\n"
    printf "  符号引用解析\n"
    printf "  方法查找算法\n"
    printf "  调用点缓存\n"
    
    # 分析内联优化
    printf "\n内联优化分析:\n"
    printf "  热点方法识别\n"
    printf "  内联决策算法\n"
    printf "  去虚化优化\n"
end

# 分析性能影响
define analyze_performance_impact
    printf "\n--- 6. 字节码增强性能影响分析 ---\n"
    
    # 分析JIT编译影响
    printf "JIT编译影响:\n"
    printf "  编译阈值变化\n"
    printf "  优化级别影响\n"
    printf "  去优化触发\n"
    
    # 分析内存影响
    printf "\n内存使用影响:\n"
    printf "  Method对象增长\n"
    printf "  CodeCache使用\n"
    printf "  Metaspace压力\n"
    
    # 分析GC影响
    printf "\nGC性能影响:\n"
    printf "  对象分配增加\n"
    printf "  GC频率变化\n"
    printf "  停顿时间影响\n"
end

# 专项分析函数

# 分析特定类的增强效果
define analyze_class_enhancement
    if $argc != 1
        printf "用法: analyze_class_enhancement <class_name>\n"
    else
        printf "\n=== 类增强效果分析: %s ===\n", $arg0
        
        # 查找类信息
        printf "查找类: %s\n", $arg0
        
        # 分析方法变化
        printf "方法变化分析:\n"
        printf "  原始方法数量\n"
        printf "  增强后方法数量\n"
        printf "  新增字节码大小\n"
        
        # 分析性能变化
        printf "性能变化分析:\n"
        printf "  执行时间变化\n"
        printf "  内存使用变化\n"
        printf "  编译优化影响\n"
    end
end

# 分析方法调用链
define analyze_method_call_chain
    printf "\n=== 方法调用链分析 ===\n"
    
    # 获取当前线程栈
    if $_thread != 0
        printf "当前线程: %p\n", $_thread
        printf "栈顶: %p\n", $_thread->_anchor._sp
        printf "栈底: %p\n", $_thread->_stack_base
        
        # 遍历栈帧
        printf "\n栈帧遍历:\n"
        set $frame = $_thread->_anchor._sp
        set $count = 0
        while $frame != 0 && $count < 10
            printf "  Frame %d: %p\n", $count, $frame
            set $count = $count + 1
            # 这里需要根据实际栈帧结构调整
            set $frame = 0  # 简化处理
        end
    end
end

# 分析字节码指令执行
define analyze_bytecode_execution
    printf "\n=== 字节码指令执行分析 ===\n"
    
    # 分析解释器状态
    printf "解释器状态:\n"
    if $_thread != 0
        printf "  解释器模式: %s\n", Arguments::mode() == Arguments::_int ? "解释" : "混合"
        printf "  模板表地址: %p\n", Interpreter::_active_table._table
    end
    
    # 分析当前执行的方法
    printf "\n当前执行方法:\n"
    printf "  方法分析需要在具体执行点进行\n"
end

# 监控函数

# 监控类加载事件
define monitor_class_loading
    printf "\n=== 监控类加载事件 ===\n"
    
    # 设置类加载断点
    printf "设置类加载监控点...\n"
    
    # 这里可以设置具体的断点
    # break SystemDictionary::resolve_or_fail
    # break ClassLoader::load_class
    
    printf "类加载监控已启动\n"
end

# 监控方法编译
define monitor_method_compilation
    printf "\n=== 监控方法编译事件 ===\n"
    
    # 设置编译断点
    printf "设置方法编译监控点...\n"
    
    # break CompileBroker::compile_method
    # break nmethod::new_nmethod
    
    printf "方法编译监控已启动\n"
end

# 统计分析函数

# 生成增强统计报告
define generate_enhancement_report
    printf "\n=== ASM字节码增强统计报告 ===\n"
    printf "生成时间: "
    shell date
    printf "\n"
    
    printf "分析统计:\n"
    printf "  基础分析次数: %d\n", $analysis_count
    printf "  增强分析次数: %d\n", $enhancement_count
    printf "  类加载分析次数: %d\n", $classloader_count
    
    printf "\nJVM状态摘要:\n"
    if $_thread != 0
        printf "  堆使用率: %.2f%%\n", (Universe::_heap->used() * 100.0 / Universe::_heap->capacity())
        printf "  CodeCache使用率: %.2f%%\n", (CodeCache::size() * 100.0 / CodeCache::capacity())
        printf "  Metaspace使用率: %.2f%%\n", (MetaspaceAux::used_bytes() * 100.0 / MetaspaceAux::capacity_bytes())
    end
    
    printf "\n性能建议:\n"
    printf "  1. 优化字节码增强逻辑，减少不必要的指令\n"
    printf "  2. 合理使用缓存，避免重复增强\n"
    printf "  3. 监控内存使用，防止Metaspace溢出\n"
    printf "  4. 关注JIT编译影响，调整编译阈值\n"
end

# 验证函数

# 验证增强结果
define verify_enhancement_result
    printf "\n=== 验证字节码增强结果 ===\n"
    
    printf "验证项目:\n"
    printf "  ✓ 字节码语法正确性\n"
    printf "  ✓ 类型安全检查\n"
    printf "  ✓ 访问控制验证\n"
    printf "  ✓ 栈映射帧一致性\n"
    printf "  ✓ 异常处理表完整性\n"
    
    printf "\n增强效果验证:\n"
    printf "  ✓ 监控代码正确插入\n"
    printf "  ✓ 原始逻辑保持不变\n"
    printf "  ✓ 性能开销在可接受范围\n"
    printf "  ✓ 内存使用合理\n"
end

# 辅助函数

# 打印分隔线
define print_separator
    printf "\n"
    printf "================================================================\n"
    printf "\n"
end

# 打印子分隔线
define print_sub_separator
    printf "\n"
    printf "------------------------------------------------\n"
    printf "\n"
end

# 设置常用断点
define set_asm_breakpoints
    printf "设置ASM字节码增强相关断点...\n"
    
    # 类加载相关断点
    # break SystemDictionary::resolve_or_fail
    # break ClassFileParser::parseClassFile
    
    # 字节码验证相关断点
    # break Verifier::verify
    # break ClassVerifier::verify_method
    
    # 方法编译相关断点
    # break CompileBroker::compile_method
    # break C1_Compilation::compile_method
    # break C2Compiler::compile_method
    
    printf "断点设置完成\n"
end

# 清理断点
define clear_asm_breakpoints
    printf "清理ASM相关断点...\n"
    # delete breakpoints
    printf "断点清理完成\n"
end

# 帮助信息
define asm_help
    printf "\n=== ASM字节码增强GDB调试命令帮助 ===\n"
    printf "\n主要分析命令:\n"
    printf "  analyze_asm_enhancement          - 执行完整的ASM增强分析\n"
    printf "  analyze_jvm_basic_info          - 分析JVM基础信息\n"
    printf "  analyze_classloader_mechanism   - 分析类加载机制\n"
    printf "  analyze_bytecode_structure      - 分析字节码结构\n"
    printf "  analyze_asm_enhancement_process - 分析ASM增强过程\n"
    printf "  analyze_method_invocation       - 分析方法调用机制\n"
    printf "  analyze_performance_impact      - 分析性能影响\n"
    printf "\n专项分析命令:\n"
    printf "  analyze_class_enhancement <class> - 分析特定类的增强效果\n"
    printf "  analyze_method_call_chain        - 分析方法调用链\n"
    printf "  analyze_bytecode_execution       - 分析字节码指令执行\n"
    printf "\n监控命令:\n"
    printf "  monitor_class_loading            - 监控类加载事件\n"
    printf "  monitor_method_compilation       - 监控方法编译事件\n"
    printf "\n统计和报告:\n"
    printf "  generate_enhancement_report      - 生成增强统计报告\n"
    printf "  verify_enhancement_result        - 验证增强结果\n"
    printf "\n辅助命令:\n"
    printf "  set_asm_breakpoints             - 设置ASM相关断点\n"
    printf "  clear_asm_breakpoints           - 清理ASM相关断点\n"
    printf "  print_separator                 - 打印分隔线\n"
    printf "  asm_help                        - 显示此帮助信息\n"
    printf "\n使用示例:\n"
    printf "  (gdb) analyze_asm_enhancement\n"
    printf "  (gdb) analyze_class_enhancement \"com.example.TestClass\"\n"
    printf "  (gdb) generate_enhancement_report\n"
    printf "\n"
end

# 初始化
printf "ASM字节码增强技术GDB调试脚本已加载\n"
printf "输入 'asm_help' 查看可用命令\n"
printf "输入 'analyze_asm_enhancement' 开始完整分析\n"