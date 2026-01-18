# 简化的堆内存验证脚本
# 验证条件：-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages

set pagination off
set logging file /data/workspace/openjdk11-core/md/heap_debug_output.log
set logging on

echo ========================================\n
echo 8GB G1堆内存验证开始\n
echo ========================================\n

# 设置基本断点
break main
break JavaMain

# 运行程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:-UseLargePages -cp /data/workspace/demo/out HelloWorld

# 在main处检查参数
commands 1
  echo === JVM启动参数验证 ===\n
  printf "argc = %d\n", argc
  printf "JVM路径: %s\n", argv[0]
  printf "堆参数: %s %s\n", argv[1], argv[2]
  continue
end

# 在JavaMain处检查线程
commands 2
  echo === JavaMain线程启动 ===\n
  info threads
  continue
end

continue
quit