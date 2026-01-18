import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.*;

/**
 * 并发锁竞争性能问题复现程序
 * 基于真实场景模拟各种并发锁竞争问题
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC
 */
public class ConcurrencyBottleneckTest {
    
    // 模拟不同的锁竞争场景
    private static final Object GLOBAL_LOCK = new Object();
    private static final ReentrantLock REENTRANT_LOCK = new ReentrantLock();
    private static final ReentrantReadWriteLock READ_WRITE_LOCK = new ReentrantReadWriteLock();
    
    // 统计数据
    private static final AtomicLong totalOperations = new AtomicLong(0);
    private static final AtomicLong blockedOperations = new AtomicLong(0);
    private static volatile boolean running = true;
    
    // 模拟业务数据
    private static final Map<String, OrderData> orderDatabase = new ConcurrentHashMap<>();
    private static final AtomicLong orderIdGenerator = new AtomicLong(0);
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 并发锁竞争性能问题复现测试 ===");
        printSystemInfo();
        
        // 初始化测试数据
        initializeTestData();
        
        // 启动监控线程
        startMonitorThread();
        
        System.out.println("\n开始测试不同锁竞争场景...");
        
        // 测试1: synchronized关键字 - 重锁竞争
        testSynchronizedBottleneck();
        
        Thread.sleep(3000);
        
        // 测试2: ReentrantLock - 显式锁竞争
        testReentrantLockBottleneck();
        
        Thread.sleep(3000);
        
        // 测试3: 读写锁 - 读写竞争
        testReadWriteLockBottleneck();
        
        Thread.sleep(3000);
        
        // 测试4: 细粒度锁 - 优化方案
        testFinegrainedLocking();
        
        Thread.sleep(3000);
        
        // 测试5: 无锁编程 - 最优方案
        testLockFreeApproach();
        
        running = false;
        System.out.println("\n所有测试完成");
    }
    
    /**
     * 测试synchronized关键字的锁竞争瓶颈
     */
    private static void testSynchronizedBottleneck() throws InterruptedException {
        System.out.println("\n=== 测试1: synchronized锁竞争 ===");
        resetCounters();
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(50);
        
        long startTime = System.currentTimeMillis();
        
        // 启动50个线程，都竞争同一个synchronized锁
        for (int i = 0; i < 50; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 200; j++) {
                        processOrderWithSynchronized("sync_order_" + threadId + "_" + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        System.out.println("synchronized测试结果:");
        System.out.println("  总耗时: " + (endTime - startTime) + "ms");
        System.out.println("  总操作数: " + totalOperations.get());
        System.out.println("  平均TPS: " + (totalOperations.get() * 1000 / (endTime - startTime)));
        System.out.println("  阻塞操作数: " + blockedOperations.get());
        System.out.println("  阻塞率: " + String.format("%.1f%%", 
            (double) blockedOperations.get() / totalOperations.get() * 100));
    }
    
    /**
     * 测试ReentrantLock的锁竞争瓶颈
     */
    private static void testReentrantLockBottleneck() throws InterruptedException {
        System.out.println("\n=== 测试2: ReentrantLock锁竞争 ===");
        resetCounters();
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(50);
        
        long startTime = System.currentTimeMillis();
        
        // 启动50个线程，都竞争同一个ReentrantLock
        for (int i = 0; i < 50; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 200; j++) {
                        processOrderWithReentrantLock("lock_order_" + threadId + "_" + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        System.out.println("ReentrantLock测试结果:");
        System.out.println("  总耗时: " + (endTime - startTime) + "ms");
        System.out.println("  总操作数: " + totalOperations.get());
        System.out.println("  平均TPS: " + (totalOperations.get() * 1000 / (endTime - startTime)));
        System.out.println("  阻塞操作数: " + blockedOperations.get());
        System.out.println("  阻塞率: " + String.format("%.1f%%", 
            (double) blockedOperations.get() / totalOperations.get() * 100));
    }
    
    /**
     * 测试读写锁的竞争问题
     */
    private static void testReadWriteLockBottleneck() throws InterruptedException {
        System.out.println("\n=== 测试3: ReadWriteLock读写竞争 ===");
        resetCounters();
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(50);
        
        long startTime = System.currentTimeMillis();
        
        // 启动40个读线程 + 10个写线程
        for (int i = 0; i < 40; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 250; j++) {
                        readOrderWithReadWriteLock("rw_order_" + (threadId % 100));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        for (int i = 40; i < 50; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {  // 写操作较少
                        writeOrderWithReadWriteLock("rw_order_" + threadId + "_" + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        System.out.println("ReadWriteLock测试结果:");
        System.out.println("  总耗时: " + (endTime - startTime) + "ms");
        System.out.println("  总操作数: " + totalOperations.get());
        System.out.println("  平均TPS: " + (totalOperations.get() * 1000 / (endTime - startTime)));
        System.out.println("  阻塞操作数: " + blockedOperations.get());
        System.out.println("  阻塞率: " + String.format("%.1f%%", 
            (double) blockedOperations.get() / totalOperations.get() * 100));
    }
    
    /**
     * 测试细粒度锁优化方案
     */
    private static void testFinegrainedLocking() throws InterruptedException {
        System.out.println("\n=== 测试4: 细粒度锁优化 ===");
        resetCounters();
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(50);
        
        long startTime = System.currentTimeMillis();
        
        // 使用分段锁，减少锁竞争
        for (int i = 0; i < 50; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 200; j++) {
                        processOrderWithFinegrainedLock("fine_order_" + threadId + "_" + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        System.out.println("细粒度锁测试结果:");
        System.out.println("  总耗时: " + (endTime - startTime) + "ms");
        System.out.println("  总操作数: " + totalOperations.get());
        System.out.println("  平均TPS: " + (totalOperations.get() * 1000 / (endTime - startTime)));
        System.out.println("  阻塞操作数: " + blockedOperations.get());
        System.out.println("  阻塞率: " + String.format("%.1f%%", 
            (double) blockedOperations.get() / totalOperations.get() * 100));
    }
    
    /**
     * 测试无锁编程方案
     */
    private static void testLockFreeApproach() throws InterruptedException {
        System.out.println("\n=== 测试5: 无锁编程优化 ===");
        resetCounters();
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(50);
        
        long startTime = System.currentTimeMillis();
        
        // 使用无锁数据结构和算法
        for (int i = 0; i < 50; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 200; j++) {
                        processOrderLockFree("lockfree_order_" + threadId + "_" + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        System.out.println("无锁编程测试结果:");
        System.out.println("  总耗时: " + (endTime - startTime) + "ms");
        System.out.println("  总操作数: " + totalOperations.get());
        System.out.println("  平均TPS: " + (totalOperations.get() * 1000 / (endTime - startTime)));
        System.out.println("  阻塞操作数: " + blockedOperations.get());
        System.out.println("  阻塞率: " + String.format("%.1f%%", 
            (double) blockedOperations.get() / totalOperations.get() * 100));
    }
    
    /**
     * 使用synchronized处理订单 - 性能瓶颈版本
     */
    private static void processOrderWithSynchronized(String orderId) {
        long startTime = System.nanoTime();
        
        synchronized (GLOBAL_LOCK) {  // 🚨 所有线程竞争同一个锁
            long waitTime = System.nanoTime() - startTime;
            if (waitTime > 1000000) { // 等待超过1ms
                blockedOperations.incrementAndGet();
            }
            
            // 模拟业务处理
            OrderData order = new OrderData(orderId, "user_" + Math.abs(orderId.hashCode() % 1000));
            order.setStatus("PROCESSING");
            
            // 模拟数据库操作 - 耗时操作
            simulateSlowOperation(2); // 2ms
            
            order.setStatus("COMPLETED");
            orderDatabase.put(orderId, order);
            
            totalOperations.incrementAndGet();
        }
    }
    
    /**
     * 使用ReentrantLock处理订单
     */
    private static void processOrderWithReentrantLock(String orderId) {
        long startTime = System.nanoTime();
        
        REENTRANT_LOCK.lock();
        try {
            long waitTime = System.nanoTime() - startTime;
            if (waitTime > 1000000) { // 等待超过1ms
                blockedOperations.incrementAndGet();
            }
            
            // 模拟业务处理
            OrderData order = new OrderData(orderId, "user_" + Math.abs(orderId.hashCode() % 1000));
            order.setStatus("PROCESSING");
            
            // 模拟数据库操作
            simulateSlowOperation(2); // 2ms
            
            order.setStatus("COMPLETED");
            orderDatabase.put(orderId, order);
            
            totalOperations.incrementAndGet();
            
        } finally {
            REENTRANT_LOCK.unlock();
        }
    }
    
    /**
     * 使用读写锁读取订单
     */
    private static void readOrderWithReadWriteLock(String orderId) {
        long startTime = System.nanoTime();
        
        READ_WRITE_LOCK.readLock().lock();
        try {
            long waitTime = System.nanoTime() - startTime;
            if (waitTime > 1000000) {
                blockedOperations.incrementAndGet();
            }
            
            // 模拟读取操作
            OrderData order = orderDatabase.get(orderId);
            if (order != null) {
                // 模拟读取处理
                simulateSlowOperation(1); // 1ms
            }
            
            totalOperations.incrementAndGet();
            
        } finally {
            READ_WRITE_LOCK.readLock().unlock();
        }
    }
    
    /**
     * 使用读写锁写入订单
     */
    private static void writeOrderWithReadWriteLock(String orderId) {
        long startTime = System.nanoTime();
        
        READ_WRITE_LOCK.writeLock().lock();
        try {
            long waitTime = System.nanoTime() - startTime;
            if (waitTime > 1000000) {
                blockedOperations.incrementAndGet();
            }
            
            // 模拟写入操作
            OrderData order = new OrderData(orderId, "user_" + Math.abs(orderId.hashCode() % 1000));
            order.setStatus("COMPLETED");
            
            // 模拟写入处理
            simulateSlowOperation(3); // 3ms
            
            orderDatabase.put(orderId, order);
            totalOperations.incrementAndGet();
            
        } finally {
            READ_WRITE_LOCK.writeLock().unlock();
        }
    }
    
    // 分段锁 - 优化方案
    private static final int SEGMENT_COUNT = 16;
    private static final ReentrantLock[] SEGMENT_LOCKS = new ReentrantLock[SEGMENT_COUNT];
    
    static {
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            SEGMENT_LOCKS[i] = new ReentrantLock();
        }
    }
    
    /**
     * 使用细粒度锁处理订单 - 优化版本
     */
    private static void processOrderWithFinegrainedLock(String orderId) {
        // 根据订单ID选择对应的锁段
        int segment = Math.abs(orderId.hashCode()) % SEGMENT_COUNT;
        ReentrantLock lock = SEGMENT_LOCKS[segment];
        
        long startTime = System.nanoTime();
        
        lock.lock();
        try {
            long waitTime = System.nanoTime() - startTime;
            if (waitTime > 1000000) {
                blockedOperations.incrementAndGet();
            }
            
            // 模拟业务处理
            OrderData order = new OrderData(orderId, "user_" + Math.abs(orderId.hashCode() % 1000));
            order.setStatus("PROCESSING");
            
            // 模拟数据库操作
            simulateSlowOperation(2); // 2ms
            
            order.setStatus("COMPLETED");
            orderDatabase.put(orderId, order);
            
            totalOperations.incrementAndGet();
            
        } finally {
            lock.unlock();
        }
    }
    
    // 无锁计数器
    private static final AtomicLong lockFreeCounter = new AtomicLong(0);
    
    /**
     * 无锁编程处理订单 - 最优版本
     */
    private static void processOrderLockFree(String orderId) {
        long startTime = System.nanoTime();
        
        // 使用CAS操作，无需锁
        long orderSeq = lockFreeCounter.incrementAndGet();
        
        // 模拟业务处理 - 无锁操作
        OrderData order = new OrderData(orderId, "user_" + Math.abs(orderId.hashCode() % 1000));
        order.setStatus("PROCESSING");
        
        // 模拟数据库操作
        simulateSlowOperation(2); // 2ms
        
        order.setStatus("COMPLETED");
        order.setSequence(orderSeq);
        
        // 使用ConcurrentHashMap，内部已优化并发性能
        orderDatabase.put(orderId, order);
        
        totalOperations.incrementAndGet();
        
        // 无锁情况下，很少有阻塞
        long waitTime = System.nanoTime() - startTime;
        if (waitTime > 5000000) { // 等待超过5ms才算阻塞
            blockedOperations.incrementAndGet();
        }
    }
    
    /**
     * 模拟耗时操作
     */
    private static void simulateSlowOperation(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 初始化测试数据
     */
    private static void initializeTestData() {
        System.out.println("初始化测试数据...");
        
        // 预先创建一些订单数据
        for (int i = 0; i < 100; i++) {
            String orderId = "init_order_" + i;
            OrderData order = new OrderData(orderId, "user_" + i);
            order.setStatus("COMPLETED");
            orderDatabase.put(orderId, order);
        }
        
        System.out.println("初始化完成，预创建订单: " + orderDatabase.size());
    }
    
    /**
     * 启动监控线程
     */
    private static void startMonitorThread() {
        Thread monitor = new Thread(() -> {
            long lastOperations = 0;
            long lastTime = System.currentTimeMillis();
            
            while (running) {
                try {
                    Thread.sleep(2000);
                    
                    long currentOperations = totalOperations.get();
                    long currentTime = System.currentTimeMillis();
                    
                    long deltaOps = currentOperations - lastOperations;
                    long deltaTime = currentTime - lastTime;
                    
                    if (deltaTime > 0 && deltaOps > 0) {
                        long currentTPS = deltaOps * 1000 / deltaTime;
                        System.out.printf("[监控] 当前TPS: %d, 总操作: %d, 阻塞操作: %d, 阻塞率: %.1f%%\n",
                            currentTPS, currentOperations, blockedOperations.get(),
                            currentOperations > 0 ? (double) blockedOperations.get() / currentOperations * 100 : 0);
                    }
                    
                    lastOperations = currentOperations;
                    lastTime = currentTime;
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "PerformanceMonitor");
        
        monitor.setDaemon(true);
        monitor.start();
    }
    
    private static void resetCounters() {
        totalOperations.set(0);
        blockedOperations.set(0);
    }
    
    private static void printSystemInfo() {
        System.out.println("系统信息:");
        System.out.println("  JVM: " + System.getProperty("java.vm.name"));
        System.out.println("  版本: " + System.getProperty("java.version"));
        System.out.println("  处理器: " + Runtime.getRuntime().availableProcessors() + "核");
        System.out.println("  最大内存: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
    }
}

/**
 * 订单数据模型
 */
class OrderData {
    private final String orderId;
    private final String userId;
    private volatile String status;  // 使用volatile保证可见性
    private final long createTime;
    private volatile long updateTime;
    private volatile long sequence;  // 无锁序列号
    
    public OrderData(String orderId, String userId) {
        this.orderId = orderId;
        this.userId = userId;
        this.createTime = System.currentTimeMillis();
        this.updateTime = createTime;
        this.status = "CREATED";
    }
    
    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getStatus() { return status; }
    public long getCreateTime() { return createTime; }
    public long getUpdateTime() { return updateTime; }
    public long getSequence() { return sequence; }
    
    public void setStatus(String status) {
        this.status = status;
        this.updateTime = System.currentTimeMillis();
    }
    
    public void setSequence(long sequence) {
        this.sequence = sequence;
    }
    
    @Override
    public String toString() {
        return String.format("Order{id='%s', user='%s', status='%s', seq=%d}", 
            orderId, userId, status, sequence);
    }
}