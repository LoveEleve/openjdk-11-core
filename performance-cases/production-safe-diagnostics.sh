#!/bin/bash

# 生产环境安全诊断脚本
# 只使用无风险的只读命令，适用于真实生产环境

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_section() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

# 检查权限和环境
check_environment() {
    log_section "环境检查"
    
    # 检查是否有Java进程
    JAVA_PIDS=$(jps 2>/dev/null | grep -v Jps | awk '{print $1}' || echo "")
    if [ -z "$JAVA_PIDS" ]; then
        log_error "未找到Java进程"
        exit 1
    fi
    
    log_info "发现Java进程: $(echo $JAVA_PIDS | tr '\n' ' ')"
    
    # 选择主要进程 (通常是第一个非jps进程)
    MAIN_PID=$(echo $JAVA_PIDS | head -1)
    log_info "主要分析进程: $MAIN_PID"
    
    # 检查基本命令可用性
    for cmd in jstat jcmd top free netstat; do
        if ! command -v $cmd >/dev/null 2>&1; then
            log_warn "命令 $cmd 不可用"
        fi
    done
}

# 第一层：系统基础信息
collect_system_info() {
    log_section "系统基础信息"
    
    echo "时间: $(date)"
    echo "主机: $(hostname)"
    echo "用户: $(whoami)"
    echo "系统: $(uname -a)"
    
    # CPU信息
    echo -e "\nCPU使用率:"
    top -bn1 | grep "Cpu(s)" || echo "无法获取CPU信息"
    
    # 内存信息
    echo -e "\n内存使用:"
    free -h 2>/dev/null || echo "无法获取内存信息"
    
    # 系统负载
    echo -e "\n系统负载:"
    uptime 2>/dev/null || echo "无法获取负载信息"
    
    # 磁盘使用
    echo -e "\n磁盘使用:"
    df -h 2>/dev/null | head -10 || echo "无法获取磁盘信息"
}

# 第二层：Java进程信息
collect_java_info() {
    log_section "Java进程信息"
    
    # Java进程列表
    echo "Java进程列表:"
    jps -v 2>/dev/null || echo "无法获取Java进程信息"
    
    if [ ! -z "$MAIN_PID" ]; then
        echo -e "\n主进程 $MAIN_PID 详细信息:"
        
        # JVM参数
        echo -e "\nJVM启动参数:"
        jcmd $MAIN_PID VM.flags 2>/dev/null | head -20 || echo "无法获取JVM参数"
        
        # 系统属性
        echo -e "\n关键系统属性:"
        jcmd $MAIN_PID VM.system_properties 2>/dev/null | grep -E "(java.version|java.vm|os.name|user.dir)" || echo "无法获取系统属性"
        
        # 进程状态
        echo -e "\n进程状态:"
        if [ -f "/proc/$MAIN_PID/status" ]; then
            grep -E "(Name|State|Pid|PPid|Threads|VmSize|VmRSS)" /proc/$MAIN_PID/status 2>/dev/null || echo "无法读取进程状态"
        fi
    fi
}

# 第三层：GC和内存分析
collect_gc_memory_info() {
    log_section "GC和内存分析"
    
    if [ ! -z "$MAIN_PID" ]; then
        # GC统计
        echo "GC统计信息:"
        jstat -gc $MAIN_PID 2>/dev/null || echo "无法获取GC统计"
        
        echo -e "\nGC容量信息:"
        jstat -gccapacity $MAIN_PID 2>/dev/null || echo "无法获取GC容量信息"
        
        echo -e "\n类加载统计:"
        jstat -class $MAIN_PID 2>/dev/null || echo "无法获取类加载统计"
        
        echo -e "\nJIT编译统计:"
        jstat -compiler $MAIN_PID 2>/dev/null || echo "无法获取编译统计"
        
        # 堆内存对象分布 (Top 15，相对安全)
        echo -e "\n堆内存对象分布 (Top 15):"
        jcmd $MAIN_PID GC.class_histogram 2>/dev/null | head -20 || echo "无法获取堆内存分布"
    fi
}

# 第四层：线程和网络分析
collect_thread_network_info() {
    log_section "线程和网络分析"
    
    if [ ! -z "$MAIN_PID" ]; then
        # 线程统计 (不获取详细堆栈，避免STW)
        echo "线程状态统计:"
        jcmd $MAIN_PID Thread.print 2>/dev/null | grep "java.lang.Thread.State" | sort | uniq -c || echo "无法获取线程统计"
        
        # 文件描述符使用
        echo -e "\n文件描述符使用:"
        if [ -d "/proc/$MAIN_PID/fd" ]; then
            FD_COUNT=$(ls /proc/$MAIN_PID/fd 2>/dev/null | wc -l)
            echo "当前使用: $FD_COUNT"
            
            # 文件描述符限制
            if [ -f "/proc/$MAIN_PID/limits" ]; then
                grep "open files" /proc/$MAIN_PID/limits 2>/dev/null || echo "无法获取文件描述符限制"
            fi
        fi
    fi
    
    # 网络连接统计
    echo -e "\n网络连接统计:"
    if command -v netstat >/dev/null 2>&1; then
        echo "ESTABLISHED连接: $(netstat -an 2>/dev/null | grep ESTABLISHED | wc -l)"
        echo "TIME_WAIT连接: $(netstat -an 2>/dev/null | grep TIME_WAIT | wc -l)"
        echo "LISTEN端口: $(netstat -tln 2>/dev/null | grep LISTEN | wc -l)"
    elif command -v ss >/dev/null 2>&1; then
        echo "ESTABLISHED连接: $(ss -an 2>/dev/null | grep ESTAB | wc -l)"
        echo "TIME_WAIT连接: $(ss -an 2>/dev/null | grep TIME-WAIT | wc -l)"
    else
        echo "无法获取网络连接信息"
    fi
}

# 第五层：应用日志分析
collect_application_logs() {
    log_section "应用日志分析"
    
    # 常见日志路径
    LOG_PATHS=(
        "logs/application.log"
        "logs/catalina.out"
        "logs/server.log"
        "../logs/application.log"
        "/var/log/application.log"
        "/opt/app/logs/application.log"
    )
    
    FOUND_LOGS=false
    
    for log_path in "${LOG_PATHS[@]}"; do
        if [ -f "$log_path" ] && [ -r "$log_path" ]; then
            FOUND_LOGS=true
            echo "分析日志文件: $log_path"
            
            # 最近的错误日志
            echo "最近的ERROR日志 (最多10条):"
            tail -1000 "$log_path" 2>/dev/null | grep -i error | tail -10 || echo "无ERROR日志"
            
            # 最近的异常日志
            echo -e "\n最近的异常日志 (最多5条):"
            tail -1000 "$log_path" 2>/dev/null | grep -i exception | tail -5 || echo "无异常日志"
            
            # GC日志 (如果在应用日志中)
            echo -e "\n最近的GC日志 (最多5条):"
            tail -1000 "$log_path" 2>/dev/null | grep -i "gc\|garbage" | tail -5 || echo "无GC日志"
            
            break
        fi
    done
    
    if [ "$FOUND_LOGS" = false ]; then
        echo "未找到可读的应用日志文件"
        echo "尝试查找GC日志文件:"
        find . -name "*.log" -type f 2>/dev/null | grep -i gc | head -5 || echo "未找到GC日志"
    fi
}

# 第六层：性能指标趋势
collect_performance_trends() {
    log_section "性能指标趋势 (5秒采样)"
    
    if [ ! -z "$MAIN_PID" ]; then
        echo "采集5秒性能数据..."
        
        # GC趋势
        echo "GC趋势 (每秒采样):"
        for i in {1..5}; do
            echo "采样 $i: $(date +%H:%M:%S)"
            jstat -gc $MAIN_PID 2>/dev/null | tail -1 || echo "无法获取GC数据"
            sleep 1
        done
        
        # CPU使用趋势
        echo -e "\nCPU使用趋势:"
        for i in {1..3}; do
            echo "采样 $i: $(top -bn1 -p $MAIN_PID 2>/dev/null | grep $MAIN_PID | awk '{print "CPU: "$9"%, MEM: "$10"%"}' || echo "无法获取CPU数据")"
            sleep 2
        done
    fi
}

# 生成诊断报告
generate_report() {
    log_section "诊断报告生成"
    
    REPORT_FILE="production_diagnostics_$(date +%Y%m%d_%H%M%S).txt"
    
    echo "生成诊断报告: $REPORT_FILE"
    
    # 将所有输出重定向到报告文件
    {
        echo "=========================================="
        echo "生产环境安全诊断报告"
        echo "生成时间: $(date)"
        echo "=========================================="
        echo ""
        
        # 重新执行所有检查并输出到文件
        check_environment
        collect_system_info
        collect_java_info
        collect_gc_memory_info
        collect_thread_network_info
        collect_application_logs
        
        echo ""
        echo "=========================================="
        echo "诊断建议"
        echo "=========================================="
        
        # 基于收集的数据给出建议
        if [ ! -z "$MAIN_PID" ]; then
            # 检查内存使用
            HEAP_USED=$(jstat -gc $MAIN_PID 2>/dev/null | tail -1 | awk '{print ($3+$4+$6+$8)/1024}' || echo "0")
            if (( $(echo "$HEAP_USED > 6000" | bc -l 2>/dev/null || echo "0") )); then
                echo "⚠️  堆内存使用过高 (${HEAP_USED}MB)，建议检查内存泄漏"
            fi
            
            # 检查GC频率
            GC_COUNT=$(jstat -gc $MAIN_PID 2>/dev/null | tail -1 | awk '{print $5+$7}' || echo "0")
            if (( $(echo "$GC_COUNT > 1000" | bc -l 2>/dev/null || echo "0") )); then
                echo "⚠️  GC次数过多 (${GC_COUNT})，建议优化GC参数"
            fi
        fi
        
        echo ""
        echo "建议后续操作:"
        echo "1. 如需详细线程分析，考虑使用 jstack $MAIN_PID"
        echo "2. 如需堆内存分析，考虑使用 jmap -histo $MAIN_PID"
        echo "3. 如需实时监控，考虑使用 Arthas"
        echo "4. 查看应用监控面板和告警信息"
        echo "5. 分析应用性能指标趋势"
        
    } > "$REPORT_FILE" 2>&1
    
    log_info "诊断报告已生成: $REPORT_FILE"
    log_info "报告大小: $(ls -lh $REPORT_FILE | awk '{print $5}')"
}

# 主函数
main() {
    echo -e "${BLUE}"
    echo "=========================================="
    echo "生产环境安全诊断工具"
    echo "适用于权限受限的生产环境"
    echo "只使用安全的只读命令"
    echo "=========================================="
    echo -e "${NC}"
    
    # 检查环境
    check_environment
    
    # 收集各层信息
    collect_system_info
    collect_java_info
    collect_gc_memory_info
    collect_thread_network_info
    collect_application_logs
    collect_performance_trends
    
    # 生成报告
    generate_report
    
    log_info "诊断完成！"
    log_info "如需更深入分析，请参考生产环境排查SOP文档"
}

# 执行主函数
main "$@"