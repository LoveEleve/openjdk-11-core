# 🚀 8GB G1 JVM初始化专用GDB调试脚本
# 严格按照 -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages 配置
# 使用方法: gdb -x g1_8gb_debug.gdb java

set confirm off
set pagination off
set print pretty on
set print elements 0

# 🔥 定义颜色输出宏
define print_header
  printf "\033[1;32m"
  printf "=== %s ===\n", $arg0
  printf "\033[0m"
end

define print_critical
  printf "\033[1;31m"
  printf "🔥 CRITICAL: %s\n", $arg0
  printf "\033[0m"
end

define print_success
  printf "\033[1;34m"
  printf "✅ SUCCESS: %s\n", $arg0
  printf "\033[0m"
end

# 🔥 第0层：预初始化层调试
define debug_vm_init_globals
  print_header "第0层：VM预初始化调试"
  
  break check_ThreadShadow
  break basic_types_init
  break mutex_init
  break chunkpool_init
  break perfMemory_init
  
  commands 1
    print_critical "线程影子检查开始"
    continue
  end
  
  commands 2
    print_critical "基本类型初始化 - 8GB配置"
    printf "sizeof(intx) = %d 字节\n", sizeof(intx)
    printf "sizeof(jobject) = %d 字节\n", sizeof(jobject)
    printf "sizeof(oop) = %d 字节\n", sizeof(oop)
    continue
  end
  
  commands 3
    print_critical "73个全局锁初始化 (包含13个G1专用锁)"
    printf "当前锁数量: %d\n", _num_mutex
    continue
  end
end

# 🔥 第1层：Universe初始化调试 (8GB G1专用)
define debug_universe_init_8gb
  print_header "第1层：8GB G1 Universe初始化调试"
  
  break universe_init
  break Universe::initialize_heap
  break G1CollectedHeap::initialize
  break Universe::set_narrow_oop_base_and_shift
  
  commands 4
    print_critical "宇宙初始化开始 - 8GB G1配置"
    printf "目标堆大小: 8GB\n"
    printf "目标GC: G1\n"
    printf "大页设置: 禁用\n"
    continue
  end
  
  commands 5
    print_critical "8GB G1堆初始化"
    printf "堆基地址: %p\n", Universe::_heap->base()
    printf "堆大小: %ld GB\n", Universe::_heap->capacity() / (1024*1024*1024)
    continue
  end
  
  commands 6
    print_critical "G1CollectedHeap对象创建"
    printf "G1堆对象地址: %p\n", Universe::_heap
    printf "Region大小: %ld MB\n", G1HeapRegionSize / (1024*1024)
    printf "Region数量: %ld\n", Universe::_heap->capacity() / G1HeapRegionSize
    continue
  end
  
  commands 7
    print_critical "8GB配置下的压缩指针设置"
    printf "压缩指针基址: %p\n", Universe::_narrow_oop._base
    printf "压缩指针位移: %d\n", Universe::_narrow_oop._shift
    if Universe::_narrow_oop._base == 0
      printf "压缩模式: Zero-based (最优性能)\n"
    else
      printf "压缩模式: HeapBased\n"
    end
    continue
  end
end

# 🔥 G1核心对象创建追踪
define debug_g1_objects
  print_header "G1核心对象创建追踪"
  
  break G1CardTable::G1CardTable
  break G1BlockOffsetTable::G1BlockOffsetTable
  break G1ConcurrentMark::G1ConcurrentMark
  break G1Policy::G1Policy
  break HeapRegionManager::HeapRegionManager
  
  commands 8
    print_critical "G1卡表创建"
    printf "卡表大小: 16MB (8GB / 512B)\n"
    continue
  end
  
  commands 9
    print_critical "G1 BOT表创建"
    printf "BOT表大小: 16MB\n"
    continue
  end
  
  commands 10
    print_critical "G1并发标记系统创建"
    printf "标记位图大小: 32MB (prev + next)\n"
    continue
  end
  
  commands 11
    print_critical "G1策略对象创建"
    continue
  end
  
  commands 12
    print_critical "堆Region管理器创建"
    printf "管理2048个4MB Region\n"
    continue
  end
end

# 🔥 内存分配追踪 (8GB配置专用)
define trace_8gb_memory_allocation
  print_header "8GB配置内存分配追踪"
  
  break os::reserve_memory
  break os::commit_memory
  
  commands 13
    printf "🔥 MMAP保留: 地址=%p, 大小=%ld MB\n", $rdi, $rsi/(1024*1024)
    if $rsi == 8589934592
      printf "   → 8GB Java堆虚拟地址空间保留\n"
    end
    if $rsi == 1073741824
      printf "   → 1GB 压缩类空间保留\n"
    end
    if $rsi == 16777216
      printf "   → 16MB G1辅助数据结构 (卡表/BOT表)\n"
    end
    if $rsi == 33554432
      printf "   → 32MB G1标记位图 (prev + next)\n"
    end
    continue
  end
  
  commands 14
    printf "🔥 MMAP提交: 地址=%p, 大小=%ld MB\n", $rdi, $rsi/(1024*1024)
    continue
  end
end

# 🔥 8GB G1状态检查
define check_8gb_g1_status
  print_header "8GB G1堆状态检查"
  
  if Universe::_heap != 0
    printf "✅ G1堆已创建\n"
    printf "   堆基址: %p\n", Universe::_heap->base()
    printf "   堆大小: %ld GB\n", Universe::_heap->capacity()/(1024*1024*1024)
    printf "   Region大小: %ld MB\n", G1HeapRegionSize/(1024*1024)
    printf "   Region数量: %ld\n", Universe::_heap->capacity()/G1HeapRegionSize
  else
    printf "❌ G1堆未创建\n"
  end
  
  printf "\n压缩指针配置:\n"
  printf "   基址: %p\n", Universe::_narrow_oop._base
  printf "   位移: %d\n", Universe::_narrow_oop._shift
  if Universe::_narrow_oop._base == 0
    printf "   模式: Zero-based (最优)\n"
  else
    printf "   模式: HeapBased\n"
  end
  
  printf "\n内存布局:\n"
  printf "   Java堆: 0x600000000 - 0x800000000 (8GB)\n"
  printf "   压缩类空间: 0x800000000 - 0x840000000 (1GB)\n"
end

# 🔥 性能关键路径分析
define analyze_8gb_critical_path
  print_header "8GB G1初始化性能分析"
  
  break universe_init
  break Universe::initialize_heap
  break G1CollectedHeap::initialize
  
  commands 15
    printf "⏱️  universe_init() 开始: %ld\n", clock()
    set $universe_start = clock()
    continue
  end
  
  commands 16
    printf "⏱️  initialize_heap() 开始: %ld\n", clock()
    set $heap_start = clock()
    continue
  end
  
  commands 17
    printf "⏱️  G1初始化完成: %ld\n", clock()
    set $g1_end = clock()
    printf "G1初始化耗时: %ld 时钟周期\n", $g1_end - $heap_start
    continue
  end
end

# 🔥 主调试函数 - 8GB G1专用
define debug_8gb_g1_init
  print_header "🚀 8GB G1 JVM初始化调试开始"
  
  # 启用所有调试模块
  debug_vm_init_globals
  debug_universe_init_8gb
  debug_g1_objects
  trace_8gb_memory_allocation
  analyze_8gb_critical_path
  
  print_success "所有8GB G1调试断点已设置"
  print_header "运行程序: java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages HelloWorld"
  
  # 运行程序
  run -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages HelloWorld
end

# 🔥 启动消息
printf "\033[1;33m"
printf "🚀 8GB G1 JVM初始化专用调试脚本已加载\n"
printf "📋 可用命令:\n"
printf "   debug_8gb_g1_init     - 开始完整的8GB G1调试\n"
printf "   check_8gb_g1_status   - 检查8GB G1堆状态\n"
printf "   debug_universe_init_8gb - 调试Universe初始化\n"
printf "   trace_8gb_memory_allocation - 追踪8GB内存分配\n"
printf "\n🎯 配置要求: -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages\n"
printf "\033[0m"