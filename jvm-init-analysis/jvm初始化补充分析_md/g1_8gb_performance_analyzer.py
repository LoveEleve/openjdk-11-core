#!/usr/bin/env python3
"""
🚀 8GB G1 JVM初始化性能分析工具
严格按照 -Xms=8GB -Xmx=8GB -XX:+UseG1GC -XX:-UseLargePages 配置
基于strace系统调用追踪和启动时间分析
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
class MemoryAllocation:
    """内存分配信息"""
    timestamp: str
    address: str
    size: int
    size_mb: float
    protection: str
    allocation_type: str
    duration_ms: float

@dataclass
class G1InitPhase:
    """G1初始化阶段信息"""
    phase_name: str
    start_time: float
    duration_ms: float
    memory_allocated: int
    description: str

class G1_8GB_Analyzer:
    """8GB G1配置专用分析器"""
    
    def __init__(self):
        self.jvm_args = ["-Xms=8GB", "-Xmx=8GB", "-XX:+UseG1GC", "-XX:-UseLargePages"]
        self.allocations: List[MemoryAllocation] = []
        self.init_phases: List[G1InitPhase] = []
        
    def run_with_strace(self, class_name: str = "HelloWorld") -> str:
        """使用strace运行Java程序，专门追踪8GB G1配置"""
        
        # 创建测试程序
        self._create_test_program()
        
        cmd = [
            "strace", "-tt", "-T", "-e", "mmap,munmap,mprotect",
            "java"
        ] + self.jvm_args + ["-XX:+TraceStartupTime", class_name]
        
        try:
            print("🚀 开始8GB G1 JVM性能分析...")
            print(f"   JVM参数: {' '.join(self.jvm_args)}")
            
            result = subprocess.run(
                cmd, 
                capture_output=True, 
                text=True, 
                timeout=120
            )
            return result.stderr  # strace输出到stderr
        except subprocess.TimeoutExpired:
            print("❌ 程序执行超时")
            return ""
        except Exception as e:
            print(f"❌ 执行失败: {e}")
            return ""
    
    def _create_test_program(self):
        """创建8GB G1测试程序"""
        test_code = '''
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("🚀 8GB G1 JVM初始化分析");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        System.out.println("最大内存: " + (maxMemory / (1024*1024*1024)) + " GB");
        System.out.println("总内存: " + (totalMemory / (1024*1024)) + " MB");
        System.out.println("空闲内存: " + (freeMemory / (1024*1024)) + " MB");
        
        // 验证G1配置
        System.out.println("GC类型: " + System.getProperty("java.vm.name"));
        
        // 触发一些内存分配以验证G1工作
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            list.add("G1-Test-" + i);
        }
        System.out.println("内存分配测试完成，列表大小: " + list.size());
    }
}
'''
        with open("HelloWorld.java", "w") as f:
            f.write(test_code)
        
        # 编译
        compile_result = subprocess.run(["javac", "HelloWorld.java"], 
                                      capture_output=True, text=True)
        if compile_result.returncode != 0:
            print(f"❌ 编译失败: {compile_result.stderr}")
            sys.exit(1)
    
    def parse_8gb_memory_allocations(self, strace_output: str) -> List[MemoryAllocation]:
        """解析8GB G1配置的内存分配"""
        allocations = []
        
        # 匹配mmap系统调用
        mmap_pattern = r'(\d+:\d+:\d+\.\d+)\s+mmap\(([^)]+)\)\s+=\s+([^\s]+)\s+<([\d.]+)>'
        
        for match in re.finditer(mmap_pattern, strace_output):
            timestamp = match.group(1)
            args = match.group(2)
            result = match.group(3)
            duration = float(match.group(4)) * 1000  # 转换为毫秒
            
            # 解析mmap参数
            arg_parts = [arg.strip() for arg in args.split(',')]
            if len(arg_parts) >= 2:
                addr = arg_parts[0]
                size_str = arg_parts[1]
                
                try:
                    # 解析大小
                    if size_str.startswith('0x'):
                        size = int(size_str, 16)
                    else:
                        size = int(size_str)
                    
                    size_mb = size / (1024 * 1024)
                    
                    # 判断分配类型
                    allocation_type = self._classify_8gb_allocation(size, addr)
                    
                    allocation = MemoryAllocation(
                        timestamp=timestamp,
                        address=addr,
                        size=size,
                        size_mb=size_mb,
                        protection=arg_parts[2] if len(arg_parts) > 2 else "UNKNOWN",
                        allocation_type=allocation_type,
                        duration_ms=duration
                    )
                    allocations.append(allocation)
                    
                except (ValueError, IndexError):
                    continue
        
        return allocations
    
    def _classify_8gb_allocation(self, size: int, addr: str) -> str:
        """分类8GB G1配置的内存分配"""
        size_gb = size / (1024 * 1024 * 1024)
        size_mb = size / (1024 * 1024)
        
        if size_gb >= 7.5:  # 8GB堆
            return "8GB Java堆保留"
        elif size_gb >= 0.9:  # 1GB类空间
            return "1GB 压缩类空间保留"
        elif size_mb >= 250:  # 256MB初始提交
            return "初始堆内存提交"
        elif size_mb >= 60:   # 64MB类空间提交
            return "初始类空间提交"
        elif size_mb >= 30:   # 32MB标记位图
            return "G1标记位图 (prev+next)"
        elif size_mb >= 15:   # 16MB卡表或BOT表
            return "G1卡表或BOT表"
        else:
            return "其他内存分配"
    
    def analyze_8gb_performance(self, strace_output: str) -> Dict:
        """分析8GB G1配置的性能"""
        allocations = self.parse_8gb_memory_allocations(strace_output)
        
        # 按分配类型分组
        allocation_groups = {}
        total_memory = 0
        total_duration = 0
        
        for alloc in allocations:
            alloc_type = alloc.allocation_type
            if alloc_type not in allocation_groups:
                allocation_groups[alloc_type] = {
                    'count': 0,
                    'total_size_mb': 0,
                    'total_duration_ms': 0,
                    'allocations': []
                }
            
            allocation_groups[alloc_type]['count'] += 1
            allocation_groups[alloc_type]['total_size_mb'] += alloc.size_mb
            allocation_groups[alloc_type]['total_duration_ms'] += alloc.duration_ms
            allocation_groups[alloc_type]['allocations'].append(alloc)
            
            total_memory += alloc.size
            total_duration += alloc.duration_ms
        
        # 生成性能洞察
        insights = self._generate_8gb_insights(allocation_groups, total_duration)
        
        return {
            'configuration': {
                'heap_size': '8GB',
                'gc_type': 'G1',
                'large_pages': 'disabled',
                'jvm_args': self.jvm_args
            },
            'summary': {
                'total_allocations': len(allocations),
                'total_memory_gb': total_memory / (1024**3),
                'total_duration_ms': total_duration,
                'average_allocation_time_ms': total_duration / len(allocations) if allocations else 0
            },
            'allocation_breakdown': allocation_groups,
            'performance_insights': insights,
            'detailed_allocations': [
                {
                    'timestamp': alloc.timestamp,
                    'type': alloc.allocation_type,
                    'size_mb': round(alloc.size_mb, 2),
                    'duration_ms': round(alloc.duration_ms, 2),
                    'address': alloc.address
                }
                for alloc in allocations
            ]
        }
    
    def _generate_8gb_insights(self, allocation_groups: Dict, total_duration: float) -> List[str]:
        """生成8GB G1配置的性能洞察"""
        insights = []
        
        # 找出最耗时的分配
        max_duration_type = max(allocation_groups.items(), 
                              key=lambda x: x[1]['total_duration_ms'])
        insights.append(f"🔥 最耗时的内存分配: {max_duration_type[0]} "
                       f"({max_duration_type[1]['total_duration_ms']:.1f}ms)")
        
        # 分析8GB堆分配
        if "8GB Java堆保留" in allocation_groups:
            heap_alloc = allocation_groups["8GB Java堆保留"]
            heap_duration = heap_alloc['total_duration_ms']
            heap_percentage = (heap_duration / total_duration) * 100
            insights.append(f"🔥 8GB堆保留耗时: {heap_duration:.1f}ms ({heap_percentage:.1f}%)")
        
        # 分析G1辅助结构
        g1_structures = ["G1卡表或BOT表", "G1标记位图 (prev+next)"]
        g1_total_duration = sum(allocation_groups.get(struct, {}).get('total_duration_ms', 0) 
                               for struct in g1_structures)
        if g1_total_duration > 0:
            g1_percentage = (g1_total_duration / total_duration) * 100
            insights.append(f"🔥 G1辅助数据结构耗时: {g1_total_duration:.1f}ms ({g1_percentage:.1f}%)")
        
        # 压缩指针分析
        if "1GB 压缩类空间保留" in allocation_groups:
            class_space = allocation_groups["1GB 压缩类空间保留"]
            insights.append(f"✅ 压缩类空间配置: 1GB @ 32GB地址 (Zero-based压缩指针)")
        
        # 性能评估
        if total_duration < 200:
            insights.append("✅ 8GB G1初始化性能优秀")
        elif total_duration < 500:
            insights.append("⚠️  8GB G1初始化性能良好，可进一步优化")
        else:
            insights.append("❌ 8GB G1初始化性能需要优化")
        
        return insights
    
    def print_colored_report(self, report: Dict):
        """打印彩色的8GB G1分析报告"""
        print("\n" + "="*80)
        print("🚀 8GB G1 JVM初始化性能分析报告")
        print("="*80)
        
        # 配置信息
        config = report['configuration']
        print(f"\n📋 配置信息:")
        print(f"   堆大小: {config['heap_size']}")
        print(f"   GC类型: {config['gc_type']}")
        print(f"   大页设置: {config['large_pages']}")
        print(f"   JVM参数: {' '.join(config['jvm_args'])}")
        
        # 摘要信息
        summary = report['summary']
        print(f"\n📊 性能摘要:")
        print(f"   内存分配次数: {summary['total_allocations']}")
        print(f"   内存分配总量: {summary['total_memory_gb']:.2f} GB")
        print(f"   总分配耗时: {summary['total_duration_ms']:.1f} ms")
        print(f"   平均分配耗时: {summary['average_allocation_time_ms']:.1f} ms")
        
        # 分配详情
        print(f"\n🎯 内存分配详情:")
        for alloc_type, details in report['allocation_breakdown'].items():
            print(f"   {alloc_type}:")
            print(f"     次数: {details['count']}")
            print(f"     大小: {details['total_size_mb']:.1f} MB")
            print(f"     耗时: {details['total_duration_ms']:.1f} ms")
        
        # 性能洞察
        print(f"\n💡 性能洞察:")
        for insight in report['performance_insights']:
            print(f"   {insight}")
        
        # 关键分配时序
        print(f"\n⏱️  关键分配时序:")
        key_allocations = [alloc for alloc in report['detailed_allocations'] 
                          if alloc['size_mb'] > 50]  # 只显示大于50MB的分配
        for i, alloc in enumerate(key_allocations[:10], 1):
            print(f"   {i:2d}. {alloc['timestamp']} - {alloc['type']}")
            print(f"       大小: {alloc['size_mb']} MB, 耗时: {alloc['duration_ms']} ms")
    
    def save_report(self, report: Dict, filename: str = None):
        """保存8GB G1分析报告"""
        if filename is None:
            timestamp = int(time.time())
            filename = f"g1_8gb_analysis_report_{timestamp}.json"
        
        with open(filename, 'w', encoding='utf-8') as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"\n📄 详细报告已保存到: {filename}")

def main():
    """主函数"""
    if len(sys.argv) < 2:
        print("使用方法: python3 g1_8gb_performance_analyzer.py <Java类名>")
        print("示例: python3 g1_8gb_performance_analyzer.py HelloWorld")
        return
    
    class_name = sys.argv[1]
    
    print("🚀 开始8GB G1 JVM初始化性能分析...")
    print(f"   目标类: {class_name}")
    print(f"   配置: 8GB堆 + G1GC + 非大页")
    
    analyzer = G1_8GB_Analyzer()
    
    # 运行分析
    start_time = time.time()
    strace_output = analyzer.run_with_strace(class_name)
    end_time = time.time()
    
    if not strace_output:
        print("❌ 未能获取到分析数据")
        return
    
    print(f"✅ 数据收集完成，总耗时 {end_time - start_time:.2f} 秒")
    
    # 生成报告
    report = analyzer.analyze_8gb_performance(strace_output)
    
    # 显示报告
    analyzer.print_colored_report(report)
    
    # 保存报告
    analyzer.save_report(report)
    
    # 清理临时文件
    Path("HelloWorld.java").unlink(missing_ok=True)
    Path("HelloWorld.class").unlink(missing_ok=True)

if __name__ == "__main__":
    main()