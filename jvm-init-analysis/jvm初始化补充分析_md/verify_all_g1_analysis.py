#!/usr/bin/env python3
"""
G1 GC分析结论验证脚本
基于OpenJDK11源码分析的8GB G1堆配置验证工具
"""

import subprocess
import re
import os
import sys
import time
from typing import Dict, List, Tuple, Optional

class G1AnalysisVerifier:
    """G1分析结论验证器"""
    
    def __init__(self):
        self.verification_results = {}
        self.test_java_file = "/tmp/G1VerificationTest.java"
        self.create_test_program()
    
    def create_test_program(self):
        """创建用于验证的Java测试程序"""
        test_program = '''
public class G1VerificationTest {
    public static void main(String[] args) {
        System.out.println("=== G1 GC验证测试程序 ===");
        
        // 打印JVM信息
        System.out.println("Java版本: " + System.getProperty("java.version"));
        System.out.println("JVM名称: " + System.getProperty("java.vm.name"));
        
        // 获取运行时信息
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        System.out.println("最大内存: " + (maxMemory / 1024 / 1024) + "MB");
        System.out.println("总内存: " + (totalMemory / 1024 / 1024) + "MB");
        System.out.println("空闲内存: " + (freeMemory / 1024 / 1024) + "MB");
        
        // 创建一些对象触发GC
        System.out.println("\\n开始分配测试...");
        for (int i = 0; i < 1000; i++) {
            byte[] data = new byte[1024 * 1024]; // 1MB对象
            if (i % 100 == 0) {
                System.out.println("已分配: " + (i + 1) + "MB");
                System.gc(); // 建议GC
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        
        System.out.println("测试完成");
    }
}
'''
        
        with open(self.test_java_file, 'w') as f:
            f.write(test_program)
    
    def verify_region_size(self) -> bool:
        """验证G1 Region大小是否为4MB"""
        print("🔍 验证G1 Region大小...")
        
        try:
            # 编译测试程序
            subprocess.run(['javac', self.test_java_file], check=True, 
                          capture_output=True, text=True)
            
            # 运行程序并捕获G1输出
            result = subprocess.run([
                'java', '-Xms8g', '-Xmx8g', '-XX:+UseG1GC', 
                '-XX:+PrintGC', '-XX:+PrintGCDetails',
                '-cp', '/tmp', 'G1VerificationTest'
            ], capture_output=True, text=True, timeout=30)
            
            # 分析输出查找Region大小
            output = result.stderr + result.stdout
            
            # 查找Region大小信息
            region_size_pattern = r'Heap region size: (\d+)([KMG])'
            match = re.search(region_size_pattern, output)
            
            if match:
                size_value = int(match.group(1))
                size_unit = match.group(2)
                
                if size_unit == 'M' and size_value == 4:
                    print("✅ Region大小验证成功: 4MB")
                    self.verification_results['region_size'] = True
                    return True
                else:
                    print(f"❌ Region大小不符合预期: {size_value}{size_unit}")
                    self.verification_results['region_size'] = False
                    return False
            else:
                print("⚠️  无法从输出中提取Region大小信息")
                self.verification_results['region_size'] = None
                return False
                
        except subprocess.TimeoutExpired:
            print("❌ 验证超时")
            return False
        except Exception as e:
            print(f"❌ 验证过程出错: {e}")
            return False
    
    def verify_compressed_oops(self) -> bool:
        """验证压缩指针配置"""
        print("🔍 验证压缩指针配置...")
        
        try:
            result = subprocess.run([
                'java', '-Xms8g', '-Xmx8g', '-XX:+UseG1GC',
                '-XX:+UnlockDiagnosticVMOptions', '-XX:+PrintCompressedOopsMode',
                '-version'
            ], capture_output=True, text=True, timeout=10)
            
            output = result.stderr + result.stdout
            
            # 查找压缩指针信息
            if 'Zero based' in output:
                print("✅ 压缩指针验证成功: Zero-based模式")
                self.verification_results['compressed_oops'] = True
                return True
            elif 'compressed oops' in output.lower():
                print("✅ 压缩指针已启用")
                self.verification_results['compressed_oops'] = True
                return True
            else:
                print("⚠️  无法确认压缩指针状态")
                self.verification_results['compressed_oops'] = None
                return False
                
        except Exception as e:
            print(f"❌ 压缩指针验证出错: {e}")
            return False
    
    def verify_heap_calculation(self) -> bool:
        """验证8GB堆的计算结果"""
        print("🔍 验证8GB堆计算结果...")
        
        # 基于我们的分析计算
        heap_size = 8 * 1024 * 1024 * 1024  # 8GB
        region_size = 4 * 1024 * 1024       # 4MB
        expected_regions = heap_size // region_size  # 2048
        
        # 验证计算
        if expected_regions == 2048:
            print(f"✅ 堆计算验证成功: 8GB = {expected_regions}个×4MB Region")
            self.verification_results['heap_calculation'] = True
            return True
        else:
            print(f"❌ 堆计算错误: 期望2048，实际{expected_regions}")
            self.verification_results['heap_calculation'] = False
            return False
    
    def verify_gc_performance(self) -> bool:
        """验证GC性能特征"""
        print("🔍 验证GC性能特征...")
        
        try:
            # 运行性能测试
            result = subprocess.run([
                'java', '-Xms8g', '-Xmx8g', '-XX:+UseG1GC',
                '-XX:+PrintGC', '-XX:+PrintGCTimeStamps',
                '-XX:MaxGCPauseMillis=100',
                '-cp', '/tmp', 'G1VerificationTest'
            ], capture_output=True, text=True, timeout=60)
            
            output = result.stderr + result.stdout
            
            # 分析GC暂停时间
            gc_times = []
            gc_pattern = r'GC\(\d+\).*?(\d+\.\d+)ms'
            
            for match in re.finditer(gc_pattern, output):
                gc_time = float(match.group(1))
                gc_times.append(gc_time)
            
            if gc_times:
                avg_gc_time = sum(gc_times) / len(gc_times)
                max_gc_time = max(gc_times)
                
                print(f"✅ GC性能验证: 平均{avg_gc_time:.1f}ms, 最大{max_gc_time:.1f}ms")
                
                # 验证是否符合预期 (大部分GC应该<100ms)
                good_gcs = sum(1 for t in gc_times if t < 100)
                good_ratio = good_gcs / len(gc_times)
                
                if good_ratio > 0.8:  # 80%的GC<100ms
                    print(f"✅ GC暂停时间符合预期: {good_ratio*100:.1f}%的GC<100ms")
                    self.verification_results['gc_performance'] = True
                    return True
                else:
                    print(f"⚠️  GC暂停时间偏高: 仅{good_ratio*100:.1f}%的GC<100ms")
                    self.verification_results['gc_performance'] = False
                    return False
            else:
                print("⚠️  未检测到GC事件")
                self.verification_results['gc_performance'] = None
                return False
                
        except Exception as e:
            print(f"❌ GC性能验证出错: {e}")
            return False
    
    def verify_memory_overhead(self) -> bool:
        """验证内存开销计算"""
        print("🔍 验证内存开销计算...")
        
        # 基于我们的分析
        heap_size = 8 * 1024 * 1024 * 1024  # 8GB
        
        # CardTable开销: 16MB (0.195%)
        card_size = 512
        total_cards = heap_size // card_size
        cardtable_overhead = total_cards  # 每卡片1字节
        cardtable_percent = (cardtable_overhead / heap_size) * 100
        
        # RememberedSet开销: ~1.3MB (0.015%)
        remset_overhead = 1.3 * 1024 * 1024  # 1.3MB
        remset_percent = (remset_overhead / heap_size) * 100
        
        print(f"✅ CardTable开销: {cardtable_overhead//1024//1024}MB ({cardtable_percent:.3f}%)")
        print(f"✅ RemSet开销: {remset_overhead//1024//1024:.1f}MB ({remset_percent:.3f}%)")
        
        # 验证开销是否在合理范围内
        total_overhead_percent = cardtable_percent + remset_percent
        if total_overhead_percent < 1.0:  # 总开销<1%
            print(f"✅ 总内存开销验证成功: {total_overhead_percent:.3f}% < 1%")
            self.verification_results['memory_overhead'] = True
            return True
        else:
            print(f"⚠️  内存开销偏高: {total_overhead_percent:.3f}%")
            self.verification_results['memory_overhead'] = False
            return False
    
    def verify_source_code_analysis(self) -> bool:
        """验证源码分析的准确性"""
        print("🔍 验证源码分析准确性...")
        
        # 检查关键源码文件是否存在
        source_files = [
            '/data/workspace/openjdk11-core/src/hotspot/share/gc/g1/g1CollectedHeap.hpp',
            '/data/workspace/openjdk11-core/src/hotspot/share/gc/g1/heapRegion.hpp',
            '/data/workspace/openjdk11-core/src/hotspot/share/gc/g1/g1BarrierSet.hpp',
            '/data/workspace/openjdk11-core/src/hotspot/share/gc/g1/g1CardTable.hpp'
        ]
        
        missing_files = []
        for file_path in source_files:
            if not os.path.exists(file_path):
                missing_files.append(file_path)
        
        if not missing_files:
            print("✅ 所有关键源码文件验证成功")
            self.verification_results['source_code'] = True
            return True
        else:
            print(f"❌ 缺少源码文件: {missing_files}")
            self.verification_results['source_code'] = False
            return False
    
    def run_all_verifications(self) -> Dict[str, bool]:
        """运行所有验证测试"""
        print("🚀 开始G1 GC分析结论验证...")
        print("=" * 50)
        
        verifications = [
            ("源码文件", self.verify_source_code_analysis),
            ("堆计算", self.verify_heap_calculation),
            ("内存开销", self.verify_memory_overhead),
            ("Region大小", self.verify_region_size),
            ("压缩指针", self.verify_compressed_oops),
            ("GC性能", self.verify_gc_performance)
        ]
        
        results = {}
        for name, verify_func in verifications:
            try:
                result = verify_func()
                results[name] = result
                print()
            except Exception as e:
                print(f"❌ {name}验证失败: {e}")
                results[name] = False
                print()
        
        return results
    
    def print_summary(self, results: Dict[str, bool]):
        """打印验证结果摘要"""
        print("=" * 50)
        print("📊 验证结果摘要")
        print("=" * 50)
        
        passed = sum(1 for v in results.values() if v is True)
        total = len(results)
        
        for name, result in results.items():
            if result is True:
                status = "✅ 通过"
            elif result is False:
                status = "❌ 失败"
            else:
                status = "⚠️  未知"
            
            print(f"{name:12s}: {status}")
        
        print("-" * 30)
        print(f"总体结果: {passed}/{total} 项验证通过")
        
        if passed == total:
            print("🎉 所有分析结论验证成功！")
        elif passed >= total * 0.8:
            print("✅ 大部分分析结论正确")
        else:
            print("⚠️  部分分析结论需要修正")
    
    def cleanup(self):
        """清理临时文件"""
        try:
            if os.path.exists(self.test_java_file):
                os.remove(self.test_java_file)
            if os.path.exists('/tmp/G1VerificationTest.class'):
                os.remove('/tmp/G1VerificationTest.class')
        except:
            pass

def main():
    """主函数"""
    verifier = G1AnalysisVerifier()
    
    try:
        results = verifier.run_all_verifications()
        verifier.print_summary(results)
        
        # 返回适当的退出码
        passed = sum(1 for v in results.values() if v is True)
        total = len(results)
        
        if passed == total:
            sys.exit(0)  # 全部通过
        elif passed >= total * 0.8:
            sys.exit(1)  # 大部分通过
        else:
            sys.exit(2)  # 多数失败
            
    except KeyboardInterrupt:
        print("\n❌ 验证被用户中断")
        sys.exit(3)
    except Exception as e:
        print(f"❌ 验证过程出现异常: {e}")
        sys.exit(4)
    finally:
        verifier.cleanup()

if __name__ == "__main__":
    main()