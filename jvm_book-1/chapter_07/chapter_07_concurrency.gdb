# 第07章：并发机制与线程管理 GDB调试脚本
# 标准环境：-Xms=8GB -Xmx=8GB, G1GC, 非大页, 非NUMA

# 基础设置
set confirm off
set pagination off
set print pretty on
set print array on
set logging file chapter_07_concurrency_debug.log
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

# 线程管理验证
define verify_thread_management
    print_header "线程管理验证"
    
    # 检查线程列表
    print_info "检查Java线程列表"
    if Threads::_thread_list != 0
        set $thread = Threads::_thread_list
        set $count = 0
        
        while $thread != 0 && $count < 20
            if $thread->is_Java_thread()
                set $java_thread = (JavaThread*)$thread
                printf "  线程[%d]: %p (状态: %d)\n", $count, $java_thread, $java_thread->_thread_state
                
                # 显示线程名称
                if $java_thread->threadObj() != 0
                    set $name_oop = java_lang_Thread::name($java_thread->threadObj())
                    if $name_oop != 0
                        printf "    名称: %s\n", $name_oop->base()
                    end
                end
                
                # 显示栈信息
                printf "    栈基址: %p, 栈大小: %lu\n", $java_thread->_stack_base, $java_thread->_stack_size
                
                # 显示线程状态
                printf "    JVM状态: %d, OS线程: %p\n", $java_thread->_thread_state, $java_thread->_osthread
            end
            
            set $thread = $thread->next()
            set $count = $count + 1
        end
        
        printf "总共检查了 %d 个线程\n", $count
    else
        print_warning "线程列表为空"
    end
end

# 线程状态分析
define analyze_thread_states
    print_header "线程状态分析"
    
    # 统计各种状态的线程数
    set $new_count = 0
    set $runnable_count = 0
    set $blocked_count = 0
    set $waiting_count = 0
    set $in_vm_count = 0
    set $in_native_count = 0
    
    if Threads::_thread_list != 0
        set $thread = Threads::_thread_list
        
        while $thread != 0
            if $thread->is_Java_thread()
                set $java_thread = (JavaThread*)$thread
                set $state = $java_thread->_thread_state
                
                if $state == 2
                    set $new_count = $new_count + 1
                end
                if $state == 4 || $state == 5
                    set $in_native_count = $in_native_count + 1
                end
                if $state == 6 || $state == 7
                    set $in_vm_count = $in_vm_count + 1
                end
                if $state == 8 || $state == 9
                    set $runnable_count = $runnable_count + 1
                end
                if $state == 10 || $state == 11
                    set $blocked_count = $blocked_count + 1
                end
            end
            set $thread = $thread->next()
        end
    end
    
    print_info "线程状态统计"
    printf "  新建线程: %d\n", $new_count
    printf "  可运行线程: %d\n", $runnable_count
    printf "  阻塞线程: %d\n", $blocked_count
    printf "  等待线程: %d\n", $waiting_count
    printf "  VM中线程: %d\n", $in_vm_count
    printf "  本地代码线程: %d\n", $in_native_count
end

# 监视器状态检查
define check_monitor_status
    print_header "监视器状态检查"
    
    print_info "检查对象监视器池"
    if ObjectSynchronizer::gBlockList != 0
        set $block = ObjectSynchronizer::gBlockList
        set $count = 0
        
        while $block != 0 && $count < 10
            printf "  监视器块[%d]: %p\n", $count, $block
            printf "    下一个块: %p\n", $block->_next
            printf "    监视器数量: %d\n", $block->_top - $block->_monitors
            
            set $block = $block->_next
            set $count = $count + 1
        end
    else
        print_warning "监视器块列表为空"
    end
    
    print_info "检查全局监视器统计"
    if ObjectSynchronizer::_sync_ContendedLockAttempts != 0
        printf "  锁竞争尝试次数: %ld\n", ObjectSynchronizer::_sync_ContendedLockAttempts->value()
    end
    if ObjectSynchronizer::_sync_FutileWakeups != 0
        printf "  无效唤醒次数: %ld\n", ObjectSynchronizer::_sync_FutileWakeups->value()
    end
    if ObjectSynchronizer::_sync_Parks != 0
        printf "  线程阻塞次数: %ld\n", ObjectSynchronizer::_sync_Parks->value()
    end
end

# 锁状态分析
define analyze_lock_states
    if $argc != 1
        print_error "用法: analyze_lock_states <object_address>"
        help analyze_lock_states
    else
        print_header "锁状态分析"
        set $obj = (oopDesc*)$arg0
        
        print_info "对象基本信息"
        printf "对象地址: %p\n", $obj
        printf "对象类: %p\n", $obj->_metadata._klass
        
        print_info "对象头分析"
        set $mark = $obj->_mark
        printf "Mark Word: 0x%lx\n", $mark
        
        # 分析锁状态
        set $lock_bits = $mark & 0x3
        if $lock_bits == 0x1
            printf "锁状态: 无锁状态\n"
            printf "哈希码: 0x%lx\n", ($mark >> 8) & 0x1ffffff
            printf "分代年龄: %d\n", ($mark >> 3) & 0xf
        end
        
        if $lock_bits == 0x0
            printf "锁状态: 轻量级锁\n"
            printf "栈锁记录: %p\n", $mark & ~0x3
        end
        
        if $lock_bits == 0x2
            printf "锁状态: 重量级锁\n"
            set $monitor = (ObjectMonitor*)($mark & ~0x3)
            printf "监视器地址: %p\n", $monitor
            
            if $monitor != 0
                printf "  拥有者: %p\n", $monitor->_owner
                printf "  重入次数: %ld\n", $monitor->_recursions
                printf "  等待线程数: %d\n", $monitor->_count
                printf "  入口列表: %p\n", $monitor->_EntryList
                printf "  等待集合: %p\n", $monitor->_WaitSet
            end
        end
        
        if $lock_bits == 0x3
            printf "锁状态: GC标记\n"
        end
        
        # 检查偏向锁
        if ($mark & 0x4) != 0
            printf "偏向锁: 已启用\n"
            printf "偏向线程ID: %ld\n", ($mark >> 8) & 0x1ffffff
            printf "偏向时间戳: %ld\n", ($mark >> 33) & 0x7fffffff
        else
            printf "偏向锁: 未启用\n"
        end
    end
end

# 安全点状态检查
define check_safepoint_status
    print_header "安全点状态检查"
    
    print_info "安全点同步状态"
    printf "当前状态: %d\n", SafepointSynchronize::_state
    printf "等待阻塞线程数: %d\n", SafepointSynchronize::_waiting_to_block
    
    # 状态解释
    set $state = SafepointSynchronize::_state
    if $state == 0
        printf "状态描述: 非同步状态\n"
    end
    if $state == 1
        printf "状态描述: 同步中\n"
    end
    if $state == 2
        printf "状态描述: 已同步\n"
    end
    
    print_info "安全点轮询配置"
    if SafepointMechanism::_polling_page != 0
        printf "轮询页地址: %p\n", SafepointMechanism::_polling_page
    end
    
    # 检查VM操作队列
    print_info "VM操作队列状态"
    if VMThread::vm_thread() != 0
        set $vm_thread = VMThread::vm_thread()
        printf "VM线程: %p\n", $vm_thread
        printf "VM线程状态: %d\n", $vm_thread->_thread_state
    end
end

# TLAB状态检查
define check_tlab_status
    print_header "TLAB状态检查"
    
    if Threads::_thread_list != 0
        set $thread = Threads::_thread_list
        set $count = 0
        
        while $thread != 0 && $count < 5
            if $thread->is_Java_thread()
                set $java_thread = (JavaThread*)$thread
                set $tlab = &$java_thread->_tlab
                
                printf "线程[%d] TLAB状态:\n", $count
                printf "  起始地址: %p\n", $tlab->_start
                printf "  当前位置: %p\n", $tlab->_top
                printf "  结束地址: %p\n", $tlab->_end
                printf "  已用大小: %lu bytes\n", ($tlab->_top - $tlab->_start) * 8
                printf "  总大小: %lu bytes\n", ($tlab->_end - $tlab->_start) * 8
                
                if $tlab->_end > $tlab->_start
                    set $usage = (($tlab->_top - $tlab->_start) * 100) / ($tlab->_end - $tlab->_start)
                    printf "  使用率: %lu%%\n", $usage
                end
                
                set $count = $count + 1
            end
            set $thread = $thread->next()
        end
    end
end

# 内存屏障跟踪
define trace_memory_barriers
    print_header "内存屏障跟踪"
    
    # 设置内存屏障断点
    print_info "设置内存屏障跟踪断点"
    break OrderAccess::fence
    commands
        silent
        printf "\n[BARRIER] 全屏障执行\n"
        printf "  线程: %p\n", Thread::current()
        continue
    end
    
    break OrderAccess::acquire
    commands
        silent
        printf "[BARRIER] 获取屏障执行\n"
        continue
    end
    
    break OrderAccess::release
    commands
        silent
        printf "[BARRIER] 释放屏障执行\n"
        continue
    end
    
    # volatile访问跟踪
    break oopDesc::load_heap_oop_volatile
    commands
        silent
        printf "[VOLATILE] volatile读操作: %p\n", $rdi
        continue
    end
    
    break oopDesc::store_heap_oop_volatile
    commands
        silent
        printf "[VOLATILE] volatile写操作: %p -> %p\n", $rdi, $rsi
        continue
    end
end

# 锁竞争跟踪
define trace_lock_contention
    print_header "锁竞争跟踪"
    
    # 设置锁相关断点
    print_info "设置锁竞争跟踪断点"
    break ObjectMonitor::enter
    commands
        silent
        printf "\n[LOCK] 监视器进入: %p (线程: %p)\n", $rdi, Thread::current()
        continue
    end
    
    break ObjectMonitor::exit
    commands
        silent
        printf "[LOCK] 监视器退出: %p (线程: %p)\n", $rdi, Thread::current()
        continue
    end
    
    break ObjectSynchronizer::inflate
    commands
        silent
        printf "[LOCK] 锁膨胀: %p -> 重量级锁\n", $rsi
        continue
    end
    
    break BiasedLocking::revoke_and_rebias
    commands
        silent
        printf "[LOCK] 偏向锁撤销: %p\n", $rdi
        continue
    end
end

# 线程创建跟踪
define trace_thread_creation
    print_header "线程创建跟踪"
    
    # 设置线程创建断点
    print_info "设置线程创建跟踪断点"
    break JavaThread::JavaThread
    commands
        silent
        printf "\n[THREAD] Java线程创建: %p\n", $rdi
        continue
    end
    
    break JavaThread::run
    commands
        silent
        printf "[THREAD] Java线程开始运行: %p\n", $rdi
        continue
    end
    
    break Thread::start
    commands
        silent
        printf "[THREAD] 线程启动: %p\n", $rdi
        continue
    end
    
    # 线程状态转换跟踪
    break ThreadStateTransition::transition
    commands
        silent
        printf "[THREAD] 状态转换: %p (%d -> %d)\n", $rdi, $rsi, $rdx
        continue
    end
end

# 死锁检测
define detect_deadlocks
    print_header "死锁检测"
    
    print_info "分析线程等待关系"
    if Threads::_thread_list != 0
        set $thread = Threads::_thread_list
        
        while $thread != 0
            if $thread->is_Java_thread()
                set $java_thread = (JavaThread*)$thread
                
                # 检查等待的监视器
                set $waiting_monitor = $java_thread->_current_waiting_monitor
                set $pending_monitor = $java_thread->_current_pending_monitor
                
                if $waiting_monitor != 0 || $pending_monitor != 0
                    printf "线程 %p:\n", $java_thread
                    
                    if $waiting_monitor != 0
                        printf "  等待监视器: %p (拥有者: %p)\n", $waiting_monitor, $waiting_monitor->_owner
                    end
                    
                    if $pending_monitor != 0
                        printf "  阻塞监视器: %p (拥有者: %p)\n", $pending_monitor, $pending_monitor->_owner
                    end
                end
            end
            set $thread = $thread->next()
        end
    end
end

# G1并发标记状态
define check_g1_concurrent_marking
    print_header "G1并发标记状态"
    
    if UseG1GC
        set $g1h = G1CollectedHeap::_g1h
        if $g1h != 0
            set $cm = $g1h->_cm
            if $cm != 0
                print_info "并发标记状态"
                printf "并发标记活跃: %d\n", $cm->_concurrent_marking_in_progress
                printf "标记线程数: %d\n", $cm->_max_worker_id
                
                # 检查标记位图
                printf "上次标记位图: %p\n", $cm->_prevMarkBitMap
                printf "下次标记位图: %p\n", $cm->_nextMarkBitMap
                
                # 检查SATB队列
                print_info "SATB队列状态"
                set $satb_qs = $g1h->_satb_mark_queue_set
                if $satb_qs != 0
                    printf "活跃队列数: %d\n", $satb_qs->_n_threads
                    printf "已完成缓冲区数: %d\n", $satb_qs->_completed_buffers_head != 0
                end
            end
        end
    else
        print_warning "G1GC未启用"
    end
end

# 写屏障状态检查
define check_write_barriers
    print_header "写屏障状态检查"
    
    if UseG1GC
        print_info "G1写屏障配置"
        printf "使用G1GC: %d\n", UseG1GC
        
        # 检查卡表状态
        set $g1h = G1CollectedHeap::_g1h
        if $g1h != 0
            set $card_table = $g1h->_card_table
            if $card_table != 0
                printf "卡表基址: %p\n", $card_table->_byte_map
                printf "卡表大小: %lu\n", $card_table->_byte_map_size
            end
        end
    end
end

# 工作窃取队列状态
define check_work_stealing_queues
    print_header "工作窃取队列状态"
    
    if UseG1GC
        set $g1h = G1CollectedHeap::_g1h
        if $g1h != 0
            print_info "G1工作队列状态"
            
            # 检查并行GC线程的任务队列
            set $workers = $g1h->_workers
            if $workers != 0
                printf "工作线程数: %d\n", $workers->_active_workers
                printf "最大线程数: %d\n", $workers->_total_workers
            end
        end
    end
end

# 综合并发分析
define analyze_concurrency_performance
    print_header "并发性能综合分析"
    
    # 执行所有分析
    verify_thread_management
    analyze_thread_states
    check_monitor_status
    check_safepoint_status
    check_tlab_status
    check_g1_concurrent_marking
    check_write_barriers
    check_work_stealing_queues
    
    # 启用跟踪
    trace_memory_barriers
    trace_lock_contention
    trace_thread_creation
    
    print_info "并发性能分析配置完成，继续执行程序..."
end

# 实时监控线程状态
define monitor_thread_states
    print_header "实时线程状态监控"
    
    while 1
        clear
        printf "\n=== 线程状态实时监控 (按Ctrl+C停止) ===\n"
        
        # 统计线程状态
        set $total_threads = 0
        set $java_threads = 0
        set $vm_threads = 0
        set $gc_threads = 0
        
        if Threads::_thread_list != 0
            set $thread = Threads::_thread_list
            
            while $thread != 0
                set $total_threads = $total_threads + 1
                
                if $thread->is_Java_thread()
                    set $java_threads = $java_threads + 1
                end
                if $thread->is_VM_thread()
                    set $vm_threads = $vm_threads + 1
                end
                if $thread->is_ConcurrentGC_thread()
                    set $gc_threads = $gc_threads + 1
                end
                
                set $thread = $thread->next()
            end
        end
        
        printf "总线程数: %d\n", $total_threads
        printf "Java线程: %d\n", $java_threads
        printf "VM线程: %d\n", $vm_threads
        printf "GC线程: %d\n", $gc_threads
        
        # 安全点状态
        printf "\n安全点状态: %d\n", SafepointSynchronize::_state
        printf "等待阻塞: %d\n", SafepointSynchronize::_waiting_to_block
        
        # 锁统计
        if ObjectSynchronizer::_sync_ContendedLockAttempts != 0
            printf "\n锁竞争次数: %ld\n", ObjectSynchronizer::_sync_ContendedLockAttempts->value()
        end
        if ObjectSynchronizer::_sync_Parks != 0
            printf "线程阻塞次数: %ld\n", ObjectSynchronizer::_sync_Parks->value()
        end
        
        sleep 2
    end
end

# 主分析函数
define concurrency_full_analysis
    print_header "并发机制完整分析"
    
    # 执行所有分析
    analyze_concurrency_performance
    detect_deadlocks
    
    print_info "并发机制分析完成"
    print_info "使用 'monitor_thread_states' 进行实时监控"
    print_info "使用 'analyze_lock_states <obj_addr>' 分析特定对象锁状态"
end

# 帮助信息
define concurrency_help
    print_header "并发机制调试命令帮助"
    
    printf "基础分析命令:\n"
    printf "  concurrency_full_analysis      - 执行完整并发分析\n"
    printf "  verify_thread_management       - 验证线程管理\n"
    printf "  analyze_thread_states          - 分析线程状态\n"
    printf "  check_monitor_status           - 检查监视器状态\n"
    printf "  check_safepoint_status         - 检查安全点状态\n"
    printf "  check_tlab_status              - 检查TLAB状态\n"
    printf "\n"
    
    printf "锁分析命令:\n"
    printf "  analyze_lock_states <addr>     - 分析对象锁状态\n"
    printf "  trace_lock_contention          - 跟踪锁竞争\n"
    printf "  detect_deadlocks               - 检测死锁\n"
    printf "\n"
    
    printf "并发GC命令:\n"
    printf "  check_g1_concurrent_marking    - 检查G1并发标记\n"
    printf "  check_write_barriers           - 检查写屏障\n"
    printf "  check_work_stealing_queues     - 检查工作窃取队列\n"
    printf "\n"
    
    printf "跟踪命令:\n"
    printf "  trace_memory_barriers          - 跟踪内存屏障\n"
    printf "  trace_thread_creation          - 跟踪线程创建\n"
    printf "\n"
    
    printf "监控命令:\n"
    printf "  monitor_thread_states          - 实时监控线程状态\n"
    printf "\n"
    
    printf "示例用法:\n"
    printf "  (gdb) concurrency_full_analysis\n"
    printf "  (gdb) analyze_lock_states 0x7f8b2c001234\n"
    printf "  (gdb) monitor_thread_states\n"
end

# 初始化消息
print_header "并发机制调试脚本已加载"
print_info "输入 'concurrency_help' 查看可用命令"
print_info "输入 'concurrency_full_analysis' 开始完整分析"