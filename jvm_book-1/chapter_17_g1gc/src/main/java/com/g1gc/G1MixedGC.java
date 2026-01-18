package com.g1gc;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * G1混合回收(Mixed GC)实现
 * 
 * 混合回收是G1的核心特性，它在一次GC中同时回收年轻代和部分老年代Region
 * 基于8GB堆内存环境，Region大小4MB的配置
 */
public class G1MixedGC {
    
    /**
     * 混合回收执行器
     */
    public static class MixedGCExecutor {
        private final G1RegionManager regionManager;
        private final G1ConcurrentMarking.SATBMarkingAlgorithm markingAlgorithm;
        private final PauseTimePredictor pausePredictor;
        
        // 混合回收配置参数
        private static final int TARGET_PAUSE_TIME_MS = 200;
        private static final double GARBAGE_RATIO_THRESHOLD = 0.1; // 10%垃圾比例阈值
        private static final int MAX_OLD_REGIONS_PER_CYCLE = 8; // 每次最多回收8个老年代Region
        
        public MixedGCExecutor(G1RegionManager regionManager, 
                              G1ConcurrentMarking.SATBMarkingAlgorithm markingAlgorithm) {
            this.regionManager = regionManager;
            this.markingAlgorithm = markingAlgorithm;
            this.pausePredictor = new PauseTimePredictor();
        }
        
        /**
         * 执行混合回收
         */
        public MixedGCResult executeMixedGC() {
            System.out.println("=== 开始G1混合回收 ===");
            long startTime = System.nanoTime();
            
            // 第一步：选择回收集合(Collection Set)
            CollectionSet collectionSet = selectCollectionSet();
            
            // 第二步：暂停应用程序线程
            pauseApplicationThreads();
            
            try {
                // 第三步：回收年轻代Region
                YoungGCResult youngResult = evacuateYoungGeneration(collectionSet);
                
                // 第四步：回收选定的老年代Region
                OldGCResult oldResult = evacuateOldGeneration(collectionSet);
                
                // 第五步：更新Remember Set
                updateRememberSets(collectionSet);
                
                // 第六步：释放回收的Region
                releaseCollectedRegions(collectionSet);
                
                long endTime = System.nanoTime();
                long pauseTimeMs = (endTime - startTime) / 1_000_000;
                
                return new MixedGCResult(youngResult, oldResult, pauseTimeMs, collectionSet);
                
            } finally {
                // 第七步：恢复应用程序线程
                resumeApplicationThreads();
            }
        }
        
        /**
         * 选择回收集合
         */
        private CollectionSet selectCollectionSet() {
            System.out.println("=== 选择回收集合 ===");
            
            CollectionSet collectionSet = new CollectionSet();
            
            // 1. 添加所有年轻代Region（Eden + Survivor）
            List<G1Region> edenRegions = regionManager.getRegionsByType(G1Region.RegionType.EDEN);
            List<G1Region> survivorRegions = regionManager.getRegionsByType(G1Region.RegionType.SURVIVOR);
            
            collectionSet.addYoungRegions(edenRegions);
            collectionSet.addYoungRegions(survivorRegions);
            
            // 2. 选择老年代Region
            List<G1Region> oldCandidates = selectOldRegionCandidates();
            
            // 3. 基于暂停时间预测选择最终的老年代Region
            List<G1Region> selectedOldRegions = selectOldRegionsWithinPauseTarget(oldCandidates);
            collectionSet.addOldRegions(selectedOldRegions);
            
            System.out.println("回收集合选择完成:");
            System.out.println("- 年轻代Region: " + collectionSet.getYoungRegions().size());
            System.out.println("- 老年代Region: " + collectionSet.getOldRegions().size());
            
            return collectionSet;
        }
        
        /**
         * 选择老年代候选Region
         */
        private List<G1Region> selectOldRegionCandidates() {
            List<G1Region> oldRegions = regionManager.getRegionsByType(G1Region.RegionType.OLD);
            Set<Long> markedObjects = markingAlgorithm.getMarkedObjects();
            
            // 计算每个Region的垃圾比例
            for (G1Region region : oldRegions) {
                region.calculateGarbageRatio(markedObjects);
            }
            
            // 选择垃圾比例超过阈值的Region
            return oldRegions.stream()
                    .filter(region -> region.getGarbageRatio() >= GARBAGE_RATIO_THRESHOLD)
                    .sorted((r1, r2) -> Double.compare(r2.getGarbageRatio(), r1.getGarbageRatio()))
                    .collect(Collectors.toList());
        }
        
        /**
         * 在暂停时间目标内选择老年代Region
         */
        private List<G1Region> selectOldRegionsWithinPauseTarget(List<G1Region> candidates) {
            List<G1Region> selected = new ArrayList<>();
            long estimatedPauseTime = 0;
            
            for (G1Region region : candidates) {
                long regionPauseTime = pausePredictor.predictRegionEvacuationTime(region);
                
                if (estimatedPauseTime + regionPauseTime <= TARGET_PAUSE_TIME_MS && 
                    selected.size() < MAX_OLD_REGIONS_PER_CYCLE) {
                    
                    selected.add(region);
                    estimatedPauseTime += regionPauseTime;
                    
                    System.out.println("选择老年代Region " + region.getRegionId() + 
                                     " (垃圾比例: " + String.format("%.2f%%", region.getGarbageRatio() * 100) + 
                                     ", 预计耗时: " + regionPauseTime + "ms)");
                } else {
                    break; // 超出暂停时间目标或数量限制
                }
            }
            
            System.out.println("预计总暂停时间: " + estimatedPauseTime + "ms (目标: " + TARGET_PAUSE_TIME_MS + "ms)");
            return selected;
        }
        
        /**
         * 疏散年轻代
         */
        private YoungGCResult evacuateYoungGeneration(CollectionSet collectionSet) {
            System.out.println("=== 疏散年轻代 ===");
            long startTime = System.nanoTime();
            
            List<G1Region> youngRegions = collectionSet.getYoungRegions();
            int survivorAge = 0;
            int promotedObjects = 0;
            int survivedObjects = 0;
            
            for (G1Region region : youngRegions) {
                EvacuationResult result = evacuateRegion(region, true);
                survivedObjects += result.getSurvivedObjects();
                promotedObjects += result.getPromotedObjects();
            }
            
            long endTime = System.nanoTime();
            long evacuationTime = (endTime - startTime) / 1_000_000;
            
            System.out.println("年轻代疏散完成: 存活=" + survivedObjects + 
                             ", 晋升=" + promotedObjects + 
                             ", 耗时=" + evacuationTime + "ms");
            
            return new YoungGCResult(survivedObjects, promotedObjects, evacuationTime);
        }
        
        /**
         * 疏散老年代
         */
        private OldGCResult evacuateOldGeneration(CollectionSet collectionSet) {
            System.out.println("=== 疏散老年代 ===");
            long startTime = System.nanoTime();
            
            List<G1Region> oldRegions = collectionSet.getOldRegions();
            int survivedObjects = 0;
            long reclaimedMemory = 0;
            
            for (G1Region region : oldRegions) {
                long beforeMemory = region.getUsedBytes();
                EvacuationResult result = evacuateRegion(region, false);
                survivedObjects += result.getSurvivedObjects();
                reclaimedMemory += beforeMemory - (result.getSurvivedObjects() * 64); // 假设平均对象64字节
            }
            
            long endTime = System.nanoTime();
            long evacuationTime = (endTime - startTime) / 1_000_000;
            
            System.out.println("老年代疏散完成: 存活=" + survivedObjects + 
                             ", 回收内存=" + (reclaimedMemory / 1024 / 1024) + "MB" +
                             ", 耗时=" + evacuationTime + "ms");
            
            return new OldGCResult(survivedObjects, reclaimedMemory, evacuationTime);
        }
        
        /**
         * 疏散单个Region
         */
        private EvacuationResult evacuateRegion(G1Region region, boolean isYoung) {
            Set<Long> markedObjects = markingAlgorithm.getMarkedObjects();
            int survivedObjects = 0;
            int promotedObjects = 0;
            
            // 模拟对象疏散
            long regionStart = region.getStartAddress();
            long regionEnd = regionStart + region.getUsedBytes();
            
            for (long addr = regionStart; addr < regionEnd; addr += 64) { // 假设64字节对象
                if (markedObjects.contains(addr)) {
                    // 对象存活，需要疏散
                    if (isYoung && shouldPromoteToOldGen(addr)) {
                        // 晋升到老年代
                        evacuateToOldGeneration(addr);
                        promotedObjects++;
                    } else {
                        // 疏散到同代的其他Region
                        evacuateToSameGeneration(addr, isYoung);
                        survivedObjects++;
                    }
                }
            }
            
            return new EvacuationResult(survivedObjects, promotedObjects);
        }
        
        private boolean shouldPromoteToOldGen(long objectAddress) {
            // 模拟晋升条件：对象年龄达到阈值
            return getObjectAge(objectAddress) >= 15;
        }
        
        private int getObjectAge(long objectAddress) {
            // 模拟获取对象年龄
            return (int) (objectAddress % 16);
        }
        
        private void evacuateToOldGeneration(long objectAddress) {
            // 模拟疏散到老年代
            G1Region targetRegion = regionManager.allocateRegion(G1Region.RegionType.OLD);
            if (targetRegion != null) {
                targetRegion.allocateObject(64);
            }
        }
        
        private void evacuateToSameGeneration(long objectAddress, boolean isYoung) {
            // 模拟疏散到同代
            G1Region.RegionType targetType = isYoung ? 
                    G1Region.RegionType.SURVIVOR : G1Region.RegionType.OLD;
            
            G1Region targetRegion = regionManager.allocateRegion(targetType);
            if (targetRegion != null) {
                targetRegion.allocateObject(64);
            }
        }
        
        private void updateRememberSets(CollectionSet collectionSet) {
            System.out.println("=== 更新Remember Set ===");
            // 更新跨Region引用信息
            // 实际实现中需要扫描所有疏散后的对象，更新Remember Set
        }
        
        private void releaseCollectedRegions(CollectionSet collectionSet) {
            System.out.println("=== 释放回收的Region ===");
            
            // 释放年轻代Region
            for (G1Region region : collectionSet.getYoungRegions()) {
                regionManager.freeRegion(region.getRegionId());
            }
            
            // 释放老年代Region
            for (G1Region region : collectionSet.getOldRegions()) {
                regionManager.freeRegion(region.getRegionId());
            }
        }
        
        private void pauseApplicationThreads() {
            System.out.println("暂停应用程序线程");
        }
        
        private void resumeApplicationThreads() {
            System.out.println("恢复应用程序线程");
        }
    }
    
    /**
     * 回收集合
     */
    public static class CollectionSet {
        private final List<G1Region> youngRegions = new ArrayList<>();
        private final List<G1Region> oldRegions = new ArrayList<>();
        
        public void addYoungRegions(List<G1Region> regions) {
            youngRegions.addAll(regions);
        }
        
        public void addOldRegions(List<G1Region> regions) {
            oldRegions.addAll(regions);
        }
        
        public List<G1Region> getYoungRegions() { return youngRegions; }
        public List<G1Region> getOldRegions() { return oldRegions; }
        
        public int getTotalRegions() {
            return youngRegions.size() + oldRegions.size();
        }
    }
    
    /**
     * 暂停时间预测器
     */
    public static class PauseTimePredictor {
        private final Map<String, List<Long>> historicalData = new HashMap<>();
        
        public long predictRegionEvacuationTime(G1Region region) {
            String key = region.getType().name();
            List<Long> history = historicalData.getOrDefault(key, new ArrayList<>());
            
            if (history.isEmpty()) {
                // 没有历史数据，使用默认估算
                return estimateEvacuationTime(region);
            }
            
            // 基于历史数据预测
            double avgTime = history.stream().mapToLong(Long::longValue).average().orElse(20.0);
            return (long) avgTime;
        }
        
        private long estimateEvacuationTime(G1Region region) {
            // 基于Region使用率估算疏散时间
            double usageRate = region.getUsageRate();
            long baseTime = 10; // 基础时间10ms
            long variableTime = (long) (usageRate * 30); // 根据使用率增加0-30ms
            
            return baseTime + variableTime;
        }
        
        public void recordEvacuationTime(G1Region.RegionType type, long actualTime) {
            String key = type.name();
            historicalData.computeIfAbsent(key, k -> new ArrayList<>()).add(actualTime);
            
            // 保持历史数据大小在合理范围内
            List<Long> history = historicalData.get(key);
            if (history.size() > 100) {
                history.remove(0);
            }
        }
    }
    
    /**
     * 疏散结果
     */
    public static class EvacuationResult {
        private final int survivedObjects;
        private final int promotedObjects;
        
        public EvacuationResult(int survivedObjects, int promotedObjects) {
            this.survivedObjects = survivedObjects;
            this.promotedObjects = promotedObjects;
        }
        
        public int getSurvivedObjects() { return survivedObjects; }
        public int getPromotedObjects() { return promotedObjects; }
    }
    
    /**
     * 年轻代GC结果
     */
    public static class YoungGCResult {
        private final int survivedObjects;
        private final int promotedObjects;
        private final long evacuationTimeMs;
        
        public YoungGCResult(int survivedObjects, int promotedObjects, long evacuationTimeMs) {
            this.survivedObjects = survivedObjects;
            this.promotedObjects = promotedObjects;
            this.evacuationTimeMs = evacuationTimeMs;
        }
        
        public int getSurvivedObjects() { return survivedObjects; }
        public int getPromotedObjects() { return promotedObjects; }
        public long getEvacuationTimeMs() { return evacuationTimeMs; }
    }
    
    /**
     * 老年代GC结果
     */
    public static class OldGCResult {
        private final int survivedObjects;
        private final long reclaimedMemory;
        private final long evacuationTimeMs;
        
        public OldGCResult(int survivedObjects, long reclaimedMemory, long evacuationTimeMs) {
            this.survivedObjects = survivedObjects;
            this.reclaimedMemory = reclaimedMemory;
            this.evacuationTimeMs = evacuationTimeMs;
        }
        
        public int getSurvivedObjects() { return survivedObjects; }
        public long getReclaimedMemory() { return reclaimedMemory; }
        public long getEvacuationTimeMs() { return evacuationTimeMs; }
    }
    
    /**
     * 混合GC结果
     */
    public static class MixedGCResult {
        private final YoungGCResult youngResult;
        private final OldGCResult oldResult;
        private final long totalPauseTimeMs;
        private final CollectionSet collectionSet;
        
        public MixedGCResult(YoungGCResult youngResult, OldGCResult oldResult, 
                           long totalPauseTimeMs, CollectionSet collectionSet) {
            this.youngResult = youngResult;
            this.oldResult = oldResult;
            this.totalPauseTimeMs = totalPauseTimeMs;
            this.collectionSet = collectionSet;
        }
        
        public void printResult() {
            System.out.println("=== 混合GC结果 ===");
            System.out.println("总暂停时间: " + totalPauseTimeMs + "ms");
            System.out.println("回收Region数: " + collectionSet.getTotalRegions());
            
            System.out.println("\n年轻代结果:");
            System.out.println("- 存活对象: " + youngResult.getSurvivedObjects());
            System.out.println("- 晋升对象: " + youngResult.getPromotedObjects());
            System.out.println("- 疏散时间: " + youngResult.getEvacuationTimeMs() + "ms");
            
            System.out.println("\n老年代结果:");
            System.out.println("- 存活对象: " + oldResult.getSurvivedObjects());
            System.out.println("- 回收内存: " + (oldResult.getReclaimedMemory() / 1024 / 1024) + "MB");
            System.out.println("- 疏散时间: " + oldResult.getEvacuationTimeMs() + "ms");
        }
        
        public YoungGCResult getYoungResult() { return youngResult; }
        public OldGCResult getOldResult() { return oldResult; }
        public long getTotalPauseTimeMs() { return totalPauseTimeMs; }
    }
    
    // 占位符类，实际实现中需要引用前面定义的类
    static class G1Region {
        enum RegionType { EDEN, SURVIVOR, OLD, FREE, HUMONGOUS, PINNED }
        
        private final int regionId;
        private final long startAddress;
        private RegionType type;
        private long usedBytes;
        private double garbageRatio;
        
        public G1Region(int regionId, long startAddress) {
            this.regionId = regionId;
            this.startAddress = startAddress;
        }
        
        public int getRegionId() { return regionId; }
        public long getStartAddress() { return startAddress; }
        public RegionType getType() { return type; }
        public void setType(RegionType type) { this.type = type; }
        public long getUsedBytes() { return usedBytes; }
        public double getUsageRate() { return usedBytes / (4.0 * 1024 * 1024); }
        public double getGarbageRatio() { return garbageRatio; }
        
        public void calculateGarbageRatio(Set<Long> markedObjects) {
            // 模拟计算垃圾比例
            this.garbageRatio = Math.random() * 0.5; // 0-50%的垃圾比例
        }
        
        public long allocateObject(int size) {
            long addr = startAddress + usedBytes;
            usedBytes += size;
            return addr;
        }
    }
    
    static class G1RegionManager {
        public List<G1Region> getRegionsByType(G1Region.RegionType type) {
            return new ArrayList<>();
        }
        
        public G1Region allocateRegion(G1Region.RegionType type) {
            return new G1Region(1, 1000);
        }
        
        public void freeRegion(int regionId) {
            // 释放Region
        }
    }
    
    static class G1ConcurrentMarking {
        static class SATBMarkingAlgorithm {
            public Set<Long> getMarkedObjects() {
                return new HashSet<>();
            }
        }
    }
}