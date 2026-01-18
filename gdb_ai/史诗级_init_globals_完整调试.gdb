# 史诗级 init_globals() 完整调试脚本
# 目标：获得每个初始化函数的详细执行信息

set pagination off
set logging file /data/workspace/openjdk11-core/md/史诗级_init_globals_调试日志.log
set logging on

# 设置断点在关键函数
break init_globals
break management_init
break bytecodes_init
break classLoader_init1
break compilationPolicy_init
break codeCache_init
break universe_init
break interpreter_init
break javaClasses_init
break universe_post_init

# 启动程序
run -version

# init_globals 函数分析
echo ========================================\n
echo 🔥 INIT_GLOBALS 函数完整分析 🔥\n
echo ========================================\n

continue

# 获取函数地址和参数
echo \n=== INIT_GLOBALS 函数信息 ===\n
info registers
print $pc
x/20i $pc
echo \n

# 继续到 management_init
continue
echo \n=== MANAGEMENT_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
backtrace 5
echo \n

# 继续到 bytecodes_init  
continue
echo \n=== BYTECODES_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
backtrace 5
echo \n

# 继续到 classLoader_init1
continue
echo \n=== CLASSLOADER_INIT1 函数分析 ===\n
info registers
print $pc
x/10i $pc
backtrace 5
echo \n

# 继续到 compilationPolicy_init
continue
echo \n=== COMPILATIONPOLICY_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
backtrace 5
echo \n

# 继续到 codeCache_init
continue
echo \n=== CODECACHE_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
backtrace 5
echo \n

# 最重要的 universe_init
continue
echo \n=== 🌟 UNIVERSE_INIT 函数分析 🌟 ===\n
info registers
print $pc
x/20i $pc
backtrace 10

# 分析Universe类的静态成员
echo \n=== Universe类静态成员分析 ===\n
print &Universe::_collectedHeap
print &Universe::_heap_base
print &Universe::_narrow_oop_base
print &Universe::_narrow_oop_shift
print &Universe::_narrow_klass_base
print &Universe::_narrow_klass_shift
echo \n

# 继续到其他重要函数
continue
echo \n=== INTERPRETER_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
echo \n

continue
echo \n=== JAVACLASSES_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
echo \n

continue
echo \n=== UNIVERSE_POST_INIT 函数分析 ===\n
info registers
print $pc
x/10i $pc
echo \n

echo ========================================\n
echo 🎉 INIT_GLOBALS 完整调试完成 🎉\n
echo ========================================\n

quit