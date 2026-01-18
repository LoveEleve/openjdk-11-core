package com.g1gc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * G1 Remember Set实现
 * 
 * Remember Set用于记录跨Region的引用关系，避免在GC时扫描整个堆
 * 基于8GB堆内存环境，Region大小4MB的配置
 */
public class G1RememberSet {
    
    /**
     * Remember Set实现
     */
    public static class RememberSet {
        // 记录指向当前Region的跨Region引用
        private final Set<CrossRegionReference> incomingReferences = ConcurrentHashMap.newKeySet();
        
        // 记录从当前Region指向其他Region的引用
        private final Set<CrossRegionReference> outgoingReferences = ConcurrentHashMap.newKeySet();
        
        private final int regionId;
        private final AtomicInteger referenceCount = new AtomicInteger(0);
        
        public RememberSet(int regionId) {
            this.regionId = regionId;
        }
        
        /**
         * 添加传入引用
         */
        public void addIncomingReference(CrossRegionReference reference) {
            if (incomingReferences.add(reference)) {
                referenceCount.incrementAndGet();
                System.out.println("Region " + regionId + " 添加传入引用: " + reference);
            }
        }
        
        /**
         * 添加传出引用
         */
        public void addOutgoingReference(CrossRegionReference reference) {
            if (outgoingReferences.add(reference)) {
                System.out.println("Region " + regionId + " 添加传出引用: " + reference);
            }
        }
        
        /**
         * 移除传入引用
         */
        public void removeIncomingReference(CrossRegionReference reference) {
            if (incomingReferences.remove(reference)) {
                referenceCount.decrementAndGet();
                System.out.println("Region " + regionId + " 移除传入引用: " + reference);
            }
        }
        
        /**
         * 移除传出引用
         */
        public void removeOutgoingReference(CrossRegionReference reference) {
            if (outgoingReferences.remove(reference)) {
                System.out.println("Region " + regionId + " 移除传出引用: " + reference);
            }
        }
        
        /**
         * 获取所有传入引用的源Region
         */
        public Set<Integer> getIncomingRegions() {
            return incomingReferences.stream()
                    .map(CrossRegionReference::getSourceRegion)
                    .collect(Collectors.toSet());
        }
        
        /**
         * 获取所有传出引用的目标Region
         */
        public Set<Integer> getOutgoingRegions() {
            return outgoingReferences.stream()
                    .map(CrossRegionReference::getTargetRegion)
                    .collect(Collectors.toSet());
        }
        
        /**
         * 清空Remember Set
         */
        public void clear() {
            incomingReferences.clear();
            outgoingReferences.clear();
            referenceCount.set(0);
        }
        
        public Set<CrossRegionReference> getIncomingReferences() { return new HashSet<>(incomingReferences); }
        public Set<CrossRegionReference> getOutgoingReferences() { return new HashSet<>(outgoingReferences); }
        public int getReferenceCount() { return referenceCount.get(); }
        public int getRegionId() { return regionId; }
    }
    
    /**
     * 跨Region引用
     */
    public static class CrossRegionReference {
        private final int sourceRegion;
        private final long sourceAddress;
        private final int targetRegion;
        private final long targetAddress;
        private final ReferenceType type;
        
        public CrossRegionReference(int sourceRegion, long sourceAddress, 
                                  int targetRegion, long targetAddress, ReferenceType type) {
            this.sourceRegion = sourceRegion;
            this.sourceAddress = sourceAddress;
            this.targetRegion = targetRegion;
            this.targetAddress = targetAddress;
            this.type = type;
        }
        
        public int getSourceRegion() { return sourceRegion; }
        public long getSourceAddress() { return sourceAddress; }
        public int getTargetRegion() { return targetRegion; }
        public long getTargetAddress() { return targetAddress; }
        public ReferenceType getType() { return type; }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CrossRegionReference)) return false;
            
            CrossRegionReference other = (CrossRegionReference) obj;
            return sourceRegion == other.sourceRegion &&
                   sourceAddress == other.sourceAddress &&
                   targetRegion == other.targetRegion &&
                   targetAddress == other.targetAddress;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(sourceRegion, sourceAddress, targetRegion, targetAddress);
        }
        
        @Override
        public String toString() {
            return String.format("Ref[%d:0x%x -> %d:0x%x, %s]", 
                               sourceRegion, sourceAddress, targetRegion, targetAddress, type);
        }
    }
    
    /**
     * 引用类型
     */
    public enum ReferenceType {
        FIELD_REFERENCE,    // 字段引用
        ARRAY_REFERENCE,    // 数组元素引用
        STATIC_REFERENCE    // 静态字段引用
    }
    
    /**
     * Remember Set管理器
     */
    public static class RememberSetManager {
        private final Map<Integer, RememberSet> rememberSets = new ConcurrentHashMap<>();
        private final G1WriteBarrier writeBarrier;
        private final RememberSetStatistics statistics;
        
        public RememberSetManager() {
            this.writeBarrier = new G1WriteBarrier(this);
            this.statistics = new RememberSetStatistics();
        }
        
        /**
         * 获取或创建Region的Remember Set
         */
        public RememberSet getOrCreateRememberSet(int regionId) {
            return rememberSets.computeIfAbsent(regionId, RememberSet::new);
        }
        
        /**
         * 记录跨Region引用
         */
        public void recordCrossRegionReference(int sourceRegion, long sourceAddress,
                                             int targetRegion, long targetAddress,
                                             ReferenceType type) {
            if (sourceRegion == targetRegion) {
                return; // 同Region内的引用不需要记录
            }
            
            CrossRegionReference reference = new CrossRegionReference(
                    sourceRegion, sourceAddress, targetRegion, targetAddress, type);
            
            // 在目标Region的Remember Set中记录传入引用
            RememberSet targetRS = getOrCreateRememberSet(targetRegion);
            targetRS.addIncomingReference(reference);
            
            // 在源Region的Remember Set中记录传出引用
            RememberSet sourceRS = getOrCreateRememberSet(sourceRegion);
            sourceRS.addOutgoingReference(reference);
            
            statistics.recordReference(type);
        }
        
        /**
         * 移除跨Region引用
         */
        public void removeCrossRegionReference(int sourceRegion, long sourceAddress,
                                             int targetRegion, long targetAddress,
                                             ReferenceType type) {
            CrossRegionReference reference = new CrossRegionReference(
                    sourceRegion, sourceAddress, targetRegion, targetAddress, type);
            
            RememberSet targetRS = rememberSets.get(targetRegion);
            if (targetRS != null) {
                targetRS.removeIncomingReference(reference);
            }
            
            RememberSet sourceRS = rememberSets.get(sourceRegion);
            if (sourceRS != null) {
                sourceRS.removeOutgoingReference(reference);
            }
        }
        
        /**
         * 清理Region的Remember Set
         */
        public void clearRememberSet(int regionId) {
            RememberSet rs = rememberSets.remove(regionId);
            if (rs != null) {
                // 清理该Region的所有传出引用在目标Region中的记录
                for (CrossRegionReference ref : rs.getOutgoingReferences()) {
                    RememberSet targetRS = rememberSets.get(ref.getTargetRegion());
                    if (targetRS != null) {
                        targetRS.removeIncomingReference(ref);
                    }
                }
                
                // 清理该Region的所有传入引用在源Region中的记录
                for (CrossRegionReference ref : rs.getIncomingReferences()) {
                    RememberSet sourceRS = rememberSets.get(ref.getSourceRegion());
                    if (sourceRS != null) {
                        sourceRS.removeOutgoingReference(ref);
                    }
                }
                
                rs.clear();
                System.out.println("清理Region " + regionId + " 的Remember Set");
            }
        }
        
        /**
         * 获取需要扫描的Region集合
         */
        public Set<Integer> getRegionsToScan(Set<Integer> collectionSet) {
            Set<Integer> regionsToScan = new HashSet<>();
            
            for (Integer regionId : collectionSet) {
                RememberSet rs = rememberSets.get(regionId);
                if (rs != null) {
                    // 添加所有指向回收集合中Region的源Region
                    regionsToScan.addAll(rs.getIncomingRegions());
                }
            }
            
            // 移除回收集合中的Region（它们本身会被完全扫描）
            regionsToScan.removeAll(collectionSet);
            
            return regionsToScan;
        }
        
        /**
         * 打印Remember Set统计信息
         */
        public void printStatistics() {
            System.out.println("=== Remember Set统计信息 ===");
            System.out.println("总Region数: " + rememberSets.size());
            
            int totalReferences = rememberSets.values().stream()
                    .mapToInt(RememberSet::getReferenceCount)
                    .sum();
            System.out.println("总引用数: " + totalReferences);
            
            double avgReferences = rememberSets.isEmpty() ? 0 : 
                    (double) totalReferences / rememberSets.size();
            System.out.println("平均每Region引用数: " + String.format("%.2f", avgReferences));
            
            statistics.printStatistics();
        }
        
        public G1WriteBarrier getWriteBarrier() { return writeBarrier; }
    }
    
    /**
     * G1写屏障实现
     */
    public static class G1WriteBarrier {
        private final RememberSetManager rememberSetManager;
        private final CardTable cardTable;
        
        public G1WriteBarrier(RememberSetManager rememberSetManager) {
            this.rememberSetManager = rememberSetManager;
            this.cardTable = new CardTable();
        }
        
        /**
         * 写屏障 - 在引用赋值时调用
         */
        public void writeBarrier(long objectAddress, int fieldOffset, 
                               long oldReference, long newReference) {
            
            // 1. 处理新引用
            if (newReference != 0) {
                int sourceRegion = getRegionId(objectAddress);
                int targetRegion = getRegionId(newReference);
                
                if (sourceRegion != targetRegion) {
                    // 跨Region引用，记录到Remember Set
                    rememberSetManager.recordCrossRegionReference(
                            sourceRegion, objectAddress,
                            targetRegion, newReference,
                            ReferenceType.FIELD_REFERENCE);
                    
                    // 标记Card为脏
                    cardTable.markCardDirty(objectAddress);
                }
            }
            
            // 2. 处理旧引用（如果需要精确维护）
            if (oldReference != 0) {
                int sourceRegion = getRegionId(objectAddress);
                int oldTargetRegion = getRegionId(oldReference);
                
                if (sourceRegion != oldTargetRegion) {
                    // 移除旧的跨Region引用
                    rememberSetManager.removeCrossRegionReference(
                            sourceRegion, objectAddress,
                            oldTargetRegion, oldReference,
                            ReferenceType.FIELD_REFERENCE);
                }
            }
        }
        
        /**
         * 数组写屏障
         */
        public void arrayWriteBarrier(long arrayAddress, int index, 
                                    long oldElement, long newElement) {
            if (newElement != 0) {
                int sourceRegion = getRegionId(arrayAddress);
                int targetRegion = getRegionId(newElement);
                
                if (sourceRegion != targetRegion) {
                    long elementAddress = arrayAddress + index * 8; // 假设8字节引用
                    
                    rememberSetManager.recordCrossRegionReference(
                            sourceRegion, elementAddress,
                            targetRegion, newElement,
                            ReferenceType.ARRAY_REFERENCE);
                    
                    cardTable.markCardDirty(arrayAddress);
                }
            }
        }
        
        private int getRegionId(long address) {
            // 基于4MB Region大小计算Region ID
            return (int) (address / (4 * 1024 * 1024));
        }
    }
    
    /**
     * Card Table实现
     */
    public static class CardTable {
        private static final int CARD_SIZE = 512; // 512字节per card
        private final Map<Long, CardState> cards = new ConcurrentHashMap<>();
        
        public enum CardState {
            CLEAN,      // 干净
            DIRTY,      // 脏（包含跨Region引用）
            PROCESSED   // 已处理
        }
        
        /**
         * 标记Card为脏
         */
        public void markCardDirty(long address) {
            long cardIndex = address / CARD_SIZE;
            cards.put(cardIndex, CardState.DIRTY);
        }
        
        /**
         * 获取脏Card列表
         */
        public Set<Long> getDirtyCards() {
            return cards.entrySet().stream()
                    .filter(entry -> entry.getValue() == CardState.DIRTY)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
        }
        
        /**
         * 清理Card
         */
        public void cleanCard(long cardIndex) {
            cards.put(cardIndex, CardState.CLEAN);
        }
        
        /**
         * 标记Card为已处理
         */
        public void markCardProcessed(long cardIndex) {
            cards.put(cardIndex, CardState.PROCESSED);
        }
        
        /**
         * 扫描脏Card并更新Remember Set
         */
        public void scanDirtyCards(RememberSetManager rsManager) {
            Set<Long> dirtyCards = getDirtyCards();
            
            System.out.println("扫描 " + dirtyCards.size() + " 个脏Card");
            
            for (Long cardIndex : dirtyCards) {
                scanCard(cardIndex, rsManager);
                markCardProcessed(cardIndex);
            }
        }
        
        private void scanCard(long cardIndex, RememberSetManager rsManager) {
            // 模拟扫描Card中的对象引用
            long cardStart = cardIndex * CARD_SIZE;
            long cardEnd = cardStart + CARD_SIZE;
            
            // 扫描Card范围内的所有对象
            for (long addr = cardStart; addr < cardEnd; addr += 64) { // 假设64字节对象
                scanObjectReferences(addr, rsManager);
            }
        }
        
        private void scanObjectReferences(long objectAddress, RememberSetManager rsManager) {
            // 模拟扫描对象的引用字段
            int sourceRegion = (int) (objectAddress / (4 * 1024 * 1024));
            
            // 假设对象有2个引用字段
            for (int i = 0; i < 2; i++) {
                long referenceAddress = objectAddress + 1000 + i * 100; // 模拟引用地址
                int targetRegion = (int) (referenceAddress / (4 * 1024 * 1024));
                
                if (sourceRegion != targetRegion) {
                    rsManager.recordCrossRegionReference(
                            sourceRegion, objectAddress,
                            targetRegion, referenceAddress,
                            ReferenceType.FIELD_REFERENCE);
                }
            }
        }
    }
    
    /**
     * Remember Set统计信息
     */
    public static class RememberSetStatistics {
        private final AtomicInteger fieldReferences = new AtomicInteger(0);
        private final AtomicInteger arrayReferences = new AtomicInteger(0);
        private final AtomicInteger staticReferences = new AtomicInteger(0);
        
        public void recordReference(ReferenceType type) {
            switch (type) {
                case FIELD_REFERENCE:
                    fieldReferences.incrementAndGet();
                    break;
                case ARRAY_REFERENCE:
                    arrayReferences.incrementAndGet();
                    break;
                case STATIC_REFERENCE:
                    staticReferences.incrementAndGet();
                    break;
            }
        }
        
        public void printStatistics() {
            System.out.println("引用类型统计:");
            System.out.println("- 字段引用: " + fieldReferences.get());
            System.out.println("- 数组引用: " + arrayReferences.get());
            System.out.println("- 静态引用: " + staticReferences.get());
            System.out.println("- 总引用数: " + getTotalReferences());
        }
        
        public int getTotalReferences() {
            return fieldReferences.get() + arrayReferences.get() + staticReferences.get();
        }
    }
    
    /**
     * Remember Set测试和演示
     */
    public static class RememberSetDemo {
        
        public static void demonstrateRememberSet() {
            System.out.println("=== G1 Remember Set演示 ===");
            
            RememberSetManager rsManager = new RememberSetManager();
            
            // 模拟跨Region引用
            simulateCrossRegionReferences(rsManager);
            
            // 演示GC时的Remember Set使用
            demonstrateGCUsage(rsManager);
            
            // 打印统计信息
            rsManager.printStatistics();
        }
        
        private static void simulateCrossRegionReferences(RememberSetManager rsManager) {
            System.out.println("\n=== 模拟跨Region引用 ===");
            
            G1WriteBarrier writeBarrier = rsManager.getWriteBarrier();
            
            // 模拟对象引用赋值
            long obj1 = 0x1000000; // Region 0中的对象
            long obj2 = 0x1400000; // Region 1中的对象
            long obj3 = 0x1800000; // Region 1中的对象
            
            // obj1.field = obj2 (跨Region引用)
            writeBarrier.writeBarrier(obj1, 8, 0, obj2);
            
            // obj2.field = obj3 (同Region引用，不会记录)
            writeBarrier.writeBarrier(obj2, 8, 0, obj3);
            
            // 数组引用
            long array = 0x1000100; // Region 0中的数组
            long element = 0x1C00000; // Region 1中的对象
            writeBarrier.arrayWriteBarrier(array, 0, 0, element);
        }
        
        private static void demonstrateGCUsage(RememberSetManager rsManager) {
            System.out.println("\n=== 演示GC中的Remember Set使用 ===");
            
            // 假设要回收Region 1
            Set<Integer> collectionSet = Set.of(1);
            
            // 获取需要扫描的Region
            Set<Integer> regionsToScan = rsManager.getRegionsToScan(collectionSet);
            
            System.out.println("回收集合: " + collectionSet);
            System.out.println("需要扫描的Region: " + regionsToScan);
            
            // 模拟扫描过程
            for (Integer regionId : regionsToScan) {
                RememberSet rs = rsManager.getOrCreateRememberSet(regionId);
                System.out.println("扫描Region " + regionId + 
                                 " (传出引用数: " + rs.getOutgoingReferences().size() + ")");
            }
        }
    }
}