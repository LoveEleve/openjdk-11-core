package com.arthas.async.demo;

import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 异步监控演示程序
 * 
 * 演示内容：
 * 1. CompletableFuture链式调用监控
 * 2. 并行异步任务监控
 * 3. 异步异常处理和堆栈跟踪
 * 4. 异步上下文传播
 * 5. 性能统计和分析
 */
public class AsyncMonitoringDemo {
    
    private static final ExecutorService EXECUTOR = 
        ForkJoinPool.commonPool();
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== 异步监控演示开始 ===");
        
        // 1. 基础异步方法监控
        demonstrateBasicAsyncMonitoring();
        
        // 2. 复杂异步链监控
        demonstrateComplexAsyncChain();
        
        // 3. 并行异步任务监控
        demonstrateParallelAsyncTasks();
        
        // 4. 异步异常监控
        demonstrateAsyncExceptionHandling();
        
        // 5. 性能统计报告
        generatePerformanceReport();
        
        System.out.println("=== 异步监控演示结束 ===");
        
        // 关闭线程池
        EXECUTOR.shutdown();
        EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    /**
     * 基础异步方法监控演示
     */
    public static void demonstrateBasicAsyncMonitoring() {
        System.out.println("\n--- 基础异步方法监控 ---");
        
        CompletableFuture<String> future = fetchUserDataAsync("user123");
        
        future.thenAccept(userData -> {
            System.out.println("用户数据: " + userData);
        }).join();
        
        // 显示监控信息
        AsyncMonitor.printMethodStatistics("fetchUserDataAsync");
    }
    
    /**
     * 模拟异步获取用户数据
     * 注意：这个方法会被字节码增强
     */
    public static CompletableFuture<String> fetchUserDataAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> {
            // 模拟网络延迟
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            
            return "UserData{id=" + userId + ", name=张三, email=zhangsan@example.com}";
        }, EXECUTOR);
    }
    
    /**
     * 复杂异步链监控演示
     */
    public static void demonstrateComplexAsyncChain() {
        System.out.println("\n--- 复杂异步链监控 ---");
        
        CompletableFuture<String> result = processOrderAsync("order456")
            .thenCompose(order -> validateOrderAsync(order))
            .thenCompose(validOrder -> calculatePriceAsync(validOrder))
            .thenCompose(price -> applyDiscountAsync(price))
            .thenApply(finalPrice -> "最终价格: " + finalPrice);
        
        try {
            String finalResult = result.get(5, TimeUnit.SECONDS);
            System.out.println(finalResult);
        } catch (Exception e) {
            System.err.println("异步链执行失败: " + e.getMessage());
        }
        
        // 显示完整的异步链监控信息
        AsyncMonitor.printAsyncChainStatistics();
    }
    
    /**
     * 处理订单 - 异步方法1
     */
    public static CompletableFuture<String> processOrderAsync(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateWork(50);
            return "ProcessedOrder{id=" + orderId + ", status=PROCESSED}";
        }, EXECUTOR);
    }
    
    /**
     * 验证订单 - 异步方法2
     */
    public static CompletableFuture<String> validateOrderAsync(String order) {
        return CompletableFuture.supplyAsync(() -> {
            simulateWork(30);
            return "ValidatedOrder{" + order + ", validated=true}";
        }, EXECUTOR);
    }
    
    /**
     * 计算价格 - 异步方法3
     */
    public static CompletableFuture<Double> calculatePriceAsync(String validOrder) {
        return CompletableFuture.supplyAsync(() -> {
            simulateWork(80);
            return 299.99;
        }, EXECUTOR);
    }
    
    /**
     * 应用折扣 - 异步方法4
     */
    public static CompletableFuture<Double> applyDiscountAsync(Double price) {
        return CompletableFuture.supplyAsync(() -> {
            simulateWork(20);
            return price * 0.9; // 9折
        }, EXECUTOR);
    }
    
    /**
     * 并行异步任务监控演示
     */
    public static void demonstrateParallelAsyncTasks() {
        System.out.println("\n--- 并行异步任务监控 ---");
        
        // 创建多个并行任务
        List<CompletableFuture<String>> futures = IntStream.range(1, 11)
            .mapToObj(i -> processDataAsync("data" + i))
            .collect(Collectors.toList());
        
        // 等待所有任务完成
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        try {
            allTasks.get(10, TimeUnit.SECONDS);
            
            // 收集结果
            List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
            
            System.out.println("并行任务完成，处理了 " + results.size() + " 个数据项");
            
        } catch (Exception e) {
            System.err.println("并行任务执行失败: " + e.getMessage());
        }
        
        // 显示并行任务统计
        AsyncMonitor.printParallelTaskStatistics();
    }
    
    /**
     * 处理数据 - 并行异步方法
     */
    public static CompletableFuture<String> processDataAsync(String data) {
        return CompletableFuture.supplyAsync(() -> {
            simulateWork(ThreadLocalRandom.current().nextInt(50, 200));
            return "Processed: " + data;
        }, EXECUTOR);
    }
    
    /**
     * 异步异常处理监控演示
     */
    public static void demonstrateAsyncExceptionHandling() {
        System.out.println("\n--- 异步异常处理监控 ---");
        
        CompletableFuture<String> future = riskyAsyncOperation("test")
            .handle((result, throwable) -> {
                if (throwable != null) {
                    System.err.println("捕获异步异常: " + throwable.getMessage());
                    
                    // 显示增强的异常堆栈
                    if (throwable instanceof AsyncExecutionException) {
                        AsyncExecutionException asyncEx = (AsyncExecutionException) throwable;
                        System.err.println("异步上下文: " + asyncEx.getAsyncContext().getContextId());
                        System.err.println("调用链: " + asyncEx.getAsyncContext().getCallChain().size() + " 层");
                    }
                    
                    return "异常恢复结果";
                }
                return result;
            });
        
        try {
            String result = future.get(5, TimeUnit.SECONDS);
            System.out.println("异常处理结果: " + result);
        } catch (Exception e) {
            System.err.println("最终异常: " + e.getMessage());
        }
        
        // 显示异常统计
        AsyncMonitor.printExceptionStatistics();
    }
    
    /**
     * 有风险的异步操作
     */
    public static CompletableFuture<String> riskyAsyncOperation(String input) {
        return CompletableFuture.supplyAsync(() -> {
            simulateWork(100);
            
            // 50%概率抛出异常
            if (ThreadLocalRandom.current().nextBoolean()) {
                throw new RuntimeException("模拟异步操作失败: " + input);
            }
            
            return "成功处理: " + input;
        }, EXECUTOR);
    }
    
    /**
     * 生成性能报告
     */
    public static void generatePerformanceReport() {
        System.out.println("\n--- 异步性能报告 ---");
        
        AsyncMonitor.generateDetailedReport();
    }
    
    /**
     * 模拟工作负载
     */
    private static void simulateWork(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

/**
 * 异步监控器 - 收集和分析异步执行统计
 */
class AsyncMonitor {
    
    // 方法执行统计
    private static final ConcurrentHashMap<String, MethodStats> METHOD_STATS = 
        new ConcurrentHashMap<>();
    
    // 异步链统计
    private static final ConcurrentHashMap<String, ChainStats> CHAIN_STATS = 
        new ConcurrentHashMap<>();
    
    // 异常统计
    private static final ConcurrentHashMap<String, ExceptionStats> EXCEPTION_STATS = 
        new ConcurrentHashMap<>();
    
    /**
     * 记录异步完成
     */
    public static void recordAsyncCompletion(String methodName, long elapsedNanos, 
                                           boolean hasException) {
        METHOD_STATS.computeIfAbsent(methodName, k -> new MethodStats())
                   .recordExecution(elapsedNanos, hasException);
    }
    
    /**
     * 记录异步链
     */
    public static void recordAsyncChain(String chainId, List<String> methods, 
                                      long totalTime) {
        CHAIN_STATS.computeIfAbsent(chainId, k -> new ChainStats())
                  .recordChain(methods, totalTime);
    }
    
    /**
     * 记录异常
     */
    public static void recordException(String methodName, Throwable exception) {
        EXCEPTION_STATS.computeIfAbsent(methodName, k -> new ExceptionStats())
                      .recordException(exception);
    }
    
    /**
     * 打印方法统计
     */
    public static void printMethodStatistics(String methodName) {
        MethodStats stats = METHOD_STATS.get(methodName);
        if (stats != null) {
            System.out.printf("方法 %s 统计:\n", methodName);
            System.out.printf("  调用次数: %d\n", stats.getCallCount());
            System.out.printf("  平均耗时: %.2f ms\n", stats.getAverageTime() / 1_000_000.0);
            System.out.printf("  最大耗时: %.2f ms\n", stats.getMaxTime() / 1_000_000.0);
            System.out.printf("  最小耗时: %.2f ms\n", stats.getMinTime() / 1_000_000.0);
            System.out.printf("  异常率: %.2f%%\n", stats.getExceptionRate() * 100);
        }
    }
    
    /**
     * 打印异步链统计
     */
    public static void printAsyncChainStatistics() {
        System.out.println("异步链统计:");
        CHAIN_STATS.forEach((chainId, stats) -> {
            System.out.printf("  链 %s: 平均长度=%.1f, 平均耗时=%.2f ms\n", 
                            chainId, stats.getAverageLength(), 
                            stats.getAverageTime() / 1_000_000.0);
        });
    }
    
    /**
     * 打印并行任务统计
     */
    public static void printParallelTaskStatistics() {
        System.out.println("并行任务统计:");
        METHOD_STATS.entrySet().stream()
                   .filter(entry -> entry.getKey().contains("processDataAsync"))
                   .forEach(entry -> {
                       MethodStats stats = entry.getValue();
                       System.out.printf("  %s: 并发度=%.1f, 吞吐量=%.1f ops/s\n", 
                                       entry.getKey(), 
                                       stats.getConcurrencyLevel(),
                                       stats.getThroughput());
                   });
    }
    
    /**
     * 打印异常统计
     */
    public static void printExceptionStatistics() {
        System.out.println("异常统计:");
        EXCEPTION_STATS.forEach((methodName, stats) -> {
            System.out.printf("  %s: 异常次数=%d, 主要异常=%s\n", 
                            methodName, stats.getExceptionCount(), 
                            stats.getMostCommonException());
        });
    }
    
    /**
     * 生成详细报告
     */
    public static void generateDetailedReport() {
        System.out.println("=== 异步执行详细报告 ===");
        
        // 总体统计
        long totalCalls = METHOD_STATS.values().stream()
                                     .mapToLong(MethodStats::getCallCount)
                                     .sum();
        
        double totalTime = METHOD_STATS.values().stream()
                                      .mapToDouble(MethodStats::getTotalTime)
                                      .sum() / 1_000_000.0;
        
        System.out.printf("总调用次数: %d\n", totalCalls);
        System.out.printf("总执行时间: %.2f ms\n", totalTime);
        System.out.printf("平均响应时间: %.2f ms\n", totalTime / totalCalls);
        
        // 性能排行
        System.out.println("\n性能排行 (按平均响应时间):");
        METHOD_STATS.entrySet().stream()
                   .sorted((e1, e2) -> Double.compare(e2.getValue().getAverageTime(), 
                                                    e1.getValue().getAverageTime()))
                   .limit(5)
                   .forEach(entry -> {
                       System.out.printf("  %s: %.2f ms\n", 
                                       entry.getKey(), 
                                       entry.getValue().getAverageTime() / 1_000_000.0);
                   });
        
        // 异常排行
        System.out.println("\n异常排行:");
        EXCEPTION_STATS.entrySet().stream()
                      .sorted((e1, e2) -> Integer.compare(e2.getValue().getExceptionCount(), 
                                                        e1.getValue().getExceptionCount()))
                      .limit(3)
                      .forEach(entry -> {
                          System.out.printf("  %s: %d 次异常\n", 
                                          entry.getKey(), 
                                          entry.getValue().getExceptionCount());
                      });
    }
    
    /**
     * 方法统计数据
     */
    private static class MethodStats {
        private final AtomicLong callCount = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong exceptionCount = new AtomicLong(0);
        private volatile long maxTime = 0;
        private volatile long minTime = Long.MAX_VALUE;
        private final AtomicLong concurrentCalls = new AtomicLong(0);
        
        public void recordExecution(long elapsedNanos, boolean hasException) {
            callCount.incrementAndGet();
            totalTime.addAndGet(elapsedNanos);
            
            if (hasException) {
                exceptionCount.incrementAndGet();
            }
            
            // 更新最大最小时间
            updateMaxTime(elapsedNanos);
            updateMinTime(elapsedNanos);
        }
        
        private synchronized void updateMaxTime(long time) {
            if (time > maxTime) {
                maxTime = time;
            }
        }
        
        private synchronized void updateMinTime(long time) {
            if (time < minTime) {
                minTime = time;
            }
        }
        
        public long getCallCount() { return callCount.get(); }
        public double getAverageTime() { 
            long calls = callCount.get();
            return calls > 0 ? (double) totalTime.get() / calls : 0;
        }
        public long getMaxTime() { return maxTime; }
        public long getMinTime() { return minTime == Long.MAX_VALUE ? 0 : minTime; }
        public double getExceptionRate() {
            long calls = callCount.get();
            return calls > 0 ? (double) exceptionCount.get() / calls : 0;
        }
        public double getTotalTime() { return totalTime.get(); }
        public double getConcurrencyLevel() { return concurrentCalls.get(); }
        public double getThroughput() {
            double totalSeconds = totalTime.get() / 1_000_000_000.0;
            return totalSeconds > 0 ? callCount.get() / totalSeconds : 0;
        }
    }
    
    /**
     * 异步链统计数据
     */
    private static class ChainStats {
        private final List<Integer> chainLengths = new ArrayList<>();
        private final List<Long> chainTimes = new ArrayList<>();
        
        public synchronized void recordChain(List<String> methods, long totalTime) {
            chainLengths.add(methods.size());
            chainTimes.add(totalTime);
        }
        
        public synchronized double getAverageLength() {
            return chainLengths.stream().mapToInt(Integer::intValue).average().orElse(0);
        }
        
        public synchronized double getAverageTime() {
            return chainTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        }
    }
    
    /**
     * 异常统计数据
     */
    private static class ExceptionStats {
        private final AtomicInteger exceptionCount = new AtomicInteger(0);
        private final ConcurrentHashMap<String, AtomicInteger> exceptionTypes = 
            new ConcurrentHashMap<>();
        
        public void recordException(Throwable exception) {
            exceptionCount.incrementAndGet();
            String exceptionType = exception.getClass().getSimpleName();
            exceptionTypes.computeIfAbsent(exceptionType, k -> new AtomicInteger(0))
                         .incrementAndGet();
        }
        
        public int getExceptionCount() { return exceptionCount.get(); }
        
        public String getMostCommonException() {
            return exceptionTypes.entrySet().stream()
                                .max((e1, e2) -> Integer.compare(e1.getValue().get(), 
                                                               e2.getValue().get()))
                                .map(Map.Entry::getKey)
                                .orElse("无");
        }
    }
}

/**
 * 异步执行异常 - 包含上下文信息
 */
class AsyncExecutionException extends RuntimeException {
    private final AsyncContext asyncContext;
    
    public AsyncExecutionException(String message, Throwable cause, AsyncContext context) {
        super(message, cause);
        this.asyncContext = context;
    }
    
    public AsyncContext getAsyncContext() {
        return asyncContext;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (asyncContext != null) {
            sb.append("\nAsync Context: ").append(asyncContext.getContextId());
            sb.append("\nMethod: ").append(asyncContext.getMethodName());
            sb.append("\nThread: ").append(asyncContext.getThreadName());
            sb.append("\nElapsed: ").append(asyncContext.getElapsedTime() / 1_000_000.0).append(" ms");
            
            List<AsyncContext> chain = asyncContext.getCallChain();
            if (chain.size() > 1) {
                sb.append("\nCall Chain:");
                for (int i = 0; i < chain.size(); i++) {
                    sb.append("\n  ").append(i + 1).append(". ").append(chain.get(i).getMethodName());
                }
            }
        }
        return sb.toString();
    }
}