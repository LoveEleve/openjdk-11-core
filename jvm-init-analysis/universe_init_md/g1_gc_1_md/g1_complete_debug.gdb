# G1 GC 对象模型 完整调试脚本
# 条件: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages

set pagination off
set print pretty on
set confirm off

# 在main函数设置断点
break main

run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages HelloWorld

# main断点命中，继续设置内部断点
break universe_init

continue

# 到达universe_init，设置返回断点
finish

printf "\n================================================================\n"
printf "=== universe_init() 完成后 - G1 GC 对象模型验证 ===\n"
printf "================================================================\n"

printf "\n=== 1. HeapRegion 静态配置 ===\n"
print 'HeapRegion::GrainBytes'
print 'HeapRegion::LogOfHRGrainBytes'
print 'HeapRegion::GrainWords'
print 'HeapRegion::CardsPerRegion'

printf "\n=== 2. G1CollectedHeap 对象 ===\n"
set $g1h = (G1CollectedHeap*)'Universe::_collectedHeap'
print $g1h

printf "\n=== 3. 堆内存区域 (_reserved) ===\n"
print $g1h->_reserved

printf "\n=== 4. HeapRegionManager ===\n"
print $g1h->_hrm._num_committed
print $g1h->_hrm._allocated_heapregions_length

printf "\n=== 5. G1Allocator ===\n"
print $g1h->_allocator

printf "\n=== 6. G1CardTable ===\n"
print $g1h->_card_table

printf "\n=== 7. G1Policy ===\n"
print $g1h->_g1_policy

printf "\n=== 8. WorkGang ===\n"
print $g1h->_workers
print 'ParallelGCThreads'

printf "\n=== 9. BOT (Block Offset Table) ===\n"
print $g1h->_bot

printf "\n=== 10. 巨型对象阈值 ===\n"
print 'G1CollectedHeap::_humongous_object_threshold_in_words'

printf "\n=== 11. HotCardCache ===\n"
print $g1h->_hot_card_cache

printf "\n=== 12. G1RemSet ===\n"
print $g1h->_g1_rem_set

printf "\n=== 13. ConcurrentMark ===\n"
print $g1h->_cm

printf "\n=== 14. ConcurrentRefine ===\n"
print $g1h->_cr

printf "\n=== 15. Eden/Survivor ===\n"
print $g1h->_eden
print $g1h->_survivor

printf "\n=== 16. Old/Humongous Set ===\n"
print $g1h->_old_set._length
print $g1h->_humongous_set._length

printf "\n=== 17. CollectorState ===\n"
print $g1h->_collector_state

printf "\n=== 18. 压缩指针配置 ===\n"
print 'Universe::_narrow_oop'
print 'Universe::_narrow_klass'

printf "\n=== 19. EvacStats ===\n"
print $g1h->_survivor_evac_stats
print $g1h->_old_evac_stats

printf "\n=== 20. 引用处理器 ===\n"
print $g1h->_ref_processor_stw
print $g1h->_ref_processor_cm

printf "\n=== 21. DirtyCardQueueSet ===\n"
print $g1h->_dirty_card_queue_set

printf "\n================================================================\n"
printf "=== GDB 调试完成 ===\n"
printf "================================================================\n"

continue
quit
