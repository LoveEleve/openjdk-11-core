/**
 * 第09章：JVM性能调优与监控实战 - 综合测试程序
 * 
 * 本程序用于验证JVM性能调优效果和监控系统功能
 * 包含多种性能测试场景和监控指标收集
 * 
 * 编译: javac PerformanceTuningTest.java
 * 运行: java -Xms8g -Xmx8g -XX:+UseG1GC PerformanceTuningTest
 * 
 * 性能监控运行:
 * java -Xms8g -Xmx8g -XX:+UseG1GC \
 *      -XX:+PrintGC -XX:+PrintGCDetails \
 *      -XX:+FlightRecorder \
 *      -XX:StartFlightRecording=duration=300s,filename=perf_test.jfr \
 *      PerformanceTuningTest
 */

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;

public class PerformanceTuningTest {
    
    // 测试配置
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int TEST_DURATION_SECONDS = 60;
    private static final int WARMUP_DURATION_SECONDS = 30;
    
    // 性能监控
    private static final PerformanceMonitor monitor = new PerformanceMonitor();
    private static final AtomicBoolean running = new AtomicBoolean(true);
    
    // 测试统计
    private static final AtomicLong totalOperations = new AtomicLong(0);
    private static final AtomicLong totalLatency = new AtomicLong(0);
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    
    public static void main(String[] args) {
        System.out.println("=== JVM性能调优与监控实战测试 ===");
        System.out.println("JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC");
        System.out.println("测试线程数: " + THREAD_COUNT);
        System.out.println("测试持续时间: " + TEST_DURATION_SECONDS + " 秒");
        
        try {
            // 启动性能监控
            monitor.start();
            
            // 显示初始状态
            printInitialStatus();
            
            // 执行性能测试套件
            runPerformanceTestSuite();
            
            // 显示最终结果
            printFinalResults();
            
        } catch (Exception e) {
            System.err.println("测试执行失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 停止监控
            monitor.stop();
        }
    }
    
    /**
     * 显示初始状态
     */
    private static void printInitialStatus() {
        System.out.println("\n=== 初始系统状态 ===");
        
        // JVM信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        System.out.println("JVM信息:");
        System.out.println("  JVM名称: " + runtimeBean.getVmName());
        System.out.println("  JVM版本: " + runtimeBean.getVmVersion());
        System.out.println("  启动时间: " + new Date(runtimeBean.getStartTime()));
        
        // 内存信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("内存信息:");
        System.out.printf("  堆内存: %d MB / %d MB (%.1f%%)\n",
                         heapUsage.getUsed() / 1024 / 1024,
                         heapUsage.getMax() / 1024 / 1024,
                         (double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        // GC信息
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("GC信息:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("  %s: %d 次收集, 总耗时 %d ms\n",
                             gcBean.getName(),
                             gcBean.getCollectionCount(),
                             gcBean.getCollectionTime());
        }
        
        // 线程信息
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        System.out.println("线程信息:");
        System.out.printf("  活跃线程: %d (守护线程: %d)\n",
                         threadBean.getThreadCount(),
                         threadBean.getDaemonThreadCount());
    }
    
    /**
     * 执行性能测试套件
     */
    private static void runPerformanceTestSuite() throws InterruptedException {
        System.out.println("\n=== 开始性能测试套件 ===");
        
        // 1. 预热阶段
        runWarmupPhase();
        
        // 2. 内存分配性能测试
        runMemoryAllocationTest();
        
        // 3. CPU密集型性能测试
        runCPUIntensiveTest();
        
        // 4. 多线程并发测试
        runConcurrencyTest();
        
        // 5. GC压力测试
        runGCStressTest();
        
        // 6. 综合性能测试
        runComprehensiveTest();
    }
    
    /**
     * 1. 预热阶段
     */
    private static void runWarmupPhase() throws InterruptedException {
        System.out.println("\n1. JVM预热阶段 (" + WARMUP_DURATION_SECONDS + "秒)");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + WARMUP_DURATION_SECONDS * 1000;
        
        // 提交预热任务
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() < endTime) {
                    // 执行各种操作以触发JIT编译
                    performWarmupOperations();
                }
            });
        }
        
        // 等待预热完成
        Thread.sleep(WARMUP_DURATION_SECONDS * 1000);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        System.out.println("预热完成");
        
        // 显示预热后的编译统计
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null) {
            System.out.printf("编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
        }
    }
    
    /**
     * 预热操作
     */
    private static void performWarmupOperations() {
        // CPU计算
        double result = 0;
        for (int i = 0; i < 1000; i++) {
            result += Math.sqrt(i) * Math.PI;
        }
        
        // 内存分配
        List<Object> objects = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            objects.add(new TestObject(i));
        }
        
        // 字符串操作
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("warmup-").append(i).append("-");
        }
        
        // 集合操作
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            map.put("key" + i, i);
        }
    }
    
    /**
     * 2. 内存分配性能测试
     */
    private static void runMemoryAllocationTest() {
        System.out.println("\n2. 内存分配性能测试");
        
        long startTime = System.nanoTime();
        
        // 测试小对象分配
        List<SmallObject> smallObjects = new ArrayList<>();
        for (int i = 0; i < 1000000; i++) {
            smallObjects.add(new SmallObject(i));
        }
        
        long smallObjectTime = System.nanoTime() - startTime;
        
        // 测试大对象分配
        startTime = System.nanoTime();
        List<LargeObject> largeObjects = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            largeObjects.add(new LargeObject(i));
        }
        
        long largeObjectTime = System.nanoTime() - startTime;
        
        System.out.printf("小对象分配耗时: %.2f ms (100万个对象)\n", smallObjectTime / 1_000_000.0);
        System.out.printf("大对象分配耗时: %.2f ms (1万个对象)\n", largeObjectTime / 1_000_000.0);
        
        // 清理对象以触发GC
        smallObjects.clear();
        largeObjects.clear();
        System.gc();
    }
    
    /**
     * 3. CPU密集型性能测试
     */
    private static void runCPUIntensiveTest() throws InterruptedException {
        System.out.println("\n3. CPU密集型性能测试");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicLong totalCalculations = new AtomicLong(0);
        
        long startTime = System.currentTimeMillis();
        
        // 提交CPU密集型任务
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    long calculations = performCPUIntensiveTask(threadId);
                    totalCalculations.addAndGet(calculations);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待完成
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        long duration = endTime - startTime;
        double throughput = totalCalculations.get() / (duration / 1000.0);
        
        System.out.printf("CPU测试完成，耗时: %d ms\n", duration);
        System.out.printf("总计算量: %d\n", totalCalculations.get());
        System.out.printf("吞吐量: %.2f 计算/秒\n", throughput);
    }
    
    /**
     * CPU密集型任务
     */
    private static long performCPUIntensiveTask(int threadId) {
        long calculations = 0;
        long endTime = System.currentTimeMillis() + 10000; // 10秒
        
        while (System.currentTimeMillis() < endTime) {
            // 数学计算
            for (int i = 0; i < 10000; i++) {
                double result = Math.sqrt(threadId * i) * Math.PI;
                result = Math.sin(result) + Math.cos(result);
                calculations++;
            }
            
            // 字符串处理
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("thread-").append(threadId).append("-calc-").append(i);
            }
            String result = sb.toString();
            calculations += result.length();
        }
        
        return calculations;
    }
    
    /**
     * 4. 多线程并发测试
     */
    private static void runConcurrencyTest() throws InterruptedException {
        System.out.println("\n4. 多线程并发测试");
        
        // 共享数据结构
        ConcurrentHashMap<String, AtomicInteger> sharedMap = new ConcurrentHashMap<>();
        AtomicInteger sharedCounter = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT * 2);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 2);
        
        long startTime = System.currentTimeMillis();
        
        // 启动并发任务
        for (int i = 0; i < THREAD_COUNT * 2; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    performConcurrentOperations(threadId, sharedMap, sharedCounter);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待完成
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        System.out.printf("并发测试完成，耗时: %d ms\n", endTime - startTime);
        System.out.printf("共享计数器最终值: %d\n", sharedCounter.get());
        System.out.printf("共享Map大小: %d\n", sharedMap.size());
    }
    
    /**
     * 并发操作
     */
    private static void performConcurrentOperations(int threadId, 
                                                  ConcurrentHashMap<String, AtomicInteger> sharedMap,
                                                  AtomicInteger sharedCounter) {
        long endTime = System.currentTimeMillis() + 5000; // 5秒
        
        while (System.currentTimeMillis() < endTime) {
            // 原子操作
            sharedCounter.incrementAndGet();
            
            // 并发Map操作
            String key = "thread-" + (threadId % 100);
            sharedMap.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
            
            // 读取操作
            AtomicInteger value = sharedMap.get(key);
            if (value != null) {
                value.get();
            }
            
            // 模拟一些处理时间
            try {
                Thread.sleep(0, 100000); // 0.1ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * 5. GC压力测试
     */
    private static void runGCStressTest() {
        System.out.println("\n5. GC压力测试");
        
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        // 记录GC前状态
        Map<String, Long> gcCountBefore = new HashMap<>();
        Map<String, Long> gcTimeBefore = new HashMap<>();
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            gcCountBefore.put(gcBean.getName(), gcBean.getCollectionCount());
            gcTimeBefore.put(gcBean.getName(), gcBean.getCollectionTime());
        }
        
        long startTime = System.currentTimeMillis();
        
        // 执行大量内存分配以产生GC压力
        List<Object> objects = new ArrayList<>();
        for (int round = 0; round < 100; round++) {
            // 分配大量对象
            for (int i = 0; i < 50000; i++) {
                objects.add(new MediumObject(round * 50000 + i));
            }
            
            // 定期清理以触发GC
            if (round % 10 == 0) {
                objects.clear();
                System.gc(); // 建议GC
                
                // 显示当前内存使用
                MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                System.out.printf("第 %d 轮后堆使用: %d MB\n", 
                                 round, heapUsage.getUsed() / 1024 / 1024);
            }
        }
        
        long endTime = System.currentTimeMillis();
        
        // 统计GC效果
        System.out.printf("GC压力测试完成，耗时: %d ms\n", endTime - startTime);
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcCountAfter = gcBean.getCollectionCount();
            long gcTimeAfter = gcBean.getCollectionTime();
            
            long gcCount = gcCountAfter - gcCountBefore.get(gcBean.getName());
            long gcTime = gcTimeAfter - gcTimeBefore.get(gcBean.getName());
            
            if (gcCount > 0) {
                System.out.printf("GC %s: %d 次收集, 总耗时 %d ms, 平均 %.2f ms\n",
                                 gcBean.getName(), gcCount, gcTime, (double) gcTime / gcCount);
            }
        }
    }
    
    /**
     * 6. 综合性能测试
     */
    private static void runComprehensiveTest() throws InterruptedException {
        System.out.println("\n6. 综合性能测试");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        // 重置统计
        totalOperations.set(0);
        totalLatency.set(0);
        errorCount.set(0);
        running.set(true);
        
        long startTime = System.currentTimeMillis();
        
        // 启动工作线程
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> performComprehensiveWorkload(threadId));
        }
        
        // 运行指定时间
        Thread.sleep(TEST_DURATION_SECONDS * 1000);
        running.set(false);
        
        // 等待线程完成
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // 计算性能指标
        long ops = totalOperations.get();
        double throughput = ops / (duration / 1000.0);
        double avgLatency = totalLatency.get() / (double) ops / 1_000_000.0; // ms
        
        System.out.printf("综合测试完成，耗时: %d ms\n", duration);
        System.out.printf("总操作数: %d\n", ops);
        System.out.printf("吞吐量: %.2f ops/sec\n", throughput);
        System.out.printf("平均延迟: %.3f ms\n", avgLatency);
        System.out.printf("错误数: %d\n", errorCount.get());
    }
    
    /**
     * 综合工作负载
     */
    private static void performComprehensiveWorkload(int threadId) {
        Random random = new Random(threadId);
        
        while (running.get()) {
            long operationStart = System.nanoTime();
            
            try {
                // 随机选择操作类型
                int operationType = random.nextInt(4);
                
                switch (operationType) {
                    case 0:
                        // CPU密集型操作
                        performCPUOperation(random);
                        break;
                    case 1:
                        // 内存操作
                        performMemoryOperation(random);
                        break;
                    case 2:
                        // I/O操作
                        performIOOperation(random);
                        break;
                    case 3:
                        // 混合操作
                        performMixedOperation(random);
                        break;
                }
                
                totalOperations.incrementAndGet();
                
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
            
            long operationEnd = System.nanoTime();
            totalLatency.addAndGet(operationEnd - operationStart);
        }
    }
    
    private static void performCPUOperation(Random random) {
        int iterations = 1000 + random.nextInt(1000);
        double result = 0;
        for (int i = 0; i < iterations; i++) {
            result += Math.sqrt(i) * Math.PI;
        }
    }
    
    private static void performMemoryOperation(Random random) {
        int size = 100 + random.nextInt(900);
        List<Object> objects = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            objects.add(new TestObject(i));
        }
    }
    
    private static void performIOOperation(Random random) {
        try {
            // 模拟文件I/O
            Path tempFile = Files.createTempFile("perf_test", ".tmp");
            byte[] data = new byte[1024 + random.nextInt(4096)];
            random.nextBytes(data);
            
            Files.write(tempFile, data);
            Files.readAllBytes(tempFile);
            Files.deleteIfExists(tempFile);
            
        } catch (IOException e) {
            // 忽略I/O错误
        }
    }
    
    private static void performMixedOperation(Random random) {
        // 组合操作
        performCPUOperation(random);
        performMemoryOperation(random);
        
        // 字符串操作
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("mixed-op-").append(random.nextInt(1000)).append("-");
        }
    }
    
    /**
     * 显示最终结果
     */
    private static void printFinalResults() {
        System.out.println("\n=== 最终测试结果 ===");
        
        // 内存使用情况
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("内存使用情况:");
        System.out.printf("  堆内存: %d MB / %d MB (%.1f%%)\n",
                         heapUsage.getUsed() / 1024 / 1024,
                         heapUsage.getMax() / 1024 / 1024,
                         (double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        // GC统计
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("GC统计:");
        long totalGCTime = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcTime = gcBean.getCollectionTime();
            totalGCTime += gcTime;
            System.out.printf("  %s: %d 次收集, 总耗时 %d ms\n",
                             gcBean.getName(),
                             gcBean.getCollectionCount(),
                             gcTime);
        }
        
        // GC开销
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        double gcOverhead = (double) totalGCTime / uptime * 100;
        System.out.printf("  GC开销: %.2f%%\n", gcOverhead);
        
        // 线程统计
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        System.out.println("线程统计:");
        System.out.printf("  当前线程数: %d (守护线程: %d)\n",
                         threadBean.getThreadCount(),
                         threadBean.getDaemonThreadCount());
        System.out.printf("  峰值线程数: %d\n", threadBean.getPeakThreadCount());
        
        // 编译统计
        CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
        if (compilationBean != null) {
            System.out.println("编译统计:");
            System.out.printf("  总编译时间: %d ms\n", compilationBean.getTotalCompilationTime());
        }
        
        // 性能监控结果
        monitor.printSummary();
        
        System.out.println("\n测试完成！");
    }
    
    // ==================== 内部类定义 ====================
    
    /**
     * 测试对象
     */
    static class TestObject {
        private final int id;
        private final String name;
        private final long timestamp;
        
        public TestObject(int id) {
            this.id = id;
            this.name = "TestObject-" + id;
            this.timestamp = System.currentTimeMillis();
        }
        
        public int getId() { return id; }
        public String getName() { return name; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * 小对象
     */
    static class SmallObject {
        private final int value;
        
        public SmallObject(int value) {
            this.value = value;
        }
        
        public int getValue() { return value; }
    }
    
    /**
     * 中等对象
     */
    static class MediumObject {
        private final int id;
        private final byte[] data;
        
        public MediumObject(int id) {
            this.id = id;
            this.data = new byte[1024]; // 1KB
            Arrays.fill(data, (byte) (id % 256));
        }
        
        public int getId() { return id; }
        public byte[] getData() { return data; }
    }
    
    /**
     * 大对象
     */
    static class LargeObject {
        private final int id;
        private final byte[] data;
        private final Map<String, Object> properties;
        
        public LargeObject(int id) {
            this.id = id;
            this.data = new byte[10240]; // 10KB
            this.properties = new HashMap<>();
            
            // 填充数据
            Arrays.fill(data, (byte) (id % 256));
            
            // 填充属性
            for (int i = 0; i < 50; i++) {
                properties.put("prop" + i, "value" + (id + i));
            }
        }
        
        public int getId() { return id; }
        public byte[] getData() { return data; }
        public Map<String, Object> getProperties() { return properties; }
    }
    
    /**
     * 性能监控器
     */
    static class PerformanceMonitor {
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private final List<PerformanceSnapshot> snapshots = new ArrayList<>();
        
        public void start() {
            scheduler.scheduleAtFixedRate(this::takeSnapshot, 0, 5, TimeUnit.SECONDS);
        }
        
        public void stop() {
            scheduler.shutdown();
        }
        
        private void takeSnapshot() {
            PerformanceSnapshot snapshot = new PerformanceSnapshot();
            
            // 内存快照
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            snapshot.heapUsed = heapUsage.getUsed();
            snapshot.heapMax = heapUsage.getMax();
            
            // GC快照
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            long totalGCTime = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                totalGCTime += gcBean.getCollectionTime();
            }
            snapshot.gcTime = totalGCTime;
            
            // 线程快照
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            snapshot.threadCount = threadBean.getThreadCount();
            
            snapshot.timestamp = System.currentTimeMillis();
            snapshots.add(snapshot);
        }
        
        public void printSummary() {
            if (snapshots.isEmpty()) return;
            
            System.out.println("\n性能监控摘要:");
            System.out.printf("  监控快照数: %d\n", snapshots.size());
            
            // 计算平均值
            double avgHeapUsage = snapshots.stream()
                .mapToDouble(s -> (double) s.heapUsed / s.heapMax * 100)
                .average().orElse(0.0);
            
            double avgThreadCount = snapshots.stream()
                .mapToInt(s -> s.threadCount)
                .average().orElse(0.0);
            
            System.out.printf("  平均堆使用率: %.2f%%\n", avgHeapUsage);
            System.out.printf("  平均线程数: %.1f\n", avgThreadCount);
        }
        
        static class PerformanceSnapshot {
            long timestamp;
            long heapUsed;
            long heapMax;
            long gcTime;
            int threadCount;
        }
    }
}