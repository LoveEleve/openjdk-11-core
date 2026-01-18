import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.concurrent.atomic.*;

/**
 * 基于OpenJDK11字符串源码的性能问题复现
 * 模拟真实的字符串和正则表达式性能陷阱
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:+UseStringDeduplication
 */
public class StringPerformanceTest {
    
    private static final AtomicLong operationCount = new AtomicLong(0);
    private static final Set<String> stringPool = ConcurrentHashMap.newKeySet();
    private static volatile boolean running = true;
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== 字符串性能问题测试开始 ===");
        System.out.println("JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:+UseStringDeduplication");
        
        startPerformanceMonitor();
        
        // 并发执行各种字符串性能问题场景
        ExecutorService executor = Executors.newFixedThreadPool(4);
        
        // 场景1：String.matches()性能陷阱
        executor.submit(() -> testStringMatches());
        
        // 场景2：String.intern()滥用
        executor.submit(() -> testStringIntern());
        
        // 场景3：低效的字符串拼接
        executor.submit(() -> testStringConcatenation());
        
        // 场景4：正则表达式编译性能问题
        executor.submit(() -> testRegexCompilation());
        
        Thread.sleep(60000);
        running = false;
        executor.shutdown();
        System.out.println("测试完成");
    }
    
    /**
     * 场景1：String.matches()性能陷阱
     * 每次调用都会重新编译正则表达式
     */
    private static void testStringMatches() {
        String[] testStrings = generateTestStrings(500);
        String complexRegex = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        
        System.out.println("开始测试String.matches()性能陷阱...");
        
        while (running) {
            for (String str : testStrings) {
                // 性能陷阱：每次都重新编译正则表达式
                boolean matches = str.matches(complexRegex);
                operationCount.incrementAndGet();
            }
            
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * 场景2：String.intern()滥用导致性能问题
     */
    private static void testStringIntern() {
        Random random = new Random();
        
        System.out.println("开始测试String.intern()滥用...");
        
        while (running) {
            for (int i = 0; i < 200; i++) {
                // 动态生成字符串并intern
                String str = "dynamic_string_" + random.nextInt(5000);
                String interned = str.intern(); // 大量intern调用
                stringPool.add(interned);
                operationCount.incrementAndGet();
            }
            
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * 场景3：低效的字符串拼接
     */
    private static void testStringConcatenation() {
        System.out.println("开始测试低效字符串拼接...");
        
        while (running) {
            // 错误方式：在循环中使用String拼接
            String result = "";
            for (int i = 0; i < 500; i++) {
                result += "item_" + i + ","; // 每次都创建新String对象
            }
            
            operationCount.addAndGet(500);
            
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * 场景4：正则表达式重复编译
     */
    private static void testRegexCompilation() {
        String[] patterns = {
            "\\d{3}-\\d{2}-\\d{4}",
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        };
        
        String[] testData = generateTestStrings(50);
        
        System.out.println("开始测试正则表达式重复编译...");
        
        while (running) {
            for (String pattern : patterns) {
                for (String data : testData) {
                    // 性能陷阱：每次都重新编译Pattern
                    Pattern p = Pattern.compile(pattern);
                    Matcher m = p.matcher(data);
                    boolean found = m.find();
                    operationCount.incrementAndGet();
                }
            }
            
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    private static String[] generateTestStrings(int count) {
        String[] strings = new String[count];
        Random random = new Random();
        
        for (int i = 0; i < count; i++) {
            if (i % 3 == 0) {
                strings[i] = "user" + i + "@example.com";
            } else if (i % 3 == 1) {
                strings[i] = "invalid-email-" + i;
            } else {
                strings[i] = random.nextInt(1000) + "-" + random.nextInt(100) + "-" + random.nextInt(10000);
            }
        }
        
        return strings;
    }
    
    private static void startPerformanceMonitor() {
        Thread monitor = new Thread(() -> {
            long lastCount = 0;
            long lastTime = System.currentTimeMillis();
            
            while (running) {
                try {
                    Thread.sleep(5000);
                    
                    long currentCount = operationCount.get();
                    long currentTime = System.currentTimeMillis();
                    
                    long countDiff = currentCount - lastCount;
                    long timeDiff = currentTime - lastTime;
                    
                    double ops = (double) countDiff * 1000 / timeDiff;
                    
                    Runtime runtime = Runtime.getRuntime();
                    long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
                    
                    System.out.printf("[性能监控] 操作数: %d, OPS: %.2f, 内存: %dMB, 字符串池: %d%n",
                        currentCount, ops, usedMemory, stringPool.size());
                    
                    // 性能问题检测
                    if (ops < 5000 && currentCount > 10000) {
                        System.out.println("⚠️  检测到字符串操作性能瓶颈，OPS过低");
                    }
                    
                    if (stringPool.size() > 10000) {
                        System.out.println("⚠️  字符串池过大，可能存在intern滥用");
                    }
                    
                    if (usedMemory > 4000) {
                        System.out.println("⚠️  内存使用过高，可能存在字符串内存泄漏");
                    }
                    
                    lastCount = currentCount;
                    lastTime = currentTime;
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        monitor.setDaemon(true);
        monitor.setName("StringPerformanceMonitor");
        monitor.start();
    }
}