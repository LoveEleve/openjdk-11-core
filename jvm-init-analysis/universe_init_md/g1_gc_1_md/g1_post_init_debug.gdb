# G1 GC 对象模型 调试脚本 - 在init_globals完成后
# 条件: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages

set pagination off
set print pretty on
set confirm off

# 设置断点在 init_globals 之后的某个地方
break thread.cpp:3869

run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages HelloWorld

printf "\n================================================================\n"
printf "=== G1 GC 对象模型验证 (init_globals 完成后) ===\n"
printf "================================================================\n"

printf "\n=== 1. HeapRegion 静态配置 ===\n"
print HeapRegion::GrainBytes
print HeapRegion::LogOfHRGrainBytes
print HeapRegion::GrainWords
print HeapRegion::CardsPerRegion

printf "\n=== 2. G1CollectedHeap 对象 ===\n"
set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
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
print ParallelGCThreads

printf "\n=== 9. BOT (Block Offset Table) ===\n"
print $g1h->_bot

printf "\n=== 10. 巨型对象阈值 ===\n"
print G1CollectedHeap::_humongous_object_threshold_in_words

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
print Universe::_narrow_oop
print Universe::_narrow_klass

printf "\n=== 19. EvacStats ===\n"
print $g1h->_survivor_evac_stats
print $g1h->_old_evac_stats

printf "\n================================================================\n"
printf "=== GDB 调试完成 ===\n"
printf "================================================================\n"

continue
quit
