import java.util.*;
import java.util.concurrent.*;
import java.lang.ref.*;
import java.lang.management.*;

/**
 * G1GC混合回收性能问题复现程序
 * 基于真实场景模拟G1GC Mixed GC性能问题
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 
 *          -XX:G1HeapRegionSize=32m -XX:+PrintGC -XX:+PrintGCDetails
 *          -XX:+PrintGCTimeStamps -Xloggc:g1gc-stress.log
 */
public class G1GCStressTest {
    
    // 模拟不同生命周期的对象
    private static final Map<String, Object> longLivedCache = new ConcurrentHashMap<>();
    private static final List<MediumLifeObject> mediumObjects = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean running = true;
    private static long objectCounter = 0;
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== G1GC性能问题复现测试 ===");
        printJVMInfo();
        
        // 启动监控线程
        startGCMonitor();
        
        // 创建长生命周期对象 - 占用大部分老年代
        createLongLivedObjects();
        
        // 启动中等生命周期对象生成器 - 触发Mixed GC
        startMediumLifeObjectGenerator();
        
        // 启动大对象生成器 - 增加GC压力
        startLargeObjectGenerator();
        
        // 启动引用密集型任务 - 增加引用处理开销
        startReferenceIntensiveTask();
        
        // 启动内存碎片化任务
        startFragmentationTask();
        
        // 运行10分钟观察GC行为
        System.out.println("开始运行测试，观察G1GC行为...");
        Thread.sleep(600000);
        running = false;
        
        System.out.println("测试结束，最终统计:");
        printFinalStats();
    }
    
    /**
     * 创建长生命周期对象，占满大部分老年代空间
     * 这些对象会存活很久，导致老年代区域增多，Mixed GC扫描开销增大
     */
    private static void createLongLivedObjects() {
        System.out.println("创建长生命周期对象...");
        
        // 创建约5GB的长生命周期数据
        for (int i = 0; i < 50000; i++) {
            String key = "long_lived_" + i;
            // 每个对象约100KB，模拟缓存数据
            LongLivedObject obj = new LongLivedObject(key, 100 * 1024);
            longLivedCache.put(key, obj);
            
            if (i % 5000 == 0) {
                System.out.println("已创建 " + i + " 个长生命周期对象");
                // 让年轻代有机会晋升到老年代
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        }
        
        System.out.println("长生命周期对象创建完成，总计: " + longLivedCache.size());
        System.out.println("预计占用内存: " + (longLivedCache.size() * 100 / 1024) + "MB");
    }
    
    /**
     * 启动中等生命周期对象生成器
     * 这些对象会在年轻代和老年代之间流转，触发Mixed GC
     */
    private static void startMediumLifeObjectGenerator() {
        Thread generator = new Thread(() -> {
            Random random = new Random();
            
            while (running) {
                try {
                    // 批量创建中等生命周期对象
                    for (int i = 0; i < 50; i++) {
                        MediumLifeObject obj = new MediumLifeObject("medium_" + (++objectCounter));
                        mediumObjects.add(obj);
                    }
                    
                    // 随机清理一些对象，模拟业务逻辑
                    if (mediumObjects.size() > 20000) {
                        int removeCount = 5000 + random.nextInt(5000);
                        synchronized (mediumObjects) {
                            for (int i = 0; i < removeCount && !mediumObjects.isEmpty(); i++) {
                                mediumObjects.remove(0);
                            }
                        }
                        System.out.println("清理了 " + removeCount + " 个中期对象，剩余: " + mediumObjects.size());
                    }
                    
                    Thread.sleep(10 + random.nextInt(20));
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "MediumLifeGenerator");
        
        generator.start();
    }
    
    /**
     * 启动大对象生成器 - 创建Humongous对象
     * 大对象直接分配到老年代，增加Mixed GC的压力
     */
    private static void startLargeObjectGenerator() {
        Thread generator = new Thread(() -> {
            Random random = new Random();
            List<byte[]> largeObjects = new ArrayList<>();
            
            while (running) {
                try {
                    // 创建大对象 (>16MB，触发Humongous分配)
                    int size = 16 * 1024 * 1024 + random.nextInt(8 * 1024 * 1024); // 16-24MB
                    byte[] largeObj = new byte[size];
                    
                    // 填充一些数据，避免被优化
                    for (int i = 0; i < size; i += 4096) {
                        largeObj[i] = (byte) random.nextInt(256);
                    }
                    
                    largeObjects.add(largeObj);
                    System.out.println("创建大对象: " + (size / 1024 / 1024) + "MB");
                    
                    // 保持一定数量的大对象，模拟实际场景
                    if (largeObjects.size() > 8) {
                        largeObjects.remove(0);
                        System.out.println("释放最老的大对象");
                    }
                    
                    Thread.sleep(2000 + random.nextInt(3000));
                    
                } catch (InterruptedException e) {
                    break;
                } catch (OutOfMemoryError e) {
                    System.out.println("大对象分配失败，清理现有对象");
                    largeObjects.clear();
                    System.gc(); // 建议GC
                }
            }
        }, "LargeObjectGenerator");
        
        generator.start();
    }
    
    /**
     * 启动引用密集型任务 - 增加引用处理开销
     * 大量的弱引用和软引用会增加GC的引用处理阶段耗时
     */
    private static void startReferenceIntensiveTask() {
        Thread task = new Thread(() -> {
            List<WeakReference<Object>> weakRefs = new ArrayList<>();
            List<SoftReference<Object>> softRefs = new ArrayList<>();
            ReferenceQueue<Object> refQueue = new ReferenceQueue<>();
            
            while (running) {
                try {
                    // 创建大量弱引用和软引用
                    for (int i = 0; i < 500; i++) {
                        ReferenceTarget obj = new ReferenceTarget("ref_" + (++objectCounter));
                        weakRefs.add(new WeakReference<>(obj, refQueue));
                        softRefs.add(new SoftReference<>(obj, refQueue));
                    }
                    
                    // 清理已回收的引用
                    Reference<?> ref;
                    int cleanedCount = 0;
                    while ((ref = refQueue.poll()) != null) {
                        cleanedCount++;
                    }
                    
                    if (cleanedCount > 0) {
                        System.out.println("清理了 " + cleanedCount + " 个已回收的引用");
                    }
                    
                    // 主动清理null引用
                    weakRefs.removeIf(r -> r.get() == null);
                    softRefs.removeIf(r -> r.get() == null);
                    
                    // 控制引用数量，避免无限增长
                    if (weakRefs.size() > 50000) {
                        weakRefs.subList(0, 10000).clear();
                    }
                    if (softRefs.size() > 50000) {
                        softRefs.subList(0, 10000).clear();
                    }
                    
                    Thread.sleep(100);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "ReferenceTask");
        
        task.start();
    }
    
    /**
     * 启动内存碎片化任务
     * 创建不同大小的对象，增加内存碎片化程度
     */
    private static void startFragmentationTask() {
        Thread task = new Thread(() -> {
            Random random = new Random();
            List<Object> fragmentObjects = new ArrayList<>();
            
            while (running) {
                try {
                    // 创建不同大小的对象，增加碎片化
                    for (int i = 0; i < 100; i++) {
                        int size = 1024 + random.nextInt(100 * 1024); // 1KB-100KB
                        FragmentObject obj = new FragmentObject(size);
                        fragmentObjects.add(obj);
                    }
                    
                    // 随机释放一些对象，增加碎片化
                    if (fragmentObjects.size() > 5000) {
                        for (int i = 0; i < 1000; i++) {
                            int index = random.nextInt(fragmentObjects.size());
                            fragmentObjects.remove(index);
                        }
                    }
                    
                    Thread.sleep(50);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "FragmentationTask");
        
        task.start();
    }
    
    /**
     * GC监控线程 - 监控GC行为和内存使用
     */
    private static void startGCMonitor() {
        Thread monitor = new Thread(() -> {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            Map<String, Long> lastGCCounts = new HashMap<>();
            Map<String, Long> lastGCTimes = new HashMap<>();
            
            // 初始化
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                lastGCCounts.put(gcBean.getName(), gcBean.getCollectionCount());
                lastGCTimes.put(gcBean.getName(), gcBean.getCollectionTime());
            }
            
            while (running) {
                try {
                    Thread.sleep(10000); // 每10秒统计一次
                    
                    Runtime runtime = Runtime.getRuntime();
                    long totalMemory = runtime.totalMemory();
                    long freeMemory = runtime.freeMemory();
                    long usedMemory = totalMemory - freeMemory;
                    long maxMemory = runtime.maxMemory();
                    
                    System.out.println("\n=== 内存和GC统计 ===");
                    System.out.printf("内存使用: %dMB/%dMB/%dMB (已用/总计/最大) %.1f%%\n",
                        usedMemory / 1024 / 1024,
                        totalMemory / 1024 / 1024,
                        maxMemory / 1024 / 1024,
                        (double) usedMemory / maxMemory * 100);
                    
                    System.out.printf("对象统计: 长期=%d, 中期=%d, 总创建=%d\n",
                        longLivedCache.size(), mediumObjects.size(), objectCounter);
                    
                    // GC统计
                    for (GarbageCollectorMXBean gcBean : gcBeans) {
                        String name = gcBean.getName();
                        long currentCount = gcBean.getCollectionCount();
                        long currentTime = gcBean.getCollectionTime();
                        
                        long deltaCount = currentCount - lastGCCounts.get(name);
                        long deltaTime = currentTime - lastGCTimes.get(name);
                        
                        if (deltaCount > 0) {
                            double avgTime = (double) deltaTime / deltaCount;
                            System.out.printf("GC[%s]: 次数=+%d(总%d), 时间=+%dms(总%dms), 平均=%.1fms\n",
                                name, deltaCount, currentCount, deltaTime, currentTime, avgTime);
                            
                            // 性能预警
                            if (name.contains("Mixed") && avgTime > 200) {
                                System.out.println("⚠️ Mixed GC性能预警: 平均暂停时间超过200ms!");
                            }
                        }
                        
                        lastGCCounts.put(name, currentCount);
                        lastGCTimes.put(name, currentTime);
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "GCMonitor");
        
        monitor.setDaemon(true);
        monitor.start();
    }
    
    private static void printJVMInfo() {
        System.out.println("JVM信息:");
        System.out.println("  版本: " + System.getProperty("java.version"));
        System.out.println("  虚拟机: " + System.getProperty("java.vm.name"));
        System.out.println("  最大内存: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        System.out.println("  处理器: " + Runtime.getRuntime().availableProcessors() + "核");
        
        // 打印GC信息
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("  垃圾收集器:");
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            System.out.println("    " + gcBean.getName());
        }
    }
    
    private static void printFinalStats() {
        Runtime runtime = Runtime.getRuntime();
        System.out.println("\n=== 最终统计 ===");
        System.out.println("内存使用: " + (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + "MB");
        System.out.println("长期对象: " + longLivedCache.size());
        System.out.println("中期对象: " + mediumObjects.size());
        System.out.println("总创建对象: " + objectCounter);
        
        // 最终GC统计
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            if (count > 0) {
                System.out.printf("GC[%s]: 总次数=%d, 总时间=%dms, 平均=%.1fms\n",
                    gcBean.getName(), count, time, (double) time / count);
            }
        }
    }
}

/**
 * 长生命周期对象 - 模拟缓存数据
 */
class LongLivedObject {
    private final String id;
    private final byte[] data;
    private final long createTime;
    private final Map<String, String> metadata;
    
    public LongLivedObject(String id, int size) {
        this.id = id;
        this.data = new byte[size];
        this.createTime = System.currentTimeMillis();
        this.metadata = new HashMap<>();
        
        // 填充一些元数据，增加对象复杂度
        for (int i = 0; i < 10; i++) {
            metadata.put("key_" + i, "value_" + i + "_" + id);
        }
        
        // 填充随机数据，避免被JVM优化
        Random random = new Random();
        for (int i = 0; i < size; i += 1024) {
            data[i] = (byte) random.nextInt(256);
        }
    }
    
    public String getId() { return id; }
    public long getCreateTime() { return createTime; }
}

/**
 * 中等生命周期对象 - 模拟业务处理对象
 */
class MediumLifeObject {
    private final String id;
    private final byte[] buffer = new byte[1024 * 50]; // 50KB
    private final List<String> processing = new ArrayList<>();
    private final long timestamp = System.currentTimeMillis();
    
    public MediumLifeObject(String id) {
        this.id = id;
        Random random = new Random();
        
        // 填充随机数据
        for (int i = 0; i < buffer.length; i += 512) {
            buffer[i] = (byte) random.nextInt(256);
        }
        
        // 模拟处理数据
        for (int i = 0; i < 50; i++) {
            processing.add("item_" + i + "_" + id);
        }
    }
    
    public String getId() { return id; }
    public long getTimestamp() { return timestamp; }
}

/**
 * 引用目标对象
 */
class ReferenceTarget {
    private final String data;
    private final byte[] payload = new byte[2048]; // 2KB
    private final long createTime = System.currentTimeMillis();
    
    public ReferenceTarget(String data) {
        this.data = data;
        new Random().nextBytes(payload);
    }
    
    public String getData() { return data; }
    public long getCreateTime() { return createTime; }
}

/**
 * 碎片化对象 - 不同大小的对象增加内存碎片化
 */
class FragmentObject {
    private final byte[] data;
    private final int size;
    
    public FragmentObject(int size) {
        this.size = size;
        this.data = new byte[size];
        
        // 填充一些数据
        Random random = new Random();
        for (int i = 0; i < size; i += 256) {
            data[i] = (byte) random.nextInt(256);
        }
    }
    
    public int getSize() { return size; }
}