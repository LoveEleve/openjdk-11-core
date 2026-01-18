# CodeCache深度分析GDB脚本
# 基于标准测试条件：-Xms=8GB -Xmx=8GB, G1GC

# 设置断点在CodeCache初始化
break CodeCache::initialize_heaps
break CodeCache::add_heap

# 运行到CodeCache初始化
run -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld

# 分析CodeCache三段式内存布局
define analyze_codecache_layout
    printf "\n=== CodeCache三段式内存布局分析 ===\n"
    
    # 1. 获取CodeCache全局变量
    printf "\n1. CodeCache全局配置:\n"
    printf "ReservedCodeCacheSize: %lu KB\n", ReservedCodeCacheSize/1024
    printf "NonNMethodCodeHeapSize: %lu KB\n", NonNMethodCodeHeapSize/1024
    printf "ProfiledCodeHeapSize: %lu KB\n", ProfiledCodeHeapSize/1024
    printf "NonProfiledCodeHeapSize: %lu KB\n", NonProfiledCodeHeapSize/1024
    
    # 2. 分析CodeHeap数组
    printf "\n2. CodeHeap实例分析:\n"
    printf "_heaps数组长度: %d\n", CodeCache::_heaps->_len
    printf "_compiled_heaps数组长度: %d\n", CodeCache::_compiled_heaps->_len
    printf "_nmethod_heaps数组长度: %d\n", CodeCache::_nmethod_heaps->_len
    
    # 3. 遍历每个CodeHeap
    set $i = 0
    while $i < CodeCache::_heaps->_len
        set $heap = CodeCache::_heaps->_data[$i]
        printf "\nCodeHeap[%d] @ %p:\n", $i, $heap
        printf "  名称: %s\n", $heap->_name
        printf "  类型: %d\n", $heap->_code_blob_type
        printf "  起始地址: %p\n", $heap->_memory._start
        printf "  结束地址: %p\n", $heap->_memory._end
        printf "  大小: %lu KB\n", ($heap->_memory._end - $heap->_memory._start)/1024
        printf "  已用: %lu KB\n", ($heap->_hwm - $heap->_memory._start)/1024
        printf "  使用率: %.2f%%\n", (double)($heap->_hwm - $heap->_memory._start) * 100.0 / ($heap->_memory._end - $heap->_memory._start)
        set $i = $i + 1
    end
    
    # 4. 分析CodeCache边界
    printf "\n3. CodeCache内存边界:\n"
    printf "Lower bound: %p\n", CodeCache::_low_bound
    printf "Upper bound: %p\n", CodeCache::_high_bound
    printf "总跨度: %lu MB\n", (CodeCache::_high_bound - CodeCache::_low_bound)/1024/1024
end

# 分析nmethod分配和管理
define analyze_nmethod_management
    printf "\n=== nmethod生命周期管理分析 ===\n"
    
    # 1. nmethod统计信息
    printf "\n1. nmethod统计:\n"
    printf "依赖nmethod数量: %d\n", CodeCache::_number_of_nmethods_with_dependencies
    printf "需要缓存清理: %s\n", CodeCache::_needs_cache_clean ? "true" : "false"
    
    # 2. 分析scavenge root nmethods链表
    printf "\n2. Scavenge Root nmethods链表:\n"
    set $nm = CodeCache::_scavenge_root_nmethods
    set $count = 0
    while $nm != 0 && $count < 10
        printf "nmethod[%d] @ %p:\n", $count, $nm
        printf "  方法: %s\n", $nm->_method->_name->_body
        printf "  编译级别: %d\n", $nm->_comp_level
        printf "  状态: %d\n", $nm->_state
        set $nm = $nm->_scavenge_root_link
        set $count = $count + 1
    end
    if $count == 10
        printf "... (更多nmethod)\n"
    end
end

# 分析CodeBlob类型分布
define analyze_codeblob_types
    printf "\n=== CodeBlob类型分布分析 ===\n"
    
    # 遍历所有CodeBlob并统计类型
    set $non_nmethod_count = 0
    set $profiled_count = 0
    set $non_profiled_count = 0
    set $total_size = 0
    
    # 这里需要遍历CodeCache中的所有CodeBlob
    # 由于GDB限制，我们先分析已知的heap信息
    printf "\n1. 按CodeHeap统计:\n"
    set $i = 0
    while $i < CodeCache::_heaps->_len
        set $heap = CodeCache::_heaps->_data[$i]
        set $heap_size = $heap->_memory._end - $heap->_memory._start
        set $heap_used = $heap->_hwm - $heap->_memory._start
        
        printf "Heap[%d] (%s):\n", $i, $heap->_name
        printf "  预留: %lu KB\n", $heap_size/1024
        printf "  已用: %lu KB\n", $heap_used/1024
        printf "  空闲: %lu KB\n", ($heap_size - $heap_used)/1024
        
        set $total_size = $total_size + $heap_size
        set $i = $i + 1
    end
    
    printf "\nCodeCache总预留: %lu MB\n", $total_size/1024/1024
end

# 分析编译队列和热点检测
define analyze_compilation_queue
    printf "\n=== JIT编译队列分析 ===\n"
    
    # 分析CompileBroker状态
    printf "\n1. CompileBroker状态:\n"
    # 这些需要在编译器初始化后才能访问
    # printf "C1编译器线程数: %d\n", CompileBroker::_c1_compile_queue->_size
    # printf "C2编译器线程数: %d\n", CompileBroker::_c2_compile_queue->_size
    
    printf "编译策略: %s\n", CompilationPolicy::policy()->name()
end

# 主分析函数
define codecache_complete_analysis
    printf "\n🎯 CodeCache完整分析 - 基于8GB堆配置\n"
    printf "=====================================\n"
    
    analyze_codecache_layout
    analyze_nmethod_management  
    analyze_codeblob_types
    analyze_compilation_queue
    
    printf "\n✅ CodeCache分析完成！\n"
end

# 继续执行到CodeCache初始化完成
continue

# 执行完整分析
codecache_complete_analysis

# 设置断点在第一次JIT编译
break CompileBroker::compile_method

printf "\n等待JIT编译触发...\n"
continue

# 分析JIT编译过程
printf "\n=== JIT编译过程分析 ===\n"
printf "编译方法: %s\n", $arg0->_name->_body
printf "编译级别: %d\n", $arg2
printf "编译队列长度: %d\n", CompileBroker::queue_size($arg2)

# 继续执行
continue