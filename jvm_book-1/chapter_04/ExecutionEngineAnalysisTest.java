/**
 * 字节码执行引擎深度分析测试程序 - 深度增强版
 * 
 * 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 标准配置
 * 全面测试解释器、编译器、OSR机制、方法调用性能等关键特性
 * 
 * 编译: javac ExecutionEngineAnalysisTest.java
 * 运行: java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintCompilation 
 *           -XX:+UnlockDiagnosticVMOptions -XX:+TraceClassLoading
 *           -XX:CompileThreshold=10000 -XX:+TieredCompilation
 *           ExecutionEngineAnalysisTest
 */

import java.lang.management.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.management.*;

public class ExecutionEngineAnalysisTest {
    
    // 测试配置
    private static final int WARMUP_ITERATIONS = 5000;
    private static final int BENCHMARK_ITERATIONS = 50000;
    private static final int OSR_LOOP_COUNT = 100000;
    private static final int CONCURRENT_THREADS = 8;
    
    // 性能统计
    private static final AtomicLong totalExecutionTime = new AtomicLong(0);
    private static final AtomicLong totalMethodCalls = new AtomicLong(0);
    private static final AtomicLong compilationEvents = new AtomicLong(0);
    
    // JMX Beans
    private static CompilationMXBean compilationBean;
    private static RuntimeMXBean runtimeBean;
    private static MemoryMXBean memoryBean;
    
    // 测试数据
    private static volatile int globalCounter = 0;
    private static volatile long globalSum = 0;
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("    字节码执行引擎深度分析测试程序");
        System.out.println("========================================");
        
        try {
            // 初始化JMX监控
            initializeJMXBeans();
            
            // 阶段1：环境验证
            phase1_EnvironmentVerification();
            
            // 阶段2：解释器执行性能测试
            phase2_InterpreterPerformanceTest();
            
            // 阶段3：方法调用机制测试
            phase3_MethodInvocationTest();
            
            // 阶段4：编译触发机制测试
            phase4_CompilationTriggerTest();
            
            // 阶段5：OSR机制测试
            phase5_OSRMechanismTest();
            
            // 阶段6：内联优化测试
            phase6_InliningOptimizationTest();
            
            // 阶段7：分支预测测试
            phase7_BranchPredictionTest();
            
            // 阶段8：异常处理性能测试
            phase8_ExceptionHandlingTest();
            
            // 阶段9：并发执行测试
            phase9_ConcurrentExecutionTest();
            
            // 阶段10：最终分析和建议
            phase10_FinalAnalysisAndRecommendations();
            
        } catch (Exception e) {
            System.err.println("测试执行出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 阶段1：环境验证
     */
    private static void phase1_EnvironmentVerification() {
        System.out.println("\n=== 阶段1：环境验证 ===");
        
        // JVM基本信息
        System.out.println("JVM执行引擎信息:");
        System.out.println("- JVM名称: " + runtimeBean.getVmName());
        System.out.println("- JVM版本: " + runtimeBean.getVmVersion());
        System.out.println("- 启动时间: " + runtimeBean.getUptime() + " ms");
        
        // 编译器配置验证
        System.out.println("\n编译器配置:");
        if (compilationBean != null) {
            System.out.println("- JIT编译器: " + compilationBean.getName());
            System.out.println("- 编译支持: ✅ 启用");
            System.out.println("- 累计编译时间: " + compilationBean.getTotalCompilationTime() + " ms");
        } else {
            System.out.println("- JIT编译器: ❌ 禁用或不可用");
        }
        
        // 运行时参数检查
        System.out.println("\n关键JVM参数:");
        List<String> inputArgs = runtimeBean.getInputArguments();
        boolean tieredCompilation = false;
        boolean printCompilation = false;
        String compileThreshold = "默认";
        
        for (String arg : inputArgs) {
            if (arg.contains("TieredCompilation")) {
                tieredCompilation = arg.contains("+");
            } else if (arg.contains("PrintCompilation")) {
                printCompilation = arg.contains("+");
            } else if (arg.startsWith("-XX:CompileThreshold=")) {
                compileThreshold = arg.substring(21);
            }
        }
        
        System.out.println("- 分层编译: " + (tieredCompilation ? "✅ 启用" : "❌ 禁用"));
        System.out.println("- 编译输出: " + (printCompilation ? "✅ 启用" : "❌ 禁用"));
        System.out.println("- 编译阈值: " + compileThreshold);
        
        // 内存配置
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("\n内存配置:");
        System.out.printf("- 堆内存: %.2f GB / %.2f GB\n", 
            heapUsage.getUsed() / (1024.0 * 1024.0 * 1024.0),
            heapUsage.getMax() / (1024.0 * 1024.0 * 1024.0));
        
        System.out.println("✅ 环境验证完成");
    }
    
    /**
     * 阶段2：解释器执行性能测试
     */
    private static void phase2_InterpreterPerformanceTest() {
        System.out.println("\n=== 阶段2：解释器执行性能测试 ===");
        
        System.out.println("测试不同类型的字节码指令性能...");
        
        // 算术运算指令测试
        System.out.println("\n1. 算术运算指令测试:");
        long startTime = System.nanoTime();
        int sum = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sum += i * 2 + 1; // iadd, imul, iconst
        }
        long arithmeticTime = System.nanoTime() - startTime;
        System.out.printf("- 算术运算: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS, arithmeticTime / 1_000_000.0, 
            arithmeticTime / (double)BENCHMARK_ITERATIONS);
        
        // 数组访问指令测试
        System.out.println("\n2. 数组访问指令测试:");
        int[] array = new int[1000];
        startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            array[i % 1000] = i; // iastore
            sum += array[i % 1000]; // iaload
        }
        long arrayTime = System.nanoTime() - startTime;
        System.out.printf("- 数组访问: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS * 2, arrayTime / 1_000_000.0, 
            arrayTime / (double)(BENCHMARK_ITERATIONS * 2));
        
        // 局部变量访问指令测试
        System.out.println("\n3. 局部变量访问指令测试:");
        startTime = System.nanoTime();
        testLocalVariableAccess(BENCHMARK_ITERATIONS);
        long localVarTime = System.nanoTime() - startTime;
        System.out.printf("- 局部变量访问: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS * 4, localVarTime / 1_000_000.0, 
            localVarTime / (double)(BENCHMARK_ITERATIONS * 4));
        
        // 控制流指令测试
        System.out.println("\n4. 控制流指令测试:");
        startTime = System.nanoTime();
        int branchResult = testBranchInstructions(BENCHMARK_ITERATIONS);
        long branchTime = System.nanoTime() - startTime;
        System.out.printf("- 控制流指令: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS, branchTime / 1_000_000.0, 
            branchTime / (double)BENCHMARK_ITERATIONS);
        
        // 性能对比分析
        System.out.println("\n解释器性能分析:");
        System.out.printf("- 算术运算效率: %.2f MOPS\n", 
            BENCHMARK_ITERATIONS / (arithmeticTime / 1_000_000.0) / 1000);
        System.out.printf("- 数组访问效率: %.2f MOPS\n", 
            (BENCHMARK_ITERATIONS * 2) / (arrayTime / 1_000_000.0) / 1000);
        System.out.printf("- 局部变量效率: %.2f MOPS\n", 
            (BENCHMARK_ITERATIONS * 4) / (localVarTime / 1_000_000.0) / 1000);
        System.out.printf("- 控制流效率: %.2f MOPS\n", 
            BENCHMARK_ITERATIONS / (branchTime / 1_000_000.0) / 1000);
        
        System.out.println("✅ 解释器执行性能测试完成");
    }
    
    /**
     * 阶段3：方法调用机制测试
     */
    private static void phase3_MethodInvocationTest() {
        System.out.println("\n=== 阶段3：方法调用机制测试 ===");
        
        // 静态方法调用测试
        System.out.println("1. 静态方法调用测试:");
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            staticMethod(i);
        }
        long staticTime = System.nanoTime() - startTime;
        System.out.printf("- 静态方法调用: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS, staticTime / 1_000_000.0, 
            staticTime / (double)BENCHMARK_ITERATIONS);
        
        // 实例方法调用测试
        System.out.println("\n2. 实例方法调用测试:");
        ExecutionEngineAnalysisTest instance = new ExecutionEngineAnalysisTest();
        startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            instance.instanceMethod(i);
        }
        long instanceTime = System.nanoTime() - startTime;
        System.out.printf("- 实例方法调用: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS, instanceTime / 1_000_000.0, 
            instanceTime / (double)BENCHMARK_ITERATIONS);
        
        // 虚方法调用测试
        System.out.println("\n3. 虚方法调用测试:");
        BaseClass[] objects = {new DerivedClass1(), new DerivedClass2()};
        startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            objects[i % 2].virtualMethod(i);
        }
        long virtualTime = System.nanoTime() - startTime;
        System.out.printf("- 虚方法调用: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS, virtualTime / 1_000_000.0, 
            virtualTime / (double)BENCHMARK_ITERATIONS);
        
        // 接口方法调用测试
        System.out.println("\n4. 接口方法调用测试:");
        TestInterface[] interfaces = {new InterfaceImpl1(), new InterfaceImpl2()};
        startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            interfaces[i % 2].interfaceMethod(i);
        }
        long interfaceTime = System.nanoTime() - startTime;
        System.out.printf("- 接口方法调用: %d次, %.2f ms, %.2f ns/次\n", 
            BENCHMARK_ITERATIONS, interfaceTime / 1_000_000.0, 
            interfaceTime / (double)BENCHMARK_ITERATIONS);
        
        // 反射方法调用测试
        System.out.println("\n5. 反射方法调用测试:");
        try {
            Method method = ExecutionEngineAnalysisTest.class.getDeclaredMethod("staticMethod", int.class);
            startTime = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) { // 减少次数，反射较慢
                method.invoke(null, i);
            }
            long reflectionTime = System.nanoTime() - startTime;
            System.out.printf("- 反射方法调用: %d次, %.2f ms, %.2f ns/次\n", 
                BENCHMARK_ITERATIONS / 10, reflectionTime / 1_000_000.0, 
                reflectionTime / (double)(BENCHMARK_ITERATIONS / 10));
        } catch (Exception e) {
            System.err.println("反射测试失败: " + e.getMessage());
        }
        
        // 方法调用性能对比
        System.out.println("\n方法调用性能对比:");
        double staticPerf = staticTime / (double)BENCHMARK_ITERATIONS;
        System.out.printf("- 静态方法: %.2f ns (基准)\n", staticPerf);
        System.out.printf("- 实例方法: %.2f ns (%.2fx)\n", 
            instanceTime / (double)BENCHMARK_ITERATIONS, 
            (instanceTime / (double)BENCHMARK_ITERATIONS) / staticPerf);
        System.out.printf("- 虚方法: %.2f ns (%.2fx)\n", 
            virtualTime / (double)BENCHMARK_ITERATIONS,
            (virtualTime / (double)BENCHMARK_ITERATIONS) / staticPerf);
        System.out.printf("- 接口方法: %.2f ns (%.2fx)\n", 
            interfaceTime / (double)BENCHMARK_ITERATIONS,
            (interfaceTime / (double)BENCHMARK_ITERATIONS) / staticPerf);
        
        System.out.println("✅ 方法调用机制测试完成");
    }
    
    /**
     * 阶段4：编译触发机制测试
     */
    private static void phase4_CompilationTriggerTest() {
        System.out.println("\n=== 阶段4：编译触发机制测试 ===");
        
        long initialCompilationTime = compilationBean != null ? 
            compilationBean.getTotalCompilationTime() : 0;
        
        System.out.println("测试方法编译触发...");
        
        // 创建多个测试方法来触发编译
        CompilationTestMethods testMethods = new CompilationTestMethods();
        
        // 预热阶段 - 触发编译
        System.out.println("\n1. 预热阶段 (触发编译):");
        long warmupStart = System.nanoTime();
        
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            testMethods.hotMethod1(i);
            testMethods.hotMethod2(i);
            testMethods.hotMethod3(i);
        }
        
        long warmupTime = System.nanoTime() - warmupStart;
        System.out.printf("- 预热执行时间: %.2f ms\n", warmupTime / 1_000_000.0);
        System.out.printf("- 平均方法执行时间: %.2f ns\n", 
            warmupTime / (double)(WARMUP_ITERATIONS * 3));
        
        // 等待编译完成
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 编译后性能测试
        System.out.println("\n2. 编译后性能测试:");
        long compiledStart = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            testMethods.hotMethod1(i);
            testMethods.hotMethod2(i);
            testMethods.hotMethod3(i);
        }
        
        long compiledTime = System.nanoTime() - compiledStart;
        System.out.printf("- 编译后执行时间: %.2f ms\n", compiledTime / 1_000_000.0);
        System.out.printf("- 平均方法执行时间: %.2f ns\n", 
            compiledTime / (double)(BENCHMARK_ITERATIONS * 3));
        
        // 性能提升分析
        double warmupPerCall = warmupTime / (double)(WARMUP_ITERATIONS * 3);
        double compiledPerCall = compiledTime / (double)(BENCHMARK_ITERATIONS * 3);
        double speedup = warmupPerCall / compiledPerCall;
        
        System.out.println("\n编译优化效果:");
        System.out.printf("- 解释执行: %.2f ns/调用\n", warmupPerCall);
        System.out.printf("- 编译执行: %.2f ns/调用\n", compiledPerCall);
        System.out.printf("- 性能提升: %.2fx\n", speedup);
        
        if (speedup > 5.0) {
            System.out.println("- 编译效果: ⭐⭐⭐⭐⭐ 优秀");
        } else if (speedup > 3.0) {
            System.out.println("- 编译效果: ⭐⭐⭐⭐ 良好");
        } else if (speedup > 2.0) {
            System.out.println("- 编译效果: ⭐⭐⭐ 一般");
        } else {
            System.out.println("- 编译效果: ⭐⭐ 需要优化");
        }
        
        // 编译统计
        if (compilationBean != null) {
            long finalCompilationTime = compilationBean.getTotalCompilationTime();
            long compilationDelta = finalCompilationTime - initialCompilationTime;
            System.out.println("\n编译统计:");
            System.out.printf("- 新增编译时间: %d ms\n", compilationDelta);
            System.out.printf("- 编译开销: %.2f%%\n", 
                (double)compilationDelta / (warmupTime / 1_000_000.0) * 100);
        }
        
        System.out.println("✅ 编译触发机制测试完成");
    }
    
    /**
     * 阶段5：OSR机制测试
     */
    private static void phase5_OSRMechanismTest() {
        System.out.println("\n=== 阶段5：OSR机制测试 ===");
        
        System.out.println("测试On-Stack Replacement机制...");
        
        // OSR触发测试 - 长循环
        System.out.println("\n1. OSR触发测试:");
        long osrStart = System.nanoTime();
        long result = osrTriggerMethod(OSR_LOOP_COUNT);
        long osrTime = System.nanoTime() - osrStart;
        
        System.out.printf("- OSR循环结果: %d\n", result);
        System.out.printf("- OSR执行时间: %.2f ms\n", osrTime / 1_000_000.0);
        System.out.printf("- 平均循环时间: %.2f ns\n", osrTime / (double)OSR_LOOP_COUNT);
        
        // 嵌套循环OSR测试
        System.out.println("\n2. 嵌套循环OSR测试:");
        long nestedStart = System.nanoTime();
        long nestedResult = nestedLoopOSRTest(1000, 100);
        long nestedTime = System.nanoTime() - nestedStart;
        
        System.out.printf("- 嵌套循环结果: %d\n", nestedResult);
        System.out.printf("- 嵌套循环时间: %.2f ms\n", nestedTime / 1_000_000.0);
        
        // OSR vs 普通编译对比
        System.out.println("\n3. OSR vs 普通编译对比:");
        
        // 预编译方法
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            preCompiledMethod(100);
        }
        
        // 测试预编译方法性能
        long preCompiledStart = System.nanoTime();
        long preCompiledResult = 0;
        for (int i = 0; i < 1000; i++) {
            preCompiledResult += preCompiledMethod(100);
        }
        long preCompiledTime = System.nanoTime() - preCompiledStart;
        
        System.out.printf("- 预编译方法时间: %.2f ms\n", preCompiledTime / 1_000_000.0);
        System.out.printf("- OSR方法时间: %.2f ms\n", osrTime / 1_000_000.0);
        
        // OSR效率分析
        System.out.println("\nOSR效率分析:");
        if (osrTime < preCompiledTime * 2) {
            System.out.println("- OSR效率: ✅ 高效 (接近预编译性能)");
        } else if (osrTime < preCompiledTime * 5) {
            System.out.println("- OSR效率: ⚠️  中等");
        } else {
            System.out.println("- OSR效率: 🚨 较低");
        }
        
        System.out.println("✅ OSR机制测试完成");
    }
    
    /**
     * 阶段6：内联优化测试
     */
    private static void phase6_InliningOptimizationTest() {
        System.out.println("\n=== 阶段6：内联优化测试 ===");
        
        System.out.println("测试方法内联优化效果...");
        
        // 小方法内联测试
        System.out.println("\n1. 小方法内联测试:");
        InliningTestClass inliningTest = new InliningTestClass();
        
        // 预热触发内联
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            inliningTest.callerMethod(i);
        }
        
        // 测试内联后性能
        long inlineStart = System.nanoTime();
        long inlineResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            inlineResult += inliningTest.callerMethod(i);
        }
        long inlineTime = System.nanoTime() - inlineStart;
        
        System.out.printf("- 内联测试结果: %d\n", inlineResult);
        System.out.printf("- 内联执行时间: %.2f ms\n", inlineTime / 1_000_000.0);
        System.out.printf("- 平均调用时间: %.2f ns\n", 
            inlineTime / (double)BENCHMARK_ITERATIONS);
        
        // 深度内联测试
        System.out.println("\n2. 深度内联测试:");
        long deepInlineStart = System.nanoTime();
        long deepInlineResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            deepInlineResult += inliningTest.deepInlineChain(i);
        }
        long deepInlineTime = System.nanoTime() - deepInlineStart;
        
        System.out.printf("- 深度内联结果: %d\n", deepInlineResult);
        System.out.printf("- 深度内联时间: %.2f ms\n", deepInlineTime / 1_000_000.0);
        
        // 多态内联测试
        System.out.println("\n3. 多态内联测试:");
        BaseClass[] polymorphicObjects = {
            new DerivedClass1(), new DerivedClass2(), new DerivedClass1()
        };
        
        // 预热多态调用
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            polymorphicObjects[i % 3].virtualMethod(i);
        }
        
        long polymorphicStart = System.nanoTime();
        long polymorphicResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            polymorphicResult += polymorphicObjects[i % 3].virtualMethod(i);
        }
        long polymorphicTime = System.nanoTime() - polymorphicStart;
        
        System.out.printf("- 多态内联结果: %d\n", polymorphicResult);
        System.out.printf("- 多态内联时间: %.2f ms\n", polymorphicTime / 1_000_000.0);
        
        // 内联优化效果分析
        System.out.println("\n内联优化效果分析:");
        double inlinePerf = inlineTime / (double)BENCHMARK_ITERATIONS;
        double deepInlinePerf = deepInlineTime / (double)BENCHMARK_ITERATIONS;
        double polymorphicPerf = polymorphicTime / (double)BENCHMARK_ITERATIONS;
        
        System.out.printf("- 简单内联: %.2f ns/调用\n", inlinePerf);
        System.out.printf("- 深度内联: %.2f ns/调用 (%.2fx)\n", 
            deepInlinePerf, deepInlinePerf / inlinePerf);
        System.out.printf("- 多态内联: %.2f ns/调用 (%.2fx)\n", 
            polymorphicPerf, polymorphicPerf / inlinePerf);
        
        System.out.println("✅ 内联优化测试完成");
    }
    
    /**
     * 阶段7：分支预测测试
     */
    private static void phase7_BranchPredictionTest() {
        System.out.println("\n=== 阶段7：分支预测测试 ===");
        
        System.out.println("测试分支预测优化效果...");
        
        // 可预测分支测试
        System.out.println("\n1. 可预测分支测试:");
        long predictableStart = System.nanoTime();
        int predictableResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            if (i % 4 == 0) { // 25%的分支，规律性强
                predictableResult += i;
            } else {
                predictableResult += i * 2;
            }
        }
        long predictableTime = System.nanoTime() - predictableStart;
        
        System.out.printf("- 可预测分支结果: %d\n", predictableResult);
        System.out.printf("- 可预测分支时间: %.2f ms\n", predictableTime / 1_000_000.0);
        
        // 随机分支测试
        System.out.println("\n2. 随机分支测试:");
        Random random = new Random(42); // 固定种子确保可重现
        long randomStart = System.nanoTime();
        int randomResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            if (random.nextBoolean()) { // 50%随机分支
                randomResult += i;
            } else {
                randomResult += i * 2;
            }
        }
        long randomTime = System.nanoTime() - randomStart;
        
        System.out.printf("- 随机分支结果: %d\n", randomResult);
        System.out.printf("- 随机分支时间: %.2f ms\n", randomTime / 1_000_000.0);
        
        // 复杂分支模式测试
        System.out.println("\n3. 复杂分支模式测试:");
        long complexStart = System.nanoTime();
        int complexResult = complexBranchPattern(BENCHMARK_ITERATIONS);
        long complexTime = System.nanoTime() - complexStart;
        
        System.out.printf("- 复杂分支结果: %d\n", complexResult);
        System.out.printf("- 复杂分支时间: %.2f ms\n", complexTime / 1_000_000.0);
        
        // 分支预测效果分析
        System.out.println("\n分支预测效果分析:");
        double predictablePerf = predictableTime / (double)BENCHMARK_ITERATIONS;
        double randomPerf = randomTime / (double)BENCHMARK_ITERATIONS;
        double complexPerf = complexTime / (double)BENCHMARK_ITERATIONS;
        
        System.out.printf("- 可预测分支: %.2f ns/次\n", predictablePerf);
        System.out.printf("- 随机分支: %.2f ns/次 (%.2fx)\n", 
            randomPerf, randomPerf / predictablePerf);
        System.out.printf("- 复杂分支: %.2f ns/次 (%.2fx)\n", 
            complexPerf, complexPerf / predictablePerf);
        
        double mispredictionPenalty = (randomPerf - predictablePerf) / predictablePerf * 100;
        System.out.printf("- 分支预测失败惩罚: %.1f%%\n", mispredictionPenalty);
        
        if (mispredictionPenalty < 20) {
            System.out.println("- 分支预测器: ⭐⭐⭐⭐⭐ 优秀");
        } else if (mispredictionPenalty < 50) {
            System.out.println("- 分支预测器: ⭐⭐⭐⭐ 良好");
        } else {
            System.out.println("- 分支预测器: ⭐⭐⭐ 一般");
        }
        
        System.out.println("✅ 分支预测测试完成");
    }
    
    /**
     * 阶段8：异常处理性能测试
     */
    private static void phase8_ExceptionHandlingTest() {
        System.out.println("\n=== 阶段8：异常处理性能测试 ===");
        
        System.out.println("测试异常处理机制性能...");
        
        // 正常控制流测试
        System.out.println("\n1. 正常控制流测试:");
        long normalStart = System.nanoTime();
        int normalResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            normalResult += normalControlFlow(i);
        }
        long normalTime = System.nanoTime() - normalStart;
        
        System.out.printf("- 正常控制流时间: %.2f ms\n", normalTime / 1_000_000.0);
        
        // 异常处理测试 (减少迭代次数，异常较慢)
        System.out.println("\n2. 异常处理测试:");
        int exceptionIterations = BENCHMARK_ITERATIONS / 100;
        long exceptionStart = System.nanoTime();
        int exceptionResult = 0;
        for (int i = 0; i < exceptionIterations; i++) {
            try {
                exceptionResult += exceptionControlFlow(i);
            } catch (RuntimeException e) {
                exceptionResult += -1;
            }
        }
        long exceptionTime = System.nanoTime() - exceptionStart;
        
        System.out.printf("- 异常处理时间: %.2f ms (%d次)\n", 
            exceptionTime / 1_000_000.0, exceptionIterations);
        
        // try-catch开销测试
        System.out.println("\n3. try-catch开销测试:");
        long tryCatchStart = System.nanoTime();
        int tryCatchResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            try {
                tryCatchResult += i * 2; // 不抛出异常
            } catch (RuntimeException e) {
                tryCatchResult += -1;
            }
        }
        long tryCatchTime = System.nanoTime() - tryCatchStart;
        
        System.out.printf("- try-catch开销时间: %.2f ms\n", tryCatchTime / 1_000_000.0);
        
        // 异常处理性能分析
        System.out.println("\n异常处理性能分析:");
        double normalPerf = normalTime / (double)BENCHMARK_ITERATIONS;
        double tryCatchPerf = tryCatchTime / (double)BENCHMARK_ITERATIONS;
        double exceptionPerf = exceptionTime / (double)exceptionIterations;
        
        System.out.printf("- 正常控制流: %.2f ns/次\n", normalPerf);
        System.out.printf("- try-catch开销: %.2f ns/次 (%.2fx)\n", 
            tryCatchPerf, tryCatchPerf / normalPerf);
        System.out.printf("- 异常抛出处理: %.2f ns/次 (%.0fx)\n", 
            exceptionPerf, exceptionPerf / normalPerf);
        
        System.out.println("\n异常处理建议:");
        if (tryCatchPerf / normalPerf < 1.1) {
            System.out.println("- try-catch开销: ✅ 很低，可以放心使用");
        } else if (tryCatchPerf / normalPerf < 1.5) {
            System.out.println("- try-catch开销: ⚠️  适中，注意热点路径");
        } else {
            System.out.println("- try-catch开销: 🚨 较高，避免在热点路径使用");
        }
        
        System.out.println("✅ 异常处理性能测试完成");
    }
    
    /**
     * 阶段9：并发执行测试
     */
    private static void phase9_ConcurrentExecutionTest() {
        System.out.println("\n=== 阶段9：并发执行测试 ===");
        
        System.out.println("测试多线程并发执行性能...");
        
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicLong totalConcurrentTime = new AtomicLong(0);
        
        // 并发执行测试
        System.out.println("\n1. 并发方法执行测试:");
        long concurrentStart = System.nanoTime();
        
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    long threadStart = System.nanoTime();
                    
                    // 每个线程执行不同的计算任务
                    ConcurrentTestClass testObj = new ConcurrentTestClass();
                    long result = 0;
                    
                    for (int j = 0; j < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; j++) {
                        result += testObj.computeIntensiveMethod(threadId * 1000 + j);
                    }
                    
                    long threadTime = System.nanoTime() - threadStart;
                    totalConcurrentTime.addAndGet(threadTime);
                    
                    globalSum += result; // 原子性不保证，仅用于防止优化
                    
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.err.println("并发测试被中断");
        }
        
        long totalConcurrentWallTime = System.nanoTime() - concurrentStart;
        
        executor.shutdown();
        
        System.out.printf("- 并发执行墙上时间: %.2f ms\n", 
            totalConcurrentWallTime / 1_000_000.0);
        System.out.printf("- 累计线程执行时间: %.2f ms\n", 
            totalConcurrentTime.get() / 1_000_000.0);
        System.out.printf("- 并发效率: %.1f%%\n", 
            (totalConcurrentTime.get() / (double)totalConcurrentWallTime) * 100);
        
        // 单线程对比测试
        System.out.println("\n2. 单线程对比测试:");
        ConcurrentTestClass singleTestObj = new ConcurrentTestClass();
        long singleStart = System.nanoTime();
        long singleResult = 0;
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            singleResult += singleTestObj.computeIntensiveMethod(i);
        }
        
        long singleTime = System.nanoTime() - singleStart;
        
        System.out.printf("- 单线程执行时间: %.2f ms\n", singleTime / 1_000_000.0);
        
        // 并发性能分析
        System.out.println("\n并发性能分析:");
        double speedup = (double)singleTime / totalConcurrentWallTime;
        double efficiency = speedup / CONCURRENT_THREADS;
        
        System.out.printf("- 并发加速比: %.2fx\n", speedup);
        System.out.printf("- 并发效率: %.1f%%\n", efficiency * 100);
        System.out.printf("- 理论最大加速比: %dx\n", CONCURRENT_THREADS);
        
        if (efficiency > 0.8) {
            System.out.println("- 并发效果: ⭐⭐⭐⭐⭐ 优秀");
        } else if (efficiency > 0.6) {
            System.out.println("- 并发效果: ⭐⭐⭐⭐ 良好");
        } else if (efficiency > 0.4) {
            System.out.println("- 并发效果: ⭐⭐⭐ 一般");
        } else {
            System.out.println("- 并发效果: ⭐⭐ 需要优化");
        }
        
        System.out.println("✅ 并发执行测试完成");
    }
    
    /**
     * 阶段10：最终分析和建议
     */
    private static void phase10_FinalAnalysisAndRecommendations() {
        System.out.println("\n=== 阶段10：最终分析和建议 ===");
        
        // 最终统计
        System.out.println("执行引擎最终统计:");
        System.out.printf("- 总方法调用次数: %d\n", totalMethodCalls.get());
        System.out.printf("- 总执行时间: %.2f ms\n", totalExecutionTime.get() / 1_000_000.0);
        
        if (compilationBean != null) {
            System.out.printf("- 总编译时间: %d ms\n", 
                compilationBean.getTotalCompilationTime());
            System.out.printf("- 编译开销占比: %.2f%%\n", 
                (double)compilationBean.getTotalCompilationTime() / 
                (totalExecutionTime.get() / 1_000_000.0) * 100);
        }
        
        // 性能评估
        System.out.println("\n性能评估:");
        long avgExecutionTime = totalExecutionTime.get() / Math.max(totalMethodCalls.get(), 1);
        
        if (avgExecutionTime < 10) { // 10ns
            System.out.println("- 执行性能: ⭐⭐⭐⭐⭐ 优秀");
        } else if (avgExecutionTime < 50) { // 50ns
            System.out.println("- 执行性能: ⭐⭐⭐⭐ 良好");
        } else if (avgExecutionTime < 100) { // 100ns
            System.out.println("- 执行性能: ⭐⭐⭐ 一般");
        } else {
            System.out.println("- 执行性能: ⭐⭐ 需要优化");
        }
        
        // 优化建议
        System.out.println("\n执行引擎优化建议:");
        System.out.println("1. 🚀 启用分层编译以获得最佳性能");
        System.out.println("2. ⚡ 合理设置编译阈值，平衡启动时间和峰值性能");
        System.out.println("3. 🎯 避免在热点路径使用异常处理");
        System.out.println("4. 📊 利用分支预测，保持代码路径的可预测性");
        System.out.println("5. 🔄 合理使用内联，避免过深的调用链");
        System.out.println("6. 🧵 充分利用多核并发，避免不必要的同步");
        System.out.println("7. 💾 监控CodeCache使用情况，防止编译代码被清理");
        
        // 配置建议
        System.out.println("\n推荐JVM参数:");
        System.out.println("-XX:+TieredCompilation           # 启用分层编译");
        System.out.println("-XX:CompileThreshold=10000       # 设置编译阈值");
        System.out.println("-XX:+UseOnStackReplacement       # 启用OSR");
        System.out.println("-XX:MaxInlineSize=35             # 设置内联大小限制");
        System.out.println("-XX:FreqInlineSize=325           # 设置频繁内联限制");
        System.out.println("-XX:ReservedCodeCacheSize=256m   # 设置代码缓存大小");
        System.out.println("-XX:+PrintCompilation            # 打印编译信息(调试用)");
        
        System.out.println("\n========================================");
        System.out.println("    字节码执行引擎深度分析测试完成！");
        System.out.println("========================================");
    }
    
    // 辅助方法和测试类
    
    private static void initializeJMXBeans() {
        compilationBean = ManagementFactory.getCompilationMXBean();
        runtimeBean = ManagementFactory.getRuntimeMXBean();
        memoryBean = ManagementFactory.getMemoryMXBean();
    }
    
    private static void testLocalVariableAccess(int iterations) {
        int local1 = 1;
        int local2 = 2;
        int local3 = 3;
        int local4 = 4;
        
        for (int i = 0; i < iterations; i++) {
            local1 += local2; // iload, iload, iadd, istore
            local2 += local3;
            local3 += local4;
            local4 += local1;
        }
        
        globalCounter = local1 + local2 + local3 + local4; // 防止优化
    }
    
    private static int testBranchInstructions(int iterations) {
        int result = 0;
        for (int i = 0; i < iterations; i++) {
            if (i % 2 == 0) { // if_icmpne
                result += i;
            } else {
                result -= i;
            }
        }
        return result;
    }
    
    private static int staticMethod(int value) {
        return value * 2 + 1;
    }
    
    private int instanceMethod(int value) {
        return value * 3 + 2;
    }
    
    private static long osrTriggerMethod(int loopCount) {
        long sum = 0;
        for (int i = 0; i < loopCount; i++) {
            sum += i * i + i; // 触发OSR的长循环
        }
        return sum;
    }
    
    private static long nestedLoopOSRTest(int outer, int inner) {
        long sum = 0;
        for (int i = 0; i < outer; i++) {
            for (int j = 0; j < inner; j++) {
                sum += i * j + i + j;
            }
        }
        return sum;
    }
    
    private static long preCompiledMethod(int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += i * i;
        }
        return sum;
    }
    
    private static int complexBranchPattern(int iterations) {
        int result = 0;
        for (int i = 0; i < iterations; i++) {
            int mod = i % 8;
            if (mod == 0 || mod == 1) {
                result += i;
            } else if (mod == 2 || mod == 3) {
                result -= i;
            } else if (mod == 4) {
                result *= 2;
            } else {
                result /= 2;
            }
        }
        return result;
    }
    
    private static int normalControlFlow(int value) {
        if (value > 0) {
            return value * 2;
        } else {
            return value * -1;
        }
    }
    
    private static int exceptionControlFlow(int value) {
        if (value % 100 == 0) {
            throw new RuntimeException("Test exception");
        }
        return value * 2;
    }
    
    // 测试类定义
    
    static class CompilationTestMethods {
        public int hotMethod1(int x) {
            return x * x + x + 1;
        }
        
        public int hotMethod2(int x) {
            return (x + 1) * (x - 1) + x;
        }
        
        public int hotMethod3(int x) {
            int result = x;
            for (int i = 0; i < 10; i++) {
                result = result * 2 + 1;
            }
            return result;
        }
    }
    
    static class InliningTestClass {
        public int callerMethod(int x) {
            return smallMethod1(x) + smallMethod2(x);
        }
        
        private int smallMethod1(int x) { // 适合内联的小方法
            return x * 2;
        }
        
        private int smallMethod2(int x) { // 适合内联的小方法
            return x + 1;
        }
        
        public int deepInlineChain(int x) {
            return level1(x);
        }
        
        private int level1(int x) {
            return level2(x) + 1;
        }
        
        private int level2(int x) {
            return level3(x) + 2;
        }
        
        private int level3(int x) {
            return x * 3;
        }
    }
    
    static abstract class BaseClass {
        public abstract int virtualMethod(int x);
    }
    
    static class DerivedClass1 extends BaseClass {
        @Override
        public int virtualMethod(int x) {
            return x * 2 + 1;
        }
    }
    
    static class DerivedClass2 extends BaseClass {
        @Override
        public int virtualMethod(int x) {
            return x * 3 + 2;
        }
    }
    
    interface TestInterface {
        int interfaceMethod(int x);
    }
    
    static class InterfaceImpl1 implements TestInterface {
        @Override
        public int interfaceMethod(int x) {
            return x * 4 + 1;
        }
    }
    
    static class InterfaceImpl2 implements TestInterface {
        @Override
        public int interfaceMethod(int x) {
            return x * 5 + 2;
        }
    }
    
    static class ConcurrentTestClass {
        public long computeIntensiveMethod(int input) {
            long result = input;
            
            // 计算密集型操作
            for (int i = 0; i < 100; i++) {
                result = result * 31 + i;
                result = result ^ (result >>> 16);
            }
            
            return result;
        }
    }
}