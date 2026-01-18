# 修复的JVM内部调试脚本
set confirm off
set pagination off
set print pretty on

# 在JavaCalls::call_static设置断点
break JavaCalls::call_static
commands 1
  echo ========== JavaCalls::call_static 被调用 ==========\n
  
  # 检查方法名
  print "方法名:"
  print name->as_C_string()
  
  # 如果是main方法，分析G1内存布局
  if $_streq((char*)name->as_C_string(), "main")
    echo *** 发现main方法调用！开始分析G1内存布局 ***\n
    
    # 打印Universe中的CollectedHeap
    echo === Universe::_collectedHeap ===\n
    print Universe::_collectedHeap
    
    # 强制转换为G1CollectedHeap
    set $g1heap = (G1CollectedHeap*)Universe::_collectedHeap
    echo G1CollectedHeap地址:
    print $g1heap
    
    # 分析关键组件
    echo === HeapRegionManager ===\n
    print $g1heap->_hrm
    
    echo === G1Policy ===\n
    print $g1heap->_policy
    
    echo === G1ConcurrentMark ===\n
    print $g1heap->_cm
    
    echo === G1Allocator ===\n
    print $g1heap->_allocator
    
    echo *** G1内存布局分析完成 ***\n
  end
  
  continue
end

# 启动程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -cp /data/workspace/demo/out HelloWorld

quit