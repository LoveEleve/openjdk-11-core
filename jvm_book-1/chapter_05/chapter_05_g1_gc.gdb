# 第05章：G1垃圾收集器 - GDB调试脚本
# 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 配置

# 设置输出日志
set logging file logs/chapter_05_g1_gc.log
set logging on

# 基础设置
set confirm off
set pagination off
set print pretty on
set print array on

echo ========================================\n
echo 第05章：G1垃圾收集器 GDB调试验证\n
echo 配置: -Xms=Xmx=8GB, G1GC\n
echo ========================================\n

# ============================================
# 1. G1堆初始化验证
# ============================================

# G1堆初始化
break G1CollectedHeap::initialize
commands
  silent
  printf "\n=== G1堆初始化 ===\n"
  printf "开始初始化G1垃圾收集器\n"
  continue
end

# Region管理器初始化
break G1HeapRegionManager::initialize
commands
  silent
  printf "\n=== Region管理器初始化 ===\n"
  printf "最大Region数: %u\n", _max_length
  printf "Region大小: %ld MB\n", HeapRegion::GrainBytes / (1024*1024)
  continue
end

# Region创建
break HeapRegion::HeapRegion
commands
  silent
  printf "\n=== Region创建 ===\n"
  printf "Region ID: %u\n", hrm_index
  printf "起始地址: %p\n", bottom
  printf "结束地址: %p\n", end
  continue
end

# Region类型设置
break HeapRegion::set_eden
commands
  silent
  printf "\n=== 设置Eden Region ===\n"
  printf "Region ID: %u\n", this->hrm_index()
  continue
end

break HeapRegion::set_survivor
commands
  silent
  printf "\n=== 设置Survivor Region ===\n"
  printf "Region ID: %u\n", this->hrm_index()
  continue
end

break HeapRegion::set_old
commands
  silent
  printf "\n=== 设置Old Region ===\n"
  printf "Region ID: %u\n", this->hrm_index()
  continue
end

# ============================================
# 2. 并发标记验证
# ============================================

# 并发标记启动
break G1ConcurrentMark::concurrent_mark_cycle_start
commands
  silent
  printf "\n=== 并发标记周期开始 ===\n"
  printf "标记线程启动\n"
  continue
end

# 标记位图操作
break G1CMBitMap::mark
commands
  silent
  printf "\n=== 标记对象 ===\n"
  printf "对象地址: %p\n", obj
  continue
end

# SATB队列处理
break SATBMarkQueue::handle_completed_buffer
commands
  silent
  printf "\n=== 处理SATB缓冲区 ===\n"
  printf "缓冲区大小: %ld\n", buffer_size() - _index
  continue
end

# 并发标记完成
break G1ConcurrentMark::concurrent_mark_cycle_end
commands
  silent
  printf "\n=== 并发标记周期结束 ===\n"
  printf "已标记字节数: %ld\n", _marked_bytes
  continue
end

# ============================================
# 3. 垃圾回收验证
# ============================================

# Young GC
break G1YoungCollector::collect
commands
  silent
  printf "\n=== Young GC开始 ===\n"
  printf "回收年轻代Region\n"
  continue
end

# 对象复制
break G1ParScanThreadState::copy_to_survivor_space
commands
  silent
  printf "\n=== 对象复制 ===\n"
  printf "源对象: %p\n", old
  printf "对象大小: %ld words\n", old->size()
  continue
end

# Mixed GC回收集合选择
break G1Policy::select_collection_set_candidates
commands
  silent
  printf "\n=== 选择Mixed GC回收集合 ===\n"
  printf "开始选择老年代Region\n"
  continue
end

# Full GC
break G1FullCollector::collect
commands
  silent
  printf "\n=== Full GC开始 ===\n"
  printf "执行完整垃圾回收\n"
  continue
end

# ============================================
# 4. 记忆集和卡表验证
# ============================================

# 写屏障
break G1BarrierSet::write_ref_field_post
commands
  silent
  printf "\n=== 写屏障触发 ===\n"
  printf "字段地址: %p\n", field
  printf "新值: %p\n", new_val
  continue
end

# 卡表标记
break G1CardTable::g1_mark_as_young
commands
  silent
  printf "\n=== 标记年轻代卡片 ===\n"
  printf "内存区域: %p - %p\n", mr.start(), mr.end()
  continue
end

# 并发精化
break G1ConcurrentRefineThread::run_service
commands
  silent
  printf "\n=== 并发精化线程运行 ===\n"
  printf "处理脏卡缓冲区\n"
  continue
end

# ============================================
# 5. 性能预测验证
# ============================================

# 停顿时间预测
break G1Policy::predict_pause_time_ms
commands
  silent
  printf "\n=== 停顿时间预测 ===\n"
  printf "预测停顿时间计算\n"
  continue
end

# 统计更新
break G1Analytics::update_recent_gc_times
commands
  silent
  printf "\n=== 更新GC统计 ===\n"
  printf "结束时间: %f\n", end_time_sec
  printf "耗时: %f ms\n", elapsed_ms
  continue
end

# ============================================
# 6. 自定义GDB命令
# ============================================

# 显示G1堆配置
define show_g1_heap_config
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  set $hrm = $g1h->_hrm
  
  printf "\n=== G1堆配置 ===\n"
  printf "堆大小: %ld MB\n", $g1h->capacity() / (1024*1024)
  printf "Region大小: %ld MB\n", HeapRegion::GrainBytes / (1024*1024)
  printf "最大Region数: %u\n", $hrm->_max_length
  printf "已分配Region数: %u\n", $hrm->_allocated_length
  printf "================\n"
end

# 显示Region使用统计
define show_region_usage
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  set $hrm = $g1h->_hrm
  
  set $free_count = 0
  set $eden_count = 0
  set $survivor_count = 0
  set $old_count = 0
  set $humongous_count = 0
  
  set $i = 0
  while $i < $hrm->_allocated_length
    set $hr = $hrm->_regions[$i]
    if $hr != 0
      if $hr->is_free()
        set $free_count = $free_count + 1
      else
        if $hr->is_eden()
          set $eden_count = $eden_count + 1
        else
          if $hr->is_survivor()
            set $survivor_count = $survivor_count + 1
          else
            if $hr->is_old()
              set $old_count = $old_count + 1
            else
              if $hr->is_humongous()
                set $humongous_count = $humongous_count + 1
              end
            end
          end
        end
      end
    end
    set $i = $i + 1
  end
  
  printf "\n=== Region使用统计 ===\n"
  printf "空闲Region: %d\n", $free_count
  printf "Eden Region: %d\n", $eden_count
  printf "Survivor Region: %d\n", $survivor_count
  printf "老年代Region: %d\n", $old_count
  printf "大对象Region: %d\n", $humongous_count
  printf "总Region数: %d\n", $hrm->_allocated_length
  printf "=====================\n"
end

# 显示并发标记状态
define show_concurrent_mark_state
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  set $cm = $g1h->concurrent_mark()
  
  printf "\n=== 并发标记状态 ===\n"
  printf "标记活跃: %s\n", $cm->mark_in_progress() ? "是" : "否"
  printf "已标记字节: %ld\n", $cm->_marked_bytes
  
  if $cm->_mark_bitmap != 0
    printf "标记位图大小: %ld bytes\n", $cm->_mark_bitmap->_bm.size_in_bytes()
  end
  
  printf "==================\n"
end

# 显示GC统计信息
define show_gc_statistics
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  set $policy = $g1h->g1_policy()
  
  printf "\n=== GC统计信息 ===\n"
  printf "年轻代GC次数: %ld\n", $policy->_young_list_target_length
  printf "混合GC次数: %ld\n", $policy->_mixed_gc_count_target
  
  if $policy->_analytics != 0
    printf "平均GC时间: %f ms\n", $policy->_analytics->_recent_gc_times_ms->avg()
  end
  
  printf "=================\n"
end

# 显示卡表状态
define show_card_table_state
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  set $ct = $g1h->card_table()
  
  printf "\n=== 卡表状态 ===\n"
  printf "卡表基址: %p\n", $ct->_byte_map
  printf "卡片大小: %d bytes\n", CardTable::card_size
  printf "===============\n"
end

# 监控GC活动
define monitor_gc_activity
  printf "\n开始监控G1 GC活动...\n"
  
  # 显示初始状态
  show_g1_heap_config
  show_region_usage
  show_concurrent_mark_state
  
  printf "监控已启动，使用 'continue' 继续执行\n"
end

# 分析内存使用模式
define analyze_memory_pattern
  printf "\n=== 内存使用模式分析 ===\n"
  
  show_region_usage
  show_concurrent_mark_state
  show_gc_statistics
  
  printf "==========================\n"
end

# 检查大对象分配
define check_humongous_allocation
  printf "\n=== 大对象分配检查 ===\n"
  
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  set $hrm = $g1h->_hrm
  
  set $humongous_regions = 0
  set $humongous_bytes = 0
  
  set $i = 0
  while $i < $hrm->_allocated_length
    set $hr = $hrm->_regions[$i]
    if $hr != 0 && $hr->is_humongous()
      set $humongous_regions = $humongous_regions + 1
      set $humongous_bytes = $humongous_bytes + $hr->used()
    end
    set $i = $i + 1
  end
  
  printf "大对象Region数: %d\n", $humongous_regions
  printf "大对象总大小: %ld MB\n", $humongous_bytes / (1024*1024)
  printf "========================\n"
end

# 性能分析
define performance_analysis
  printf "\n=== G1性能分析 ===\n"
  
  show_gc_statistics
  check_humongous_allocation
  
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  printf "堆使用率: %.2f%%\n", (double)$g1h->used() / $g1h->capacity() * 100
  
  printf "==================\n"
end

# ============================================
# 7. 启动监控
# ============================================

echo \n开始G1垃圾收集器验证...\n
echo 使用以下命令进行交互式调试:\n
echo - show_g1_heap_config: 显示G1堆配置\n
echo - show_region_usage: 显示Region使用统计\n
echo - show_concurrent_mark_state: 显示并发标记状态\n
echo - show_gc_statistics: 显示GC统计信息\n
echo - monitor_gc_activity: 监控GC活动\n
echo - analyze_memory_pattern: 分析内存使用模式\n
echo - check_humongous_allocation: 检查大对象分配\n
echo - performance_analysis: 性能分析\n
echo \n

# 运行程序
run

# 程序结束后显示最终统计
echo \n========================================\n
echo G1垃圾收集器验证完成\n
echo ========================================\n

show_g1_heap_config
show_region_usage
show_gc_statistics
performance_analysis

# 关闭日志
set logging off

quit