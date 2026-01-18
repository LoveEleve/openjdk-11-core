set pagination off
set confirm off

# 设置断点在HeapRegion::setup_heap_region_size函数
break HeapRegion::setup_heap_region_size
commands
  silent
  printf "=== HeapRegion::setup_heap_region_size 调用 ===\n"
  printf "initial_heap_size: %lu bytes (%.2f MB)\n", $arg0, $arg0/1024.0/1024.0
  printf "max_heap_size: %lu bytes (%.2f MB)\n", $arg1, $arg1/1024.0/1024.0
  continue
end

# 设置断点在GrainBytes设置之后
break heapRegion.cpp:98
commands
  silent
  printf "=== Region大小计算结果 ===\n"
  printf "GrainBytes: %d bytes (%.2f MB)\n", GrainBytes, GrainBytes/1024.0/1024.0
  printf "LogOfHRGrainBytes: %d\n", LogOfHRGrainBytes
  continue
end

run
quit