# GDB调试脚本：堆内存分配机制验证
# 验证环境：-Xms8g -Xmx8g -XX:+UseG1GC (不开启大页) Linux系统
# 目标：验证JVM堆内存的分配和管理机制

set pagination off
set print pretty on
set logging file /data/workspace/openjdk11-core/md/heap_debug_output.log
set logging on

echo ========================================\n
echo JVM堆内存分配机制验证开始\n
echo 验证条件：-Xms8g -Xmx8g -XX:+UseG1GC\n
echo 系统：Linux x86_64，不开启大页\n
echo ========================================\n

# 设置断点在关键函数
break main
break JavaMain
break JNI_CreateJavaVM
break Universe::initialize_heap
break G1CollectedHeap::initialize

# 运行程序 - 严格按照指定参数
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -cp /data/workspace/demo/out HelloWorld

# 在main断点处执行
commands 1
  echo === main函数入口 ===\n
  print argc
  print argv[0]
  print argv[1]
  print argv[2]
  print argv[3]
  print argv[4]
  print argv[5]
  continue
end

# 在JavaMain断点处执行
commands 2
  echo === JavaMain函数执行 ===\n
  info threads
  continue
end

# 在JNI_CreateJavaVM断点处执行
commands 3
  echo === JVM创建开始 ===\n
  print *vm_args
  continue
end

# 在Universe::initialize_heap断点处执行
commands 4
  echo === 堆初始化开始 ===\n
  print "堆初始化参数验证"
  continue
end

# 在G1CollectedHeap::initialize断点处执行
commands 5
  echo === G1堆初始化 ===\n
  print "G1收集器初始化"
  continue
end

# 继续执行直到程序结束
continue
quit