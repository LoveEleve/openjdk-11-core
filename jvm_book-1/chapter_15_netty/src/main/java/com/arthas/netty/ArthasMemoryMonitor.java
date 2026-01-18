package com.arthas.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Arthas内存监控器
 * 监控JVM内存使用情况，特别针对8GB堆内存环境优化
 */
public class ArthasMemoryMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasMemoryMonitor.class);
    
    private final ScheduledExecutorService scheduler;
    private final MemoryMXBean memoryBean;
    private final GarbageCollectorMXBean[] gcBeans;
    private final ThreadMXBean threadBean;
    
    // 监控阈值配置（基于8GB堆内存）
    private static final double HEAP_WARNING_THRESHOLD = 0.8;  // 80%堆内存使用率告警
    private static final double HEAP_CRITICAL_THRESHOLD = 0.9; // 90%堆内存使用率严重告警
    private static final long GC_TIME_WARNING_THRESHOLD = 1000; // GC时间超过1秒告警
    
    private long lastGcTime = 0;
    private long lastGcCount = 0;
    
    public ArthasMemoryMonitor() {
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Arthas-Memory-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans().toArray(new GarbageCollectorMXBean[0]);
        this.threadBean = ManagementFactory.getThreadMXBean();
        
        logger.info("内存监控器初始化完成 - 堆内存告警阈值: {}%, 严重告警阈值: {}%", 
                   HEAP_WARNING_THRESHOLD * 100, HEAP_CRITICAL_THRESHOLD * 100);
    }
    
    /**
     * 开始监控
     */
    public void startMonitoring() {
        // 每30秒监控一次内存使用情况
        scheduler.scheduleAtFixedRate(this::monitorMemoryUsage, 0, 30, TimeUnit.SECONDS);
        
        // 每5分钟进行一次详细的内存分析
        scheduler.scheduleAtFixedRate(this::detailedMemoryAnalysis, 60, 300, TimeUnit.SECONDS);
        
        logger.info("内存监控已启动");
    }
    
    /**
     * 停止监控
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("内存监控已停止");
    }
    
    /**
     * 监控内存使用情况
     */
    private void monitorMemoryUsage() {
        try {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            
            // 计算堆内存使用率
            double heapUsageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();
            
            // 获取GC信息
            GCStats gcStats = collectGCStats();
            
            // 获取线程信息
            int threadCount = threadBean.getThreadCount();
            int daemonThreadCount = threadBean.getDaemonThreadCount();
            
            // 记录基本信息
            logger.info("💾 内存监控 - 堆内存: {}MB/{}MB ({:.1f}%), 非堆内存: {}MB, " +
                       "GC次数: {}, GC时间: {}ms, 线程数: {}/{}(守护)",
                       heapUsage.getUsed() / 1024 / 1024,
                       heapUsage.getMax() / 1024 / 1024,
                       heapUsageRatio * 100,
                       nonHeapUsage.getUsed() / 1024 / 1024,
                       gcStats.totalGcCount,
                       gcStats.totalGcTime,
                       threadCount,
                       daemonThreadCount);
            
            // 检查告警条件
            checkMemoryAlerts(heapUsageRatio, gcStats);
            
        } catch (Exception e) {
            logger.error("内存监控异常", e);
        }
    }
    
    /**
     * 详细内存分析
     */
    private void detailedMemoryAnalysis() {
        try {
            logger.info("🔍 开始详细内存分析...");
            
            // 堆内存详细信息
            analyzeHeapMemory();
            
            // 非堆内存详细信息
            analyzeNonHeapMemory();
            
            // GC详细分析
            analyzeGarbageCollection();
            
            // 线程详细分析
            analyzeThreads();
            
            // 内存池分析
            analyzeMemoryPools();
            
            logger.info("✅ 详细内存分析完成");
            
        } catch (Exception e) {
            logger.error("详细内存分析异常", e);
        }
    }
    
    /**
     * 分析堆内存
     */
    private void analyzeHeapMemory() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        long used = heapUsage.getUsed();
        long committed = heapUsage.getCommitted();
        long max = heapUsage.getMax();
        long init = heapUsage.getInit();
        
        logger.info("📊 堆内存详情:");
        logger.info("  - 初始大小: {}MB", init / 1024 / 1024);
        logger.info("  - 已使用: {}MB", used / 1024 / 1024);
        logger.info("  - 已提交: {}MB", committed / 1024 / 1024);
        logger.info("  - 最大大小: {}MB", max / 1024 / 1024);
        logger.info("  - 使用率: {:.2f}%", (double) used / max * 100);
        logger.info("  - 提交率: {:.2f}%", (double) committed / max * 100);
        
        // 在8GB堆环境下的G1 GC分析
        if (max == 8L * 1024 * 1024 * 1024) { // 8GB
            int regionSize = 4 * 1024 * 1024; // 4MB per region
            int totalRegions = (int) (max / regionSize);
            int usedRegions = (int) (used / regionSize);
            
            logger.info("  - G1 Region分析: 总Region数={}, 已使用Region数={}, Region大小=4MB", 
                       totalRegions, usedRegions);
        }
    }
    
    /**
     * 分析非堆内存
     */
    private void analyzeNonHeapMemory() {
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        logger.info("📊 非堆内存详情:");
        logger.info("  - 已使用: {}MB", nonHeapUsage.getUsed() / 1024 / 1024);
        logger.info("  - 已提交: {}MB", nonHeapUsage.getCommitted() / 1024 / 1024);
        
        if (nonHeapUsage.getMax() > 0) {
            logger.info("  - 最大大小: {}MB", nonHeapUsage.getMax() / 1024 / 1024);
            logger.info("  - 使用率: {:.2f}%", 
                       (double) nonHeapUsage.getUsed() / nonHeapUsage.getMax() * 100);
        }
    }
    
    /**
     * 分析垃圾收集
     */
    private void analyzeGarbageCollection() {
        logger.info("📊 垃圾收集详情:");
        
        long totalGcTime = 0;
        long totalGcCount = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcTime = gcBean.getCollectionTime();
            long gcCount = gcBean.getCollectionCount();
            
            totalGcTime += gcTime;
            totalGcCount += gcCount;
            
            logger.info("  - {}: 次数={}, 时间={}ms, 平均时间={:.2f}ms", 
                       gcBean.getName(), gcCount, gcTime,
                       gcCount > 0 ? (double) gcTime / gcCount : 0);
        }
        
        // 计算GC开销
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        double gcOverhead = uptime > 0 ? (double) totalGcTime / uptime * 100 : 0;
        
        logger.info("  - 总GC时间: {}ms, 总GC次数: {}, GC开销: {:.2f}%", 
                   totalGcTime, totalGcCount, gcOverhead);
        
        // GC频率分析
        if (lastGcCount > 0) {
            long gcCountDelta = totalGcCount - lastGcCount;
            long gcTimeDelta = totalGcTime - lastGcTime;
            
            if (gcCountDelta > 0) {
                logger.info("  - 近期GC: 次数增量={}, 时间增量={}ms, 平均时间={:.2f}ms",
                           gcCountDelta, gcTimeDelta, (double) gcTimeDelta / gcCountDelta);
            }
        }
        
        lastGcCount = totalGcCount;
        lastGcTime = totalGcTime;
    }
    
    /**
     * 分析线程
     */
    private void analyzeThreads() {
        int threadCount = threadBean.getThreadCount();
        int daemonThreadCount = threadBean.getDaemonThreadCount();
        int peakThreadCount = threadBean.getPeakThreadCount();
        long totalStartedThreadCount = threadBean.getTotalStartedThreadCount();
        
        logger.info("📊 线程详情:");
        logger.info("  - 当前线程数: {}", threadCount);
        logger.info("  - 守护线程数: {}", daemonThreadCount);
        logger.info("  - 用户线程数: {}", threadCount - daemonThreadCount);
        logger.info("  - 峰值线程数: {}", peakThreadCount);
        logger.info("  - 总启动线程数: {}", totalStartedThreadCount);
        
        // 检查线程数是否异常
        if (threadCount > 1000) {
            logger.warn("⚠️  线程数过多: {} (可能存在线程泄漏)", threadCount);
        }
    }
    
    /**
     * 分析内存池
     */
    private void analyzeMemoryPools() {
        logger.info("📊 内存池详情:");
        
        for (MemoryPoolMXBean poolBean : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = poolBean.getUsage();
            if (usage != null) {
                String poolName = poolBean.getName();
                long used = usage.getUsed();
                long max = usage.getMax();
                
                logger.info("  - {}: {}MB/{}", 
                           poolName, 
                           used / 1024 / 1024,
                           max > 0 ? max / 1024 / 1024 + "MB" : "无限制");
                
                // 检查内存池使用率
                if (max > 0) {
                    double usageRatio = (double) used / max;
                    if (usageRatio > 0.9) {
                        logger.warn("⚠️  内存池 {} 使用率过高: {:.1f}%", poolName, usageRatio * 100);
                    }
                }
            }
        }
    }
    
    /**
     * 检查内存告警
     */
    private void checkMemoryAlerts(double heapUsageRatio, GCStats gcStats) {
        // 堆内存使用率告警
        if (heapUsageRatio >= HEAP_CRITICAL_THRESHOLD) {
            logger.error("🚨 严重告警: 堆内存使用率达到 {:.1f}% (阈值: {:.1f}%)", 
                        heapUsageRatio * 100, HEAP_CRITICAL_THRESHOLD * 100);
        } else if (heapUsageRatio >= HEAP_WARNING_THRESHOLD) {
            logger.warn("⚠️  告警: 堆内存使用率达到 {:.1f}% (阈值: {:.1f}%)", 
                       heapUsageRatio * 100, HEAP_WARNING_THRESHOLD * 100);
        }
        
        // GC时间告警
        if (gcStats.recentGcTime > GC_TIME_WARNING_THRESHOLD) {
            logger.warn("⚠️  告警: 近期GC时间过长 {}ms (阈值: {}ms)", 
                       gcStats.recentGcTime, GC_TIME_WARNING_THRESHOLD);
        }
        
        // GC频率告警
        if (gcStats.recentGcCount > 10) { // 30秒内GC超过10次
            logger.warn("⚠️  告警: GC频率过高，30秒内发生 {} 次GC", gcStats.recentGcCount);
        }
    }
    
    /**
     * 收集GC统计信息
     */
    private GCStats collectGCStats() {
        long totalGcTime = 0;
        long totalGcCount = 0;
        long recentGcTime = 0;
        long recentGcCount = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long gcTime = gcBean.getCollectionTime();
            long gcCount = gcBean.getCollectionCount();
            
            totalGcTime += gcTime;
            totalGcCount += gcCount;
            
            // 计算近期GC（与上次监控的差值）
            // 这里简化处理，实际应该记录每个GC器的历史数据
        }
        
        return new GCStats(totalGcTime, totalGcCount, recentGcTime, recentGcCount);
    }
    
    /**
     * 获取当前内存快照
     */
    public MemorySnapshot getMemorySnapshot() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        GCStats gcStats = collectGCStats();
        
        return new MemorySnapshot(
            heapUsage.getUsed(),
            heapUsage.getMax(),
            nonHeapUsage.getUsed(),
            gcStats.totalGcCount,
            gcStats.totalGcTime,
            threadBean.getThreadCount(),
            System.currentTimeMillis()
        );
    }
    
    /**
     * GC统计信息
     */
    private static class GCStats {
        final long totalGcTime;
        final long totalGcCount;
        final long recentGcTime;
        final long recentGcCount;
        
        GCStats(long totalGcTime, long totalGcCount, long recentGcTime, long recentGcCount) {
            this.totalGcTime = totalGcTime;
            this.totalGcCount = totalGcCount;
            this.recentGcTime = recentGcTime;
            this.recentGcCount = recentGcCount;
        }
    }
    
    /**
     * 内存快照
     */
    public static class MemorySnapshot {
        private final long heapUsed;
        private final long heapMax;
        private final long nonHeapUsed;
        private final long gcCount;
        private final long gcTime;
        private final int threadCount;
        private final long timestamp;
        
        public MemorySnapshot(long heapUsed, long heapMax, long nonHeapUsed,
                             long gcCount, long gcTime, int threadCount, long timestamp) {
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.nonHeapUsed = nonHeapUsed;
            this.gcCount = gcCount;
            this.gcTime = gcTime;
            this.threadCount = threadCount;
            this.timestamp = timestamp;
        }
        
        public double getHeapUsageRatio() {
            return heapMax > 0 ? (double) heapUsed / heapMax : 0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "MemorySnapshot{堆内存=%dMB/%dMB(%.1f%%), 非堆内存=%dMB, " +
                "GC次数=%d, GC时间=%dms, 线程数=%d, 时间=%d}",
                heapUsed / 1024 / 1024, heapMax / 1024 / 1024, getHeapUsageRatio() * 100,
                nonHeapUsed / 1024 / 1024, gcCount, gcTime, threadCount, timestamp
            );
        }
        
        // Getters
        public long getHeapUsed() { return heapUsed; }
        public long getHeapMax() { return heapMax; }
        public long getNonHeapUsed() { return nonHeapUsed; }
        public long getGcCount() { return gcCount; }
        public long getGcTime() { return gcTime; }
        public int getThreadCount() { return threadCount; }
        public long getTimestamp() { return timestamp; }
    }
}