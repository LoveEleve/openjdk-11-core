# ============================================================================
# 堆详细信息调试脚本
# ============================================================================

set pagination off
set print pretty on
set confirm off

break init.cpp:120
run

echo \n=== 堆详细信息 ===\n

echo \n--- G1CollectedHeap对象 ---\n
print Universe::_collectedHeap
print/x Universe::_collectedHeap

echo \n--- 堆内存区域 (_reserved) ---\n
print ((G1CollectedHeap*)Universe::_collectedHeap)->_reserved
print/x ((G1CollectedHeap*)Universe::_collectedHeap)->_reserved._start

echo \n--- HeapRegion配置 ---\n
print HeapRegion::GrainBytes
print HeapRegion::LogOfHRGrainBytes
print HeapRegion::GrainWords  
print HeapRegion::CardsPerRegion

echo \n--- G1堆特有属性 ---\n
print ((G1CollectedHeap*)Universe::_collectedHeap)->_hrm._num_committed
print ((G1CollectedHeap*)Universe::_collectedHeap)->_hrm._length
print ((G1CollectedHeap*)Universe::_collectedHeap)->_summary_bytes_used

echo \n--- 压缩指针详情 ---\n
print Universe::_narrow_oop
print Universe::_narrow_klass
print/x Universe::_narrow_ptrs_base

echo \n调试完成\n
quit
