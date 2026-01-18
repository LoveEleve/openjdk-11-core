# JVM内存布局精确验证GDB脚本
# 环境: -Xms8g -Xmx8g -XX:+UseG1GC
# 作用: 通过源码级调试验证压缩指针、堆内存布局等关键参数

set pagination off
set print pretty on
set print object on

# 设置日志
set logging file /data/workspace/openjdk11-core/gdb_ai/memory_verify_output.txt
set logging overwrite on
set logging on

echo ========================================\n
echo JVM 8GB堆内存布局GDB验证开始\n
echo ========================================\n

# 断点1: Universe::initialize_heap - 堆初始化入口
break Universe::initialize_heap
commands
  silent
  echo \n=== [断点] Universe::initialize_heap() ===\n
  echo 堆初始化开始\n
  continue
end

# 断点2: 压缩指针堆分配策略
break ReservedHeapSpace::initialize_compressed_heap
commands
  silent
  echo \n=== [断点] initialize_compressed_heap() ===\n
  printf "堆大小(size): %lu bytes (%.2f GB)\n", size, (double)size/1024/1024/1024
  printf "对齐(alignment): %lu bytes (%.2f MB)\n", alignment, (double)alignment/1024/1024
  printf "使用大页(large): %d\n", large
  continue
end

# 断点3: 尝试预留堆内存
break ReservedHeapSpace::try_reserve_heap
commands
  silent
  echo \n=== [断点] try_reserve_heap() ===\n
  printf "请求大小(size): %lu bytes (%.2f GB)\n", size, (double)size/1024/1024/1024
  printf "请求地址(requested_address): %p\n", requested_address
  printf "对齐(alignment): %lu bytes\n", alignment
  continue
end

# 断点4: G1堆初始化
break G1CollectedHeap::initialize
commands
  silent
  echo \n=== [断点] G1CollectedHeap::initialize() ===\n
  continue
end

# 断点5: HeapRegion大小设置
break HeapRegion::setup_heap_region_size
commands
  silent
  echo \n=== [断点] HeapRegion::setup_heap_region_size() ===\n
  printf "初始堆大小(initial_heap_size): %lu bytes (%.2f GB)\n", initial_heap_size, (double)initial_heap_size/1024/1024/1024
  printf "最大堆大小(max_heap_size): %lu bytes (%.2f GB)\n", max_heap_size, (double)max_heap_size/1024/1024/1024
  continue
end

# 断点6: 打印压缩指针模式
break Universe::print_compressed_oops_mode
commands
  silent
  echo \n=== [断点] print_compressed_oops_mode() ===\n
  echo 即将输出压缩指针模式信息...\n
  continue
end

# 运行JVM
run -Xms8g -Xmx8g -XX:+UseG1GC -Xlog:gc+heap=debug,gc+heap+coops=debug -version

# 验证完成后打印关键参数
echo \n========================================\n
echo 验证完成\n
echo ========================================\n

set logging off
quit
