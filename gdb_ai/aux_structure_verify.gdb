# G1辅助数据结构内存验证GDB脚本
# 验证CardTable, BOT, CMBitMap等数据结构的精确大小和地址

set pagination off
set print pretty on

# 设置日志
set logging file /data/workspace/openjdk11-core/gdb_ai/aux_structure_output.txt
set logging overwrite on
set logging enabled on

echo ========================================\n
echo G1辅助数据结构内存验证\n
echo ========================================\n

# 在G1CollectedHeap::initialize中BOT创建后
break g1CollectedHeap.cpp:1775
commands
  silent
  echo \n=== G1辅助数据结构映射器创建 ===\n
  continue
end

# 在G1CardTable构造函数
break G1CardTable::G1CardTable
commands
  silent
  echo \n=== [断点] G1CardTable构造 ===\n
  continue
end

# 在G1ConcurrentMark构造函数
break G1ConcurrentMark::G1ConcurrentMark
commands
  silent
  echo \n=== [断点] G1ConcurrentMark构造 ===\n
  continue
end

run -Xms8g -Xmx8g -XX:+UseG1GC -version

echo \n========================================\n
echo 辅助结构验证完成\n
echo ========================================\n

set logging enabled off
quit
