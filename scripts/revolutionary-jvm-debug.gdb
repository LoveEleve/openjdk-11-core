# 🚀 革命性的JVM初始化调试脚本
# 基于OpenJDK11源码的颠覆性分析工具
# 使用方法: gdb -x revolutionary-jvm-debug.gdb java

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
  
  # 设置预初始化断点
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
    print_critical "基本类型初始化"
    printf "检查关键类型大小:\n"
    printf "sizeof(intx) = %d\n", sizeof(intx)
    printf "sizeof(jobject) = %d\n", sizeof(jobject)
    printf "sizeof(oop) = %d\n", sizeof(oop)
    continue
  end
  
  commands 3
    print_critical "73个全局锁初始化"
    printf "当前锁数量: %d\n", _num_mutex
    continue
  end
end

# 🔥 第1层：基础设施初始化调试
define debug_infrastructure_init
  print_header "第1层：基础设施初始化调试"
  
  break management_init
  break bytecodes_init
  break classLoader_init1
  break codeCache_init
  
  commands 4
    print_critical "JMX管理接口初始化"
    printf "Management对象地址: %p\n", &Management::_jmm_version
    continue
  end
  
  commands 5
    print_critical "256个字节码表初始化"
    printf "字节码表地址: %p\n", &Bytecodes::_lengths
    printf "nop字节码长度: %d\n", Bytecodes::_lengths[Bytecodes::_nop]
    continue
  end
end

# 🔥 第2层：宇宙初始化调试（最关键）
define debug_universe_init
  print_header "第2层：宇宙初始化调试（最关键）"
  
  break universe_init
  break Universe::initialize_heap
  break Universe::set_narrow_oop_base_and_shift
  
  commands 6
    print_critical "宇宙初始化开始 - Genesis"
    printf "当前时间戳: %ld\n", time(NULL)
    printf "堆状态: %p\n", Universe::_heap
    continue
  end
  
  commands 7
    print_critical "G1堆初始化"
    printf "堆基地址: %p\n", Universe::_heap->base()
    printf "堆大小: %ld GB\n", Universe::_heap->capacity() / (1024*1024*1024)
    printf "Region大小: %ld MB\n", G1HeapRegionSize / (1024*1024)
    printf "Region数量: %ld\n", Universe::_heap->capacity() / G1HeapRegionSize
    continue
  end
  
  commands 8
    print_critical "压缩指针配置"
    printf "压缩指针基址: %p\n", Universe::_narrow_oop._base
    printf "压缩指针位移: %d\n", Universe::_narrow_oop._shift
    printf "压缩模式: %s\n", Universe::_narrow_oop._shift == 3 ? "Zero-based" : "HeapBased"
    continue
  end
end

# 🔥 第3层：解释器初始化调试
define debug_interpreter_init
  print_header "第3层：解释器初始化调试"
  
  break interpreter_init
  break templateTable_init
  break TemplateTable::initialize
  
  commands 9
    print_critical "解释器初始化"
    printf "解释器入口表地址: %p\n", &Interpreter::_entry_table
    continue
  end
  
  commands 10
    print_critical "模板表初始化"
    printf "模板表地址: %p\n", &TemplateTable::_template_table
    continue
  end
  
  commands 11
    print_critical "256+字节码模板生成"
    printf "nop模板地址: %p\n", TemplateTable::_template_table[Bytecodes::_nop]._gen
    printf "iconst_0模板地址: %p\n", TemplateTable::_template_table[Bytecodes::_iconst_0]._gen
    continue
  end
end

# 🔥 内存分配追踪
define trace_memory_allocation
  print_header "内存分配实时追踪"
  
  # 追踪关键的内存分配函数
  break os::reserve_memory
  break os::commit_memory
  break Metaspace::allocate
  
  commands 12
    printf "🔥 MMAP保留: 地址=%p, 大小=%ld MB\n", $rdi, $rsi/(1024*1024)
    bt 3
    continue
  end
  
  commands 13
    printf "🔥 MMAP提交: 地址=%p, 大小=%ld MB\n", $rdi, $rsi/(1024*1024)
    continue
  end
end

# 🔥 性能关键路径分析
define analyze_critical_path
  print_header "性能关键路径分析"
  
  # 记录关键函数的进入和退出时间
  break universe_init
  break Universe::initialize_heap
  break G1CollectedHeap::initialize
  
  commands 14
    printf "⏱️  universe_init() 开始: %ld\n", clock()
    set $universe_start = clock()
    continue
  end
  
  commands 15
    printf "⏱️  initialize_heap() 开始: %ld\n", clock()
    set $heap_start = clock()
    continue
  end
  
  commands 16
    printf "⏱️  G1初始化完成: %ld\n", clock()
    set $g1_end = clock()
    printf "G1初始化耗时: %ld 时钟周期\n", $g1_end - $heap_start
    continue
  end
end

# 🔥 错误处理机制验证
define verify_error_handling
  print_header "错误处理机制验证"
  
  # 在关键的错误检查点设置断点
  break init_globals if status != JNI_OK
  break universe_init if status != JNI_OK
  break compileBroker_init if !result
  
  commands 17
    print_critical "初始化失败检测"
    printf "失败状态码: %d\n", status
    printf "调用栈:\n"
    bt
    continue
  end
end

# 🔥 主调试函数
define revolutionary_debug
  print_header "🚀 革命性JVM初始化调试开始"
  
  # 启用所有调试模块
  debug_vm_init_globals
  debug_infrastructure_init  
  debug_universe_init
  debug_interpreter_init
  trace_memory_allocation
  analyze_critical_path
  verify_error_handling
  
  print_success "所有调试断点已设置"
  print_header "开始运行程序..."
  
  # 运行程序
  run HelloWorld
end

# 🔥 快速状态检查
define quick_status
  print_header "JVM初始化状态快速检查"
  
  printf "✅ 基本类型: sizeof(oop)=%d, sizeof(intx)=%d\n", sizeof(oop), sizeof(intx)
  
  if Universe::_heap != 0
    printf "✅ 堆已创建: 基址=%p, 大小=%ld GB\n", Universe::_heap->base(), Universe::_heap->capacity()/(1024*1024*1024)
  else
    printf "❌ 堆未创建\n"
  end
  
  if Universe::_narrow_oop._base != 0 || Universe::_narrow_oop._shift != 0
    printf "✅ 压缩指针: base=%p, shift=%d\n", Universe::_narrow_oop._base, Universe::_narrow_oop._shift
  else
    printf "❌ 压缩指针未配置\n"
  end
  
  printf "✅ 初始化完成标志: %s\n", is_init_completed() ? "true" : "false"
end

# 🔥 启动消息
printf "\033[1;33m"
printf "🚀 革命性JVM初始化调试脚本已加载\n"
printf "📋 可用命令:\n"
printf "   revolutionary_debug  - 开始完整调试\n"
printf "   quick_status        - 快速状态检查\n"
printf "   debug_vm_init_globals - 调试预初始化层\n"
printf "   debug_universe_init - 调试宇宙初始化\n"
printf "   trace_memory_allocation - 追踪内存分配\n"
printf "\033[0m"