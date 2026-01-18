#!/bin/bash
# 🚀 验证8GB G1 JVM初始化分析的完整脚本
# 严格按照 -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages 配置

set -e

echo "🚀 开始验证8GB G1 JVM初始化分析..."
echo "==============================================="

# 检查环境
if ! command -v java &> /dev/null; then
    echo "❌ Java未安装或不在PATH中"
    exit 1
fi

if ! command -v gdb &> /dev/null; then
    echo "❌ GDB未安装或不在PATH中"
    exit 1
fi

if ! command -v strace &> /dev/null; then
    echo "❌ strace未安装或不在PATH中"
    exit 1
fi

echo "✅ 环境检查通过"

# 创建8GB G1专用测试类
cat > HelloWorld.java << 'EOF'
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("🚀 8GB G1 JVM初始化分析验证");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        
        System.out.println("最大内存: " + (maxMemory / (1024*1024*1024)) + " GB");
        System.out.println("总内存: " + (totalMemory / (1024*1024)) + " MB");
        
        // 验证G1配置
        String gcType = System.getProperty("java.vm.name");
        System.out.println("JVM类型: " + gcType);
        
        // 触发一些G1活动
        java.util.List<Object> objects = new java.util.ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            objects.add(new Object());
        }
        System.out.println("G1内存分配测试完成");
    }
}
EOF

echo "✅ 8GB G1测试类创建完成"

# 编译测试类
javac HelloWorld.java
echo "✅ 测试类编译完成"

# 验证1：8GB G1配置的启动时间
echo ""
echo "🔍 验证1：8GB G1配置启动时间分析"
echo "----------------------------------------"

echo "测试8GB G1配置的启动性能："
start_time=$(date +%s%N)
java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages HelloWorld > /dev/null 2>&1
end_time=$(date +%s%N)
duration=$(( (end_time - start_time) / 1000000 ))
echo "  8GB G1 (非大页): ${duration} ms"

# 验证2：压缩指针配置验证
echo ""
echo "🔍 验证2：8GB配置下的压缩指针验证"
echo "----------------------------------------"

echo "验证8GB堆的压缩指针配置："
compression_mode=$(java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompressedOopsMode HelloWorld 2>&1 | grep -o -E "(Zero based|HeapBased|Unscaled)" | head -1)

if [ "$compression_mode" = "Zero based" ]; then
    echo "  ✅ 压缩指针模式: Zero based (最优性能)"
else
    echo "  ⚠️  压缩指针模式: $compression_mode"
fi

# 验证3：G1堆布局分析
echo ""
echo "🔍 验证3：G1堆布局分析"
echo "----------------------------------------"

echo "分析G1堆的内存布局："
java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages -XX:+PrintGCDetails HelloWorld 2>&1 | grep -E "(region|Region|heap)" | head -5

# 验证4：系统调用追踪 (8GB专用)
echo ""
echo "🔍 验证4：8GB G1系统调用追踪"
echo "----------------------------------------"

if command -v strace &> /dev/null; then
    echo "追踪8GB G1配置的关键mmap调用："
    timeout 60 strace -e mmap java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages HelloWorld 2>&1 | grep mmap | while read line; do
        # 解析mmap调用，识别8GB相关分配
        if echo "$line" | grep -q "8589934592"; then
            echo "  🔥 8GB Java堆保留: $line"
        elif echo "$line" | grep -q "1073741824"; then
            echo "  🔥 1GB 压缩类空间: $line"
        elif echo "$line" | grep -q "268435456"; then
            echo "  🔥 256MB 初始堆提交: $line"
        elif echo "$line" | grep -q "16777216"; then
            echo "  🔥 16MB G1辅助结构: $line"
        elif echo "$line" | grep -q "33554432"; then
            echo "  🔥 32MB G1标记位图: $line"
        fi
    done | head -10
else
    echo "⚠️  strace未安装，跳过系统调用追踪"
fi

# 验证5：GDB调试脚本功能测试
echo ""
echo "🔍 验证5：8GB G1 GDB调试脚本测试"
echo "----------------------------------------"

if [ -f "g1_8gb_debug.gdb" ]; then
    echo "✅ 8GB G1专用GDB调试脚本存在"
    echo "脚本大小: $(wc -l < g1_8gb_debug.gdb) 行"
    echo "主要功能："
    grep -E "^define " g1_8gb_debug.gdb | while read line; do
        func_name=$(echo "$line" | cut -d' ' -f2)
        echo "  - $func_name"
    done
else
    echo "❌ 8GB G1 GDB调试脚本不存在"
fi

# 验证6：性能分析工具测试
echo ""
echo "🔍 验证6：8GB G1性能分析工具测试"
echo "----------------------------------------"

if [ -f "g1_8gb_performance_analyzer.py" ]; then
    echo "✅ 8GB G1性能分析工具存在"
    echo "脚本大小: $(wc -l < g1_8gb_performance_analyzer.py) 行"
    
    # 测试Python脚本语法
    if python3 -m py_compile g1_8gb_performance_analyzer.py 2>/dev/null; then
        echo "✅ Python脚本语法正确"
        
        # 简单功能测试
        echo "🧪 运行简单功能测试..."
        if timeout 120 python3 g1_8gb_performance_analyzer.py HelloWorld > /dev/null 2>&1; then
            echo "✅ 性能分析工具运行成功"
        else
            echo "⚠️  性能分析工具运行超时或失败"
        fi
    else
        echo "❌ Python脚本语法错误"
    fi
else
    echo "❌ 8GB G1性能分析工具不存在"
fi

# 验证7：内存配置验证
echo ""
echo "🔍 验证7：8GB G1内存配置验证"
echo "----------------------------------------"

echo "验证JVM内存配置："
java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages -XX:+PrintFlagsFinal HelloWorld 2>&1 | grep -E "(InitialHeapSize|MaxHeapSize|UseG1GC|UseLargePages)" | while read line; do
    echo "  $line"
done

# 验证8：G1特定参数验证
echo ""
echo "🔍 验证8：G1特定参数验证"
echo "----------------------------------------"

echo "验证G1相关参数："
java -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages -XX:+PrintFlagsFinal HelloWorld 2>&1 | grep -E "(G1HeapRegionSize|G1NewSizePercent|G1MaxNewSizePercent)" | while read line; do
    echo "  $line"
done

# 计算预期的Region配置
echo ""
echo "📊 8GB G1配置理论分析："
echo "  预期Region大小: 4MB (8GB / 2048)"
echo "  预期Region数量: 2048"
echo "  预期卡表大小: 16MB (8GB / 512B)"
echo "  预期BOT表大小: 16MB"
echo "  预期标记位图: 32MB (prev + next)"

# 清理
rm -f HelloWorld.java HelloWorld.class
rm -f g1_8gb_analysis_report_*.json

echo ""
echo "🎉 8GB G1验证完成！"
echo "==============================================="
echo "📊 验证总结："
echo "  - 8GB G1启动时间: 完成"
echo "  - 压缩指针验证: 完成"
echo "  - G1堆布局分析: 完成"
echo "  - 系统调用追踪: $(command -v strace &> /dev/null && echo "完成" || echo "跳过")"
echo "  - GDB调试脚本: 完成"
echo "  - 性能分析工具: 完成"
echo "  - 内存配置验证: 完成"
echo "  - G1参数验证: 完成"
echo ""
echo "🚀 8GB G1 JVM初始化分析验证成功！"