# G1 GC 对象模型 GDB 调试脚本
# 条件: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages

set pagination off
set print pretty on
set print object on
set confirm off

# 加载libjvm.so符号
file /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/images/jdk/bin/java

# 设置断点在G1CollectedHeap::initialize()完成后
break g1CollectedHeap.cpp:1858
# G1CollectedHeap构造函数
break g1CollectedHeap.cpp:1523

run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -XX:+UnlockDiagnosticVMOptions HelloWorld

# 等待第一个断点（构造函数结束）
printf "\n========================================\n"
printf "=== G1CollectedHeap 构造函数完成后 ===\n"
printf "========================================\n"

# 打印关键信息
printf "\n--- 1. G1CollectedHeap 基本信息 ---\n"
print this
print *this

# 继续到initialize()完成
continue

printf "\n========================================\n"
printf "=== G1CollectedHeap::initialize() 完成后 ===\n"
printf "========================================\n"

printf "\n--- 2. HeapRegion 静态配置 ---\n"
print HeapRegion::GrainBytes
print HeapRegion::LogOfHRGrainBytes
print HeapRegion::GrainWords
print HeapRegion::CardsPerRegion

printf "\n--- 3. 堆内存区域 ---\n"
print this->_reserved
print ((G1CollectedHeap*)this)->_hrm

printf "\n--- 4. G1Allocator ---\n"
print this->_allocator
print *this->_allocator

printf "\n--- 5. G1CardTable ---\n"
print this->_card_table
print *this->_card_table

printf "\n--- 6. G1Policy ---\n"
print this->_g1_policy
print *this->_g1_policy

printf "\n--- 7. G1ConcurrentMark ---\n"
print this->_cm
print *this->_cm

printf "\n--- 8. G1RemSet ---\n"
print this->_g1_rem_set

printf "\n--- 9. HotCardCache ---\n"
print this->_hot_card_cache

printf "\n--- 10. HeapRegionManager ---\n"
print this->_hrm._num_committed
print this->_hrm._allocated_heapregions_length

printf "\n--- 11. Eden/Survivor ---\n"
print this->_eden
print this->_survivor

printf "\n--- 12. Old/Humongous Set ---\n"
print this->_old_set
print this->_humongous_set

printf "\n--- 13. CollectionSet ---\n"
print this->_collection_set

printf "\n--- 14. 工作线程 ---\n"
print this->_workers
print ParallelGCThreads

printf "\n--- 15. BOT (Block Offset Table) ---\n"
print this->_bot

printf "\n--- 16. 巨型对象阈值 ---\n"
print G1CollectedHeap::_humongous_object_threshold_in_words

printf "\n--- 17. 压缩指针配置 ---\n"
print Universe::_narrow_oop
print Universe::_narrow_klass

printf "\n========================================\n"
printf "=== GDB 调试完成 ===\n"
printf "========================================\n"

continue
quit
