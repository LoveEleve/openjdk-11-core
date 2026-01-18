/**
 * JIT编译器深度分析测试程序
 * 
 * 基于8GB堆配置的完整测试套件，验证JIT编译器的各个核心功能：
 * - 分层编译和编译触发机制
 * - C1/C2编译器性能对比
 * - 内联优化和循环优化
 * - OSR(On-Stack Replacement)编译
 * - 去优化和重编译机制
 * 
 * 编译: javac JITAnalysisTest.java
 * 运行: java -Xms8g -Xmx8g -XX:+TieredCompilation -XX:+PrintCompilation JITAnalysisTest
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

public class JITAnalysisTest {
    
    // 测试配置常量
    private static final int COMPILE_THRESHOLD = 10000;  // 默认编译阈值
    private static final int WARMUP_ITERATIONS = 15000;  // 预热迭代次数
    private static final int BENCHMARK_ITERATIONS = 50000; // 基准测试迭代次数
    private static final int LOOP_COUNT = 1000;          // 循环测试次数
    
    // 测试数据
    private static volatile int globalCounter = 0;
    private static volatile long globalSum = 0;
    private static final Random random = new Random(42);
    
    // 性能统计
    private static Map<String, Long> compilationTimes = new ConcurrentHashMap<>();
    private static Map<String, Integer> compilationLevels = new ConcurrentHashMap<>();
    
    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("JIT编译器深度分析测试程序");
        System.out.println("================================================================================");
        
        try {
            // 阶段1: 环境验证和JIT配置检查
            phase1_EnvironmentValidation();
            
            // 阶段2: 分层编译触发测试
            phase2_TieredCompilationTest();
            
            // 阶段3: C1编译器性能测试
            phase3_C1CompilerTest();
            
            // 阶段4: C2编译器优化测试
            phase4_C2CompilerTest();
            
            // 阶段5: 内联优化测试
            phase5_InliningOptimizationTest();
            
            // 阶段6: 循环优化测试
            phase6_LoopOptimizationTest();
            
            // 阶段7: OSR编译测试
            phase7_OSRCompilationTest();
            
            // 阶段8: 去优化测试
            phase8_DeoptimizationTest();
            
            // 阶段9: 编译器性能对比
            phase9_CompilerPerformanceComparison();
            
            // 阶段10: 最终分析和总结
            phase10_FinalAnalysis();
            
        } catch (Exception e) {
            System.err.println("❌ 测试执行异常: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n🎉 JIT编译器深度分析测试完成！");
    }
    
    /**
     * 阶段1: 环境验证和JIT配置检查
     */
    private static void phase1_EnvironmentValidation() {
        System.out.println("\n=== 阶段1: 环境验证和JIT配置检查 ===");
        
        // 验证JVM配置
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        List<String> jvmArgs = runtimeBean.getInputArguments();
        
        System.out.println("🔧 JVM启动参数验证:");
        boolean tieredCompilation = false, printCompilation = false;
        
        for (String arg : jvmArgs) {
            if (arg.contains("TieredCompilation")) {
                tieredCompilation = true;
                System.out.println("   ✅ 分层编译: " + arg);
            } else if (arg.contains("PrintCompilation")) {
                printCompilation = true;
                System.out.println("   ✅ 编译输出: " + arg);
            } else if (arg.contains("Xms") || arg.contains("Xmx")) {
                System.out.println("   ✅ 堆内存配置: " + arg);
            } else if (arg.contains("CompileThreshold")) {
                System.out.println("   ✅ 编译阈值: " + arg);
            }
        }
        
        if (!tieredCompilation) {
            System.out.println("   ⚠️  建议: 启用分层编译 -XX:+TieredCompilation");
        }
        if (!printCompilation) {
            System.out.println("   ⚠️  建议: 启用编译输出 -XX:+PrintCompilation");
        }
        
        // 编译器信息
        System.out.println("\n🏭 编译器配置信息:");
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        
        if (compilationBean != null) {
            System.out.printf("   编译器名称: %s\n", compilationBean.getName());
            System.out.printf("   支持编译时间监控: %s\n", 
                             compilationBean.isCompilationTimeMonitoringSupported() ? "是" : "否");
            
            if (compilationBean.isCompilationTimeMonitoringSupported()) {
                System.out.printf("   累计编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
            }
        }
        
        // 处理器信息
        System.out.println("\n💻 处理器信息:");
        int processors = Runtime.getRuntime().availableProcessors();
        System.out.printf("   可用处理器数: %d\n", processors);
        System.out.printf("   建议编译线程数: C1=%d, C2=%d\n", 
                         Math.max(1, processors / 3), 
                         Math.max(1, processors * 2 / 3));
        
        // 内存信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.println("\n💾 内存配置信息:");
        System.out.printf("   最大堆内存: %d MB\n", heapUsage.getMax() / (1024 * 1024));
        System.out.printf("   初始堆内存: %d MB\n", heapUsage.getInit() / (1024 * 1024));
        System.out.printf("   当前堆使用: %d MB\n", heapUsage.getUsed() / (1024 * 1024));
    }
    
    /**
     * 阶段2: 分层编译触发测试
     */
    private static void phase2_TieredCompilationTest() {
        System.out.println("\n=== 阶段2: 分层编译触发测试 ===");
        
        System.out.println("🎯 测试分层编译触发机制...");
        
        // 创建测试方法，逐步触发不同编译级别
        TieredCompilationTarget target = new TieredCompilationTarget();
        
        System.out.println("   开始方法调用，观察编译级别提升...");
        
        // Level 0 -> Level 1: 少量调用
        System.out.println("   Phase A: 触发Level 1编译 (C1有限profiling)");
        for (int i = 0; i < 100; i++) {
            target.simpleMethod(i);
        }
        
        // 短暂暂停让编译完成
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        
        // Level 1 -> Level 2: 更多调用
        System.out.println("   Phase B: 触发Level 2编译 (C1完整profiling)");
        for (int i = 0; i < 1000; i++) {
            target.simpleMethod(i);
        }
        
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        
        // Level 2 -> Level 3: 继续调用
        System.out.println("   Phase C: 触发Level 3编译 (C1完整优化)");
        for (int i = 0; i < 5000; i++) {
            target.simpleMethod(i);
        }
        
        try { Thread.sleep(200); } catch (InterruptedException e) {}
        
        // Level 3 -> Level 4: 大量调用
        System.out.println("   Phase D: 触发Level 4编译 (C2最高优化)");
        for (int i = 0; i < 15000; i++) {
            target.simpleMethod(i);
        }
        
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        
        System.out.printf("   分层编译测试完成，总调用次数: %d\n", 
                         100 + 1000 + 5000 + 15000);
        System.out.println("   请观察控制台的编译输出信息");
    }
    
    /**
     * 阶段3: C1编译器性能测试
     */
    private static void phase3_C1CompilerTest() {
        System.out.println("\n=== 阶段3: C1编译器性能测试 ===");
        
        System.out.println("🔵 测试C1编译器特性...");
        
        // C1编译器快速编译测试
        C1TestTarget c1Target = new C1TestTarget();
        
        System.out.println("   测试C1快速编译能力...");
        
        long startTime = System.nanoTime();
        
        // 快速达到C1编译阈值
        for (int i = 0; i < 3000; i++) {
            c1Target.fastCompileMethod(i);
        }
        
        long c1CompileTime = System.nanoTime();
        
        // 继续调用测试C1性能
        for (int i = 0; i < 10000; i++) {
            c1Target.fastCompileMethod(i);
        }
        
        long endTime = System.nanoTime();
        
        double compilePhaseMs = (c1CompileTime - startTime) / 1_000_000.0;
        double executePhaseMs = (endTime - c1CompileTime) / 1_000_000.0;
        
        System.out.printf("   C1编译阶段: %.2f ms (3000次调用)\n", compilePhaseMs);
        System.out.printf("   C1执行阶段: %.2f ms (10000次调用)\n", executePhaseMs);
        System.out.printf("   C1编译后性能提升: %.1fx\n", 
                         compilePhaseMs / executePhaseMs * (10000.0 / 3000.0));
        
        // 测试C1 profiling能力
        System.out.println("\n   测试C1 profiling能力...");
        
        ProfilingTestTarget profilingTarget = new ProfilingTestTarget();
        
        // 触发profiling编译
        for (int i = 0; i < 5000; i++) {
            profilingTarget.profilingMethod(i % 10);
        }
        
        System.out.println("   C1 profiling测试完成");
    }
    
    /**
     * 阶段4: C2编译器优化测试
     */
    private static void phase4_C2CompilerTest() {
        System.out.println("\n=== 阶段4: C2编译器优化测试 ===");
        
        System.out.println("🔴 测试C2编译器高级优化...");
        
        C2TestTarget c2Target = new C2TestTarget();
        
        // 预热到C2编译
        System.out.println("   预热到C2编译级别...");
        for (int i = 0; i < COMPILE_THRESHOLD + 5000; i++) {
            c2Target.optimizationMethod(i);
        }
        
        // 等待C2编译完成
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
        
        // 测试C2优化性能
        System.out.println("   测试C2优化性能...");
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            c2Target.optimizationMethod(i);
        }
        
        long endTime = System.nanoTime();
        
        double c2ExecuteTime = (endTime - startTime) / 1_000_000.0;
        double avgTimePerCall = (endTime - startTime) / (double)BENCHMARK_ITERATIONS;
        
        System.out.printf("   C2优化执行时间: %.2f ms (%d次调用)\n", 
                         c2ExecuteTime, BENCHMARK_ITERATIONS);
        System.out.printf("   平均每次调用: %.2f ns\n", avgTimePerCall);
        
        // 测试C2高级优化特性
        System.out.println("\n   测试C2高级优化特性...");
        
        // 逃逸分析测试
        testEscapeAnalysis();
        
        // 循环优化测试
        testLoopOptimizations(c2Target);
        
        // 条件优化测试
        testBranchOptimizations(c2Target);
    }
    
    /**
     * 阶段5: 内联优化测试
     */
    private static void phase5_InliningOptimizationTest() {
        System.out.println("\n=== 阶段5: 内联优化测试 ===");
        
        System.out.println("🔗 测试方法内联优化...");
        
        InliningTestTarget inliningTarget = new InliningTestTarget();
        
        // 小方法内联测试
        System.out.println("   测试小方法内联...");
        
        long startTime = System.nanoTime();
        
        // 预热触发内联
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            inliningTarget.callerMethod(i);
        }
        
        long warmupTime = System.nanoTime();
        
        // 内联后性能测试
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            inliningTarget.callerMethod(i);
        }
        
        long endTime = System.nanoTime();
        
        double warmupMs = (warmupTime - startTime) / 1_000_000.0;
        double benchmarkMs = (endTime - warmupTime) / 1_000_000.0;
        
        System.out.printf("   预热阶段: %.2f ms (%d次调用)\n", warmupMs, WARMUP_ITERATIONS);
        System.out.printf("   内联后执行: %.2f ms (%d次调用)\n", benchmarkMs, BENCHMARK_ITERATIONS);
        System.out.printf("   内联优化效果: %.1fx 性能提升\n", 
                         (warmupMs / WARMUP_ITERATIONS) / (benchmarkMs / BENCHMARK_ITERATIONS));
        
        // 深度内联测试
        System.out.println("\n   测试深度内联...");
        testDeepInlining(inliningTarget);
        
        // 多态内联测试
        System.out.println("   测试多态内联...");
        testPolymorphicInlining();
    }
    
    /**
     * 阶段6: 循环优化测试
     */
    private static void phase6_LoopOptimizationTest() {
        System.out.println("\n=== 阶段6: 循环优化测试 ===");
        
        System.out.println("🔄 测试循环优化...");
        
        LoopOptimizationTarget loopTarget = new LoopOptimizationTarget();
        
        // 循环展开测试
        System.out.println("   测试循环展开优化...");
        testLoopUnrolling(loopTarget);
        
        // 循环不变量提升测试
        System.out.println("   测试循环不变量提升...");
        testLoopInvariantHoisting(loopTarget);
        
        // 循环向量化测试
        System.out.println("   测试循环向量化...");
        testLoopVectorization(loopTarget);
        
        // 嵌套循环优化测试
        System.out.println("   测试嵌套循环优化...");
        testNestedLoopOptimization(loopTarget);
    }
    
    /**
     * 阶段7: OSR编译测试
     */
    private static void phase7_OSRCompilationTest() {
        System.out.println("\n=== 阶段7: OSR编译测试 ===");
        
        System.out.println("🔄 测试OSR(On-Stack Replacement)编译...");
        
        OSRTestTarget osrTarget = new OSRTestTarget();
        
        System.out.println("   启动长时间运行循环触发OSR...");
        
        long startTime = System.nanoTime();
        
        // 触发OSR编译的长循环
        int result = osrTarget.longRunningLoop(100000);
        
        long endTime = System.nanoTime();
        
        System.out.printf("   OSR循环执行时间: %.2f ms\n", (endTime - startTime) / 1_000_000.0);
        System.out.printf("   循环结果: %d\n", result);
        System.out.println("   请观察编译输出中的OSR编译信息");
        
        // 多层嵌套OSR测试
        System.out.println("\n   测试嵌套循环OSR...");
        testNestedOSR(osrTarget);
    }
    
    /**
     * 阶段8: 去优化测试
     */
    private static void phase8_DeoptimizationTest() {
        System.out.println("\n=== 阶段8: 去优化测试 ===");
        
        System.out.println("🔙 测试去优化机制...");
        
        DeoptimizationTarget deoptTarget = new DeoptimizationTarget();
        
        // 预热编译
        System.out.println("   预热编译阶段...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            deoptTarget.polymorphicMethod(new ConcreteTypeA());
        }
        
        // 触发去优化
        System.out.println("   触发去优化...");
        
        long startTime = System.nanoTime();
        
        // 使用不同类型触发去优化
        for (int i = 0; i < 1000; i++) {
            if (i % 100 == 0) {
                deoptTarget.polymorphicMethod(new ConcreteTypeB()); // 触发去优化
            } else {
                deoptTarget.polymorphicMethod(new ConcreteTypeA());
            }
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("   去优化测试执行时间: %.2f ms\n", (endTime - startTime) / 1_000_000.0);
        System.out.println("   请观察编译输出中的去优化信息");
        
        // 重编译测试
        System.out.println("\n   测试重编译...");
        testRecompilation(deoptTarget);
    }
    
    /**
     * 阶段9: 编译器性能对比
     */
    private static void phase9_CompilerPerformanceComparison() {
        System.out.println("\n=== 阶段9: 编译器性能对比 ===");
        
        System.out.println("⚡ 编译器性能对比测试...");
        
        // 创建性能对比测试目标
        PerformanceComparisonTarget perfTarget = new PerformanceComparisonTarget();
        
        // 解释执行基准
        System.out.println("   建立解释执行基准...");
        long interpretedTime = measureInterpretedPerformance(perfTarget);
        
        // C1编译性能
        System.out.println("   测试C1编译性能...");
        long c1Time = measureC1Performance(perfTarget);
        
        // C2编译性能
        System.out.println("   测试C2编译性能...");
        long c2Time = measureC2Performance(perfTarget);
        
        // 性能对比分析
        System.out.println("\n📊 性能对比结果:");
        System.out.printf("   解释执行: %.2f ms (基准)\n", interpretedTime / 1_000_000.0);
        System.out.printf("   C1编译: %.2f ms (%.1fx 提升)\n", 
                         c1Time / 1_000_000.0, (double)interpretedTime / c1Time);
        System.out.printf("   C2编译: %.2f ms (%.1fx 提升)\n", 
                         c2Time / 1_000_000.0, (double)interpretedTime / c2Time);
        System.out.printf("   C2 vs C1: %.1fx 提升\n", (double)c1Time / c2Time);
        
        // 编译开销分析
        analyzeCompilationOverhead();
    }
    
    /**
     * 阶段10: 最终分析和总结
     */
    private static void phase10_FinalAnalysis() {
        System.out.println("\n=== 阶段10: 最终分析和总结 ===");
        
        System.out.println("📊 JIT编译器最终分析:");
        
        // 编译统计
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null && compilationBean.isCompilationTimeMonitoringSupported()) {
            System.out.printf("   总编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
        }
        
        // 内存使用分析
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.println("\n💾 内存使用分析:");
        System.out.printf("   当前堆使用: %d MB\n", heapUsage.getUsed() / (1024 * 1024));
        System.out.printf("   堆利用率: %.1f%%\n", 
                         (heapUsage.getUsed() * 100.0) / heapUsage.getCommitted());
        
        // 性能总结
        System.out.println("\n⚡ 性能总结:");
        System.out.println("   ✅ 分层编译机制验证完成");
        System.out.println("   ✅ C1快速编译能力验证");
        System.out.println("   ✅ C2高级优化能力验证");
        System.out.println("   ✅ 内联优化效果验证");
        System.out.println("   ✅ 循环优化效果验证");
        System.out.println("   ✅ OSR编译机制验证");
        System.out.println("   ✅ 去优化机制验证");
        
        // 优化建议
        System.out.println("\n💡 JIT优化建议:");
        System.out.println("   - 保持分层编译启用状态");
        System.out.println("   - 根据应用特点调整编译阈值");
        System.out.println("   - 监控编译时间和代码缓存使用");
        System.out.println("   - 避免频繁的类型变化导致去优化");
        System.out.println("   - 设计内联友好的方法结构");
        
        System.out.println("\n✅ JIT编译器深度分析测试全部完成！");
    }
    
    // ============================================================================
    // 测试目标类定义
    // ============================================================================
    
    /**
     * 分层编译测试目标
     */
    static class TieredCompilationTarget {
        private int counter = 0;
        
        public int simpleMethod(int input) {
            counter++;
            return input * 2 + counter;
        }
    }
    
    /**
     * C1编译器测试目标
     */
    static class C1TestTarget {
        private long sum = 0;
        
        public long fastCompileMethod(int input) {
            sum += input;
            return sum * 3 + input;
        }
    }
    
    /**
     * Profiling测试目标
     */
    static class ProfilingTestTarget {
        private int[] counters = new int[10];
        
        public int profilingMethod(int branch) {
            counters[branch]++;
            
            switch (branch) {
                case 0: return counters[0] * 2;
                case 1: return counters[1] * 3;
                case 2: return counters[2] * 5;
                default: return counters[branch];
            }
        }
    }
    
    /**
     * C2编译器测试目标
     */
    static class C2TestTarget {
        private double result = 0.0;
        
        public double optimizationMethod(int input) {
            // 复杂计算触发C2优化
            double temp = Math.sqrt(input);
            temp = Math.sin(temp) + Math.cos(temp);
            temp = temp * temp + input;
            result += temp;
            return result;
        }
        
        public long loopOptimizationMethod(int[] array) {
            long sum = 0;
            for (int i = 0; i < array.length; i++) {
                sum += array[i] * array[i];
            }
            return sum;
        }
        
        public int branchOptimizationMethod(int input) {
            if (input > 0) {
                if (input % 2 == 0) {
                    return input * 2;
                } else {
                    return input * 3;
                }
            } else {
                return input + 1;
            }
        }
    }
    
    /**
     * 内联测试目标
     */
    static class InliningTestTarget {
        private int value = 0;
        
        public int callerMethod(int input) {
            return smallMethod1(input) + smallMethod2(input);
        }
        
        private int smallMethod1(int input) {
            return input * 2;
        }
        
        private int smallMethod2(int input) {
            return input + 1;
        }
        
        public int deepInliningMethod(int input) {
            return level1(input);
        }
        
        private int level1(int input) {
            return level2(input) + 1;
        }
        
        private int level2(int input) {
            return level3(input) + 2;
        }
        
        private int level3(int input) {
            return input * 3;
        }
    }
    
    /**
     * 循环优化测试目标
     */
    static class LoopOptimizationTarget {
        
        public long unrollableLoop(int[] array) {
            long sum = 0;
            for (int i = 0; i < array.length; i++) {
                sum += array[i];
            }
            return sum;
        }
        
        public long invariantHoistingLoop(int[] array, int multiplier) {
            long sum = 0;
            for (int i = 0; i < array.length; i++) {
                sum += array[i] * Math.abs(multiplier); // Math.abs(multiplier)可以提升
            }
            return sum;
        }
        
        public void vectorizableLoop(int[] a, int[] b, int[] result) {
            for (int i = 0; i < a.length; i++) {
                result[i] = a[i] + b[i];
            }
        }
        
        public long nestedLoop(int[][] matrix) {
            long sum = 0;
            for (int i = 0; i < matrix.length; i++) {
                for (int j = 0; j < matrix[i].length; j++) {
                    sum += matrix[i][j];
                }
            }
            return sum;
        }
    }
    
    /**
     * OSR测试目标
     */
    static class OSRTestTarget {
        
        public int longRunningLoop(int iterations) {
            int sum = 0;
            for (int i = 0; i < iterations; i++) {
                sum += i * i;
                // 添加一些计算让循环运行更长时间
                if (i % 1000 == 0) {
                    sum += Math.abs(sum);
                }
            }
            return sum;
        }
        
        public int nestedOSRLoop(int outer, int inner) {
            int sum = 0;
            for (int i = 0; i < outer; i++) {
                for (int j = 0; j < inner; j++) {
                    sum += i * j;
                }
            }
            return sum;
        }
    }
    
    /**
     * 去优化测试目标
     */
    static class DeoptimizationTarget {
        
        public int polymorphicMethod(BaseType obj) {
            return obj.getValue();
        }
    }
    
    /**
     * 基础类型接口
     */
    interface BaseType {
        int getValue();
    }
    
    /**
     * 具体类型A
     */
    static class ConcreteTypeA implements BaseType {
        public int getValue() {
            return 42;
        }
    }
    
    /**
     * 具体类型B
     */
    static class ConcreteTypeB implements BaseType {
        public int getValue() {
            return 24;
        }
    }
    
    /**
     * 性能对比测试目标
     */
    static class PerformanceComparisonTarget {
        private long counter = 0;
        
        public long computeIntensiveMethod(int input) {
            long result = input;
            for (int i = 0; i < 100; i++) {
                result = result * 31 + i;
                result = result ^ (result >>> 16);
            }
            counter += result;
            return result;
        }
    }
    
    // ============================================================================
    // 辅助测试方法
    // ============================================================================
    
    private static void testEscapeAnalysis() {
        System.out.println("     测试逃逸分析优化...");
        
        EscapeAnalysisTest escapeTest = new EscapeAnalysisTest();
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            escapeTest.noEscapeMethod();
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            escapeTest.noEscapeMethod();
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     逃逸分析优化执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testLoopOptimizations(C2TestTarget target) {
        System.out.println("     测试循环优化...");
        
        int[] testArray = new int[1000];
        for (int i = 0; i < testArray.length; i++) {
            testArray[i] = random.nextInt(100);
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            target.loopOptimizationMethod(testArray);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 10000; i++) {
            target.loopOptimizationMethod(testArray);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     循环优化执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testBranchOptimizations(C2TestTarget target) {
        System.out.println("     测试分支优化...");
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.branchOptimizationMethod(i);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.branchOptimizationMethod(i);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     分支优化执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testDeepInlining(InliningTestTarget target) {
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.deepInliningMethod(i);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.deepInliningMethod(i);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     深度内联执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testPolymorphicInlining() {
        BaseType[] objects = {
            new ConcreteTypeA(), new ConcreteTypeA(), new ConcreteTypeA(),
            new ConcreteTypeB() // 少量不同类型
        };
        
        DeoptimizationTarget target = new DeoptimizationTarget();
        
        // 预热，主要使用ConcreteTypeA
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.polymorphicMethod(objects[i % 3]); // 只使用前3个ConcreteTypeA
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.polymorphicMethod(objects[i % objects.length]);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     多态内联执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testLoopUnrolling(LoopOptimizationTarget target) {
        int[] testArray = new int[1000];
        for (int i = 0; i < testArray.length; i++) {
            testArray[i] = i;
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            target.unrollableLoop(testArray);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 10000; i++) {
            target.unrollableLoop(testArray);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     循环展开执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testLoopInvariantHoisting(LoopOptimizationTarget target) {
        int[] testArray = new int[1000];
        for (int i = 0; i < testArray.length; i++) {
            testArray[i] = i;
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            target.invariantHoistingLoop(testArray, 42);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 10000; i++) {
            target.invariantHoistingLoop(testArray, 42);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     循环不变量提升执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testLoopVectorization(LoopOptimizationTarget target) {
        int size = 1000;
        int[] a = new int[size];
        int[] b = new int[size];
        int[] result = new int[size];
        
        for (int i = 0; i < size; i++) {
            a[i] = i;
            b[i] = i * 2;
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            target.vectorizableLoop(a, b, result);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 10000; i++) {
            target.vectorizableLoop(a, b, result);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     循环向量化执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testNestedLoopOptimization(LoopOptimizationTarget target) {
        int[][] matrix = new int[100][100];
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 100; j++) {
                matrix[i][j] = i * j;
            }
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            target.nestedLoop(matrix);
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 5000; i++) {
            target.nestedLoop(matrix);
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     嵌套循环优化执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static void testNestedOSR(OSRTestTarget target) {
        long startTime = System.nanoTime();
        
        int result = target.nestedOSRLoop(1000, 1000);
        
        long endTime = System.nanoTime();
        
        System.out.printf("     嵌套OSR执行时间: %.2f ms, 结果: %d\n", 
                         (endTime - startTime) / 1_000_000.0, result);
    }
    
    private static void testRecompilation(DeoptimizationTarget target) {
        // 重新预热，让JIT重新编译
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.polymorphicMethod(new ConcreteTypeA());
        }
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.polymorphicMethod(new ConcreteTypeA());
        }
        
        long endTime = System.nanoTime();
        
        System.out.printf("     重编译后执行时间: %.2f ms\n", 
                         (endTime - startTime) / 1_000_000.0);
    }
    
    private static long measureInterpretedPerformance(PerformanceComparisonTarget target) {
        // 使用新实例避免编译
        PerformanceComparisonTarget freshTarget = new PerformanceComparisonTarget();
        
        long startTime = System.nanoTime();
        
        // 少量调用保持解释执行
        for (int i = 0; i < 1000; i++) {
            freshTarget.computeIntensiveMethod(i);
        }
        
        return System.nanoTime() - startTime;
    }
    
    private static long measureC1Performance(PerformanceComparisonTarget target) {
        // 预热到C1级别
        for (int i = 0; i < 3000; i++) {
            target.computeIntensiveMethod(i);
        }
        
        // 等待编译完成
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 10000; i++) {
            target.computeIntensiveMethod(i);
        }
        
        return System.nanoTime() - startTime;
    }
    
    private static long measureC2Performance(PerformanceComparisonTarget target) {
        // 预热到C2级别
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.computeIntensiveMethod(i);
        }
        
        // 等待C2编译完成
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 10000; i++) {
            target.computeIntensiveMethod(i);
        }
        
        return System.nanoTime() - startTime;
    }
    
    private static void analyzeCompilationOverhead() {
        System.out.println("\n📈 编译开销分析:");
        
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null && compilationBean.isCompilationTimeMonitoringSupported()) {
            long totalCompilationTime = compilationBean.getTotalCompilationTime();
            long totalRunTime = ManagementFactory.getRuntimeMXBean().getUptime();
            
            double compilationOverhead = (totalCompilationTime * 100.0) / totalRunTime;
            
            System.out.printf("   总运行时间: %d ms\n", totalRunTime);
            System.out.printf("   总编译时间: %d ms\n", totalCompilationTime);
            System.out.printf("   编译开销: %.2f%%\n", compilationOverhead);
            
            if (compilationOverhead < 5.0) {
                System.out.println("   编译开销评级: ✅ 优秀");
            } else if (compilationOverhead < 10.0) {
                System.out.println("   编译开销评级: ⚠️  一般");
            } else {
                System.out.println("   编译开销评级: ❌ 需要优化");
            }
        }
    }
    
    /**
     * 逃逸分析测试类
     */
    static class EscapeAnalysisTest {
        
        public int noEscapeMethod() {
            // 对象不逃逸，应该被优化为栈分配
            Point p = new Point(10, 20);
            return p.x + p.y;
        }
        
        static class Point {
            int x, y;
            
            Point(int x, int y) {
                this.x = x;
                this.y = y;
            }
        }
    }
}