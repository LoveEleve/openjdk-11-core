#!/bin/bash

# Arthas企业级服务器启动脚本
# 基于JVM标准配置：-Xms=8GB -Xmx=8GB，G1 GC，Region=4MB

echo "🚀 启动Arthas企业级服务器..."

# 检查Java版本
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{print $1}')
if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "❌ 错误: 需要Java 11或更高版本"
    exit 1
fi

# 创建日志目录
mkdir -p logs

# JVM参数配置
JVM_OPTS=""

# 内存配置（8GB堆内存）
JVM_OPTS="$JVM_OPTS -Xms8g"
JVM_OPTS="$JVM_OPTS -Xmx8g"

# G1 GC配置
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -XX:G1HeapRegionSize=4m"
JVM_OPTS="$JVM_OPTS -XX:MaxGCPauseMillis=200"
JVM_OPTS="$JVM_OPTS -XX:G1NewSizePercent=20"
JVM_OPTS="$JVM_OPTS -XX:G1MaxNewSizePercent=30"
JVM_OPTS="$JVM_OPTS -XX:InitiatingHeapOccupancyPercent=45"

# GC日志配置
JVM_OPTS="$JVM_OPTS -XX:+PrintGC"
JVM_OPTS="$JVM_OPTS -XX:+PrintGCDetails"
JVM_OPTS="$JVM_OPTS -XX:+PrintGCTimeStamps"
JVM_OPTS="$JVM_OPTS -XX:+PrintGCApplicationStoppedTime"
JVM_OPTS="$JVM_OPTS -Xloggc:logs/gc.log"
JVM_OPTS="$JVM_OPTS -XX:+UseGCLogFileRotation"
JVM_OPTS="$JVM_OPTS -XX:NumberOfGCLogFiles=5"
JVM_OPTS="$JVM_OPTS -XX:GCLogFileSize=100M"

# JVM调优参数
JVM_OPTS="$JVM_OPTS -XX:+UnlockExperimentalVMOptions"
JVM_OPTS="$JVM_OPTS -XX:+UseCGroupMemoryLimitForHeap"
JVM_OPTS="$JVM_OPTS -XX:+ExitOnOutOfMemoryError"
JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError"
JVM_OPTS="$JVM_OPTS -XX:HeapDumpPath=logs/"

# Netty优化参数
JVM_OPTS="$JVM_OPTS -Dio.netty.leakDetection.level=SIMPLE"
JVM_OPTS="$JVM_OPTS -Dio.netty.maxDirectMemory=2147483648"  # 2GB直接内存
JVM_OPTS="$JVM_OPTS -Dio.netty.allocator.numHeapArenas=2"
JVM_OPTS="$JVM_OPTS -Dio.netty.allocator.numDirectArenas=4"

# 系统属性
JVM_OPTS="$JVM_OPTS -Dfile.encoding=UTF-8"
JVM_OPTS="$JVM_OPTS -Djava.net.preferIPv4Stack=true"
JVM_OPTS="$JVM_OPTS -Djava.awt.headless=true"

# 调试模式（可选）
if [ "$1" = "debug" ]; then
    echo "🔍 启用调试模式..."
    JVM_OPTS="$JVM_OPTS -Darthas.debug=true"
    JVM_OPTS="$JVM_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
fi

# 性能分析模式（可选）
if [ "$1" = "profile" ]; then
    echo "📊 启用性能分析模式..."
    JVM_OPTS="$JVM_OPTS -XX:+FlightRecorder"
    JVM_OPTS="$JVM_OPTS -XX:StartFlightRecording=duration=60s,filename=logs/arthas-profile.jfr"
fi

# 端口配置
TCP_PORT=${TCP_PORT:-3658}
WEB_PORT=${WEB_PORT:-8563}

echo "📋 JVM配置:"
echo "  堆内存: 8GB"
echo "  GC算法: G1GC"
echo "  Region大小: 4MB"
echo "  直接内存: 2GB"
echo ""
echo "🌐 服务端口:"
echo "  TCP端口: $TCP_PORT"
echo "  Web端口: $WEB_PORT"
echo ""

# 构建JAR文件（如果不存在）
if [ ! -f "target/netty-enterprise-server-1.0.0.jar" ]; then
    echo "📦 构建项目..."
    mvn clean package -DskipTests
    if [ $? -ne 0 ]; then
        echo "❌ 构建失败"
        exit 1
    fi
fi

# 启动服务器
echo "🎯 启动服务器..."
java $JVM_OPTS -jar target/netty-enterprise-server-1.0.0.jar $TCP_PORT $WEB_PORT

echo "👋 服务器已停止"