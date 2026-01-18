/**
 * 第08章：异常处理与调试机制 - 综合测试程序
 * 
 * 本程序用于验证JVM的异常处理机制、调试功能和性能监控工具
 * 配合GDB调试脚本进行深度分析
 * 
 * 编译: javac DebuggingTest.java
 * 运行: java -Xms8g -Xmx8g -XX:+UseG1GC DebuggingTest
 * 
 * JFR记录: java -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=debug_test.jfr DebuggingTest
 * JVMTI调试: java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 DebuggingTest
 */

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

public class DebuggingTest {
    
    // 测试配置
    private static final int THREAD_COUNT = 4;
    private static final int ITERATIONS_PER_THREAD = 10000;
    private static final int EXCEPTION_FREQUENCY = 100;
    
    // 统计信息
    private static final AtomicInteger exceptionCount = new AtomicInteger(0);
    private static final AtomicLong totalExceptionTime = new AtomicLong(0);
    private static final AtomicInteger debugEventCount = new AtomicInteger(0);
    
    // 性能监控
    private static MemoryMXBean memoryBean;
    private static ThreadMXBean threadBean;
    private static List<GarbageCollectorMXBean> gcBeans;
    
    public static void main(String[] args) {
        System.out.println("=== JVM异常处理与调试机制测试 ===");
        System.out.println("JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC");
        System.out.println("线程数: " + THREAD_COUNT);
        System.out.println("每线程迭代数: " + ITERATIONS_PER_THREAD);
        
        // 初始化监控
        initializeMonitoring();
        
        // 显示初始状态
        printInitialStatus();
        
        try {
            // 执行测试套件
            runTestSuite();
            
            // 显示最终结果
            printFinalResults();
            
        } catch (Exception e) {
            System.err.println("测试执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 初始化性能监控
     */
    private static void initializeMonitoring() {
        memoryBean = ManagementFactory.getMemoryMXBean();
        threadBean = ManagementFactory.getThreadMXBean();
        gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        // 启用线程CPU时间测量
        if (threadBean.isThreadCpuTimeSupported()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
        
        System.out.println("性能监控已初始化");
    }
    
    /**
     * 显示初始状态
     */
    private static void printInitialStatus() {
        System.out.println("\n=== 初始系统状态 ===");
        
        // 内存状态
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.printf("堆内存使用: %d MB / %d MB (%.1f%%)\n",
                         heapUsage.getUsed() / 1024 / 1024,
                         heapUsage.getMax() / 1024 / 1024,
                         (double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        // 线程状态
        System.out.printf("当前线程数: %d (守护线程: %d)\n",
                         threadBean.getThreadCount(),
                         threadBean.getDaemonThreadCount());
        
        // GC状态
        System.out.println("GC收集器状态:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("  %s: %d 次收集, 总耗时 %d ms\n",
                             gcBean.getName(),
                             gcBean.getCollectionCount(),
                             gcBean.getCollectionTime());
        }
    }
    
    /**
     * 执行测试套件
     */
    private static void runTestSuite() throws InterruptedException {
        System.out.println("\n=== 开始测试套件 ===");
        
        // 1. 异常处理性能测试
        runExceptionPerformanceTest();
        
        // 2. 多线程异常测试
        runMultiThreadExceptionTest();
        
        // 3. 深度调用栈异常测试
        runDeepStackExceptionTest();
        
        // 4. 内存分配压力测试
        runMemoryAllocationTest();
        
        // 5. 调试事件模拟测试
        runDebugEventSimulationTest();
    }
    
    /**
     * 1. 异常处理性能测试
     */
    private static void runExceptionPerformanceTest() {
        System.out.println("\n1. 异常处理性能测试");
        
        // 测试异常创建开销
        long startTime = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            Exception e = new Exception("Performance test exception " + i);
        }
        long creationTime = System.nanoTime() - startTime;
        
        // 测试异常抛出捕获开销
        startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            try {
                throwTestException(i);
            } catch (TestException e) {
                // 处理异常
            }
        }
        long throwCatchTime = System.nanoTime() - startTime;
        
        System.out.printf("异常创建平均耗时: %.2f ns\n", creationTime / 10000.0);
        System.out.printf("异常抛出捕获平均耗时: %.2f ns\n", throwCatchTime / 1000.0);
    }
    
    /**
     * 2. 多线程异常测试
     */
    private static void runMultiThreadExceptionTest() throws InterruptedException {
        System.out.println("\n2. 多线程异常测试");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long startTime = System.currentTimeMillis();
        
        // 启动工作线程
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    runWorkerThread(threadId);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待完成
        latch.await();
        executor.shutdown();
        
        long endTime = System.currentTimeMillis();
        
        System.out.printf("多线程测试完成，耗时: %d ms\n", endTime - startTime);
        System.out.printf("总异常数: %d\n", exceptionCount.get());
        System.out.printf("平均异常处理时间: %.2f ns\n", 
                         totalExceptionTime.get() / (double) exceptionCount.get());
    }
    
    /**
     * 工作线程逻辑
     */
    private static void runWorkerThread(int threadId) {
        System.out.printf("线程 %d 开始执行\n", threadId);
        
        for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
            try {
                // 执行业务逻辑
                performBusinessLogic(threadId, i);
                
                // 定期触发异常
                if (i % EXCEPTION_FREQUENCY == 0) {
                    long exceptionStart = System.nanoTime();
                    try {
                        triggerTestException(threadId, i);
                    } catch (Exception e) {
                        long exceptionEnd = System.nanoTime();
                        exceptionCount.incrementAndGet();
                        totalExceptionTime.addAndGet(exceptionEnd - exceptionStart);
                    }
                }
                
                // 模拟调试事件
                if (i % 500 == 0) {
                    simulateDebugEvent(threadId, i);
                }
                
            } catch (Exception e) {
                System.err.printf("线程 %d 发生未处理异常: %s\n", threadId, e.getMessage());
            }
        }
        
        System.out.printf("线程 %d 完成执行\n", threadId);
    }
    
    /**
     * 3. 深度调用栈异常测试
     */
    private static void runDeepStackExceptionTest() {
        System.out.println("\n3. 深度调用栈异常测试");
        
        int[] depths = {10, 50, 100, 200};
        
        for (int depth : depths) {
            long startTime = System.nanoTime();
            
            for (int i = 0; i < 100; i++) {
                try {
                    deepRecursiveCall(depth, i);
                } catch (DeepStackException e) {
                    // 异常处理
                }
            }
            
            long endTime = System.nanoTime();
            double avgTime = (endTime - startTime) / 100.0;
            
            System.out.printf("调用栈深度 %d, 平均异常处理时间: %.2f ns\n", depth, avgTime);
        }
    }
    
    /**
     * 4. 内存分配压力测试
     */
    private static void runMemoryAllocationTest() {
        System.out.println("\n4. 内存分配压力测试");
        
        List<Object> allocations = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        
        try {
            // 分配大量对象
            for (int i = 0; i < 100000; i++) {
                // 分配不同大小的对象
                if (i % 10 == 0) {
                    allocations.add(new LargeObject(i));
                } else {
                    allocations.add(new SmallObject(i));
                }
                
                // 定期清理以触发GC
                if (i % 10000 == 0) {
                    allocations.clear();
                    System.gc(); // 建议GC
                    
                    // 检查内存使用
                    MemoryUsage usage = memoryBean.getHeapMemoryUsage();
                    System.out.printf("第 %d 轮分配后堆使用: %d MB\n", 
                                     i / 10000, usage.getUsed() / 1024 / 1024);
                }
            }
        } catch (OutOfMemoryError e) {
            System.err.println("内存不足: " + e.getMessage());
        }
        
        long endTime = System.currentTimeMillis();
        System.out.printf("内存分配测试完成，耗时: %d ms\n", endTime - startTime);
    }
    
    /**
     * 5. 调试事件模拟测试
     */
    private static void runDebugEventSimulationTest() {
        System.out.println("\n5. 调试事件模拟测试");
        
        // 模拟断点事件
        simulateBreakpointEvents();
        
        // 模拟方法进入/退出事件
        simulateMethodEvents();
        
        // 模拟线程事件
        simulateThreadEvents();
        
        System.out.printf("总调试事件数: %d\n", debugEventCount.get());
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 执行业务逻辑
     */
    private static void performBusinessLogic(int threadId, int iteration) {
        // CPU密集型计算
        double result = 0;
        for (int i = 0; i < 50; i++) {
            result += Math.sqrt(threadId * iteration * i) * Math.PI;
        }
        
        // 字符串操作
        String data = "Thread-" + threadId + "-Iteration-" + iteration;
        data = data.toUpperCase().toLowerCase();
        
        // 集合操作
        Map<String, Integer> map = new HashMap<>();
        map.put("threadId", threadId);
        map.put("iteration", iteration);
        map.put("result", (int) result);
    }
    
    /**
     * 触发测试异常
     */
    private static void triggerTestException(int threadId, int iteration) throws TestException {
        if (iteration % 3 == 0) {
            throw new TestException("Thread " + threadId + " exception at iteration " + iteration);
        } else if (iteration % 5 == 0) {
            throw new RuntimeException("Runtime exception in thread " + threadId);
        } else {
            throw new IllegalStateException("Illegal state in thread " + threadId);
        }
    }
    
    /**
     * 抛出测试异常
     */
    private static void throwTestException(int value) throws TestException {
        throw new TestException("Test exception for value: " + value);
    }
    
    /**
     * 深度递归调用
     */
    private static void deepRecursiveCall(int depth, int value) throws DeepStackException {
        if (depth <= 0) {
            throw new DeepStackException("Deep stack exception at value: " + value);
        } else {
            deepRecursiveCall(depth - 1, value);
        }
    }
    
    /**
     * 模拟调试事件
     */
    private static void simulateDebugEvent(int threadId, int iteration) {
        debugEventCount.incrementAndGet();
        
        // 模拟调试器检查
        if (iteration % 1000 == 0) {
            Thread currentThread = Thread.currentThread();
            StackTraceElement[] stackTrace = currentThread.getStackTrace();
            
            // 分析栈跟踪（模拟调试器行为）
            for (StackTraceElement element : stackTrace) {
                if (element.getMethodName().contains("runWorkerThread")) {
                    // 模拟断点命中
                    break;
                }
            }
        }
    }
    
    /**
     * 模拟断点事件
     */
    private static void simulateBreakpointEvents() {
        for (int i = 0; i < 1000; i++) {
            // 模拟断点命中
            breakpointMethod(i);
            debugEventCount.incrementAndGet();
        }
    }
    
    /**
     * 断点方法
     */
    private static void breakpointMethod(int value) {
        // 这个方法可以设置断点进行调试
        int result = value * 2;
        String message = "Breakpoint hit at value: " + value;
        
        if (value % 100 == 0) {
            System.out.println(message);
        }
    }
    
    /**
     * 模拟方法事件
     */
    private static void simulateMethodEvents() {
        for (int i = 0; i < 500; i++) {
            methodEntryEvent("testMethod" + i);
            // 执行方法逻辑
            performMethodLogic(i);
            methodExitEvent("testMethod" + i);
        }
    }
    
    /**
     * 方法进入事件
     */
    private static void methodEntryEvent(String methodName) {
        debugEventCount.incrementAndGet();
        // 模拟JVMTI方法进入事件处理
    }
    
    /**
     * 方法退出事件
     */
    private static void methodExitEvent(String methodName) {
        debugEventCount.incrementAndGet();
        // 模拟JVMTI方法退出事件处理
    }
    
    /**
     * 执行方法逻辑
     */
    private static void performMethodLogic(int value) {
        // 简单的方法逻辑
        for (int i = 0; i < 10; i++) {
            Math.sqrt(value + i);
        }
    }
    
    /**
     * 模拟线程事件
     */
    private static void simulateThreadEvents() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        for (int i = 0; i < 10; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                threadStartEvent("SimulatedThread-" + threadNum);
                
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                threadEndEvent("SimulatedThread-" + threadNum);
            });
        }
        
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 线程启动事件
     */
    private static void threadStartEvent(String threadName) {
        debugEventCount.incrementAndGet();
        // 模拟JVMTI线程启动事件处理
    }
    
    /**
     * 线程结束事件
     */
    private static void threadEndEvent(String threadName) {
        debugEventCount.incrementAndGet();
        // 模拟JVMTI线程结束事件处理
    }
    
    /**
     * 显示最终结果
     */
    private static void printFinalResults() {
        System.out.println("\n=== 测试结果统计 ===");
        
        // 异常统计
        System.out.printf("总异常数: %d\n", exceptionCount.get());
        if (exceptionCount.get() > 0) {
            System.out.printf("平均异常处理时间: %.2f ns\n", 
                             totalExceptionTime.get() / (double) exceptionCount.get());
        }
        
        // 调试事件统计
        System.out.printf("总调试事件数: %d\n", debugEventCount.get());
        
        // 内存使用情况
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.printf("最终堆内存使用: %d MB / %d MB (%.1f%%)\n",
                         heapUsage.getUsed() / 1024 / 1024,
                         heapUsage.getMax() / 1024 / 1024,
                         (double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        // GC统计
        System.out.println("GC统计:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("  %s: %d 次收集, 总耗时 %d ms\n",
                             gcBean.getName(),
                             gcBean.getCollectionCount(),
                             gcBean.getCollectionTime());
        }
        
        // 线程统计
        System.out.printf("最终线程数: %d (守护线程: %d)\n",
                         threadBean.getThreadCount(),
                         threadBean.getDaemonThreadCount());
        
        // 检测死锁
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        if (deadlockedThreads != null) {
            System.out.printf("检测到死锁线程: %d 个\n", deadlockedThreads.length);
        } else {
            System.out.println("未检测到死锁");
        }
        
        System.out.println("\n测试完成！");
    }
    
    // ==================== 内部类定义 ====================
    
    /**
     * 测试异常类
     */
    static class TestException extends Exception {
        public TestException(String message) {
            super(message);
        }
    }
    
    /**
     * 深度栈异常类
     */
    static class DeepStackException extends Exception {
        public DeepStackException(String message) {
            super(message);
        }
    }
    
    /**
     * 大对象类
     */
    static class LargeObject {
        private final int id;
        private final byte[] data;
        
        public LargeObject(int id) {
            this.id = id;
            this.data = new byte[10240]; // 10KB
            Arrays.fill(data, (byte) (id % 256));
        }
        
        public int getId() { return id; }
        public byte[] getData() { return data; }
    }
    
    /**
     * 小对象类
     */
    static class SmallObject {
        private final int id;
        private final String name;
        
        public SmallObject(int id) {
            this.id = id;
            this.name = "SmallObject-" + id;
        }
        
        public int getId() { return id; }
        public String getName() { return name; }
    }
}