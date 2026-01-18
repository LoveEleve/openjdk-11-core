# 第09章：JVM性能调优与监控实战 - GDB调试脚本
# 基于OpenJDK 11源码的性能调优和监控机制验证
# 标准配置：-Xms8g -Xmx8g，G1GC，4MB Region

# 设置调试环境
set confirm off
set pagination off
set print pretty on
set print array on
set print array-indexes on

# 定义颜色输出宏
define print_header
    printf "\n\033[1;34m=== %s ===\033[0m\n", $arg0
end

define print_info
    printf "\033[1;32m[INFO]\033[0m %s\n", $arg0
end

define print_warning
    printf "\033[1;33m[WARN]\033[0m %s\n", $arg0
end

define print_error
    printf "\033[1;31m[ERROR]\033[0m %s\n", $arg0
end

# ==================== 性能基线建立 ====================

# 1. 验证JVM参数配置
define verify_jvm_parameters
    print_header "JVM参数配置验证"
    
    print_info "堆内存配置："
    printf "  初始堆大小: %zu MB\n", InitialHeapSize / 1024 / 1024
    printf "  最大堆大小: %zu MB\n", MaxHeapSize / 1024 / 1024
    printf "  新生代大小: %zu MB\n", NewSize / 1024 / 1024
    printf "  最大新生代: %zu MB\n", MaxNewSize / 1024 / 1024
    
    print_info "GC配置："
    printf "  使用G1GC: %s\n", UseG1GC ? "是" : "否"
    if UseG1GC
        printf "  G1 Region大小: %zu MB\n", G1HeapRegionSize / 1024 / 1024
        printf "  最大GC暂停时间: %d ms\n", MaxGCPauseMillis
        printf "  并发GC线程数: %d\n", ConcGCThreads
    end
    
    print_info "编译器配置："
    printf "  分层编译: %s\n", TieredCompilation ? "启用" : "禁用"
    printf "  C1编译阈值: %d\n", Tier1CompileThreshold
    printf "  C2编译阈值: %d\n", Tier4CompileThreshold
    printf "  编译线程数: %d\n", CICompilerCount
end

# 2. 分析内存布局
define analyze_memory_layout
    print_header "内存布局分析"
    
    # 获取堆信息
    set $heap = Universe::_collectedHeap
    if $heap != 0
        printf "堆类型: %s\n", $heap->name()
        printf "堆地址范围: %p - %p\n", $heap->reserved_region().start(), $heap->reserved_region().end()
        printf "堆大小: %zu MB\n", $heap->capacity() / 1024 / 1024
        printf "已使用: %zu MB\n", $heap->used() / 1024 / 1024
        
        # G1堆特定信息
        if UseG1GC
            set $g1_heap = (G1CollectedHeap*)$heap
            printf "G1 Region数量: %u\n", $g1_heap->num_regions()
            printf "G1 Region大小: %zu MB\n", $g1_heap->region_size() / 1024 / 1024
        end
    end
    
    # 分析Metaspace
    print_info "Metaspace信息："
    printf "  已使用: %zu KB\n", MetaspaceAux::used_bytes() / 1024
    printf "  已提交: %zu KB\n", MetaspaceAux::committed_bytes() / 1024
    printf "  保留空间: %zu KB\n", MetaspaceAux::reserved_bytes() / 1024
end

# 3. 监控GC性能
define monitor_gc_performance
    print_header "GC性能监控"
    
    # 设置GC相关断点
    break G1CollectedHeap::do_collection_pause_at_safepoint
    break G1ConcurrentMark::checkpointRootsInitial
    
    print_info "已设置GC性能监控断点"
    
    # 继续执行
    continue
    
    # 分析GC统计信息
    print_info "GC统计信息："
    if UseG1GC
        set $g1_policy = G1CollectorPolicy::_policy
        if $g1_policy != 0
            printf "  平均GC暂停时间: %.2f ms\n", $g1_policy->average_pause_time_ms()
            printf "  目标暂停时间: %d ms\n", $g1_policy->max_pause_time_ms()
        end
    end
end

# 4. 分析编译器性能
define analyze_compiler_performance
    print_header "编译器性能分析"
    
    # 设置编译相关断点
    break CompileBroker::compile_method
    break nmethod::make_not_entrant
    
    print_info "已设置编译器性能监控断点"
    
    # 分析编译统计
    print_info "编译器统计："
    printf "  C1编译数: %d\n", CompileBroker::_sum_nof_compiles[CompLevel_simple]
    printf "  C2编译数: %d\n", CompileBroker::_sum_nof_compiles[CompLevel_full_optimization]
    printf "  编译队列长度: %d\n", CompileBroker::queue_size(CompLevel_full_optimization)
end

# ==================== 性能监控指标收集 ====================

# 5. 收集内存使用指标
define collect_memory_metrics
    print_header "内存使用指标收集"
    
    set $heap = Universe::_collectedHeap
    if $heap != 0
        set $heap_used = $heap->used()
        set $heap_capacity = $heap->capacity()
        set $heap_usage_ratio = ($heap_used * 100.0) / $heap_capacity
        
        printf "堆使用率: %.2f%% (%zu MB / %zu MB)\n", 
               $heap_usage_ratio, 
               $heap_used / 1024 / 1024,
               $heap_capacity / 1024 / 1024
        
        # G1特定指标
        if UseG1GC
            set $g1_heap = (G1CollectedHeap*)$heap
            set $young_list = $g1_heap->young_list()
            
            printf "年轻代Region数: %u\n", $young_list->length()
            printf "Eden Region数: %u\n", $young_list->eden_length()
            printf "Survivor Region数: %u\n", $young_list->survivor_length()
        end
    end
    
    # Metaspace指标
    set $metaspace_used = MetaspaceAux::used_bytes()
    set $metaspace_capacity = MetaspaceAux::capacity_bytes()
    if $metaspace_capacity > 0
        set $metaspace_usage = ($metaspace_used * 100.0) / $metaspace_capacity
        printf "Metaspace使用率: %.2f%%\n", $metaspace_usage
    end
end

# 6. 收集GC性能指标
define collect_gc_metrics
    print_header "GC性能指标收集"
    
    if UseG1GC
        # G1 GC指标
        set $g1_heap = (G1CollectedHeap*)Universe::_collectedHeap
        set $g1_policy = $g1_heap->g1_policy()
        
        printf "G1 GC指标:\n"
        printf "  混合GC阈值: %u%%\n", G1HeapWastePercent
        printf "  并发周期阈值: %u%%\n", G1OldCSetRegionThreshold
        
        # 收集集合信息
        set $collection_set = $g1_heap->collection_set()
        if $collection_set != 0
            printf "  收集集合Region数: 待实现\n"
        end
    end
    
    # 通用GC指标
    printf "GC通用指标:\n"
    printf "  安全点暂停: %s\n", SafepointSynchronize::is_at_safepoint() ? "是" : "否"
end

# 7. 收集线程性能指标
define collect_thread_metrics
    print_header "线程性能指标收集"
    
    # 统计线程数量
    set $thread_count = 0
    set $daemon_count = 0
    set $java_thread_count = 0
    
    set $thread = Threads::_thread_list
    while $thread != 0
        set $thread_count = $thread_count + 1
        
        if $thread->is_Java_thread()
            set $java_thread_count = $java_thread_count + 1
            set $java_thread = (JavaThread*)$thread
            
            if $java_thread->is_daemon()
                set $daemon_count = $daemon_count + 1
            end
        end
        
        set $thread = $thread->next()
    end
    
    printf "线程统计:\n"
    printf "  总线程数: %d\n", $thread_count
    printf "  Java线程数: %d\n", $java_thread_count
    printf "  守护线程数: %d\n", $daemon_count
    printf "  用户线程数: %d\n", $java_thread_count - $daemon_count
end

# 8. 收集编译器指标
define collect_compiler_metrics
    print_header "编译器性能指标收集"
    
    printf "编译器指标:\n"
    printf "  总编译时间: 待实现\n"
    printf "  编译方法数: 待实现\n"
    printf "  去优化次数: 待实现\n"
    
    # 检查编译队列
    printf "编译队列状态:\n"
    printf "  C1队列长度: 待实现\n"
    printf "  C2队列长度: 待实现\n"
end

# ==================== 性能问题诊断 ====================

# 9. 诊断内存问题
define diagnose_memory_issues
    print_header "内存问题诊断"
    
    set $heap = Universe::_collectedHeap
    if $heap != 0
        set $heap_used = $heap->used()
        set $heap_capacity = $heap->capacity()
        set $usage_ratio = ($heap_used * 100.0) / $heap_capacity
        
        if $usage_ratio > 90
            print_error "严重：堆内存使用率过高"
            printf "  当前使用率: %.2f%%\n", $usage_ratio
            printf "  建议：增加堆内存大小或检查内存泄漏\n"
        else
            if $usage_ratio > 80
                print_warning "警告：堆内存使用率较高"
                printf "  当前使用率: %.2f%%\n", $usage_ratio
            else
                print_info "堆内存使用正常"
            end
        end
        
        # 检查G1特定问题
        if UseG1GC
            diagnose_g1_issues
        end
    end
end

# 10. 诊断G1特定问题
define diagnose_g1_issues
    print_info "G1 GC问题诊断："
    
    set $g1_heap = (G1CollectedHeap*)Universe::_collectedHeap
    
    # 检查Region使用情况
    set $total_regions = $g1_heap->num_regions()
    set $used_regions = $g1_heap->num_used_regions()
    set $region_usage = ($used_regions * 100.0) / $total_regions
    
    printf "  Region使用率: %.2f%% (%u/%u)\n", $region_usage, $used_regions, $total_regions
    
    if $region_usage > 90
        print_warning "Region使用率过高，可能影响GC效率"
    end
    
    # 检查并发标记状态
    set $concurrent_mark = $g1_heap->concurrent_mark()
    if $concurrent_mark != 0
        printf "  并发标记状态: 待实现\n"
    end
end

# 11. 诊断CPU性能问题
define diagnose_cpu_issues
    print_header "CPU性能问题诊断"
    
    # 检查编译器活动
    printf "编译器活动检查:\n"
    
    # 检查是否有过多的编译活动
    set $compile_queue_size = CompileBroker::queue_size(CompLevel_full_optimization)
    if $compile_queue_size > 100
        print_warning "编译队列积压严重"
        printf "  C2编译队列长度: %d\n", $compile_queue_size
        printf "  建议：调整编译阈值或增加编译线程\n"
    end
    
    # 检查去优化情况
    printf "  去优化统计: 待实现\n"
end

# 12. 诊断线程问题
define diagnose_thread_issues
    print_header "线程问题诊断"
    
    # 统计线程状态
    set $runnable_count = 0
    set $blocked_count = 0
    set $waiting_count = 0
    
    set $thread = Threads::_thread_list
    while $thread != 0
        if $thread->is_Java_thread()
            set $java_thread = (JavaThread*)$thread
            set $state = $java_thread->thread_state()
            
            # 根据线程状态分类统计
            if $state == _thread_in_Java || $state == _thread_in_vm
                set $runnable_count = $runnable_count + 1
            end
            if $state == _thread_blocked
                set $blocked_count = $blocked_count + 1
            end
        end
        
        set $thread = $thread->next()
    end
    
    printf "线程状态分布:\n"
    printf "  可运行: %d\n", $runnable_count
    printf "  阻塞: %d\n", $blocked_count
    printf "  等待: %d\n", $waiting_count
    
    # 检查是否有异常情况
    if $blocked_count > $runnable_count
        print_warning "阻塞线程数量过多，可能存在锁竞争"
    end
end

# ==================== 性能优化建议 ====================

# 13. 生成内存优化建议
define suggest_memory_optimization
    print_header "内存优化建议"
    
    set $heap = Universe::_collectedHeap
    if $heap != 0
        set $heap_used = $heap->used()
        set $heap_capacity = $heap->capacity()
        set $usage_ratio = ($heap_used * 100.0) / $heap_capacity
        
        printf "基于当前内存使用情况的优化建议:\n"
        
        if $usage_ratio > 85
            printf "1. 增加堆内存大小:\n"
            printf "   -Xmx%zuG (当前: %zuG)\n", 
                   ($heap_capacity * 120 / 100) / 1024 / 1024 / 1024,
                   $heap_capacity / 1024 / 1024 / 1024
            
            printf "2. 检查内存泄漏:\n"
            printf "   使用 jmap -dump 生成堆转储进行分析\n"
        end
        
        if UseG1GC
            printf "3. G1 GC优化:\n"
            printf "   -XX:MaxGCPauseMillis=200 (调整暂停时间目标)\n"
            printf "   -XX:G1HeapRegionSize=16m (调整Region大小)\n"
        end
    end
end

# 14. 生成GC优化建议
define suggest_gc_optimization
    print_header "GC优化建议"
    
    printf "GC优化建议:\n"
    
    if UseG1GC
        printf "G1 GC参数优化:\n"
        printf "1. 暂停时间调优:\n"
        printf "   -XX:MaxGCPauseMillis=100  # 降低暂停时间目标\n"
        printf "   -XX:GCPauseIntervalMillis=200  # 设置暂停间隔\n"
        
        printf "2. 并发线程调优:\n"
        printf "   -XX:ConcGCThreads=%d  # 并发GC线程数\n", Runtime::getRuntime().availableProcessors() / 4
        printf "   -XX:ParallelGCThreads=%d  # 并行GC线程数\n", Runtime::getRuntime().availableProcessors()
        
        printf "3. 混合GC调优:\n"
        printf "   -XX:G1MixedGCCountTarget=8  # 混合GC目标次数\n"
        printf "   -XX:G1OldCSetRegionThreshold=10  # 老年代收集阈值\n"
    else
        printf "建议使用G1 GC以获得更好的延迟特性:\n"
        printf "   -XX:+UseG1GC\n"
        printf "   -XX:MaxGCPauseMillis=200\n"
    end
end

# 15. 生成编译器优化建议
define suggest_compiler_optimization
    print_header "编译器优化建议"
    
    printf "JIT编译器优化建议:\n"
    
    printf "1. 编译阈值调优:\n"
    printf "   -XX:CompileThreshold=1500  # C2编译阈值\n"
    printf "   -XX:Tier4CompileThreshold=15000  # 分层编译阈值\n"
    
    printf "2. 内联优化:\n"
    printf "   -XX:MaxInlineSize=70  # 增加内联大小限制\n"
    printf "   -XX:FreqInlineSize=500  # 频繁调用方法内联大小\n"
    
    printf "3. 编译线程数调优:\n"
    set $cpu_count = Runtime::getRuntime().availableProcessors()
    printf "   -XX:CICompilerCount=%d  # 编译线程数\n", $cpu_count / 2
end

# ==================== 综合性能分析 ====================

# 16. 执行完整性能分析
define run_performance_analysis
    print_header "执行完整性能分析"
    
    print_info "开始性能分析..."
    
    # 1. 验证JVM配置
    verify_jvm_parameters
    
    # 2. 分析内存布局
    analyze_memory_layout
    
    # 3. 收集性能指标
    collect_memory_metrics
    collect_gc_metrics
    collect_thread_metrics
    collect_compiler_metrics
    
    # 4. 诊断性能问题
    diagnose_memory_issues
    diagnose_cpu_issues
    diagnose_thread_issues
    
    # 5. 生成优化建议
    suggest_memory_optimization
    suggest_gc_optimization
    suggest_compiler_optimization
    
    print_info "性能分析完成"
end

# 17. 监控性能趋势
define monitor_performance_trends
    print_header "性能趋势监控"
    
    print_info "启动性能趋势监控..."
    
    # 设置监控断点
    break SafepointSynchronize::begin
    break G1CollectedHeap::do_collection_pause_at_safepoint
    
    # 定期收集指标
    set $monitor_count = 0
    while $monitor_count < 10
        printf "\n--- 监控周期 %d ---\n", $monitor_count
        
        collect_memory_metrics
        collect_gc_metrics
        
        # 等待一段时间（在实际使用中通过continue实现）
        set $monitor_count = $monitor_count + 1
    end
    
    print_info "性能趋势监控完成"
end

# 18. 性能基准测试
define run_performance_benchmark
    print_header "性能基准测试"
    
    print_info "执行性能基准测试..."
    
    # 记录基准开始状态
    set $start_heap_used = Universe::_collectedHeap->used()
    set $start_time = os::javaTimeMillis()
    
    print_info "基准测试开始状态已记录"
    printf "  起始堆使用: %zu MB\n", $start_heap_used / 1024 / 1024
    
    # 这里需要应用程序执行一些操作
    print_info "请运行应用程序进行基准测试..."
    
    # 继续执行一段时间
    continue
end

# ==================== 辅助调试命令 ====================

# 19. 快速性能检查
define quick_performance_check
    print_header "快速性能检查"
    
    # 内存快速检查
    set $heap = Universe::_collectedHeap
    if $heap != 0
        set $usage_ratio = ($heap->used() * 100.0) / $heap->capacity()
        printf "堆使用率: %.2f%%\n", $usage_ratio
        
        if $usage_ratio > 90
            print_error "内存使用率过高！"
        else
            if $usage_ratio > 80
                print_warning "内存使用率较高"
            else
                print_info "内存使用正常"
            end
        end
    end
    
    # GC快速检查
    printf "GC状态: %s\n", SafepointSynchronize::is_at_safepoint() ? "GC中" : "正常"
    
    # 线程快速检查
    printf "Java线程数: %d\n", Threads::number_of_threads()
end

# 20. 保存性能报告
define save_performance_report
    print_header "保存性能报告"
    
    if $argc == 1
        set logging file $arg0
        set logging on
        
        print_info "开始生成性能报告..."
        run_performance_analysis
        
        set logging off
        printf "性能报告已保存到: %s\n", $arg0
    else
        print_error "用法: save_performance_report <filename>"
    end
end

# ==================== 初始化和帮助 ====================

# 显示帮助信息
define help_chapter09
    print_header "第09章调试脚本帮助"
    
    printf "性能基线建立:\n"
    printf "  verify_jvm_parameters              - 验证JVM参数配置\n"
    printf "  analyze_memory_layout              - 分析内存布局\n"
    printf "  monitor_gc_performance             - 监控GC性能\n"
    printf "  analyze_compiler_performance       - 分析编译器性能\n"
    printf "\n"
    
    printf "性能指标收集:\n"
    printf "  collect_memory_metrics             - 收集内存使用指标\n"
    printf "  collect_gc_metrics                 - 收集GC性能指标\n"
    printf "  collect_thread_metrics             - 收集线程性能指标\n"
    printf "  collect_compiler_metrics           - 收集编译器指标\n"
    printf "\n"
    
    printf "性能问题诊断:\n"
    printf "  diagnose_memory_issues             - 诊断内存问题\n"
    printf "  diagnose_g1_issues                 - 诊断G1特定问题\n"
    printf "  diagnose_cpu_issues                - 诊断CPU性能问题\n"
    printf "  diagnose_thread_issues             - 诊断线程问题\n"
    printf "\n"
    
    printf "性能优化建议:\n"
    printf "  suggest_memory_optimization        - 生成内存优化建议\n"
    printf "  suggest_gc_optimization            - 生成GC优化建议\n"
    printf "  suggest_compiler_optimization      - 生成编译器优化建议\n"
    printf "\n"
    
    printf "综合分析:\n"
    printf "  run_performance_analysis           - 执行完整性能分析\n"
    printf "  monitor_performance_trends         - 监控性能趋势\n"
    printf "  run_performance_benchmark          - 性能基准测试\n"
    printf "\n"
    
    printf "辅助命令:\n"
    printf "  quick_performance_check            - 快速性能检查\n"
    printf "  save_performance_report <file>     - 保存性能报告\n"
    printf "  help_chapter09                     - 显示此帮助\n"
end

# 脚本初始化
print_header "第09章：JVM性能调优与监控实战 - GDB调试脚本已加载"
print_info "输入 'help_chapter09' 查看可用命令"
print_info "建议先运行 'run_performance_analysis' 进行完整分析"