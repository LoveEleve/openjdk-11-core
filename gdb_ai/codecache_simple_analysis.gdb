# CodeCache简化分析GDB脚本

# 设置断点在main函数
break main

# 运行程序
run -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld

# 跳过到JVM初始化完成
break Universe::initialize_heap
continue

# 继续到CodeCache初始化
break CodeCache::initialize
continue

# 分析CodeCache配置
printf "\n=== CodeCache配置分析 ===\n"
printf "ReservedCodeCacheSize: %lu KB\n", ReservedCodeCacheSize/1024

# 继续执行
continue

# 在程序结束前分析CodeCache状态
break exit
continue

printf "\n=== 程序结束时CodeCache状态 ===\n"
printf "分析完成\n"

quit