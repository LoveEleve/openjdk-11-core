#!/bin/bash

# ASM字节码增强技术演示运行脚本
# 展示Arthas核心技术的实现原理

echo "=== ASM字节码增强技术演示 ==="
echo "展示Arthas核心技术的实现原理"
echo ""

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java环境，请确保Java 11+已安装"
    exit 1
fi

# 检查Maven环境
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到Maven环境，请确保Maven已安装"
    exit 1
fi

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "当前工作目录: $SCRIPT_DIR"
echo ""

# 清理之前的构建
echo "--- 清理之前的构建 ---"
mvn clean

if [ $? -ne 0 ]; then
    echo "错误: Maven清理失败"
    exit 1
fi

# 编译项目
echo ""
echo "--- 编译项目 ---"
mvn compile

if [ $? -ne 0 ]; then
    echo "错误: 项目编译失败"
    exit 1
fi

# 运行测试
echo ""
echo "--- 运行单元测试 ---"
mvn test

if [ $? -ne 0 ]; then
    echo "警告: 单元测试失败，但继续运行演示"
fi

# 打包项目
echo ""
echo "--- 打包项目 ---"
mvn package -DskipTests

if [ $? -ne 0 ]; then
    echo "错误: 项目打包失败"
    exit 1
fi

# 运行演示程序
echo ""
echo "--- 运行ASM字节码增强演示 ---"
echo "使用标准JVM参数运行..."

java -cp "target/classes:target/lib/*" \
     -Xms1g -Xmx1g \
     -XX:+UseG1GC \
     -XX:+PrintGC \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:+LogVMOutput \
     com.example.asm.demo.ASMEnhancementDemo

DEMO_EXIT_CODE=$?

echo ""
echo "--- 演示完成 ---"

if [ $DEMO_EXIT_CODE -eq 0 ]; then
    echo "✅ ASM字节码增强演示成功完成"
    echo ""
    echo "生成的文件:"
    echo "  - target/asm-enhancement-demo-1.0.0.jar (主程序JAR)"
    echo "  - target/lib/ (依赖库)"
    echo "  - *.class (增强后的类文件)"
    echo ""
    echo "下一步学习建议:"
    echo "  1. 查看生成的增强类文件"
    echo "  2. 分析字节码增强的效果"
    echo "  3. 尝试修改增强逻辑"
    echo "  4. 对比Arthas的实现方式"
else
    echo "❌ ASM字节码增强演示失败，退出码: $DEMO_EXIT_CODE"
    exit $DEMO_EXIT_CODE
fi

# 可选: 运行性能测试
read -p "是否运行性能测试? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    echo "--- 运行性能测试 ---"
    
    java -cp "target/classes:target/lib/*" \
         -Xms2g -Xmx2g \
         -XX:+UseG1GC \
         -XX:+PrintGC \
         -XX:+PrintGCDetails \
         -XX:+PrintGCTimeStamps \
         -XX:+UnlockDiagnosticVMOptions \
         -XX:+TraceClassLoading \
         com.example.asm.demo.ASMEnhancementDemo performance
    
    echo "性能测试完成"
fi

# 可选: 生成字节码分析报告
read -p "是否生成字节码分析报告? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    echo "--- 生成字节码分析报告 ---"
    
    # 使用javap分析增强后的类
    if [ -f "TestBusinessClass_Enhanced.class" ]; then
        echo "分析增强后的类文件..."
        javap -c -v TestBusinessClass_Enhanced.class > bytecode_analysis_report.txt
        echo "字节码分析报告已生成: bytecode_analysis_report.txt"
    else
        echo "未找到增强后的类文件"
    fi
fi

echo ""
echo "🎉 ASM字节码增强技术演示全部完成!"
echo "   现在您已经掌握了Arthas字节码增强的核心技术原理!"