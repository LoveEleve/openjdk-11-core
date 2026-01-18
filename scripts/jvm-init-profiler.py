#!/usr/bin/env python3
"""
🚀 JVM初始化性能剖析工具
基于OpenJDK11源码的颠覆性分析
作者: AI智能编程助手
"""

import subprocess
import re
import time
import json
import sys
from pathlib import Path
from dataclasses import dataclass
from typing import List, Dict, Optional

@dataclass
class InitFunction:
    """初始化函数信息"""
    name: str
    start_time: float
    end_time: float
    duration: float
    memory_allocated: int
    syscalls: List[str]
    dependencies: List[str]

class JVMInitProfiler:
    """JVM初始化性能剖析器"""
    
    def __init__(self, java_path: str = "java", jvm_args: List[str] = None):
        self.java_path = java_path
        self.jvm_args = jvm_args or ["-Xms8g", "-Xmx8g", "-XX:+UseG1GC"]
        self.functions: Dict[str, InitFunction] = {}
        self.total_init_time = 0.0
        
    def run_with_strace(self, class_name: str = "HelloWorld") -> str:
        """使用strace运行Java程序，捕获系统调用"""
        cmd = [
            "strace", "-tt", "-T", "-e", "mmap,munmap,mprotect,brk",
            self.java_path
        ] + self.jvm_args + ["-XX:+TraceStartupTime", class_name]
        
        try:
            result = subprocess.run(
                cmd, 
                capture_output=True, 
                text=True, 
                timeout=60
            )
            return result.stderr  # strace输出到stderr
        except subprocess.TimeoutExpired:
            print("❌ 程序执行超时")
            return ""
        except Exception as e:
            print(f"❌ 执行失败: {e}")
            return ""
    
    def parse_startup_time_log(self, output: str) -> Dict[str, float]:
        """解析启动时间日志"""
        timing_data = {}
        
        # 匹配启动时间日志格式
        # [0.123s][info][startuptime] Genesis = 156.234ms
        pattern = r'\[[\d.]+s\]\[info\]\[startuptime\]\s+(.+?)\s+=\s+([\d.]+)ms'
        
        for match in re.finditer(pattern, output):
            function_name = match.group(1).strip()
            duration_ms = float(match.group(2))
            timing_data[function_name] = duration_ms
            
        return timing_data
    
    def parse_memory_allocation(self, strace_output: str) -> List[Dict]:
        """解析内存分配信息"""
        allocations = []
        
        # 匹配mmap系统调用
        # 14:23:45.123456 mmap(0x600000000, 8589934592, PROT_NONE, ...) = 0x600000000 <0.089234>
        mmap_pattern = r'(\d+:\d+:\d+\.\d+)\s+mmap\(([^)]+)\)\s+=\s+([^\s]+)\s+<([\d.]+)>'
        
        for match in re.finditer(mmap_pattern, strace_output):
            timestamp = match.group(1)
            args = match.group(2)
            result = match.group(3)
            duration = float(match.group(4))
            
            # 解析mmap参数
            arg_parts = [arg.strip() for arg in args.split(',')]
            if len(arg_parts) >= 2:
                addr = arg_parts[0]
                size = arg_parts[1]
                
                allocations.append({
                    'timestamp': timestamp,
                    'type': 'mmap',
                    'address': addr,
                    'size': size,
                    'result': result,
                    'duration': duration
                })
        
        return allocations
    
    def analyze_critical_path(self, timing_data: Dict[str, float]) -> List[str]:
        """分析关键路径"""
        # 按耗时排序
        sorted_functions = sorted(
            timing_data.items(), 
            key=lambda x: x[1], 
            reverse=True
        )
        
        total_time = sum(timing_data.values())
        critical_path = []
        
        for func_name, duration in sorted_functions:
            percentage = (duration / total_time) * 100
            if percentage >= 5.0:  # 占用5%以上时间的函数
                critical_path.append(f"{func_name}: {duration:.3f}ms ({percentage:.1f}%)")
        
        return critical_path
    
    def generate_report(self, output: str) -> Dict:
        """生成完整的分析报告"""
        timing_data = self.parse_startup_time_log(output)
        allocations = self.parse_memory_allocation(output)
        critical_path = self.analyze_critical_path(timing_data)
        
        # 计算总的内存分配
        total_memory = 0
        for alloc in allocations:
            try:
                size_str = alloc['size']
                if size_str.startswith('0x'):
                    size = int(size_str, 16)
                else:
                    size = int(size_str)
                total_memory += size
            except:
                continue
        
        report = {
            'summary': {
                'total_functions': len(timing_data),
                'total_init_time': sum(timing_data.values()),
                'total_memory_allocated': total_memory,
                'memory_allocations_count': len(allocations)
            },
            'timing_breakdown': timing_data,
            'critical_path': critical_path,
            'memory_allocations': allocations,
            'performance_insights': self.generate_insights(timing_data, allocations)
        }
        
        return report
    
    def generate_insights(self, timing_data: Dict[str, float], allocations: List[Dict]) -> List[str]:
        """生成性能洞察"""
        insights = []
        
        # 分析最耗时的函数
        if timing_data:
            max_time_func = max(timing_data.items(), key=lambda x: x[1])
            insights.append(f"🔥 最耗时的初始化步骤: {max_time_func[0]} ({max_time_func[1]:.3f}ms)")
        
        # 分析内存分配模式
        if allocations:
            large_allocs = [a for a in allocations if 'size' in a and 
                          (a['size'].startswith('0x') and int(a['size'], 16) > 1024*1024*1024 or
                           a['size'].isdigit() and int(a['size']) > 1024*1024*1024)]
            if large_allocs:
                insights.append(f"🔥 发现 {len(large_allocs)} 个大内存分配 (>1GB)")
        
        # 分析初始化效率
        total_time = sum(timing_data.values())
        if total_time > 200:
            insights.append("⚠️  初始化时间较长，建议优化堆大小或GC策略")
        elif total_time < 50:
            insights.append("✅ 初始化性能优秀")
        
        return insights
    
    def print_colored_report(self, report: Dict):
        """打印彩色报告"""
        print("\n" + "="*80)
        print("🚀 JVM初始化性能分析报告")
        print("="*80)
        
        # 摘要信息
        summary = report['summary']
        print(f"\n📊 摘要信息:")
        print(f"   初始化函数数量: {summary['total_functions']}")
        print(f"   总初始化时间: {summary['total_init_time']:.3f} ms")
        print(f"   内存分配总量: {summary['total_memory_allocated'] / (1024*1024*1024):.2f} GB")
        print(f"   内存分配次数: {summary['memory_allocations_count']}")
        
        # 关键路径
        print(f"\n🎯 性能关键路径:")
        for i, path_item in enumerate(report['critical_path'][:10], 1):
            print(f"   {i:2d}. {path_item}")
        
        # 性能洞察
        print(f"\n💡 性能洞察:")
        for insight in report['performance_insights']:
            print(f"   {insight}")
        
        # 内存分配详情（前10个）
        print(f"\n🔍 主要内存分配:")
        for i, alloc in enumerate(report['memory_allocations'][:10], 1):
            size_mb = 0
            try:
                size_str = alloc['size']
                if size_str.startswith('0x'):
                    size_mb = int(size_str, 16) / (1024*1024)
                else:
                    size_mb = int(size_str) / (1024*1024)
            except:
                pass
            
            print(f"   {i:2d}. {alloc['timestamp']} - {size_mb:.1f} MB @ {alloc['address']}")
    
    def save_report(self, report: Dict, filename: str = "jvm_init_report.json"):
        """保存报告到文件"""
        with open(filename, 'w', encoding='utf-8') as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"\n📄 详细报告已保存到: {filename}")

def main():
    """主函数"""
    if len(sys.argv) < 2:
        print("使用方法: python3 jvm-init-profiler.py <Java类名> [JVM参数...]")
        print("示例: python3 jvm-init-profiler.py HelloWorld -Xms4g -Xmx4g")
        return
    
    class_name = sys.argv[1]
    jvm_args = sys.argv[2:] if len(sys.argv) > 2 else ["-Xms8g", "-Xmx8g", "-XX:+UseG1GC"]
    
    print("🚀 开始JVM初始化性能分析...")
    print(f"   目标类: {class_name}")
    print(f"   JVM参数: {' '.join(jvm_args)}")
    
    profiler = JVMInitProfiler(jvm_args=jvm_args)
    
    # 运行分析
    start_time = time.time()
    output = profiler.run_with_strace(class_name)
    end_time = time.time()
    
    if not output:
        print("❌ 未能获取到分析数据")
        return
    
    print(f"✅ 数据收集完成，耗时 {end_time - start_time:.2f} 秒")
    
    # 生成报告
    report = profiler.generate_report(output)
    
    # 显示报告
    profiler.print_colored_report(report)
    
    # 保存报告
    timestamp = int(time.time())
    filename = f"jvm_init_report_{timestamp}.json"
    profiler.save_report(report, filename)

if __name__ == "__main__":
    main()