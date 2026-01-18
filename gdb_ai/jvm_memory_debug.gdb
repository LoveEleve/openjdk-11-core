# JVM内存布局Debug脚本
# 用于验证8GB堆配置下的JVM内存布局

set pagination off
set print pretty on
set print object on

# 在堆初始化完成后设置断点
break universe_init
break Universe::initialize_heap
break ReservedHeapSpace::ReservedHeapSpace
break G1CollectedHeap::initialize
break Metaspace::global_initialize
break CodeCache::initialize

commands 1
  echo \n=== universe_init 开始 ===\n
  continue
end

commands 2
  echo \n=== Universe::initialize_heap 堆初始化 ===\n
  continue
end

commands 3
  echo \n=== ReservedHeapSpace 构造函数 ===\n
  printf "请求堆大小: %lu bytes (%lu MB)\n", size, size/1024/1024
  printf "对齐要求: %lu\n", alignment
  printf "使用大页: %d\n", large
  continue
end

commands 4
  echo \n=== G1CollectedHeap::initialize ===\n
  continue
end

commands 5
  echo \n=== Metaspace::global_initialize ===\n
  continue
end

commands 6
  echo \n=== CodeCache::initialize ===\n
  continue
end

# 运行JVM
run -Xms8g -Xmx8g -XX:+UseG1GC -version

# 打印关键内存信息
echo \n=== JVM内存布局验证 ===\n

# 打印Universe信息
print Universe::_collectedHeap
print Universe::_narrow_oop
print Universe::_narrow_klass
print Universe::_narrow_ptrs_base
print Universe::_narrow_klass_range

quit
