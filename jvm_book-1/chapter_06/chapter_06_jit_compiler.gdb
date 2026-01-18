# 第06章：JIT编译器优化 - C1/C2编译器深度分析 GDB调试脚本
# 标准环境：-Xms=8GB -Xmx=8GB, G1GC, 非大页, 非NUMA

# 基础设置
set confirm off
set pagination off
set print pretty on
set print array on
set logging file chapter_06_jit_debug.log
set logging on

# 定义颜色输出宏
define print_header
    printf "\n\033[1;34m=== %s ===\033[0m\n", $arg0
end

define print_info
    printf "\033[1;32m[INFO]\033[0m %s\n", $arg0
end

define print_warning
    printf "\033[1;33m[WARNING]\033[0m %s\n", $arg0
end

define print_error
    printf "\033[1;31m[ERROR]\033[0m %s\n", $arg0
end

# JIT编译器初始化验证
define verify_jit_initialization
    print_header "JIT编译器初始化验证"
    
    # 检查编译器线程
    print_info "检查C1编译器线程"
    if CompileBroker::_compiler1_threads != 0
        printf "C1编译器线程数: %d\n", CompileBroker::_compiler1_count
        set $i = 0
        while $i < CompileBroker::_compiler1_count
            if CompileBroker::_compiler1_threads[$i] != 0
                printf "  线程%d: %p (状态: %d)\n", $i, CompileBroker::_compiler1_threads[$i], CompileBroker::_compiler1_threads[$i]->_thread_state
            end
            set $i = $i + 1
        end
    else
        print_warning "C1编译器线程未初始化"
    end
    
    print_info "检查C2编译器线程"
    if CompileBroker::_compiler2_threads != 0
        printf "C2编译器线程数: %d\n", CompileBroker::_compiler2_count
        set $i = 0
        while $i < CompileBroker::_compiler2_count
            if CompileBroker::_compiler2_threads[$i] != 0
                printf "  线程%d: %p (状态: %d)\n", $i, CompileBroker::_compiler2_threads[$i], CompileBroker::_compiler2_threads[$i]->_thread_state
            end
            set $i = $i + 1
        end
    else
        print_warning "C2编译器线程未初始化"
    end
    
    # 检查编译队列
    print_info "检查编译队列状态"
    if CompileBroker::_c1_compile_queue != 0
        printf "C1编译队列大小: %d\n", CompileBroker::_c1_compile_queue->_size
        printf "C1编译队列容量: %d\n", CompileBroker::_c1_compile_queue->_capacity
    end
    
    if CompileBroker::_c2_compile_queue != 0
        printf "C2编译队列大小: %d\n", CompileBroker::_c2_compile_queue->_size
        printf "C2编译队列容量: %d\n", CompileBroker::_c2_compile_queue->_capacity
    end
end

# 编译触发机制验证
define verify_compilation_trigger
    print_header "编译触发机制验证"
    
    print_info "检查编译阈值配置"
    printf "Tier1编译阈值: %d\n", Tier1CompileThreshold
    printf "Tier2编译阈值: %d\n", Tier2CompileThreshold  
    printf "Tier3编译阈值: %d\n", Tier3CompileThreshold
    printf "Tier4编译阈值: %d\n", Tier4CompileThreshold
    
    print_info "检查内联配置"
    printf "最大内联大小: %d\n", MaxInlineSize
    printf "最大内联层级: %d\n", MaxInlineLevel
    printf "内联调用频率阈值: %d\n", CallFrequencyInliningThreshold
    
    print_info "检查循环优化配置"
    printf "循环展开限制: %d\n", LoopUnrollLimit
    printf "循环展开最小值: %d\n", LoopUnrollMin
    printf "循环优化限制: %d\n", LoopOptsCount
end

# CodeCache状态检查
define check_codecache_status
    print_header "CodeCache状态检查"
    
    print_info "检查CodeCache配置"
    printf "初始CodeCache大小: %lu bytes\n", InitialCodeCacheSize
    printf "保留CodeCache大小: %lu bytes\n", ReservedCodeCacheSize
    
    # 检查各个代码堆
    print_info "检查NonNMethod代码堆"
    if CodeCache::_heap != 0
        set $non_nmethod_heap = CodeCache::_heap->_non_nmethod_heap
        if $non_nmethod_heap != 0
            printf "  容量: %lu bytes\n", $non_nmethod_heap->_memory._capacity
            printf "  已用: %lu bytes\n", $non_nmethod_heap->_memory._used
            printf "  可用: %lu bytes\n", $non_nmethod_heap->_memory._capacity - $non_nmethod_heap->_memory._used
        end
    end
    
    print_info "检查Profiled代码堆"
    if CodeCache::_heap != 0
        set $profiled_heap = CodeCache::_heap->_profiled_heap  
        if $profiled_heap != 0
            printf "  容量: %lu bytes\n", $profiled_heap->_memory._capacity
            printf "  已用: %lu bytes\n", $profiled_heap->_memory._used
            printf "  可用: %lu bytes\n", $profiled_heap->_memory._capacity - $profiled_heap->_memory._used
        end
    end
    
    print_info "检查NonProfiled代码堆"
    if CodeCache::_heap != 0
        set $non_profiled_heap = CodeCache::_heap->_non_profiled_heap
        if $non_profiled_heap != 0
            printf "  容量: %lu bytes\n", $non_profiled_heap->_memory._capacity
            printf "  已用: %lu bytes\n", $non_profiled_heap->_memory._used  
            printf "  可用: %lu bytes\n", $non_profiled_heap->_memory._capacity - $non_profiled_heap->_memory._used
        end
    end
end

# 编译统计信息
define show_compilation_stats
    print_header "编译统计信息"
    
    print_info "C1编译器统计"
    if CompileBroker::_counters_c1 != 0
        printf "总编译数: %d\n", CompileBroker::_counters_c1->_total_compiles
        printf "编译放弃数: %d\n", CompileBroker::_counters_c1->_total_bailouts
        printf "编译失效数: %d\n", CompileBroker::_counters_c1->_total_invalidated
        printf "总编译时间: %ld ms\n", CompileBroker::_counters_c1->_total_compile_time / 1000000
    end
    
    print_info "C2编译器统计"  
    if CompileBroker::_counters_c2 != 0
        printf "总编译数: %d\n", CompileBroker::_counters_c2->_total_compiles
        printf "编译放弃数: %d\n", CompileBroker::_counters_c2->_total_bailouts
        printf "编译失效数: %d\n", CompileBroker::_counters_c2->_total_invalidated
        printf "总编译时间: %ld ms\n", CompileBroker::_counters_c2->_total_compile_time / 1000000
    end
end

# 方法编译状态检查
define check_method_compilation
    if $argc != 1
        print_error "用法: check_method_compilation <method_address>"
        help check_method_compilation
    else
        print_header "方法编译状态检查"
        set $method = (Method*)$arg0
        
        print_info "方法基本信息"
        printf "方法地址: %p\n", $method
        if $method->_name != 0
            printf "方法名: %s\n", $method->_name->_body
        end
        if $method->_signature != 0  
            printf "方法签名: %s\n", $method->_signature->_body
        end
        
        print_info "编译相关计数器"
        printf "调用计数器: %d\n", $method->_invocation_counter._counter
        printf "回边计数器: %d\n", $method->_backedge_counter._counter
        
        print_info "编译状态"
        printf "编译级别: %d\n", $method->_highest_comp_level
        printf "编译OSR级别: %d\n", $method->_highest_osr_comp_level
        
        if $method->_code != 0
            printf "已编译代码地址: %p\n", $method->_code
            printf "代码大小: %d bytes\n", $method->_code->_total_size
        else
            printf "未编译\n"
        end
    end
end

# 内联决策跟踪
define trace_inlining_decisions
    print_header "内联决策跟踪"
    
    # 设置内联相关断点
    print_info "设置内联跟踪断点"
    break InlineTree::ok_to_inline
    commands
        silent
        printf "\n[INLINE] 检查内联: %s.%s\n", $rdi->_method->_method_holder->_name->_body, $rdi->_method->_name->_body
        printf "  方法大小: %d bytes\n", $rdi->_method->_code_size
        printf "  内联深度: %d\n", $rdi->_inline_level
        continue
    end
    
    break InlineTree::should_inline  
    commands
        silent
        printf "[INLINE] 内联决策: %s.%s -> ", $rsi->_method_holder->_name->_body, $rsi->_name->_body
        if $rax == 1
            printf "接受\n"
        else
            printf "拒绝\n"
        end
        continue
    end
end

# 循环优化跟踪
define trace_loop_optimizations
    print_header "循环优化跟踪"
    
    # 设置循环优化断点
    print_info "设置循环优化跟踪断点"
    break PhaseIdealLoop::build_and_optimize
    commands
        silent
        printf "\n[LOOP] 开始循环优化阶段\n"
        continue
    end
    
    break PhaseIdealLoop::do_unroll
    commands
        silent  
        printf "[LOOP] 执行循环展开\n"
        printf "  循环头: %p\n", $rdi->_head
        continue
    end
    
    break PhaseIdealLoop::hoist_uses
    commands
        silent
        printf "[LOOP] 执行循环不变式外提\n"
        continue
    end
end

# 逃逸分析跟踪
define trace_escape_analysis
    print_header "逃逸分析跟踪"
    
    # 设置逃逸分析断点
    print_info "设置逃逸分析跟踪断点"
    break ConnectionGraph::compute_escape
    commands
        silent
        printf "\n[ESCAPE] 开始逃逸分析\n"
        continue
    end
    
    break PhaseMacroExpand::eliminate_allocate_node
    commands
        silent
        printf "[ESCAPE] 消除分配节点: %p\n", $rdi
        continue
    end
end

# 去优化事件跟踪
define trace_deoptimization
    print_header "去优化事件跟踪"
    
    # 设置去优化断点
    print_info "设置去优化跟踪断点"
    break Deoptimization::uncommon_trap
    commands
        silent
        printf "\n[DEOPT] 触发去优化陷阱\n"
        printf "  原因: %d\n", $rsi & 0xFF
        printf "  动作: %d\n", ($rsi >> 8) & 0xFF
        continue
    end
    
    break Deoptimization::deoptimize_frame
    commands
        silent
        printf "[DEOPT] 去优化栈帧: %p\n", $rsi
        continue
    end
end

# 综合JIT性能分析
define analyze_jit_performance
    print_header "JIT编译器性能分析"
    
    # 基础状态检查
    verify_jit_initialization
    verify_compilation_trigger
    check_codecache_status
    show_compilation_stats
    
    # 启用跟踪
    trace_inlining_decisions
    trace_loop_optimizations  
    trace_escape_analysis
    trace_deoptimization
    
    print_info "JIT性能分析配置完成，继续执行程序..."
end

# 编译队列监控
define monitor_compile_queue
    print_header "编译队列实时监控"
    
    while 1
        clear
        printf "\n=== 编译队列状态 (按Ctrl+C停止) ===\n"
        
        if CompileBroker::_c1_compile_queue != 0
            printf "C1队列: %d/%d\n", CompileBroker::_c1_compile_queue->_size, CompileBroker::_c1_compile_queue->_capacity
        end
        
        if CompileBroker::_c2_compile_queue != 0  
            printf "C2队列: %d/%d\n", CompileBroker::_c2_compile_queue->_size, CompileBroker::_c2_compile_queue->_capacity
        end
        
        # CodeCache使用情况
        if CodeCache::_heap != 0
            set $total_capacity = 0
            set $total_used = 0
            
            if CodeCache::_heap->_non_nmethod_heap != 0
                set $total_capacity = $total_capacity + CodeCache::_heap->_non_nmethod_heap->_memory._capacity
                set $total_used = $total_used + CodeCache::_heap->_non_nmethod_heap->_memory._used
            end
            
            if CodeCache::_heap->_profiled_heap != 0
                set $total_capacity = $total_capacity + CodeCache::_heap->_profiled_heap->_memory._capacity  
                set $total_used = $total_used + CodeCache::_heap->_profiled_heap->_memory._used
            end
            
            if CodeCache::_heap->_non_profiled_heap != 0
                set $total_capacity = $total_capacity + CodeCache::_heap->_non_profiled_heap->_memory._capacity
                set $total_used = $total_used + CodeCache::_heap->_non_profiled_heap->_memory._used
            end
            
            printf "CodeCache: %lu/%lu (%.1f%%)\n", $total_used, $total_capacity, ($total_used * 100.0) / $total_capacity
        end
        
        sleep 1
    end
end

# 热点方法分析
define analyze_hotspot_methods
    print_header "热点方法分析"
    
    print_info "分析编译队列中的热点方法"
    
    # 遍历C1编译队列
    if CompileBroker::_c1_compile_queue != 0
        printf "\nC1编译队列中的方法:\n"
        set $queue = CompileBroker::_c1_compile_queue
        set $task = $queue->_first
        set $count = 0
        
        while $task != 0 && $count < 10
            if $task->_method != 0
                printf "  [%d] %s.%s (调用:%d, 回边:%d)\n", $count, \
                       $task->_method->_method_holder->_name->_body, \
                       $task->_method->_name->_body, \
                       $task->_method->_invocation_counter._counter, \
                       $task->_method->_backedge_counter._counter
            end
            set $task = $task->_next
            set $count = $count + 1
        end
    end
    
    # 遍历C2编译队列
    if CompileBroker::_c2_compile_queue != 0
        printf "\nC2编译队列中的方法:\n"
        set $queue = CompileBroker::_c2_compile_queue
        set $task = $queue->_first
        set $count = 0
        
        while $task != 0 && $count < 10
            if $task->_method != 0
                printf "  [%d] %s.%s (调用:%d, 回边:%d)\n", $count, \
                       $task->_method->_method_holder->_name->_body, \
                       $task->_method->_name->_body, \
                       $task->_method->_invocation_counter._counter, \
                       $task->_method->_backedge_counter._counter
            end
            set $task = $task->_next
            set $count = $count + 1
        end
    end
end

# 编译时间分析
define analyze_compilation_time
    print_header "编译时间分析"
    
    if CompileBroker::_counters_c1 != 0
        set $c1_total_time = CompileBroker::_counters_c1->_total_compile_time
        set $c1_total_compiles = CompileBroker::_counters_c1->_total_compiles
        
        if $c1_total_compiles > 0
            printf "C1平均编译时间: %.2f ms\n", ($c1_total_time / 1000000.0) / $c1_total_compiles
        end
    end
    
    if CompileBroker::_counters_c2 != 0
        set $c2_total_time = CompileBroker::_counters_c2->_total_compile_time
        set $c2_total_compiles = CompileBroker::_counters_c2->_total_compiles
        
        if $c2_total_compiles > 0
            printf "C2平均编译时间: %.2f ms\n", ($c2_total_time / 1000000.0) / $c2_total_compiles
        end
    end
end

# 主分析函数
define jit_full_analysis
    print_header "JIT编译器完整分析"
    
    # 执行所有分析
    analyze_jit_performance
    analyze_hotspot_methods
    analyze_compilation_time
    
    print_info "JIT编译器分析完成"
    print_info "使用 'monitor_compile_queue' 进行实时监控"
    print_info "使用 'check_method_compilation <method_addr>' 检查特定方法"
end

# 帮助信息
define jit_help
    print_header "JIT编译器调试命令帮助"
    
    printf "基础分析命令:\n"
    printf "  jit_full_analysis          - 执行完整JIT分析\n"
    printf "  verify_jit_initialization  - 验证JIT初始化\n"
    printf "  verify_compilation_trigger - 验证编译触发机制\n"
    printf "  check_codecache_status     - 检查CodeCache状态\n"
    printf "  show_compilation_stats     - 显示编译统计\n"
    printf "\n"
    
    printf "监控命令:\n"
    printf "  monitor_compile_queue      - 实时监控编译队列\n"
    printf "  analyze_hotspot_methods    - 分析热点方法\n"
    printf "  analyze_compilation_time   - 分析编译时间\n"
    printf "\n"
    
    printf "跟踪命令:\n"
    printf "  trace_inlining_decisions   - 跟踪内联决策\n"
    printf "  trace_loop_optimizations   - 跟踪循环优化\n"
    printf "  trace_escape_analysis      - 跟踪逃逸分析\n"
    printf "  trace_deoptimization       - 跟踪去优化事件\n"
    printf "\n"
    
    printf "方法分析:\n"
    printf "  check_method_compilation <addr> - 检查方法编译状态\n"
    printf "\n"
    
    printf "示例用法:\n"
    printf "  (gdb) jit_full_analysis\n"
    printf "  (gdb) monitor_compile_queue\n"
    printf "  (gdb) check_method_compilation 0x7f8b2c001234\n"
end

# 初始化消息
print_header "JIT编译器调试脚本已加载"
print_info "输入 'jit_help' 查看可用命令"
print_info "输入 'jit_full_analysis' 开始完整分析"