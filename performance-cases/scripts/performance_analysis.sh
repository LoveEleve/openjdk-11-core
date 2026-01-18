#!/bin/bash

# 性能问题排查脚本集合
# 基于OpenJDK11环境的真实性能问题排查工具

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置参数
PID=""
DURATION=60
OUTPUT_DIR="./performance_analysis_$(date +%Y%m%d_%H%M%S)"

# 帮助信息
show_help() {
    echo "性能问题排查脚本 - 基于OpenJDK11"
    echo ""
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -p PID          目标Java进程ID"
    echo "  -d DURATION     采样持续时间(秒)，默认60"
    echo "  -o OUTPUT_DIR   输出目录，默认自动生成"
    echo "  -h              显示帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 -p 12345 -d 120"
    echo "  $0 -p 12345 -o /tmp/analysis"
    echo ""
}

# 解析命令行参数
while getopts "p:d:o:h" opt; do
    case $opt in
        p) PID="$OPTARG" ;;
        d) DURATION="$OPTARG" ;;
        o) OUTPUT_DIR="$OPTARG" ;;
        h) show_help; exit 0 ;;
        \?) echo "无效选项: -$OPTARG" >&2; show_help; exit 1 ;;
    esac
done

# 检查必需参数
if [ -z "$PID" ]; then
    echo -e "${RED}错误: 必须指定进程ID (-p)${NC}"
    show_help
    exit 1
fi

# 检查进程是否存在
if ! kill -0 "$PID" 2>/dev/null; then
    echo -e "${RED}错误: 进程 $PID 不存在或无权限访问${NC}"
    exit 1
fi

# 检查是否为Java进程
if ! ps -p "$PID" -o cmd= | grep -q java; then
    echo -e "${YELLOW}警告: 进程 $PID 可能不是Java进程${NC}"
fi

# 创建输出目录
mkdir -p "$OUTPUT_DIR"
echo -e "${GREEN}输出目录: $OUTPUT_DIR${NC}"

# 记录系统信息
log_system_info() {
    echo -e "${BLUE}=== 收集系统信息 ===${NC}"
    
    {
        echo "=== 系统信息 ==="
        echo "时间: $(date)"
        echo "主机: $(hostname)"
        echo "内核: $(uname -r)"
        echo "CPU: $(nproc) 核"
        echo "内存: $(free -h | grep Mem | awk '{print $2}')"
        echo ""
        
        echo "=== Java进程信息 ==="
        echo "PID: $PID"
        echo "命令行: $(ps -p $PID -o cmd= 2>/dev/null || echo '获取失败')"
        echo "启动时间: $(ps -p $PID -o lstart= 2>/dev/null || echo '获取失败')"
        echo ""
        
        echo "=== JVM信息 ==="
        jcmd $PID VM.version 2>/dev/null || echo "无法获取JVM版本信息"
        echo ""
        jcmd $PID VM.system_properties 2>/dev/null | grep -E "(java.version|java.vm.name|java.vm.version)" || echo "无法获取JVM属性"
        echo ""
        
    } > "$OUTPUT_DIR/system_info.txt"
    
    echo "系统信息已保存到: $OUTPUT_DIR/system_info.txt"
}

# 收集线程堆栈
collect_thread_dumps() {
    echo -e "${BLUE}=== 收集线程堆栈 (连续3次) ===${NC}"
    
    for i in {1..3}; do
        echo "收集第 $i 次线程堆栈..."
        jstack $PID > "$OUTPUT_DIR/thread_dump_$i.txt" 2>&1
        if [ $i -lt 3 ]; then
            sleep 5
        fi
    done
    
    # 分析线程状态
    echo -e "${BLUE}=== 分析线程状态 ===${NC}"
    {
        echo "=== 线程状态统计 ==="
        for i in {1..3}; do
            echo "--- 第 $i 次采样 ---"
            grep "java.lang.Thread.State" "$OUTPUT_DIR/thread_dump_$i.txt" | sort | uniq -c | sort -nr
            echo ""
        done
        
        echo "=== BLOCKED线程分析 ==="
        echo "查找持续BLOCKED的线程..."
        
        # 查找在所有采样中都BLOCKED的线程
        blocked_threads=$(grep -B2 "BLOCKED" "$OUTPUT_DIR/thread_dump_1.txt" | grep "^\"" | cut -d'"' -f2)
        
        for thread in $blocked_threads; do
            count=0
            for i in {1..3}; do
                if grep -A5 "\"$thread\"" "$OUTPUT_DIR/thread_dump_$i.txt" | grep -q "BLOCKED"; then
                    ((count++))
                fi
            done
            if [ $count -eq 3 ]; then
                echo "持续BLOCKED线程: $thread"
            fi
        done
        
    } > "$OUTPUT_DIR/thread_analysis.txt"
    
    echo "线程分析结果已保存到: $OUTPUT_DIR/thread_analysis.txt"
}

# 收集GC信息
collect_gc_info() {
    echo -e "${BLUE}=== 收集GC信息 ===${NC}"
    
    {
        echo "=== GC统计信息 ==="
        jstat -gc $PID
        echo ""
        
        echo "=== GC性能统计 ==="
        jstat -gcutil $PID
        echo ""
        
        echo "=== 堆内存使用 ==="
        jcmd $PID GC.run_finalization 2>/dev/null || echo "无法执行finalization"
        jmap -histo $PID | head -20
        echo ""
        
    } > "$OUTPUT_DIR/gc_info.txt"
    
    echo "GC信息已保存到: $OUTPUT_DIR/gc_info.txt"
}

# 收集内存信息
collect_memory_info() {
    echo -e "${BLUE}=== 收集内存信息 ===${NC}"
    
    {
        echo "=== 堆内存概览 ==="
        jmap -heap $PID 2>/dev/null || echo "无法获取堆信息"
        echo ""
        
        echo "=== 对象统计 (Top 50) ==="
        jmap -histo $PID | head -50
        echo ""
        
        echo "=== 内存使用趋势 ==="
        for i in {1..5}; do
            echo "--- 第 $i 次采样 (间隔10秒) ---"
            jstat -gc $PID | tail -1
            if [ $i -lt 5 ]; then
                sleep 10
            fi
        done
        
    } > "$OUTPUT_DIR/memory_info.txt"
    
    echo "内存信息已保存到: $OUTPUT_DIR/memory_info.txt"
}

# 使用async-profiler进行CPU采样
collect_cpu_profile() {
    echo -e "${BLUE}=== CPU性能采样 (${DURATION}秒) ===${NC}"
    
    # 检查async-profiler是否可用
    PROFILER_JAR="async-profiler.jar"
    if [ ! -f "$PROFILER_JAR" ]; then
        echo -e "${YELLOW}警告: 未找到async-profiler.jar，跳过CPU采样${NC}"
        return
    fi
    
    echo "开始CPU采样，持续 $DURATION 秒..."
    java -jar "$PROFILER_JAR" -e cpu -d $DURATION -f "$OUTPUT_DIR/cpu_profile.html" $PID 2>&1 | tee "$OUTPUT_DIR/profiler.log"
    
    if [ -f "$OUTPUT_DIR/cpu_profile.html" ]; then
        echo "CPU性能报告已保存到: $OUTPUT_DIR/cpu_profile.html"
    else
        echo -e "${YELLOW}CPU采样失败，请检查profiler.log${NC}"
    fi
}

# 使用jstack分析锁竞争
analyze_lock_contention() {
    echo -e "${BLUE}=== 分析锁竞争 ===${NC}"
    
    {
        echo "=== 锁竞争分析 ==="
        echo "基于线程堆栈分析锁竞争情况..."
        echo ""
        
        # 分析BLOCKED线程
        echo "--- BLOCKED线程统计 ---"
        for i in {1..3}; do
            echo "第 $i 次采样:"
            blocked_count=$(grep -c "BLOCKED" "$OUTPUT_DIR/thread_dump_$i.txt" 2>/dev/null || echo 0)
            total_count=$(grep -c "java.lang.Thread.State" "$OUTPUT_DIR/thread_dump_$i.txt" 2>/dev/null || echo 0)
            if [ $total_count -gt 0 ]; then
                blocked_ratio=$(echo "scale=2; $blocked_count * 100 / $total_count" | bc -l 2>/dev/null || echo "0")
                echo "  BLOCKED线程: $blocked_count/$total_count (${blocked_ratio}%)"
            fi
        done
        echo ""
        
        # 查找热点锁对象
        echo "--- 热点锁对象 ---"
        grep -h "waiting to lock" "$OUTPUT_DIR"/thread_dump_*.txt | sort | uniq -c | sort -nr | head -10
        echo ""
        
        # 查找持有锁的线程
        echo "--- 锁持有者分析 ---"
        grep -h "locked" "$OUTPUT_DIR"/thread_dump_*.txt | sort | uniq -c | sort -nr | head -10
        echo ""
        
    } > "$OUTPUT_DIR/lock_analysis.txt"
    
    echo "锁竞争分析已保存到: $OUTPUT_DIR/lock_analysis.txt"
}

# 收集JVM内部信息
collect_jvm_internals() {
    echo -e "${BLUE}=== 收集JVM内部信息 ===${NC}"
    
    {
        echo "=== JVM标志参数 ==="
        jcmd $PID VM.flags 2>/dev/null || echo "无法获取JVM标志"
        echo ""
        
        echo "=== 类加载统计 ==="
        jcmd $PID VM.classloader_stats 2>/dev/null || echo "无法获取类加载统计"
        echo ""
        
        echo "=== 编译统计 ==="
        jcmd $PID Compiler.queue 2>/dev/null || echo "无法获取编译队列信息"
        echo ""
        
        echo "=== 代码缓存 ==="
        jcmd $PID Compiler.codecache 2>/dev/null || echo "无法获取代码缓存信息"
        echo ""
        
    } > "$OUTPUT_DIR/jvm_internals.txt"
    
    echo "JVM内部信息已保存到: $OUTPUT_DIR/jvm_internals.txt"
}

# 生成性能分析报告
generate_report() {
    echo -e "${BLUE}=== 生成性能分析报告 ===${NC}"
    
    REPORT_FILE="$OUTPUT_DIR/performance_report.md"
    
    {
        echo "# Java性能问题分析报告"
        echo ""
        echo "**生成时间**: $(date)"
        echo "**目标进程**: $PID"
        echo "**分析持续时间**: ${DURATION}秒"
        echo ""
        
        echo "## 1. 系统概览"
        echo ""
        echo "### 基本信息"
        grep -A10 "=== 系统信息 ===" "$OUTPUT_DIR/system_info.txt" | tail -n +2
        echo ""
        
        echo "### JVM信息"
        grep -A5 "=== JVM信息 ===" "$OUTPUT_DIR/system_info.txt" | tail -n +2
        echo ""
        
        echo "## 2. 线程分析"
        echo ""
        echo "### 线程状态分布"
        echo '```'
        grep -A20 "=== 线程状态统计 ===" "$OUTPUT_DIR/thread_analysis.txt" | tail -n +2
        echo '```'
        echo ""
        
        echo "### 锁竞争分析"
        echo '```'
        head -20 "$OUTPUT_DIR/lock_analysis.txt"
        echo '```'
        echo ""
        
        echo "## 3. 内存分析"
        echo ""
        echo "### GC统计"
        echo '```'
        grep -A5 "=== GC统计信息 ===" "$OUTPUT_DIR/gc_info.txt" | tail -n +2
        echo '```'
        echo ""
        
        echo "### 堆内存使用"
        echo '```'
        grep -A10 "=== 对象统计" "$OUTPUT_DIR/memory_info.txt" | tail -n +2 | head -15
        echo '```'
        echo ""
        
        echo "## 4. 性能建议"
        echo ""
        
        # 基于分析结果给出建议
        blocked_ratio=$(grep "BLOCKED线程" "$OUTPUT_DIR/lock_analysis.txt" | head -1 | grep -o '[0-9.]*%' | head -1 | sed 's/%//')
        if [ ! -z "$blocked_ratio" ] && [ $(echo "$blocked_ratio > 10" | bc -l 2>/dev/null || echo 0) -eq 1 ]; then
            echo "### ⚠️ 锁竞争问题"
            echo "- 检测到高比例的BLOCKED线程 (${blocked_ratio}%)"
            echo "- 建议检查热点锁对象，考虑使用细粒度锁或无锁编程"
            echo "- 参考文档: 03_并发锁竞争性能问题.md"
            echo ""
        fi
        
        # 检查GC问题
        if grep -q "Full GC" "$OUTPUT_DIR/gc_info.txt"; then
            echo "### ⚠️ GC性能问题"
            echo "- 检测到Full GC，可能存在内存泄漏或GC参数不当"
            echo "- 建议分析堆转储文件，优化GC参数"
            echo "- 参考文档: 02_G1GC混合回收性能问题.md"
            echo ""
        fi
        
        echo "## 5. 相关文件"
        echo ""
        echo "- 线程堆栈: thread_dump_*.txt"
        echo "- GC信息: gc_info.txt"
        echo "- 内存分析: memory_info.txt"
        echo "- 锁分析: lock_analysis.txt"
        echo "- JVM内部: jvm_internals.txt"
        if [ -f "$OUTPUT_DIR/cpu_profile.html" ]; then
            echo "- CPU性能报告: cpu_profile.html"
        fi
        echo ""
        
    } > "$REPORT_FILE"
    
    echo "性能分析报告已生成: $REPORT_FILE"
}

# 主执行流程
main() {
    echo -e "${GREEN}开始Java性能问题排查...${NC}"
    echo "目标进程: $PID"
    echo "持续时间: ${DURATION}秒"
    echo ""
    
    # 执行各项分析
    log_system_info
    collect_thread_dumps
    collect_gc_info
    collect_memory_info
    analyze_lock_contention
    collect_jvm_internals
    
    # CPU采样（可选，需要async-profiler）
    collect_cpu_profile
    
    # 生成报告
    generate_report
    
    echo ""
    echo -e "${GREEN}性能分析完成！${NC}"
    echo -e "分析结果保存在: ${BLUE}$OUTPUT_DIR${NC}"
    echo -e "查看报告: ${BLUE}$OUTPUT_DIR/performance_report.md${NC}"
    
    # 提供快速查看建议
    echo ""
    echo -e "${YELLOW}快速查看建议:${NC}"
    echo "1. 查看线程状态: cat $OUTPUT_DIR/thread_analysis.txt"
    echo "2. 查看锁竞争: cat $OUTPUT_DIR/lock_analysis.txt"
    echo "3. 查看GC状态: cat $OUTPUT_DIR/gc_info.txt"
    echo "4. 查看完整报告: cat $OUTPUT_DIR/performance_report.md"
}

# 执行主流程
main