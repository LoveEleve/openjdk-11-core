# 字节码执行引擎深度分析GDB脚本 - 深度增强版
# 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 标准配置
# 提供150+个关键数据点的完整验证

# 执行引擎架构分析
define analyze_execution_engine_architecture
    printf "=== 执行引擎架构分析 ===\n"
    
    # 1. 解释器状态
    printf "1. 解释器配置:\n"
    if AbstractInterpreter::_code != 0
        printf "   - 解释器代码缓存: 0x%lx\n", AbstractInterpreter::_code
        printf "   - 代码缓存大小: %d bytes\n", AbstractInterpreter::_code->_buffer_size
        printf "   - 已用代码大小: %d bytes\n", AbstractInterpreter::_code->_buffer_end - AbstractInterpreter::_code->_buffer_start
    else
        printf "   - 解释器未初始化\n"
    end
    
    # 2. 字节码表状态
    printf "\n2. 字节码表状态:\n"
    set $defined_bytecodes = 0
    set $i = 0
    while $i < Bytecodes::number_of_codes
        if Bytecodes::is_defined($i)
            set $defined_bytecodes = $defined_bytecodes + 1
        end
        set $i = $i + 1
    end
    printf "   - 已定义字节码数: %d / %d\n", $defined_bytecodes, Bytecodes::number_of_codes
    
    # 3. 方法入口点
    printf "\n3. 方法入口点:\n"
    set $entry_count = 0
    set $i = 0
    while $i < AbstractInterpreter::number_of_method_entries
        if AbstractInterpreter::_entry_table[$i] != 0
            set $entry_count = $entry_count + 1
        end
        set $i = $i + 1
    end
    printf "   - 已生成入口点: %d / %d\n", $entry_count, AbstractInterpreter::number_of_method_entries
    
    # 4. 模板表状态
    printf "\n4. 模板表状态:\n"
    if TemplateTable::_is_initialized
        printf "   - 模板表: ✅ 已初始化\n"
        printf "   - 模板数量: %d\n", Bytecodes::number_of_codes
    else
        printf "   - 模板表: ❌ 未初始化\n"
    end
    
    printf "\n"
end

# 当前线程执行状态分析
define analyze_thread_execution_state
    printf "=== 当前线程执行状态分析 ===\n"
    
    # 获取当前Java线程
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0
        printf "❌ 无法获取当前Java线程\n"
        return
    end
    
    printf "当前线程: 0x%lx\n", $thread
    
    # 1. 线程状态
    printf "\n1. 线程状态:\n"
    set $state = $thread->_thread_state
    printf "   - 线程状态: "
    if $state == 0
        printf "NEW\n"
    elif $state == 2
        printf "IN_JAVA\n"
    elif $state == 3
        printf "IN_VM\n"
    elif $state == 4
        printf "IN_NATIVE\n"
    elif $state == 5
        printf "BLOCKED\n"
    else
        printf "未知状态(%d)\n", $state
    end
    
    # 2. 栈帧信息
    printf "\n2. 栈帧信息:\n"
    if $thread->has_last_Java_frame()
        set $frame = $thread->last_Java_frame()
        printf "   - 栈指针: 0x%lx\n", $frame._sp
        printf "   - 程序计数器: 0x%lx\n", $frame._pc
        
        if $frame.is_interpreted_frame()
            printf "   - 栈帧类型: 解释器栈帧\n"
            set $method = $frame.interpreter_frame_method()
            if $method != 0
                printf "   - 当前方法: %s\n", $method->_name->_body
                printf "   - 方法签名: %s\n", $method->_signature->_body
                printf "   - 字节码长度: %d\n", $method->_code_size
            end
        elif $frame.is_compiled_frame()
            printf "   - 栈帧类型: 编译栈帧\n"
            set $nm = $frame._cb
            if $nm != 0
                printf "   - 编译方法: 0x%lx\n", $nm
                printf "   - 编译级别: %d\n", $nm->_comp_level
            end
        else
            printf "   - 栈帧类型: 其他类型\n"
        end
    else
        printf "   - 无Java栈帧\n"
    end
    
    # 3. 解释器状态
    printf "\n3. 解释器状态:\n"
    if $thread->_interpreter_state != 0
        printf "   - 解释器状态: 0x%lx\n", $thread->_interpreter_state
    else
        printf "   - 解释器状态: 未设置\n"
    end
    
    # 4. OSR缓存
    printf "\n4. OSR缓存:\n"
    if $thread->_osr_nmethod_cache != 0
        printf "   - OSR编译缓存: 0x%lx\n", $thread->_osr_nmethod_cache
        set $osr_method = $thread->_osr_nmethod_cache->_method
        if $osr_method != 0
            printf "   - OSR方法: %s\n", $osr_method->_name->_body
        end
    else
        printf "   - OSR缓存: 空\n"
    end
    
    # 5. 异常状态
    printf "\n5. 异常状态:\n"
    if $thread->_exception_oop != 0
        printf "   - 待处理异常: 0x%lx\n", $thread->_exception_oop
        printf "   - 异常PC: 0x%lx\n", $thread->_exception_pc
    else
        printf "   - 无待处理异常\n"
    end
    
    printf "\n"
end

# 栈帧遍历分析
define analyze_stack_frames
    printf "=== 栈帧遍历分析 ===\n"
    
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0 || !$thread->has_last_Java_frame()
        printf "❌ 无可用的Java栈帧\n"
        return
    end
    
    printf "栈帧遍历:\n"
    set $frame_count = 0
    set $interpreted_count = 0
    set $compiled_count = 0
    set $native_count = 0
    
    # 遍历栈帧
    set $current_frame = $thread->last_Java_frame()
    set $max_frames = 50  # 限制最大栈帧数
    
    while $frame_count < $max_frames && $current_frame._sp != 0
        set $frame_count = $frame_count + 1
        
        printf "%d. 栈帧 0x%lx:\n", $frame_count, $current_frame._sp
        
        if $current_frame.is_interpreted_frame()
            set $interpreted_count = $interpreted_count + 1
            printf "   - 类型: 解释器栈帧\n"
            
            set $method = $current_frame.interpreter_frame_method()
            if $method != 0
                printf "   - 方法: %s.%s%s\n", $method->_method_holder->_name->_body, $method->_name->_body, $method->_signature->_body
                printf "   - 字节码位置: %d / %d\n", 
                    $current_frame.interpreter_frame_bcp() - $method->_code_base,
                    $method->_code_size
                
                # 局部变量信息
                set $locals = $current_frame.interpreter_frame_locals()
                printf "   - 局部变量: 0x%lx\n", $locals
                printf "   - 最大局部变量: %d\n", $method->_max_locals
                printf "   - 最大栈深度: %d\n", $method->_max_stack
            end
            
        elif $current_frame.is_compiled_frame()
            set $compiled_count = $compiled_count + 1
            printf "   - 类型: 编译栈帧\n"
            
            set $nm = $current_frame._cb
            if $nm != 0
                printf "   - 编译方法: 0x%lx\n", $nm
                if $nm->_method != 0
                    printf "   - 方法: %s.%s\n", $nm->_method->_method_holder->_name->_body, $nm->_method->_name->_body
                end
                printf "   - 编译级别: %d\n", $nm->_comp_level
                printf "   - 入口点: 0x%lx\n", $nm->_entry_point
            end
            
        elif $current_frame.is_native_frame()
            set $native_count = $native_count + 1
            printf "   - 类型: 本地方法栈帧\n"
            
        else
            printf "   - 类型: 其他栈帧\n"
        end
        
        printf "   - PC: 0x%lx\n", $current_frame._pc
        
        # 获取下一个栈帧
        # 注意：这里简化处理，实际需要RegisterMap
        break  # 暂时只分析第一个栈帧
    end
    
    printf "\n栈帧统计:\n"
    printf "- 总栈帧数: %d\n", $frame_count
    printf "- 解释器栈帧: %d\n", $interpreted_count
    printf "- 编译栈帧: %d\n", $compiled_count
    printf "- 本地方法栈帧: %d\n", $native_count
    
    printf "\n"
end

# 字节码执行分析
define analyze_bytecode_execution
    printf "=== 字节码执行分析 ===\n"
    
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0 || !$thread->has_last_Java_frame()
        printf "❌ 无可用的Java栈帧\n"
        return
    end
    
    set $frame = $thread->last_Java_frame()
    if !$frame.is_interpreted_frame()
        printf "❌ 当前栈帧不是解释器栈帧\n"
        return
    end
    
    set $method = $frame.interpreter_frame_method()
    if $method == 0
        printf "❌ 无法获取当前方法\n"
        return
    end
    
    printf "当前执行方法: %s.%s%s\n", 
        $method->_method_holder->_name->_body,
        $method->_name->_body,
        $method->_signature->_body
    
    # 1. 字节码信息
    printf "\n1. 字节码信息:\n"
    set $bcp = $frame.interpreter_frame_bcp()
    set $code_base = $method->_code_base
    set $bci = $bcp - $code_base
    
    printf "   - 字节码基址: 0x%lx\n", $code_base
    printf "   - 当前字节码指针: 0x%lx\n", $bcp
    printf "   - 字节码索引: %d\n", $bci
    printf "   - 字节码长度: %d\n", $method->_code_size
    
    if $bci >= 0 && $bci < $method->_code_size
        set $current_bytecode = *((unsigned char*)$bcp)
        printf "   - 当前指令: 0x%02x (%s)\n", $current_bytecode, Bytecodes::name($current_bytecode)
        printf "   - 指令长度: %d\n", Bytecodes::length_for($current_bytecode)
        
        # 显示接下来几条指令
        printf "   - 指令序列:\n"
        set $i = 0
        set $display_count = 5
        while $i < $display_count && ($bci + $i) < $method->_code_size
            set $bc = *((unsigned char*)($bcp + $i))
            printf "     [%d] 0x%02x %s\n", $bci + $i, $bc, Bytecodes::name($bc)
            set $i = $i + 1
        end
    else
        printf "   - ⚠️  字节码索引越界\n"
    end
    
    # 2. 局部变量表
    printf "\n2. 局部变量表:\n"
    set $locals = $frame.interpreter_frame_locals()
    set $max_locals = $method->_max_locals
    printf "   - 局部变量基址: 0x%lx\n", $locals
    printf "   - 最大局部变量数: %d\n", $max_locals
    
    if $max_locals > 0 && $max_locals <= 20  # 限制显示数量
        printf "   - 局部变量值:\n"
        set $i = 0
        while $i < $max_locals
            set $value = *($locals - $i)
            printf "     [%d] = 0x%lx (%ld)\n", $i, $value, $value
            set $i = $i + 1
        end
    end
    
    # 3. 操作数栈
    printf "\n3. 操作数栈:\n"
    set $tos = $frame.interpreter_frame_tos_address()
    set $max_stack = $method->_max_stack
    printf "   - 栈顶地址: 0x%lx\n", $tos
    printf "   - 最大栈深度: %d\n", $max_stack
    
    # 简化的栈内容显示
    if $tos != 0
        printf "   - 栈顶值: 0x%lx\n", *$tos
    end
    
    # 4. 常量池缓存
    printf "\n4. 常量池缓存:\n"
    set $cache = $frame.interpreter_frame_cache()
    if $cache != 0
        printf "   - 缓存地址: 0x%lx\n", $cache
        printf "   - 缓存大小: %d\n", $cache->_length
    else
        printf "   - 无常量池缓存\n"
    end
    
    printf "\n"
end

# 方法调用性能分析
define analyze_method_invocation_performance
    printf "=== 方法调用性能分析 ===\n"
    
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0 || !$thread->has_last_Java_frame()
        printf "❌ 无可用的Java栈帧\n"
        return
    end
    
    set $frame = $thread->last_Java_frame()
    if !$frame.is_interpreted_frame()
        printf "❌ 当前栈帧不是解释器栈帧\n"
        return
    end
    
    set $method = $frame.interpreter_frame_method()
    if $method == 0
        printf "❌ 无法获取当前方法\n"
        return
    end
    
    printf "方法: %s.%s%s\n", 
        $method->_method_holder->_name->_body,
        $method->_name->_body,
        $method->_signature->_body
    
    # 1. 调用计数器
    printf "\n1. 调用计数器:\n"
    if $method->_method_counters != 0
        set $counters = $method->_method_counters
        set $invocation_count = $counters->_invocation_counter._counter & 0x1FFFFFFF
        set $backedge_count = $counters->_backedge_counter._counter & 0x1FFFFFFF
        
        printf "   - 调用次数: %d\n", $invocation_count
        printf "   - 回边次数: %d\n", $backedge_count
        printf "   - 总热度: %d\n", $invocation_count + $backedge_count
        
        # 编译阈值检查
        printf "   - 编译阈值: %d\n", CompileThreshold
        if $invocation_count >= CompileThreshold
            printf "   - 调用计数器: ✅ 已达编译阈值\n"
        else
            printf "   - 调用计数器: ⏳ 未达编译阈值 (%.1f%%)\n", (double)$invocation_count * 100 / CompileThreshold
        end
        
        # OSR阈值检查
        set $osr_threshold = OnStackReplacePercentage * CompileThreshold / 100
        if $backedge_count >= $osr_threshold
            printf "   - 回边计数器: ✅ 已达OSR阈值\n"
        else
            printf "   - 回边计数器: ⏳ 未达OSR阈值 (%.1f%%)\n", (double)$backedge_count * 100 / $osr_threshold
        end
        
        # 编译级别
        printf "   - 最高编译级别: %d\n", $counters->_highest_comp_level
        printf "   - 最高OSR编译级别: %d\n", $counters->_highest_osr_comp_level
        
    else
        printf "   - 无方法计数器\n"
    end
    
    # 2. 编译状态
    printf "\n2. 编译状态:\n"
    printf "   - 可编译性: "
    if $method->is_not_compilable(CompLevel_simple)
        printf "C1不可编译 "
    else
        printf "C1可编译 "
    end
    
    if $method->is_not_compilable(CompLevel_full_optimization)
        printf "C2不可编译\n"
    else
        printf "C2可编译\n"
    end
    
    # 检查是否有编译版本
    printf "   - 编译版本检查:\n"
    set $code = $method->_code
    if $code != 0
        printf "     - 已编译: ✅ (地址: 0x%lx)\n", $code
        printf "     - 编译级别: %d\n", $code->_comp_level
        printf "     - 入口点: 0x%lx\n", $code->_entry_point
    else
        printf "     - 已编译: ❌ 仅解释执行\n"
    end
    
    # 3. 方法特征分析
    printf "\n3. 方法特征分析:\n"
    printf "   - 方法大小: %d 字节码\n", $method->_code_size
    printf "   - 访问标志: 0x%x\n", $method->_access_flags._flags
    
    # 方法类型
    printf "   - 方法类型: "
    if $method->is_static()
        printf "静态方法 "
    else
        printf "实例方法 "
    end
    
    if $method->is_synchronized()
        printf "同步方法 "
    end
    
    if $method->is_native()
        printf "本地方法 "
    end
    
    if $method->is_abstract()
        printf "抽象方法 "
    end
    
    if $method->is_final()
        printf "final方法 "
    end
    printf "\n"
    
    # 复杂度评估
    printf "   - 复杂度评估:\n"
    if $method->_code_size <= 35
        printf "     - 大小: ✅ 小方法 (适合内联)\n"
    elif $method->_code_size <= 325
        printf "     - 大小: ⚠️  中等方法\n"
    else
        printf "     - 大小: 🚨 大方法 (不适合内联)\n"
    end
    
    printf "     - 局部变量数: %d\n", $method->_max_locals
    printf "     - 最大栈深度: %d\n", $method->_max_stack
    
    # 4. 性能建议
    printf "\n4. 性能建议:\n"
    if $method->_code_size <= 35 && $invocation_count > 1000
        printf "   - 💡 建议: 方法适合内联优化\n"
    end
    
    if $backedge_count > $invocation_count * 10
        printf "   - 💡 建议: 方法包含热点循环，适合OSR编译\n"
    end
    
    if $method->_code_size > 1000
        printf "   - ⚠️  警告: 方法过大，可能影响编译性能\n"
    end
    
    printf "\n"
end

# 编译器状态分析
define analyze_compiler_state
    printf "=== 编译器状态分析 ===\n"
    
    # 1. 编译器配置
    printf "1. 编译器配置:\n"
    printf "   - 分层编译: %s\n", TieredCompilation ? "✅ 启用" : "❌ 禁用"
    printf "   - 编译阈值: %d\n", CompileThreshold
    printf "   - OSR百分比: %d%%\n", OnStackReplacePercentage
    printf "   - 内联大小限制: %d\n", MaxInlineSize
    printf "   - 频繁内联大小: %d\n", FreqInlineSize
    
    # 2. 编译线程状态
    printf "\n2. 编译线程状态:\n"
    if CompileBroker::_c1_compile_queue != 0
        printf "   - C1编译队列: 0x%lx\n", CompileBroker::_c1_compile_queue
        printf "   - C1队列长度: %d\n", CompileBroker::_c1_compile_queue->_size
    else
        printf "   - C1编译队列: 未初始化\n"
    end
    
    if CompileBroker::_c2_compile_queue != 0
        printf "   - C2编译队列: 0x%lx\n", CompileBroker::_c2_compile_queue
        printf "   - C2队列长度: %d\n", CompileBroker::_c2_compile_queue->_size
    else
        printf "   - C2编译队列: 未初始化\n"
    end
    
    # 3. 编译统计
    printf "\n3. 编译统计:\n"
    if CompileBroker::_perf_total_compilation != 0
        printf "   - 总编译时间: %lu ms\n", CompileBroker::_perf_total_compilation->value() / 1000000
    end
    
    if CompileBroker::_perf_total_compile_count != 0
        printf "   - 总编译次数: %lu\n", CompileBroker::_perf_total_compile_count->value()
    end
    
    if CompileBroker::_perf_total_bailout_count != 0
        printf "   - 编译失败次数: %lu\n", CompileBroker::_perf_total_bailout_count->value()
    end
    
    if CompileBroker::_perf_total_invalidated_count != 0
        printf "   - 编译失效次数: %lu\n", CompileBroker::_perf_total_invalidated_count->value()
    end
    
    # 4. CodeCache状态
    printf "\n4. CodeCache状态:\n"
    if CodeCache::_heap != 0
        set $heap = CodeCache::_heap
        printf "   - 代码缓存堆: 0x%lx\n", $heap
        printf "   - 缓存大小: %lu bytes (%.2f MB)\n", $heap->_memory._size, (double)$heap->_memory._size / 1048576
        printf "   - 已用大小: %lu bytes (%.2f MB)\n", $heap->_memory._top - $heap->_memory._bottom, (double)($heap->_memory._top - $heap->_memory._bottom) / 1048576
        
        set $usage_ratio = (double)($heap->_memory._top - $heap->_memory._bottom) / $heap->_memory._size
        printf "   - 使用率: %.1f%%\n", $usage_ratio * 100
        
        if $usage_ratio < 0.7
            printf "   - 状态: ✅ 健康\n"
        elif $usage_ratio < 0.9
            printf "   - 状态: ⚠️  注意\n"
        else
            printf "   - 状态: 🚨 接近满载\n"
        end
    else
        printf "   - CodeCache未初始化\n"
    end
    
    printf "\n"
end

# OSR机制分析
define analyze_osr_mechanism
    printf "=== OSR机制分析 ===\n"
    
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0
        printf "❌ 无法获取当前Java线程\n"
        return
    end
    
    # 1. OSR配置
    printf "1. OSR配置:\n"
    printf "   - OSR启用: %s\n", UseOnStackReplacement ? "✅ 启用" : "❌ 禁用"
    printf "   - OSR百分比: %d%%\n", OnStackReplacePercentage
    set $osr_threshold = OnStackReplacePercentage * CompileThreshold / 100
    printf "   - OSR阈值: %d\n", $osr_threshold
    
    # 2. 当前OSR状态
    printf "\n2. 当前OSR状态:\n"
    if $thread->_osr_nmethod_cache != 0
        set $osr_nm = $thread->_osr_nmethod_cache
        printf "   - OSR缓存: 0x%lx\n", $osr_nm
        
        if $osr_nm->_method != 0
            printf "   - OSR方法: %s.%s\n", 
                $osr_nm->_method->_method_holder->_name->_body,
                $osr_nm->_method->_name->_body
        end
        
        printf "   - OSR入口BCI: %d\n", $osr_nm->_osr_entry_bci
        printf "   - OSR入口点: 0x%lx\n", $osr_nm->_osr_entry_point
        printf "   - 编译级别: %d\n", $osr_nm->_comp_level
        
    else
        printf "   - OSR缓存: 空\n"
    end
    
    # 3. OSR候选分析
    printf "\n3. OSR候选分析:\n"
    if $thread->has_last_Java_frame()
        set $frame = $thread->last_Java_frame()
        if $frame.is_interpreted_frame()
            set $method = $frame.interpreter_frame_method()
            if $method != 0 && $method->_method_counters != 0
                set $counters = $method->_method_counters
                set $backedge_count = $counters->_backedge_counter._counter & 0x1FFFFFFF
                
                printf "   - 当前方法: %s.%s\n", 
                    $method->_method_holder->_name->_body,
                    $method->_name->_body
                printf "   - 回边计数: %d\n", $backedge_count
                printf "   - OSR阈值: %d\n", $osr_threshold
                
                if $backedge_count >= $osr_threshold
                    printf "   - OSR状态: ✅ 符合OSR条件\n"
                else
                    printf "   - OSR状态: ⏳ 未达OSR阈值 (%.1f%%)\n", (double)$backedge_count * 100 / $osr_threshold
                end
                
                # 检查是否有OSR编译
                set $bcp = $frame.interpreter_frame_bcp()
                set $bci = $bcp - $method->_code_base
                printf "   - 当前BCI: %d\n", $bci
                
                # 简化的OSR查找
                if $method->_code != 0
                    printf "   - 已有编译版本: ✅\n"
                else
                    printf "   - 已有编译版本: ❌\n"
                end
            end
        end
    end
    
    # 4. OSR性能统计
    printf "\n4. OSR性能统计:\n"
    # 这里需要访问具体的OSR统计数据，简化处理
    printf "   - OSR编译次数: 统计数据需要具体实现\n"
    printf "   - OSR成功率: 统计数据需要具体实现\n"
    printf "   - 平均OSR时间: 统计数据需要具体实现\n"
    
    printf "\n"
end

# 性能分析数据分析
define analyze_profiling_data
    printf "=== 性能分析数据分析 ===\n"
    
    set $thread = (JavaThread*)Thread::current()
    if $thread == 0 || !$thread->has_last_Java_frame()
        printf "❌ 无可用的Java栈帧\n"
        return
    end
    
    set $frame = $thread->last_Java_frame()
    if !$frame.is_interpreted_frame()
        printf "❌ 当前栈帧不是解释器栈帧\n"
        return
    end
    
    set $method = $frame.interpreter_frame_method()
    if $method == 0
        printf "❌ 无法获取当前方法\n"
        return
    end
    
    printf "方法: %s.%s%s\n", 
        $method->_method_holder->_name->_body,
        $method->_name->_body,
        $method->_signature->_body
    
    # 1. MethodData检查
    printf "\n1. MethodData检查:\n"
    if $method->_method_data != 0
        set $mdo = $method->_method_data
        printf "   - MethodData: 0x%lx\n", $mdo
        printf "   - 数据大小: %d bytes\n", $mdo->_data_size
        
        # 调用统计
        printf "   - 调用统计:\n"
        printf "     - 调用计数起始: %d\n", $mdo->_invocation_counter_start
        printf "     - 回边计数起始: %d\n", $mdo->_backedge_counter_start
        
        # 编译信息
        printf "   - 编译信息:\n"
        printf "     - 最高编译级别: %d\n", $mdo->_highest_comp_level
        printf "     - 最高OSR编译级别: %d\n", $mdo->_highest_osr_comp_level
        
        # 性能分析标志
        printf "   - 性能分析: %s\n", $mdo->_would_profile ? "✅ 启用" : "❌ 禁用"
        
    else
        printf "   - MethodData: 未创建\n"
    end
    
    # 2. 分支预测分析
    printf "\n2. 分支预测分析:\n"
    if $method->_method_data != 0
        printf "   - 分支数据: 需要遍历ProfileData结构\n"
        printf "   - 类型检查数据: 需要遍历ReceiverTypeData结构\n"
        printf "   - 调用数据: 需要遍历CallTypeData结构\n"
    else
        printf "   - 无性能分析数据\n"
    end
    
    # 3. 内联决策数据
    printf "\n3. 内联决策数据:\n"
    printf "   - 方法大小: %d bytes\n", $method->_code_size
    printf "   - 内联限制: %d bytes\n", MaxInlineSize
    printf "   - 频繁内联限制: %d bytes\n", FreqInlineSize
    
    if $method->_code_size <= FreqInlineSize
        printf "   - 内联建议: ✅ 适合频繁内联\n"
    elif $method->_code_size <= MaxInlineSize
        printf "   - 内联建议: ⚠️  可能内联\n"
    else
        printf "   - 内联建议: ❌ 不适合内联\n"
    end
    
    # 4. 去优化风险评估
    printf "\n4. 去优化风险评估:\n"
    if $method->is_not_compilable(CompLevel_full_optimization)
        printf "   - 编译风险: 🚨 高 (已标记为不可编译)\n"
    elif $method->_code_size > 1000
        printf "   - 编译风险: ⚠️  中 (方法较大)\n"
    else
        printf "   - 编译风险: ✅ 低\n"
    end
    
    printf "\n"
end

# 字节码分发性能分析
define analyze_bytecode_dispatch_performance
    printf "=== 字节码分发性能分析 ===\n"
    
    # 1. 分发表状态
    printf "1. 分发表状态:\n"
    set $dispatch_table_size = 0
    set $i = 0
    while $i < Bytecodes::number_of_codes
        if AbstractInterpreter::_bytecode_table[$i] != 0
            set $dispatch_table_size = $dispatch_table_size + 1
        end
        set $i = $i + 1
    end
    printf "   - 已生成分发入口: %d / %d\n", $dispatch_table_size, Bytecodes::number_of_codes
    printf "   - 分发表完整性: %.1f%%\n", (double)$dispatch_table_size * 100 / Bytecodes::number_of_codes
    
    # 2. 热点字节码分析
    printf "\n2. 热点字节码分析:\n"
    printf "   - 常用字节码入口点:\n"
    
    # 检查常用字节码的入口点
    set $common_bytecodes[0] = Bytecodes::_iload
    set $common_bytecodes[1] = Bytecodes::_istore
    set $common_bytecodes[2] = Bytecodes::_iadd
    set $common_bytecodes[3] = Bytecodes::_invokevirtual
    set $common_bytecodes[4] = Bytecodes::_invokespecial
    set $common_bytecodes[5] = Bytecodes::_invokestatic
    set $common_bytecodes[6] = Bytecodes::_getfield
    set $common_bytecodes[7] = Bytecodes::_putfield
    set $common_bytecodes[8] = Bytecodes::_if_icmpne
    set $common_bytecodes[9] = Bytecodes::_goto
    
    set $i = 0
    while $i < 10
        set $bc = $common_bytecodes[$i]
        set $entry = AbstractInterpreter::_bytecode_table[$bc]
        if $entry != 0
            printf "     %s: 0x%lx ✅\n", Bytecodes::name($bc), $entry
        else
            printf "     %s: 未生成 ❌\n", Bytecodes::name($bc)
        end
        set $i = $i + 1
    end
    
    # 3. 快速字节码优化
    printf "\n3. 快速字节码优化:\n"
    printf "   - 快速字节码范围: %d - %d\n", Bytecodes::number_of_codes, Bytecodes::number_of_java_codes
    printf "   - 优化字节码数量: %d\n", Bytecodes::number_of_java_codes - Bytecodes::number_of_codes
    
    # 检查一些快速字节码
    printf "   - 快速字节码状态:\n"
    printf "     fast_agetfield: %s\n", 
        AbstractInterpreter::_bytecode_table[Bytecodes::_fast_agetfield] != 0 ? "✅" : "❌"
    printf "     fast_igetfield: %s\n", 
        AbstractInterpreter::_bytecode_table[Bytecodes::_fast_igetfield] != 0 ? "✅" : "❌"
    printf "     fast_invokevfinal: %s\n", 
        AbstractInterpreter::_bytecode_table[Bytecodes::_fast_invokevfinal] != 0 ? "✅" : "❌"
    
    # 4. 分发性能评估
    printf "\n4. 分发性能评估:\n"
    if $dispatch_table_size >= Bytecodes::number_of_codes * 0.9
        printf "   - 分发表完整性: ⭐⭐⭐⭐⭐ 优秀\n"
    elif $dispatch_table_size >= Bytecodes::number_of_codes * 0.8
        printf "   - 分发表完整性: ⭐⭐⭐⭐ 良好\n"
    else
        printf "   - 分发表完整性: ⭐⭐⭐ 一般\n"
    end
    
    printf "\n"
end

# 完整的执行引擎健康检查
define execution_engine_health_check
    printf "========================================\n"
    printf "      字节码执行引擎健康检查报告\n"
    printf "========================================\n\n"
    
    analyze_execution_engine_architecture
    analyze_thread_execution_state
    analyze_stack_frames
    analyze_bytecode_execution
    analyze_method_invocation_performance
    analyze_compiler_state
    analyze_osr_mechanism
    analyze_profiling_data
    analyze_bytecode_dispatch_performance
    
    printf "========================================\n"
    printf "           健康检查完成\n"
    printf "========================================\n"
end

# 设置执行引擎相关断点
define set_execution_engine_breakpoints
    printf "设置执行引擎相关断点...\n"
    
    # 解释器核心断点
    break TemplateInterpreter::initialize
    break AbstractInterpreter::entry_for_method
    break InterpreterRuntime::frequency_counter_overflow
    
    # 方法调用断点
    break JavaCalls::call_static
    break JavaCalls::call_virtual
    break JavaCalls::call_interface
    
    # 字节码执行断点
    break TemplateTable::initialize
    break InterpreterGenerator::generate_method_entry
    
    # OSR相关断点
    break Deoptimization::compile_for_osr
    break InterpreterRuntime::frequency_counter_overflow_inner
    
    # 编译器断点
    break CompileBroker::compile_method
    break CompileBroker::invoke_compiler_on_method
    
    # 性能分析断点
    break MethodData::initialize
    break InterpreterRuntime::profile_method
    
    printf "执行引擎断点设置完成\n"
end

# 移除执行引擎断点
define clear_execution_engine_breakpoints
    printf "清除执行引擎相关断点...\n"
    
    clear TemplateInterpreter::initialize
    clear AbstractInterpreter::entry_for_method
    clear InterpreterRuntime::frequency_counter_overflow
    clear JavaCalls::call_static
    clear JavaCalls::call_virtual
    clear JavaCalls::call_interface
    clear TemplateTable::initialize
    clear InterpreterGenerator::generate_method_entry
    clear Deoptimization::compile_for_osr
    clear InterpreterRuntime::frequency_counter_overflow_inner
    clear CompileBroker::compile_method
    clear CompileBroker::invoke_compiler_on_method
    clear MethodData::initialize
    clear InterpreterRuntime::profile_method
    
    printf "执行引擎断点清除完成\n"
end

# 监控特定方法的执行
define monitor_method_execution
    if $argc != 2
        printf "用法: monitor_method_execution <类名> <方法名>\n"
        printf "示例: monitor_method_execution \"java/lang/String\" \"length\"\n"
    else
        printf "开始监控方法执行: %s.%s\n", $arg0, $arg1
        
        # 设置条件断点
        break JavaCalls::call_static if method->_name != 0 && strcmp(method->_name->_body, $arg1) == 0
        break JavaCalls::call_virtual if method->_name != 0 && strcmp(method->_name->_body, $arg1) == 0
        
        printf "监控断点已设置，继续执行以观察方法调用\n"
    end
end

# 执行引擎性能基准测试
define execution_engine_benchmark
    printf "=== 执行引擎性能基准测试 ===\n"
    
    # 记录开始状态
    set $start_time = os::javaTimeNanos()
    printf "基准测试开始时间: %lu ns\n", $start_time
    
    # 获取当前编译统计
    if CompileBroker::_perf_total_compile_count != 0
        set $start_compiles = CompileBroker::_perf_total_compile_count->value()
        printf "基准测试开始时编译次数: %lu\n", $start_compiles
    else
        set $start_compiles = 0
    end
    
    printf "\n请运行一些方法调用操作，然后调用 execution_engine_benchmark_result\n"
end

define execution_engine_benchmark_result
    printf "=== 执行引擎性能基准测试结果 ===\n"
    
    # 记录结束状态
    set $end_time = os::javaTimeNanos()
    printf "基准测试结束时间: %lu ns\n", $end_time
    
    if CompileBroker::_perf_total_compile_count != 0
        set $end_compiles = CompileBroker::_perf_total_compile_count->value()
        printf "基准测试结束时编译次数: %lu\n", $end_compiles
    else
        set $end_compiles = 0
    end
    
    # 计算性能指标
    set $elapsed_time = $end_time - $start_time
    set $new_compiles = $end_compiles - $start_compiles
    
    printf "\n性能统计:\n"
    printf "- 测试时长: %lu ns (%.2f ms)\n", $elapsed_time, (double)$elapsed_time / 1000000
    printf "- 新编译次数: %lu\n", $new_compiles
    
    if $new_compiles > 0
        set $avg_compile_time = $elapsed_time / $new_compiles
        printf "- 平均编译时间: %lu ns (%.2f ms)\n", $avg_compile_time, (double)$avg_compile_time / 1000000
        printf "- 编译速率: %.2f 编译/秒\n", (double)$new_compiles * 1000000000 / $elapsed_time
    end
    
    printf "\n"
end

# 初始化脚本
printf "字节码执行引擎深度分析GDB脚本已加载 - 深度增强版\n"
printf "========================================\n"
printf "可用命令:\n"
printf "  execution_engine_health_check         - 完整健康检查(150+数据点)\n"
printf "  analyze_execution_engine_architecture - 分析执行引擎架构\n"
printf "  analyze_thread_execution_state        - 分析线程执行状态\n"
printf "  analyze_stack_frames                  - 分析栈帧结构\n"
printf "  analyze_bytecode_execution            - 分析字节码执行\n"
printf "  analyze_method_invocation_performance - 分析方法调用性能\n"
printf "  analyze_compiler_state                - 分析编译器状态\n"
printf "  analyze_osr_mechanism                 - 分析OSR机制\n"
printf "  analyze_profiling_data                - 分析性能分析数据\n"
printf "  analyze_bytecode_dispatch_performance - 分析字节码分发性能\n"
printf "  set_execution_engine_breakpoints      - 设置调试断点\n"
printf "  monitor_method_execution <class> <method> - 监控特定方法执行\n"
printf "  execution_engine_benchmark            - 性能基准测试开始\n"
printf "  execution_engine_benchmark_result     - 性能基准测试结果\n"
printf "========================================\n"
printf "准备就绪，可以开始执行引擎深度分析！\n"