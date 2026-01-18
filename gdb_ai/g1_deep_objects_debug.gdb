# G1深度对象验证脚本
# 验证G1垃圾收集器的核心对象结构和内存布局
# 环境：-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages

set pagination off
set logging file /data/workspace/openjdk11-core/md/g1_deep_objects_debug.log
set logging enabled on

echo ========================================\n
echo G1深度对象验证开始\n
echo 目标：验证G1收集器的核心对象结构\n
echo ========================================\n

# 设置关键对象的断点
break G1CollectedHeap::G1CollectedHeap
break G1RegionToSpaceMapper::create_mapper
break G1YoungGenSizer::G1YoungGenSizer
break G1MonitoringSupport::G1MonitoringSupport
break G1BlockOffsetTable::G1BlockOffsetTable
break G1RootProcessor::G1RootProcessor

# 运行程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -XX:+UnlockDiagnosticVMOptions -XX:+PrintGCDetails -XX:+TraceConcurrentGCollection -cp /data/workspace/demo/out HelloWorld

# G1CollectedHeap构造函数
commands 1
  echo === G1CollectedHeap对象构造 ===\n
  print "G1CollectedHeap this指针:"
  print this
  print "G1CollectedHeap对象大小:"
  print sizeof(*this)
  continue
end

# G1RegionToSpaceMapper创建
commands 2
  echo === G1RegionToSpaceMapper创建 ===\n
  print "创建Region到空间的映射器"
  continue
end

# G1YoungGenSizer构造
commands 3
  echo === G1YoungGenSizer对象构造 ===\n
  print "年轻代大小调整器创建"
  print this
  continue
end

# G1MonitoringSupport构造
commands 4
  echo === G1MonitoringSupport对象构造 ===\n
  print "G1监控支持对象创建"
  print this
  continue
end

# G1BlockOffsetTable构造
commands 5
  echo === G1BlockOffsetTable对象构造 ===\n
  print "G1块偏移表创建"
  print this
  continue
end

# G1RootProcessor构造
commands 6
  echo === G1RootProcessor对象构造 ===\n
  print "G1根处理器创建"
  print this
  continue
end

continue
quit