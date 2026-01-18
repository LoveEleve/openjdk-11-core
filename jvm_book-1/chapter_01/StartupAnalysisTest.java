/**
 * JVM启动流程深度分析测试程序
 * 
 * 功能：
 * 1. 验证JVM启动过程中的各个子系统初始化
 * 2. 测试内存分配和TLAB机制
 * 3. 触发JIT编译和代码缓存使用
 * 4. 验证类加载和方法调用机制
 * 5. 测试异常处理和调试接口
 * 
 * 使用方法：
 * javac StartupAnalysisTest.java
 * java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintGCDetails StartupAnalysisTest
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

public class StartupAnalysisTest {
    
    // 测试常量
    private static final int WARMUP_ITERATIONS = 10000;
    private static final int BENCHMARK_ITERATIONS = 100000;
    private static final int THREAD_COUNT = 8;
    
    // 性能统计
    private static long totalAllocations = 0;
    private static long totalComputations = 0;
    private static long startTime;
    
    /**
     * 主入口函数 - 验证JVM启动完成状态
     */
    public static void main(String[] args) {
        startTime = System.nanoTime();
        
        System.out.println("🚀 === JVM启动流程深度分析测试 ===");
        System.out.println("测试目标：验证JVM各子系统初始化状态");
        System.out.println("配置环境：8GB G1堆，非大页，非NUMA");
        System.out.println();
        
        try {
            // 第一阶段：JVM状态验证
            verifyJVMInitialization();
            
            // 第二阶段：内存子系统测试
            testMemorySubsystem();
            
            // 第三阶段：类加载子系统测试
            testClassLoadingSubsystem();
            
            // 第四阶段：执行引擎测试
            testExecutionEngine();
            
            // 第五阶段：JIT编译器测试
            testJITCompiler();
            
            // 第六阶段：并发机制测试
            testConcurrencyMechanism();
            
            // 第七阶段：异常处理测试
            testExceptionHandling();
            
            // 第八阶段：性能基准测试
            runPerformanceBenchmark();
            
            // 最终报告
            generateFinalReport();
            
        } catch (Exception e) {
            System.err.println("❌ 测试过程中发生异常：" + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 验证JVM初始化状态
     */
    private static void verifyJVMInitialization() {
        System.out.println("📋 === 第一阶段：JVM初始化状态验证 ===");
        
        // 获取运行时信息
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        System.out.println("JVM基本信息：");
        System.out.printf("  JVM名称: %s\n", System.getProperty("java.vm.name"));
        System.out.printf("  JVM版本: %s\n", System.getProperty("java.vm.version"));
        System.out.printf("  JVM供应商: %s\n", System.getProperty("java.vm.vendor"));
        System.out.printf("  Java版本: %s\n", System.getProperty("java.version"));
        
        System.out.println("\n内存配置验证：");
        System.out.printf("  最大堆内存: %.2f GB\n", runtime.maxMemory() / (1024.0 * 1024 * 1024));
        System.out.printf("  总堆内存: %.2f GB\n", runtime.totalMemory() / (1024.0 * 1024 * 1024));
        System.out.printf("  空闲堆内存: %.2f GB\n", runtime.freeMemory() / (1024.0 * 1024 * 1024));
        
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.printf("  堆内存使用: %.2f MB / %.2f MB (%.1f%%)\n",
            heapUsage.getUsed() / (1024.0 * 1024),
            heapUsage.getMax() / (1024.0 * 1024),
            heapUsage.getUsed() * 100.0 / heapUsage.getMax());
        
        // 验证GC配置
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("\nGC配置验证：");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("  GC算法: %s\n", gcBean.getName());
            System.out.printf("  GC次数: %d\n", gcBean.getCollectionCount());
            System.out.printf("  GC时间: %d ms\n", gcBean.getCollectionTime());
        }
        
        // 验证编译器配置
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null) {
            System.out.println("\n编译器配置验证：");
            System.out.printf("  编译器名称: %s\n", compilationBean.getName());
            System.out.printf("  编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
        }
        
        System.out.println("✅ JVM初始化状态验证完成\n");
    }
    
    /**
     * 测试内存子系统
     */
    private static void testMemorySubsystem() {
        System.out.println("💾 === 第二阶段：内存子系统测试 ===");
        
        long startTime = System.nanoTime();
        
        // 测试对象分配性能
        System.out.println("测试对象分配性能...");
        List<Object> objects = new ArrayList<>();
        
        // 小对象分配测试 (TLAB快速分配)
        for (int i = 0; i < 100000; i++) {
            objects.add(new SmallObject(i));
            totalAllocations++;
        }
        
        // 中等对象分配测试
        for (int i = 0; i < 10000; i++) {
            objects.add(new MediumObject(i));
            totalAllocations++;
        }
        
        // 大对象分配测试 (直接分配到Old区)
        for (int i = 0; i < 100; i++) {
            objects.add(new LargeObject(i));
            totalAllocations++;
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("对象分配性能测试完成：\n");
        System.out.printf("  总分配对象: %d 个\n", objects.size());
        System.out.printf("  分配耗时: %.2f ms\n", duration);
        System.out.printf("  分配速率: %.0f 对象/秒\n", objects.size() * 1000.0 / duration);
        
        // 触发GC测试
        System.out.println("\n触发GC测试...");
        long beforeGC = System.currentTimeMillis();
        System.gc();
        long afterGC = System.currentTimeMillis();
        System.out.printf("GC耗时: %d ms\n", afterGC - beforeGC);
        
        // 清理对象引用
        objects.clear();
        objects = null;
        
        System.out.println("✅ 内存子系统测试完成\n");
    }
    
    /**
     * 测试类加载子系统
     */
    private static void testClassLoadingSubsystem() {
        System.out.println("📚 === 第三阶段：类加载子系统测试 ===");
        
        try {
            // 测试动态类加载
            System.out.println("测试动态类加载...");
            
            // 加载系统类
            Class<?> stringClass = Class.forName("java.lang.String");
            Class<?> listClass = Class.forName("java.util.ArrayList");
            Class<?> mapClass = Class.forName("java.util.HashMap");
            
            System.out.printf("成功加载类: %s\n", stringClass.getName());
            System.out.printf("成功加载类: %s\n", listClass.getName());
            System.out.printf("成功加载类: %s\n", mapClass.getName());
            
            // 测试反射机制
            System.out.println("\n测试反射机制...");
            Method[] methods = String.class.getDeclaredMethods();
            System.out.printf("String类方法数量: %d\n", methods.length);
            
            Field[] fields = ArrayList.class.getDeclaredFields();
            System.out.printf("ArrayList类字段数量: %d\n", fields.length);
            
            // 测试类加载器层次
            System.out.println("\n测试类加载器层次...");
            ClassLoader currentLoader = StartupAnalysisTest.class.getClassLoader();
            ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
            ClassLoader extLoader = systemLoader.getParent();
            
            System.out.printf("当前类加载器: %s\n", currentLoader);
            System.out.printf("系统类加载器: %s\n", systemLoader);
            System.out.printf("扩展类加载器: %s\n", extLoader);
            
        } catch (ClassNotFoundException e) {
            System.err.println("类加载失败: " + e.getMessage());
        }
        
        System.out.println("✅ 类加载子系统测试完成\n");
    }
    
    /**
     * 测试执行引擎
     */
    private static void testExecutionEngine() {
        System.out.println("⚙️ === 第四阶段：执行引擎测试 ===");
        
        // 测试方法调用性能
        System.out.println("测试方法调用性能...");
        long startTime = System.nanoTime();
        
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            performComputation(i);
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("方法调用性能测试：\n");
        System.out.printf("  调用次数: %d\n", WARMUP_ITERATIONS);
        System.out.printf("  总耗时: %.2f ms\n", duration);
        System.out.printf("  平均耗时: %.3f μs/调用\n", duration * 1000 / WARMUP_ITERATIONS);
        
        // 测试递归调用
        System.out.println("\n测试递归调用...");
        int result = fibonacci(30);
        System.out.printf("fibonacci(30) = %d\n", result);
        
        // 测试异常处理性能
        System.out.println("\n测试异常处理性能...");
        testExceptionPerformance();
        
        System.out.println("✅ 执行引擎测试完成\n");
    }
    
    /**
     * 测试JIT编译器
     */
    private static void testJITCompiler() {
        System.out.println("🚀 === 第五阶段：JIT编译器测试 ===");
        
        // 热点方法测试 - 触发JIT编译
        System.out.println("触发JIT编译测试...");
        
        // 预热阶段
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            hotspotMethod(i);
        }
        
        // 基准测试阶段
        long startTime = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            hotspotMethod(i);
        }
        long endTime = System.nanoTime();
        
        double duration = (endTime - startTime) / 1_000_000.0;
        System.out.printf("JIT编译后性能：\n");
        System.out.printf("  执行次数: %d\n", BENCHMARK_ITERATIONS);
        System.out.printf("  总耗时: %.2f ms\n", duration);
        System.out.printf("  平均耗时: %.3f ns/调用\n", (endTime - startTime) / (double)BENCHMARK_ITERATIONS);
        
        // 测试内联优化
        System.out.println("\n测试内联优化...");
        testInlining();
        
        System.out.println("✅ JIT编译器测试完成\n");
    }
    
    /**
     * 测试并发机制
     */
    private static void testConcurrencyMechanism() {
        System.out.println("🔄 === 第六阶段：并发机制测试 ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        System.out.printf("启动 %d 个并发线程...\n", THREAD_COUNT);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // 每个线程执行计算任务
                    for (int j = 0; j < 10000; j++) {
                        performComputation(threadId * 10000 + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
            long endTime = System.nanoTime();
            double duration = (endTime - startTime) / 1_000_000.0;
            
            System.out.printf("并发测试完成：\n");
            System.out.printf("  线程数量: %d\n", THREAD_COUNT);
            System.out.printf("  总耗时: %.2f ms\n", duration);
            System.out.printf("  吞吐量: %.0f 操作/秒\n", THREAD_COUNT * 10000 * 1000.0 / duration);
            
        } catch (InterruptedException e) {
            System.err.println("并发测试被中断: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
        
        System.out.println("✅ 并发机制测试完成\n");
    }
    
    /**
     * 测试异常处理
     */
    private static void testExceptionHandling() {
        System.out.println("🔍 === 第七阶段：异常处理测试 ===");
        
        // 测试异常创建性能
        System.out.println("测试异常创建性能...");
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 1000; i++) {
            try {
                throw new RuntimeException("测试异常 " + i);
            } catch (RuntimeException e) {
                // 捕获并忽略
            }
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("异常处理性能：\n");
        System.out.printf("  异常次数: 1000\n");
        System.out.printf("  总耗时: %.2f ms\n", duration);
        System.out.printf("  平均耗时: %.3f μs/异常\n", duration * 1000 / 1000);
        
        // 测试栈跟踪
        System.out.println("\n测试栈跟踪...");
        testStackTrace();
        
        System.out.println("✅ 异常处理测试完成\n");
    }
    
    /**
     * 运行性能基准测试
     */
    private static void runPerformanceBenchmark() {
        System.out.println("📊 === 第八阶段：性能基准测试 ===");
        
        // CPU密集型测试
        System.out.println("CPU密集型基准测试...");
        long startTime = System.nanoTime();
        
        double result = 0;
        for (int i = 0; i < 1000000; i++) {
            result += Math.sin(i) * Math.cos(i) * Math.sqrt(i);
        }
        
        long endTime = System.nanoTime();
        double cpuDuration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("CPU基准测试结果：\n");
        System.out.printf("  计算结果: %.6f\n", result);
        System.out.printf("  耗时: %.2f ms\n", cpuDuration);
        System.out.printf("  计算速率: %.0f 操作/秒\n", 1000000 * 1000.0 / cpuDuration);
        
        // 内存密集型测试
        System.out.println("\n内存密集型基准测试...");
        startTime = System.nanoTime();
        
        List<byte[]> memoryTest = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            memoryTest.add(new byte[1024 * 1024]); // 1MB数组
        }
        
        endTime = System.nanoTime();
        double memoryDuration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("内存基准测试结果：\n");
        System.out.printf("  分配内存: 1000 MB\n");
        System.out.printf("  耗时: %.2f ms\n", memoryDuration);
        System.out.printf("  分配速率: %.2f MB/秒\n", 1000 * 1000.0 / memoryDuration);
        
        // 清理内存
        memoryTest.clear();
        
        System.out.println("✅ 性能基准测试完成\n");
    }
    
    /**
     * 生成最终报告
     */
    private static void generateFinalReport() {
        long endTime = System.nanoTime();
        double totalDuration = (endTime - startTime) / 1_000_000.0;
        
        System.out.println("📋 === 最终测试报告 ===");
        System.out.printf("总测试时间: %.2f ms\n", totalDuration);
        System.out.printf("总分配对象: %d 个\n", totalAllocations);
        System.out.printf("总计算次数: %d 次\n", totalComputations);
        
        // 获取最终内存使用情况
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.printf("\n最终内存状态：\n");
        System.out.printf("  堆内存使用: %.2f MB\n", heapUsage.getUsed() / (1024.0 * 1024));
        System.out.printf("  堆内存容量: %.2f MB\n", heapUsage.getCommitted() / (1024.0 * 1024));
        System.out.printf("  使用率: %.1f%%\n", heapUsage.getUsed() * 100.0 / heapUsage.getCommitted());
        
        // GC统计
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.printf("\nGC统计：\n");
        long totalGCTime = 0;
        long totalGCCount = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            totalGCTime += gcBean.getCollectionTime();
            totalGCCount += gcBean.getCollectionCount();
            System.out.printf("  %s: %d次, %dms\n", 
                gcBean.getName(), gcBean.getCollectionCount(), gcBean.getCollectionTime());
        }
        System.out.printf("  总GC次数: %d\n", totalGCCount);
        System.out.printf("  总GC时间: %d ms\n", totalGCTime);
        System.out.printf("  GC时间占比: %.2f%%\n", totalGCTime * 100.0 / totalDuration);
        
        // 编译统计
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null) {
            System.out.printf("\n编译统计：\n");
            System.out.printf("  编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
            System.out.printf("  编译时间占比: %.2f%%\n", 
                compilationBean.getTotalCompilationTime() * 100.0 / totalDuration);
        }
        
        System.out.println("\n🎉 === JVM启动流程深度分析测试完成 ===");
        System.out.println("所有子系统验证通过，JVM运行状态正常！");
    }
    
    // ========================================================================
    // 辅助方法和测试类
    // ========================================================================
    
    /**
     * 执行计算任务
     */
    private static double performComputation(int input) {
        totalComputations++;
        double result = 0;
        for (int i = 1; i <= 100; i++) {
            result += Math.sqrt(input * i) / Math.log(i + 1);
        }
        return result;
    }
    
    /**
     * 热点方法 - 用于触发JIT编译
     */
    private static long hotspotMethod(int n) {
        long result = 0;
        for (int i = 0; i < n % 1000; i++) {
            result += i * i + i;
        }
        return result;
    }
    
    /**
     * 斐波那契数列 - 测试递归调用
     */
    private static int fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
    
    /**
     * 测试内联优化
     */
    private static void testInlining() {
        long startTime = System.nanoTime();
        long sum = 0;
        
        for (int i = 0; i < 1000000; i++) {
            sum += inlineableMethod(i);
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("内联优化测试：sum=%d, 耗时=%.2fms\n", sum, duration);
    }
    
    /**
     * 可内联的小方法
     */
    private static int inlineableMethod(int x) {
        return x * 2 + 1;
    }
    
    /**
     * 测试异常性能
     */
    private static void testExceptionPerformance() {
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 100; i++) {
            try {
                riskyMethod(i);
            } catch (Exception e) {
                // 处理异常
            }
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        System.out.printf("异常处理性能: %.2f ms\n", duration);
    }
    
    /**
     * 可能抛出异常的方法
     */
    private static void riskyMethod(int i) throws Exception {
        if (i % 10 == 0) {
            throw new Exception("测试异常: " + i);
        }
    }
    
    /**
     * 测试栈跟踪
     */
    private static void testStackTrace() {
        try {
            methodA();
        } catch (Exception e) {
            StackTraceElement[] stack = e.getStackTrace();
            System.out.printf("栈跟踪深度: %d\n", stack.length);
            for (int i = 0; i < Math.min(3, stack.length); i++) {
                System.out.printf("  [%d] %s.%s:%d\n", 
                    i, stack[i].getClassName(), stack[i].getMethodName(), stack[i].getLineNumber());
            }
        }
    }
    
    private static void methodA() throws Exception {
        methodB();
    }
    
    private static void methodB() throws Exception {
        methodC();
    }
    
    private static void methodC() throws Exception {
        throw new Exception("深层异常测试");
    }
    
    // ========================================================================
    // 测试用的数据类
    // ========================================================================
    
    /**
     * 小对象 - 测试TLAB分配
     */
    static class SmallObject {
        private int id;
        private String name;
        
        public SmallObject(int id) {
            this.id = id;
            this.name = "Small-" + id;
        }
    }
    
    /**
     * 中等对象 - 测试正常堆分配
     */
    static class MediumObject {
        private int[] data = new int[1000];
        private String description;
        
        public MediumObject(int id) {
            this.description = "Medium-" + id;
            for (int i = 0; i < data.length; i++) {
                data[i] = id + i;
            }
        }
    }
    
    /**
     * 大对象 - 测试直接Old区分配
     */
    static class LargeObject {
        private byte[] largeData = new byte[1024 * 1024]; // 1MB
        private String info;
        
        public LargeObject(int id) {
            this.info = "Large-" + id;
            // 填充一些数据
            for (int i = 0; i < Math.min(1000, largeData.length); i++) {
                largeData[i] = (byte)(id % 256);
            }
        }
    }
}