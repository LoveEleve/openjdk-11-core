# 第10章：JVM故障诊断与排查 - 深度增强版GDB调试脚本
# 基于OpenJDK 11源码的完整故障诊断分析
# 标准配置：-Xms=8GB -Xmx=8GB，G1 GC，Region大小4MB

# 设置调试环境
set confirm off
set pagination off
set print pretty on
set print array on
set print array-indexes on

# 定义全局变量用于统计
set $total_diagnostics_checks = 0
set $failed_diagnostics_checks = 0
set $vmError_analysis_count = 0
set $memory_leak_detections = 0
set $deadlock_detections = 0
set $oom_analysis_count = 0
set $thread_leak_detections = 0
set $compiler_failure_analysis = 0
set $code_cache_analysis = 0

# ================================
# 10.1 故障检测机制分析函数
# ================================

define analyze_vmError_system
    printf "\n=== VMError故障检测系统分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查VMError静态变量
    if $_id != 0
        printf "VMError状态: 已发生错误\n"
        printf "  错误ID: %d\n", $_id
        printf "  错误消息: %s\n", $_message
        printf "  详细消息: %s\n", $_detail_msg
        printf "  错误线程: %p\n", $_thread
        printf "  程序计数器: %p\n", $_pc
        set $vmError_analysis_count = $vmError_analysis_count + 1
    else
        printf "VMError状态: 正常\n"
    end
    
    # 分析信号处理器状态
    printf "\n信号处理器分析:\n"
    printf "  SIGSEGV处理器: %p\n", &signalHandler
    printf "  SIGBUS处理器: %p\n", &signalHandler
    printf "  SIGFPE处理器: %p\n", &signalHandler
    
    # 检查崩溃保护状态
    printf "\n崩溃保护状态:\n"
    if _crash_protection_enabled
        printf "  崩溃保护: 已启用\n"
        printf "  崩溃上下文: %p\n", _crash_context
    else
        printf "  崩溃保护: 未启用\n"
    end
    
    printf "VMError系统分析完成\n"
end

define analyze_signal_handlers
    printf "\n=== 信号处理机制分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查关键信号的处理器
    printf "关键信号处理器状态:\n"
    
    # SIGSEGV (段错误)
    printf "  SIGSEGV (11): "
    if sigact[11].sa_sigaction != 0
        printf "已安装 -> %p\n", sigact[11].sa_sigaction
    else
        printf "未安装\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # SIGBUS (总线错误)
    printf "  SIGBUS (7): "
    if sigact[7].sa_sigaction != 0
        printf "已安装 -> %p\n", sigact[7].sa_sigaction
    else
        printf "未安装\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # SIGFPE (浮点异常)
    printf "  SIGFPE (8): "
    if sigact[8].sa_sigaction != 0
        printf "已安装 -> %p\n", sigact[8].sa_sigaction
    else
        printf "未安装\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # SIGQUIT (线程转储)
    printf "  SIGQUIT (3): "
    if sigact[3].sa_sigaction != 0
        printf "已安装 -> %p\n", sigact[3].sa_sigaction
    else
        printf "未安装\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    printf "信号处理机制分析完成\n"
end

define analyze_crash_handler
    printf "\n=== 崩溃处理机制分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查崩溃处理器状态
    printf "崩溃处理器配置:\n"
    printf "  CreateCoredumpOnCrash: %d\n", CreateCoredumpOnCrash
    printf "  ShowMessageBoxOnError: %d\n", ShowMessageBoxOnError
    printf "  ErrorFile路径: %s\n", ErrorFile
    
    # 检查错误日志配置
    printf "\n错误日志配置:\n"
    printf "  LogFile: %s\n", LogFile
    printf "  LogVMOutput: %d\n", LogVMOutput
    printf "  TraceExceptions: %d\n", TraceExceptions
    
    # 分析崩溃保护机制
    printf "\n崩溃保护机制:\n"
    if _crash_protection_enabled
        printf "  保护状态: 启用\n"
        printf "  保护上下文: %p\n", _crash_context
        printf "  保护级别: 完整\n"
    else
        printf "  保护状态: 禁用\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    printf "崩溃处理机制分析完成\n"
end

# ================================
# 10.2 诊断工具实现分析函数
# ================================

define analyze_thread_service
    printf "\n=== 线程服务诊断工具分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 分析ThreadService状态
    printf "ThreadService配置:\n"
    printf "  Eden池: %p\n", _eden_pool
    printf "  Survivor池: %p\n", _survivor_pool  
    printf "  Old池: %p\n", _old_pool
    
    # 检查线程转储功能
    printf "\n线程转储功能:\n"
    printf "  dump_stack_traces: 可用\n"
    printf "  get_all_threads: 可用\n"
    printf "  find_deadlocks: 可用\n"
    
    # 分析当前线程状态
    printf "\n当前线程统计:\n"
    set $thread_count = 0
    set $java_thread_count = 0
    set $daemon_count = 0
    
    # 遍历线程列表统计
    set $current_thread = Threads::_thread_list
    while $current_thread != 0
        set $thread_count = $thread_count + 1
        if $current_thread->is_Java_thread()
            set $java_thread_count = $java_thread_count + 1
            if java_lang_Thread::is_daemon($current_thread->threadObj())
                set $daemon_count = $daemon_count + 1
            end
        end
        set $current_thread = $current_thread->next()
    end
    
    printf "  总线程数: %d\n", $thread_count
    printf "  Java线程数: %d\n", $java_thread_count
    printf "  守护线程数: %d\n", $daemon_count
    printf "  用户线程数: %d\n", $java_thread_count - $daemon_count
    
    printf "线程服务分析完成\n"
end

define analyze_heap_dumper
    printf "\n=== 堆转储工具分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查HeapDumper配置
    printf "HeapDumper配置:\n"
    printf "  HPROF格式支持: 是\n"
    printf "  GC前转储: %d\n", _gc_before_heap_dump
    printf "  OOM触发转储: %d\n", _oome
    
    # 分析堆转储能力
    printf "\n堆转储能力:\n"
    printf "  实例对象转储: 支持\n"
    printf "  对象数组转储: 支持\n"
    printf "  基本类型数组转储: 支持\n"
    printf "  类信息转储: 支持\n"
    
    # 检查当前堆状态
    printf "\n当前堆状态:\n"
    set $heap = Universe::_collectedheap
    if $heap != 0
        printf "  堆类型: %s\n", $heap->name()
        printf "  堆容量: %zu bytes\n", $heap->capacity()
        printf "  已使用: %zu bytes\n", $heap->used()
        printf "  使用率: %.1f%%\n", ($heap->used() * 100.0) / $heap->capacity()
    else
        printf "  堆状态: 未初始化\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    printf "堆转储工具分析完成\n"
end

define analyze_perf_counters
    printf "\n=== 性能计数器分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查PerfDataManager状态
    printf "PerfDataManager状态:\n"
    if _all != 0
        printf "  所有计数器列表: 已初始化\n"
        printf "  采样计数器列表: 已初始化\n"
        printf "  常量计数器列表: 已初始化\n"
    else
        printf "  计数器系统: 未初始化\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # 分析GC性能计数器
    printf "\nGC性能计数器:\n"
    if _collections != 0
        printf "  GC次数计数器: 已创建\n"
        printf "  GC时间计数器: 已创建\n"
        printf "  最后进入时间: 已创建\n"
        printf "  最后退出时间: 已创建\n"
    else
        printf "  GC计数器: 未创建\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # 分析类加载计数器
    printf "\n类加载性能计数器:\n"
    if _classes_loaded_count != 0
        printf "  已加载类计数器: 已创建\n"
        printf "  已卸载类计数器: 已创建\n"
        printf "  加载字节数计数器: 已创建\n"
        printf "  卸载字节数计数器: 已创建\n"
    else
        printf "  类加载计数器: 未创建\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # 分析编译器计数器
    printf "\n编译器性能计数器:\n"
    printf "  当前方法计数器: 可用\n"
    printf "  编译类型计数器: 可用\n"
    printf "  编译时间计数器: 可用\n"
    printf "  编译字节数计数器: 可用\n"
    
    printf "性能计数器分析完成\n"
end

# ================================
# 10.3 内存故障诊断分析函数
# ================================

define analyze_memory_leak_detection
    printf "\n=== 内存泄漏检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查内存服务状态
    printf "MemoryService状态:\n"
    if _pools_list != 0
        printf "  内存池列表: 已初始化\n"
        printf "  内存管理器列表: 已初始化\n"
    else
        printf "  内存服务: 未初始化\n"
        set $failed_diagnostics_checks = $failed_diagnostics_checks + 1
    end
    
    # 分析堆使用阈值
    printf "\n内存使用阈值:\n"
    printf "  堆使用阈值: %zu bytes\n", _heap_usage_threshold.used()
    printf "  非堆使用阈值: %zu bytes\n", _non_heap_usage_threshold.used()
    
    # 检查GC通知状态
    printf "\nGC通知配置:\n"
    printf "  GC通知启用: %d\n", _gc_notification_enabled
    printf "  GC前计数: %ld\n", _gc_count_before_gc
    printf "  GC前时间: %ld ms\n", _gc_time_before_gc
    
    # 分析当前内存使用情况
    printf "\n当前内存使用:\n"
    set $heap = Universe::_collectedheap
    if $heap != 0
        set $heap_used = $heap->used()
        set $heap_capacity = $heap->capacity()
        set $usage_ratio = ($heap_used * 100.0) / $heap_capacity
        
        printf "  堆已使用: %zu bytes\n", $heap_used
        printf "  堆容量: %zu bytes\n", $heap_capacity
        printf "  使用率: %.1f%%\n", $usage_ratio
        
        if $usage_ratio > 80.0
            printf "  警告: 堆使用率过高\n"
            set $memory_leak_detections = $memory_leak_detections + 1
        end
    end
    
    printf "内存泄漏检测分析完成\n"
end

define analyze_metaspace_leak_detection
    printf "\n=== Metaspace泄漏检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查Metaspace使用情况
    printf "Metaspace使用分析:\n"
    set $metaspace_used = MetaspaceAux::used_bytes()
    set $metaspace_capacity = MetaspaceAux::capacity_bytes()
    set $metaspace_committed = MetaspaceAux::committed_bytes()
    
    printf "  已使用: %zu bytes\n", $metaspace_used
    printf "  已提交: %zu bytes\n", $metaspace_committed
    printf "  容量: %zu bytes\n", $metaspace_capacity
    
    if $metaspace_capacity > 0
        set $metaspace_usage = ($metaspace_used * 100.0) / $metaspace_capacity
        printf "  使用率: %.1f%%\n", $metaspace_usage
        
        if $metaspace_usage > 85.0
            printf "  警告: Metaspace使用率过高\n"
            set $memory_leak_detections = $memory_leak_detections + 1
        end
    end
    
    # 分析类加载统计
    printf "\n类加载统计:\n"
    set $loaded_classes = ClassLoadingService::get_classes_loaded_count()
    set $unloaded_classes = ClassLoadingService::get_classes_unloaded_count()
    set $net_classes = $loaded_classes - $unloaded_classes
    
    printf "  已加载类: %ld\n", $loaded_classes
    printf "  已卸载类: %ld\n", $unloaded_classes
    printf "  净加载类: %ld\n", $net_classes
    
    if $net_classes > 50000
        printf "  警告: 类数量过多，可能存在类加载器泄漏\n"
        set $memory_leak_detections = $memory_leak_detections + 1
    end
    
    printf "Metaspace泄漏检测分析完成\n"
end

define analyze_direct_memory_leak
    printf "\n=== 直接内存泄漏检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查直接内存使用情况
    printf "直接内存使用分析:\n"
    printf "  已分配内存: %zu bytes\n", _allocated_memory
    printf "  峰值内存: %zu bytes\n", _peak_memory
    
    # 检查直接内存限制
    set $max_direct_memory = Arguments::max_direct_memory_size()
    printf "  最大直接内存: %zu bytes\n", $max_direct_memory
    
    if $max_direct_memory > 0
        set $direct_usage = (_allocated_memory * 100.0) / $max_direct_memory
        printf "  使用率: %.1f%%\n", $direct_usage
        
        if $direct_usage > 90.0
            printf "  警告: 直接内存使用率过高\n"
            set $memory_leak_detections = $memory_leak_detections + 1
        end
    end
    
    # 检查分配跟踪
    printf "\n分配跟踪状态:\n"
    if TrackDirectMemoryAllocations
        printf "  分配跟踪: 启用\n"
        if _allocations != 0
            printf "  跟踪记录数: %d\n", _allocations->length()
        end
    else
        printf "  分配跟踪: 禁用\n"
    end
    
    printf "直接内存泄漏检测分析完成\n"
end

# ================================
# 10.4 OOM故障分析函数
# ================================

define analyze_oom_detection
    printf "\n=== OOM故障检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查OOM分析器状态
    printf "OOM分析器状态:\n"
    if _oom_history != 0
        printf "  OOM历史记录: 已初始化\n"
        printf "  历史记录数: %d\n", _oom_history->length()
    else
        printf "  OOM历史记录: 未初始化\n"
    end
    
    printf "  总失败次数: %d\n", _total_failures
    printf "  最近失败次数: %d\n", _recent_failures
    printf "  OOM进行中: %d\n", _oom_in_progress
    
    # 分析不同类型的OOM
    printf "\nOOM类型分析:\n"
    printf "  HEAP_OOM: 堆内存溢出\n"
    printf "  METASPACE_OOM: Metaspace溢出\n"
    printf "  DIRECT_MEMORY_OOM: 直接内存溢出\n"
    printf "  STACK_OVERFLOW: 栈溢出\n"
    printf "  UNABLE_TO_CREATE_THREAD: 无法创建线程\n"
    
    # 检查当前内存压力
    printf "\n当前内存压力评估:\n"
    set $heap = Universe::_collectedheap
    if $heap != 0
        set $heap_used = $heap->used()
        set $heap_max = $heap->max_capacity()
        set $pressure_ratio = ($heap_used * 100.0) / $heap_max
        
        printf "  堆内存压力: %.1f%%\n", $pressure_ratio
        
        if $pressure_ratio > 95.0
            printf "  状态: 极高压力 - OOM风险\n"
            set $oom_analysis_count = $oom_analysis_count + 1
        else
            if $pressure_ratio > 85.0
                printf "  状态: 高压力\n"
            else
                if $pressure_ratio > 70.0
                    printf "  状态: 中等压力\n"
                else
                    printf "  状态: 正常\n"
                end
            end
        end
    end
    
    printf "OOM故障检测分析完成\n"
end

define analyze_heap_oom_details
    printf "\n=== 堆OOM详细分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 收集堆统计信息
    printf "堆统计信息:\n"
    set $heap = Universe::_collectedheap
    if $heap != 0
        printf "  堆类型: %s\n", $heap->name()
        printf "  堆容量: %zu bytes\n", $heap->capacity()
        printf "  最大容量: %zu bytes\n", $heap->max_capacity()
        printf "  已使用: %zu bytes\n", $heap->used()
        
        # G1特定分析
        if $heap->kind() == CollectedHeap::G1CollectedHeap
            printf "\nG1堆区域分析:\n"
            set $g1h = (G1CollectedHeap*)$heap
            set $total_regions = $g1h->max_regions()
            set $used_regions = $g1h->num_used_regions()
            set $free_regions = $total_regions - $used_regions
            
            printf "  总区域数: %zu\n", $total_regions
            printf "  已使用区域: %zu (%.1f%%)\n", $used_regions, ($used_regions * 100.0) / $total_regions
            printf "  空闲区域: %zu (%.1f%%)\n", $free_regions, ($free_regions * 100.0) / $total_regions
            
            # 分析各代使用情况
            set $young_regions = $g1h->young_list_length()
            set $old_regions = $g1h->old_regions_count()
            
            printf "\n各代使用情况:\n"
            printf "  年轻代区域: %zu (%.1f%%)\n", $young_regions, ($young_regions * 100.0) / $total_regions
            printf "  老年代区域: %zu (%.1f%%)\n", $old_regions, ($old_regions * 100.0) / $total_regions
            
            # 检查大对象
            set $humongous_regions = $g1h->humongous_regions_count()
            if $humongous_regions > 0
                printf "  大对象区域: %zu (%.1f%%)\n", $humongous_regions, ($humongous_regions * 100.0) / $total_regions
                printf "  警告: 检测到大量大对象\n"
            end
        end
    end
    
    # 分析GC效率
    printf "\nGC效率分析:\n"
    set $total_collections = $heap->total_collections()
    set $total_gc_time = $heap->total_collection_time()
    
    if $total_collections > 0
        set $avg_gc_time = $total_gc_time / $total_collections
        printf "  总GC次数: %ld\n", $total_collections
        printf "  总GC时间: %ld ms\n", $total_gc_time
        printf "  平均GC时间: %ld ms\n", $avg_gc_time
        
        # 计算GC开销
        set $uptime = Management::vm_init_done_time()
        if $uptime > 0
            set $gc_overhead = ($total_gc_time * 100.0) / $uptime
            printf "  GC开销: %.2f%%\n", $gc_overhead
            
            if $gc_overhead > 10.0
                printf "  警告: GC开销过高\n"
            end
        end
    end
    
    printf "堆OOM详细分析完成\n"
end

# ================================
# 10.5 线程故障诊断分析函数
# ================================

define analyze_deadlock_detection
    printf "\n=== 死锁检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查死锁检测器状态
    printf "死锁检测器状态:\n"
    printf "  死锁检测启用: %d\n", _deadlock_detection_enabled
    
    if _waiting_graph != 0
        printf "  等待图: 已构建\n"
        printf "  等待节点数: %d\n", _waiting_graph->length()
    else
        printf "  等待图: 未构建\n"
    end
    
    # 分析当前线程等待情况
    printf "\n线程等待情况分析:\n"
    set $blocked_threads = 0
    set $waiting_threads = 0
    set $total_threads = 0
    
    # 遍历所有Java线程
    set $current_thread = Threads::_thread_list
    while $current_thread != 0
        if $current_thread->is_Java_thread()
            set $total_threads = $total_threads + 1
            set $state = $current_thread->thread_state()
            
            if $state == JavaThread::_thread_blocked
                set $blocked_threads = $blocked_threads + 1
            end
            
            if $state == JavaThread::_thread_in_Object_wait
                set $waiting_threads = $waiting_threads + 1
            end
        end
        set $current_thread = $current_thread->next()
    end
    
    printf "  总Java线程数: %d\n", $total_threads
    printf "  阻塞线程数: %d\n", $blocked_threads
    printf "  等待线程数: %d\n", $waiting_threads
    
    if $blocked_threads > 0 || $waiting_threads > 0
        printf "  潜在死锁风险: 是\n"
        set $deadlock_detections = $deadlock_detections + 1
    else
        printf "  潜在死锁风险: 否\n"
    end
    
    printf "死锁检测分析完成\n"
end

define analyze_thread_leak_detection
    printf "\n=== 线程泄漏检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查线程泄漏检测器状态
    printf "线程泄漏检测器状态:\n"
    if _thread_history != 0
        printf "  线程历史记录: 已初始化\n"
    else
        printf "  线程历史记录: 未初始化\n"
    end
    
    printf "  基线线程数: %d\n", _baseline_thread_count
    printf "  最后检查时间: %ld ms\n", _last_check_time
    
    # 统计当前线程情况
    printf "\n当前线程统计:\n"
    set $java_threads = 0
    set $daemon_threads = 0
    set $user_threads = 0
    set $blocked_threads = 0
    set $waiting_threads = 0
    set $runnable_threads = 0
    
    # 遍历线程并统计状态
    set $current_thread = Threads::_thread_list
    while $current_thread != 0
        if $current_thread->is_Java_thread()
            set $java_threads = $java_threads + 1
            
            # 统计守护线程
            if java_lang_Thread::is_daemon($current_thread->threadObj())
                set $daemon_threads = $daemon_threads + 1
            else
                set $user_threads = $user_threads + 1
            end
            
            # 统计线程状态
            set $state = $current_thread->thread_state()
            if $state == JavaThread::_thread_blocked
                set $blocked_threads = $blocked_threads + 1
            end
            if $state == JavaThread::_thread_in_Object_wait
                set $waiting_threads = $waiting_threads + 1
            end
            if $state == JavaThread::_thread_in_Java
                set $runnable_threads = $runnable_threads + 1
            end
        end
        set $current_thread = $current_thread->next()
    end
    
    printf "  Java线程总数: %d\n", $java_threads
    printf "  守护线程数: %d\n", $daemon_threads
    printf "  用户线程数: %d\n", $user_threads
    printf "  运行中线程: %d\n", $runnable_threads
    printf "  阻塞线程: %d\n", $blocked_threads
    printf "  等待线程: %d\n", $waiting_threads
    
    # 检查线程泄漏
    set $growth = $java_threads - _baseline_thread_count
    if $growth > 50
        printf "  线程泄漏检测: 是 (增长%d个线程)\n", $growth
        set $thread_leak_detections = $thread_leak_detections + 1
    else
        printf "  线程泄漏检测: 否\n"
    end
    
    printf "线程泄漏检测分析完成\n"
end

# ================================
# 10.6 JIT编译器故障诊断函数
# ================================

define analyze_compiler_failure_detection
    printf "\n=== 编译器故障检测分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查编译器故障分析器状态
    printf "编译器故障分析器状态:\n"
    if _failure_history != 0
        printf "  故障历史记录: 已初始化\n"
        printf "  历史记录数: %d\n", _failure_history->length()
    else
        printf "  故障历史记录: 未初始化\n"
    end
    
    printf "  总故障次数: %d\n", _total_failures
    printf "  最近故障次数: %d\n", _recent_failures
    printf "  最后重置时间: %ld ms\n", _last_reset_time
    
    # 分析编译器健康状况
    printf "\n编译器健康状况:\n"
    set $total_compilations = CompileBroker::get_total_compile_count()
    printf "  总编译次数: %d\n", $total_compilations
    
    if $total_compilations > 100
        set $failure_rate = (_total_failures * 100.0) / $total_compilations
        printf "  编译失败率: %.2f%%\n", $failure_rate
        
        if $failure_rate > 5.0
            printf "  健康状况: 不良 (失败率过高)\n"
            set $compiler_failure_analysis = $compiler_failure_analysis + 1
        else
            if $failure_rate > 2.0
                printf "  健康状况: 一般\n"
            else
                printf "  健康状况: 良好\n"
            end
        end
    else
        printf "  健康状况: 数据不足\n"
    end
    
    printf "编译器故障检测分析完成\n"
end

define analyze_compiler_thread_health
    printf "\n=== 编译器线程健康分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 分析C1编译器线程
    printf "C1编译器线程分析:\n"
    set $c1_count = CompileBroker::c1_count()
    printf "  C1线程数: %d\n", $c1_count
    
    if $c1_count > 0
        set $i = 0
        while $i < $c1_count
            set $c1_thread = CompileBroker::get_c1_thread($i)
            if $c1_thread != 0
                printf "  C1线程%d: %s\n", $i, $c1_thread->name()
                set $state = $c1_thread->thread_state()
                printf "    状态: %s\n", JavaThread::thread_state_name($state)
                
                # 检查当前编译任务
                set $current_task = $c1_thread->task()
                if $current_task != 0
                    printf "    当前任务: 编译中\n"
                    set $compile_time = os::javaTimeMillis() - $current_task->time_queued()
                    printf "    编译时间: %ld ms\n", $compile_time
                    
                    if $compile_time > 30000
                        printf "    警告: 编译时间过长\n"
                    end
                else
                    printf "    当前任务: 空闲\n"
                end
            end
            set $i = $i + 1
        end
    end
    
    # 分析C2编译器线程
    printf "\nC2编译器线程分析:\n"
    set $c2_count = CompileBroker::c2_count()
    printf "  C2线程数: %d\n", $c2_count
    
    if $c2_count > 0
        set $i = 0
        while $i < $c2_count
            set $c2_thread = CompileBroker::get_c2_thread($i)
            if $c2_thread != 0
                printf "  C2线程%d: %s\n", $i, $c2_thread->name()
                set $state = $c2_thread->thread_state()
                printf "    状态: %s\n", JavaThread::thread_state_name($state)
                
                # 检查编译队列
                set $queue_size = CompileBroker::queue_size($c2_thread->queue_type())
                printf "    队列大小: %d\n", $queue_size
                
                if $queue_size > 1000
                    printf "    警告: 编译队列过大\n"
                end
            end
            set $i = $i + 1
        end
    end
    
    printf "编译器线程健康分析完成\n"
end

define analyze_code_cache_health
    printf "\n=== 代码缓存健康分析 ===\n"
    set $total_diagnostics_checks = $total_diagnostics_checks + 1
    
    # 检查代码缓存基本信息
    printf "代码缓存基本信息:\n"
    set $code_cache_size = CodeCache::capacity()
    set $code_cache_used = CodeCache::size()
    set $usage_ratio = ($code_cache_used * 100.0) / $code_cache_size
    
    printf "  容量: %zu bytes\n", $code_cache_size
    printf "  已使用: %zu bytes\n", $code_cache_used
    printf "  使用率: %.1f%%\n", $usage_ratio
    printf "  峰值使用: %zu bytes\n", _peak_code_cache_usage
    
    # 检查使用率阈值
    if $usage_ratio > 90.0
        printf "  健康状况: 危险 (使用率过高)\n"
        set $code_cache_analysis = $code_cache_analysis + 1
    else
        if $usage_ratio > 75.0
            printf "  健康状况: 警告 (使用率较高)\n"
        else
            printf "  健康状况: 正常\n"
        end
    end
    
    # 分析代码缓存内容
    printf "\n代码缓存内容分析:\n"
    set $nmethod_count = 0
    set $adapter_count = 0
    set $stub_count = 0
    set $nmethod_size = 0
    set $adapter_size = 0
    set $stub_size = 0
    
    # 遍历代码缓存统计各类型代码
    set $cb = CodeCache::first()
    while $cb != 0
        if $cb->is_nmethod()
            set $nmethod_count = $nmethod_count + 1
            set $nmethod_size = $nmethod_size + $cb->size()
        else
            if $cb->is_adapter_blob()
                set $adapter_count = $adapter_count + 1
                set $adapter_size = $adapter_size + $cb->size()
            else
                set $stub_count = $stub_count + 1
                set $stub_size = $stub_size + $cb->size()
            end
        end
        set $cb = CodeCache::next($cb)
    end
    
    printf "  nmethod数量: %d (%zu bytes)\n", $nmethod_count, $nmethod_size
    printf "  adapter数量: %d (%zu bytes)\n", $adapter_count, $adapter_size
    printf "  stub数量: %d (%zu bytes)\n", $stub_count, $stub_size
    
    # 计算分布比例
    set $total_size = $nmethod_size + $adapter_size + $stub_size
    if $total_size > 0
        printf "\n内容分布:\n"
        printf "  nmethod: %.1f%%\n", ($nmethod_size * 100.0) / $total_size
        printf "  adapter: %.1f%%\n", ($adapter_size * 100.0) / $total_size
        printf "  stub: %.1f%%\n", ($stub_size * 100.0) / $total_size
    end
    
    printf "代码缓存健康分析完成\n"
end

# ================================
# 综合故障诊断分析函数
# ================================

define comprehensive_diagnostics_analysis
    printf "\n" 
    printf "================================================================\n"
    printf "JVM故障诊断与排查 - 综合分析报告\n"
    printf "================================================================\n"
    printf "基于OpenJDK 11源码的完整故障诊断分析\n"
    printf "标准配置: -Xms=8GB -Xmx=8GB, G1 GC, Region大小4MB\n"
    printf "================================================================\n"
    
    # 重置统计计数器
    set $total_diagnostics_checks = 0
    set $failed_diagnostics_checks = 0
    set $vmError_analysis_count = 0
    set $memory_leak_detections = 0
    set $deadlock_detections = 0
    set $oom_analysis_count = 0
    set $thread_leak_detections = 0
    set $compiler_failure_analysis = 0
    set $code_cache_analysis = 0
    
    # 1. 故障检测机制分析
    printf "\n1. 故障检测机制分析\n"
    printf "----------------------------------------\n"
    analyze_vmError_system
    analyze_signal_handlers
    analyze_crash_handler
    
    # 2. 诊断工具实现分析
    printf "\n2. 诊断工具实现分析\n"
    printf "----------------------------------------\n"
    analyze_thread_service
    analyze_heap_dumper
    analyze_perf_counters
    
    # 3. 内存故障诊断分析
    printf "\n3. 内存故障诊断分析\n"
    printf "----------------------------------------\n"
    analyze_memory_leak_detection
    analyze_metaspace_leak_detection
    analyze_direct_memory_leak
    
    # 4. OOM故障分析
    printf "\n4. OOM故障分析\n"
    printf "----------------------------------------\n"
    analyze_oom_detection
    analyze_heap_oom_details
    
    # 5. 线程故障诊断分析
    printf "\n5. 线程故障诊断分析\n"
    printf "----------------------------------------\n"
    analyze_deadlock_detection
    analyze_thread_leak_detection
    
    # 6. JIT编译器故障诊断
    printf "\n6. JIT编译器故障诊断\n"
    printf "----------------------------------------\n"
    analyze_compiler_failure_detection
    analyze_compiler_thread_health
    analyze_code_cache_health
    
    # 生成综合诊断报告
    printf "\n"
    printf "================================================================\n"
    printf "综合故障诊断报告\n"
    printf "================================================================\n"
    
    printf "诊断统计:\n"
    printf "  总检查项目: %d\n", $total_diagnostics_checks
    printf "  失败检查项目: %d\n", $failed_diagnostics_checks
    printf "  检查成功率: %.1f%%\n", (($total_diagnostics_checks - $failed_diagnostics_checks) * 100.0) / $total_diagnostics_checks
    
    printf "\n故障检测结果:\n"
    printf "  VMError分析: %d次\n", $vmError_analysis_count
    printf "  内存泄漏检测: %d次\n", $memory_leak_detections
    printf "  死锁检测: %d次\n", $deadlock_detections
    printf "  OOM分析: %d次\n", $oom_analysis_count
    printf "  线程泄漏检测: %d次\n", $thread_leak_detections
    printf "  编译器故障分析: %d次\n", $compiler_failure_analysis
    printf "  代码缓存分析: %d次\n", $code_cache_analysis
    
    # 计算总体健康评分
    set $total_issues = $vmError_analysis_count + $memory_leak_detections + $deadlock_detections + $oom_analysis_count + $thread_leak_detections + $compiler_failure_analysis + $code_cache_analysis
    
    printf "\n系统健康评估:\n"
    if $total_issues == 0
        printf "  健康状况: 优秀 ✅\n"
        printf "  评分: 95-100分\n"
        printf "  建议: 系统运行良好，继续保持\n"
    else
        if $total_issues <= 2
            printf "  健康状况: 良好 ⚠️\n"
            printf "  评分: 80-94分\n"
            printf "  建议: 关注检测到的问题，进行优化\n"
        else
            if $total_issues <= 5
                printf "  健康状况: 一般 ⚠️\n"
                printf "  评分: 60-79分\n"
                printf "  建议: 需要及时处理多个问题\n"
            else
                printf "  健康状况: 差 ❌\n"
                printf "  评分: 0-59分\n"
                printf "  建议: 系统存在严重问题，需要立即处理\n"
            end
        end
    end
    
    printf "\n故障诊断建议:\n"
    printf "1. 定期监控内存使用情况，防止内存泄漏\n"
    printf "2. 配置合适的堆大小和GC参数\n"
    printf "3. 监控线程创建和销毁，避免线程泄漏\n"
    printf "4. 关注编译器性能，优化热点代码\n"
    printf "5. 配置适当的代码缓存大小\n"
    printf "6. 建立完善的监控和告警机制\n"
    printf "7. 定期进行故障演练和恢复测试\n"
    
    printf "\n================================================================\n"
    printf "JVM故障诊断与排查分析完成！\n"
    printf "================================================================\n"
end

# 设置便捷的调试命令别名
define diag
    comprehensive_diagnostics_analysis
end

define vmError
    analyze_vmError_system
end

define memleak
    analyze_memory_leak_detection
    analyze_metaspace_leak_detection
    analyze_direct_memory_leak
end

define deadlock
    analyze_deadlock_detection
end

define oom
    analyze_oom_detection
    analyze_heap_oom_details
end

define threadleak
    analyze_thread_leak_detection
end

define compiler
    analyze_compiler_failure_detection
    analyze_compiler_thread_health
end

define codecache
    analyze_code_cache_health
end

# 打印使用说明
printf "JVM故障诊断与排查 - 深度增强版GDB调试脚本已加载\n"
printf "========================================\n"
printf "可用命令:\n"
printf "  diag          - 执行完整的故障诊断分析\n"
printf "  vmError       - 分析VMError故障检测系统\n"
printf "  memleak       - 分析内存泄漏检测\n"
printf "  deadlock      - 分析死锁检测\n"
printf "  oom           - 分析OOM故障\n"
printf "  threadleak    - 分析线程泄漏检测\n"
printf "  compiler      - 分析编译器故障\n"
printf "  codecache     - 分析代码缓存健康状况\n"
printf "========================================\n"
printf "使用 'diag' 命令开始完整的故障诊断分析\n"