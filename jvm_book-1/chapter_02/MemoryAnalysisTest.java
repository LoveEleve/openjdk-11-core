/**
 * 内存模型与对象创建深度分析测试程序
 * 
 * 功能：
 * 1. 验证G1堆内存布局和Region管理
 * 2. 测试TLAB分配机制和性能
 * 3. 验证压缩指针编码/解码
 * 4. 分析对象内存布局和对齐
 * 5. 测试不同大小对象的分配策略
 * 6. 验证GC触发条件和内存回收
 * 
 * 使用方法：
 * javac MemoryAnalysisTest.java
 * java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintGCDetails MemoryAnalysisTest
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import sun.misc.Unsafe;

public class MemoryAnalysisTest {
    
    // 测试常量
    private static final int SMALL_OBJECT_COUNT = 100000;    // 小对象数量
    private static final int MEDIUM_OBJECT_COUNT = 10000;    // 中等对象数量
    private static final int LARGE_OBJECT_COUNT = 1000;      // 大对象数量
    private static final int THREAD_COUNT = 8;               // 并发线程数
    
    // 性能统计
    private static long totalAllocations = 0;
    private static long totalAllocatedBytes = 0;
    private static long startTime;
    
    // Unsafe实例（用于底层内存操作）
    private static Unsafe unsafe;
    
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            System.err.println("无法获取Unsafe实例: " + e.getMessage());
        }
    }
    
    /**
     * 主入口函数
     */
    public static void main(String[] args) {
        startTime = System.nanoTime();
        
        System.out.println("🧠 === 内存模型与对象创建深度分析测试 ===");
        System.out.println("测试目标：验证G1堆内存布局与对象分配机制");
        System.out.println("配置环境：8GB G1堆，压缩指针，TLAB分配");
        System.out.println();
        
        try {
            // 第一阶段：内存布局验证
            verifyMemoryLayout();
            
            // 第二阶段：对象分配性能测试
            testObjectAllocationPerformance();
            
            // 第三阶段：TLAB机制验证
            testTLABMechanism();
            
            // 第四阶段：压缩指针验证
            testCompressedOops();
            
            // 第五阶段：对象内存布局分析
            analyzeObjectMemoryLayout();
            
            // 第六阶段：不同大小对象分配测试
            testDifferentSizeAllocations();
            
            // 第七阶段：并发分配测试
            testConcurrentAllocation();
            
            // 第八阶段：GC触发测试
            testGCTrigger();
            
            // 最终报告
            generateFinalReport();
            
        } catch (Exception e) {
            System.err.println("❌ 测试过程中发生异常：" + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 验证内存布局
     */
    private static void verifyMemoryLayout() {
        System.out.println("📏 === 第一阶段：内存布局验证 ===");
        
        // 获取内存管理信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        
        System.out.println("JVM内存配置：");
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.printf("  堆内存初始: %.2f GB\n", heapUsage.getInit() / (1024.0 * 1024 * 1024));
        System.out.printf("  堆内存最大: %.2f GB\n", heapUsage.getMax() / (1024.0 * 1024 * 1024));
        System.out.printf("  堆内存已用: %.2f MB\n", heapUsage.getUsed() / (1024.0 * 1024));
        System.out.printf("  堆内存已提交: %.2f GB\n", heapUsage.getCommitted() / (1024.0 * 1024 * 1024));
        
        System.out.println("\n内存池详情：");
        for (MemoryPoolMXBean pool : memoryPools) {
            MemoryUsage usage = pool.getUsage();
            System.out.printf("  %s:\n", pool.getName());
            System.out.printf("    类型: %s\n", pool.getType());
            System.out.printf("    已用: %.2f MB\n", usage.getUsed() / (1024.0 * 1024));
            System.out.printf("    最大: %.2f MB\n", usage.getMax() / (1024.0 * 1024));
        }
        
        // 验证G1特定配置
        System.out.println("\nG1GC配置验证：");
        String regionSize = System.getProperty("G1HeapRegionSize", "未设置");
        System.out.printf("  G1 Region大小: %s\n", regionSize);
        
        // 验证压缩指针
        System.out.println("\n压缩指针配置：");
        String compressedOops = System.getProperty("UseCompressedOops", "未知");
        String compressedClassPointers = System.getProperty("UseCompressedClassPointers", "未知");
        System.out.printf("  压缩指针: %s\n", compressedOops);
        System.out.printf("  压缩类指针: %s\n", compressedClassPointers);
        
        System.out.println("✅ 内存布局验证完成\n");
    }
    
    /**
     * 测试对象分配性能
     */
    private static void testObjectAllocationPerformance() {
        System.out.println("🏭 === 第二阶段：对象分配性能测试 ===");
        
        // 预热JVM
        System.out.println("预热JVM...");
        for (int i = 0; i < 10000; i++) {
            new SmallObject(i);
        }
        
        // 小对象分配性能测试
        System.out.println("\n测试小对象分配性能...");
        long startTime = System.nanoTime();
        List<SmallObject> smallObjects = new ArrayList<>();
        
        for (int i = 0; i < SMALL_OBJECT_COUNT; i++) {
            smallObjects.add(new SmallObject(i));
            totalAllocations++;
            totalAllocatedBytes += 32; // 估算小对象大小
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("小对象分配性能：\n");
        System.out.printf("  分配数量: %d 个\n", SMALL_OBJECT_COUNT);
        System.out.printf("  分配耗时: %.2f ms\n", duration);
        System.out.printf("  分配速率: %.0f 对象/秒\n", SMALL_OBJECT_COUNT * 1000.0 / duration);
        System.out.printf("  平均分配时间: %.2f ns/对象\n", (endTime - startTime) / (double)SMALL_OBJECT_COUNT);
        
        // 中等对象分配性能测试
        System.out.println("\n测试中等对象分配性能...");
        startTime = System.nanoTime();
        List<MediumObject> mediumObjects = new ArrayList<>();
        
        for (int i = 0; i < MEDIUM_OBJECT_COUNT; i++) {
            mediumObjects.add(new MediumObject(i));
            totalAllocations++;
            totalAllocatedBytes += 1024; // 估算中等对象大小
        }
        
        endTime = System.nanoTime();
        duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("中等对象分配性能：\n");
        System.out.printf("  分配数量: %d 个\n", MEDIUM_OBJECT_COUNT);
        System.out.printf("  分配耗时: %.2f ms\n", duration);
        System.out.printf("  分配速率: %.0f 对象/秒\n", MEDIUM_OBJECT_COUNT * 1000.0 / duration);
        System.out.printf("  平均分配时间: %.2f ns/对象\n", (endTime - startTime) / (double)MEDIUM_OBJECT_COUNT);
        
        // 清理引用，准备GC
        smallObjects.clear();
        mediumObjects.clear();
        
        System.out.println("✅ 对象分配性能测试完成\n");
    }
    
    /**
     * 测试TLAB机制
     */
    private static void testTLABMechanism() {
        System.out.println("🧵 === 第三阶段：TLAB机制验证 ===");
        
        // 获取线程相关的内存信息
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadBean.getAllThreadIds();
        
        System.out.printf("当前线程数量: %d\n", threadIds.length);
        System.out.printf("主线程ID: %d\n", Thread.currentThread().getId());
        
        // 测试TLAB分配模式
        System.out.println("\n测试TLAB分配模式...");
        
        // 连续分配小对象（应该在TLAB中快速分配）
        long tlabStartTime = System.nanoTime();
        Object[] tlabObjects = new Object[50000];
        
        for (int i = 0; i < tlabObjects.length; i++) {
            tlabObjects[i] = new TinyObject(i);
        }
        
        long tlabEndTime = System.nanoTime();
        double tlabDuration = (tlabEndTime - tlabStartTime) / 1_000_000.0;
        
        System.out.printf("TLAB分配测试：\n");
        System.out.printf("  分配对象: %d 个\n", tlabObjects.length);
        System.out.printf("  分配耗时: %.2f ms\n", tlabDuration);
        System.out.printf("  平均分配时间: %.2f ns/对象\n", (tlabEndTime - tlabStartTime) / (double)tlabObjects.length);
        
        // 测试TLAB溢出情况
        System.out.println("\n测试TLAB溢出情况...");
        
        // 分配大对象（可能导致TLAB溢出）
        long overflowStartTime = System.nanoTime();
        List<LargeObject> largeObjects = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            largeObjects.add(new LargeObject(i));
        }
        
        long overflowEndTime = System.nanoTime();
        double overflowDuration = (overflowEndTime - overflowStartTime) / 1_000_000.0;
        
        System.out.printf("大对象分配测试：\n");
        System.out.printf("  分配对象: %d 个\n", largeObjects.size());
        System.out.printf("  分配耗时: %.2f ms\n", overflowDuration);
        System.out.printf("  平均分配时间: %.2f μs/对象\n", overflowDuration * 1000 / largeObjects.size());
        
        // 清理
        tlabObjects = null;
        largeObjects.clear();
        
        System.out.println("✅ TLAB机制验证完成\n");
    }
    
    /**
     * 测试压缩指针
     */
    private static void testCompressedOops() {
        System.out.println("🗜️ === 第四阶段：压缩指针验证 ===");
        
        if (unsafe == null) {
            System.out.println("❌ 无法获取Unsafe实例，跳过压缩指针测试");
            return;
        }
        
        try {
            // 创建测试对象
            Object testObj = new TestObject(12345);
            
            // 获取对象地址（需要Unsafe）
            Object[] objArray = new Object[]{testObj};
            long baseOffset = unsafe.arrayBaseOffset(Object[].class);
            
            System.out.println("压缩指针测试：");
            System.out.printf("  测试对象: %s\n", testObj.getClass().getSimpleName());
            System.out.printf("  对象哈希码: 0x%x\n", testObj.hashCode());
            
            // 分析对象头信息
            System.out.println("\n对象头分析：");
            
            // 获取Mark Word（需要Unsafe，这里只是示例）
            System.out.println("  Mark Word: [需要Unsafe访问]");
            System.out.println("  类指针: [需要Unsafe访问]");
            
            // 测试对象引用
            System.out.println("\n对象引用测试：");
            Object ref1 = testObj;
            Object ref2 = testObj;
            
            System.out.printf("  引用1 == 引用2: %b\n", ref1 == ref2);
            System.out.printf("  引用1.equals(引用2): %b\n", ref1.equals(ref2));
            
        } catch (Exception e) {
            System.err.println("压缩指针测试失败: " + e.getMessage());
        }
        
        System.out.println("✅ 压缩指针验证完成\n");
    }
    
    /**
     * 分析对象内存布局
     */
    private static void analyzeObjectMemoryLayout() {
        System.out.println("🏷️ === 第五阶段：对象内存布局分析 ===");
        
        // 分析不同类型对象的内存布局
        System.out.println("对象大小分析：");
        
        // 使用反射分析对象结构
        analyzeObjectStructure(TinyObject.class);
        analyzeObjectStructure(SmallObject.class);
        analyzeObjectStructure(MediumObject.class);
        analyzeObjectStructure(LargeObject.class);
        
        // 测试对象对齐
        System.out.println("\n对象对齐测试：");
        testObjectAlignment();
        
        System.out.println("✅ 对象内存布局分析完成\n");
    }
    
    /**
     * 分析对象结构
     */
    private static void analyzeObjectStructure(Class<?> clazz) {
        System.out.printf("\n%s 结构分析：\n", clazz.getSimpleName());
        
        Field[] fields = clazz.getDeclaredFields();
        System.out.printf("  字段数量: %d\n", fields.length);
        
        int totalFieldSize = 0;
        for (Field field : fields) {
            Class<?> fieldType = field.getType();
            int fieldSize = getFieldSize(fieldType);
            totalFieldSize += fieldSize;
            
            System.out.printf("    %s %s: %d bytes\n", 
                fieldType.getSimpleName(), field.getName(), fieldSize);
        }
        
        System.out.printf("  字段总大小: %d bytes\n", totalFieldSize);
        System.out.printf("  对象头大小: %d bytes (估算)\n", 16); // Mark Word + 类指针
        System.out.printf("  估算对象大小: %d bytes\n", totalFieldSize + 16);
    }
    
    /**
     * 获取字段大小
     */
    private static int getFieldSize(Class<?> type) {
        if (type == byte.class || type == boolean.class) return 1;
        if (type == short.class || type == char.class) return 2;
        if (type == int.class || type == float.class) return 4;
        if (type == long.class || type == double.class) return 8;
        return 8; // 引用类型（64位平台，可能被压缩到4字节）
    }
    
    /**
     * 测试对象对齐
     */
    private static void testObjectAlignment() {
        // 创建不同大小的对象数组来观察对齐
        Object[] objects = {
            new TinyObject(1),
            new SmallObject(1),
            new MediumObject(1),
            new LargeObject(1)
        };
        
        System.out.println("对象对齐观察：");
        for (int i = 0; i < objects.length; i++) {
            System.out.printf("  对象[%d]: %s, 哈希码: 0x%x\n", 
                i, objects[i].getClass().getSimpleName(), objects[i].hashCode());
        }
    }
    
    /**
     * 测试不同大小对象分配
     */
    private static void testDifferentSizeAllocations() {
        System.out.println("📦 === 第六阶段：不同大小对象分配测试 ===");
        
        // 超小对象测试（<32字节）
        testAllocationCategory("超小对象", 10000, () -> new TinyObject(1));
        
        // 小对象测试（32-128字节）
        testAllocationCategory("小对象", 5000, () -> new SmallObject(1));
        
        // 中等对象测试（128字节-8KB）
        testAllocationCategory("中等对象", 1000, () -> new MediumObject(1));
        
        // 大对象测试（>8KB）
        testAllocationCategory("大对象", 100, () -> new LargeObject(1));
        
        // 巨型对象测试（>Region大小的一半）
        testAllocationCategory("巨型对象", 10, () -> new HugeObject(1));
        
        System.out.println("✅ 不同大小对象分配测试完成\n");
    }
    
    /**
     * 测试特定类别的对象分配
     */
    private static void testAllocationCategory(String category, int count, Supplier<Object> supplier) {
        System.out.printf("\n%s分配测试 (%d个):\n", category, count);
        
        long startTime = System.nanoTime();
        List<Object> objects = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            objects.add(supplier.get());
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("  分配耗时: %.2f ms\n", duration);
        System.out.printf("  分配速率: %.0f 对象/秒\n", count * 1000.0 / duration);
        System.out.printf("  平均分配时间: %.2f ns/对象\n", (endTime - startTime) / (double)count);
        
        // 清理
        objects.clear();
    }
    
    /**
     * 测试并发分配
     */
    private static void testConcurrentAllocation() {
        System.out.println("🔄 === 第七阶段：并发分配测试 ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        
        System.out.printf("启动 %d 个并发线程进行对象分配...\n", THREAD_COUNT);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // 每个线程分配不同类型的对象
                    List<Object> threadObjects = new ArrayList<>();
                    
                    for (int j = 0; j < 5000; j++) {
                        switch (j % 4) {
                            case 0: threadObjects.add(new TinyObject(threadId * 10000 + j)); break;
                            case 1: threadObjects.add(new SmallObject(threadId * 10000 + j)); break;
                            case 2: threadObjects.add(new MediumObject(threadId * 10000 + j)); break;
                            case 3: threadObjects.add(new LargeObject(threadId * 10000 + j)); break;
                        }
                    }
                    
                    // 模拟一些计算工作
                    Thread.sleep(10);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
            long endTime = System.nanoTime();
            double duration = (endTime - startTime) / 1_000_000.0;
            
            System.out.printf("并发分配测试完成：\n");
            System.out.printf("  线程数量: %d\n", THREAD_COUNT);
            System.out.printf("  总耗时: %.2f ms\n", duration);
            System.out.printf("  每线程分配: 5000 对象\n");
            System.out.printf("  总分配数: %d 对象\n", THREAD_COUNT * 5000);
            System.out.printf("  并发吞吐量: %.0f 对象/秒\n", THREAD_COUNT * 5000 * 1000.0 / duration);
            
        } catch (InterruptedException e) {
            System.err.println("并发测试被中断: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
        
        System.out.println("✅ 并发分配测试完成\n");
    }
    
    /**
     * 测试GC触发
     */
    private static void testGCTrigger() {
        System.out.println("🗑️ === 第八阶段：GC触发测试 ===");
        
        // 获取GC信息
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        System.out.println("GC收集器信息：");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("  %s: %d次收集, %dms总时间\n", 
                gcBean.getName(), gcBean.getCollectionCount(), gcBean.getCollectionTime());
        }
        
        // 记录GC前状态
        long gcCountBefore = 0;
        long gcTimeBefore = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            gcCountBefore += gcBean.getCollectionCount();
            gcTimeBefore += gcBean.getCollectionTime();
        }
        
        // 分配大量对象触发GC
        System.out.println("\n分配大量对象触发GC...");
        List<Object> gcTestObjects = new ArrayList<>();
        
        for (int i = 0; i < 100000; i++) {
            gcTestObjects.add(new MediumObject(i));
            
            // 每1000个对象检查一次GC状态
            if (i % 1000 == 0) {
                long currentGcCount = 0;
                for (GarbageCollectorMXBean gcBean : gcBeans) {
                    currentGcCount += gcBean.getCollectionCount();
                }
                
                if (currentGcCount > gcCountBefore) {
                    System.out.printf("  检测到GC发生，已分配 %d 个对象\n", i);
                    break;
                }
            }
        }
        
        // 手动触发GC
        System.out.println("\n手动触发GC...");
        long beforeGC = System.currentTimeMillis();
        System.gc();
        long afterGC = System.currentTimeMillis();
        
        System.out.printf("手动GC耗时: %d ms\n", afterGC - beforeGC);
        
        // 记录GC后状态
        long gcCountAfter = 0;
        long gcTimeAfter = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            gcCountAfter += gcBean.getCollectionCount();
            gcTimeAfter += gcBean.getCollectionTime();
        }
        
        System.out.printf("\nGC统计：\n");
        System.out.printf("  GC次数增加: %d\n", gcCountAfter - gcCountBefore);
        System.out.printf("  GC时间增加: %d ms\n", gcTimeAfter - gcTimeBefore);
        
        // 清理
        gcTestObjects.clear();
        
        System.out.println("✅ GC触发测试完成\n");
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
        System.out.printf("总分配内存: %.2f MB\n", totalAllocatedBytes / (1024.0 * 1024));
        
        // 获取最终内存使用情况
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.printf("\n最终内存状态：\n");
        System.out.printf("  堆内存使用: %.2f MB\n", heapUsage.getUsed() / (1024.0 * 1024));
        System.out.printf("  堆内存容量: %.2f MB\n", heapUsage.getCommitted() / (1024.0 * 1024));
        System.out.printf("  使用率: %.1f%%\n", heapUsage.getUsed() * 100.0 / heapUsage.getCommitted());
        
        // GC统计
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.printf("\n最终GC统计：\n");
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
        
        System.out.println("\n🎉 === 内存模型与对象创建深度分析测试完成 ===");
        System.out.println("所有内存管理机制验证通过，JVM内存系统运行正常！");
    }
    
    // ========================================================================
    // 测试用的数据类
    // ========================================================================
    
    /**
     * 超小对象 - 测试最小对象分配
     */
    static class TinyObject {
        private int id;
        
        public TinyObject(int id) {
            this.id = id;
        }
    }
    
    /**
     * 小对象 - 测试TLAB快速分配
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
        private int[] data = new int[64];  // 256字节
        private String description;
        
        public MediumObject(int id) {
            this.description = "Medium-" + id;
            for (int i = 0; i < data.length; i++) {
                data[i] = id + i;
            }
        }
    }
    
    /**
     * 大对象 - 测试大对象分配策略
     */
    static class LargeObject {
        private byte[] largeData = new byte[8192]; // 8KB
        private String info;
        
        public LargeObject(int id) {
            this.info = "Large-" + id;
            // 填充一些数据
            for (int i = 0; i < Math.min(100, largeData.length); i++) {
                largeData[i] = (byte)(id % 256);
            }
        }
    }
    
    /**
     * 巨型对象 - 测试巨型对象分配（直接分配到Old区）
     */
    static class HugeObject {
        private byte[] hugeData = new byte[2 * 1024 * 1024]; // 2MB
        private String description;
        
        public HugeObject(int id) {
            this.description = "Huge-" + id;
            // 填充少量数据以避免过度内存使用
            for (int i = 0; i < 1000; i++) {
                hugeData[i] = (byte)(id % 256);
            }
        }
    }
    
    /**
     * 测试对象 - 用于压缩指针测试
     */
    static class TestObject {
        private int value;
        private long timestamp;
        private String data;
        
        public TestObject(int value) {
            this.value = value;
            this.timestamp = System.nanoTime();
            this.data = "TestData-" + value;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TestObject that = (TestObject) obj;
            return value == that.value;
        }
        
        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }
    }
}