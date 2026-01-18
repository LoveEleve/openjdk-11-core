# G1内存分区深度分析脚本
# 目标：验证G1CollectedHeap的Region管理和内存布局

set pagination off
set logging file /data/workspace/openjdk11-core/md/g1_region_debug.log
set logging enabled on

echo ========================================\n
echo G1内存分区深度分析开始\n
echo 验证8GB堆的Region管理机制\n
echo ========================================\n

# 设置关键断点
break Universe::initialize_heap
break G1CollectedHeap::initialize
break G1CollectedHeap::G1CollectedHeap

# 运行程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -XX:+UnlockDiagnosticVMOptions -XX:+PrintGCDetails -cp /data/workspace/demo/out HelloWorld

# Universe::initialize_heap断点处理
commands 1
  echo === Universe堆初始化 ===\n
  print "开始堆初始化过程"
  continue
end

# G1CollectedHeap::initialize断点处理  
commands 2
  echo === G1CollectedHeap初始化 ===\n
  print "G1收集器初始化开始"
  # 尝试打印G1相关信息
  continue
end

# G1CollectedHeap构造函数断点处理
commands 3
  echo === G1CollectedHeap构造 ===\n
  print "G1收集器对象构造"
  continue
end

continue
quit