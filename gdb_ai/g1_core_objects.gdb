# G1核心对象验证脚本

set pagination off
set logging file /data/workspace/openjdk11-core/md/g1_objects_output.log
set logging enabled on

echo ========================================\n
echo G1核心对象深度验证\n
echo ========================================\n

# 设置断点
break G1CollectedHeap::G1CollectedHeap
break G1CollectedHeap::initialize

# 运行程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -cp /data/workspace/demo/out HelloWorld

# G1CollectedHeap构造函数断点
commands 1
  echo === G1CollectedHeap构造函数 ===\n
  printf "G1CollectedHeap对象地址: %p\n", this
  printf "G1CollectedHeap对象大小: %lu bytes\n", sizeof(*this)
  continue
end

# G1CollectedHeap初始化断点
commands 2
  echo === G1CollectedHeap初始化 ===\n
  printf "G1CollectedHeap初始化开始\n"
  continue
end

continue
quit