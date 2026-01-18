# 第04章：字节码执行引擎 - GDB调试脚本
# 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 配置

# 设置输出日志
set logging file logs/chapter_04_execution_engine.log
set logging on

# 基础设置
set confirm off
set pagination off
set print pretty on
set print array on

echo ========================================\n
echo 第04章：字节码执行引擎 GDB调试验证\n
echo 配置: -Xms=Xmx=8GB, G1GC\n
echo ========================================\n

# ============================================
# 1. 模板解释器验证
# ============================================

# 解释器初始化
break TemplateInterpreter::initialize
commands
  silent
  printf "\n=== 模板解释器初始化 ===\n"
  printf "代码生成开始\n"
  continue
end

# 方法入口生成
break InterpreterGenerator::generate_method_entry
commands
  silent
  printf "\n=== 方法入口生成 ===\n"
  printf "方法类型: %d\n", kind
  continue
end

# 字节码模板生成
break TemplateTable::initialize
commands
  silent
  printf "\n=== 字节码模板初始化 ===\n"
  printf "生成字节码处理模板\n"
  continue
end

# 字节码分发
break InterpreterMacroAssembler::dispatch_next
commands
  silent
  printf "\n=== 字节码分发 ===\n"
  printf "TOS状态: %d\n", state
  printf "步长: %d\n", step
  continue
end

# 具体字节码处理 - iadd指令
break TemplateTable::iadd
commands
  silent
  printf "\n=== iadd指令执行 ===\n"
  printf "整数加法操作\n"
  continue
end

# 方法调用指令
break TemplateTable::invokevirtual
commands
  silent
  printf "\n=== invokevirtual指令 ===\n"
  printf "虚方法调用\n"
  continue
end

# 栈帧创建
break frame::interpreter_frame_method
commands
  silent
  printf "\n=== 解释器栈帧 ===\n"
  printf "方法: %s\n", this->interpreter_frame_method()->name()->as_C_string()
  printf "字节码指针: %p\n", this->interpreter_frame_bcp()
  continue
end

# ============================================
# 2. JIT编译器验证
# ============================================

# 编译决策
break SimpleThresholdPolicy::call_event
commands
  silent
  printf "\n=== 编译决策：方法调用 ===\n"
  printf "方法: %s\n", method->name()->as_C_string()
  printf "当前级别: %d\n", cur_level
  printf "调用计数: %d\n", method->invocation_count()
  continue
end

break SimpleThresholdPolicy::loop_event
commands
  silent
  printf "\n=== 编译决策：循环回边 ===\n"
  printf "方法: %s\n", method->name()->as_C_string()
  printf "当前级别: %d\n", cur_level
  printf "回边计数: %d\n", method->backedge_count()
  continue
end

# 编译任务提交
break CompileBroker::compile_method_base
commands
  silent
  printf "\n=== 编译任务提交 ===\n"
  printf "方法: %s\n", method->name()->as_C_string()
  printf "OSR BCI: %d\n", osr_bci
  printf "编译级别: %d\n", comp_level
  printf "热点计数: %d\n", hot_count
  printf "注释: %s\n", comment ? comment : "NULL"
  continue
end

# C1编译器
break Compilation::compile_method
commands
  silent
  printf "\n=== C1编译器 ===\n"
  printf "编译方法: %s\n", method()->name()->as_C_string()
  printf "OSR BCI: %d\n", osr_bci()
  continue
end

# HIR构建
break GraphBuilder::iterate_bytecodes_for_block
commands
  silent
  printf "\n=== HIR构建 ===\n"
  printf "字节码索引: %d\n", bci
  continue
end

# C2编译器
break Compile::Compile_wrapper
commands
  silent
  printf "\n=== C2编译器 ===\n"
  printf "目标方法: %s\n", target->name()->as_C_string()
  printf "OSR BCI: %d\n", osr_bci
  continue
end

# C2优化阶段
break Compile::Optimize
commands
  silent
  printf "\n=== C2优化阶段 ===\n"
  printf "开始优化处理\n"
  continue
end

# ============================================
# 3. OSR机制验证
# ============================================

# OSR触发
break InterpreterRuntime::frequency_counter_overflow_inner
commands
  silent
  printf "\n=== OSR触发检查 ===\n"
  printf "分支字节码: %p\n", branch_bcp
  set $method = thread->last_frame().interpreter_frame_method()
  printf "方法: %s\n", $method->name()->as_C_string()
  printf "调用计数: %d\n", $method->invocation_count()
  continue
end

# OSR编译任务
break CompileBroker::compile_method if osr_bci != -1
commands
  silent
  printf "\n=== OSR编译任务 ===\n"
  printf "方法: %s\n", method->name()->as_C_string()
  printf "OSR字节码索引: %d\n", osr_bci
  continue
end

# 去优化
break Deoptimization::uncommon_trap_inner
commands
  silent
  printf "\n=== 去优化事件 ===\n"
  set $reason = Deoptimization::trap_request_reason(trap_request)
  set $action = Deoptimization::trap_request_action(trap_request)
  printf "去优化原因: %d\n", $reason
  printf "去优化动作: %d\n", $action
  continue
end

# ============================================
# 4. CodeCache管理验证
# ============================================

# CodeCache初始化
break CodeCache::initialize
commands
  silent
  printf "\n=== CodeCache初始化 ===\n"
  printf "初始化代码缓存\n"
  continue
end

# 代码分配
break CodeCache::allocate
commands
  silent
  printf "\n=== CodeCache分配 ===\n"
  printf "分配大小: %d bytes\n", size
  printf "代码类型: %d\n", code_blob_type
  continue
end

# nmethod创建
break nmethod::nmethod
commands
  silent
  printf "\n=== nmethod创建 ===\n"
  printf "方法: %s\n", method->name()->as_C_string()
  printf "编译级别: %d\n", comp_level
  continue
end

# nmethod状态变更
break nmethod::make_not_entrant_or_zombie
commands
  silent
  printf "\n=== nmethod状态变更 ===\n"
  printf "方法: %s\n", method()->name()->as_C_string()
  printf "新状态: %d\n", state
  continue
end

# ============================================
# 5. 自定义GDB命令
# ============================================

# 显示当前字节码
define show_current_bytecode
  if $argc == 0
    set $frame = (frame*)&thread->_anchor
  else
    set $frame = (frame*)$arg0
  end
  
  if $frame->is_interpreted_frame()
    set $method = $frame->interpreter_frame_method()
    set $bcp = $frame->interpreter_frame_bcp()
    set $bc = *$bcp
    
    printf "\n=== 当前字节码信息 ===\n"
    printf "方法: %s\n", $method->name()->as_C_string()
    printf "字节码: 0x%02x\n", $bc
    printf "字节码指针: %p\n", $bcp
    printf "========================\n"
  else
    printf "当前帧不是解释器帧\n"
  end
end

# 显示编译统计
define show_compilation_stats
  printf "\n=== 编译统计信息 ===\n"
  printf "编译任务总数: %d\n", CompileBroker::_total_compile_count
  printf "编译成功数: %d\n", CompileBroker::_total_successful_compile_count
  printf "编译失败数: %d\n", CompileBroker::_total_bailout_count
  printf "无效编译数: %d\n", CompileBroker::_total_invalidated_count
  printf "===================\n"
end

# 显示CodeCache使用情况
define show_codecache_usage
  printf "\n=== CodeCache使用情况 ===\n"
  printf "代码块总数: %ld\n", CodeCache::_number_of_blobs
  printf "nmethod数量: %ld\n", CodeCache::_number_of_nmethods
  printf "适配器数量: %ld\n", CodeCache::_number_of_adapters
  
  # 遍历代码堆
  set $i = 0
  while $i < CodeBlobType::NumTypes
    if CodeCache::_heaps[$i] != 0
      set $heap = CodeCache::_heaps[$i]
      printf "代码堆[%d]: %s\n", $i, $heap->name()
      printf "  已使用: %ld bytes\n", $heap->allocated_capacity()
      printf "  总容量: %ld bytes\n", $heap->capacity()
    end
    set $i = $i + 1
  end
  printf "========================\n"
end

# 显示方法编译信息
define show_method_compilation_info
  if $argc != 1
    printf "用法: show_method_compilation_info <method_ptr>\n"
  else
    set $method = (Method*)$arg0
    printf "\n=== 方法编译信息 ===\n"
    printf "方法名: %s\n", $method->name()->as_C_string()
    printf "调用计数: %d\n", $method->invocation_count()
    printf "回边计数: %d\n", $method->backedge_count()
    printf "编译级别: %d\n", $method->highest_comp_level()
    
    if $method->code() != 0
      printf "已编译代码: %p\n", $method->code()
      printf "代码大小: %d bytes\n", $method->code()->insts_size()
    else
      printf "未编译\n"
    end
    printf "==================\n"
  end
end

# 监控字节码执行
define monitor_bytecode_execution
  printf "\n开始监控字节码执行...\n"
  
  # 设置计数器
  set $bytecode_count = 0
  set $method_call_count = 0
  
  printf "监控已启动，使用 'continue' 继续执行\n"
end

# 监控编译活动
define monitor_compilation_activity
  printf "\n开始监控编译活动...\n"
  
  # 显示初始状态
  show_compilation_stats
  show_codecache_usage
  
  printf "监控已启动，使用 'continue' 继续执行\n"
end

# 分析性能热点
define analyze_hotspots
  printf "\n=== 性能热点分析 ===\n"
  
  # 这里可以添加热点方法识别逻辑
  printf "分析编译方法的调用频率...\n"
  
  # 遍历已编译方法
  printf "热点方法列表:\n"
  # 实际实现需要遍历CodeCache中的nmethod
  
  printf "=====================\n"
end

# ============================================
# 6. 启动监控
# ============================================

echo \n开始字节码执行引擎验证...\n
echo 使用以下命令进行交互式调试:\n
echo - show_current_bytecode: 显示当前字节码\n
echo - show_compilation_stats: 显示编译统计\n
echo - show_codecache_usage: 显示CodeCache使用情况\n
echo - show_method_compilation_info <method>: 显示方法编译信息\n
echo - monitor_bytecode_execution: 监控字节码执行\n
echo - monitor_compilation_activity: 监控编译活动\n
echo - analyze_hotspots: 分析性能热点\n
echo \n

# 运行程序
run

# 程序结束后显示最终统计
echo \n========================================\n
echo 字节码执行引擎验证完成\n
echo ========================================\n

show_compilation_stats
show_codecache_usage

# 关闭日志
set logging off

quit