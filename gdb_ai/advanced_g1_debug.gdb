# 高级G1内存布局调试脚本
set confirm off
set pagination off
set print pretty on

# 在HelloWorld的main方法调用前设置断点
break 'HelloWorld::main(java.lang.String[])'
commands 1
  echo ========== 进入HelloWorld.main方法 ==========\n
  
  # 打印G1CollectedHeap全局实例
  echo === Universe::_collectedHeap 地址 ===\n
  print Universe::_collectedHeap
  
  # 强制转换为G1CollectedHeap并打印关键字段
  echo === G1CollectedHeap 关键字段 ===\n
  set $g1heap = (G1CollectedHeap*)Universe::_collectedHeap
  
  # 打印堆区域管理器
  echo HeapRegionManager地址:\n
  print $g1heap->_hrm
  
  # 打印G1策略
  echo G1Policy地址:\n  
  print $g1heap->_policy
  
  # 打印并发标记
  echo G1ConcurrentMark地址:\n
  print $g1heap->_cm
  
  # 打印收集集合
  echo G1CollectionSet地址:\n
  print $g1heap->_collection_set
  
  # 打印分配器
  echo G1Allocator地址:\n
  print $g1heap->_allocator
  
  continue
end

# 启动调试
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -cp /data/workspace/demo/out HelloWorld

quit