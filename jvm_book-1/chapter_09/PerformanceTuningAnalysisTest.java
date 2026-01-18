/**
 * JVM性能调优深度案例 - 综合测试程序
 * 基于8GB堆内存配置，G1GC，4MB Region设置
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PerformanceTuningAnalysisTest {
    
    private static final int WARMUP_ITERATIONS = 10000;
    private static final int TEST_ITERATIONS = 100000;
    private static final int THREAD_COUNT = 8;
    
    // 测试对象类型
    static class SmallObject {
        private final long value1;
        private final int value2;
        
        public SmallObject(long v1, int v2) {
            this.value1 = v1;
            this.value2 = v2;
        }
        
        public long getValue1() { return value1; }
        public int getValue2() { return value2; }
    }
    
    static class MediumObject {
        private final long[] data;
        private final String name;
        
        public MediumObject(int size) {
            this.data = new long[size];
            this.name = "MediumObject_" + System.nanoTime();
            
            for (int i = 0; i < size; i++) {
                data[i] = i * 2L;
            }
        }
        
        public long[] getData() { return data; }
        public String getName() { return name; }
    }
    
    static class LargeObject {
        private final byte[] buffer;
        private final List<MediumObject> children;
        
        public LargeObject(int bufferSize, int childCount) {
            this.buffer = new byte[bufferSize];
            this.children = new ArrayList<>(childCount);
            
            Arrays.fill(buffer, (byte) 0xAA);
            
            for (int i = 0; i < childCount; i++) {
                children.add(new MediumObject(64));
            }
        }
        
        public byte[] getBuffer() { return buffer; }
        public List<MediumObject> getChildren() { return children; }
    }
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("JVM性能调优深度案例 - 综合测试程序");
        System.out.println("=".repeat(80));
        
        printJVMConfiguration();
        
        try {
            // 阶段1: 环境验证和预热
            runPhase1_EnvironmentValidation();
            
            // 阶段2: 内存分配性能测试
            runPhase2_MemoryAllocationPerformance();
            
            // 阶段3: GC性能影响分析
            runPhase3_GCPerformanceAnalysis();
            
            // 阶段4: JIT编译器优化验证
            runPhase4_JITCompilerOptimization();
            
            // 阶段5: 线程性能测试
            runPhase5_ThreadPerformanceTest();
            
            // 阶段6: 性能基准建立
            runPhase6_PerformanceBenchmark();
            
            // 阶段7: 最终性能评估
            runPhase7_FinalPerformanceAssessment();
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n测试程序执行完成");
    }
    
    private static void printJVMConfiguration() {
        System.out.println("\n📋 JVM配置信息:");
        
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        System.out.println("├─ JVM版本: " + System.getProperty("java.version"));
        System.out.println("├─ JVM供应商: " + System.getProperty("java.vendor"));
        System.out.println("├─ 运行时名称: " + runtimeBean.getVmName());
        
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("├─ 堆内存配置:");
        System.out.printf("│  ├─ 初始大小: %.1f GB\n", heapUsage.getInit() / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("│  ├─ 最大大小: %.1f GB\n", heapUsage.getMax() / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("│  └─ 当前大小: %.1f GB\n", heapUsage.getCommitted() / 1024.0 / 1024.0 / 1024.0);
        
        System.out.println("├─ 可用处理器: " + Runtime.getRuntime().availableProcessors());
        
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("└─ 垃圾收集器:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.println("   ├─ " + gcBean.getName());
        }
    }
    
    private static void runPhase1_EnvironmentValidation() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段1: 环境验证和预热");
        System.out.println("=".repeat(60));
        
        // 验证JVM配置
        System.out.println("\n1.1 JVM配置验证:");
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        long maxHeapGB = heapUsage.getMax() / 1024 / 1024 / 1024;
        System.out.println("├─ 最大堆大小: " + maxHeapGB + " GB");
        
        if (maxHeapGB >= 7 && maxHeapGB <= 9) {
            System.out.println("│  └─ 8GB堆配置: 验证通过 ✅");
        } else {
            System.out.println("│  └─ 8GB堆配置: 验证失败 ❌ (实际: " + maxHeapGB + "GB)");
        }
        
        // 检查G1GC
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        boolean hasG1 = gcBeans.stream().anyMatch(gc -> gc.getName().contains("G1"));
        System.out.println("├─ G1GC配置: " + (hasG1 ? "验证通过 ✅" : "验证失败 ❌"));
        
        // JVM预热
        System.out.println("\n1.2 JVM预热:");
        System.out.println("├─ 执行预热迭代: " + WARMUP_ITERATIONS + "次");
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            SmallObject small = new SmallObject(i, i * 2);
            MediumObject medium = new MediumObject(32);
            
            double result = Math.sqrt(i) * Math.sin(i) + Math.cos(i);
            String str = "warmup_" + i + "_" + result;
            str.hashCode();
            
            if (i % 1000 == 0) {
                System.gc();
            }
        }
        
        long warmupTime = (System.nanoTime() - startTime) / 1_000_000;
        System.out.println("├─ 预热完成时间: " + warmupTime + " ms");
        System.out.println("└─ JVM预热: 完成 ✅");
    }
    
    private static void runPhase2_MemoryAllocationPerformance() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段2: 内存分配性能测试");
        System.out.println("=".repeat(60));
        
        // 小对象分配性能测试
        System.out.println("\n2.1 小对象分配性能测试:");
        testSmallObjectAllocation();
        
        // 中等对象分配性能测试
        System.out.println("\n2.2 中等对象分配性能测试:");
        testMediumObjectAllocation();
        
        // 大对象分配性能测试
        System.out.println("\n2.3 大对象分配性能测试:");
        testLargeObjectAllocation();
        
        // TLAB效率测试
        System.out.println("\n2.4 TLAB效率测试:");
        testTLABEfficiency();
    }
    
    private static void testSmallObjectAllocation() {
        final int iterations = TEST_ITERATIONS;
        List<SmallObject> objects = new ArrayList<>(iterations);
        
        System.out.println("├─ 测试参数: " + iterations + "个小对象(32字节)");
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            SmallObject obj = new SmallObject(i, i * 2);
            objects.add(obj);
        }
        
        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgTimeNs = (double) totalTime / iterations;
        
        System.out.printf("├─ 总分配时间: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("├─ 平均分配时间: %.2f ns/对象\n", avgTimeNs);
        System.out.printf("├─ 分配速率: %.2f M对象/秒\n", 
                         (iterations * 1000.0) / (totalTime / 1_000_000.0) / 1_000_000.0);
        
        if (avgTimeNs < 100) {
            System.out.println("└─ 性能评估: 优秀 ⭐⭐⭐⭐⭐ (目标: <100ns)");
        } else if (avgTimeNs < 200) {
            System.out.println("└─ 性能评估: 良好 ⭐⭐⭐⭐ (目标: <100ns)");
        } else {
            System.out.println("└─ 性能评估: 需要优化 ⭐⭐⭐ (目标: <100ns)");
        }
        
        objects.clear();
    }
    
    private static void testMediumObjectAllocation() {
        final int iterations = TEST_ITERATIONS / 10;
        List<MediumObject> objects = new ArrayList<>(iterations);
        
        System.out.println("├─ 测试参数: " + iterations + "个中等对象(~1KB)");
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            MediumObject obj = new MediumObject(64);
            objects.add(obj);
        }
        
        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgTimeNs = (double) totalTime / iterations;
        
        System.out.printf("├─ 总分配时间: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("├─ 平均分配时间: %.2f ns/对象\n", avgTimeNs);
        System.out.printf("├─ 分配速率: %.2f K对象/秒\n", 
                         (iterations * 1000.0) / (totalTime / 1_000_000.0) / 1000.0);
        
        if (avgTimeNs < 500) {
            System.out.println("└─ 性能评估: 优秀 ⭐⭐⭐⭐⭐ (目标: <500ns)");
        } else if (avgTimeNs < 1000) {
            System.out.println("└─ 性能评估: 良好 ⭐⭐⭐⭐ (目标: <500ns)");
        } else {
            System.out.println("└─ 性能评估: 需要优化 ⭐⭐⭐ (目标: <500ns)");
        }
        
        objects.clear();
    }
    
    private static void testLargeObjectAllocation() {
        final int iterations = 1000;
        List<LargeObject> objects = new ArrayList<>(iterations);
        
        System.out.println("├─ 测试参数: " + iterations + "个大对象(~64KB)");
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            LargeObject obj = new LargeObject(65536, 10);
            objects.add(obj);
        }
        
        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgTimeMicros = (double) totalTime / iterations / 1000.0;
        
        System.out.printf("├─ 总分配时间: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("├─ 平均分配时间: %.2f μs/对象\n", avgTimeMicros);
        System.out.printf("├─ 分配速率: %.2f 对象/秒\n", 
                         (iterations * 1000.0) / (totalTime / 1_000_000.0));
        
        if (avgTimeMicros < 5) {
            System.out.println("└─ 性能评估: 优秀 ⭐⭐⭐⭐⭐ (目标: <5μs)");
        } else if (avgTimeMicros < 10) {
            System.out.println("└─ 性能评估: 良好 ⭐⭐⭐⭐ (目标: <5μs)");
        } else {
            System.out.println("└─ 性能评估: 需要优化 ⭐⭐⭐ (目标: <5μs)");
        }
        
        objects.clear();
    }
    
    private static void testTLABEfficiency() {
        System.out.println("├─ TLAB效率测试:");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicLong totalAllocations = new AtomicLong(0);
        AtomicLong totalTime = new AtomicLong(0);
        
        for (int t = 0; t < THREAD_COUNT; t++) {
            executor.submit(() -> {
                try {
                    List<Object> localObjects = new ArrayList<>();
                    long startTime = System.nanoTime();
                    
                    for (int i = 0; i < TEST_ITERATIONS / THREAD_COUNT; i++) {
                        if (i % 3 == 0) {
                            localObjects.add(new SmallObject(i, i));
                        } else if (i % 3 == 1) {
                            localObjects.add(new MediumObject(16));
                        } else {
                            localObjects.add(new byte[1024]);
                        }
                        
                        if (i % 1000 == 0) {
                            localObjects.clear();
                        }
                    }
                    
                    long endTime = System.nanoTime();
                    totalAllocations.addAndGet(TEST_ITERATIONS / THREAD_COUNT);
                    totalTime.addAndGet(endTime - startTime);
                    
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
            executor.shutdown();
            
            double avgTimeNs = (double) totalTime.get() / totalAllocations.get();
            System.out.printf("│  ├─ 并发分配平均时间: %.2f ns/对象\n", avgTimeNs);
            System.out.printf("│  ├─ 总分配数: %d\n", totalAllocations.get());
            System.out.printf("│  └─ 并发效率: %.2f M对象/秒\n", 
                             (totalAllocations.get() * 1000.0) / (totalTime.get() / 1_000_000.0) / 1_000_000.0);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("│  └─ TLAB测试被中断");
        }
        
        System.out.println("└─ TLAB效率测试: 完成 ✅");
    }
    
    private static void runPhase3_GCPerformanceAnalysis() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段3: GC性能影响分析");
        System.out.println("=".repeat(60));
        
        // GC基线测试
        System.out.println("\n3.1 GC基线测试:");
        testGCBaseline();
        
        // 内存压力测试
        System.out.println("\n3.2 内存压力测试:");
        testMemoryPressure();
        
        // GC暂停时间分析
        System.out.println("\n3.3 GC暂停时间分析:");
        analyzeGCPauseTime();
    }
    
    private static void testGCBaseline() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        Map<String, Long> baselineCollections = new HashMap<>();
        Map<String, Long> baselineTime = new HashMap<>();
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            baselineCollections.put(gcBean.getName(), gcBean.getCollectionCount());
            baselineTime.put(gcBean.getName(), gcBean.getCollectionTime());
        }
        
        System.out.println("├─ GC基线数据:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("│  ├─ %s: %d次, %d ms\n", 
                             gcBean.getName(), 
                             gcBean.getCollectionCount(), 
                             gcBean.getCollectionTime());
        }
        
        // 执行分配操作
        List<Object> objects = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            objects.add(new MediumObject(32));
            if (i % 10000 == 0) {
                objects.clear();
            }
        }
        
        System.out.println("├─ 测试后GC变化:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long newCollections = gcBean.getCollectionCount();
            long newTime = gcBean.getCollectionTime();
            long deltaCollections = newCollections - baselineCollections.get(gcBean.getName());
            long deltaTime = newTime - baselineTime.get(gcBean.getName());
            
            System.out.printf("│  ├─ %s: +%d次, +%d ms\n", 
                             gcBean.getName(), deltaCollections, deltaTime);
        }
        
        System.out.println("└─ GC基线测试: 完成 ✅");
    }
    
    private static void testMemoryPressure() {
        System.out.println("├─ 内存压力测试:");
        
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        MemoryUsage beforeHeap = memoryBean.getHeapMemoryUsage();
        Map<String, Long> beforeGC = new HashMap<>();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            beforeGC.put(gcBean.getName(), gcBean.getCollectionCount());
        }
        
        long startTime = System.currentTimeMillis();
        
        List<byte[]> memoryPressure = new ArrayList<>();
        try {
            for (int i = 0; i < 1000; i++) {
                byte[] largeArray = new byte[1024 * 1024];
                Arrays.fill(largeArray, (byte) (i % 256));
                memoryPressure.add(largeArray);
                
                if (i % 100 == 0 && i > 0) {
                    for (int j = 0; j < memoryPressure.size() / 2; j++) {
                        memoryPressure.set(j, null);
                    }
                    memoryPressure.removeIf(Objects::isNull);
                }
                
                MemoryUsage currentHeap = memoryBean.getHeapMemoryUsage();
                double usagePercent = (double) currentHeap.getUsed() / currentHeap.getMax() * 100;
                
                if (usagePercent > 80) {
                    System.out.printf("│  ├─ 内存使用率达到 %.1f%%, 停止分配\n", usagePercent);
                    break;
                }
            }
        } catch (OutOfMemoryError e) {
            System.out.println("│  ├─ 触发OutOfMemoryError，清理内存");
            memoryPressure.clear();
            System.gc();
        }
        
        long endTime = System.currentTimeMillis();
        
        MemoryUsage afterHeap = memoryBean.getHeapMemoryUsage();
        
        System.out.printf("│  ├─ 测试时间: %d ms\n", endTime - startTime);
        System.out.printf("│  ├─ 堆使用变化: %.1f MB -> %.1f MB\n", 
                         beforeHeap.getUsed() / 1024.0 / 1024.0,
                         afterHeap.getUsed() / 1024.0 / 1024.0);
        
        System.out.println("│  ├─ GC活动:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long afterGC = gcBean.getCollectionCount();
            long gcDelta = afterGC - beforeGC.get(gcBean.getName());
            System.out.printf("│  │  ├─ %s: +%d次\n", gcBean.getName(), gcDelta);
        }
        
        memoryPressure.clear();
        System.gc();
        
        System.out.println("└─ 内存压力测试: 完成 ✅");
    }
    
    private static void analyzeGCPauseTime() {
        System.out.println("├─ GC暂停时间分析:");
        
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long collections = gcBean.getCollectionCount();
            long totalTime = gcBean.getCollectionTime();
            
            if (collections > 0) {
                double avgPause = (double) totalTime / collections;
                System.out.printf("│  ├─ %s:\n", gcBean.getName());
                System.out.printf("│  │  ├─ 总次数: %d\n", collections);
                System.out.printf("│  │  ├─ 总时间: %d ms\n", totalTime);
                System.out.printf("│  │  └─ 平均暂停: %.2f ms\n", avgPause);
                
                if (gcBean.getName().contains("G1 Young")) {
                    if (avgPause < 30) {
                        System.out.println("│  │     └─ 评估: 优秀 ⭐⭐⭐⭐⭐ (目标: <30ms)");
                    } else if (avgPause < 50) {
                        System.out.println("│  │     └─ 评估: 良好 ⭐⭐⭐⭐ (目标: <30ms)");
                    } else {
                        System.out.println("│  │     └─ 评估: 需要优化 ⭐⭐⭐ (目标: <30ms)");
                    }
                }
            } else {
                System.out.printf("│  ├─ %s: 无GC活动\n", gcBean.getName());
            }
        }
        
        System.out.println("└─ GC暂停时间分析: 完成 ✅");
    }
    
    private static void runPhase4_JITCompilerOptimization() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段4: JIT编译器优化验证");
        System.out.println("=".repeat(60));
        
        // 编译器状态检查
        System.out.println("\n4.1 编译器状态检查:");
        checkCompilerStatus();
        
        // 热点方法编译测试
        System.out.println("\n4.2 热点方法编译测试:");
        testHotMethodCompilation();
    }
    
    private static void checkCompilerStatus() {
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        
        System.out.println("├─ 编译器配置:");
        System.out.println("│  ├─ 编译器名称: " + compilationBean.getName());
        System.out.println("│  ├─ 支持编译时间监控: " + 
                          (compilationBean.isCompilationTimeMonitoringSupported() ? "是" : "否"));
        
        if (compilationBean.isCompilationTimeMonitoringSupported()) {
            System.out.printf("│  └─ 总编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
        }
        
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threads = threadBean.getThreadInfo(threadBean.getAllThreadIds());
        
        int c1Threads = 0;
        int c2Threads = 0;
        
        for (ThreadInfo thread : threads) {
            if (thread != null && thread.getThreadName() != null) {
                String name = thread.getThreadName();
                if (name.contains("C1 CompilerThread")) {
                    c1Threads++;
                } else if (name.contains("C2 CompilerThread")) {
                    c2Threads++;
                }
            }
        }
        
        System.out.println("├─ 编译器线程:");
        System.out.println("│  ├─ C1编译线程: " + c1Threads);
        System.out.println("│  └─ C2编译线程: " + c2Threads);
        
        System.out.println("└─ 编译器状态检查: 完成 ✅");
    }
    
    private static void testHotMethodCompilation() {
        System.out.println("├─ 热点方法编译测试:");
        
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        long beforeCompilationTime = compilationBean.isCompilationTimeMonitoringSupported() ? 
                                    compilationBean.getTotalCompilationTime() : 0;
        
        HotMethodTester tester = new HotMethodTester();
        
        System.out.println("│  ├─ 执行热点方法调用...");
        
        long result = 0;
        for (int i = 0; i < 50000; i++) {
            result += tester.hotMethod(i);
            result += tester.anotherHotMethod(i, i * 2);
        }
        
        long afterCompilationTime = compilationBean.isCompilationTimeMonitoringSupported() ? 
                                   compilationBean.getTotalCompilationTime() : 0;
        
        System.out.printf("│  ├─ 计算结果: %d\n", result);
        
        if (compilationBean.isCompilationTimeMonitoringSupported()) {
            long compilationDelta = afterCompilationTime - beforeCompilationTime;
            System.out.printf("│  ├─ 编译时间增量: %d ms\n", compilationDelta);
            
            if (compilationDelta > 0) {
                System.out.println("│  └─ 热点编译: 检测到编译活动 ✅");
            } else {
                System.out.println("│  └─ 热点编译: 可能已预编译或编译阈值未达到");
            }
        } else {
            System.out.println("│  └─ 热点编译: 无法监控编译时间");
        }
        
        System.out.println("└─ 热点方法编译测试: 完成 ✅");
    }
    
    static class HotMethodTester {
        public long hotMethod(int x) {
            return x * x + x - 1;
        }
        
        public long anotherHotMethod(int a, int b) {
            long result = 0;
            for (int i = 0; i < 10; i++) {
                result += (a + b) * i;
            }
            return result;
        }
    }
    
    private static void runPhase5_ThreadPerformanceTest() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段5: 线程性能测试");
        System.out.println("=".repeat(60));
        
        // 线程创建性能测试
        System.out.println("\n5.1 线程创建性能测试:");
        testThreadCreationPerformance();
        
        // 并发性能测试
        System.out.println("\n5.2 并发性能测试:");
        testConcurrentPerformance();
    }
    
    private static void testThreadCreationPerformance() {
        System.out.println("├─ 线程创建性能测试:");
        
        final int threadCount = 100;
        List<Thread> threads = new ArrayList<>(threadCount);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "TestThread-" + i);
            threads.add(thread);
        }
        
        long creationTime = System.nanoTime();
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        long endTime = System.nanoTime();
        
        double creationTimeMs = (creationTime - startTime) / 1_000_000.0;
        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("│  ├─ 线程数量: %d\n", threadCount);
        System.out.printf("│  ├─ 创建时间: %.2f ms (%.2f μs/线程)\n", 
                         creationTimeMs, creationTimeMs * 1000 / threadCount);
        System.out.printf("│  └─ 总执行时间: %.2f ms\n", totalTimeMs);
        
        System.out.println("└─ 线程创建性能测试: 完成 ✅");
    }
    
    private static void testConcurrentPerformance() {
        System.out.println("├─ 并发性能测试:");
        
        final int iterations = 100000;
        final int threadCount = THREAD_COUNT;
        
        AtomicLong atomicCounter = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        long startTime = System.nanoTime();
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations / threadCount; i++) {
                        atomicCounter.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
            long endTime = System.nanoTime();
            
            double timeMs = (endTime - startTime) / 1_000_000.0;
            double opsPerSecond = iterations * 1000.0 / timeMs;
            
            System.out.printf("│  ├─ 原子操作数: %d\n", iterations);
            System.out.printf("│  ├─ 执行时间: %.2f ms\n", timeMs);
            System.out.printf("│  ├─ 最终计数: %d\n", atomicCounter.get());
            System.out.printf("│  └─ 操作速率: %.2f M ops/秒\n", opsPerSecond / 1_000_000);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("│  └─ 并发测试被中断");
        } finally {
            executor.shutdown();
        }
        
        System.out.println("└─ 并发性能测试: 完成 ✅");
    }
    
    private static void runPhase6_PerformanceBenchmark() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段6: 性能基准建立");
        System.out.println("=".repeat(60));
        
        System.out.println("\n6.1 建立性能基准:");
        
        // CPU密集型基准
        System.out.println("├─ CPU密集型基准测试:");
        double cpuBenchmark = runCPUBenchmark();
        System.out.printf("│  └─ CPU基准: %.2f M ops/秒\n", cpuBenchmark);
        
        // 内存分配基准
        System.out.println("├─ 内存分配基准测试:");
        double memoryBenchmark = runMemoryBenchmark();
        System.out.printf("│  └─ 内存基准: %.2f M allocs/秒\n", memoryBenchmark);
        
        System.out.println("└─ 性能基准建立: 完成 ✅");
    }
    
    private static double runCPUBenchmark() {
        final int iterations = 1000000;
        
        long startTime = System.nanoTime();
        
        double result = 0;
        for (int i = 0; i < iterations; i++) {
            result += Math.sqrt(i) * Math.sin(i) + Math.cos(i * 2);
        }
        
        long endTime = System.nanoTime();
        double timeSeconds = (endTime - startTime) / 1_000_000_000.0;
        
        if (result == Double.NEGATIVE_INFINITY) {
            System.out.println("Unexpected result");
        }
        
        return iterations / timeSeconds / 1_000_000;
    }
    
    private static double runMemoryBenchmark() {
        final int iterations = 100000;
        
        long startTime = System.nanoTime();
        
        List<Object> objects = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            objects.add(new SmallObject(i, i * 2));
        }
        
        long endTime = System.nanoTime();
        double timeSeconds = (endTime - startTime) / 1_000_000_000.0;
        
        objects.clear();
        
        return iterations / timeSeconds / 1_000_000;
    }
    
    private static void runPhase7_FinalPerformanceAssessment() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段7: 最终性能评估");
        System.out.println("=".repeat(60));
        
        System.out.println("\n7.1 综合性能评估:");
        generateFinalReport();
    }
    
    private static void generateFinalReport() {
        System.out.println("├─ 性能评估报告:");
        
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        
        // 内存指标
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double heapUsagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
        
        System.out.printf("│  ├─ 堆内存使用率: %.1f%%\n", heapUsagePercent);
        System.out.printf("│  ├─ 堆内存大小: %.1f GB\n", heapUsage.getMax() / 1024.0 / 1024.0 / 1024.0);
        
        // GC指标
        long totalGCCount = 0;
        long totalGCTime = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            totalGCCount += gcBean.getCollectionCount();
            totalGCTime += gcBean.getCollectionTime();
        }
        
        System.out.printf("│  ├─ 总GC次数: %d\n", totalGCCount);
        System.out.printf("│  ├─ 总GC时间: %d ms\n", totalGCTime);
        
        if (totalGCCount > 0) {
            System.out.printf("│  ├─ 平均GC暂停: %.2f ms\n", (double) totalGCTime / totalGCCount);
        }
        
        // 编译器指标
        if (compilationBean.isCompilationTimeMonitoringSupported()) {
            System.out.printf("│  ├─ 总编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
        }
        
        // 线程指标
        System.out.printf("│  ├─ 当前线程数: %d\n", threadBean.getThreadCount());
        
        // 综合评分
        int memoryScore = heapUsagePercent < 70 ? 90 : (heapUsagePercent < 85 ? 80 : 70);
        int gcScore = totalGCCount < 50 ? 95 : (totalGCCount < 100 ? 85 : 75);
        int overallScore = (memoryScore + gcScore) / 2;
        
        System.out.println("│  ├─ 性能评分:");
        System.out.printf("│  │  ├─ 内存管理: %d/100\n", memoryScore);
        System.out.printf("│  │  ├─ GC性能: %d/100\n", gcScore);
        System.out.printf("│  │  └─ 综合评分: %d/100 ", overallScore);
        
        if (overallScore >= 90) {
            System.out.println("⭐⭐⭐⭐⭐ 优秀");
        } else if (overallScore >= 80) {
            System.out.println("⭐⭐⭐⭐ 良好");
        } else {
            System.out.println("⭐⭐⭐ 需要优化");
        }
        
        System.out.println("└─ 最终性能评估: 完成 ✅");
    }
}