# G1CollectedHeap内存布局调试脚本
set confirm off
set pagination off
set print pretty on
set print elements 0

# 设置源码目录
directory /data/workspace/openjdk11-core/src

# 关键断点 - 在JVM初始化完成后
break Universe::initialize_heap
commands 1
  echo ========== Universe::initialize_heap 被调用 ==========\n
  continue
end

# 在G1CollectedHeap创建后
break G1CollectedHeap::initialize
commands 2
  echo ========== G1CollectedHeap::initialize 被调用 ==========\n
  print "G1CollectedHeap实例地址:"
  print this
  print "堆大小配置:"
  print _hrm
  continue
end

# 在主类加载完成后停下来分析内存布局
break JavaCalls::call_static
commands 3
  echo ========== JavaCalls::call_static - 准备调用Java main方法 ==========\n
  echo 现在分析G1CollectedHeap内存布局...\n
  
  # 获取G1CollectedHeap实例
  print "=== G1CollectedHeap全局实例 ==="
  print Universe::_collectedHeap
  
  # 分析堆区域管理器
  print "=== HeapRegionManager ==="
  print ((G1CollectedHeap*)Universe::_collectedHeap)->_hrm
  
  # 分析G1策略
  print "=== G1Policy ==="  
  print ((G1CollectedHeap*)Universe::_collectedHeap)->_policy
  
  continue
end

# 启动调试
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -cp /data/workspace/demo/out HelloWorld

quit