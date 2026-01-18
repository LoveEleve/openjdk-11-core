# G1对象深度分析脚本

set pagination off
set logging file /data/workspace/openjdk11-core/md/g1_object_analysis.log
set logging enabled on

echo ========================================\n
echo G1对象深度分析开始\n
echo 验证G1CollectedHeap对象的内存结构\n
echo ========================================\n

# 设置断点
break G1CollectedHeap::G1CollectedHeap

# 运行程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -cp /data/workspace/demo/out HelloWorld

# 在G1CollectedHeap构造函数中分析对象
commands 1
  echo === G1CollectedHeap对象分析 ===\n
  printf "G1CollectedHeap对象地址: %p\n", this
  printf "G1CollectedHeap对象大小: %zu bytes\n", sizeof(*this)
  echo === 尝试打印对象成员 ===\n
  # 尝试打印一些成员变量
  print "分析G1CollectedHeap对象结构"
  continue
end

continue
quit