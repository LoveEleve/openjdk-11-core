# chapter_02_object_creation.gdb - 对象创建流程完整追踪脚本
# 用途：追踪从new指令到内存分配的完整对象创建过程
# 环境：-Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest

# 设置输出格式
set print pretty on
set print array on
set pagination off

printf "=== 第02章：对象创建流程GDB追踪脚本 ===\n"
printf "目标：追踪完整对象分配调用链和TLAB机制\n"
printf "配置：8GB堆 + G1GC + TLAB + 压缩指针\n\n"

# 全局变量：统计分配次数和大小
set $allocation_count = 0
set $total_allocated_bytes = 0

# 追踪解释器层面的对象分配
break InterpreterRuntime::_new
commands
    set $allocation_count = $allocation_count + 1
    printf "\n=== 对象分配 #%d ===\n", $allocation_count
    
    # 获取分配参数
    set $pool = (ConstantPool*)$rsi
    set $index = $rdx
    
    printf "InterpreterRuntime::_new() - 解释器对象分配\n"
    printf "  常量池: %p\n", $pool
    printf "  常量池索引: %d\n", $index
    
    # 尝试获取类名 (如果可能)
    if $pool != 0 && $index > 0 && $index < $pool->_length
        set $klass_entry = $pool->_resolved_klasses->_data[$index]
        if $klass_entry != 0
            set $klass = (Klass*)$klass_entry
            if $klass != 0 && $klass->_name != 0
                printf "  分配类: %s\n", $klass->_name->_body
            end
        end
    end
    
    continue
end

# 追踪类实例化层面
break InstanceKlass::allocate_instance
commands
    printf "InstanceKlass::allocate_instance() - 类实例化\n"
    
    set $klass = (InstanceKlass*)$rdi
    printf "  InstanceKlass地址: %p\n", $klass
    
    if $klass != 0
        printf "  类名: %s\n", $klass->_name->_body
        printf "  实例大小: %d bytes\n", $klass->_layout_helper
        printf "  字段数量: %d\n", $klass->_java_fields_count
        
        # 累计分配字节数
        set $total_allocated_bytes = $total_allocated_bytes + $klass->_layout_helper
    end
    
    continue
end

# 追踪堆分配层面
break CollectedHeap::obj_allocate
commands
    printf "CollectedHeap::obj_allocate() - 堆分配请求\n"
    
    set $klass = (Klass*)$rdi
    set $size = $rsi
    
    printf "  请求大小: %d words (%d bytes)\n", $size, $size * 8
    printf "  分配类: %p\n", $klass
    
    continue
end

# 追踪内存分配器层面
break MemAllocator::allocate
commands
    printf "MemAllocator::allocate() - 内存分配器\n"
    
    set $allocator = (MemAllocator*)$rdi
    printf "  分配器地址: %p\n", $allocator
    printf "  分配大小: %d words (%d bytes)\n", $allocator->_word_size, $allocator->_word_size * 8
    printf "  分配类: %p\n", $allocator->_klass
    
    continue
end

# 追踪TLAB分配 (关键路径)
break ThreadLocalAllocBuffer::allocate
commands
    printf "TLAB分配 (快速路径):\n"
    
    set $tlab = (ThreadLocalAllocBuffer*)$rdi
    set $size = $rsi
    
    printf "  TLAB起始: %p\n", $tlab->_start
    printf "  TLAB当前: %p\n", $tlab->_top
    printf "  TLAB结束: %p\n", $tlab->_end
    printf "  请求大小: %d words (%d bytes)\n", $size, $size * 8
    
    # 计算分配后的位置
    set $new_top = $tlab->_top + $size
    printf "  分配后位置: %p\n", $new_top
    
    # 检查是否有足够空间
    if $new_top <= $tlab->_end
        printf "  TLAB空间: 充足 ✅\n"
        set $remaining = ($tlab->_end - $new_top) * 8
        printf "  剩余空间: %d bytes (%.2f KB)\n", $remaining, $remaining / 1024.0
    else
        printf "  TLAB空间: 不足 ❌ (需要重填)\n"
    end
    
    continue
end

# 追踪TLAB重填过程
break ThreadLocalAllocBuffer::retire_before_allocation
commands
    printf "\n=== TLAB重填过程 ===\n"
    
    set $tlab = (ThreadLocalAllocBuffer*)$rdi
    set $obj_size = $rsi
    
    printf "TLAB重填原因: 空间不足\n"
    printf "  请求对象大小: %d words (%d bytes)\n", $obj_size, $obj_size * 8
    printf "  旧TLAB起始: %p\n", $tlab->_start
    printf "  旧TLAB当前: %p\n", $tlab->_top
    printf "  旧TLAB结束: %p\n", $tlab->_end
    
    set $old_used = ($tlab->_top - $tlab->_start) * 8
    set $old_remaining = ($tlab->_end - $tlab->_top) * 8
    set $old_total = ($tlab->_end - $tlab->_start) * 8
    
    printf "  旧TLAB使用: %d bytes (%.2f%%)\n", $old_used, ($old_used * 100.0) / $old_total
    printf "  旧TLAB剩余: %d bytes\n", $old_remaining
    
    continue
end

break ThreadLocalAllocBuffer::fill
commands
    printf "TLAB重填完成:\n"
    
    set $start = (HeapWord*)$rsi
    set $top = (HeapWord*)$rdx  
    set $new_size = $rcx
    
    printf "  新TLAB起始: %p\n", $start
    printf "  新TLAB当前: %p\n", $top
    printf "  新TLAB大小: %d words (%.2f MB)\n", $new_size, ($new_size * 8.0) / 1024 / 1024
    
    continue
end

# 验证对象分配结果
define verify_allocation_result
    printf "\n=== 对象分配统计 ===\n"
    
    printf "分配统计:\n"
    printf "  总分配次数: %d\n", $allocation_count
    printf "  总分配字节: %d bytes (%.2f KB)\n", $total_allocated_bytes, $total_allocated_bytes / 1024.0
    
    if $allocation_count > 0
        printf "  平均对象大小: %.1f bytes\n", $total_allocated_bytes / $allocation_count
    end
    
    # 获取当前线程TLAB状态
    set $thread = Thread::current()
    if $thread != 0
        set $tlab = &$thread->_tlab
        
        printf "\n当前TLAB状态:\n"
        printf "  TLAB起始: %p\n", $tlab->_start
        printf "  TLAB当前: %p\n", $tlab->_top
        printf "  TLAB结束: %p\n", $tlab->_end
        
        if $tlab->_start != 0 && $tlab->_end != 0
            set $tlab_size = ($tlab->_end - $tlab->_start) * 8
            set $tlab_used = ($tlab->_top - $tlab->_start) * 8
            set $tlab_remaining = ($tlab->_end - $tlab->_top) * 8
            
            printf "  TLAB大小: %d bytes (%.2f MB)\n", $tlab_size, $tlab_size / 1024.0 / 1024.0
            printf "  TLAB已用: %d bytes (%.2f%%)\n", $tlab_used, ($tlab_used * 100.0) / $tlab_size
            printf "  TLAB剩余: %d bytes (%.2f KB)\n", $tlab_remaining, $tlab_remaining / 1024.0
        end
    end
end

# 分析压缩指针编码
define analyze_compressed_pointers
    printf "\n=== 压缩指针分析 ===\n"
    
    # 获取压缩指针配置
    printf "压缩指针配置:\n"
    printf "  启用状态: %s\n", UseCompressedOops ? "启用" : "禁用"
    
    if UseCompressedOops
        printf "  基址: 0x%lx\n", Universe::_narrow_oop._base
        printf "  偏移: %d bits\n", Universe::_narrow_oop._shift
        
        # 模拟压缩指针编码过程
        set $heap_start = Universe::_narrow_oop._base
        set $shift = Universe::_narrow_oop._shift
        
        printf "\n压缩指针编码示例:\n"
        printf "  假设对象地址: 0x%lx\n", $heap_start + 0x100
        set $offset = 0x100
        set $compressed = $offset >> $shift
        printf "  偏移量: 0x%lx\n", $offset
        printf "  压缩值: 0x%x\n", $compressed
        
        # 验证解码
        set $decoded_offset = $compressed << $shift
        set $decoded_addr = $heap_start + $decoded_offset
        printf "  解码地址: 0x%lx\n", $decoded_addr
        printf "  编码正确: %s\n", ($decoded_addr == $heap_start + 0x100) ? "✅" : "❌"
    end
end

# 在程序开始时设置初始状态
break ObjectCreationTest.main
commands
    printf "=== 开始对象创建测试 ===\n"
    analyze_compressed_pointers
    continue
end

# 在程序结束前进行最终统计
break java.lang.System.exit
commands
    verify_allocation_result
    printf "\n=== 对象创建流程追踪完成 ===\n"
    continue
end

# 设置运行参数
printf "开始对象创建流程追踪...\n"
printf "使用参数: -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest\n\n"

# 运行目标程序
run -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest

printf "\n=== 对象创建流程追踪脚本执行完成 ===\n"