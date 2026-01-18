# chapter_02_memory_layout.gdb - 8GB G1堆内存布局验证脚本
# 用途：验证G1堆的精确内存布局和配置参数
# 环境：-Xms8g -Xmx8g -XX:+UseG1GC HelloWorld

# 设置输出格式
set print pretty on
set print array on
set pagination off

printf "=== 第02章：内存模型与对象创建 - GDB验证脚本 ===\n"
printf "目标：验证8GB G1堆的完整内存布局\n"
printf "配置：8GB堆 + G1GC + 压缩指针 + 非大页\n\n"

# 验证G1堆基本配置
define verify_g1_heap_config
    printf "=== G1堆基本配置验证 ===\n"
    
    # 获取G1堆实例
    set $heap = (G1CollectedHeap*)Universe::_collectedHeap
    if $heap == 0
        printf "错误: G1堆未初始化\n"
        return
    end
    
    printf "G1堆对象地址: %p\n", $heap
    
    # 验证堆边界和大小
    printf "\n堆内存配置:\n"
    printf "  堆起始地址: 0x%lx (%.1f GB虚拟地址)\n", $heap->_reserved._base, $heap->_reserved._base / 1024.0 / 1024.0 / 1024.0
    printf "  堆结束地址: 0x%lx (%.1f GB虚拟地址)\n", $heap->_reserved._end, $heap->_reserved._end / 1024.0 / 1024.0 / 1024.0
    printf "  堆总大小: %lu bytes (%.2f GB)\n", $heap->_reserved._size, $heap->_reserved._size / 1024.0 / 1024.0 / 1024.0
    
    # 验证Region配置
    printf "\nRegion配置:\n"
    printf "  Region大小: %u bytes (%.1f MB)\n", HeapRegion::GrainBytes, HeapRegion::GrainBytes / 1024.0 / 1024.0
    printf "  Region数量: %u个\n", $heap->_hrm->_allocated_heapregions_length
    printf "  总Region容量: %.2f GB\n", ($heap->_hrm->_allocated_heapregions_length * HeapRegion::GrainBytes) / 1024.0 / 1024.0 / 1024.0
    
    # 验证年轻代配置
    printf "\n年轻代配置:\n"
    set $young_list = &$heap->_young_list
    printf "  当前Eden Region数: %u个\n", $young_list->_length
    printf "  Eden区大小: %.1f MB\n", ($young_list->_length * HeapRegion::GrainBytes) / 1024.0 / 1024.0
    
    # 验证G1策略配置
    set $policy = $heap->_g1_policy
    if $policy != 0
        printf "\nG1策略配置:\n"
        printf "  最大暂停时间目标: %u ms\n", $policy->_max_pause_time_ms
        printf "  年轻代最小Region数: %u个\n", $policy->_young_gen_sizer->_min_desired_young_length
        printf "  年轻代最大Region数: %u个\n", $policy->_young_gen_sizer->_max_desired_young_length
    end
end

# 验证压缩指针配置
define verify_compressed_pointers
    printf "\n=== 压缩指针配置验证 ===\n"
    
    # 验证压缩对象指针
    printf "压缩对象指针:\n"
    printf "  启用状态: %s\n", UseCompressedOops ? "启用 ✅" : "禁用 ❌"
    if UseCompressedOops
        printf "  基址: 0x%lx\n", Universe::_narrow_oop._base
        printf "  偏移位数: %d bits\n", Universe::_narrow_oop._shift
        printf "  可寻址范围: %.1f GB\n", (1ULL << (32 + Universe::_narrow_oop._shift)) / 1024.0 / 1024.0 / 1024.0
    end
    
    # 验证压缩类指针
    printf "\n压缩类指针:\n"
    printf "  启用状态: %s\n", UseCompressedClassPointers ? "启用 ✅" : "禁用 ❌"
    if UseCompressedClassPointers
        printf "  基址: 0x%lx\n", Universe::_narrow_klass._base
        printf "  偏移位数: %d bits\n", Universe::_narrow_klass._shift
        printf "  类空间大小: %lu bytes (%.2f GB)\n", CompressedClassSpaceSize, CompressedClassSpaceSize / 1024.0 / 1024.0 / 1024.0
    end
end

# 验证CodeCache内存布局
define verify_codecache_layout
    printf "\n=== CodeCache内存布局验证 ===\n"
    
    # Non-nmethod区域 (解释器代码)
    set $non_nmethod = CodeCache::get_code_heap(CodeBlobType::NonNMethod)
    if $non_nmethod != 0
        printf "Non-nmethod区域 (解释器):\n"
        printf "  起始地址: %p\n", $non_nmethod->_memory._low_boundary
        printf "  结束地址: %p\n", $non_nmethod->_memory._high_boundary
        set $non_nmethod_size = $non_nmethod->_memory._high_boundary - $non_nmethod->_memory._low_boundary
        printf "  区域大小: %.2f MB\n", $non_nmethod_size / 1024.0 / 1024.0
        printf "  已使用: %.2f MB\n", ($non_nmethod->_memory._top - $non_nmethod->_memory._low_boundary) / 1024.0 / 1024.0
    end
    
    # Profiled区域 (C1编译代码)
    set $profiled = CodeCache::get_code_heap(CodeBlobType::MethodProfiled)
    if $profiled != 0
        printf "\nProfiled区域 (C1编译器):\n"
        printf "  起始地址: %p\n", $profiled->_memory._low_boundary
        printf "  结束地址: %p\n", $profiled->_memory._high_boundary
        set $profiled_size = $profiled->_memory._high_boundary - $profiled->_memory._low_boundary
        printf "  区域大小: %.2f MB\n", $profiled_size / 1024.0 / 1024.0
        printf "  已使用: %.2f MB\n", ($profiled->_memory._top - $profiled->_memory._low_boundary) / 1024.0 / 1024.0
    end
    
    # Non-profiled区域 (C2编译代码)
    set $non_profiled = CodeCache::get_code_heap(CodeBlobType::MethodNonProfiled)
    if $non_profiled != 0
        printf "\nNon-profiled区域 (C2编译器):\n"
        printf "  起始地址: %p\n", $non_profiled->_memory._low_boundary
        printf "  结束地址: %p\n", $non_profiled->_memory._high_boundary
        set $non_profiled_size = $non_profiled->_memory._high_boundary - $non_profiled->_memory._low_boundary
        printf "  区域大小: %.2f MB\n", $non_profiled_size / 1024.0 / 1024.0
        printf "  已使用: %.2f MB\n", ($non_profiled->_memory._top - $non_profiled->_memory._low_boundary) / 1024.0 / 1024.0
    end
    
    # 计算CodeCache总大小
    set $total_codecache = 0
    if $non_nmethod != 0
        set $total_codecache = $total_codecache + $non_nmethod_size
    end
    if $profiled != 0
        set $total_codecache = $total_codecache + $profiled_size
    end
    if $non_profiled != 0
        set $total_codecache = $total_codecache + $non_profiled_size
    end
    printf "\nCodeCache总大小: %.2f MB\n", $total_codecache / 1024.0 / 1024.0
end

# 验证Metaspace内存布局
define verify_metaspace_layout
    printf "\n=== Metaspace内存布局验证 ===\n"
    
    # 获取Metaspace统计信息
    printf "Metaspace配置:\n"
    printf "  最大Metaspace大小: "
    if MaxMetaspaceSize == 0xFFFFFFFFFFFFFFFF
        printf "无限制\n"
    else
        printf "%lu bytes (%.2f MB)\n", MaxMetaspaceSize, MaxMetaspaceSize / 1024.0 / 1024.0
    end
    
    printf "  压缩类空间大小: %lu bytes (%.2f MB)\n", CompressedClassSpaceSize, CompressedClassSpaceSize / 1024.0 / 1024.0
    printf "  初始Metaspace大小: %lu bytes (%.2f MB)\n", MetaspaceSize, MetaspaceSize / 1024.0 / 1024.0
end

# 生成完整内存布局图
define generate_memory_layout_map
    printf "\n=== 完整内存布局图 ===\n"
    
    # 获取关键地址
    set $heap = (G1CollectedHeap*)Universe::_collectedHeap
    set $heap_start = $heap->_reserved._base
    set $heap_end = $heap->_reserved._end
    set $class_space_start = Universe::_narrow_klass._base
    set $class_space_end = $class_space_start + CompressedClassSpaceSize
    
    printf "虚拟地址空间布局 (64位Linux):\n"
    printf "┌─────────────────────────────────────────────────────────────┐\n"
    printf "│ 0x000000000 (0GB)    ── 进程空间起始                        │\n"
    printf "│     │                                                       │\n"
    printf "│ 0x%lx (%.1fGB) ── G1堆起始地址 ⭐               │\n", $heap_start, $heap_start / 1024.0 / 1024.0 / 1024.0
    printf "│     │ ├─ Eden区域        (~%.0fMB)                         │\n", ($heap->_young_list._length * HeapRegion::GrainBytes) / 1024.0 / 1024.0
    printf "│     │ ├─ 可分配区域      (~%.1fGB)                         │\n", (($heap->_hrm->_allocated_heapregions_length - $heap->_young_list._length) * HeapRegion::GrainBytes) / 1024.0 / 1024.0 / 1024.0
    printf "│     │                                                       │\n"
    printf "│ 0x%lx (%.1fGB) ── G1堆结束/压缩类空间起始 ⭐     │\n", $heap_end, $heap_end / 1024.0 / 1024.0 / 1024.0
    printf "│     │ ├─ 压缩类空间      (%.2fGB)                          │\n", CompressedClassSpaceSize / 1024.0 / 1024.0 / 1024.0
    printf "│     │                                                       │\n"
    printf "│ 0x%lx (%.1fGB) ── 压缩类空间结束                 │\n", $class_space_end, $class_space_end / 1024.0 / 1024.0 / 1024.0
    printf "│     │                                                       │\n"
    printf "│ 0x????????????? ── CodeCache区域 (~%.0fMB)              │\n", $total_codecache / 1024.0 / 1024.0
    printf "│ 0x????????????? ── Metaspace区域                         │\n"
    printf "└─────────────────────────────────────────────────────────────┘\n"
end

# 在HelloWorld.main开始时进行完整验证
break HelloWorld.main
commands
    verify_g1_heap_config
    verify_compressed_pointers
    verify_codecache_layout
    verify_metaspace_layout
    generate_memory_layout_map
    printf "\n=== 内存布局验证完成 ===\n"
    continue
end

# 设置运行参数
printf "开始内存布局验证...\n"
printf "使用参数: -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld\n\n"

# 运行目标程序
run -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld

printf "\n=== 内存布局验证脚本执行完成 ===\n"