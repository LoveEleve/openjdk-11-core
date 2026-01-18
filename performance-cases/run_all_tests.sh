#!/bin/bash

# 真实Java性能问题排查实战 - 测试运行脚本
# 基于OpenJDK11源码的性能问题复现和排查

echo "=========================================="
echo "真实Java性能问题排查实战测试套件"
echo "基于OpenJDK11源码 - 8GB堆内存 G1GC"
echo "=========================================="

# JVM参数配置
JVM_OPTS="-Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps"

# 创建日志目录
LOG_DIR="logs/$(date +%Y%m%d_%H%M%S)"
mkdir -p $LOG_DIR

echo "日志将保存到: $LOG_DIR"
echo ""

# 编译所有测试类
echo "编译测试类..."
javac *.java
if [ $? -ne 0 ]; then
    echo "编译失败，请检查代码"
    exit 1
fi
echo "编译完成"
echo ""

# 测试1: HashMap哈希冲突性能问题
echo "1. 运行HashMap哈希冲突性能测试..."
echo "   预期现象: CPU使用率飙升，响应时间增长"
timeout 30s java $JVM_OPTS HashMapPerformanceTest > $LOG_DIR/hashmap_test.log 2>&1 &
HASHMAP_PID=$!
echo "   HashMap测试启动 (PID: $HASHMAP_PID)"

# 测试2: G1GC性能问题
echo "2. 运行G1GC混合回收性能测试..."
echo "   预期现象: Mixed GC暂停时间超标"
timeout 30s java $JVM_OPTS -XX:G1HeapRegionSize=32m -Xloggc:$LOG_DIR/g1gc.log G1GCStressTest > $LOG_DIR/g1gc_test.log 2>&1 &
G1GC_PID=$!
echo "   G1GC测试启动 (PID: $G1GC_PID)"

# 测试3: 并发锁竞争问题
echo "3. 运行并发锁竞争性能测试..."
echo "   预期现象: CPU使用率低但响应慢"
timeout 30s java $JVM_OPTS ConcurrencyBottleneckTest > $LOG_DIR/concurrency_test.log 2>&1 &
CONCURRENCY_PID=$!
echo "   并发测试启动 (PID: $CONCURRENCY_PID)"

# 测试4: DirectByteBuffer内存泄漏
echo "4. 运行DirectByteBuffer内存泄漏测试..."
echo "   预期现象: 直接内存持续增长，最终OOM"
timeout 30s java $JVM_OPTS -XX:MaxDirectMemorySize=2g DirectBufferLeakTest > $LOG_DIR/directbuffer_test.log 2>&1 &
DIRECTBUFFER_PID=$!
echo "   DirectBuffer测试启动 (PID: $DIRECTBUFFER_PID)"

# 测试5: ClassLoader内存泄漏
echo "5. 运行ClassLoader内存泄漏测试..."
echo "   预期现象: Metaspace内存持续增长"
timeout 30s java $JVM_OPTS -XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=1g ClassLoaderLeakTest > $LOG_DIR/classloader_test.log 2>&1 &
CLASSLOADER_PID=$!
echo "   ClassLoader测试启动 (PID: $CLASSLOADER_PID)"

# 测试6: NIO Selector性能瓶颈
echo "6. 运行NIO Selector性能瓶颈测试..."
echo "   预期现象: 高并发时性能急剧下降"
timeout 30s java $JVM_OPTS NIOSelectorBottleneckTest > $LOG_DIR/nio_test.log 2>&1 &
NIO_PID=$!
echo "   NIO测试启动 (PID: $NIO_PID)"

# 测试7: 字符串性能陷阱
echo "7. 运行字符串性能陷阱测试..."
echo "   预期现象: CPU使用率高，大量字符串操作"
timeout 30s java $JVM_OPTS -XX:+UseStringDeduplication StringPerformanceTest > $LOG_DIR/string_test.log 2>&1 &
STRING_PID=$!
echo "   字符串测试启动 (PID: $STRING_PID)"

echo ""
echo "所有测试已启动，等待30秒完成..."
echo "可以使用以下命令监控测试进程:"
echo "  jps -v | grep java"
echo "  top -p $HASHMAP_PID,$G1GC_PID,$CONCURRENCY_PID,$DIRECTBUFFER_PID,$CLASSLOADER_PID,$NIO_PID,$STRING_PID"
echo ""

# 等待所有测试完成
wait

echo ""
echo "=========================================="
echo "所有测试完成！"
echo "=========================================="
echo ""
echo "测试结果分析:"
echo "1. 查看日志文件: ls -la $LOG_DIR/"
echo "2. 分析GC日志: $LOG_DIR/g1gc.log"
echo "3. 查看测试输出: cat $LOG_DIR/*.log"
echo ""
echo "性能问题排查要点:"
echo "- HashMap测试: 观察CPU使用率和响应时间变化"
echo "- G1GC测试: 分析Mixed GC暂停时间和频率"
echo "- 并发测试: 关注线程状态和锁竞争情况"
echo "- DirectBuffer测试: 监控直接内存使用情况"
echo "- ClassLoader测试: 观察Metaspace内存增长"
echo "- NIO测试: 分析高并发下的性能表现"
echo "- 字符串测试: 检查字符串操作的CPU消耗"
echo ""
echo "进一步分析建议:"
echo "1. 使用jstack分析线程状态"
echo "2. 使用jstat监控GC情况"
echo "3. 使用jmap分析内存使用"
echo "4. 使用Arthas进行实时诊断"
echo "5. 使用async-profiler生成火焰图"
echo ""
echo "🎯 这些测试基于OpenJDK11真实源码，展示了生产环境中的典型性能问题！"