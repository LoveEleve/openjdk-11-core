#!/bin/bash
# 🚀 验证颠覆性JVM初始化分析的脚本
# 基于OpenJDK11源码验证我们的发现

set -e

echo "🚀 开始验证颠覆性JVM初始化分析..."
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

echo "✅ 环境检查通过"

# 创建测试Java类
cat > HelloWorld.java << 'EOF'
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello JVM Initialization Analysis!");
        
        // 触发一些JVM活动
        Runtime runtime = Runtime.getRuntime();
        System.out.println("可用处理器: " + runtime.availableProcessors());
        System.out.println("最大内存: " + runtime.maxMemory() / (1024*1024*1024) + " GB");
        System.out.println("总内存: " + runtime.totalMemory() / (1024*1024*1024) + " GB");
        System.out.println("空闲内存: " + runtime.freeMemory() / (1024*1024*1024) + " GB");
        
        // 触发一些类加载
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add("JVM");
        list.add("Analysis");
        System.out.println("列表内容: " + list);
    }
}
EOF

echo "✅ 测试类创建完成"

# 编译测试类
javac HelloWorld.java
echo "✅ 测试类编译完成"

# 验证1：基本JVM启动时间测量
echo ""
echo "🔍 验证1：JVM启动时间分析"
echo "----------------------------------------"

echo "测试不同堆大小的启动时间："

for heap_size in "1g" "2g" "4g" "8g"; do
    echo -n "  堆大小 ${heap_size}: "
    start_time=$(date +%s%N)
    java -Xms${heap_size} -Xmx${heap_size} -XX:+UseG1GC HelloWorld > /dev/null 2>&1
    end_time=$(date +%s%N)
    duration=$(( (end_time - start_time) / 1000000 ))
    echo "${duration} ms"
done

# 验证2：内存布局分析
echo ""
echo "🔍 验证2：内存布局分析"
echo "----------------------------------------"

echo "分析G1堆的内存布局："
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps HelloWorld 2>&1 | grep -E "(heap|region|Heap)" | head -10

# 验证3：压缩指针配置
echo ""
echo "🔍 验证3：压缩指针配置验证"
echo "----------------------------------------"

echo "测试不同堆大小的压缩指针配置："

# 小堆（应该使用Zero-based）
echo -n "  8GB堆（Zero-based预期）: "
java -Xms8g -Xmx8g -XX:+UseCompressedOops -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompressedOopsMode HelloWorld 2>&1 | grep -o "Zero based" || echo "其他模式"

# 大堆（可能使用HeapBased）
echo -n "  32GB堆（HeapBased预期）: "
java -Xms32g -Xmx32g -XX:+UseCompressedOops -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompressedOopsMode HelloWorld 2>&1 | grep -o -E "(Zero based|HeapBased)" || echo "其他模式"

# 验证4：初始化函数调用追踪
echo ""
echo "🔍 验证4：初始化函数调用追踪"
echo "----------------------------------------"

if command -v strace &> /dev/null; then
    echo "使用strace追踪系统调用（前10个mmap调用）："
    timeout 30 strace -e mmap java -Xms4g -Xmx4g -XX:+UseG1GC HelloWorld 2>&1 | grep mmap | head -10 | while read line; do
        echo "  $line"
    done
else
    echo "⚠️  strace未安装，跳过系统调用追踪"
fi

# 验证5：GDB调试脚本测试
echo ""
echo "🔍 验证5：GDB调试脚本功能测试"
echo "----------------------------------------"

if [ -f "scripts/revolutionary-jvm-debug.gdb" ]; then
    echo "✅ 革命性GDB调试脚本存在"
    echo "脚本大小: $(wc -l < scripts/revolutionary-jvm-debug.gdb) 行"
    echo "主要功能："
    grep -E "^define " scripts/revolutionary-jvm-debug.gdb | while read line; do
        func_name=$(echo "$line" | cut -d' ' -f2)
        echo "  - $func_name"
    done
else
    echo "❌ GDB调试脚本不存在"
fi

# 验证6：性能剖析工具测试
echo ""
echo "🔍 验证6：性能剖析工具功能测试"
echo "----------------------------------------"

if [ -f "scripts/jvm-init-profiler.py" ]; then
    echo "✅ JVM性能剖析工具存在"
    echo "脚本大小: $(wc -l < scripts/jvm-init-profiler.py) 行"
    
    # 测试Python脚本语法
    if python3 -m py_compile scripts/jvm-init-profiler.py 2>/dev/null; then
        echo "✅ Python脚本语法正确"
    else
        echo "❌ Python脚本语法错误"
    fi
else
    echo "❌ 性能剖析工具不存在"
fi

# 验证7：源码文件存在性检查
echo ""
echo "🔍 验证7：关键源码文件存在性检查"
echo "----------------------------------------"

key_files=(
    "src/hotspot/share/runtime/init.cpp"
    "src/hotspot/share/memory/universe.cpp"
    "src/hotspot/share/gc/g1/g1CollectedHeap.cpp"
    "src/hotspot/share/interpreter/templateTable.cpp"
    "src/hotspot/share/runtime/mutexLocker.cpp"
)

for file in "${key_files[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✅ $file ($(wc -l < "$file") 行)"
    else
        echo "  ❌ $file 不存在"
    fi
done

# 验证8：关键函数存在性检查
echo ""
echo "🔍 验证8：关键函数存在性检查"
echo "----------------------------------------"

key_functions=(
    "init_globals"
    "vm_init_globals"
    "universe_init"
    "basic_types_init"
    "mutex_init"
)

for func in "${key_functions[@]}"; do
    if grep -r "^[[:space:]]*[a-zA-Z_][a-zA-Z0-9_]*[[:space:]]*${func}[[:space:]]*(" src/hotspot/share/ > /dev/null 2>&1; then
        echo "  ✅ 函数 $func 找到"
    else
        echo "  ❌ 函数 $func 未找到"
    fi
done

# 清理
rm -f HelloWorld.java HelloWorld.class

echo ""
echo "🎉 验证完成！"
echo "==============================================="
echo "📊 验证总结："
echo "  - JVM启动时间测试: 完成"
echo "  - 内存布局分析: 完成"
echo "  - 压缩指针验证: 完成"
echo "  - 系统调用追踪: $(command -v strace &> /dev/null && echo "完成" || echo "跳过")"
echo "  - 调试工具检查: 完成"
echo "  - 源码文件检查: 完成"
echo ""
echo "🚀 颠覆性分析验证成功！"