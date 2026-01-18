import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.management.*;

/**
 * 生产环境监控示例
 * 展示如何在应用内部集成监控和诊断功能
 * 适用于真实的生产环境约束
 */
public class ProductionMonitoringExample {
    
    // 监控指标收集器
    private static final ScheduledExecutorService monitorExecutor = 
        Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "production-monitor");
            t.setDaemon(true);
            return t;
        });
    
    // 性能指标存储
    private static final Map<String, AtomicLong> metrics = new ConcurrentHashMap<>();
    private static final Map<String, Double> thresholds = new HashMap<>();
    
    // 告警状态
    private static final Set<String> activeAlerts = ConcurrentHashMap.newKeySet();
    
    static {
        // 初始化告警阈值
        thresholds.put("heap.usage.percent", 85.0);
        thresholds.put("gc.time.percent", 10.0);
        thresholds.put("thread.count", 500.0);
        thresholds.put("response.time.avg", 1000.0);
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== 生产环境监控系统启动 ===");
        
        // 启动监控
        startProductionMonitoring();
        
        // 模拟应用运行
        simulateApplicationLoad();
        
        // 运行监控
        Thread.sleep(120000); // 运行2分钟
        
        System.out.println("监控系统停止");
        monitorExecutor.shutdown();
    }
    
    /**
     * 启动生产环境监控
     */
    private static void startProductionMonitoring() {
        System.out.println("启动JVM指标监控...");
        
        // JVM指标监控 (每30秒)
        monitorExecutor.scheduleAtFixedRate(() -> {
            try {
                collectJVMMetrics();
            } catch (Exception e) {
                System.err.println("JVM指标收集失败: " + e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);
        
        // 应用指标监控 (每10秒)
        monitorExecutor.scheduleAtFixedRate(() -> {
            try {
                collectApplicationMetrics();
            } catch (Exception e) {
                System.err.println("应用指标收集失败: " + e.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);
        
        // 告警检查 (每60秒)
        monitorExecutor.scheduleAtFixedRate(() -> {
            try {
                checkAlerts();
            } catch (Exception e) {
                System.err.println("告警检查失败: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
        
        System.out.println("监控系统已启动");
    }
    
    /**
     * 收集JVM指标
     */
    private static void collectJVMMetrics() {
        // 内存使用情况
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        double heapUsagePercent = (double) heapUsed / heapMax * 100;
        
        metrics.put("heap.used", new AtomicLong(heapUsed / 1024 / 1024)); // MB
        metrics.put("heap.max", new AtomicLong(heapMax / 1024 / 1024));   // MB
        metrics.put("heap.usage.percent", new AtomicLong((long) heapUsagePercent));
        
        metrics.put("nonheap.used", new AtomicLong(nonHeapUsage.getUsed() / 1024 / 1024)); // MB
        
        // GC统计
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGCTime = 0;
        long totalGCCount = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcTime = gcBean.getCollectionTime();
            long gcCount = gcBean.getCollectionCount();
            
            totalGCTime += gcTime;
            totalGCCount += gcCount;
            
            metrics.put("gc." + gcBean.getName() + ".time", new AtomicLong(gcTime));
            metrics.put("gc." + gcBean.getName() + ".count", new AtomicLong(gcCount));
        }
        
        metrics.put("gc.total.time", new AtomicLong(totalGCTime));
        metrics.put("gc.total.count", new AtomicLong(totalGCCount));
        
        // 线程统计
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        metrics.put("thread.count", new AtomicLong(threadBean.getThreadCount()));
        metrics.put("thread.peak", new AtomicLong(threadBean.getPeakThreadCount()));
        metrics.put("thread.daemon", new AtomicLong(threadBean.getDaemonThreadCount()));
        
        // 检查死锁
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        metrics.put("thread.deadlocked", new AtomicLong(deadlockedThreads != null ? deadlockedThreads.length : 0));
        
        // 类加载统计
        ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
        metrics.put("class.loaded", new AtomicLong(classBean.getLoadedClassCount()));
        metrics.put("class.total", new AtomicLong(classBean.getTotalLoadedClassCount()));
        metrics.put("class.unloaded", new AtomicLong(classBean.getUnloadedClassCount()));
        
        // 运行时信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        metrics.put("uptime", new AtomicLong(runtimeBean.getUptime() / 1000)); // 秒
        
        System.out.printf("[JVM监控] 堆内存: %dMB/%dMB (%.1f%%), 线程: %d, GC次数: %d%n",
            heapUsed / 1024 / 1024, heapMax / 1024 / 1024, heapUsagePercent,
            threadBean.getThreadCount(), totalGCCount);
    }
    
    /**
     * 收集应用指标
     */
    private static void collectApplicationMetrics() {
        // 模拟应用指标收集
        Runtime runtime = Runtime.getRuntime();
        
        // CPU使用率 (简化计算)
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        metrics.put("app.memory.used", new AtomicLong(usedMemory / 1024 / 1024));
        
        // 模拟响应时间统计
        long currentTime = System.currentTimeMillis();
        AtomicLong responseTimeSum = metrics.computeIfAbsent("response.time.sum", k -> new AtomicLong(0));
        AtomicLong responseTimeCount = metrics.computeIfAbsent("response.time.count", k -> new AtomicLong(0));
        
        // 模拟一些响应时间数据
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            long responseTime = 50 + random.nextInt(200); // 50-250ms
            responseTimeSum.addAndGet(responseTime);
            responseTimeCount.incrementAndGet();
        }
        
        long avgResponseTime = responseTimeCount.get() > 0 ? 
            responseTimeSum.get() / responseTimeCount.get() : 0;
        metrics.put("response.time.avg", new AtomicLong(avgResponseTime));
        
        // 模拟错误计数
        AtomicLong errorCount = metrics.computeIfAbsent("error.count", k -> new AtomicLong(0));
        if (random.nextInt(100) < 5) { // 5%概率产生错误
            errorCount.incrementAndGet();
        }
        
        System.out.printf("[应用监控] 内存使用: %dMB, 平均响应时间: %dms, 错误数: %d%n",
            usedMemory / 1024 / 1024, avgResponseTime, errorCount.get());
    }
    
    /**
     * 检查告警条件
     */
    private static void checkAlerts() {
        System.out.println("[告警检查] 开始检查告警条件...");
        
        // 检查堆内存使用率
        AtomicLong heapUsagePercent = metrics.get("heap.usage.percent");
        if (heapUsagePercent != null && heapUsagePercent.get() > thresholds.get("heap.usage.percent")) {
            String alertKey = "heap.usage.high";
            if (!activeAlerts.contains(alertKey)) {
                sendAlert(alertKey, String.format("堆内存使用率过高: %d%%", heapUsagePercent.get()));
                activeAlerts.add(alertKey);
            }
        } else {
            activeAlerts.remove("heap.usage.high");
        }
        
        // 检查线程数
        AtomicLong threadCount = metrics.get("thread.count");
        if (threadCount != null && threadCount.get() > thresholds.get("thread.count")) {
            String alertKey = "thread.count.high";
            if (!activeAlerts.contains(alertKey)) {
                sendAlert(alertKey, String.format("线程数过多: %d", threadCount.get()));
                activeAlerts.add(alertKey);
            }
        } else {
            activeAlerts.remove("thread.count.high");
        }
        
        // 检查死锁
        AtomicLong deadlockedThreads = metrics.get("thread.deadlocked");
        if (deadlockedThreads != null && deadlockedThreads.get() > 0) {
            String alertKey = "thread.deadlock";
            if (!activeAlerts.contains(alertKey)) {
                sendAlert(alertKey, String.format("检测到死锁线程: %d个", deadlockedThreads.get()));
                activeAlerts.add(alertKey);
            }
        } else {
            activeAlerts.remove("thread.deadlock");
        }
        
        // 检查响应时间
        AtomicLong avgResponseTime = metrics.get("response.time.avg");
        if (avgResponseTime != null && avgResponseTime.get() > thresholds.get("response.time.avg")) {
            String alertKey = "response.time.high";
            if (!activeAlerts.contains(alertKey)) {
                sendAlert(alertKey, String.format("平均响应时间过高: %dms", avgResponseTime.get()));
                activeAlerts.add(alertKey);
            }
        } else {
            activeAlerts.remove("response.time.high");
        }
        
        if (activeAlerts.isEmpty()) {
            System.out.println("[告警检查] 所有指标正常");
        } else {
            System.out.printf("[告警检查] 当前活跃告警: %d个%n", activeAlerts.size());
        }
    }
    
    /**
     * 发送告警
     */
    private static void sendAlert(String alertKey, String message) {
        System.err.printf("🚨 [告警] %s: %s%n", alertKey, message);
        
        // 在真实环境中，这里会：
        // 1. 发送到告警系统 (如PagerDuty, 钉钉, 企业微信)
        // 2. 记录到日志系统
        // 3. 更新监控面板状态
        // 4. 触发自动化响应流程
        
        // 模拟告警处理
        logAlert(alertKey, message);
    }
    
    /**
     * 记录告警日志
     */
    private static void logAlert(String alertKey, String message) {
        String timestamp = new Date().toString();
        String logEntry = String.format("[%s] ALERT %s: %s%n", timestamp, alertKey, message);
        
        // 在真实环境中，这里会写入到专门的告警日志文件
        System.err.print(logEntry);
    }
    
    /**
     * 模拟应用负载
     */
    private static void simulateApplicationLoad() {
        System.out.println("启动应用负载模拟...");
        
        // 模拟一些内存分配
        ExecutorService loadExecutor = Executors.newFixedThreadPool(4);
        
        for (int i = 0; i < 4; i++) {
            loadExecutor.submit(() -> {
                List<byte[]> memoryLoad = new ArrayList<>();
                Random random = new Random();
                
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // 模拟内存分配
                        if (random.nextInt(10) < 7) {
                            memoryLoad.add(new byte[1024 * 100]); // 100KB
                        }
                        
                        // 偶尔清理内存
                        if (memoryLoad.size() > 100) {
                            memoryLoad.clear();
                            System.gc(); // 触发GC
                        }
                        
                        // 模拟业务处理时间
                        Thread.sleep(100 + random.nextInt(200));
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
        
        // 2分钟后停止负载
        monitorExecutor.schedule(() -> {
            loadExecutor.shutdownNow();
            System.out.println("应用负载模拟停止");
        }, 2, TimeUnit.MINUTES);
    }
    
    /**
     * 获取当前监控指标 (用于外部查询)
     */
    public static Map<String, Long> getCurrentMetrics() {
        Map<String, Long> currentMetrics = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : metrics.entrySet()) {
            currentMetrics.put(entry.getKey(), entry.getValue().get());
        }
        return currentMetrics;
    }
    
    /**
     * 健康检查接口 (用于负载均衡器)
     */
    public static boolean isHealthy() {
        // 检查关键指标是否正常
        AtomicLong heapUsage = metrics.get("heap.usage.percent");
        AtomicLong deadlocks = metrics.get("thread.deadlocked");
        
        boolean healthy = true;
        
        if (heapUsage != null && heapUsage.get() > 90) {
            healthy = false;
        }
        
        if (deadlocks != null && deadlocks.get() > 0) {
            healthy = false;
        }
        
        return healthy;
    }
}