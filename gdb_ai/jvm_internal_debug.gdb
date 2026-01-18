# JVM内部函数调试脚本
set confirm off
set pagination off
set print pretty on

# 在JVM初始化完成后设置断点
break JavaCalls::call_static
commands 1
  echo ========== JavaCalls::call_static 被调用 ==========\n
  
  # 检查是否是HelloWorld.main调用
  if $_streq((char*)method->name()->as_C_string(), "main")
    echo 发现main方法调用！\n
    
    # 打印G1CollectedHeap信息
    echo === G1CollectedHeap 全局实例分析 ===\n
    print Universe::_collectedHeap
    
    # 转换为G1CollectedHeap类型
    set $g1 = (G1CollectedHeap*)Universe::_collectedHeap
    
    echo === 堆区域管理器 ===\n
    print $g1->_hrm
    print "区域数量:"
    print $g1->_hrm->_num_committed
    
    echo === G1策略对象 ===\n
    print $g1->_policy
    
    echo === 并发标记对象 ===\n
    print $g1->_cm
    
    echo === 分配器对象 ===\n
    print $g1->_allocator
    
    echo 继续执行...\n
  end
  
  continue
end

# 启动调试
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -cp /data/workspace/demo/out HelloWorld

quit