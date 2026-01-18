/**
 * JVM故障诊断与排查深度分析测试程序
 * 
 * 本程序模拟各种JVM故障场景，用于验证故障诊断机制的有效性
 * 基于OpenJDK 11，标准配置：-Xms=8GB -Xmx=8GB，G1 GC
 * 
 * 编译命令：
 * javac -cp . JVMDiagnosticsAnalysisTest.java
 * 
 * 运行命令：
 * java -cp . -Xms8g -Xmx8g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions \
 *      -XX:+LogVMOutput -XX:+TraceClassLoading -XX:+PrintGC \
 *      -XX:+PrintGCDetails -XX:+PrintGCTimeStamps \
 *      JVMDiagnosticsAnalysisTest
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.nio.*;
import java.lang.reflect.*;

public class JVMDiagnosticsAnalysisTest {
    
    // 测试配置常量
    private static final int THREAD_POOL_SIZE = 20;
    private static final int MEMORY_ALLOCATION_SIZE = 1024 * 1024; // 1MB
    private static final int TEST_DURATION_SECONDS = 300; // 5分钟
    
    // 统计变量
    private static final AtomicLong totalAllocations = new AtomicLong(0);
    private static final AtomicLong totalDeallocations = new AtomicLong(0);
    private static final AtomicInteger activeThreads = new AtomicInteger(0);
    private static final AtomicInteger deadlockCount = new AtomicInteger(0);
    
    // 共享资源用于死锁测试
    private static final Object lock1 = new Object();
    private static final Object lock2 = new Object();
    
    // 内存泄漏模拟
    private static final List<byte[]> memoryLeakList = new ArrayList<>();
    private static final Map<String, Object> classLoaderLeakMap = new HashMap<>();
    
    public static void main(String[] args) {
        System.out.println("=== JVM故障诊断与排查深度分析测试 ===");
        System.out.println("开始时间: " + new Date());
        
        // 打印JVM信息
        printJVMInfo();
        
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        
        try {
            // 阶段1：环境验证和基线建立
            System.out.println("\n阶段1：环境验证和基线建立");
            verifyEnvironment();
            establishBaseline();
            
            // 阶段2：内存故障模拟测试
            System.out.println("\n阶段2：内存故障模拟测试");
            executor.submit(new MemoryLeakSimulator());
            executor.submit(new MetaspaceLeakSimulator());
            executor.submit(new DirectMemoryLeakSimulator());
            
            // 阶段3：线程故障模拟测试
            System.out.println("\n阶段3：线程故障模拟测试");
            executor.submit(new DeadlockSimulator());
            executor.submit(new ThreadLeakSimulator());
            executor.submit(new HighCPUSimulator());
            
            // 阶段4：GC压力测试
            System.out.println("\n阶段4：GC压力测试");
            executor.submit(new GCPressureSimulator());
            executor.submit(new LargeObjectSimulator());
            
            // 阶段5：JIT编译器压力测试
            System.out.println("\n阶段5：JIT编译器压力测试");
            executor.submit(new CompilerStressSimulator());
            executor.submit(new CodeCacheStressSimulator());
            
            // 阶段6：综合故障场景测试
            System.out.println("\n阶段6：综合故障场景测试");
            executor.submit(new ComprehensiveStressSimulator());
            
            // 阶段7：监控和诊断验证
            System.out.println("\n阶段7：监控和诊断验证");
            monitorAndDiagnose(executor);
            
        } catch (Exception e) {
            System.err.println("测试执行异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 清理资源
            cleanup(executor);
        }
        
        // 生成最终报告
        generateFinalReport();
    }
    
    /**
     * 打印JVM基本信息
     */
    private static void printJVMInfo() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        
        System.out.println("JVM基本信息:");
        System.out.println("  JVM名称: " + runtime.getVmName());
        System.out.println("  JVM版本: " + runtime.getVmVersion());
        System.out.println("  JVM供应商: " + runtime.getVmVendor());
        System.out.println("  启动时间: " + new Date(runtime.getStartTime()));
        System.out.println("  运行时间: " + runtime.getUptime() + "ms");
        
        MemoryUsage heapUsage = memory.getHeapMemoryUsage();
        System.out.println("  堆内存: " + formatBytes(heapUsage.getUsed()) + 
                          "/" + formatBytes(heapUsage.getMax()));
        
        MemoryUsage nonHeapUsage = memory.getNonHeapMemoryUsage();
        System.out.println("  非堆内存: " + formatBytes(nonHeapUsage.getUsed()) + 
                          "/" + formatBytes(nonHeapUsage.getMax()));
        
        System.out.println("  可用处理器: " + Runtime.getRuntime().availableProcessors());
    }
    
    /**
     * 环境验证
     */
    private static void verifyEnvironment() {
        System.out.println("环境验证:");
        
        // 验证堆大小配置
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memory.getHeapMemoryUsage();
        long heapMax = heapUsage.getMax();
        long expectedHeap = 8L * 1024 * 1024 * 1024; // 8GB
        
        System.out.println("  堆大小验证: " + formatBytes(heapMax));
        if (Math.abs(heapMax - expectedHeap) < 100 * 1024 * 1024) { // 100MB误差
            System.out.println("  ✅ 堆大小配置正确");
        } else {
            System.out.println("  ❌ 堆大小配置异常");
        }
        
        // 验证GC配置
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        boolean g1Found = false;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.println("  GC: " + gcBean.getName());
            if (gcBean.getName().contains("G1")) {
                g1Found = true;
            }
        }
        
        if (g1Found) {
            System.out.println("  ✅ G1 GC配置正确");
        } else {
            System.out.println("  ❌ G1 GC配置异常");
        }
        
        // 验证编译器配置
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        if (compilation != null) {
            System.out.println("  编译器: " + compilation.getName());
            System.out.println("  ✅ JIT编译器可用");
        } else {
            System.out.println("  ❌ JIT编译器不可用");
        }
    }
    
    /**
     * 建立性能基线
     */
    private static void establishBaseline() {
        System.out.println("建立性能基线:");
        
        // CPU基准测试
        long startTime = System.nanoTime();
        long operations = 0;
        long endTime = startTime + 1_000_000_000L; // 1秒
        
        while (System.nanoTime() < endTime) {
            Math.sqrt(Math.random() * 1000);
            operations++;
        }
        
        double cpuBenchmark = operations / 1_000_000.0; // M ops/sec
        System.out.println("  CPU基准: " + String.format("%.2f", cpuBenchmark) + " M ops/秒");
        
        // 内存分配基准测试
        startTime = System.nanoTime();
        long allocations = 0;
        endTime = startTime + 1_000_000_000L; // 1秒
        
        while (System.nanoTime() < endTime) {
            byte[] buffer = new byte[1024]; // 1KB分配
            allocations++;
        }
        
        double memoryBenchmark = allocations / 1_000_000.0; // M allocs/sec
        System.out.println("  内存基准: " + String.format("%.2f", memoryBenchmark) + " M allocs/秒");
        
        // GC基准测试
        System.gc();
        long gcCountBefore = getTotalGCCount();
        long gcTimeBefore = getTotalGCTime();
        
        // 分配一些内存触发GC
        List<byte[]> tempList = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tempList.add(new byte[1024 * 1024]); // 1MB
        }
        tempList.clear();
        System.gc();
        
        long gcCountAfter = getTotalGCCount();
        long gcTimeAfter = getTotalGCTime();
        
        System.out.println("  GC基准: " + (gcCountAfter - gcCountBefore) + 
                          "次GC, " + (gcTimeAfter - gcTimeBefore) + "ms");
    }
    
    /**
     * 内存泄漏模拟器
     */
    static class MemoryLeakSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动内存泄漏模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 模拟内存泄漏：持续添加对象到静态集合
                    byte[] leakData = new byte[MEMORY_ALLOCATION_SIZE];
                    Arrays.fill(leakData, (byte) 0xAA);
                    memoryLeakList.add(leakData);
                    
                    totalAllocations.incrementAndGet();
                    
                    // 偶尔清理一些数据，但不是全部
                    if (memoryLeakList.size() > 1000 && Math.random() < 0.1) {
                        for (int i = 0; i < 100; i++) {
                            if (!memoryLeakList.isEmpty()) {
                                memoryLeakList.remove(0);
                                totalDeallocations.incrementAndGet();
                            }
                        }
                    }
                    
                    Thread.sleep(100); // 100ms间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Metaspace泄漏模拟器
     */
    static class MetaspaceLeakSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动Metaspace泄漏模拟器");
            
            try {
                int classCounter = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    // 动态生成类模拟Metaspace泄漏
                    String className = "DynamicClass" + (classCounter++);
                    generateDynamicClass(className);
                    
                    Thread.sleep(1000); // 1秒间隔
                }
            } catch (Exception e) {
                System.err.println("Metaspace泄漏模拟异常: " + e.getMessage());
            }
        }
        
        private void generateDynamicClass(String className) {
            try {
                // 使用反射和动态代理模拟类生成
                ClassLoader customLoader = new CustomClassLoader();
                classLoaderLeakMap.put(className, customLoader);
            } catch (Exception e) {
                // 忽略异常，继续测试
            }
        }
    }
    
    /**
     * 自定义类加载器
     */
    static class CustomClassLoader extends ClassLoader {
        private static int counter = 0;
        private final int id = counter++;
        
        @Override
        public String toString() {
            return "CustomClassLoader-" + id;
        }
    }
    
    /**
     * 直接内存泄漏模拟器
     */
    static class DirectMemoryLeakSimulator implements Runnable {
        private final List<ByteBuffer> directBuffers = new ArrayList<>();
        
        @Override
        public void run() {
            System.out.println("启动直接内存泄漏模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 分配直接内存
                    ByteBuffer directBuffer = ByteBuffer.allocateDirect(1024 * 1024); // 1MB
                    directBuffers.add(directBuffer);
                    
                    // 偶尔清理一些缓冲区
                    if (directBuffers.size() > 100 && Math.random() < 0.2) {
                        for (int i = 0; i < 10; i++) {
                            if (!directBuffers.isEmpty()) {
                                directBuffers.remove(0);
                            }
                        }
                    }
                    
                    Thread.sleep(500); // 500ms间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 死锁模拟器
     */
    static class DeadlockSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动死锁模拟器");
            
            // 创建两个线程，互相等待对方的锁
            Thread thread1 = new Thread(() -> {
                synchronized (lock1) {
                    System.out.println("线程1获得lock1");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (lock2) {
                        System.out.println("线程1获得lock2");
                    }
                }
            }, "DeadlockThread1");
            
            Thread thread2 = new Thread(() -> {
                synchronized (lock2) {
                    System.out.println("线程2获得lock2");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (lock1) {
                        System.out.println("线程2获得lock1");
                    }
                }
            }, "DeadlockThread2");
            
            thread1.start();
            thread2.start();
            
            // 等待死锁发生
            try {
                Thread.sleep(5000); // 5秒后检测死锁
                
                ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                long[] deadlockedThreads = threadBean.findDeadlockedThreads();
                
                if (deadlockedThreads != null) {
                    System.out.println("检测到死锁，涉及线程数: " + deadlockedThreads.length);
                    deadlockCount.incrementAndGet();
                    
                    // 打印死锁信息
                    ThreadInfo[] threadInfos = threadBean.getThreadInfo(deadlockedThreads);
                    for (ThreadInfo threadInfo : threadInfos) {
                        System.out.println("死锁线程: " + threadInfo.getThreadName() + 
                                         " 状态: " + threadInfo.getThreadState());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 线程泄漏模拟器
     */
    static class ThreadLeakSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动线程泄漏模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 创建线程但不正确管理
                    Thread leakThread = new Thread(() -> {
                        activeThreads.incrementAndGet();
                        try {
                            // 线程长时间运行
                            Thread.sleep(60000); // 1分钟
                        } catch (InterruptedException e) {
                            // 不处理中断，模拟线程泄漏
                        } finally {
                            activeThreads.decrementAndGet();
                        }
                    }, "LeakThread-" + System.currentTimeMillis());
                    
                    leakThread.start();
                    
                    Thread.sleep(2000); // 2秒间隔创建新线程
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 高CPU使用率模拟器
     */
    static class HighCPUSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动高CPU使用率模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // CPU密集型计算
                    for (int i = 0; i < 1000000; i++) {
                        Math.sqrt(Math.random() * Double.MAX_VALUE);
                        Math.sin(Math.random() * Math.PI);
                        Math.log(Math.random() * 1000);
                    }
                    
                    // 短暂休息避免完全占用CPU
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * GC压力模拟器
     */
    static class GCPressureSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动GC压力模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 快速分配和释放内存
                    List<byte[]> tempList = new ArrayList<>();
                    
                    for (int i = 0; i < 1000; i++) {
                        tempList.add(new byte[1024 * 1024]); // 1MB
                    }
                    
                    // 清理一半内存
                    for (int i = 0; i < 500; i++) {
                        tempList.remove(0);
                    }
                    
                    Thread.sleep(100); // 100ms间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 大对象模拟器
     */
    static class LargeObjectSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动大对象模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 分配大对象（超过G1 Region大小的一半）
                    byte[] largeObject = new byte[3 * 1024 * 1024]; // 3MB
                    Arrays.fill(largeObject, (byte) 0xFF);
                    
                    // 短暂持有后释放
                    Thread.sleep(1000);
                    largeObject = null;
                    
                    Thread.sleep(2000); // 2秒间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 编译器压力模拟器
     */
    static class CompilerStressSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动编译器压力模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 创建热点方法触发JIT编译
                    hotMethod1();
                    hotMethod2();
                    hotMethod3();
                    
                    Thread.sleep(50); // 50ms间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        private void hotMethod1() {
            for (int i = 0; i < 10000; i++) {
                Math.sqrt(i);
            }
        }
        
        private void hotMethod2() {
            for (int i = 0; i < 10000; i++) {
                Math.sin(i);
            }
        }
        
        private void hotMethod3() {
            for (int i = 0; i < 10000; i++) {
                Math.log(i + 1);
            }
        }
    }
    
    /**
     * 代码缓存压力模拟器
     */
    static class CodeCacheStressSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动代码缓存压力模拟器");
            
            try {
                int methodCounter = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    // 动态生成方法增加代码缓存压力
                    generateDynamicMethod(methodCounter++);
                    
                    Thread.sleep(1000); // 1秒间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        private void generateDynamicMethod(int counter) {
            // 使用反射调用不同的方法
            try {
                Method method = Math.class.getMethod("sqrt", double.class);
                for (int i = 0; i < 1000; i++) {
                    method.invoke(null, (double) (counter + i));
                }
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    /**
     * 综合压力模拟器
     */
    static class ComprehensiveStressSimulator implements Runnable {
        @Override
        public void run() {
            System.out.println("启动综合压力模拟器");
            
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 综合各种压力场景
                    
                    // 内存压力
                    List<byte[]> tempMemory = new ArrayList<>();
                    for (int i = 0; i < 100; i++) {
                        tempMemory.add(new byte[1024 * 1024]); // 1MB
                    }
                    
                    // CPU压力
                    for (int i = 0; i < 100000; i++) {
                        Math.sqrt(Math.random() * 1000);
                    }
                    
                    // 线程压力
                    Thread tempThread = new Thread(() -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    tempThread.start();
                    
                    // 清理
                    tempMemory.clear();
                    
                    Thread.sleep(1000); // 1秒间隔
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 监控和诊断
     */
    private static void monitorAndDiagnose(ExecutorService executor) {
        System.out.println("开始监控和诊断");
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + TEST_DURATION_SECONDS * 1000;
        
        while (System.currentTimeMillis() < endTime) {
            try {
                // 打印当前状态
                printCurrentStatus();
                
                // 检查死锁
                checkDeadlocks();
                
                // 检查内存使用
                checkMemoryUsage();
                
                // 检查线程状态
                checkThreadStatus();
                
                // 检查GC状态
                checkGCStatus();
                
                Thread.sleep(30000); // 30秒间隔
                
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * 打印当前状态
     */
    private static void printCurrentStatus() {
        System.out.println("\n=== 当前系统状态 ===");
        System.out.println("时间: " + new Date());
        
        // 内存状态
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memory.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memory.getNonHeapMemoryUsage();
        
        System.out.println("内存状态:");
        System.out.println("  堆内存: " + formatBytes(heapUsage.getUsed()) + 
                          "/" + formatBytes(heapUsage.getMax()) + 
                          " (" + String.format("%.1f", (heapUsage.getUsed() * 100.0) / heapUsage.getMax()) + "%)");
        System.out.println("  非堆内存: " + formatBytes(nonHeapUsage.getUsed()) + 
                          "/" + formatBytes(nonHeapUsage.getMax()) + 
                          " (" + String.format("%.1f", (nonHeapUsage.getUsed() * 100.0) / nonHeapUsage.getMax()) + "%)");
        
        // 线程状态
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        System.out.println("线程状态:");
        System.out.println("  总线程数: " + threadBean.getThreadCount());
        System.out.println("  守护线程数: " + threadBean.getDaemonThreadCount());
        System.out.println("  峰值线程数: " + threadBean.getPeakThreadCount());
        System.out.println("  活跃线程数: " + activeThreads.get());
        
        // 统计信息
        System.out.println("测试统计:");
        System.out.println("  总分配次数: " + totalAllocations.get());
        System.out.println("  总释放次数: " + totalDeallocations.get());
        System.out.println("  死锁检测次数: " + deadlockCount.get());
    }
    
    /**
     * 检查死锁
     */
    private static void checkDeadlocks() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        
        if (deadlockedThreads != null) {
            System.out.println("⚠️ 检测到死锁!");
            System.out.println("  涉及线程数: " + deadlockedThreads.length);
            
            ThreadInfo[] threadInfos = threadBean.getThreadInfo(deadlockedThreads);
            for (ThreadInfo threadInfo : threadInfos) {
                System.out.println("  死锁线程: " + threadInfo.getThreadName() + 
                                 " (ID: " + threadInfo.getThreadId() + 
                                 ", 状态: " + threadInfo.getThreadState() + ")");
            }
        }
    }
    
    /**
     * 检查内存使用
     */
    private static void checkMemoryUsage() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memory.getHeapMemoryUsage();
        
        double heapUsagePercent = (heapUsage.getUsed() * 100.0) / heapUsage.getMax();
        
        if (heapUsagePercent > 90) {
            System.out.println("🚨 堆内存使用率过高: " + String.format("%.1f", heapUsagePercent) + "%");
        } else if (heapUsagePercent > 80) {
            System.out.println("⚠️ 堆内存使用率较高: " + String.format("%.1f", heapUsagePercent) + "%");
        }
        
        // 检查内存泄漏指标
        if (memoryLeakList.size() > 500) {
            System.out.println("⚠️ 检测到潜在内存泄漏: " + memoryLeakList.size() + " 个泄漏对象");
        }
    }
    
    /**
     * 检查线程状态
     */
    private static void checkThreadStatus() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int currentThreadCount = threadBean.getThreadCount();
        int peakThreadCount = threadBean.getPeakThreadCount();
        
        if (currentThreadCount > 200) {
            System.out.println("⚠️ 线程数量过多: " + currentThreadCount);
        }
        
        if (activeThreads.get() > 50) {
            System.out.println("⚠️ 活跃线程数过多: " + activeThreads.get());
        }
    }
    
    /**
     * 检查GC状态
     */
    private static void checkGCStatus() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        System.out.println("GC状态:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long collections = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            
            System.out.println("  " + gcBean.getName() + ": " + 
                             collections + "次, " + time + "ms");
            
            if (collections > 0) {
                double avgTime = (double) time / collections;
                if (avgTime > 1000) { // 1秒
                    System.out.println("    ⚠️ 平均GC时间过长: " + String.format("%.1f", avgTime) + "ms");
                }
            }
        }
    }
    
    /**
     * 清理资源
     */
    private static void cleanup(ExecutorService executor) {
        System.out.println("\n开始清理资源...");
        
        // 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        
        // 清理内存泄漏列表
        memoryLeakList.clear();
        classLoaderLeakMap.clear();
        
        // 强制GC
        System.gc();
        
        System.out.println("资源清理完成");
    }
    
    /**
     * 生成最终报告
     */
    private static void generateFinalReport() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("JVM故障诊断与排查测试 - 最终报告");
        System.out.println("=".repeat(60));
        
        // 运行时统计
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        long totalRuntime = runtime.getUptime();
        
        System.out.println("运行时统计:");
        System.out.println("  总运行时间: " + formatTime(totalRuntime));
        System.out.println("  测试开始时间: " + new Date(runtime.getStartTime()));
        System.out.println("  测试结束时间: " + new Date());
        
        // 内存统计
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memory.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memory.getNonHeapMemoryUsage();
        
        System.out.println("\n内存使用统计:");
        System.out.println("  最终堆使用: " + formatBytes(heapUsage.getUsed()) + 
                          "/" + formatBytes(heapUsage.getMax()) + 
                          " (" + String.format("%.1f", (heapUsage.getUsed() * 100.0) / heapUsage.getMax()) + "%)");
        System.out.println("  最终非堆使用: " + formatBytes(nonHeapUsage.getUsed()) + 
                          "/" + formatBytes(nonHeapUsage.getMax()) + 
                          " (" + String.format("%.1f", (nonHeapUsage.getUsed() * 100.0) / nonHeapUsage.getMax()) + "%)");
        
        // GC统计
        System.out.println("\nGC统计:");
        long totalGCCount = getTotalGCCount();
        long totalGCTime = getTotalGCTime();
        
        System.out.println("  总GC次数: " + totalGCCount);
        System.out.println("  总GC时间: " + totalGCTime + "ms");
        if (totalGCCount > 0) {
            System.out.println("  平均GC时间: " + String.format("%.1f", (double) totalGCTime / totalGCCount) + "ms");
        }
        
        double gcOverhead = (totalGCTime * 100.0) / totalRuntime;
        System.out.println("  GC开销: " + String.format("%.2f", gcOverhead) + "%");
        
        // 线程统计
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        System.out.println("\n线程统计:");
        System.out.println("  最终线程数: " + threadBean.getThreadCount());
        System.out.println("  峰值线程数: " + threadBean.getPeakThreadCount());
        System.out.println("  总启动线程数: " + threadBean.getTotalStartedThreadCount());
        
        // 测试统计
        System.out.println("\n测试统计:");
        System.out.println("  总内存分配: " + totalAllocations.get());
        System.out.println("  总内存释放: " + totalDeallocations.get());
        System.out.println("  内存泄漏对象: " + memoryLeakList.size());
        System.out.println("  死锁检测次数: " + deadlockCount.get());
        System.out.println("  类加载器泄漏: " + classLoaderLeakMap.size());
        
        // 性能评估
        System.out.println("\n性能评估:");
        
        // 内存性能评分
        double heapUsagePercent = (heapUsage.getUsed() * 100.0) / heapUsage.getMax();
        int memoryScore = heapUsagePercent < 70 ? 5 : (heapUsagePercent < 85 ? 4 : (heapUsagePercent < 95 ? 3 : 2));
        
        // GC性能评分
        int gcScore = gcOverhead < 5 ? 5 : (gcOverhead < 10 ? 4 : (gcOverhead < 20 ? 3 : 2));
        
        // 线程性能评分
        int threadScore = threadBean.getThreadCount() < 100 ? 5 : (threadBean.getThreadCount() < 200 ? 4 : 3);
        
        // 故障检测评分
        int faultScore = (deadlockCount.get() == 0 && memoryLeakList.size() < 100) ? 5 : 
                        (deadlockCount.get() < 2 && memoryLeakList.size() < 500) ? 4 : 3;
        
        int totalScore = memoryScore + gcScore + threadScore + faultScore;
        
        System.out.println("  内存使用: " + getScoreString(memoryScore) + " (" + memoryScore + "/5)");
        System.out.println("  GC性能: " + getScoreString(gcScore) + " (" + gcScore + "/5)");
        System.out.println("  线程管理: " + getScoreString(threadScore) + " (" + threadScore + "/5)");
        System.out.println("  故障检测: " + getScoreString(faultScore) + " (" + faultScore + "/5)");
        System.out.println("  综合评分: " + totalScore + "/20 " + getOverallRating(totalScore));
        
        // 诊断建议
        System.out.println("\n诊断建议:");
        if (heapUsagePercent > 85) {
            System.out.println("  ⚠️ 建议增加堆内存大小或优化内存使用");
        }
        if (gcOverhead > 10) {
            System.out.println("  ⚠️ 建议调优GC参数以减少GC开销");
        }
        if (threadBean.getThreadCount() > 150) {
            System.out.println("  ⚠️ 建议优化线程管理，减少线程数量");
        }
        if (deadlockCount.get() > 0) {
            System.out.println("  🚨 检测到死锁，需要修复并发问题");
        }
        if (memoryLeakList.size() > 200) {
            System.out.println("  🚨 检测到内存泄漏，需要检查对象生命周期管理");
        }
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("JVM故障诊断与排查测试完成！");
        System.out.println("=".repeat(60));
    }
    
    // 辅助方法
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private static String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%d小时%d分钟%d秒", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }
    
    private static long getTotalGCCount() {
        long total = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gcBean.getCollectionCount();
        }
        return total;
    }
    
    private static long getTotalGCTime() {
        long total = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gcBean.getCollectionTime();
        }
        return total;
    }
    
    private static String getScoreString(int score) {
        switch (score) {
            case 5: return "优秀 ⭐⭐⭐⭐⭐";
            case 4: return "良好 ⭐⭐⭐⭐";
            case 3: return "一般 ⭐⭐⭐";
            case 2: return "较差 ⭐⭐";
            case 1: return "很差 ⭐";
            default: return "未知";
        }
    }
    
    private static String getOverallRating(int totalScore) {
        if (totalScore >= 18) return "⭐⭐⭐⭐⭐ 优秀";
        if (totalScore >= 15) return "⭐⭐⭐⭐ 良好";
        if (totalScore >= 12) return "⭐⭐⭐ 一般";
        if (totalScore >= 9) return "⭐⭐ 较差";
        return "⭐ 很差";
    }
}