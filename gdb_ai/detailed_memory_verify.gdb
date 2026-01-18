# JVM内存布局详细参数验证GDB脚本
# 这个脚本在关键函数返回后打印详细参数

set pagination off
set print pretty on

# 设置日志
set logging file /data/workspace/openjdk11-core/gdb_ai/detailed_verify_output.txt
set logging overwrite on
set logging on

echo ========================================\n
echo JVM内存参数详细验证\n
echo ========================================\n

# 在HeapRegion::setup_heap_region_size返回后检查参数
break heapRegion.cpp:109
commands
  silent
  echo \n=== HeapRegion参数(setup_heap_region_size返回后) ===\n
  printf "GrainBytes: %lu bytes (%.2f MB)\n", HeapRegion::GrainBytes, (double)HeapRegion::GrainBytes/1024/1024
  printf "GrainWords: %lu\n", HeapRegion::GrainWords
  printf "LogOfHRGrainBytes: %d\n", HeapRegion::LogOfHRGrainBytes
  printf "LogOfHRGrainWords: %d\n", HeapRegion::LogOfHRGrainWords
  printf "CardsPerRegion: %lu\n", HeapRegion::CardsPerRegion
  continue
end

# 在G1CollectedHeap::initialize中检查堆参数
break g1CollectedHeap.cpp:1600
commands
  silent
  echo \n=== G1CollectedHeap初始化参数 ===\n
  printf "init_byte_size: %lu bytes (%.2f GB)\n", init_byte_size, (double)init_byte_size/1024/1024/1024
  printf "max_byte_size: %lu bytes (%.2f GB)\n", max_byte_size, (double)max_byte_size/1024/1024/1024
  printf "heap_alignment: %lu bytes (%.2f MB)\n", heap_alignment, (double)heap_alignment/1024/1024
  continue
end

# 在Universe::initialize_heap返回前检查压缩指针参数
break universe.cpp:865
commands
  silent
  echo \n=== 压缩指针参数(Universe::initialize_heap返回前) ===\n
  printf "narrow_oop_base: %p\n", Universe::_narrow_oop._base
  printf "narrow_oop_shift: %d\n", Universe::_narrow_oop._shift
  printf "narrow_oop_use_implicit_null_checks: %d\n", Universe::_narrow_oop._use_implicit_null_checks
  continue
end

run -Xms8g -Xmx8g -XX:+UseG1GC -version

echo \n========================================\n
echo 详细验证完成\n
echo ========================================\n

set logging off
quit
