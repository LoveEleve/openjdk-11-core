/**
 * 第05章：G1垃圾收集器测试程序
 * 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 配置
 * 
 * 功能：
 * 1. 验证G1堆Region管理
 * 2. 触发不同类型的GC
 * 3. 测试大对象分配
 * 4. 分析GC性能特征
 * 5. 验证并发标记机制
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

public class G1GCTest {
    
    // 用于控制内存分配的参数
    private static final int REGION_SIZE = 4 * 1024 * 1024; // 4MB Region
    private static final int LARGE_OBJECT_THRESHOLD = REGION_SIZE / 2; // 2MB
    private static final int ALLOCATION_ROUNDS = 10;
    
    // 存储对象引用，防止被过早回收
    private static List<Object> longLivedObjects = new ArrayList<>();
    private static List<byte[]> largeObjects = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("第05章：G1垃圾收集器验证测试");
        System.out.println("JVM配置: -Xms=Xmx=8GB, G1GC");
        System.out.println("Region大小: 4MB, 总Region数: 2048");
        System.out.println("========================================\n");
        
        // 1. G1堆配置验证
        verifyG1Configuration();
        
        // 2. Young GC触发测试
        testYoungGC();
        
        // 3. 大对象分配测试
        testHumongousAllocation();
        
        // 4. Mixed GC触发测试
        testMixedGC();
        
        // 5. 并发标记测试
        testConcurrentMarking();
        
        // 6. GC性能分析
        performGCAnalysis();
        
        // 7. 内存压力测试
        memoryPressureTest();
        
        System.out.println("\n========================================");
        System.out.println("G1垃圾收集器验证完成");
        System.out.println("========================================");
    }
    
    /**
     * 1. G1堆配置验证
     */
    private static void verifyG1Configuration() {
        System.out.println("=== 1. G1堆配置验证 ===");
        
        // 获取内存管理信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.println("堆内存配置:");
        System.out.println("  初始大小: " + formatBytes(heapUsage.getInit()));
        System.out.println("  当前大小: " + formatBytes(heapUsage.getCommitted()));
        System.out.println("  最大大小: " + formatBytes(heapUsage.getMax()));
        System.out.println("  已使用: " + formatBytes(heapUsage.getUsed()));
        
        // 验证G1特定配置
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        System.out.println("\n垃圾收集器信息:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.println("  收集器: " + gcBean.getName());
            System.out.println("  收集次数: " + gcBean.getCollectionCount());
            System.out.println("  收集时间: " + gcBean.getCollectionTime() + " ms");
        }
        
        // 计算Region相关信息
        long heapSize = heapUsage.getMax();
        long regionCount = heapSize / REGION_SIZE;
        
        System.out.println("\nG1 Region信息:");
        System.out.println("  Region大小: " + formatBytes(REGION_SIZE));
        System.out.println("  总Region数: " + regionCount);
        System.out.println("  大对象阈值: " + formatBytes(LARGE_OBJECT_THRESHOLD));
        
        System.out.println();
    }
    
    /**
     * 2. Young GC触发测试
     */
    private static void testYoungGC() throws Exception {
        System.out.println("=== 2. Young GC触发测试 ===");
        
        // 记录GC前状态
        long gcCountBefore = getTotalGCCount();
        long gcTimeBefore = getTotalGCTime();
        
        System.out.println("开始分配短生命周期对象以触发Young GC...");
        
        // 分配大量短生命周期对象
        for (int round = 0; round < ALLOCATION_ROUNDS; round++) {
            List<byte[]> tempObjects = new ArrayList<>();
            
            // 每轮分配约100MB对象
            for (int i = 0; i < 1000; i++) {
                byte[] obj = new byte[100 * 1024]; // 100KB对象
                tempObjects.add(obj);
                
                // 填充一些数据
                Arrays.fill(obj, (byte)(i % 256));
            }
            
            System.out.println("  第" + (round + 1) + "轮分配完成，分配了" + 
                             tempObjects.size() + "个对象");
            
            // 让对象变为垃圾
            tempObjects.clear();
            
            // 偶尔保留一些对象到老年代
            if (round % 3 == 0) {
                byte[] longLived = new byte[50 * 1024];
                longLivedObjects.add(longLived);
            }
            
            // 短暂休眠，让GC有机会运行
            Thread.sleep(100);
        }
        
        // 强制触发GC
        System.gc();
        Thread.sleep(1000);
        
        // 记录GC后状态
        long gcCountAfter = getTotalGCCount();
        long gcTimeAfter = getTotalGCTime();
        
        System.out.println("Young GC测试结果:");
        System.out.println("  触发GC次数: " + (gcCountAfter - gcCountBefore));
        System.out.println("  GC总耗时: " + (gcTimeAfter - gcTimeBefore) + " ms");
        System.out.println("  平均GC时间: " + 
                         (gcCountAfter > gcCountBefore ? 
                          (gcTimeAfter - gcTimeBefore) / (gcCountAfter - gcCountBefore) : 0) + " ms");
        
        System.out.println();
    }
    
    /**
     * 3. 大对象分配测试
     */
    private static void testHumongousAllocation() {
        System.out.println("=== 3. 大对象分配测试 ===");
        
        System.out.println("开始分配大对象 (>2MB)...");
        
        // 分配不同大小的大对象
        int[] sizes = {
            3 * 1024 * 1024,  // 3MB - 占用1个Region
            6 * 1024 * 1024,  // 6MB - 占用2个Region
            10 * 1024 * 1024, // 10MB - 占用3个Region
            20 * 1024 * 1024  // 20MB - 占用5个Region
        };
        
        for (int i = 0; i < sizes.length; i++) {
            try {
                System.out.println("  分配大对象 " + (i + 1) + ": " + formatBytes(sizes[i]));
                
                byte[] largeObject = new byte[sizes[i]];
                
                // 填充数据以确保对象被真正分配
                for (int j = 0; j < largeObject.length; j += 4096) {
                    largeObject[j] = (byte)(j % 256);
                }
                
                largeObjects.add(largeObject);
                
                System.out.println("    分配成功，Region占用: " + 
                                 ((sizes[i] + REGION_SIZE - 1) / REGION_SIZE) + "个");
                
            } catch (OutOfMemoryError e) {
                System.out.println("    分配失败: " + e.getMessage());
                break;
            }
        }
        
        // 显示当前内存使用情况
        MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        System.out.println("\n大对象分配后内存使用:");
        System.out.println("  已使用: " + formatBytes(heapUsage.getUsed()));
        System.out.println("  使用率: " + String.format("%.2f%%", 
                          (double)heapUsage.getUsed() / heapUsage.getCommitted() * 100));
        
        System.out.println();
    }
    
    /**
     * 4. Mixed GC触发测试
     */
    private static void testMixedGC() throws Exception {
        System.out.println("=== 4. Mixed GC触发测试 ===");
        
        System.out.println("创建老年代对象以触发Mixed GC...");
        
        // 创建大量长生命周期对象，让它们晋升到老年代
        List<Object[]> oldGenObjects = new ArrayList<>();
        
        for (int i = 0; i < 500; i++) {
            // 创建对象数组
            Object[] objArray = new Object[1000];
            
            // 填充引用
            for (int j = 0; j < objArray.length; j++) {
                objArray[j] = new String("OldGenObject_" + i + "_" + j);
            }
            
            oldGenObjects.add(objArray);
            
            // 定期触发Young GC，促进对象晋升
            if (i % 50 == 0) {
                // 分配一些临时对象触发Young GC
                for (int k = 0; k < 100; k++) {
                    byte[] temp = new byte[50 * 1024];
                }
                System.gc(); // 建议GC运行
                Thread.sleep(50);
                
                System.out.println("  已创建 " + (i + 1) + " 组老年代对象");
            }
        }
        
        // 让一些老年代对象变为垃圾
        for (int i = 0; i < oldGenObjects.size(); i += 3) {
            oldGenObjects.set(i, null); // 制造垃圾
        }
        
        System.out.println("制造老年代垃圾，等待Mixed GC触发...");
        
        // 继续分配年轻代对象，触发Mixed GC
        for (int i = 0; i < 200; i++) {
            byte[] youngObj = new byte[100 * 1024];
            if (i % 50 == 0) {
                System.gc();
                Thread.sleep(100);
            }
        }
        
        System.out.println("Mixed GC测试完成");
        System.out.println();
    }
    
    /**
     * 5. 并发标记测试
     */
    private static void testConcurrentMarking() throws Exception {
        System.out.println("=== 5. 并发标记测试 ===");
        
        System.out.println("创建复杂对象图以测试并发标记...");
        
        // 创建复杂的对象引用图
        List<Node> nodeList = new ArrayList<>();
        
        // 创建节点
        for (int i = 0; i < 1000; i++) {
            Node node = new Node("Node_" + i);
            nodeList.add(node);
        }
        
        // 建立复杂的引用关系
        Random random = new Random(42); // 固定种子保证可重现
        for (Node node : nodeList) {
            // 每个节点随机引用其他节点
            int refCount = random.nextInt(5) + 1;
            for (int i = 0; i < refCount; i++) {
                int targetIndex = random.nextInt(nodeList.size());
                node.addReference(nodeList.get(targetIndex));
            }
        }
        
        System.out.println("创建了 " + nodeList.size() + " 个节点的对象图");
        
        // 记录并发标记前的状态
        long gcCountBefore = getTotalGCCount();
        
        // 触发并发标记
        System.out.println("触发并发标记...");
        System.gc();
        
        // 在并发标记期间继续分配对象
        for (int i = 0; i < 100; i++) {
            // 分配一些对象
            byte[] obj = new byte[50 * 1024];
            
            // 修改对象图结构，测试SATB
            if (i % 10 == 0 && !nodeList.isEmpty()) {
                int index1 = random.nextInt(nodeList.size());
                int index2 = random.nextInt(nodeList.size());
                nodeList.get(index1).addReference(nodeList.get(index2));
            }
            
            Thread.sleep(10); // 给并发标记时间
        }
        
        // 等待并发标记完成
        Thread.sleep(2000);
        
        long gcCountAfter = getTotalGCCount();
        
        System.out.println("并发标记测试结果:");
        System.out.println("  期间GC次数: " + (gcCountAfter - gcCountBefore));
        System.out.println("  对象图节点数: " + nodeList.size());
        
        System.out.println();
    }
    
    /**
     * 6. GC性能分析
     */
    private static void performGCAnalysis() {
        System.out.println("=== 6. GC性能分析 ===");
        
        // 获取GC统计信息
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        long totalCollections = 0;
        long totalTime = 0;
        
        System.out.println("详细GC统计:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long collections = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            
            totalCollections += collections;
            totalTime += time;
            
            System.out.println("  " + gcBean.getName() + ":");
            System.out.println("    收集次数: " + collections);
            System.out.println("    总时间: " + time + " ms");
            System.out.println("    平均时间: " + 
                             (collections > 0 ? time / collections : 0) + " ms");
            System.out.println("    管理的内存池: " + 
                             Arrays.toString(gcBean.getMemoryPoolNames()));
        }
        
        System.out.println("\n总体GC性能:");
        System.out.println("  总收集次数: " + totalCollections);
        System.out.println("  总收集时间: " + totalTime + " ms");
        System.out.println("  平均收集时间: " + 
                         (totalCollections > 0 ? totalTime / totalCollections : 0) + " ms");
        
        // 内存使用效率分析
        MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        
        System.out.println("\n内存使用效率:");
        System.out.println("  堆使用率: " + String.format("%.2f%%", 
                          (double)heapUsage.getUsed() / heapUsage.getCommitted() * 100));
        System.out.println("  长期存活对象: " + longLivedObjects.size());
        System.out.println("  大对象数量: " + largeObjects.size());
        
        System.out.println();
    }
    
    /**
     * 7. 内存压力测试
     */
    private static void memoryPressureTest() throws Exception {
        System.out.println("=== 7. 内存压力测试 ===");
        
        System.out.println("开始内存压力测试...");
        
        List<byte[]> pressureObjects = new ArrayList<>();
        long totalAllocated = 0;
        
        try {
            // 持续分配内存直到接近堆限制
            while (true) {
                MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                double usageRatio = (double)heapUsage.getUsed() / heapUsage.getMax();
                
                if (usageRatio > 0.8) { // 使用率超过80%时停止
                    System.out.println("达到内存使用阈值，停止分配");
                    break;
                }
                
                // 分配1MB对象
                byte[] obj = new byte[1024 * 1024];
                pressureObjects.add(obj);
                totalAllocated += obj.length;
                
                if (pressureObjects.size() % 100 == 0) {
                    System.out.println("  已分配: " + formatBytes(totalAllocated) + 
                                     ", 堆使用率: " + String.format("%.2f%%", usageRatio * 100));
                }
                
                // 偶尔释放一些对象
                if (pressureObjects.size() % 200 == 0 && pressureObjects.size() > 100) {
                    for (int i = 0; i < 50; i++) {
                        pressureObjects.remove(0);
                    }
                }
                
                Thread.sleep(10);
            }
            
        } catch (OutOfMemoryError e) {
            System.out.println("内存不足: " + e.getMessage());
        }
        
        System.out.println("内存压力测试完成:");
        System.out.println("  总分配内存: " + formatBytes(totalAllocated));
        System.out.println("  存活对象数: " + pressureObjects.size());
        
        // 清理压力测试对象
        pressureObjects.clear();
        System.gc();
        
        System.out.println();
    }
    
    // ============================================
    // 辅助方法和类
    // ============================================
    
    /**
     * 获取总GC次数
     */
    private static long getTotalGCCount() {
        long total = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gcBean.getCollectionCount();
        }
        return total;
    }
    
    /**
     * 获取总GC时间
     */
    private static long getTotalGCTime() {
        long total = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gcBean.getCollectionTime();
        }
        return total;
    }
    
    /**
     * 格式化字节数
     */
    private static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        
        String[] units = {"B", "KB", "MB", "GB"};
        double size = bytes;
        int unitIndex = 0;
        
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        
        return String.format("%.2f %s", size, units[unitIndex]);
    }
    
    /**
     * 测试节点类 - 用于构建复杂对象图
     */
    static class Node {
        private String name;
        private List<Node> references;
        private byte[] data;
        
        public Node(String name) {
            this.name = name;
            this.references = new ArrayList<>();
            this.data = new byte[1024]; // 1KB数据
            Arrays.fill(data, (byte)name.hashCode());
        }
        
        public void addReference(Node node) {
            if (node != null && node != this && !references.contains(node)) {
                references.add(node);
            }
        }
        
        public String getName() {
            return name;
        }
        
        public List<Node> getReferences() {
            return references;
        }
        
        @Override
        public String toString() {
            return name + " (refs: " + references.size() + ")";
        }
    }
}