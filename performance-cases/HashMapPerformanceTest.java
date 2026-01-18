import java.util.*;
import java.lang.reflect.Field;

/**
 * HashMap性能问题验证程序
 * 基于OpenJDK11真实源码验证哈希冲突性能问题
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintGC
 */
public class HashMapPerformanceTest {
    
    public static void main(String[] args) {
        System.out.println("=== HashMap性能问题验证 ===");
        System.out.println("JVM: " + System.getProperty("java.vm.name"));
        System.out.println("版本: " + System.getProperty("java.version"));
        System.out.println("最大内存: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        
        testBadHashCode();
        testGoodHashCode();
        
        System.out.println("\n=== 并发性能测试 ===");
        testConcurrentPerformance();
    }
    
    // 测试坏的hashCode实现
    static void testBadHashCode() {
        System.out.println("\n=== 测试坏的hashCode实现 ===");
        Map<BadKey, String> badMap = new HashMap<>();
        
        // 插入10万条数据
        System.out.println("插入100,000条数据...");
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100000; i++) {
            badMap.put(new BadKey("key_" + i), "value_" + i);
        }
        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println("插入耗时: " + insertTime + "ms");
        
        // 查询测试
        System.out.println("执行1000次查询...");
        startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            badMap.get(new BadKey("key_" + (i * 100)));
        }
        long queryTime = System.currentTimeMillis() - startTime;
        System.out.println("查询1000次耗时: " + queryTime + "ms");
        System.out.println("平均每次查询: " + (queryTime / 1000.0) + "ms");
        
        // 分析HashMap内部结构
        analyzeHashMapStructure(badMap, "坏hashCode的HashMap");
    }
    
    // 测试正确的hashCode实现
    static void testGoodHashCode() {
        System.out.println("\n=== 测试正确的hashCode实现 ===");
        Map<GoodKey, String> goodMap = new HashMap<>();
        
        // 插入10万条数据
        System.out.println("插入100,000条数据...");
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100000; i++) {
            goodMap.put(new GoodKey("key_" + i), "value_" + i);
        }
        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println("插入耗时: " + insertTime + "ms");
        
        // 查询测试
        System.out.println("执行1000次查询...");
        startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            goodMap.get(new GoodKey("key_" + (i * 100)));
        }
        long queryTime = System.currentTimeMillis() - startTime;
        System.out.println("查询1000次耗时: " + queryTime + "ms");
        System.out.println("平均每次查询: " + (queryTime / 1000.0) + "ms");
        
        analyzeHashMapStructure(goodMap, "正确hashCode的HashMap");
    }
    
    // 并发性能测试
    static void testConcurrentPerformance() {
        final Map<BadKey, String> badMap = Collections.synchronizedMap(new HashMap<>());
        final Map<GoodKey, String> goodMap = Collections.synchronizedMap(new HashMap<>());
        
        // 初始化数据
        for (int i = 0; i < 10000; i++) {
            badMap.put(new BadKey("key_" + i), "value_" + i);
            goodMap.put(new GoodKey("key_" + i), "value_" + i);
        }
        
        int threadCount = 10;
        int queryCount = 1000;
        
        // 测试坏HashMap的并发性能
        System.out.println("测试坏HashMap并发性能 (" + threadCount + "线程, 每线程" + queryCount + "次查询)");
        long badConcurrentTime = testConcurrentQuery(badMap, threadCount, queryCount, true);
        
        // 测试好HashMap的并发性能
        System.out.println("测试好HashMap并发性能 (" + threadCount + "线程, 每线程" + queryCount + "次查询)");
        long goodConcurrentTime = testConcurrentQuery(goodMap, threadCount, queryCount, false);
        
        System.out.println("\n并发性能对比:");
        System.out.println("坏HashMap总耗时: " + badConcurrentTime + "ms");
        System.out.println("好HashMap总耗时: " + goodConcurrentTime + "ms");
        System.out.println("性能差异: " + (badConcurrentTime / (double)goodConcurrentTime) + "倍");
    }
    
    static long testConcurrentQuery(Map<?, String> map, int threadCount, int queryCount, boolean isBad) {
        Thread[] threads = new Thread[threadCount];
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < queryCount; j++) {
                    Object key = isBad ? 
                        new BadKey("key_" + ((threadId * queryCount + j) % 10000)) :
                        new GoodKey("key_" + ((threadId * queryCount + j) % 10000));
                    map.get(key);
                }
            });
            threads[i].start();
        }
        
        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        return System.currentTimeMillis() - startTime;
    }
    
    // 分析HashMap内部结构（使用反射）
    static void analyzeHashMapStructure(Map<?, ?> map, String mapName) {
        try {
            Field tableField = HashMap.class.getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(map);
            
            if (table == null) {
                System.out.println(mapName + " - 表为空");
                return;
            }
            
            int nonEmptyBuckets = 0;
            int maxChainLength = 0;
            int totalNodes = 0;
            int[] chainLengthDistribution = new int[11]; // 0-10+
            
            for (Object node : table) {
                if (node != null) {
                    nonEmptyBuckets++;
                    int chainLength = getChainLength(node);
                    totalNodes += chainLength;
                    maxChainLength = Math.max(maxChainLength, chainLength);
                    
                    // 统计链长分布
                    int index = Math.min(chainLength, 10);
                    chainLengthDistribution[index]++;
                }
            }
            
            System.out.println("\n" + mapName + " 结构分析:");
            System.out.println("  总桶数: " + table.length);
            System.out.println("  非空桶数: " + nonEmptyBuckets);
            System.out.println("  总节点数: " + totalNodes);
            System.out.println("  最大链表长度: " + maxChainLength);
            System.out.println("  平均链表长度: " + (nonEmptyBuckets > 0 ? (double)totalNodes / nonEmptyBuckets : 0));
            System.out.println("  负载因子: " + String.format("%.3f", (double)map.size() / table.length));
            
            System.out.println("  链长分布:");
            for (int i = 0; i < chainLengthDistribution.length; i++) {
                if (chainLengthDistribution[i] > 0) {
                    String label = i == 10 ? "10+" : String.valueOf(i);
                    System.out.println("    长度" + label + ": " + chainLengthDistribution[i] + "个桶");
                }
            }
            
            // 性能评估
            if (maxChainLength > 8) {
                System.out.println("  ⚠️  警告: 存在过长链表，可能影响性能！");
            }
            if (nonEmptyBuckets < table.length * 0.1) {
                System.out.println("  ⚠️  警告: 桶利用率过低，存在哈希分布问题！");
            }
            
        } catch (Exception e) {
            System.out.println("分析失败: " + e.getMessage());
        }
    }
    
    static int getChainLength(Object node) {
        int length = 0;
        try {
            Field nextField = node.getClass().getDeclaredField("next");
            nextField.setAccessible(true);
            
            Object current = node;
            while (current != null) {
                length++;
                current = nextField.get(current);
            }
        } catch (Exception e) {
            return 1;
        }
        return length;
    }
}

// 坏的Key实现 - 所有对象返回相同hashCode
class BadKey {
    private String key;
    
    public BadKey(String key) { 
        this.key = key; 
    }
    
    @Override
    public int hashCode() { 
        return 42;  // 🚨 所有对象相同hashCode，导致哈希冲突
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BadKey badKey = (BadKey) obj;
        return Objects.equals(key, badKey.key);
    }
    
    @Override
    public String toString() {
        return "BadKey{" + key + "}";
    }
}

// 正确的Key实现 - 使用合理的hashCode
class GoodKey {
    private String key;
    
    public GoodKey(String key) { 
        this.key = key; 
    }
    
    @Override
    public int hashCode() { 
        return Objects.hash(key);  // ✅ 正确的hashCode实现
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GoodKey goodKey = (GoodKey) obj;
        return Objects.equals(key, goodKey.key);
    }
    
    @Override
    public String toString() {
        return "GoodKey{" + key + "}";
    }
}