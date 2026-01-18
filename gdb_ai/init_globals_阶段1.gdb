set pagination off
set logging file /data/workspace/openjdk11-core/md/init_globals_阶段1_日志.log
set logging on

break init_globals
run -version

echo ========================================\n
echo 🔥 INIT_GLOBALS 阶段1分析 🔥\n
echo ========================================\n

continue

echo \n=== INIT_GLOBALS 函数入口分析 ===\n
info registers
print $pc
x/30i $pc
echo \n

echo \n=== 调用栈分析 ===\n
backtrace 10
echo \n

echo \n=== 内存布局分析 ===\n
info proc mappings
echo \n

quit