# 完整JVM内存布局验证脚本
set pagination off
set print pretty on

# 在堆初始化完成后设置断点
break Universe::initialize_heap
commands
  printf "\n=== Universe::initialize_heap 入口 ===\n"
  continue
end

# G1堆初始化完成后
break G1CollectedHeap::initialize
commands
  printf "\n=== G1CollectedHeap::initialize 入口 ===\n"
  continue
end

# 元空间初始化
break Metaspace::global_initialize
commands
  printf "\n=== Metaspace::global_initialize ===\n"
  continue
end

# CodeCache初始化
break CodeCache::initialize
commands
  printf "\n=== CodeCache::initialize ===\n"
  continue
end

# 在main入口前打印内存信息
break main
commands
  printf "\n=== JVM初始化完成 - main() ===\n"
  printf "\n--- CollectedHeap信息 ---\n"
  print Universe::_collectedHeap
  printf "堆类型: G1CollectedHeap\n"
  printf "\n--- 压缩对象指针 (NarrowOop) ---\n"
  print Universe::_narrow_oop
  printf "\n--- 压缩类指针 (NarrowKlass) ---\n"
  print Universe::_narrow_klass
  printf "\n--- 压缩指针基地址 ---\n"
  printf "narrow_ptrs_base = %p\n", Universe::_narrow_ptrs_base
  printf "\n--- Klass范围 ---\n"
  printf "narrow_klass_range = %lu\n", Universe::_narrow_klass_range
  continue
end

run -Xms8g -Xmx8g -XX:+UseG1GC -version
quit
