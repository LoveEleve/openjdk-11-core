/**
 * 并发机制与线程管理测试程序
 * 
 * 测试环境：
 * - JVM配置：-Xms8g -Xmx8g -XX:+UseG1GC
 * - 线程模型：1:1线程映射
 * - 系统：非大页，非NUMA
 * 
 * 功能：
 * 1. 验证线程创建和管理
 * 2. 测试同步机制性能
 * 3. 验证锁优化效果
 * 4. 测试内存模型语义
 * 5. 分析并发GC影响
 */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class ConcurrencyTest {
    
    // 测试参数
    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int TEST_ITERATIONS = 1000000;
    private static final int WARMUP_ITERATIONS = 100000;
    
    // 共享变量
    private static volatile boolean volatileFlag = false;
    private static int normalField = 0;
    private static final Object syncObject = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();
    private static final AtomicInteger atomicCounter = new AtomicInteger(0);
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== 并发机制与线程管理测试 ===");
        System.out.println("JVM版本: " + System.getProperty("java.version"));
        System.out.println("最大堆内存: " + Runtime.getRuntime().maxMemory() / (1024*1024) + "MB");
        System.out.println("可用处理器: " + Runtime.getRuntime().availableProcessors());
        System.out.println("测试线程数: " + THREAD_COUNT);
        System.out.println();
        
        // 1. 线程创建与管理测试
        testThreadCreationAndManagement();
        
        // 2. 同步机制性能测试
        testSynchronizationPerformance();
        
        // 3. 锁优化验证
        testLockOptimizations();
        
        // 4. 内存模型测试
        testMemoryModel();
        
        // 5. 线程本地存储测试
        testThreadLocalStorage();
        
        // 6. 并发集合性能测试
        testConcurrentCollections();
        
        // 7. 死锁检测测试
        testDeadlockDetection();
        
        // 8. 工作窃取测试
        testWorkStealing();
        
        System.out.println("=== 测试完成 ===");
    }
    
    /**
     * 测试线程创建与管理
     */
    private static void testThreadCreationAndManagement() throws Exception {
        System.out.println("1. 线程创建与管理测试");
        
        // 测试线程创建开销
        testThreadCreationOverhead();
        
        // 测试线程池性能
        testThreadPoolPerformance();
        
        // 测试线程状态转换
        testThreadStateTransitions();
        
        System.out.println();
    }
    
    /**
     * 线程创建开销测试
     */
    private static void testThreadCreationOverhead() throws Exception {
        System.out.println("  线程创建开销测试:");
        
        // 直接创建线程
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        long directTime = System.nanoTime() - start;
        
        // 使用线程池
        start = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        
        for (Future<?> future : futures) {
            future.get();
        }
        
        executor.shutdown();
        long poolTime = System.nanoTime() - start;
        
        System.out.printf("    直接创建线程时间: %.2f ms\n", directTime / 1_000_000.0);
        System.out.printf("    线程池执行时间: %.2f ms\n", poolTime / 1_000_000.0);
        System.out.printf("    性能提升: %.2fx\n", (double)directTime / poolTime);
    }
    
    /**
     * 线程池性能测试
     */
    private static void testThreadPoolPerformance() throws Exception {
        System.out.println("  线程池性能测试:");
        
        // 测试不同类型的线程池
        testExecutorPerformance("FixedThreadPool", 
                               Executors.newFixedThreadPool(THREAD_COUNT));
        
        testExecutorPerformance("CachedThreadPool", 
                               Executors.newCachedThreadPool());
        
        testExecutorPerformance("WorkStealingPool", 
                               Executors.newWorkStealingPool(THREAD_COUNT));
        
        testExecutorPerformance("ForkJoinPool", 
                               ForkJoinPool.commonPool());
    }
    
    private static void testExecutorPerformance(String name, ExecutorService executor) throws Exception {
        long start = System.nanoTime();
        
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            final int value = i;
            futures.add(executor.submit(() -> {
                // 简单计算任务
                int sum = 0;
                for (int j = 0; j < 100; j++) {
                    sum += value + j;
                }
                return sum;
            }));
        }
        
        long totalSum = 0;
        for (Future<Integer> future : futures) {
            totalSum += future.get();
        }
        
        long time = System.nanoTime() - start;
        
        System.out.printf("    %s: %.2f ms (结果: %d)\n", 
                         name, time / 1_000_000.0, totalSum);
        
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }
    
    /**
     * 线程状态转换测试
     */
    private static void testThreadStateTransitions() throws Exception {
        System.out.println("  线程状态转换测试:");
        
        Thread testThread = new Thread(() -> {
            try {
                // NEW -> RUNNABLE
                System.out.println("    线程开始运行: " + Thread.currentThread().getState());
                
                // RUNNABLE -> TIMED_WAITING
                Thread.sleep(100);
                
                // RUNNABLE -> WAITING
                synchronized (syncObject) {
                    syncObject.wait(50);
                }
                
                // RUNNABLE -> BLOCKED (通过竞争锁)
                synchronized (ConcurrencyTest.class) {
                    Thread.sleep(10);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        System.out.println("    创建后状态: " + testThread.getState());
        
        testThread.start();
        Thread.sleep(10);
        System.out.println("    启动后状态: " + testThread.getState());
        
        testThread.join();
        System.out.println("    结束后状态: " + testThread.getState());
    }
    
    /**
     * 同步机制性能测试
     */
    private static void testSynchronizationPerformance() throws Exception {
        System.out.println("2. 同步机制性能测试");
        
        // 测试不同同步机制的性能
        testNoSynchronization();
        testSynchronizedMethod();
        testSynchronizedBlock();
        testReentrantLock();
        testAtomicOperations();
        testVolatileAccess();
        
        System.out.println();
    }
    
    /**
     * 无同步基准测试
     */
    private static void testNoSynchronization() throws Exception {
        System.out.println("  无同步基准测试:");
        
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        long[] results = new long[THREAD_COUNT];
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            new Thread(() -> {
                long localSum = 0;
                for (int j = 0; j < TEST_ITERATIONS; j++) {
                    localSum += j;  // 无同步操作
                }
                results[threadId] = localSum;
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        long totalSum = Arrays.stream(results).sum();
        System.out.printf("    无同步时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, totalSum);
    }
    
    /**
     * synchronized方法测试
     */
    private static void testSynchronizedMethod() throws Exception {
        System.out.println("  synchronized方法测试:");
        
        SynchronizedCounter counter = new SynchronizedCounter();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    counter.increment();
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    synchronized方法时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, counter.getValue());
    }
    
    /**
     * synchronized块测试
     */
    private static void testSynchronizedBlock() throws Exception {
        System.out.println("  synchronized块测试:");
        
        final int[] counter = {0};
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    synchronized (syncObject) {
                        counter[0]++;
                    }
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    synchronized块时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, counter[0]);
    }
    
    /**
     * ReentrantLock测试
     */
    private static void testReentrantLock() throws Exception {
        System.out.println("  ReentrantLock测试:");
        
        final int[] counter = {0};
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    reentrantLock.lock();
                    try {
                        counter[0]++;
                    } finally {
                        reentrantLock.unlock();
                    }
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    ReentrantLock时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, counter[0]);
    }
    
    /**
     * 原子操作测试
     */
    private static void testAtomicOperations() throws Exception {
        System.out.println("  原子操作测试:");
        
        atomicCounter.set(0);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    atomicCounter.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    原子操作时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, atomicCounter.get());
    }
    
    /**
     * volatile访问测试
     */
    private static void testVolatileAccess() throws Exception {
        System.out.println("  volatile访问测试:");
        
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        long[] results = new long[THREAD_COUNT];
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            new Thread(() -> {
                long count = 0;
                for (int j = 0; j < TEST_ITERATIONS; j++) {
                    if (volatileFlag || !volatileFlag) {  // volatile读
                        count++;
                    }
                }
                results[threadId] = count;
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        long totalCount = Arrays.stream(results).sum();
        System.out.printf("    volatile访问时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, totalCount);
    }
    
    /**
     * 锁优化验证
     */
    private static void testLockOptimizations() throws Exception {
        System.out.println("3. 锁优化验证");
        
        // 测试偏向锁
        testBiasedLocking();
        
        // 测试轻量级锁
        testLightweightLocking();
        
        // 测试锁消除
        testLockElimination();
        
        // 测试锁粗化
        testLockCoarsening();
        
        System.out.println();
    }
    
    /**
     * 偏向锁测试
     */
    private static void testBiasedLocking() {
        System.out.println("  偏向锁测试:");
        
        Object biasedObject = new Object();
        
        // 单线程重复获取同一个锁（应该使用偏向锁）
        long start = System.nanoTime();
        
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            synchronized (biasedObject) {
                // 简单操作
                normalField++;
            }
        }
        
        long time = System.nanoTime() - start;
        
        System.out.printf("    偏向锁时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, normalField);
    }
    
    /**
     * 轻量级锁测试
     */
    private static void testLightweightLocking() throws Exception {
        System.out.println("  轻量级锁测试:");
        
        Object lightweightObject = new Object();
        CountDownLatch latch = new CountDownLatch(2);
        final int[] result = {0};
        
        long start = System.nanoTime();
        
        // 两个线程交替获取锁（应该使用轻量级锁）
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / 2; j++) {
                    synchronized (lightweightObject) {
                        result[0]++;
                    }
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    轻量级锁时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, result[0]);
    }
    
    /**
     * 锁消除测试
     */
    private static void testLockElimination() {
        System.out.println("  锁消除测试:");
        
        long start = System.nanoTime();
        
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            // 局部对象的同步应该被消除
            StringBuffer sb = new StringBuffer();
            sb.append("Hello");
            sb.append(" World");
            sb.toString();
        }
        
        long time = System.nanoTime() - start;
        
        System.out.printf("    锁消除时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    /**
     * 锁粗化测试
     */
    private static void testLockCoarsening() {
        System.out.println("  锁粗化测试:");
        
        Object coarseningObject = new Object();
        
        long start = System.nanoTime();
        
        for (int i = 0; i < TEST_ITERATIONS / 100; i++) {
            // 连续的同步块应该被粗化
            synchronized (coarseningObject) {
                normalField++;
            }
            synchronized (coarseningObject) {
                normalField++;
            }
            synchronized (coarseningObject) {
                normalField++;
            }
        }
        
        long time = System.nanoTime() - start;
        
        System.out.printf("    锁粗化时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, normalField);
    }
    
    /**
     * 内存模型测试
     */
    private static void testMemoryModel() throws Exception {
        System.out.println("4. 内存模型测试");
        
        // 测试volatile语义
        testVolatileSemantics();
        
        // 测试happens-before关系
        testHappensBefore();
        
        // 测试内存屏障
        testMemoryBarriers();
        
        System.out.println();
    }
    
    /**
     * volatile语义测试
     */
    private static void testVolatileSemantics() throws Exception {
        System.out.println("  volatile语义测试:");
        
        VolatileExample example = new VolatileExample();
        CountDownLatch latch = new CountDownLatch(2);
        final boolean[] results = new boolean[1];
        
        // 写线程
        Thread writer = new Thread(() -> {
            example.writer();
            latch.countDown();
        });
        
        // 读线程
        Thread reader = new Thread(() -> {
            try {
                Thread.sleep(10);  // 确保写操作先执行
                results[0] = example.reader();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });
        
        writer.start();
        reader.start();
        
        latch.await();
        
        System.out.printf("    volatile语义验证: %s\n", 
                         results[0] ? "通过" : "失败");
    }
    
    /**
     * happens-before关系测试
     */
    private static void testHappensBefore() throws Exception {
        System.out.println("  happens-before关系测试:");
        
        HappensBeforeExample example = new HappensBeforeExample();
        CountDownLatch latch = new CountDownLatch(2);
        final boolean[] results = new boolean[1];
        
        Thread thread1 = new Thread(() -> {
            example.thread1();
            latch.countDown();
        });
        
        Thread thread2 = new Thread(() -> {
            try {
                Thread.sleep(10);
                results[0] = example.thread2();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });
        
        thread1.start();
        thread2.start();
        
        latch.await();
        
        System.out.printf("    happens-before验证: %s\n", 
                         results[0] ? "通过" : "失败");
    }
    
    /**
     * 内存屏障测试
     */
    private static void testMemoryBarriers() {
        System.out.println("  内存屏障测试:");
        
        // 通过volatile操作触发内存屏障
        long start = System.nanoTime();
        
        for (int i = 0; i < TEST_ITERATIONS / 10; i++) {
            volatileFlag = true;   // StoreStore + StoreLoad屏障
            boolean temp = volatileFlag;  // LoadLoad + LoadStore屏障
        }
        
        long time = System.nanoTime() - start;
        
        System.out.printf("    内存屏障开销: %.2f ms\n", time / 1_000_000.0);
    }
    
    /**
     * 线程本地存储测试
     */
    private static void testThreadLocalStorage() throws Exception {
        System.out.println("5. 线程本地存储测试");
        
        ThreadLocal<Integer> threadLocal = new ThreadLocal<Integer>() {
            @Override
            protected Integer initialValue() {
                return 0;
            }
        };
        
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        long[] results = new long[THREAD_COUNT];
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            new Thread(() -> {
                // 每个线程独立操作ThreadLocal
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    threadLocal.set(threadLocal.get() + 1);
                }
                results[threadId] = threadLocal.get();
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        long totalSum = Arrays.stream(results).sum();
        System.out.printf("  ThreadLocal性能: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, totalSum);
        
        System.out.println();
    }
    
    /**
     * 并发集合性能测试
     */
    private static void testConcurrentCollections() throws Exception {
        System.out.println("6. 并发集合性能测试");
        
        // 测试不同并发集合的性能
        testConcurrentHashMap();
        testConcurrentLinkedQueue();
        testBlockingQueue();
        
        System.out.println();
    }
    
    /**
     * ConcurrentHashMap测试
     */
    private static void testConcurrentHashMap() throws Exception {
        System.out.println("  ConcurrentHashMap测试:");
        
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    int key = threadId * (TEST_ITERATIONS / THREAD_COUNT) + j;
                    map.put(key, key * 2);
                    map.get(key);
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    ConcurrentHashMap时间: %.2f ms (大小: %d)\n", 
                         time / 1_000_000.0, map.size());
    }
    
    /**
     * ConcurrentLinkedQueue测试
     */
    private static void testConcurrentLinkedQueue() throws Exception {
        System.out.println("  ConcurrentLinkedQueue测试:");
        
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            new Thread(() -> {
                for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                    queue.offer(threadId * 1000 + j);
                    queue.poll();
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    ConcurrentLinkedQueue时间: %.2f ms (大小: %d)\n", 
                         time / 1_000_000.0, queue.size());
    }
    
    /**
     * BlockingQueue测试
     */
    private static void testBlockingQueue() throws Exception {
        System.out.println("  BlockingQueue测试:");
        
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10000);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        // 生产者线程
        for (int i = 0; i < THREAD_COUNT / 2; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                        queue.put(threadId * 1000 + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            }).start();
        }
        
        // 消费者线程
        for (int i = THREAD_COUNT / 2; i < THREAD_COUNT; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < TEST_ITERATIONS / THREAD_COUNT; j++) {
                        queue.take();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long time = System.nanoTime() - start;
        
        System.out.printf("    BlockingQueue时间: %.2f ms (剩余: %d)\n", 
                         time / 1_000_000.0, queue.size());
    }
    
    /**
     * 死锁检测测试
     */
    private static void testDeadlockDetection() throws Exception {
        System.out.println("7. 死锁检测测试");
        
        Object lock1 = new Object();
        Object lock2 = new Object();
        
        CountDownLatch latch = new CountDownLatch(2);
        
        // 线程1：先获取lock1，再获取lock2
        Thread thread1 = new Thread(() -> {
            synchronized (lock1) {
                System.out.println("  线程1获取lock1");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                System.out.println("  线程1尝试获取lock2");
                synchronized (lock2) {
                    System.out.println("  线程1获取lock2");
                }
            }
            latch.countDown();
        }, "DeadlockThread1");
        
        // 线程2：先获取lock2，再获取lock1
        Thread thread2 = new Thread(() -> {
            synchronized (lock2) {
                System.out.println("  线程2获取lock2");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                System.out.println("  线程2尝试获取lock1");
                synchronized (lock1) {
                    System.out.println("  线程2获取lock1");
                }
            }
            latch.countDown();
        }, "DeadlockThread2");
        
        thread1.start();
        thread2.start();
        
        // 等待一段时间检测死锁
        boolean finished = latch.await(5, TimeUnit.SECONDS);
        
        if (!finished) {
            System.out.println("  检测到潜在死锁，中断线程");
            thread1.interrupt();
            thread2.interrupt();
        } else {
            System.out.println("  未发生死锁");
        }
        
        System.out.println();
    }
    
    /**
     * 工作窃取测试
     */
    private static void testWorkStealing() throws Exception {
        System.out.println("8. 工作窃取测试");
        
        // 使用ForkJoinPool测试工作窃取
        ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
        
        long start = System.nanoTime();
        
        Integer result = forkJoinPool.submit(new FibonacciTask(35)).get();
        
        long time = System.nanoTime() - start;
        
        System.out.printf("  ForkJoinPool计算时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, result);
        
        forkJoinPool.shutdown();
        
        // 对比普通递归
        start = System.nanoTime();
        int normalResult = fibonacci(35);
        time = System.nanoTime() - start;
        
        System.out.printf("  普通递归计算时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, normalResult);
        
        System.out.println();
    }
    
    private static int fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
    
    // 辅助类定义
    
    /**
     * 同步计数器
     */
    static class SynchronizedCounter {
        private int count = 0;
        
        public synchronized void increment() {
            count++;
        }
        
        public synchronized int getValue() {
            return count;
        }
    }
    
    /**
     * volatile语义示例
     */
    static class VolatileExample {
        private int data = 0;
        private volatile boolean ready = false;
        
        public void writer() {
            data = 42;      // 1
            ready = true;   // 2 (volatile写)
        }
        
        public boolean reader() {
            if (ready) {    // 3 (volatile读)
                return data == 42;  // 4 (应该能看到42)
            }
            return false;
        }
    }
    
    /**
     * happens-before关系示例
     */
    static class HappensBeforeExample {
        private int x = 0;
        private int y = 0;
        private volatile boolean flag = false;
        
        public void thread1() {
            x = 1;          // 1
            y = 2;          // 2
            flag = true;    // 3 (volatile写)
        }
        
        public boolean thread2() {
            if (flag) {     // 4 (volatile读)
                // 由于happens-before关系，应该能看到x=1, y=2
                return x == 1 && y == 2;  // 5
            }
            return false;
        }
    }
    
    /**
     * ForkJoin斐波那契任务
     */
    static class FibonacciTask extends RecursiveTask<Integer> {
        private final int n;
        
        public FibonacciTask(int n) {
            this.n = n;
        }
        
        @Override
        protected Integer compute() {
            if (n <= 1) {
                return n;
            }
            
            if (n < 20) {
                // 小任务直接计算
                return fibonacci(n);
            }
            
            // 大任务分解
            FibonacciTask f1 = new FibonacciTask(n - 1);
            FibonacciTask f2 = new FibonacciTask(n - 2);
            
            f1.fork();  // 异步执行
            return f2.compute() + f1.join();  // 等待结果
        }
    }
}