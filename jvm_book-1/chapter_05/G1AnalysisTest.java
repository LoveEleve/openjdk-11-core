/**
 * G1垃圾收集器深度分析测试程序
 * 
 * 基于8GB G1堆配置的完整测试套件，验证G1收集器的各个核心功能：
 * - Region管理和分配策略
 * - 并发标记和混合回收
 * - 停顿时间预测和控制
 * - 大对象处理和TLAB分配
 * - 记忆集维护和卡表更新
 * 
 * 编译: javac G1AnalysisTest.java
 * 运行: java -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 G1AnalysisTest
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.ref.*;

public class G1AnalysisTest {
    
    // 测试配置常量
    private static final int REGION_SIZE_MB = 4;  // G1 Region大小 4MB
    private static final int TOTAL_REGIONS = 2048; // 8GB / 4MB = 2048个Region
    private static final int SMALL_OBJECT_SIZE = 64;     // 64字节小对象
    private static final int MEDIUM_OBJECT_SIZE = 1024;  // 1KB中等对象
    private static final int LARGE_OBJECT_SIZE = 1024 * 1024; // 1MB大对象
    private static final int HUMONGOUS_OBJECT_SIZE = 3 * 1024 * 1024; // 3MB巨型对象
    
    // 测试数据容器
    private static List<Object> youngGenObjects = new ArrayList<>();
    private static List<Object> oldGenObjects = new ArrayList<>();
    private static List<Object> humongousObjects = new ArrayList<>();
    private static AtomicLong totalAllocatedBytes = new AtomicLong(0);
    
    // GC监控
    private static List<GarbageCollectorMXBean> gcBeans;
    private static MemoryMXBean memoryBean;
    private static long initialGCCount = 0;
    private static long initialGCTime = 0;
    
    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("G1垃圾收集器深度分析测试程序");
        System.out.println("================================================================================");
        
        // 初始化监控
        initializeMonitoring();
        
        try {
            // 阶段1: 环境验证和基础信息
            phase1_EnvironmentValidation();
            
            // 阶段2: Region分配模式测试
            phase2_RegionAllocationTest();
            
            // 阶段3: TLAB和快速分配测试
            phase3_TLABAllocationTest();
            
            // 阶段4: 并发标记触发测试
            phase4_ConcurrentMarkingTest();
            
            // 阶段5: 混合GC测试
            phase5_MixedGCTest();
            
            // 阶段6: 巨型对象处理测试
            phase6_HumongousObjectTest();
            
            // 阶段7: 记忆集和跨代引用测试
            phase7_RememberedSetTest();
            
            // 阶段8: 停顿时间控制测试
            phase8_PauseTimeControlTest();
            
            // 阶段9: 性能基准测试
            phase9_PerformanceBenchmark();
            
            // 阶段10: 最终状态分析
            phase10_FinalStateAnalysis();
            
        } catch (Exception e) {
            System.err.println("❌ 测试执行异常: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n🎉 G1垃圾收集器深度分析测试完成！");
    }
    
    /**
     * 阶段1: 环境验证和基础信息
     */
    private static void phase1_EnvironmentValidation() {
        System.out.println("\n=== 阶段1: 环境验证和基础信息 ===");
        
        // 验证JVM配置
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        List<String> jvmArgs = runtimeBean.getInputArguments();
        
        System.out.println("🔧 JVM启动参数验证:");
        boolean useG1 = false, heapSizeOk = false;
        for (String arg : jvmArgs) {
            if (arg.contains("UseG1GC")) {
                useG1 = true;
                System.out.println("   ✅ G1垃圾收集器: " + arg);
            } else if (arg.contains("Xms") || arg.contains("Xmx")) {
                if (arg.contains("8g") || arg.contains("8G")) {
                    heapSizeOk = true;
                }
                System.out.println("   ✅ 堆内存配置: " + arg);
            } else if (arg.contains("MaxGCPauseMillis")) {
                System.out.println("   ✅ 停顿时间目标: " + arg);
            }
        }
        
        if (!useG1) {
            System.out.println("   ⚠️  警告: 未检测到G1垃圾收集器配置");
        }
        if (!heapSizeOk) {
            System.out.println("   ⚠️  警告: 堆内存可能不是8GB配置");
        }
        
        // 内存布局信息
        System.out.println("\n💾 内存布局信息:");
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long maxHeap = heapUsage.getMax();
        long initHeap = heapUsage.getInit();
        long usedHeap = heapUsage.getUsed();
        long committedHeap = heapUsage.getCommitted();
        
        System.out.printf("   最大堆内存: %d MB\n", maxHeap / (1024 * 1024));
        System.out.printf("   初始堆内存: %d MB\n", initHeap / (1024 * 1024));
        System.out.printf("   已用堆内存: %d MB (%.1f%%)\n", 
                         usedHeap / (1024 * 1024), 
                         (usedHeap * 100.0) / committedHeap);
        System.out.printf("   已提交堆内存: %d MB (%.1f%%)\n", 
                         committedHeap / (1024 * 1024), 
                         (committedHeap * 100.0) / maxHeap);
        
        // 计算理论Region配置
        long regionSize = REGION_SIZE_MB * 1024 * 1024;
        long maxRegions = maxHeap / regionSize;
        System.out.printf("   理论Region大小: %d MB\n", REGION_SIZE_MB);
        System.out.printf("   理论最大Region数: %d\n", maxRegions);
        
        // GC收集器信息
        System.out.println("\n🗑️  垃圾收集器信息:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.printf("   收集器: %s\n", gcBean.getName());
            System.out.printf("     收集次数: %d\n", gcBean.getCollectionCount());
            System.out.printf("     收集时间: %d ms\n", gcBean.getCollectionTime());
        }
        
        recordGCBaseline();
    }
    
    /**
     * 阶段2: Region分配模式测试
     */
    private static void phase2_RegionAllocationTest() {
        System.out.println("\n=== 阶段2: Region分配模式测试 ===");
        
        long startTime = System.nanoTime();
        long startUsed = memoryBean.getHeapMemoryUsage().getUsed();
        
        System.out.println("🏗️  测试Eden Region分配模式...");
        
        // 分配足够的对象来填充多个Eden Region
        List<byte[]> edenObjects = new ArrayList<>();
        int objectsPerRegion = (REGION_SIZE_MB * 1024 * 1024) / MEDIUM_OBJECT_SIZE;
        int targetRegions = 10; // 目标填充10个Region
        
        System.out.printf("   目标填充Region数: %d\n", targetRegions);
        System.out.printf("   每个Region对象数: %d\n", objectsPerRegion);
        
        for (int region = 0; region < targetRegions; region++) {
            System.out.printf("   正在填充Region %d...", region + 1);
            
            for (int obj = 0; obj < objectsPerRegion; obj++) {
                byte[] object = new byte[MEDIUM_OBJECT_SIZE];
                // 填充一些数据避免优化
                Arrays.fill(object, (byte)(region + obj));
                edenObjects.add(object);
                totalAllocatedBytes.addAndGet(MEDIUM_OBJECT_SIZE);
            }
            
            // 检查是否触发了GC
            long currentGCCount = getTotalGCCount();
            if (currentGCCount > initialGCCount) {
                System.out.printf(" [GC触发: %d次]", currentGCCount - initialGCCount);
                initialGCCount = currentGCCount;
            }
            System.out.println(" ✅");
        }
        
        long endTime = System.nanoTime();
        long endUsed = memoryBean.getHeapMemoryUsage().getUsed();
        
        System.out.printf("   分配对象数: %d\n", edenObjects.size());
        System.out.printf("   分配内存: %d MB\n", (endUsed - startUsed) / (1024 * 1024));
        System.out.printf("   分配时间: %.2f ms\n", (endTime - startTime) / 1_000_000.0);
        System.out.printf("   分配速率: %.0f MB/s\n", 
                         ((endUsed - startUsed) / (1024.0 * 1024.0)) / 
                         ((endTime - startTime) / 1_000_000_000.0));
        
        // 保存一部分对象到老年代
        youngGenObjects.addAll(edenObjects.subList(0, edenObjects.size() / 4));
        
        // 触发一次GC来观察Region回收
        System.out.println("   手动触发GC观察Region回收...");
        System.gc();
        waitForGC();
        
        long afterGCUsed = memoryBean.getHeapMemoryUsage().getUsed();
        System.out.printf("   GC后内存使用: %d MB (回收了 %d MB)\n", 
                         afterGCUsed / (1024 * 1024),
                         (endUsed - afterGCUsed) / (1024 * 1024));
    }
    
    /**
     * 阶段3: TLAB和快速分配测试
     */
    private static void phase3_TLABAllocationTest() {
        System.out.println("\n=== 阶段3: TLAB和快速分配测试 ===");
        
        System.out.println("🚀 测试TLAB快速分配性能...");
        
        // 小对象快速分配测试
        int smallObjectCount = 1_000_000;
        long startTime = System.nanoTime();
        
        List<Object> tlabObjects = new ArrayList<>(smallObjectCount);
        for (int i = 0; i < smallObjectCount; i++) {
            // 分配小对象，应该走TLAB快速路径
            byte[] smallObj = new byte[SMALL_OBJECT_SIZE];
            smallObj[0] = (byte)i; // 避免优化
            tlabObjects.add(smallObj);
            totalAllocatedBytes.addAndGet(SMALL_OBJECT_SIZE);
        }
        
        long endTime = System.nanoTime();
        double elapsedMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("   小对象分配数量: %d\n", smallObjectCount);
        System.out.printf("   平均对象大小: %d bytes\n", SMALL_OBJECT_SIZE);
        System.out.printf("   总分配时间: %.2f ms\n", elapsedMs);
        System.out.printf("   平均分配时间: %.2f ns/对象\n", (endTime - startTime) / (double)smallObjectCount);
        System.out.printf("   分配速率: %.0f 对象/秒\n", smallObjectCount / (elapsedMs / 1000.0));
        
        // 测试TLAB溢出情况
        System.out.println("\n🔄 测试TLAB溢出和重新分配...");
        
        // 分配一些中等大小对象，可能导致TLAB溢出
        List<Object> mediumObjects = new ArrayList<>();
        int mediumObjectCount = 10_000;
        
        startTime = System.nanoTime();
        for (int i = 0; i < mediumObjectCount; i++) {
            byte[] mediumObj = new byte[MEDIUM_OBJECT_SIZE];
            Arrays.fill(mediumObj, (byte)(i % 256));
            mediumObjects.add(mediumObj);
            totalAllocatedBytes.addAndGet(MEDIUM_OBJECT_SIZE);
        }
        endTime = System.nanoTime();
        
        System.out.printf("   中等对象分配数量: %d\n", mediumObjectCount);
        System.out.printf("   平均对象大小: %d bytes\n", MEDIUM_OBJECT_SIZE);
        System.out.printf("   分配时间: %.2f ms\n", (endTime - startTime) / 1_000_000.0);
        
        // 保存部分对象
        youngGenObjects.addAll(tlabObjects.subList(0, tlabObjects.size() / 10));
        youngGenObjects.addAll(mediumObjects.subList(0, mediumObjects.size() / 4));
    }
    
    /**
     * 阶段4: 并发标记触发测试
     */
    private static void phase4_ConcurrentMarkingTest() {
        System.out.println("\n=== 阶段4: 并发标记触发测试 ===");
        
        System.out.println("📊 测试并发标记触发条件...");
        
        // 获取当前堆使用情况
        MemoryUsage beforeUsage = memoryBean.getHeapMemoryUsage();
        long beforeUsed = beforeUsage.getUsed();
        long maxHeap = beforeUsage.getMax();
        double currentUsagePercent = (beforeUsed * 100.0) / maxHeap;
        
        System.out.printf("   当前堆使用率: %.1f%%\n", currentUsagePercent);
        System.out.printf("   G1默认标记触发阈值: 45%%\n");
        
        // 如果使用率还不够高，继续分配对象
        if (currentUsagePercent < 40.0) {
            System.out.println("   堆使用率较低，分配更多对象以触发并发标记...");
            
            List<Object> triggerObjects = new ArrayList<>();
            long targetBytes = (long)(maxHeap * 0.5 - beforeUsed); // 目标达到50%使用率
            int objectSize = LARGE_OBJECT_SIZE;
            int objectCount = (int)(targetBytes / objectSize);
            
            System.out.printf("   目标分配: %d MB (%d个对象)\n", 
                             targetBytes / (1024 * 1024), objectCount);
            
            long gcCountBefore = getTotalGCCount();
            
            for (int i = 0; i < objectCount && i < 1000; i++) { // 限制最大1000个对象
                byte[] largeObj = new byte[objectSize];
                // 填充数据模拟真实对象
                for (int j = 0; j < largeObj.length; j += 1024) {
                    largeObj[j] = (byte)(i % 256);
                }
                triggerObjects.add(largeObj);
                totalAllocatedBytes.addAndGet(objectSize);
                
                // 每100个对象检查一次GC状态
                if (i % 100 == 0) {
                    long currentGCCount = getTotalGCCount();
                    if (currentGCCount > gcCountBefore) {
                        System.out.printf("   第%d个对象后触发GC (总计%d次)\n", 
                                         i + 1, currentGCCount - gcCountBefore);
                        gcCountBefore = currentGCCount;
                    }
                }
            }
            
            // 保存一些对象到老年代
            oldGenObjects.addAll(triggerObjects.subList(0, Math.min(triggerObjects.size() / 2, 200)));
        }
        
        // 检查并发标记是否被触发
        MemoryUsage afterUsage = memoryBean.getHeapMemoryUsage();
        double afterUsagePercent = (afterUsage.getUsed() * 100.0) / maxHeap;
        
        System.out.printf("   分配后堆使用率: %.1f%%\n", afterUsagePercent);
        
        if (afterUsagePercent > 45.0) {
            System.out.println("   ✅ 堆使用率超过阈值，应该触发并发标记");
        } else {
            System.out.println("   ℹ️  堆使用率未达到标记阈值");
        }
        
        // 等待一段时间让并发标记进行
        System.out.println("   等待并发标记进行...");
        try {
            Thread.sleep(1000); // 等待1秒
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 阶段5: 混合GC测试
     */
    private static void phase5_MixedGCTest() {
        System.out.println("\n=== 阶段5: 混合GC测试 ===");
        
        System.out.println("🔄 准备混合GC测试环境...");
        
        // 确保有足够的老年代对象
        if (oldGenObjects.size() < 100) {
            System.out.println("   创建老年代对象...");
            for (int i = 0; i < 500; i++) {
                byte[] oldObj = new byte[LARGE_OBJECT_SIZE];
                Arrays.fill(oldObj, (byte)(i % 256));
                oldGenObjects.add(oldObj);
                totalAllocatedBytes.addAndGet(LARGE_OBJECT_SIZE);
            }
        }
        
        System.out.printf("   老年代对象数量: %d\n", oldGenObjects.size());
        
        // 创建一些跨代引用
        System.out.println("   创建跨代引用模式...");
        List<ObjectWithReferences> crossGenRefs = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            ObjectWithReferences refObj = new ObjectWithReferences();
            
            // 引用一些老年代对象
            if (!oldGenObjects.isEmpty()) {
                refObj.oldRef = oldGenObjects.get(i % oldGenObjects.size());
            }
            
            // 引用一些新生代对象
            if (!youngGenObjects.isEmpty()) {
                refObj.youngRef = youngGenObjects.get(i % youngGenObjects.size());
            }
            
            crossGenRefs.add(refObj);
        }
        
        youngGenObjects.addAll(crossGenRefs);
        
        // 释放一些老年代对象，创建垃圾
        System.out.println("   创建老年代垃圾对象...");
        int objectsToRemove = oldGenObjects.size() / 3;
        for (int i = 0; i < objectsToRemove; i++) {
            oldGenObjects.remove(oldGenObjects.size() - 1);
        }
        
        System.out.printf("   移除了%d个老年代对象，剩余%d个\n", 
                         objectsToRemove, oldGenObjects.size());
        
        // 触发GC观察混合回收
        long gcCountBefore = getTotalGCCount();
        MemoryUsage beforeGC = memoryBean.getHeapMemoryUsage();
        
        System.out.println("   触发GC观察混合回收行为...");
        System.gc();
        waitForGC();
        
        long gcCountAfter = getTotalGCCount();
        MemoryUsage afterGC = memoryBean.getHeapMemoryUsage();
        
        System.out.printf("   GC次数增加: %d\n", gcCountAfter - gcCountBefore);
        System.out.printf("   内存回收: %d MB\n", 
                         (beforeGC.getUsed() - afterGC.getUsed()) / (1024 * 1024));
        System.out.printf("   回收效率: %.1f%%\n", 
                         ((beforeGC.getUsed() - afterGC.getUsed()) * 100.0) / beforeGC.getUsed());
    }
    
    /**
     * 阶段6: 巨型对象处理测试
     */
    private static void phase6_HumongousObjectTest() {
        System.out.println("\n=== 阶段6: 巨型对象处理测试 ===");
        
        System.out.println("🐘 测试巨型对象分配和回收...");
        
        // G1中超过Region大小50%的对象被认为是巨型对象
        int humongousThreshold = (REGION_SIZE_MB * 1024 * 1024) / 2;
        System.out.printf("   巨型对象阈值: %d MB\n", humongousThreshold / (1024 * 1024));
        System.out.printf("   测试对象大小: %d MB\n", HUMONGOUS_OBJECT_SIZE / (1024 * 1024));
        
        long gcCountBefore = getTotalGCCount();
        MemoryUsage beforeAlloc = memoryBean.getHeapMemoryUsage();
        
        // 分配巨型对象
        List<byte[]> humongousObjs = new ArrayList<>();
        int humongousCount = 20; // 分配20个巨型对象
        
        System.out.printf("   分配%d个巨型对象...\n", humongousCount);
        
        for (int i = 0; i < humongousCount; i++) {
            try {
                byte[] humongousObj = new byte[HUMONGOUS_OBJECT_SIZE];
                
                // 填充数据避免优化
                for (int j = 0; j < humongousObj.length; j += 4096) {
                    humongousObj[j] = (byte)(i % 256);
                }
                
                humongousObjs.add(humongousObj);
                totalAllocatedBytes.addAndGet(HUMONGOUS_OBJECT_SIZE);
                
                System.out.printf("     巨型对象 %d: %d MB ✅\n", 
                                 i + 1, HUMONGOUS_OBJECT_SIZE / (1024 * 1024));
                
                // 检查是否触发GC
                long currentGCCount = getTotalGCCount();
                if (currentGCCount > gcCountBefore) {
                    System.out.printf("     [触发GC: %d次]\n", currentGCCount - gcCountBefore);
                    gcCountBefore = currentGCCount;
                }
                
            } catch (OutOfMemoryError e) {
                System.out.printf("     ❌ 第%d个巨型对象分配失败: %s\n", i + 1, e.getMessage());
                break;
            }
        }
        
        MemoryUsage afterAlloc = memoryBean.getHeapMemoryUsage();
        long allocatedMB = (afterAlloc.getUsed() - beforeAlloc.getUsed()) / (1024 * 1024);
        
        System.out.printf("   成功分配巨型对象: %d个\n", humongousObjs.size());
        System.out.printf("   实际分配内存: %d MB\n", allocatedMB);
        System.out.printf("   预期分配内存: %d MB\n", 
                         (humongousObjs.size() * HUMONGOUS_OBJECT_SIZE) / (1024 * 1024));
        
        // 保存一些巨型对象
        humongousObjects.addAll(humongousObjs.subList(0, Math.min(humongousObjs.size() / 2, 5)));
        
        // 释放其他巨型对象并观察回收
        System.out.println("   释放部分巨型对象并观察回收...");
        int objectsToKeep = humongousObjs.size() / 3;
        humongousObjs.subList(objectsToKeep, humongousObjs.size()).clear();
        
        // 强制GC观察巨型对象回收
        System.gc();
        waitForGC();
        
        MemoryUsage afterGC = memoryBean.getHeapMemoryUsage();
        long reclaimedMB = (afterAlloc.getUsed() - afterGC.getUsed()) / (1024 * 1024);
        
        System.out.printf("   GC回收内存: %d MB\n", reclaimedMB);
        System.out.printf("   巨型对象回收效率: %.1f%%\n", 
                         (reclaimedMB * 100.0) / allocatedMB);
    }
    
    /**
     * 阶段7: 记忆集和跨代引用测试
     */
    private static void phase7_RememberedSetTest() {
        System.out.println("\n=== 阶段7: 记忆集和跨代引用测试 ===");
        
        System.out.println("🔗 测试记忆集维护和跨代引用处理...");
        
        // 创建复杂的跨代引用结构
        List<ComplexReferenceObject> complexRefs = new ArrayList<>();
        
        System.out.println("   创建复杂跨代引用结构...");
        
        for (int i = 0; i < 200; i++) {
            ComplexReferenceObject complexObj = new ComplexReferenceObject();
            
            // 创建多层引用链
            complexObj.data = new byte[MEDIUM_OBJECT_SIZE];
            Arrays.fill(complexObj.data, (byte)(i % 256));
            
            // 引用老年代对象
            if (!oldGenObjects.isEmpty()) {
                complexObj.oldGenRef = oldGenObjects.get(i % oldGenObjects.size());
            }
            
            // 引用新生代对象
            if (!youngGenObjects.isEmpty()) {
                complexObj.youngGenRef = youngGenObjects.get(i % youngGenObjects.size());
            }
            
            // 引用巨型对象
            if (!humongousObjects.isEmpty()) {
                complexObj.humongousRef = humongousObjects.get(i % humongousObjects.size());
            }
            
            // 创建引用数组
            complexObj.refArray = new Object[10];
            for (int j = 0; j < complexObj.refArray.length; j++) {
                if (j % 3 == 0 && !oldGenObjects.isEmpty()) {
                    complexObj.refArray[j] = oldGenObjects.get((i + j) % oldGenObjects.size());
                } else if (j % 3 == 1 && !youngGenObjects.isEmpty()) {
                    complexObj.refArray[j] = youngGenObjects.get((i + j) % youngGenObjects.size());
                } else {
                    complexObj.refArray[j] = new byte[SMALL_OBJECT_SIZE];
                }
            }
            
            complexRefs.add(complexObj);
            totalAllocatedBytes.addAndGet(MEDIUM_OBJECT_SIZE + 10 * SMALL_OBJECT_SIZE);
        }
        
        System.out.printf("   创建复杂引用对象: %d个\n", complexRefs.size());
        
        // 修改引用关系，触发记忆集更新
        System.out.println("   修改引用关系触发记忆集更新...");
        
        Random random = new Random(42); // 固定种子保证可重现
        int modifications = 0;
        
        for (int i = 0; i < 100; i++) {
            ComplexReferenceObject obj = complexRefs.get(random.nextInt(complexRefs.size()));
            
            // 随机修改引用
            if (random.nextBoolean() && !oldGenObjects.isEmpty()) {
                obj.oldGenRef = oldGenObjects.get(random.nextInt(oldGenObjects.size()));
                modifications++;
            }
            
            if (random.nextBoolean() && !youngGenObjects.isEmpty()) {
                obj.youngGenRef = youngGenObjects.get(random.nextInt(youngGenObjects.size()));
                modifications++;
            }
            
            // 修改数组引用
            if (obj.refArray != null && obj.refArray.length > 0) {
                int index = random.nextInt(obj.refArray.length);
                if (!oldGenObjects.isEmpty()) {
                    obj.refArray[index] = oldGenObjects.get(random.nextInt(oldGenObjects.size()));
                    modifications++;
                }
            }
        }
        
        System.out.printf("   执行引用修改: %d次\n", modifications);
        
        // 保存复杂引用对象
        youngGenObjects.addAll(complexRefs.subList(0, complexRefs.size() / 4));
        oldGenObjects.addAll(complexRefs.subList(complexRefs.size() / 4, complexRefs.size() / 2));
        
        // 触发GC观察记忆集处理
        System.out.println("   触发GC观察记忆集处理性能...");
        
        long gcTimeBefore = getTotalGCTime();
        long gcCountBefore = getTotalGCCount();
        
        System.gc();
        waitForGC();
        
        long gcTimeAfter = getTotalGCTime();
        long gcCountAfter = getTotalGCCount();
        
        if (gcCountAfter > gcCountBefore) {
            long avgGCTime = (gcTimeAfter - gcTimeBefore) / (gcCountAfter - gcCountBefore);
            System.out.printf("   GC平均时间: %d ms\n", avgGCTime);
            System.out.printf("   记忆集处理开销已包含在GC时间中\n");
        }
    }
    
    /**
     * 阶段8: 停顿时间控制测试
     */
    private static void phase8_PauseTimeControlTest() {
        System.out.println("\n=== 阶段8: 停顿时间控制测试 ===");
        
        System.out.println("⏱️  测试G1停顿时间控制机制...");
        
        // 记录GC时间统计
        List<Long> gcTimes = new ArrayList<>();
        long initialGCCount = getTotalGCCount();
        long initialGCTime = getTotalGCTime();
        
        // 创建持续的内存压力来触发多次GC
        System.out.println("   创建内存压力触发多次GC...");
        
        List<Object> pressureObjects = new ArrayList<>();
        
        for (int round = 0; round < 10; round++) {
            System.out.printf("   压力轮次 %d...", round + 1);
            
            long roundStartGCCount = getTotalGCCount();
            long roundStartGCTime = getTotalGCTime();
            
            // 快速分配对象创建内存压力
            List<byte[]> roundObjects = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                byte[] obj = new byte[LARGE_OBJECT_SIZE];
                obj[0] = (byte)(round + i);
                roundObjects.add(obj);
                totalAllocatedBytes.addAndGet(LARGE_OBJECT_SIZE);
            }
            
            // 保留一部分对象
            pressureObjects.addAll(roundObjects.subList(0, roundObjects.size() / 4));
            
            long roundEndGCCount = getTotalGCCount();
            long roundEndGCTime = getTotalGCTime();
            
            if (roundEndGCCount > roundStartGCCount) {
                long roundGCTime = roundEndGCTime - roundStartGCTime;
                long roundGCCount = roundEndGCCount - roundStartGCCount;
                long avgGCTime = roundGCTime / roundGCCount;
                
                gcTimes.add(avgGCTime);
                System.out.printf(" [GC: %d次, 平均: %d ms] ✅\n", roundGCCount, avgGCTime);
            } else {
                System.out.println(" [无GC] ✅");
            }
        }
        
        // 分析停顿时间统计
        if (!gcTimes.isEmpty()) {
            System.out.println("\n📊 停顿时间统计分析:");
            
            long minTime = Collections.min(gcTimes);
            long maxTime = Collections.max(gcTimes);
            double avgTime = gcTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
            
            System.out.printf("   GC次数: %d\n", gcTimes.size());
            System.out.printf("   最短停顿: %d ms\n", minTime);
            System.out.printf("   最长停顿: %d ms\n", maxTime);
            System.out.printf("   平均停顿: %.1f ms\n", avgTime);
            System.out.printf("   目标停顿: 200 ms (MaxGCPauseMillis)\n");
            
            // 检查是否符合停顿时间目标
            long exceedCount = gcTimes.stream().mapToLong(Long::longValue)
                                     .filter(time -> time > 200).count();
            
            if (exceedCount == 0) {
                System.out.println("   ✅ 所有GC停顿都在目标时间内");
            } else {
                System.out.printf("   ⚠️  %d次GC超过目标停顿时间\n", exceedCount);
            }
            
            // 计算停顿时间分布
            long under50 = gcTimes.stream().mapToLong(Long::longValue).filter(t -> t < 50).count();
            long under100 = gcTimes.stream().mapToLong(Long::longValue).filter(t -> t < 100).count();
            long under200 = gcTimes.stream().mapToLong(Long::longValue).filter(t -> t < 200).count();
            
            System.out.println("   停顿时间分布:");
            System.out.printf("     < 50ms: %d次 (%.1f%%)\n", under50, (under50 * 100.0) / gcTimes.size());
            System.out.printf("     < 100ms: %d次 (%.1f%%)\n", under100, (under100 * 100.0) / gcTimes.size());
            System.out.printf("     < 200ms: %d次 (%.1f%%)\n", under200, (under200 * 100.0) / gcTimes.size());
        }
        
        // 保存压力测试对象
        oldGenObjects.addAll(pressureObjects.subList(0, Math.min(pressureObjects.size() / 2, 100)));
    }
    
    /**
     * 阶段9: 性能基准测试
     */
    private static void phase9_PerformanceBenchmark() {
        System.out.println("\n=== 阶段9: 性能基准测试 ===");
        
        System.out.println("🏃 执行G1性能基准测试...");
        
        // 测试1: 分配性能基准
        System.out.println("\n📈 分配性能基准测试:");
        
        int[] objectSizes = {64, 256, 1024, 4096, 16384}; // 不同大小对象
        String[] sizeNames = {"64B", "256B", "1KB", "4KB", "16KB"};
        
        for (int i = 0; i < objectSizes.length; i++) {
            int size = objectSizes[i];
            String sizeName = sizeNames[i];
            int count = 100_000 / (size / 64); // 根据大小调整数量
            
            System.out.printf("   %s对象分配测试 (%d个)...", sizeName, count);
            
            long startTime = System.nanoTime();
            List<byte[]> benchObjects = new ArrayList<>(count);
            
            for (int j = 0; j < count; j++) {
                byte[] obj = new byte[size];
                obj[0] = (byte)j; // 避免优化
                benchObjects.add(obj);
            }
            
            long endTime = System.nanoTime();
            double elapsedMs = (endTime - startTime) / 1_000_000.0;
            double throughput = count / (elapsedMs / 1000.0);
            
            System.out.printf(" %.2f ms, %.0f 对象/秒\n", elapsedMs, throughput);
            
            totalAllocatedBytes.addAndGet((long)count * size);
        }
        
        // 测试2: 并发分配性能
        System.out.println("\n🔀 并发分配性能测试:");
        
        int threadCount = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong concurrentAllocations = new AtomicLong(0);
        
        System.out.printf("   使用%d个线程并发分配...", threadCount);
        
        long concurrentStartTime = System.nanoTime();
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    List<Object> threadObjects = new ArrayList<>();
                    for (int i = 0; i < 10_000; i++) {
                        byte[] obj = new byte[MEDIUM_OBJECT_SIZE];
                        obj[0] = (byte)(threadId + i);
                        threadObjects.add(obj);
                        concurrentAllocations.incrementAndGet();
                        totalAllocatedBytes.addAndGet(MEDIUM_OBJECT_SIZE);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long concurrentEndTime = System.nanoTime();
        double concurrentElapsedMs = (concurrentEndTime - concurrentStartTime) / 1_000_000.0;
        double concurrentThroughput = concurrentAllocations.get() / (concurrentElapsedMs / 1000.0);
        
        System.out.printf(" %.2f ms, %.0f 对象/秒\n", concurrentElapsedMs, concurrentThroughput);
        
        executor.shutdown();
        
        // 测试3: GC吞吐量测试
        System.out.println("\n🗑️  GC吞吐量测试:");
        
        long totalGCTimeBefore = getTotalGCTime();
        long benchmarkStartTime = System.currentTimeMillis();
        
        // 运行一段时间的分配和GC
        long testDurationMs = 5000; // 5秒测试
        long testEndTime = benchmarkStartTime + testDurationMs;
        
        List<Object> throughputObjects = new ArrayList<>();
        int allocationRounds = 0;
        
        System.out.printf("   运行%d秒吞吐量测试...", testDurationMs / 1000);
        
        while (System.currentTimeMillis() < testEndTime) {
            // 分配一批对象
            for (int i = 0; i < 1000; i++) {
                byte[] obj = new byte[MEDIUM_OBJECT_SIZE];
                obj[0] = (byte)(allocationRounds + i);
                throughputObjects.add(obj);
                totalAllocatedBytes.addAndGet(MEDIUM_OBJECT_SIZE);
            }
            
            // 定期清理对象
            if (throughputObjects.size() > 50_000) {
                throughputObjects.subList(0, throughputObjects.size() / 2).clear();
            }
            
            allocationRounds++;
        }
        
        long benchmarkEndTime = System.currentTimeMillis();
        long totalGCTimeAfter = getTotalGCTime();
        
        long actualTestTime = benchmarkEndTime - benchmarkStartTime;
        long gcTimeInTest = totalGCTimeAfter - totalGCTimeBefore;
        double gcOverhead = (gcTimeInTest * 100.0) / actualTestTime;
        double throughputPercent = ((actualTestTime - gcTimeInTest) * 100.0) / actualTestTime;
        
        System.out.printf(" 完成\n");
        System.out.printf("   测试时间: %d ms\n", actualTestTime);
        System.out.printf("   GC时间: %d ms\n", gcTimeInTest);
        System.out.printf("   GC开销: %.2f%%\n", gcOverhead);
        System.out.printf("   应用吞吐量: %.2f%%\n", throughputPercent);
        System.out.printf("   分配轮次: %d\n", allocationRounds);
    }
    
    /**
     * 阶段10: 最终状态分析
     */
    private static void phase10_FinalStateAnalysis() {
        System.out.println("\n=== 阶段10: 最终状态分析 ===");
        
        System.out.println("📊 G1收集器最终状态分析:");
        
        // 内存使用总结
        MemoryUsage finalHeapUsage = memoryBean.getHeapMemoryUsage();
        
        System.out.println("\n💾 最终内存状态:");
        System.out.printf("   最大堆内存: %d MB\n", finalHeapUsage.getMax() / (1024 * 1024));
        System.out.printf("   已提交内存: %d MB\n", finalHeapUsage.getCommitted() / (1024 * 1024));
        System.out.printf("   已使用内存: %d MB (%.1f%%)\n", 
                         finalHeapUsage.getUsed() / (1024 * 1024),
                         (finalHeapUsage.getUsed() * 100.0) / finalHeapUsage.getCommitted());
        System.out.printf("   空闲内存: %d MB\n", 
                         (finalHeapUsage.getCommitted() - finalHeapUsage.getUsed()) / (1024 * 1024));
        
        // 对象统计
        System.out.println("\n📦 对象分布统计:");
        System.out.printf("   新生代对象: %d个\n", youngGenObjects.size());
        System.out.printf("   老年代对象: %d个\n", oldGenObjects.size());
        System.out.printf("   巨型对象: %d个\n", humongousObjects.size());
        System.out.printf("   总分配字节: %d MB\n", totalAllocatedBytes.get() / (1024 * 1024));
        
        // GC统计总结
        System.out.println("\n🗑️  GC统计总结:");
        long totalGCCount = 0;
        long totalGCTime = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcCount = gcBean.getCollectionCount();
            long gcTime = gcBean.getCollectionTime();
            
            System.out.printf("   %s:\n", gcBean.getName());
            System.out.printf("     收集次数: %d\n", gcCount);
            System.out.printf("     收集时间: %d ms\n", gcTime);
            
            if (gcCount > 0) {
                System.out.printf("     平均时间: %.1f ms\n", (double)gcTime / gcCount);
            }
            
            totalGCCount += gcCount;
            totalGCTime += gcTime;
        }
        
        System.out.printf("   总GC次数: %d\n", totalGCCount);
        System.out.printf("   总GC时间: %d ms\n", totalGCTime);
        
        if (totalGCCount > 0) {
            System.out.printf("   平均GC时间: %.1f ms\n", (double)totalGCTime / totalGCCount);
        }
        
        // 性能评估
        System.out.println("\n⚡ 性能评估:");
        
        long runtimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        double gcOverheadPercent = (totalGCTime * 100.0) / runtimeMs;
        
        System.out.printf("   运行时间: %d ms\n", runtimeMs);
        System.out.printf("   GC开销: %.2f%%\n", gcOverheadPercent);
        System.out.printf("   应用时间: %.2f%%\n", 100.0 - gcOverheadPercent);
        
        // 性能评级
        if (gcOverheadPercent < 2.0) {
            System.out.println("   性能评级: ⭐⭐⭐⭐⭐ 优秀");
        } else if (gcOverheadPercent < 5.0) {
            System.out.println("   性能评级: ⭐⭐⭐⭐ 良好");
        } else if (gcOverheadPercent < 10.0) {
            System.out.println("   性能评级: ⭐⭐⭐ 一般");
        } else {
            System.out.println("   性能评级: ⭐⭐ 需要优化");
        }
        
        // 建议
        System.out.println("\n💡 优化建议:");
        
        if (gcOverheadPercent > 5.0) {
            System.out.println("   - 考虑增加堆内存大小");
            System.out.println("   - 调整G1NewSizePercent和G1MaxNewSizePercent");
            System.out.println("   - 优化对象生命周期管理");
        }
        
        if (totalGCCount > 0 && (totalGCTime / totalGCCount) > 200) {
            System.out.println("   - 当前GC停顿时间较长，考虑调整MaxGCPauseMillis");
            System.out.println("   - 检查是否有大量跨代引用");
        }
        
        double heapUtilization = (finalHeapUsage.getUsed() * 100.0) / finalHeapUsage.getMax();
        if (heapUtilization > 80.0) {
            System.out.println("   - 堆内存使用率较高，建议增加堆大小");
        } else if (heapUtilization < 30.0) {
            System.out.println("   - 堆内存使用率较低，可以考虑减少堆大小");
        }
        
        System.out.println("\n✅ G1垃圾收集器深度分析测试全部完成！");
    }
    
    // ============================================================================
    // 辅助方法和工具类
    // ============================================================================
    
    /**
     * 初始化GC监控
     */
    private static void initializeMonitoring() {
        gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        memoryBean = ManagementFactory.getMemoryMXBean();
        
        recordGCBaseline();
    }
    
    /**
     * 记录GC基线
     */
    private static void recordGCBaseline() {
        initialGCCount = getTotalGCCount();
        initialGCTime = getTotalGCTime();
    }
    
    /**
     * 获取总GC次数
     */
    private static long getTotalGCCount() {
        return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }
    
    /**
     * 获取总GC时间
     */
    private static long getTotalGCTime() {
        return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }
    
    /**
     * 等待GC完成
     */
    private static void waitForGC() {
        try {
            Thread.sleep(100); // 等待100ms让GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 带引用的对象类
     */
    static class ObjectWithReferences {
        Object oldRef;
        Object youngRef;
        byte[] data = new byte[128];
    }
    
    /**
     * 复杂引用对象类
     */
    static class ComplexReferenceObject {
        byte[] data;
        Object oldGenRef;
        Object youngGenRef;
        Object humongousRef;
        Object[] refArray;
        
        // 弱引用测试
        WeakReference<Object> weakRef;
        SoftReference<Object> softRef;
    }
}