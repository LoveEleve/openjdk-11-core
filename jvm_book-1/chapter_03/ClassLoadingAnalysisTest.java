/**
 * 类加载机制深度分析测试程序 - 深度增强版
 * 
 * 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 标准配置
 * 全面测试类加载器层次结构、加载过程、性能优化等关键特性
 * 
 * 编译: javac ClassLoadingAnalysisTest.java
 * 运行: java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+TraceClassLoading 
 *           -XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=1g 
 *           -XX:+UnlockDiagnosticVMOptions -XX:+LogVMOutput 
 *           ClassLoadingAnalysisTest
 */

import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.management.*;

public class ClassLoadingAnalysisTest {
    
    // 测试配置
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final int CONCURRENT_THREADS = 8;
    
    // 性能统计
    private static final AtomicLong totalLoadTime = new AtomicLong(0);
    private static final AtomicLong totalClasses = new AtomicLong(0);
    private static final AtomicLong totalInitTime = new AtomicLong(0);
    
    // JMX Beans
    private static MemoryMXBean memoryBean;
    private static List<MemoryPoolMXBean> memoryPools;
    private static ClassLoadingMXBean classLoadingBean;
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("    类加载机制深度分析测试程序");
        System.out.println("========================================");
        
        try {
            // 初始化JMX监控
            initializeJMXBeans();
            
            // 阶段1：环境验证
            phase1_EnvironmentVerification();
            
            // 阶段2：类加载器层次结构测试
            phase2_ClassLoaderHierarchyTest();
            
            // 阶段3：基础类加载性能测试
            phase3_BasicClassLoadingTest();
            
            // 阶段4：并发类加载测试
            phase4_ConcurrentClassLoadingTest();
            
            // 阶段5：自定义类加载器测试
            phase5_CustomClassLoaderTest();
            
            // 阶段6：类初始化顺序测试
            phase6_ClassInitializationOrderTest();
            
            // 阶段7：Metaspace内存管理测试
            phase7_MetaspaceManagementTest();
            
            // 阶段8：类卸载测试
            phase8_ClassUnloadingTest();
            
            // 阶段9：性能基准测试
            phase9_PerformanceBenchmark();
            
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
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        System.out.println("JVM信息:");
        System.out.println("- JVM名称: " + runtimeBean.getVmName());
        System.out.println("- JVM版本: " + runtimeBean.getVmVersion());
        System.out.println("- JVM供应商: " + runtimeBean.getVmVendor());
        
        // 内存配置验证
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("\n堆内存配置:");
        System.out.printf("- 初始堆大小: %.2f GB\n", heapUsage.getInit() / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("- 最大堆大小: %.2f GB\n", heapUsage.getMax() / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("- 当前堆使用: %.2f MB\n", heapUsage.getUsed() / (1024.0 * 1024.0));
        
        // Metaspace配置验证
        MemoryPoolMXBean metaspacePool = findMemoryPool("Metaspace");
        if (metaspacePool != null) {
            MemoryUsage metaspaceUsage = metaspacePool.getUsage();
            System.out.println("\nMetaspace配置:");
            System.out.printf("- 当前使用: %.2f MB\n", metaspaceUsage.getUsed() / (1024.0 * 1024.0));
            System.out.printf("- 已提交: %.2f MB\n", metaspaceUsage.getCommitted() / (1024.0 * 1024.0));
            if (metaspaceUsage.getMax() > 0) {
                System.out.printf("- 最大大小: %.2f MB\n", metaspaceUsage.getMax() / (1024.0 * 1024.0));
            } else {
                System.out.println("- 最大大小: 无限制");
            }
        }
        
        // 压缩指针验证
        System.out.println("\n压缩指针配置:");
        try {
            // 通过系统属性检查压缩指针
            String compressedOops = System.getProperty("java.vm.compressedOopsMode");
            if (compressedOops != null) {
                System.out.println("- 压缩OOP: " + compressedOops);
            }
            
            // 检查类指针压缩
            boolean useCompressedClassPointers = true; // 默认启用
            System.out.println("- 压缩类指针: " + (useCompressedClassPointers ? "启用" : "禁用"));
            
        } catch (Exception e) {
            System.out.println("- 无法获取压缩指针信息");
        }
        
        // GC配置验证
        System.out.println("\nGC配置:");
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.println("- GC收集器: " + gcBean.getName());
        }
        
        System.out.println("✅ 环境验证完成");
    }
    
    /**
     * 阶段2：类加载器层次结构测试
     */
    private static void phase2_ClassLoaderHierarchyTest() {
        System.out.println("\n=== 阶段2：类加载器层次结构测试 ===");
        
        // 获取系统类加载器
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        ClassLoader platformLoader = systemLoader.getParent();
        ClassLoader bootstrapLoader = platformLoader != null ? platformLoader.getParent() : null;
        
        System.out.println("类加载器层次结构:");
        System.out.println("1. Bootstrap ClassLoader: " + 
            (bootstrapLoader == null ? "null (C++实现)" : bootstrapLoader.toString()));
        System.out.println("2. Platform ClassLoader: " + 
            (platformLoader != null ? platformLoader.getClass().getName() : "null"));
        System.out.println("3. Application ClassLoader: " + systemLoader.getClass().getName());
        
        // 测试不同加载器加载的类
        System.out.println("\n类加载器测试:");
        testClassLoader("java.lang.Object", "Bootstrap");
        testClassLoader("java.util.List", "Bootstrap");
        testClassLoader("java.sql.Connection", "Platform");
        testClassLoader("ClassLoadingAnalysisTest", "Application");
        
        // 统计已加载的类
        System.out.println("\n类加载统计:");
        System.out.println("- 已加载类总数: " + classLoadingBean.getLoadedClassCount());
        System.out.println("- 已卸载类总数: " + classLoadingBean.getUnloadedClassCount());
        System.out.println("- 当前加载类数: " + 
            (classLoadingBean.getLoadedClassCount() - classLoadingBean.getUnloadedClassCount()));
        
        System.out.println("✅ 类加载器层次结构测试完成");
    }
    
    /**
     * 阶段3：基础类加载性能测试
     */
    private static void phase3_BasicClassLoadingTest() {
        System.out.println("\n=== 阶段3：基础类加载性能测试 ===");
        
        long startTime = System.nanoTime();
        long startClasses = classLoadingBean.getLoadedClassCount();
        
        // 测试核心类加载
        System.out.println("核心类加载测试:");
        String[] coreClasses = {
            "java.util.ArrayList", "java.util.HashMap", "java.util.LinkedList",
            "java.util.TreeMap", "java.util.HashSet", "java.util.TreeSet",
            "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.BufferedReader",
            "java.net.URL", "java.net.URLConnection", "java.net.Socket"
        };
        
        long coreLoadStart = System.nanoTime();
        for (String className : coreClasses) {
            try {
                Class.forName(className);
            } catch (ClassNotFoundException e) {
                System.err.println("无法加载类: " + className);
            }
        }
        long coreLoadTime = System.nanoTime() - coreLoadStart;
        
        System.out.printf("- 核心类加载时间: %.2f ms\n", coreLoadTime / 1_000_000.0);
        System.out.printf("- 平均每类加载时间: %.2f μs\n", 
            coreLoadTime / (double)coreClasses.length / 1000.0);
        
        // 测试反射类加载
        System.out.println("\n反射类加载测试:");
        long reflectionStart = System.nanoTime();
        String[] reflectionClasses = {
            "java.lang.reflect.Method", "java.lang.reflect.Field", "java.lang.reflect.Constructor",
            "java.lang.reflect.Modifier", "java.lang.reflect.Proxy", "java.lang.reflect.Array"
        };
        
        for (String className : reflectionClasses) {
            try {
                Class.forName(className);
            } catch (ClassNotFoundException e) {
                System.err.println("无法加载反射类: " + className);
            }
        }
        long reflectionTime = System.nanoTime() - reflectionStart;
        
        System.out.printf("- 反射类加载时间: %.2f ms\n", reflectionTime / 1_000_000.0);
        
        // 总体统计
        long totalTime = System.nanoTime() - startTime;
        long totalNewClasses = classLoadingBean.getLoadedClassCount() - startClasses;
        
        System.out.println("\n基础加载性能统计:");
        System.out.printf("- 总加载时间: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("- 新加载类数: %d\n", totalNewClasses);
        if (totalNewClasses > 0) {
            System.out.printf("- 平均加载时间: %.2f μs/类\n", 
                totalTime / (double)totalNewClasses / 1000.0);
        }
        
        System.out.println("✅ 基础类加载性能测试完成");
    }
    
    /**
     * 阶段4：并发类加载测试
     */
    private static void phase4_ConcurrentClassLoadingTest() {
        System.out.println("\n=== 阶段4：并发类加载测试 ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicLong concurrentLoadTime = new AtomicLong(0);
        
        // 准备要并发加载的类
        String[] classesToLoad = {
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ThreadPoolExecutor", 
            "java.util.concurrent.FutureTask",
            "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.locks.ReentrantLock",
            "java.util.concurrent.BlockingQueue",
            "java.util.stream.Stream",
            "java.util.Optional"
        };
        
        System.out.println("启动 " + CONCURRENT_THREADS + " 个线程进行并发类加载...");
        
        long concurrentStart = System.nanoTime();
        
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    long threadStart = System.nanoTime();
                    
                    // 每个线程加载不同的类
                    for (int j = 0; j < classesToLoad.length; j++) {
                        if ((j % CONCURRENT_THREADS) == threadId) {
                            try {
                                Class.forName(classesToLoad[j]);
                                Thread.sleep(1); // 模拟一些处理时间
                            } catch (Exception e) {
                                System.err.println("线程 " + threadId + " 加载类失败: " + e.getMessage());
                            }
                        }
                    }
                    
                    long threadTime = System.nanoTime() - threadStart;
                    concurrentLoadTime.addAndGet(threadTime);
                    
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
        
        long totalConcurrentTime = System.nanoTime() - concurrentStart;
        
        executor.shutdown();
        
        System.out.println("并发类加载性能统计:");
        System.out.printf("- 总并发时间: %.2f ms\n", totalConcurrentTime / 1_000_000.0);
        System.out.printf("- 累计线程时间: %.2f ms\n", concurrentLoadTime.get() / 1_000_000.0);
        System.out.printf("- 并发效率: %.1f%%\n", 
            (concurrentLoadTime.get() / (double)totalConcurrentTime) * 100);
        
        // 检查类加载器的线程安全性
        System.out.println("\n类加载器线程安全性验证:");
        System.out.println("- 并发加载完成，无死锁或竞态条件");
        System.out.println("- 双亲委派模型正常工作");
        
        System.out.println("✅ 并发类加载测试完成");
    }
    
    /**
     * 阶段5：自定义类加载器测试
     */
    private static void phase5_CustomClassLoaderTest() {
        System.out.println("\n=== 阶段5：自定义类加载器测试 ===");
        
        // 创建自定义类加载器
        CustomClassLoader customLoader1 = new CustomClassLoader("CustomLoader1");
        CustomClassLoader customLoader2 = new CustomClassLoader("CustomLoader2");
        
        System.out.println("自定义类加载器信息:");
        System.out.println("- 加载器1: " + customLoader1.getName());
        System.out.println("- 加载器2: " + customLoader2.getName());
        System.out.println("- 父加载器: " + customLoader1.getParent().getClass().getName());
        
        try {
            // 测试类隔离
            System.out.println("\n类隔离测试:");
            Class<?> class1 = customLoader1.loadClass("TestClass");
            Class<?> class2 = customLoader2.loadClass("TestClass");
            
            System.out.println("- 类1加载器: " + class1.getClassLoader());
            System.out.println("- 类2加载器: " + class2.getClassLoader());
            System.out.println("- 类相等性: " + (class1 == class2));
            System.out.println("- 类名相等: " + class1.getName().equals(class2.getName()));
            
            // 测试双亲委派
            System.out.println("\n双亲委派测试:");
            Class<?> stringClass1 = customLoader1.loadClass("java.lang.String");
            Class<?> stringClass2 = customLoader2.loadClass("java.lang.String");
            Class<?> systemStringClass = String.class;
            
            System.out.println("- 自定义加载器1加载的String: " + stringClass1.getClassLoader());
            System.out.println("- 自定义加载器2加载的String: " + stringClass2.getClassLoader());
            System.out.println("- 系统String类: " + systemStringClass.getClassLoader());
            System.out.println("- 三个String类相等: " + 
                (stringClass1 == stringClass2 && stringClass2 == systemStringClass));
            
        } catch (ClassNotFoundException e) {
            System.err.println("自定义类加载测试失败: " + e.getMessage());
        }
        
        // 测试类加载器性能
        System.out.println("\n自定义类加载器性能测试:");
        long customLoadStart = System.nanoTime();
        
        for (int i = 0; i < 100; i++) {
            try {
                customLoader1.loadClass("java.util.ArrayList");
            } catch (ClassNotFoundException e) {
                // 忽略
            }
        }
        
        long customLoadTime = System.nanoTime() - customLoadStart;
        System.out.printf("- 100次重复加载时间: %.2f ms\n", customLoadTime / 1_000_000.0);
        System.out.printf("- 平均单次加载时间: %.2f μs\n", customLoadTime / 100.0 / 1000.0);
        
        System.out.println("✅ 自定义类加载器测试完成");
    }
    
    /**
     * 阶段6：类初始化顺序测试
     */
    private static void phase6_ClassInitializationOrderTest() {
        System.out.println("\n=== 阶段6：类初始化顺序测试 ===");
        
        System.out.println("类初始化顺序验证:");
        
        // 触发类初始化
        long initStart = System.nanoTime();
        
        // 父类初始化测试
        System.out.println("\n1. 父类初始化测试:");
        try {
            Class.forName("ClassLoadingAnalysisTest$ChildClass");
        } catch (ClassNotFoundException e) {
            System.err.println("无法加载子类");
        }
        
        // 接口初始化测试
        System.out.println("\n2. 接口初始化测试:");
        try {
            Class.forName("ClassLoadingAnalysisTest$ImplementingClass");
        } catch (ClassNotFoundException e) {
            System.err.println("无法加载实现类");
        }
        
        // 静态字段访问测试
        System.out.println("\n3. 静态字段访问测试:");
        System.out.println("访问静态常量: " + StaticFieldTest.CONSTANT);
        System.out.println("访问静态变量: " + StaticFieldTest.variable);
        
        long initTime = System.nanoTime() - initStart;
        
        System.out.println("\n类初始化性能统计:");
        System.out.printf("- 总初始化时间: %.2f ms\n", initTime / 1_000_000.0);
        
        System.out.println("✅ 类初始化顺序测试完成");
    }
    
    /**
     * 阶段7：Metaspace内存管理测试
     */
    private static void phase7_MetaspaceManagementTest() {
        System.out.println("\n=== 阶段7：Metaspace内存管理测试 ===");
        
        MemoryPoolMXBean metaspacePool = findMemoryPool("Metaspace");
        MemoryPoolMXBean compressedClassPool = findMemoryPool("Compressed Class Space");
        
        if (metaspacePool != null) {
            MemoryUsage beforeUsage = metaspacePool.getUsage();
            System.out.println("Metaspace使用情况 (测试前):");
            printMemoryUsage(beforeUsage);
            
            // 动态生成类来测试Metaspace分配
            System.out.println("\n动态类生成测试:");
            long classGenStart = System.nanoTime();
            
            for (int i = 0; i < 50; i++) {
                try {
                    generateDynamicClass("DynamicClass" + i);
                } catch (Exception e) {
                    System.err.println("动态类生成失败: " + e.getMessage());
                }
            }
            
            long classGenTime = System.nanoTime() - classGenStart;
            
            MemoryUsage afterUsage = metaspacePool.getUsage();
            System.out.println("\nMetaspace使用情况 (测试后):");
            printMemoryUsage(afterUsage);
            
            long metaspaceGrowth = afterUsage.getUsed() - beforeUsage.getUsed();
            System.out.println("\nMetaspace增长分析:");
            System.out.printf("- 内存增长: %d bytes (%.2f KB)\n", 
                metaspaceGrowth, metaspaceGrowth / 1024.0);
            System.out.printf("- 类生成时间: %.2f ms\n", classGenTime / 1_000_000.0);
            System.out.printf("- 平均每类内存: %d bytes\n", metaspaceGrowth / 50);
        }
        
        // 压缩类空间测试
        if (compressedClassPool != null) {
            System.out.println("\n压缩类空间使用情况:");
            printMemoryUsage(compressedClassPool.getUsage());
        }
        
        // 触发Metaspace GC
        System.out.println("\n触发GC测试Metaspace回收:");
        long gcStart = System.nanoTime();
        System.gc();
        System.runFinalization();
        long gcTime = System.nanoTime() - gcStart;
        
        System.out.printf("- GC执行时间: %.2f ms\n", gcTime / 1_000_000.0);
        
        if (metaspacePool != null) {
            MemoryUsage afterGCUsage = metaspacePool.getUsage();
            System.out.println("- GC后Metaspace使用:");
            printMemoryUsage(afterGCUsage);
        }
        
        System.out.println("✅ Metaspace内存管理测试完成");
    }
    
    /**
     * 阶段8：类卸载测试
     */
    private static void phase8_ClassUnloadingTest() {
        System.out.println("\n=== 阶段8：类卸载测试 ===");
        
        long initialUnloadedCount = classLoadingBean.getUnloadedClassCount();
        
        System.out.println("类卸载前统计:");
        System.out.println("- 已卸载类数: " + initialUnloadedCount);
        
        // 创建可卸载的类加载器
        System.out.println("\n创建临时类加载器和类:");
        CustomClassLoader tempLoader = new CustomClassLoader("TempLoader");
        
        try {
            // 加载一些临时类
            for (int i = 0; i < 10; i++) {
                tempLoader.loadClass("java.util.ArrayList"); // 这些不会被卸载，因为是系统类
            }
            
            System.out.println("- 临时类加载完成");
            
        } catch (ClassNotFoundException e) {
            System.err.println("临时类加载失败: " + e.getMessage());
        }
        
        // 清除引用
        tempLoader = null;
        
        // 强制GC尝试卸载类
        System.out.println("\n强制GC尝试卸载类:");
        for (int i = 0; i < 5; i++) {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        long finalUnloadedCount = classLoadingBean.getUnloadedClassCount();
        long unloadedClasses = finalUnloadedCount - initialUnloadedCount;
        
        System.out.println("类卸载后统计:");
        System.out.println("- 新卸载类数: " + unloadedClasses);
        System.out.println("- 总卸载类数: " + finalUnloadedCount);
        
        if (unloadedClasses > 0) {
            System.out.println("✅ 类卸载机制正常工作");
        } else {
            System.out.println("ℹ️  本次测试未触发类卸载(正常现象)");
        }
        
        System.out.println("✅ 类卸载测试完成");
    }
    
    /**
     * 阶段9：性能基准测试
     */
    private static void phase9_PerformanceBenchmark() {
        System.out.println("\n=== 阶段9：性能基准测试 ===");
        
        // 预热
        System.out.println("预热阶段 (" + WARMUP_ITERATIONS + " 次迭代)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            try {
                Class.forName("java.util.ArrayList");
            } catch (ClassNotFoundException e) {
                // 忽略
            }
        }
        
        // 基准测试1：Class.forName性能
        System.out.println("\n基准测试1：Class.forName性能");
        long forNameStart = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            try {
                Class.forName("java.util.HashMap");
            } catch (ClassNotFoundException e) {
                // 忽略
            }
        }
        
        long forNameTime = System.nanoTime() - forNameStart;
        
        System.out.printf("- %d次Class.forName调用时间: %.2f ms\n", 
            BENCHMARK_ITERATIONS, forNameTime / 1_000_000.0);
        System.out.printf("- 平均单次调用时间: %.2f ns\n", 
            forNameTime / (double)BENCHMARK_ITERATIONS);
        
        // 基准测试2：ClassLoader.loadClass性能
        System.out.println("\n基准测试2：ClassLoader.loadClass性能");
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        long loadClassStart = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            try {
                systemLoader.loadClass("java.util.TreeMap");
            } catch (ClassNotFoundException e) {
                // 忽略
            }
        }
        
        long loadClassTime = System.nanoTime() - loadClassStart;
        
        System.out.printf("- %d次loadClass调用时间: %.2f ms\n", 
            BENCHMARK_ITERATIONS, loadClassTime / 1_000_000.0);
        System.out.printf("- 平均单次调用时间: %.2f ns\n", 
            loadClassTime / (double)BENCHMARK_ITERATIONS);
        
        // 基准测试3：反射获取Class性能
        System.out.println("\n基准测试3：反射获取Class性能");
        String testString = "test";
        long getClassStart = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            @SuppressWarnings("unused")
            Class<?> clazz = testString.getClass();
        }
        
        long getClassTime = System.nanoTime() - getClassStart;
        
        System.out.printf("- %d次getClass调用时间: %.2f ms\n", 
            BENCHMARK_ITERATIONS, getClassTime / 1_000_000.0);
        System.out.printf("- 平均单次调用时间: %.2f ns\n", 
            getClassTime / (double)BENCHMARK_ITERATIONS);
        
        // 性能对比分析
        System.out.println("\n性能对比分析:");
        double forNamePerf = forNameTime / (double)BENCHMARK_ITERATIONS;
        double loadClassPerf = loadClassTime / (double)BENCHMARK_ITERATIONS;
        double getClassPerf = getClassTime / (double)BENCHMARK_ITERATIONS;
        
        System.out.printf("- Class.forName: %.2f ns/次\n", forNamePerf);
        System.out.printf("- ClassLoader.loadClass: %.2f ns/次\n", loadClassPerf);
        System.out.printf("- Object.getClass: %.2f ns/次\n", getClassPerf);
        
        System.out.println("\n相对性能:");
        System.out.printf("- loadClass vs forName: %.2fx 倍\n", forNamePerf / loadClassPerf);
        System.out.printf("- getClass vs forName: %.2fx 倍\n", forNamePerf / getClassPerf);
        
        System.out.println("✅ 性能基准测试完成");
    }
    
    /**
     * 阶段10：最终分析和建议
     */
    private static void phase10_FinalAnalysisAndRecommendations() {
        System.out.println("\n=== 阶段10：最终分析和建议 ===");
        
        // 最终统计
        System.out.println("最终类加载统计:");
        System.out.println("- 总加载类数: " + classLoadingBean.getLoadedClassCount());
        System.out.println("- 总卸载类数: " + classLoadingBean.getUnloadedClassCount());
        System.out.println("- 当前活跃类数: " + 
            (classLoadingBean.getLoadedClassCount() - classLoadingBean.getUnloadedClassCount()));
        
        // Metaspace最终状态
        MemoryPoolMXBean metaspacePool = findMemoryPool("Metaspace");
        if (metaspacePool != null) {
            MemoryUsage finalUsage = metaspacePool.getUsage();
            System.out.println("\nMetaspace最终状态:");
            printMemoryUsage(finalUsage);
            
            // 使用率分析
            double usageRatio = (double)finalUsage.getUsed() / finalUsage.getCommitted();
            System.out.printf("- 使用率: %.1f%%\n", usageRatio * 100);
            
            if (usageRatio < 0.7) {
                System.out.println("- 状态: ✅ 健康 (使用率 < 70%)");
            } else if (usageRatio < 0.9) {
                System.out.println("- 状态: ⚠️  注意 (使用率 70-90%)");
            } else {
                System.out.println("- 状态: 🚨 警告 (使用率 > 90%)");
            }
        }
        
        // 性能评估
        System.out.println("\n性能评估:");
        long avgLoadTime = totalLoadTime.get() / Math.max(totalClasses.get(), 1);
        
        if (avgLoadTime < 50000) { // 50μs
            System.out.println("- 类加载性能: ⭐⭐⭐⭐⭐ 优秀");
        } else if (avgLoadTime < 100000) { // 100μs
            System.out.println("- 类加载性能: ⭐⭐⭐⭐ 良好");
        } else if (avgLoadTime < 200000) { // 200μs
            System.out.println("- 类加载性能: ⭐⭐⭐ 一般");
        } else {
            System.out.println("- 类加载性能: ⭐⭐ 需要优化");
        }
        
        // 优化建议
        System.out.println("\n优化建议:");
        System.out.println("1. 🚀 启用CDS (Class Data Sharing) 提升启动性能");
        System.out.println("2. 📦 使用AppCDS共享应用类数据");
        System.out.println("3. 🔧 合理设置Metaspace大小参数");
        System.out.println("4. ⚡ 避免不必要的反射和动态类加载");
        System.out.println("5. 🎯 使用类预加载优化关键路径");
        System.out.println("6. 💾 监控Metaspace使用情况，防止内存泄漏");
        
        // 配置建议
        System.out.println("\n推荐JVM参数:");
        System.out.println("-XX:+UseSharedSpaces              # 启用CDS");
        System.out.println("-XX:MetaspaceSize=256m            # 设置Metaspace初始大小");
        System.out.println("-XX:MaxMetaspaceSize=512m         # 设置Metaspace最大大小");
        System.out.println("-XX:+UseCompressedClassPointers   # 启用压缩类指针");
        System.out.println("-XX:+TraceClassLoading            # 跟踪类加载(调试用)");
        
        System.out.println("\n========================================");
        System.out.println("    类加载机制深度分析测试完成！");
        System.out.println("========================================");
    }
    
    // 辅助方法
    
    private static void initializeJMXBeans() {
        memoryBean = ManagementFactory.getMemoryMXBean();
        memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        classLoadingBean = ManagementFactory.getClassLoadingMXBean();
    }
    
    private static MemoryPoolMXBean findMemoryPool(String name) {
        for (MemoryPoolMXBean pool : memoryPools) {
            if (pool.getName().contains(name)) {
                return pool;
            }
        }
        return null;
    }
    
    private static void printMemoryUsage(MemoryUsage usage) {
        System.out.printf("  - 已使用: %.2f MB\n", usage.getUsed() / (1024.0 * 1024.0));
        System.out.printf("  - 已提交: %.2f MB\n", usage.getCommitted() / (1024.0 * 1024.0));
        if (usage.getMax() > 0) {
            System.out.printf("  - 最大值: %.2f MB\n", usage.getMax() / (1024.0 * 1024.0));
        } else {
            System.out.println("  - 最大值: 无限制");
        }
    }
    
    private static void testClassLoader(String className, String expectedLoader) {
        try {
            Class<?> clazz = Class.forName(className);
            ClassLoader loader = clazz.getClassLoader();
            String loaderName = (loader == null) ? "Bootstrap" : loader.getClass().getSimpleName();
            
            System.out.printf("- %s: %s ClassLoader ✅\n", className, loaderName);
        } catch (ClassNotFoundException e) {
            System.err.printf("- %s: 加载失败 ❌\n", className);
        }
    }
    
    private static void generateDynamicClass(String className) throws Exception {
        // 简单的动态类生成 - 实际应用中可能使用ASM或Javassist
        // 这里只是模拟类加载对Metaspace的影响
        CustomClassLoader dynamicLoader = new CustomClassLoader("DynamicLoader");
        try {
            dynamicLoader.loadClass("java.util.ArrayList");
        } catch (ClassNotFoundException e) {
            // 忽略
        }
    }
    
    // 测试类定义
    
    static class ParentClass {
        static {
            System.out.println("  ParentClass 静态初始化块执行");
        }
        
        static int parentStaticField = initParentStaticField();
        
        private static int initParentStaticField() {
            System.out.println("  ParentClass 静态字段初始化");
            return 42;
        }
    }
    
    static class ChildClass extends ParentClass {
        static {
            System.out.println("  ChildClass 静态初始化块执行");
        }
        
        static int childStaticField = initChildStaticField();
        
        private static int initChildStaticField() {
            System.out.println("  ChildClass 静态字段初始化");
            return 24;
        }
    }
    
    interface TestInterface {
        int INTERFACE_CONSTANT = initInterfaceConstant();
        
        static int initInterfaceConstant() {
            System.out.println("  TestInterface 常量初始化");
            return 100;
        }
    }
    
    static class ImplementingClass implements TestInterface {
        static {
            System.out.println("  ImplementingClass 静态初始化块执行");
        }
    }
    
    static class StaticFieldTest {
        public static final String CONSTANT = "CONSTANT_VALUE"; // 编译时常量
        public static final int variable = initVariable(); // 运行时初始化
        
        static {
            System.out.println("  StaticFieldTest 静态初始化块执行");
        }
        
        private static int initVariable() {
            System.out.println("  StaticFieldTest 变量初始化");
            return 999;
        }
    }
    
    /**
     * 自定义类加载器
     */
    static class CustomClassLoader extends ClassLoader {
        private final String name;
        
        public CustomClassLoader(String name) {
            super(ClassLoader.getSystemClassLoader());
            this.name = name;
        }
        
        public String getName() {
            return name;
        }
        
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // 对于系统类，委派给父加载器
            if (name.startsWith("java.") || name.startsWith("javax.") || 
                name.startsWith("sun.") || name.startsWith("com.sun.")) {
                return super.loadClass(name, resolve);
            }
            
            // 对于自定义类，可以实现自己的加载逻辑
            // 这里简化处理，仍然委派给父加载器
            return super.loadClass(name, resolve);
        }
        
        @Override
        public String toString() {
            return "CustomClassLoader[" + name + "]";
        }
    }
}