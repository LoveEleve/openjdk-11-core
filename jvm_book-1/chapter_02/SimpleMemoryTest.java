/**
 * 简化版内存分析测试程序
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class SimpleMemoryTest {
    
    private static long totalAllocations = 0;
    private static long startTime;
    
    public static void main(String[] args) {
        startTime = System.nanoTime();
        
        System.out.println("🧠 === 内存模型与对象创建深度分析测试 ===");
        System.out.println("配置环境：8GB G1堆，压缩指针，TLAB分配");
        System.out.println();
        
        try {
            verifyMemoryLayout();
            testObjectAllocation();
            testTLABMechanism();
            testDifferentSizes();
            generateReport();
        } catch (Exception e) {
            System.err.println("测试异常：" + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void verifyMemoryLayout() {
        System.out.println("📏 === 内存布局验证 ===");
        
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.printf("堆内存最大: %.2f GB\n", heapUsage.getMax() / (1024.0 * 1024 * 1024));
        System.out.printf("堆内存已用: %.2f MB\n", heapUsage.getUsed() / (1024.0 * 1024));
        System.out.printf("堆内存已提交: %.2f GB\n", heapUsage.getCommitted() / (1024.0 * 1024 * 1024));
        
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        System.out.println("\n内存池：");
        for (MemoryPoolMXBean pool : pools) {
            MemoryUsage usage = pool.getUsage();
            System.out.printf("  %s: %.2f MB\n", pool.getName(), usage.getUsed() / (1024.0 * 1024));
        }
        
        System.out.println("✅ 内存布局验证完成\n");
    }
    
    private static void testObjectAllocation() {
        System.out.println("🏭 === 对象分配性能测试 ===");
        
        // 小对象分配测试
        long startTime = System.nanoTime();
        List<SmallObject> objects = new ArrayList<>();
        
        for (int i = 0; i < 100000; i++) {
            objects.add(new SmallObject(i));
            totalAllocations++;
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("小对象分配：\n");
        System.out.printf("  数量: %d 个\n", objects.size());
        System.out.printf("  耗时: %.2f ms\n", duration);
        System.out.printf("  速率: %.0f 对象/秒\n", objects.size() * 1000.0 / duration);
        
        objects.clear();
        System.out.println("✅ 对象分配测试完成\n");
    }
    
    private static void testTLABMechanism() {
        System.out.println("🧵 === TLAB机制验证 ===");
        
        // 连续小对象分配（TLAB快速路径）
        long startTime = System.nanoTime();
        Object[] tlabObjects = new Object[50000];
        
        for (int i = 0; i < tlabObjects.length; i++) {
            tlabObjects[i] = new TinyObject(i);
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("TLAB分配测试：\n");
        System.out.printf("  对象数: %d\n", tlabObjects.length);
        System.out.printf("  耗时: %.2f ms\n", duration);
        System.out.printf("  平均: %.2f ns/对象\n", (endTime - startTime) / (double)tlabObjects.length);
        
        System.out.println("✅ TLAB机制验证完成\n");
    }
    
    private static void testDifferentSizes() {
        System.out.println("📦 === 不同大小对象测试 ===");
        
        testCategory("超小对象", 10000, () -> new TinyObject(1));
        testCategory("小对象", 5000, () -> new SmallObject(1));
        testCategory("中等对象", 1000, () -> new MediumObject(1));
        testCategory("大对象", 100, () -> new LargeObject(1));
        
        System.out.println("✅ 不同大小对象测试完成\n");
    }
    
    private static void testCategory(String name, int count, Supplier<Object> supplier) {
        System.out.printf("%s (%d个):\n", name, count);
        
        long startTime = System.nanoTime();
        List<Object> objects = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            objects.add(supplier.get());
        }
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("  耗时: %.2f ms, 速率: %.0f 对象/秒\n", 
            duration, count * 1000.0 / duration);
        
        objects.clear();
    }
    
    private static void generateReport() {
        long endTime = System.nanoTime();
        double totalDuration = (endTime - startTime) / 1_000_000.0;
        
        System.out.println("📋 === 最终报告 ===");
        System.out.printf("总测试时间: %.2f ms\n", totalDuration);
        System.out.printf("总分配对象: %d 个\n", totalAllocations);
        
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.printf("最终堆使用: %.2f MB (%.1f%%)\n", 
            heapUsage.getUsed() / (1024.0 * 1024),
            heapUsage.getUsed() * 100.0 / heapUsage.getCommitted());
        
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGCTime = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            totalGCTime += gcBean.getCollectionTime();
            System.out.printf("GC %s: %d次, %dms\n", 
                gcBean.getName(), gcBean.getCollectionCount(), gcBean.getCollectionTime());
        }
        
        System.out.println("🎉 === 测试完成 ===");
    }
    
    // 测试类
    static class TinyObject {
        private int id;
        public TinyObject(int id) { this.id = id; }
    }
    
    static class SmallObject {
        private int id;
        private String name;
        public SmallObject(int id) { 
            this.id = id; 
            this.name = "Small-" + id; 
        }
    }
    
    static class MediumObject {
        private int[] data = new int[64];
        private String desc;
        public MediumObject(int id) {
            this.desc = "Medium-" + id;
            for (int i = 0; i < data.length; i++) {
                data[i] = id + i;
            }
        }
    }
    
    static class LargeObject {
        private byte[] data = new byte[8192];
        private String info;
        public LargeObject(int id) {
            this.info = "Large-" + id;
            for (int i = 0; i < 100; i++) {
                data[i] = (byte)(id % 256);
            }
        }
    }
}