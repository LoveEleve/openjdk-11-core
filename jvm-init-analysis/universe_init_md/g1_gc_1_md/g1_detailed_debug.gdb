# G1 GC 对象模型 详细GDB 调试脚本
# 条件: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages

set pagination off
set print pretty on
set print object on
set confirm off

# 设置断点
break Universe::initialize_heap
break G1CollectedHeap::initialize

run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages HelloWorld

# 第一个断点 Universe::initialize_heap
printf "\n========================================\n"
printf "=== [1] Universe::initialize_heap() 入口 ===\n"
printf "========================================\n"
print Universe::_collectedHeap

continue

# G1CollectedHeap::initialize
printf "\n========================================\n"
printf "=== [2] G1CollectedHeap::initialize() ===\n"
printf "========================================\n"

# 在initialize完成后设置断点
tbreak g1CollectedHeap.cpp:1857

continue

printf "\n========================================\n"
printf "=== [3] G1CollectedHeap::initialize() 完成后 ===\n"
printf "========================================\n"

printf "\n--- HeapRegion 静态配置 ---\n"
print HeapRegion::GrainBytes
print HeapRegion::LogOfHRGrainBytes
print HeapRegion::GrainWords
print HeapRegion::CardsPerRegion

printf "\n--- G1CollectedHeap 核心成员 ---\n"
print this
print this->_reserved
print &this->_hrm

printf "\n--- HeapRegionManager ---\n"
print this->_hrm._num_committed

printf "\n--- G1Allocator ---\n"
print this->_allocator

printf "\n--- G1CardTable ---\n"
print this->_card_table

printf "\n--- G1Policy ---\n"
print this->_g1_policy

printf "\n--- WorkGang ---\n"
print this->_workers
print ParallelGCThreads

printf "\n--- BOT ---\n"
print this->_bot

printf "\n--- 巨型对象阈值 ---\n"
print G1CollectedHeap::_humongous_object_threshold_in_words

printf "\n--- HotCardCache ---\n"
print this->_hot_card_cache

printf "\n--- G1RemSet ---\n"
print this->_g1_rem_set

printf "\n--- Eden/Survivor ---\n"
print this->_eden
print this->_survivor

printf "\n--- Old/Humongous Set ---\n"
print this->_old_set._length
print this->_humongous_set._length

printf "\n--- ConcurrentMark ---\n"
print this->_cm

printf "\n--- ConcurrentRefine ---\n"
print this->_cr

printf "\n--- CollectionSet ---\n"
print &this->_collection_set

printf "\n--- CollectorState ---\n"
print this->_collector_state

printf "\n========================================\n"
printf "=== 调试完成 ===\n"
printf "========================================\n"

continue
quit
