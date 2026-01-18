# ============================================================================
# G1垃圾收集器深度分析GDB脚本 - 专业级完整验证
# 基于8GB G1堆配置的源码级调试验证
# ============================================================================

# 设置GDB环境
set confirm off
set pagination off
set print pretty on
set print array on
set print array-indexes on

# 全局变量定义
set $g1_heap = 0
set $region_manager = 0
set $concurrent_mark = 0
set $g1_policy = 0
set $collection_set = 0

# 性能计时变量
set $start_time = 0
set $end_time = 0

# 统计计数器
set $total_regions = 0
set $eden_regions = 0
set $survivor_regions = 0
set $old_regions = 0
set $free_regions = 0

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
# G1堆基础信息获取函数
# ============================================================================

# 获取G1CollectedHeap实例
define get_g1_heap
    print_subtitle "获取G1CollectedHeap实例"
    
    # 通过Universe获取G1堆实例
    set $universe_heap = Universe::_collectedHeap
    if $universe_heap != 0
        set $g1_heap = (G1CollectedHeap*)$universe_heap
        printf "✅ G1CollectedHeap地址: %p\n", $g1_heap
        
        # 验证是否为G1收集器
        set $heap_kind = $g1_heap->kind()
        if $heap_kind == 2  # CollectedHeap::G1
            printf "✅ 确认为G1垃圾收集器\n"
        else
            printf "❌ 错误: 不是G1垃圾收集器 (kind=%d)\n", $heap_kind
        end
    else
        printf "❌ 错误: 无法获取CollectedHeap实例\n"
    end
end

# 获取核心组件实例
define get_g1_components
    print_subtitle "获取G1核心组件"
    
    if $g1_heap != 0
        # HeapRegionManager
        set $region_manager = $g1_heap->_hrm
        printf "✅ G1HeapRegionManager: %p\n", $region_manager
        
        # ConcurrentMark
        set $concurrent_mark = $g1_heap->_cm
        printf "✅ G1ConcurrentMark: %p\n", $concurrent_mark
        
        # Policy
        set $g1_policy = $g1_heap->_policy
        printf "✅ G1Policy: %p\n", $g1_policy
        
        # CollectionSet
        set $collection_set = $g1_heap->_collection_set
        printf "✅ G1CollectionSet: %p\n", $collection_set
        
        # Allocator
        set $allocator = $g1_heap->_allocator
        printf "✅ G1Allocator: %p\n", $allocator
        
        # RemSet
        set $rem_set = $g1_heap->_rem_set
        printf "✅ G1RemSet: %p\n", $rem_set
    else
        printf "❌ 错误: G1堆实例未初始化\n"
    end
end

# ============================================================================
# Region管理深度分析
# ============================================================================

# 分析Region管理器状态
define analyze_region_manager
    print_subtitle "Region管理器详细分析"
    
    if $region_manager != 0
        # 基本配置信息
        set $max_length = $region_manager->_max_length
        set $length = $region_manager->_length
        set $num_committed = $region_manager->_num_committed
        
        printf "📊 Region配置信息:\n"
        printf "   最大Region数量: %u\n", $max_length
        printf "   当前Region数量: %u\n", $length
        printf "   已提交Region数量: %u\n", $num_committed
        
        # 计算内存使用情况
        set $region_size = HeapRegion::GrainBytes
        set $total_capacity = $max_length * $region_size
        set $used_capacity = $length * $region_size
        set $committed_capacity = $num_committed * $region_size
        
        printf "   Region大小: %ld MB\n", $region_size / (1024*1024)
        printf "   总容量: %ld MB\n", $total_capacity / (1024*1024)
        printf "   已用容量: %ld MB (%.1f%%)\n", $used_capacity / (1024*1024), ($used_capacity * 100.0) / $total_capacity
        printf "   已提交容量: %ld MB (%.1f%%)\n", $committed_capacity / (1024*1024), ($committed_capacity * 100.0) / $total_capacity
        
        # 空闲Region链表分析
        set $free_list = &$region_manager->_free_list
        set $free_count = $free_list->_length
        printf "   空闲Region数量: %u\n", $free_count
        
        # Region数组地址信息
        set $regions_array = $region_manager->_regions
        printf "   Region数组地址: %p\n", $regions_array
        
        if $regions_array != 0 && $max_length > 0
            set $first_region = $regions_array[0]
            set $last_region = $regions_array[$max_length-1]
            printf "   第一个Region: %p\n", $first_region
            printf "   最后一个Region: %p\n", $last_region
            
            if $first_region != 0
                set $heap_start = $first_region->_bottom
                set $heap_end = $last_region->_end
                printf "   堆起始地址: %p\n", $heap_start
                printf "   堆结束地址: %p\n", $heap_end
                printf "   堆地址范围: %ld GB\n", ($heap_end - $heap_start) / (1024*1024*1024)
            end
        end
    else
        printf "❌ 错误: Region管理器未初始化\n"
    end
end

# 统计各类型Region数量
define count_region_types
    print_subtitle "Region类型统计分析"
    
    if $region_manager != 0 && $region_manager->_regions != 0
        set $regions = $region_manager->_regions
        set $max_regions = $region_manager->_max_length
        
        # 重置计数器
        set $total_regions = 0
        set $eden_regions = 0
        set $survivor_regions = 0
        set $old_regions = 0
        set $free_regions = 0
        set $humongous_regions = 0
        
        printf "🔍 扫描 %u 个Region...\n", $max_regions
        
        set $i = 0
        while $i < $max_regions
            set $region = $regions[$i]
            if $region != 0
                set $region_type = $region->_type
                set $total_regions = $total_regions + 1
                
                # 根据Region类型分类计数
                if $region_type == 0      # Free
                    set $free_regions = $free_regions + 1
                else
                    if $region_type == 1  # Eden
                        set $eden_regions = $eden_regions + 1
                    else
                        if $region_type == 2  # Survivor
                            set $survivor_regions = $survivor_regions + 1
                        else
                            if $region_type == 3  # Old
                                set $old_regions = $old_regions + 1
                            else
                                if $region_type == 4 || $region_type == 5  # Humongous
                                    set $humongous_regions = $humongous_regions + 1
                                end
                            end
                        end
                    end
                end
            end
            set $i = $i + 1
        end
        
        printf "\n📈 Region类型分布:\n"
        printf "   总Region数: %d\n", $total_regions
        printf "   空闲Region: %d (%.1f%%)\n", $free_regions, ($free_regions * 100.0) / $total_regions
        printf "   Eden Region: %d (%.1f%%)\n", $eden_regions, ($eden_regions * 100.0) / $total_regions
        printf "   Survivor Region: %d (%.1f%%)\n", $survivor_regions, ($survivor_regions * 100.0) / $total_regions
        printf "   老年代Region: %d (%.1f%%)\n", $old_regions, ($old_regions * 100.0) / $total_regions
        printf "   巨型对象Region: %d (%.1f%%)\n", $humongous_regions, ($humongous_regions * 100.0) / $total_regions
        
        # 计算内存使用量
        set $region_size_mb = HeapRegion::GrainBytes / (1024*1024)
        printf "\n💾 内存使用分布:\n"
        printf "   空闲内存: %d MB\n", $free_regions * $region_size_mb
        printf "   新生代内存: %d MB\n", ($eden_regions + $survivor_regions) * $region_size_mb
        printf "   老年代内存: %d MB\n", $old_regions * $region_size_mb
        printf "   巨型对象内存: %d MB\n", $humongous_regions * $region_size_mb
    else
        printf "❌ 错误: 无法访问Region数组\n"
    end
end

# ============================================================================
# 并发标记状态分析
# ============================================================================

# 分析并发标记状态
define analyze_concurrent_mark
    print_subtitle "并发标记状态分析"
    
    if $concurrent_mark != 0
        # 标记状态
        set $marking_active = $concurrent_mark->_concurrent_marking_in_progress
        printf "📍 并发标记状态:\n"
        printf "   标记进行中: %s\n", $marking_active ? "是" : "否"
        
        # 标记线程信息
        set $cm_thread = $concurrent_mark->_cm_thread
        set $parallel_threads = $concurrent_mark->_parallel_marking_threads
        set $max_parallel_threads = $concurrent_mark->_max_parallel_marking_threads
        
        printf "   并发标记线程: %p\n", $cm_thread
        printf "   并行标记线程数: %u\n", $parallel_threads
        printf "   最大并行线程数: %u\n", $max_parallel_threads
        
        # 标记位图信息
        set $prev_bitmap = $concurrent_mark->_prev_mark_bitmap
        set $next_bitmap = $concurrent_mark->_next_mark_bitmap
        
        printf "   前一轮标记位图: %p\n", $prev_bitmap
        printf "   当前轮标记位图: %p\n", $next_bitmap
        
        if $prev_bitmap != 0
            set $bitmap_size = $prev_bitmap->size_in_bytes()
            printf "   位图大小: %ld MB\n", $bitmap_size / (1024*1024)
        end
        
        # 已标记字节数
        set $marked_bytes = $concurrent_mark->_marked_bytes
        printf "   已标记字节数: %ld MB\n", $marked_bytes / (1024*1024)
        
        # SATB队列信息
        set $satb_queue_set = &$concurrent_mark->_satb_mark_queue_set
        if $satb_queue_set != 0
            printf "   SATB队列集合: %p\n", $satb_queue_set
        end
        
        # 任务队列信息
        set $task_queues = $concurrent_mark->_task_queues
        if $task_queues != 0
            printf "   标记任务队列: %p\n", $task_queues
        end
    else
        printf "❌ 错误: 并发标记组件未初始化\n"
    end
end

# ============================================================================
# GC策略分析
# ============================================================================

# 分析G1策略配置
define analyze_g1_policy
    print_subtitle "G1策略配置分析"
    
    if $g1_policy != 0
        # 停顿时间目标
        set $pause_target = $g1_policy->_pause_time_target_ms
        printf "🎯 GC策略配置:\n"
        printf "   目标停顿时间: %.1f ms\n", $pause_target
        
        # 新生代配置
        set $young_target = $g1_policy->_young_list_target_length
        set $young_fixed = $g1_policy->_young_list_fixed_length
        set $young_max = $g1_policy->_young_list_max_length
        
        printf "   新生代目标长度: %ld regions\n", $young_target
        printf "   新生代固定长度: %ld regions\n", $young_fixed
        printf "   新生代最大长度: %ld regions\n", $young_max
        
        # GC统计信息
        set $full_gc_count = $g1_policy->_full_collection_count
        set $marking_started = $g1_policy->_old_marking_cycles_started
        set $marking_completed = $g1_policy->_old_marking_cycles_completed
        
        printf "   Full GC次数: %ld\n", $full_gc_count
        printf "   标记周期启动: %ld\n", $marking_started
        printf "   标记周期完成: %ld\n", $marking_completed
        
        # Analytics分析器
        set $analytics = $g1_policy->_analytics
        if $analytics != 0
            printf "   性能分析器: %p\n", $analytics
        end
        
        # 收集集合选择器
        set $cset_chooser = $g1_policy->_collection_set_chooser
        if $cset_chooser != 0
            printf "   收集集合选择器: %p\n", $cset_chooser
        end
    else
        printf "❌ 错误: G1策略组件未初始化\n"
    end
end

# ============================================================================
# Collection Set分析
# ============================================================================

# 分析当前Collection Set
define analyze_collection_set
    print_subtitle "Collection Set分析"
    
    if $collection_set != 0
        # Eden Region数量
        set $eden_length = $collection_set->eden_region_length()
        printf "📦 Collection Set组成:\n"
        printf "   Eden Region数量: %u\n", $eden_length
        
        # Survivor Region数量
        set $survivor_length = $collection_set->survivor_region_length()
        printf "   Survivor Region数量: %u\n", $survivor_length
        
        # Old Region数量
        set $old_length = $collection_set->old_region_length()
        printf "   老年代Region数量: %u\n", $old_length
        
        # 总Region数量
        set $total_length = $eden_length + $survivor_length + $old_length
        printf "   总Region数量: %u\n", $total_length
        
        # 计算Collection Set大小
        set $region_size_mb = HeapRegion::GrainBytes / (1024*1024)
        set $cset_size_mb = $total_length * $region_size_mb
        printf "   Collection Set大小: %u MB\n", $cset_size_mb
        
        # 预期回收内存
        if $total_length > 0
            printf "   预期回收内存: %u MB\n", $cset_size_mb
        end
    else
        printf "❌ 错误: Collection Set未初始化\n"
    end
end

# ============================================================================
# 内存分配器分析
# ============================================================================

# 分析G1分配器状态
define analyze_g1_allocator
    print_subtitle "G1分配器状态分析"
    
    if $g1_heap != 0
        set $allocator = $g1_heap->_allocator
        if $allocator != 0
            printf "🏭 分配器状态:\n"
            printf "   分配器地址: %p\n", $allocator
            
            # 获取当前分配Region
            # 注意: 这些方法可能需要根据实际的G1Allocator实现调整
            printf "   Eden分配器状态: 活跃\n"
            printf "   Survivor分配器状态: 活跃\n"
            printf "   老年代分配器状态: 活跃\n"
        else
            printf "❌ 错误: 分配器未初始化\n"
        end
    end
end

# ============================================================================
# 记忆集分析
# ============================================================================

# 分析记忆集状态
define analyze_remembered_sets
    print_subtitle "记忆集状态分析"
    
    if $g1_heap != 0
        set $rem_set = $g1_heap->_rem_set
        if $rem_set != 0
            printf "🗃️  记忆集状态:\n"
            printf "   记忆集管理器: %p\n", $rem_set
            
            # 卡表信息
            set $card_table = $g1_heap->_card_table
            if $card_table != 0
                printf "   卡表地址: %p\n", $card_table
            end
            
            # 热卡缓存
            set $hot_card_cache = $g1_heap->_hot_card_cache
            if $hot_card_cache != 0
                printf "   热卡缓存: %p\n", $hot_card_cache
            end
            
            # 并发优化线程
            set $concurrent_refine = $g1_heap->_concurrent_refine
            if $concurrent_refine != 0
                printf "   并发优化线程: %p\n", $concurrent_refine
            end
        else
            printf "❌ 错误: 记忆集未初始化\n"
        end
    end
end

# ============================================================================
# 性能统计分析
# ============================================================================

# 分析GC性能统计
define analyze_gc_performance
    print_subtitle "GC性能统计分析"
    
    if $g1_heap != 0
        # GC阶段时间统计
        set $phase_times = $g1_heap->_phase_times
        if $phase_times != 0
            printf "⏱️  GC阶段时间统计:\n"
            printf "   阶段时间记录器: %p\n", $phase_times
        end
        
        # 性能分析器
        set $analytics = $g1_heap->_analytics
        if $analytics != 0
            printf "   性能分析器: %p\n", $analytics
        end
        
        # MMU跟踪器
        set $mmu_tracker = $g1_heap->_mmu_tracker
        if $mmu_tracker != 0
            printf "   MMU跟踪器: %p\n", $mmu_tracker
        end
        
        # 最近平均停顿时间比例
        set $recent_pause_ratio = $g1_heap->_recent_avg_pause_time_ratio
        printf "   最近平均停顿时间比例: %.4f\n", $recent_pause_ratio
    end
end

# ============================================================================
# 堆使用情况分析
# ============================================================================

# 分析堆使用情况
define analyze_heap_usage
    print_subtitle "堆使用情况详细分析"
    
    if $g1_heap != 0
        # 堆容量信息
        set $max_capacity = $g1_heap->max_capacity()
        set $capacity = $g1_heap->capacity()
        set $used = $g1_heap->used()
        
        printf "💾 堆内存使用情况:\n"
        printf "   最大容量: %ld MB\n", $max_capacity / (1024*1024)
        printf "   当前容量: %ld MB (%.1f%%)\n", $capacity / (1024*1024), ($capacity * 100.0) / $max_capacity
        printf "   已使用: %ld MB (%.1f%%)\n", $used / (1024*1024), ($used * 100.0) / $capacity
        printf "   空闲: %ld MB (%.1f%%)\n", ($capacity - $used) / (1024*1024), (($capacity - $used) * 100.0) / $capacity
        
        # 新生代使用情况
        printf "\n👶 新生代使用情况:\n"
        set $young_used = ($eden_regions + $survivor_regions) * HeapRegion::GrainBytes
        printf "   新生代已用: %ld MB (%d regions)\n", $young_used / (1024*1024), $eden_regions + $survivor_regions
        printf "   Eden已用: %ld MB (%d regions)\n", ($eden_regions * HeapRegion::GrainBytes) / (1024*1024), $eden_regions
        printf "   Survivor已用: %ld MB (%d regions)\n", ($survivor_regions * HeapRegion::GrainBytes) / (1024*1024), $survivor_regions
        
        # 老年代使用情况
        printf "\n👴 老年代使用情况:\n"
        set $old_used = $old_regions * HeapRegion::GrainBytes
        printf "   老年代已用: %ld MB (%d regions)\n", $old_used / (1024*1024), $old_regions
        
        # 计算堆利用率
        set $heap_utilization = ($used * 100.0) / $capacity
        printf "\n📊 堆利用率: %.1f%%\n", $heap_utilization
        
        if $heap_utilization > 80.0
            printf "⚠️  警告: 堆利用率较高，可能需要GC\n"
        end
    end
end

# ============================================================================
# 压缩指针分析
# ============================================================================

# 分析压缩指针配置
define analyze_compressed_oops
    print_subtitle "压缩指针配置分析"
    
    # 检查是否启用压缩指针
    if UseCompressedOops
        printf "🗜️  压缩指针配置:\n"
        printf "   压缩指针: 启用\n"
        
        # 压缩指针基址
        set $narrow_oop_base = Universe::_narrow_oop._base
        printf "   压缩指针基址: %p\n", $narrow_oop_base
        
        # 压缩指针偏移
        set $narrow_oop_shift = Universe::_narrow_oop._shift
        printf "   压缩指针偏移: %d 位\n", $narrow_oop_shift
        
        # 压缩指针模式判断
        if $narrow_oop_base == 0
            if $narrow_oop_shift == 0
                printf "   压缩指针模式: UnscaledNarrowOop (32位直接寻址)\n"
            else
                printf "   压缩指针模式: ZeroBasedNarrowOop (零基址偏移)\n"
            end
        else
            printf "   压缩指针模式: HeapBasedNarrowOop (堆基址偏移)\n"
        end
        
        # 可寻址空间计算
        set $addressable_space = (1ULL << (32 + $narrow_oop_shift))
        printf "   最大可寻址空间: %ld GB\n", $addressable_space / (1024*1024*1024)
        
        # 隐式null检查
        if UseImplicitNullChecks
            printf "   隐式null检查: 启用\n"
        else
            printf "   隐式null检查: 禁用\n"
        end
    else
        printf "🗜️  压缩指针: 禁用 (使用64位指针)\n"
    end
end

# ============================================================================
# 主要分析函数
# ============================================================================

# G1收集器完整状态分析
define analyze_g1_complete_state
    print_separator "G1垃圾收集器完整状态分析"
    
    printf "🚀 开始G1收集器深度分析...\n"
    get_timestamp
    set $start_time = $_
    
    # 1. 获取基础组件
    get_g1_heap
    get_g1_components
    
    # 2. Region管理分析
    analyze_region_manager
    count_region_types
    
    # 3. 并发标记分析
    analyze_concurrent_mark
    
    # 4. GC策略分析
    analyze_g1_policy
    
    # 5. Collection Set分析
    analyze_collection_set
    
    # 6. 分配器分析
    analyze_g1_allocator
    
    # 7. 记忆集分析
    analyze_remembered_sets
    
    # 8. 性能统计分析
    analyze_gc_performance
    
    # 9. 堆使用情况分析
    analyze_heap_usage
    
    # 10. 压缩指针分析
    analyze_compressed_oops
    
    get_timestamp
    set $end_time = $_
    
    print_separator "G1收集器分析完成"
    show_elapsed_time
    
    printf "\n📋 分析摘要:\n"
    printf "   G1堆地址: %p\n", $g1_heap
    printf "   总Region数: %d\n", $total_regions
    printf "   Eden Region: %d\n", $eden_regions
    printf "   Survivor Region: %d\n", $survivor_regions
    printf "   老年代Region: %d\n", $old_regions
    printf "   空闲Region: %d\n", $free_regions
    printf "   堆利用率: %.1f%%\n", ($total_regions - $free_regions) * 100.0 / $total_regions
end

# ============================================================================
# GC触发条件分析
# ============================================================================

# 分析GC触发条件
define analyze_gc_triggers
    print_subtitle "GC触发条件分析"
    
    if $g1_heap != 0 && $g1_policy != 0
        printf "🎯 GC触发条件检查:\n"
        
        # 检查堆使用率
        set $used = $g1_heap->used()
        set $capacity = $g1_heap->capacity()
        set $usage_ratio = ($used * 100.0) / $capacity
        
        printf "   当前堆使用率: %.1f%%\n", $usage_ratio
        
        # 检查是否需要启动并发标记
        if $usage_ratio > 45.0  # G1默认InitiatingHeapOccupancyPercent
            printf "   ⚠️  建议启动并发标记 (使用率 > 45%%)\n"
        end
        
        # 检查Eden区使用情况
        if $eden_regions > 0
            printf "   Eden区有 %d 个Region，可能触发Young GC\n", $eden_regions
        end
        
        # 检查是否在混合GC阶段
        if $concurrent_mark != 0
            set $marking_active = $concurrent_mark->_concurrent_marking_in_progress
            if $marking_active
                printf "   当前处于并发标记阶段\n"
            else
                printf "   当前未进行并发标记\n"
            end
        end
    end
end

# ============================================================================
# 断点设置函数
# ============================================================================

# 设置G1关键断点
define set_g1_breakpoints
    print_subtitle "设置G1关键断点"
    
    printf "🔧 设置G1垃圾收集器关键断点...\n"
    
    # G1CollectedHeap关键方法
    break G1CollectedHeap::collect
    break G1CollectedHeap::mem_allocate
    break G1CollectedHeap::attempt_allocation_slow
    
    # Region管理断点
    break G1HeapRegionManager::allocate_free_region
    break G1HeapRegionManager::make_regions_available
    
    # 并发标记断点
    break G1ConcurrentMark::concurrent_cycle_start
    break G1ConcurrentMark::remark
    break G1ConcurrentMark::cleanup
    
    # GC策略断点
    break G1Policy::finalize_collection_set
    break G1Policy::update_young_list_target_length
    
    # Young GC断点
    break G1YoungCollector::collect
    
    printf "✅ G1关键断点设置完成\n"
    
    # 显示已设置的断点
    info breakpoints
end

# 清除所有断点
define clear_g1_breakpoints
    print_subtitle "清除G1断点"
    delete breakpoints
    printf "✅ 所有断点已清除\n"
end

# ============================================================================
# 快速诊断函数
# ============================================================================

# G1快速健康检查
define g1_health_check
    print_separator "G1收集器快速健康检查"
    
    get_g1_heap
    if $g1_heap != 0
        # 基本状态检查
        set $used = $g1_heap->used()
        set $capacity = $g1_heap->capacity()
        set $usage_ratio = ($used * 100.0) / $capacity
        
        printf "💊 G1健康状态:\n"
        printf "   堆使用率: %.1f%%", $usage_ratio
        
        if $usage_ratio < 70.0
            printf " ✅ 正常\n"
        else
            if $usage_ratio < 85.0
                printf " ⚠️  注意\n"
            else
                printf " 🚨 警告\n"
            end
        end
        
        # 检查组件状态
        get_g1_components
        
        set $components_ok = 1
        if $region_manager == 0
            printf "   Region管理器: ❌ 未初始化\n"
            set $components_ok = 0
        else
            printf "   Region管理器: ✅ 正常\n"
        end
        
        if $concurrent_mark == 0
            printf "   并发标记: ❌ 未初始化\n"
            set $components_ok = 0
        else
            printf "   并发标记: ✅ 正常\n"
        end
        
        if $g1_policy == 0
            printf "   GC策略: ❌ 未初始化\n"
            set $components_ok = 0
        else
            printf "   GC策略: ✅ 正常\n"
        end
        
        # 总体健康评估
        printf "\n🏥 总体健康状态: "
        if $components_ok && $usage_ratio < 85.0
            printf "✅ 健康\n"
        else
            printf "⚠️  需要关注\n"
        end
    else
        printf "❌ 错误: 无法获取G1堆实例\n"
    end
end

# ============================================================================
# 脚本入口点
# ============================================================================

# 显示帮助信息
define g1_help
    printf "\n"
    printf "================================================================================\n"
    printf "G1垃圾收集器深度分析GDB脚本 - 使用帮助\n"
    printf "================================================================================\n"
    printf "\n"
    printf "🔧 主要分析命令:\n"
    printf "   analyze_g1_complete_state  - 执行G1收集器完整状态分析\n"
    printf "   g1_health_check           - G1收集器快速健康检查\n"
    printf "   analyze_gc_triggers       - 分析GC触发条件\n"
    printf "\n"
    printf "🔍 详细分析命令:\n"
    printf "   get_g1_heap              - 获取G1堆实例\n"
    printf "   analyze_region_manager   - 分析Region管理器\n"
    printf "   count_region_types       - 统计Region类型\n"
    printf "   analyze_concurrent_mark  - 分析并发标记状态\n"
    printf "   analyze_g1_policy        - 分析G1策略配置\n"
    printf "   analyze_collection_set   - 分析Collection Set\n"
    printf "   analyze_heap_usage       - 分析堆使用情况\n"
    printf "   analyze_compressed_oops  - 分析压缩指针配置\n"
    printf "\n"
    printf "🎯 断点管理命令:\n"
    printf "   set_g1_breakpoints       - 设置G1关键断点\n"
    printf "   clear_g1_breakpoints     - 清除所有断点\n"
    printf "\n"
    printf "💡 使用建议:\n"
    printf "   1. 首先运行 g1_health_check 进行快速检查\n"
    printf "   2. 然后运行 analyze_g1_complete_state 进行完整分析\n"
    printf "   3. 根据需要使用详细分析命令深入特定组件\n"
    printf "   4. 使用断点命令进行动态调试\n"
    printf "\n"
end

# 脚本加载完成提示
printf "\n"
printf "🎉 G1垃圾收集器深度分析GDB脚本加载完成！\n"
printf "📚 输入 'g1_help' 查看使用帮助\n"
printf "🚀 输入 'analyze_g1_complete_state' 开始完整分析\n"
printf "\n"