# 堆初始化详细Debug脚本
set pagination off
set print pretty on

# 断点：ReservedHeapSpace构造函数
break virtualspace.cpp:757
commands
  printf "\n=== ReservedHeapSpace构造函数 ===\n"
  printf "请求大小: %lu bytes = %lu MB\n", size, size/1024/1024
  printf "对齐: %lu\n", alignment
  printf "大页: %d\n", large
  continue
end

# 断点：压缩指针堆初始化
break virtualspace.cpp:540
commands
  printf "\n=== initialize_compressed_heap ===\n"
  printf "堆大小: %lu MB\n", size/1024/1024
  printf "对齐: %lu\n", alignment
  continue
end

# 断点：尝试预留堆内存
break virtualspace.cpp:348
commands
  printf "\n=== try_reserve_heap ===\n"
  printf "大小: %lu MB\n", size/1024/1024
  printf "期望地址: 0x%lx\n", requested_address
  continue
end

# 运行
run -Xms8g -Xmx8g -XX:+UseG1GC -version
quit
