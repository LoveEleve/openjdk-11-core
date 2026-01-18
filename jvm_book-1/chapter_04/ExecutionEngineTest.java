/**
 * 第04章：字节码执行引擎测试程序
 * 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 配置
 * 
 * 功能：
 * 1. 验证解释器执行机制
 * 2. 触发JIT编译过程
 * 3. 测试OSR机制
 * 4. 分析CodeCache使用
 * 5. 性能对比分析
 */

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

public class ExecutionEngineTest {
    
    // 用于触发编译的热点方法
    private static volatile int globalCounter = 0;
    private static final int COMPILE_THRESHOLD = 10000;
    private static final int OSR_THRESHOLD = 100000;
    
    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("第04章：字节码执行引擎验证测试");
        System.out.println("JVM配置: -Xms=Xmx=8GB, G1GC");
        System.out.println("========================================\n");
        
        // 1. 解释器执行测试
        testInterpreterExecution();
        
        // 2. JIT编译触发测试
        testJITCompilation();
        
        // 3. OSR机制测试
        testOSRMechanism();
        
        // 4. 性能对比测试
        performanceComparison();
        
        // 5. CodeCache分析
        analyzeCodeCache();
        
        // 6. 内联优化测试
        testInlining();
        
        System.out.println("\n========================================");
        System.out.println("字节码执行引擎验证完成");
        System.out.println("========================================");
    }
    
    /**
     * 1. 解释器执行测试
     */
    private static void testInterpreterExecution() {
        System.out.println("=== 1. 解释器执行测试 ===");
        
        // 简单的字节码指令测试
        int a = 10;
        int b = 20;
        int result = simpleArithmetic(a, b);
        
        System.out.println("简单算术运算: " + a + " + " + b + " = " + result);
        
        // 数组操作测试
        int[] array = {1, 2, 3, 4, 5};
        int sum = arraySum(array);
        System.out.println("数组求和: " + sum);
        
        // 对象创建和方法调用
        TestObject obj = new TestObject("test");
        String message = obj.getMessage();
        System.out.println("对象方法调用: " + message);
        
        // 控制流测试
        int factorial = calculateFactorial(5);
        System.out.println("阶乘计算: 5! = " + factorial);
        
        System.out.println();
    }
    
    /**
     * 2. JIT编译触发测试
     */
    private static void testJITCompilation() throws Exception {
        System.out.println("=== 2. JIT编译触发测试 ===");
        
        System.out.println("开始热身，触发JIT编译...");
        
        // 预热阶段 - 触发C1编译
        long startTime = System.nanoTime();
        for (int i = 0; i < COMPILE_THRESHOLD; i++) {
            hotMethod(i);
        }
        long warmupTime = System.nanoTime() - startTime;
        
        System.out.println("预热完成，耗时: " + warmupTime / 1_000_000 + " ms");
        
        // 继续执行 - 可能触发C2编译
        startTime = System.nanoTime();
        for (int i = 0; i < COMPILE_THRESHOLD * 2; i++) {
            hotMethod(i);
        }
        long optimizedTime = System.nanoTime() - startTime;
        
        System.out.println("优化执行，耗时: " + optimizedTime / 1_000_000 + " ms");
        System.out.println("性能提升: " + String.format("%.2fx", 
                          (double) warmupTime / optimizedTime));
        
        // 显示编译信息
        showCompilationInfo();
        
        System.out.println();
    }
    
    /**
     * 3. OSR机制测试
     */
    private static void testOSRMechanism() {
        System.out.println("=== 3. OSR机制测试 ===");
        
        System.out.println("执行长循环以触发OSR编译...");
        
        long startTime = System.nanoTime();
        
        // 长循环触发OSR
        int sum = 0;
        for (int i = 0; i < OSR_THRESHOLD; i++) {
            sum += complexCalculation(i);
            
            // 在循环中间检查是否发生了OSR
            if (i == OSR_THRESHOLD / 2) {
                long midTime = System.nanoTime();
                System.out.println("循环中间点，已执行时间: " + 
                                 (midTime - startTime) / 1_000_000 + " ms");
            }
        }
        
        long endTime = System.nanoTime();
        System.out.println("OSR循环完成，总耗时: " + (endTime - startTime) / 1_000_000 + " ms");
        System.out.println("计算结果: " + sum);
        
        System.out.println();
    }
    
    /**
     * 4. 性能对比测试
     */
    private static void performanceComparison() throws Exception {
        System.out.println("=== 4. 性能对比测试 ===");
        
        // 测试不同执行模式的性能
        int iterations = 1000000;
        
        // 冷启动测试（解释执行）
        long coldStart = measureExecutionTime(() -> {
            for (int i = 0; i < iterations / 10; i++) {
                mathOperations(i);
            }
        });
        
        // 预热后测试（编译执行）
        // 先预热
        for (int i = 0; i < COMPILE_THRESHOLD; i++) {
            mathOperations(i);
        }
        
        long warmStart = measureExecutionTime(() -> {
            for (int i = 0; i < iterations; i++) {
                mathOperations(i);
            }
        });
        
        System.out.println("性能对比结果:");
        System.out.println("  冷启动 (解释执行): " + coldStart + " ms");
        System.out.println("  预热后 (编译执行): " + warmStart + " ms");
        System.out.println("  性能提升: " + String.format("%.2fx", 
                          (double) coldStart * 10 / warmStart));
        
        System.out.println();
    }
    
    /**
     * 5. CodeCache分析
     */
    private static void analyzeCodeCache() {
        System.out.println("=== 5. CodeCache分析 ===");
        
        // 获取编译相关的MXBean
        try {
            CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
            
            if (compilationBean != null) {
                System.out.println("编译器信息:");
                System.out.println("  编译器名称: " + compilationBean.getName());
                System.out.println("  总编译时间: " + compilationBean.getTotalCompilationTime() + " ms");
            }
            
            // 内存使用情况
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            
            System.out.println("\n非堆内存使用 (包含CodeCache):");
            System.out.println("  已使用: " + formatBytes(nonHeapUsage.getUsed()));
            System.out.println("  已提交: " + formatBytes(nonHeapUsage.getCommitted()));
            System.out.println("  最大值: " + formatBytes(nonHeapUsage.getMax()));
            
            // 获取所有内存池信息
            List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
            
            System.out.println("\n内存池详情:");
            for (MemoryPoolMXBean pool : memoryPools) {
                if (pool.getName().contains("Code")) {
                    MemoryUsage usage = pool.getUsage();
                    System.out.println("  " + pool.getName() + ":");
                    System.out.println("    已使用: " + formatBytes(usage.getUsed()));
                    System.out.println("    已提交: " + formatBytes(usage.getCommitted()));
                    System.out.println("    最大值: " + formatBytes(usage.getMax()));
                }
            }
            
        } catch (Exception e) {
            System.out.println("无法获取CodeCache信息: " + e.getMessage());
        }
        
        System.out.println();
    }
    
    /**
     * 6. 内联优化测试
     */
    private static void testInlining() {
        System.out.println("=== 6. 内联优化测试 ===");
        
        // 小方法内联测试
        long startTime = System.nanoTime();
        
        int result = 0;
        for (int i = 0; i < COMPILE_THRESHOLD * 2; i++) {
            result += smallMethod(i) + anotherSmallMethod(i);
        }
        
        long endTime = System.nanoTime();
        
        System.out.println("内联优化测试完成:");
        System.out.println("  执行时间: " + (endTime - startTime) / 1_000_000 + " ms");
        System.out.println("  计算结果: " + result);
        System.out.println("  (小方法应该被内联优化)");
        
        System.out.println();
    }
    
    // ============================================
    // 辅助方法
    // ============================================
    
    /**
     * 简单算术运算
     */
    private static int simpleArithmetic(int a, int b) {
        return a + b * 2 - 1;
    }
    
    /**
     * 数组求和
     */
    private static int arraySum(int[] array) {
        int sum = 0;
        for (int value : array) {
            sum += value;
        }
        return sum;
    }
    
    /**
     * 计算阶乘
     */
    private static int calculateFactorial(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * calculateFactorial(n - 1);
    }
    
    /**
     * 热点方法 - 用于触发JIT编译
     */
    private static int hotMethod(int input) {
        int result = input;
        result = result * 2 + 1;
        result = result ^ (result >> 16);
        result = result * 0x85ebca6b;
        result = result ^ (result >> 13);
        result = result * 0xc2b2ae35;
        result = result ^ (result >> 16);
        globalCounter += result & 0xFF;
        return result;
    }
    
    /**
     * 复杂计算 - 用于OSR测试
     */
    private static int complexCalculation(int input) {
        int result = input;
        
        // 多层嵌套计算
        for (int i = 0; i < 10; i++) {
            result = (result * 31 + i) % 1000000;
        }
        
        // 条件分支
        if (result % 2 == 0) {
            result += input * input;
        } else {
            result -= input / 2;
        }
        
        return result;
    }
    
    /**
     * 数学运算 - 用于性能对比
     */
    private static double mathOperations(int input) {
        double result = input;
        result = Math.sin(result) + Math.cos(result);
        result = Math.sqrt(Math.abs(result));
        result = Math.log(result + 1);
        return result;
    }
    
    /**
     * 小方法 - 用于内联测试
     */
    private static int smallMethod(int x) {
        return x * 2 + 1;
    }
    
    /**
     * 另一个小方法 - 用于内联测试
     */
    private static int anotherSmallMethod(int x) {
        return x * x - x + 1;
    }
    
    /**
     * 测量执行时间
     */
    private static long measureExecutionTime(Runnable task) {
        long startTime = System.nanoTime();
        task.run();
        long endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000; // 转换为毫秒
    }
    
    /**
     * 显示编译信息
     */
    private static void showCompilationInfo() {
        try {
            CompilationMXBean compilationBean = ManagementFactory.getCompilationMXBean();
            if (compilationBean != null && compilationBean.isCompilationTimeMonitoringSupported()) {
                System.out.println("编译统计:");
                System.out.println("  编译器: " + compilationBean.getName());
                System.out.println("  编译时间: " + compilationBean.getTotalCompilationTime() + " ms");
            }
        } catch (Exception e) {
            System.out.println("无法获取编译信息: " + e.getMessage());
        }
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
     * 测试对象类
     */
    static class TestObject {
        private final String message;
        
        public TestObject(String message) {
            this.message = message;
        }
        
        public String getMessage() {
            return "Hello from " + message;
        }
        
        public int calculate(int x, int y) {
            return x * y + message.length();
        }
    }
    
    /**
     * 递归方法测试
     */
    static class RecursiveTest {
        public static long fibonacci(int n) {
            if (n <= 1) {
                return n;
            }
            return fibonacci(n - 1) + fibonacci(n - 2);
        }
        
        public static void testRecursion() {
            System.out.println("递归方法测试:");
            for (int i = 1; i <= 10; i++) {
                long result = fibonacci(i);
                System.out.println("  fibonacci(" + i + ") = " + result);
            }
        }
    }
}