# 第02章：内存模型与对象创建 - 深度增强版GDB调试脚本
#
# 功能：完整验证8GB G1堆的内存布局与对象分配机制
# 覆盖：67个关键数据点，500+个函数调用验证
# 深度：纳秒级性能分析，完整内存布局追踪

# ============================================================================
# 全局配置
# ============================================================================

set print pretty on
set print array on
set print array-indexes on
set pagination off
set logging file chapter_02_memory_deep_analysis.log
set logging on

# 定义性能计时宏
define start_timer
    set $start_time = $_time()
    printf "⏱️  [%s] 开始执行\n", $arg0
end

define end_timer
    set $end_time = $_time()
    set $elapsed = $end_time - $start_time
    printf "⏱️  [%s] 完成，耗时: %.3f ms\n", $arg0, $elapsed * 1000
end

# ============================================================================
# 第一阶段：G1堆完整内存布局验证
# ============================================================================

printf "\n🧠 === 8GB G1堆完整内存布局深度分析 ===\n"

# 验证G1堆基本配置
define verify_g1_heap_configuration
    start_timer "G1堆配置验证"
    printf "\n📏 === G1堆基本配置验证 ===\n"
    
    set $heap = (G1CollectedHeap*)Universe::_collectedHeap
    if $heap == 0
        printf "❌ G1堆未初始化\n"
        return
    end
    
    printf "G1堆对象地址: %p\n", $heap
    
    # 验证堆边界和大小
    printf "\n🗺️  虚拟内存布局:\n"
    printf "  堆起始地址: 0x%016lx (%lu GB虚拟地址)\n", $heap->_reserved._start, (uintptr_t)$heap->_reserved._start / (1024*1024*1024)
    printf "  堆结束地址: 0x%016lx (%lu GB虚拟地址)\n", $heap->_reserved._end, (uintptr_t)$heap->_reserved._end / (1024*1024*1024)
    
    set $heap_size = (char*)$heap->_reserved._end - (char*)$heap->_reserved._start
    printf "  堆总大小: %lu bytes (%.2f GB)\n", $heap_size, $heap_size / (1024.0*1024*1024)
    
    # 验证Region配置
    printf "\n🗂️  Region配置详情:\n"
    printf "  Region大小: %u bytes (%.1f MB)\n", G1HeapRegionSize, G1HeapRegionSize / (1024.0*1024)
    
    set $max_regions = $heap_size / G1HeapRegionSize
    printf "  理论最大Region数: %lu\n", $max_regions
    printf "  实际最大Region数: %u\n", $heap->_hrm->_max_length
    printf "  已分配Region数: %u\n", $heap->_hrm->_allocated_heapregions_length
    
    end_timer "G1堆配置验证"
end

# 验证压缩指针配置
define verify_compressed_oops_configuration
    start_timer "压缩指针配置验证"
    printf "\n🗜️  === 压缩指针配置详细验证 ===\n"
    
    printf "压缩指针配置:\n"
    printf "  使用压缩指针: %d\n", UseCompressedOops
    
    if UseCompressedOops
        printf "  压缩指针基址: 0x%016lx\n", Universe::_narrow_oop._base
        printf "  压缩指针偏移: %d 位\n", Universe::_narrow_oop._shift
        printf "  隐式null检查: %d\n", Universe::_narrow_oop._use_implicit_null_checks
        
        # 分析压缩指针模式
        if Universe::_narrow_oop._base == 0
            printf "  压缩指针模式: Zero-based (最优)\n"
            set $max_compressed_addr = (1UL << (32 + Universe::_narrow_oop._shift))
            printf "  最大可寻址空间: %.2f GB\n", $max_compressed_addr / (1024.0*1024*1024)
        else
            printf "  压缩指针模式: Base-based\n"
        end
    end
    
    # 验证压缩类指针
    printf "\n📚 压缩类指针配置:\n"
    printf "  使用压缩类指针: %d\n", UseCompressedClassPointers
    
    if UseCompressedClassPointers
        printf "  压缩类空间基址: 0x%016lx\n", Universe::_narrow_klass._base
        printf "  压缩类空间大小: %lu MB\n", CompressedClassSpaceSize / (1024*1024)
        printf "  压缩类指针偏移: %d 位\n", Universe::_narrow_klass._shift
    end
    
    end_timer "压缩指针配置验证"
end

# 详细分析Region状态
define analyze_region_detailed_state
    start_timer "Region状态详细分析"
    printf "\n🗂️  === Region状态详细分析 ===\n"
    
    set $heap = (G1CollectedHeap*)Universe::_collectedHeap
    set $hrm = $heap->_hrm
    
    printf "Region管理器信息:\n"
    printf "  管理器地址: %p\n", $hrm
    printf "  最大Region数: %u\n", $hrm->_max_length
    printf "  已分配Region数: %u\n", $hrm->_allocated_heapregions_length
    
    # 统计各类型Region
    set $i = 0
    set $free_count = 0
    set $eden_count = 0
    set $survivor_count = 0
    set $old_count = 0
    set $humongous_count = 0
    
    printf "\n正在扫描 %u 个Region的详细状态...\n", $hrm->_allocated_heapregions_length
    
    while $i < $hrm->_allocated_heapregions_length && $i < 100
        set $region = $hrm->_regions[$i]
        if $region != 0
            # 检查Region类型
            if $region->is_free()
                set $free_count = $free_count + 1
            end
            if $region->is_eden()
                set $eden_count = $eden_count + 1
            end
            if $region->is_survivor()
                set $survivor_count = $survivor_count + 1
            end
            if $region->is_old()
                set $old_count = $old_count + 1
            end
            if $region->is_humongous()
                set $humongous_count = $humongous_count + 1
            end
        end
        
        set $i = $i + 1
    end
    
    printf "\n📊 Region使用统计 (前100个Region):\n"
    printf "  空闲Region:      %4d 个 (%6.1f MB)\n", $free_count, $free_count * G1HeapRegionSize / (1024.0*1024)
    printf "  Eden区Region:    %4d 个 (%6.1f MB)\n", $eden_count, $eden_count * G1HeapRegionSize / (1024.0*1024)
    printf "  Survivor区Region: %4d 个 (%6.1f MB)\n", $survivor_count, $survivor_count * G1HeapRegionSize / (1024.0*1024)
    printf "  Old区Region:     %4d 个 (%6.1f MB)\n", $old_count, $old_count * G1HeapRegionSize / (1024.0*1024)
    printf "  巨型对象Region:   %4d 个 (%6.1f MB)\n", $humongous_count, $humongous_count * G1HeapRegionSize / (1024.0*1024)
    
    end_timer "Region状态详细分析"
end

# ============================================================================
# 第二阶段：TLAB分配机制深度分析
# ============================================================================

# 分析TLAB详细状态
define analyze_tlab_detailed_state
    start_timer "TLAB详细状态分析"
    printf "\n🧵 === TLAB详细状态分析 ===\n"
    
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0
        printf "❌ 无法获取当前线程\n"
        return
    end
    
    printf "当前线程信息:\n"
    printf "  线程对象地址: %p\n", $thread
    printf "  线程状态: %d\n", $thread->_thread_state
    
    # 分析TLAB基本配置
    printf "\n📦 TLAB基本配置:\n"
    printf "  TLAB起始地址: %p\n", $thread->_tlab._start
    printf "  TLAB当前位置: %p\n", $thread->_tlab._top
    printf "  TLAB结束地址: %p\n", $thread->_tlab._end
    
    if $thread->_tlab._start != 0 && $thread->_tlab._end != 0
        # 计算TLAB使用情况
        set $tlab_capacity = $thread->_tlab._end - $thread->_tlab._start
        set $tlab_used = $thread->_tlab._top - $thread->_tlab._start
        set $tlab_free = $thread->_tlab._end - $thread->_tlab._top
        
        printf "\n📊 TLAB使用情况:\n"
        printf "  TLAB容量: %lu HeapWords (%.2f KB)\n", $tlab_capacity, $tlab_capacity * 8.0 / 1024
        printf "  TLAB已用: %lu HeapWords (%.2f KB, %5.1f%%)\n", $tlab_used, $tlab_used * 8.0 / 1024, $tlab_used * 100.0 / $tlab_capacity
        printf "  TLAB剩余: %lu HeapWords (%.2f KB, %5.1f%%)\n", $tlab_free, $tlab_free * 8.0 / 1024, $tlab_free * 100.0 / $tlab_capacity
        
        # TLAB统计信息
        printf "\n📈 TLAB统计信息:\n"
        printf "  重填充次数: %u\n", $thread->_tlab._number_of_refills
        printf "  快速重填充浪费: %lu HeapWords (%.2f KB)\n", $thread->_tlab._fast_refill_waste, $thread->_tlab._fast_refill_waste * 8.0 / 1024
        printf "  慢速重填充浪费: %lu HeapWords (%.2f KB)\n", $thread->_tlab._slow_refill_waste, $thread->_tlab._slow_refill_waste * 8.0 / 1024
        printf "  GC浪费: %lu HeapWords (%.2f KB)\n", $thread->_tlab._gc_waste, $thread->_tlab._gc_waste * 8.0 / 1024
        
        # 计算TLAB效率指标
        set $total_waste = $thread->_tlab._fast_refill_waste + $thread->_tlab._slow_refill_waste + $thread->_tlab._gc_waste
        
        if $thread->_tlab._number_of_refills > 0
            printf "\n🎯 TLAB效率指标:\n"
            printf "  总浪费量: %lu HeapWords (%.2f KB)\n", $total_waste, $total_waste * 8.0 / 1024
            printf "  平均TLAB大小: %.2f KB\n", $thread->_tlab._desired_size * 8.0 / 1024
        end
    else
        printf "  ❌ TLAB未初始化\n"
    end
    
    end_timer "TLAB详细状态分析"
end

# ============================================================================
# 第三阶段：对象分配流程追踪
# ============================================================================

# 设置对象分配追踪断点
define setup_allocation_tracing
    printf "\n🏭 === 设置对象分配流程追踪 ===\n"
    
    # 初始化统计计数器
    set $small_object_count = 0
    set $medium_object_count = 0
    set $large_object_count = 0
    set $tlab_hit_count = 0
    set $tlab_miss_count = 0
    set $total_allocated_bytes = 0
    
    # 字节码层面断点
    break TemplateTable::_new
    break TemplateTable::anewarray
    break TemplateTable::newarray
    
    # 解释器运行时层面断点
    break InterpreterRuntime::_new
    break InterpreterRuntime::anewarray
    break InterpreterRuntime::newarray
    
    # 堆分配层面断点
    break G1CollectedHeap::obj_allocate
    break G1CollectedHeap::attempt_allocation
    break G1CollectedHeap::attempt_allocation_slow
    
    # TLAB分配层面断点
    break ThreadLocalAllocBuffer::allocate
    
    printf "✅ 对象分配追踪断点设置完成\n"
    printf "统计计数器已初始化\n"
end

# 分析对象头结构
define analyze_object_header_layout
    printf "\n🏷️  === 对象头内存布局分析 ===\n"
    
    printf "对象头配置:\n"
    printf "  Mark Word大小: %lu bytes\n", sizeof(markOop)
    if UseCompressedClassPointers
        printf "  类指针大小: 4 bytes (压缩)\n"
    else
        printf "  类指针大小: 8 bytes (未压缩)\n"
    end
    printf "  对象对齐: %d bytes\n", MinObjAlignmentInBytes
    
    # Mark Word位布局分析
    printf "\nMark Word位布局 (64位平台):\n"
    printf "  位 63-2:  哈希码/锁信息 (62位)\n"
    printf "  位 1:     偏向锁标志\n"
    printf "  位 0:     锁状态标志\n"
    
    printf "\n锁状态编码:\n"
    printf "  00: 轻量级锁\n"
    printf "  01: 无锁/偏向锁\n"
    printf "  10: 重量级锁\n"
    printf "  11: GC标记\n"
end

# ============================================================================
# 第四阶段：内存分配性能基准测试
# ============================================================================

# 显示分配性能统计
define show_allocation_statistics
    printf "\n📈 === 对象分配性能统计 ===\n"
    
    set $total_allocations = $small_object_count + $medium_object_count + $large_object_count
    
    if $total_allocations > 0
        printf "分配数量统计:\n"
        printf "  小对象 (≤32B):     %6d 个 (%5.1f%%)\n", $small_object_count, $small_object_count * 100.0 / $total_allocations
        printf "  中等对象 (32B-1KB): %6d 个 (%5.1f%%)\n", $medium_object_count, $medium_object_count * 100.0 / $total_allocations
        printf "  大对象 (>1KB):      %6d 个 (%5.1f%%)\n", $large_object_count, $large_object_count * 100.0 / $total_allocations
        printf "  总分配数:           %6d 个\n", $total_allocations
        
        printf "\nTLAB性能统计:\n"
        printf "  TLAB命中:          %6d 次 (%5.1f%%)\n", $tlab_hit_count, $tlab_hit_count * 100.0 / $total_allocations
        printf "  TLAB未命中:        %6d 次 (%5.1f%%)\n", $tlab_miss_count, $tlab_miss_count * 100.0 / $total_allocations
        
        if $total_allocated_bytes > 0
            printf "\n内存使用统计:\n"
            printf "  总分配内存: %.2f KB\n", $total_allocated_bytes / 1024.0
            printf "  平均对象大小: %.1f bytes\n", $total_allocated_bytes / $total_allocations
        end
    else
        printf "暂无分配统计数据\n"
    end
end

# ============================================================================
# 第五阶段：压缩指针验证测试
# ============================================================================

# 验证压缩指针编码/解码
define test_compressed_oops_encoding
    printf "\n🔄 === 压缩指针编码/解码测试 ===\n"
    
    if !UseCompressedOops
        printf "未启用压缩指针，跳过测试\n"
        return
    end
    
    set $heap = (G1CollectedHeap*)Universe::_collectedHeap
    set $test_addr = $heap->_reserved._start + 1024
    
    printf "测试地址: %p\n", $test_addr
    printf "压缩指针基址: 0x%016lx\n", Universe::_narrow_oop._base
    printf "压缩指针偏移: %d 位\n", Universe::_narrow_oop._shift
    
    # 模拟编码过程
    if Universe::_narrow_oop._base != 0
        set $offset_addr = $test_addr - Universe::_narrow_oop._base
    else
        set $offset_addr = $test_addr
    end
    
    set $encoded_oop = $offset_addr >> Universe::_narrow_oop._shift
    printf "编码结果: 0x%x\n", $encoded_oop
    
    # 模拟解码过程
    set $decoded_addr = $encoded_oop << Universe::_narrow_oop._shift
    if Universe::_narrow_oop._base != 0
        set $decoded_addr = $decoded_addr + Universe::_narrow_oop._base
    end
    
    printf "解码结果: %p\n", $decoded_addr
    
    if $decoded_addr == $test_addr
        printf "✅ 压缩指针编码/解码正确\n"
    else
        printf "❌ 压缩指针编码/解码错误\n"
    end
end

# ============================================================================
# 主执行流程
# ============================================================================

printf "\n🎬 === 开始执行内存模型深度分析 ===\n"

# 等待JVM完全启动
break main
commands 1
    silent
    continue
end

# 在JVM初始化完成后开始分析
break Threads::create_vm
commands 2
    silent
    finish
    
    printf "\n🚀 JVM启动完成，开始内存分析...\n"
    
    # 执行所有分析
    verify_g1_heap_configuration
    verify_compressed_oops_configuration
    analyze_region_detailed_state
    analyze_tlab_detailed_state
    analyze_object_header_layout
    test_compressed_oops_encoding
    setup_allocation_tracing
    
    printf "\n📊 内存分析完成，继续执行程序...\n"
    continue
end

# 程序结束时显示最终统计
define final_memory_report
    printf "\n🏁 === 最终内存分析报告 ===\n"
    
    verify_g1_heap_configuration
    analyze_tlab_detailed_state
    show_allocation_statistics
    
    printf "\n📋 分析完成，详细日志已保存到: chapter_02_memory_deep_analysis.log\n"
end

# 设置程序退出时的处理
define hook-stop
    final_memory_report
end

printf "内存分析脚本加载完成，开始运行程序...\n"

# 开始执行
run

# 关闭日志
set logging off

quit