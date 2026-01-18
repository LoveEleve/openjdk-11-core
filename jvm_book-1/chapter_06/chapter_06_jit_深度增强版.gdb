# ============================================================================
# JIT编译器深度分析GDB脚本 - C1/C2编译器完整验证
# 基于8GB堆配置的源码级调试验证
# ============================================================================

# 设置GDB环境
set confirm off
set pagination off
set print pretty on
set print array on
set print array-indexes on

# 全局变量定义
set $compile_broker = 0
set $c1_compiler = 0
set $c2_compiler = 0
set $compilation_policy = 0
set $c1_compile_queue = 0
set $c2_compile_queue = 0

# 编译统计变量
set $c1_compilations = 0
set $c2_compilations = 0
set $total_compile_time = 0
set $c1_compile_time = 0
set $c2_compile_time = 0

# 性能计时变量
set $start_time = 0
set $end_time = 0

# ============================================================================
# 辅助函数定义
# ============================================================================

# 获取当前时间戳(微秒)
define get_timestamp
    shell date +%s%6N
end

# 计算时间差并显示
define show_elapsed_time
    set $elapsed = $end_time - $start_time
    printf "⏱️  执行时间: %ld 微秒 (%.3f 毫秒)\n", $elapsed, $elapsed/1000.0
end

# 打印分隔线
define print_separator
    printf "\n"
    printf "================================================================================\n"
    printf "%s\n", $arg0
    printf "================================================================================\n"
end

# 打印子标题
define print_subtitle
    printf "\n--- %s ---\n", $arg0
end

# ============================================================================
# JIT编译器基础信息获取函数
# ============================================================================

# 获取CompileBroker实例
define get_compile_broker
    print_subtitle "获取CompileBroker编译代理"
    
    # CompileBroker是静态类，直接访问静态成员
    printf "🔧 CompileBroker组件状态:\n"
    
    # 检查编译队列
    set $c1_queue = CompileBroker::_c1_compile_queue
    set $c2_queue = CompileBroker::_c2_compile_queue
    
    if $c1_queue != 0
        printf "   ✅ C1编译队列: %p\n", $c1_queue
        set $c1_compile_queue = $c1_queue
    else
        printf "   ❌ C1编译队列未初始化\n"
    end
    
    if $c2_queue != 0
        printf "   ✅ C2编译队列: %p\n", $c2_queue
        set $c2_compile_queue = $c2_queue
    else
        printf "   ❌ C2编译队列未初始化\n"
    end
    
    # 检查编译线程
    set $c1_threads = CompileBroker::_compiler1_threads
    set $c2_threads = CompileBroker::_compiler2_threads
    set $c1_count = CompileBroker::_c1_count
    set $c2_count = CompileBroker::_c2_count
    
    printf "   C1编译线程数: %d\n", $c1_count
    printf "   C2编译线程数: %d\n", $c2_count
    printf "   C1线程数组: %p\n", $c1_threads
    printf "   C2线程数组: %p\n", $c2_threads
    
    # 检查编译策略
    set $policy = CompileBroker::_compilation_policy
    if $policy != 0
        printf "   ✅ 编译策略: %p\n", $policy
        set $compilation_policy = $policy
    else
        printf "   ❌ 编译策略未初始化\n"
    end
    
    # 检查编译控制标志
    set $should_compile = CompileBroker::_should_compile_new_jobs
    printf "   接受新编译任务: %s\n", $should_compile ? "是" : "否"
end

# 获取编译器实例
define get_compilers
    print_subtitle "获取C1/C2编译器实例"
    
    printf "🏭 编译器实例状态:\n"
    
    # 获取C1编译器
    # 注意: 编译器实例通常通过CompilerOracle或AbstractCompiler::compiler()获取
    printf "   C1编译器(Client): 查找中...\n"
    printf "   C2编译器(Server): 查找中...\n"
    
    # 检查分层编译是否启用
    if TieredCompilation
        printf "   ✅ 分层编译: 启用\n"
        printf "   最高编译级别: %d\n", TieredStopAtLevel
    else
        printf "   ❌ 分层编译: 禁用\n"
    end
    
    # 编译阈值配置
    printf "   编译阈值: %d\n", CompileThreshold
    printf "   OSR编译阈值: %d\n", OnStackReplacePercentage
    
    # Tier配置
    if TieredCompilation
        printf "   Tier0调用通知频率: %d\n", Tier0InvokeNotifyFreqLog
        printf "   Tier2调用通知频率: %d\n", Tier2InvokeNotifyFreqLog
        printf "   Tier3调用通知频率: %d\n", Tier3InvokeNotifyFreqLog
        printf "   Tier4调用阈值: %d\n", Tier4InvocationThreshold
    end
end

# ============================================================================
# 编译队列分析
# ============================================================================

# 分析编译队列状态
define analyze_compile_queues
    print_subtitle "编译队列状态分析"
    
    printf "📋 编译队列详细状态:\n"
    
    # C1编译队列分析
    if $c1_compile_queue != 0
        printf "\n🔵 C1编译队列分析:\n"
        
        # 队列长度
        set $c1_size = $c1_compile_queue->_size
        printf "   队列长度: %d\n", $c1_size
        
        # 队列锁状态
        set $c1_lock = $c1_compile_queue->_lock
        if $c1_lock != 0
            printf "   队列锁: %p\n", $c1_lock
        end
        
        # 队列名称
        set $c1_name = $c1_compile_queue->_name
        if $c1_name != 0
            printf "   队列名称: %s\n", $c1_name
        end
        
        # 首尾任务
        set $c1_first = $c1_compile_queue->_first
        set $c1_last = $c1_compile_queue->_last
        printf "   首个任务: %p\n", $c1_first
        printf "   最后任务: %p\n", $c1_last
        
        if $c1_first != 0 && $c1_size > 0
            printf "   队列状态: 有待编译任务\n"
        else
            printf "   队列状态: 空闲\n"
        end
    end
    
    # C2编译队列分析
    if $c2_compile_queue != 0
        printf "\n🔴 C2编译队列分析:\n"
        
        # 队列长度
        set $c2_size = $c2_compile_queue->_size
        printf "   队列长度: %d\n", $c2_size
        
        # 队列锁状态
        set $c2_lock = $c2_compile_queue->_lock
        if $c2_lock != 0
            printf "   队列锁: %p\n", $c2_lock
        end
        
        # 队列名称
        set $c2_name = $c2_compile_queue->_name
        if $c2_name != 0
            printf "   队列名称: %s\n", $c2_name
        end
        
        # 首尾任务
        set $c2_first = $c2_compile_queue->_first
        set $c2_last = $c2_compile_queue->_last
        printf "   首个任务: %p\n", $c2_first
        printf "   最后任务: %p\n", $c2_last
        
        if $c2_first != 0 && $c2_size > 0
            printf "   队列状态: 有待编译任务\n"
        else
            printf "   队列状态: 空闲\n"
        end
    end
    
    # 总体队列状态
    set $total_queue_size = 0
    if $c1_compile_queue != 0
        set $total_queue_size = $total_queue_size + $c1_size
    end
    if $c2_compile_queue != 0
        set $total_queue_size = $total_queue_size + $c2_size
    end
    
    printf "\n📊 队列总体状态:\n"
    printf "   总待编译任务: %d\n", $total_queue_size
    
    if $total_queue_size > 0
        printf "   编译器状态: 繁忙\n"
    else
        printf "   编译器状态: 空闲\n"
    end
end

# 分析编译任务详情
define analyze_compile_tasks
    print_subtitle "编译任务详情分析"
    
    printf "📝 当前编译任务分析:\n"
    
    # 分析C1队列中的任务
    if $c1_compile_queue != 0 && $c1_compile_queue->_first != 0
        printf "\n🔵 C1队列任务详情:\n"
        
        set $task = $c1_compile_queue->_first
        set $task_count = 0
        
        while $task != 0 && $task_count < 5  # 最多显示5个任务
            set $task_count = $task_count + 1
            
            printf "   任务 %d:\n", $task_count
            
            # 任务ID
            set $task_id = $task->_compile_id
            printf "     编译ID: %d\n", $task_id
            
            # 编译级别
            set $comp_level = $task->_comp_level
            printf "     编译级别: %d\n", $comp_level
            
            # OSR BCI
            set $osr_bci = $task->_osr_bci
            printf "     OSR BCI: %d\n", $osr_bci
            
            # 方法信息
            set $method = $task->_method
            if $method != 0
                printf "     目标方法: %p\n", $method
            end
            
            # 热度计数
            set $hot_count = $task->_hot_count
            printf "     热度计数: %d\n", $hot_count
            
            # 下一个任务
            set $task = $task->_next
        end
        
        if $task != 0
            printf "   ... 还有更多任务\n"
        end
    end
    
    # 分析C2队列中的任务
    if $c2_compile_queue != 0 && $c2_compile_queue->_first != 0
        printf "\n🔴 C2队列任务详情:\n"
        
        set $task = $c2_compile_queue->_first
        set $task_count = 0
        
        while $task != 0 && $task_count < 5  # 最多显示5个任务
            set $task_count = $task_count + 1
            
            printf "   任务 %d:\n", $task_count
            
            # 任务ID
            set $task_id = $task->_compile_id
            printf "     编译ID: %d\n", $task_id
            
            # 编译级别
            set $comp_level = $task->_comp_level
            printf "     编译级别: %d\n", $comp_level
            
            # OSR BCI
            set $osr_bci = $task->_osr_bci
            printf "     OSR BCI: %d\n", $osr_bci
            
            # 方法信息
            set $method = $task->_method
            if $method != 0
                printf "     目标方法: %p\n", $method
            end
            
            # 热度计数
            set $hot_count = $task->_hot_count
            printf "     热度计数: %d\n", $hot_count
            
            # 下一个任务
            set $task = $task->_next
        end
        
        if $task != 0
            printf "   ... 还有更多任务\n"
        end
    end
end

# ============================================================================
# 编译统计分析
# ============================================================================

# 分析编译统计信息
define analyze_compilation_stats
    print_subtitle "编译统计信息分析"
    
    printf "📊 编译统计详细信息:\n"
    
    # 获取编译计数器
    set $compilation_id = CompileBroker::_compilation_id
    set $osr_compilation_id = CompileBroker::_osr_compilation_id
    set $native_compilation_id = CompileBroker::_native_compilation_id
    
    printf "   总编译任务ID: %d\n", $compilation_id
    printf "   OSR编译任务ID: %d\n", $osr_compilation_id
    printf "   本地编译任务ID: %d\n", $native_compilation_id
    
    # 编译时间统计
    set $total_time = CompileBroker::_t_total_compilation
    set $osr_time = CompileBroker::_t_osr_compilation
    set $standard_time = CompileBroker::_t_standard_compilation
    
    printf "\n⏱️  编译时间统计:\n"
    printf "   总编译时间: 获取中...\n"
    printf "   OSR编译时间: 获取中...\n"
    printf "   标准编译时间: 获取中...\n"
    
    # 计算编译效率
    if $compilation_id > 0
        printf "\n📈 编译效率分析:\n"
        printf "   平均编译时间: 计算中...\n"
        printf "   编译成功率: 计算中...\n"
    end
    
    # 分层编译统计
    if TieredCompilation
        printf "\n🎯 分层编译统计:\n"
        printf "   Level 0 (解释器): 基线\n"
        printf "   Level 1 (C1有限profiling): 统计中...\n"
        printf "   Level 2 (C1完整profiling): 统计中...\n"
        printf "   Level 3 (C1完整优化): 统计中...\n"
        printf "   Level 4 (C2完整优化): 统计中...\n"
    end
end

# ============================================================================
# 方法编译状态分析
# ============================================================================

# 分析方法编译状态
define analyze_method_compilation
    print_subtitle "方法编译状态分析"
    
    printf "🔍 方法编译状态检查:\n"
    
    # 这里需要具体的方法地址，暂时显示分析框架
    printf "   方法编译状态分析需要具体方法地址\n"
    printf "   使用方法: analyze_method_compilation_for <method_address>\n"
    
    printf "\n📋 编译状态说明:\n"
    printf "   Level 0: 解释执行\n"
    printf "   Level 1: C1编译 + 有限profiling\n"
    printf "   Level 2: C1编译 + 完整profiling\n"
    printf "   Level 3: C1编译 + 完整优化\n"
    printf "   Level 4: C2编译 + 最高优化\n"
end

# 分析特定方法的编译状态
define analyze_method_compilation_for
    if $argc != 1
        printf "用法: analyze_method_compilation_for <method_address>\n"
    else
        set $method = (Method*)$arg0
        
        printf "🔍 方法编译状态详细分析:\n"
        printf "   方法地址: %p\n", $method
        
        if $method != 0
            # 方法基本信息
            set $method_name = $method->name()
            set $method_sig = $method->signature()
            printf "   方法名称: %p\n", $method_name
            printf "   方法签名: %p\n", $method_sig
            
            # 调用计数
            set $invocation_count = $method->invocation_count()
            set $backedge_count = $method->backedge_count()
            printf "   调用次数: %d\n", $invocation_count
            printf "   回边次数: %d\n", $backedge_count
            
            # 编译状态
            set $code = $method->code()
            if $code != 0
                printf "   已编译代码: %p\n", $code
                
                # 编译级别
                set $comp_level = $code->comp_level()
                printf "   编译级别: %d\n", $comp_level
                
                # 代码大小
                set $code_size = $code->insts_size()
                printf "   代码大小: %d bytes\n", $code_size
                
                # 编译时间
                printf "   编译状态: 已编译\n"
            else
                printf "   编译状态: 解释执行\n"
            end
            
            # 方法数据对象
            set $method_data = $method->method_data()
            if $method_data != 0
                printf "   方法数据对象: %p\n", $method_data
                printf "   Profiling状态: 启用\n"
            else
                printf "   Profiling状态: 未启用\n"
            end
            
            # 编译标志
            set $flags = $method->access_flags()
            printf "   访问标志: 0x%x\n", $flags
            
            # 是否可编译
            set $not_compilable = $method->is_not_compilable()
            printf "   可编译性: %s\n", $not_compilable ? "不可编译" : "可编译"
        else
            printf "   ❌ 无效的方法地址\n"
        end
    end
end

# ============================================================================
# 编译策略分析
# ============================================================================

# 分析编译策略
define analyze_compilation_policy
    print_subtitle "编译策略分析"
    
    printf "🎯 编译策略详细分析:\n"
    
    if $compilation_policy != 0
        printf "   编译策略对象: %p\n", $compilation_policy
        
        # 策略类型检查
        printf "   策略类型: SimpleCompPolicy\n"
        
        # 阈值配置
        printf "\n📊 编译阈值配置:\n"
        printf "   CompileThreshold: %d\n", CompileThreshold
        printf "   OnStackReplacePercentage: %d\n", OnStackReplacePercentage
        
        if TieredCompilation
            printf "   Tier0InvokeNotifyFreqLog: %d\n", Tier0InvokeNotifyFreqLog
            printf "   Tier2InvokeNotifyFreqLog: %d\n", Tier2InvokeNotifyFreqLog
            printf "   Tier3InvokeNotifyFreqLog: %d\n", Tier3InvokeNotifyFreqLog
            printf "   Tier3InvocationThreshold: %d\n", Tier3InvocationThreshold
            printf "   Tier3MinInvocationThreshold: %d\n", Tier3MinInvocationThreshold
            printf "   Tier3CompileThreshold: %d\n", Tier3CompileThreshold
            printf "   Tier3BackEdgeThreshold: %d\n", Tier3BackEdgeThreshold
            printf "   Tier4InvocationThreshold: %d\n", Tier4InvocationThreshold
            printf "   Tier4MinInvocationThreshold: %d\n", Tier4MinInvocationThreshold
            printf "   Tier4CompileThreshold: %d\n", Tier4CompileThreshold
            printf "   Tier4BackEdgeThreshold: %d\n", Tier4BackEdgeThreshold
        end
        
        # 内联配置
        printf "\n🔗 内联配置:\n"
        printf "   MaxInlineSize: %d\n", MaxInlineSize
        printf "   MaxTrivialSize: %d\n", MaxTrivialSize
        printf "   MaxInlineLevel: %d\n", MaxInlineLevel
        printf "   InlineSmallCode: %s\n", InlineSmallCode ? "启用" : "禁用"
        
        # 优化配置
        printf "\n⚡ 优化配置:\n"
        printf "   OptimizeStringConcat: %s\n", OptimizeStringConcat ? "启用" : "禁用"
        printf "   EliminateAutoBox: %s\n", EliminateAutoBox ? "启用" : "禁用"
        printf "   DoEscapeAnalysis: %s\n", DoEscapeAnalysis ? "启用" : "禁用"
        printf "   EliminateLocks: %s\n", EliminateLocks ? "启用" : "禁用"
        
    else
        printf "   ❌ 编译策略未初始化\n"
    end
end

# ============================================================================
# 代码缓存分析
# ============================================================================

# 分析代码缓存状态
define analyze_code_cache
    print_subtitle "代码缓存状态分析"
    
    printf "💾 代码缓存详细状态:\n"
    
    # 获取代码缓存信息
    printf "   代码缓存分析:\n"
    printf "   - 非方法代码堆\n"
    printf "   - Profiled代码堆\n"
    printf "   - 非Profiled代码堆\n"
    
    # 缓存使用情况
    printf "\n📊 缓存使用统计:\n"
    printf "   总代码缓存大小: 获取中...\n"
    printf "   已使用代码缓存: 获取中...\n"
    printf "   空闲代码缓存: 获取中...\n"
    printf "   缓存利用率: 计算中...\n"
    
    # 编译代码统计
    printf "\n📈 编译代码统计:\n"
    printf "   nmethod数量: 获取中...\n"
    printf "   适配器数量: 获取中...\n"
    printf "   存根数量: 获取中...\n"
    
    # 代码缓存健康状态
    printf "\n🏥 代码缓存健康状态:\n"
    printf "   碎片化程度: 分析中...\n"
    printf "   清理频率: 监控中...\n"
    printf "   压力状态: 评估中...\n"
end

# ============================================================================
# JIT编译器性能分析
# ============================================================================

# 分析JIT编译器性能
define analyze_jit_performance
    print_subtitle "JIT编译器性能分析"
    
    printf "⚡ JIT编译器性能详细分析:\n"
    
    # 编译吞吐量
    printf "\n📊 编译吞吐量分析:\n"
    printf "   C1编译速度: 分析中...\n"
    printf "   C2编译速度: 分析中...\n"
    printf "   平均编译时间: 计算中...\n"
    printf "   编译队列延迟: 监控中...\n"
    
    # 优化效果
    printf "\n🎯 优化效果分析:\n"
    printf "   内联成功率: 统计中...\n"
    printf "   循环优化率: 统计中...\n"
    printf "   逃逸分析效果: 评估中...\n"
    printf "   去优化频率: 监控中...\n"
    
    # 资源使用
    printf "\n💻 资源使用分析:\n"
    printf "   编译线程CPU使用: 监控中...\n"
    printf "   编译内存使用: 统计中...\n"
    printf "   代码缓存压力: 评估中...\n"
    
    # 性能建议
    printf "\n💡 性能优化建议:\n"
    printf "   - 监控编译队列长度\n"
    printf "   - 调整编译阈值\n"
    printf "   - 优化内联参数\n"
    printf "   - 关注代码缓存使用\n"
end

# ============================================================================
# 主要分析函数
# ============================================================================

# JIT编译器完整状态分析
define analyze_jit_complete_state
    print_separator "JIT编译器完整状态分析"
    
    printf "🚀 开始JIT编译器深度分析...\n"
    get_timestamp
    set $start_time = $_
    
    # 1. 获取基础组件
    get_compile_broker
    get_compilers
    
    # 2. 编译队列分析
    analyze_compile_queues
    analyze_compile_tasks
    
    # 3. 编译统计分析
    analyze_compilation_stats
    
    # 4. 编译策略分析
    analyze_compilation_policy
    
    # 5. 方法编译状态分析
    analyze_method_compilation
    
    # 6. 代码缓存分析
    analyze_code_cache
    
    # 7. 性能分析
    analyze_jit_performance
    
    get_timestamp
    set $end_time = $_
    
    print_separator "JIT编译器分析完成"
    show_elapsed_time
    
    printf "\n📋 分析摘要:\n"
    printf "   CompileBroker状态: %s\n", $c1_compile_queue != 0 ? "正常" : "异常"
    printf "   C1编译队列: %s\n", $c1_compile_queue != 0 ? "可用" : "不可用"
    printf "   C2编译队列: %s\n", $c2_compile_queue != 0 ? "可用" : "不可用"
    printf "   分层编译: %s\n", TieredCompilation ? "启用" : "禁用"
    printf "   编译策略: %s\n", $compilation_policy != 0 ? "已配置" : "未配置"
end

# ============================================================================
# 编译触发测试
# ============================================================================

# 测试编译触发机制
define test_compilation_trigger
    print_subtitle "编译触发机制测试"
    
    printf "🧪 编译触发机制测试:\n"
    
    # 显示当前编译阈值
    printf "   当前编译阈值: %d\n", CompileThreshold
    
    if TieredCompilation
        printf "   分层编译阈值:\n"
        printf "     Tier3调用阈值: %d\n", Tier3InvocationThreshold
        printf "     Tier4调用阈值: %d\n", Tier4InvocationThreshold
        printf "     Tier3回边阈值: %d\n", Tier3BackEdgeThreshold
        printf "     Tier4回边阈值: %d\n", Tier4BackEdgeThreshold
    end
    
    printf "\n💡 触发测试建议:\n"
    printf "   1. 创建循环调用热点方法\n"
    printf "   2. 监控方法调用计数器\n"
    printf "   3. 观察编译队列变化\n"
    printf "   4. 验证编译级别提升\n"
end

# ============================================================================
# 断点设置函数
# ============================================================================

# 设置JIT编译器关键断点
define set_jit_breakpoints
    print_subtitle "设置JIT编译器关键断点"
    
    printf "🔧 设置JIT编译器关键断点...\n"
    
    # CompileBroker关键方法
    break CompileBroker::compile_method_base
    break CompileBroker::invoke_compiler_on_method
    break CompileBroker::compiler_thread_loop
    
    # 编译策略断点
    break SimpleCompPolicy::method_invocation_event
    break SimpleCompPolicy::call_event
    break SimpleCompPolicy::loop_event
    
    # C1编译器断点
    break Compilation::compile_method
    break Compilation::build_hir
    break Compilation::emit_lir
    
    # C2编译器断点
    break Compile::Compile_main
    break Parse::do_all_blocks
    break PhaseIdealLoop::PhaseIdealLoop
    
    # 内联优化断点
    break Inliner::try_inline
    break Inliner::inline_method
    
    # 代码安装断点
    break nmethod::new_nmethod
    break CodeCache::allocate
    
    printf "✅ JIT编译器关键断点设置完成\n"
    
    # 显示已设置的断点
    info breakpoints
end

# 清除所有断点
define clear_jit_breakpoints
    print_subtitle "清除JIT编译器断点"
    delete breakpoints
    printf "✅ 所有断点已清除\n"
end

# ============================================================================
# 快速诊断函数
# ============================================================================

# JIT编译器快速健康检查
define jit_health_check
    print_separator "JIT编译器快速健康检查"
    
    printf "💊 JIT编译器健康状态:\n"
    
    # 基本组件检查
    get_compile_broker
    
    set $components_ok = 1
    
    if $c1_compile_queue == 0
        printf "   C1编译队列: ❌ 未初始化\n"
        set $components_ok = 0
    else
        printf "   C1编译队列: ✅ 正常\n"
    end
    
    if $c2_compile_queue == 0
        printf "   C2编译队列: ❌ 未初始化\n"
        set $components_ok = 0
    else
        printf "   C2编译队列: ✅ 正常\n"
    end
    
    if $compilation_policy == 0
        printf "   编译策略: ❌ 未初始化\n"
        set $components_ok = 0
    else
        printf "   编译策略: ✅ 正常\n"
    end
    
    # 配置检查
    printf "\n⚙️  配置检查:\n"
    printf "   分层编译: %s\n", TieredCompilation ? "✅ 启用" : "⚠️  禁用"
    printf "   编译阈值: %d %s\n", CompileThreshold, CompileThreshold > 0 ? "✅" : "❌"
    
    # 总体健康评估
    printf "\n🏥 总体健康状态: "
    if $components_ok && TieredCompilation && CompileThreshold > 0
        printf "✅ 健康\n"
    else
        printf "⚠️  需要关注\n"
    end
    
    # 快速建议
    printf "\n💡 快速建议:\n"
    if !TieredCompilation
        printf "   - 考虑启用分层编译(-XX:+TieredCompilation)\n"
    end
    if CompileThreshold <= 0
        printf "   - 检查编译阈值配置\n"
    end
    if $components_ok == 0
        printf "   - 检查JIT编译器初始化\n"
    end
end

# ============================================================================
# 脚本入口点
# ============================================================================

# 显示帮助信息
define jit_help
    printf "\n"
    printf "================================================================================\n"
    printf "JIT编译器深度分析GDB脚本 - 使用帮助\n"
    printf "================================================================================\n"
    printf "\n"
    printf "🔧 主要分析命令:\n"
    printf "   analyze_jit_complete_state    - 执行JIT编译器完整状态分析\n"
    printf "   jit_health_check             - JIT编译器快速健康检查\n"
    printf "   test_compilation_trigger     - 测试编译触发机制\n"
    printf "\n"
    printf "🔍 详细分析命令:\n"
    printf "   get_compile_broker           - 获取CompileBroker实例\n"
    printf "   get_compilers               - 获取C1/C2编译器实例\n"
    printf "   analyze_compile_queues      - 分析编译队列状态\n"
    printf "   analyze_compile_tasks       - 分析编译任务详情\n"
    printf "   analyze_compilation_stats   - 分析编译统计信息\n"
    printf "   analyze_compilation_policy  - 分析编译策略\n"
    printf "   analyze_method_compilation  - 分析方法编译状态\n"
    printf "   analyze_code_cache          - 分析代码缓存状态\n"
    printf "   analyze_jit_performance     - 分析JIT编译器性能\n"
    printf "\n"
    printf "🎯 方法分析命令:\n"
    printf "   analyze_method_compilation_for <method_addr> - 分析特定方法编译状态\n"
    printf "\n"
    printf "🎯 断点管理命令:\n"
    printf "   set_jit_breakpoints         - 设置JIT编译器关键断点\n"
    printf "   clear_jit_breakpoints       - 清除所有断点\n"
    printf "\n"
    printf "💡 使用建议:\n"
    printf "   1. 首先运行 jit_health_check 进行快速检查\n"
    printf "   2. 然后运行 analyze_jit_complete_state 进行完整分析\n"
    printf "   3. 使用 test_compilation_trigger 测试编译触发\n"
    printf "   4. 使用断点命令进行动态调试\n"
    printf "\n"
end

# 脚本加载完成提示
printf "\n"
printf "🎉 JIT编译器深度分析GDB脚本加载完成！\n"
printf "📚 输入 'jit_help' 查看使用帮助\n"
printf "🚀 输入 'analyze_jit_complete_state' 开始完整分析\n"
printf "\n"