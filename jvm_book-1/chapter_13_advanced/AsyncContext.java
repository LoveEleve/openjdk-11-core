package com.arthas.async.demo;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步上下文 - 跟踪异步调用链和执行状态
 * 
 * 这个类演示了如何在字节码增强中实现：
 * 1. 异步调用链跟踪
 * 2. 上下文传播
 * 3. 性能监控
 * 4. 异常关联
 */
public class AsyncContext {
    
    // 全局上下文存储
    private static final ConcurrentHashMap<String, AsyncContext> GLOBAL_CONTEXTS = 
        new ConcurrentHashMap<>();
    
    // 线程本地上下文
    private static final ThreadLocal<AsyncContext> THREAD_LOCAL_CONTEXT = 
        new ThreadLocal<>();
    
    // 上下文ID生成器
    private static final AtomicLong CONTEXT_ID_GENERATOR = new AtomicLong(0);
    
    // 上下文基本信息
    private final String contextId;
    private final String methodName;
    private final String threadName;
    private final long startTime;
    private final List<AsyncContext> callChain;
    private final AsyncContext parent;
    
    // 执行状态
    private volatile boolean completed = false;
    private volatile boolean hasException = false;
    private volatile Throwable exception;
    private volatile long endTime;
    
    // 自定义属性
    private final ConcurrentHashMap<String, Object> attributes = 
        new ConcurrentHashMap<>();
    
    /**
     * 构造函数
     */
    public AsyncContext(String methodName, AsyncContext parent) {
        this.contextId = generateContextId();
        this.methodName = methodName;
        this.threadName = Thread.currentThread().getName();
        this.startTime = System.nanoTime();
        this.parent = parent;
        this.callChain = buildCallChain(parent);
        
        // 注册到全局上下文
        GLOBAL_CONTEXTS.put(contextId, this);
    }
    
    /**
     * 生成上下文ID
     */
    private static String generateContextId() {
        return "async-" + CONTEXT_ID_GENERATOR.incrementAndGet() + "-" + 
               UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * 构建调用链
     */
    private List<AsyncContext> buildCallChain(AsyncContext parent) {
        List<AsyncContext> chain = new ArrayList<>();
        if (parent != null) {
            chain.addAll(parent.getCallChain());
        }
        chain.add(this);
        return chain;
    }
    
    /**
     * 创建新的异步上下文
     */
    public static AsyncContext create(String methodName) {
        AsyncContext parent = THREAD_LOCAL_CONTEXT.get();
        AsyncContext context = new AsyncContext(methodName, parent);
        THREAD_LOCAL_CONTEXT.set(context);
        return context;
    }
    
    /**
     * 获取当前上下文
     */
    public static AsyncContext current() {
        return THREAD_LOCAL_CONTEXT.get();
    }
    
    /**
     * 设置当前上下文
     */
    public static void setCurrent(AsyncContext context) {
        if (context != null) {
            THREAD_LOCAL_CONTEXT.set(context);
        } else {
            THREAD_LOCAL_CONTEXT.remove();
        }
    }
    
    /**
     * 完成异步操作
     */
    public void complete() {
        this.endTime = System.nanoTime();
        this.completed = true;
        
        // 记录统计信息
        AsyncMonitor.recordAsyncCompletion(methodName, getElapsedTime(), hasException);
        
        // 如果是调用链的根，记录整个链的统计
        if (parent == null && callChain.size() > 1) {
            List<String> methodNames = callChain.stream()
                .map(AsyncContext::getMethodName)
                .collect(java.util.stream.Collectors.toList());
            AsyncMonitor.recordAsyncChain(contextId, methodNames, getElapsedTime());
        }
        
        // 清理线程本地上下文
        if (THREAD_LOCAL_CONTEXT.get() == this) {
            THREAD_LOCAL_CONTEXT.set(parent);
        }
    }
    
    /**
     * 记录异常
     */
    public void recordException(Throwable throwable) {
        this.hasException = true;
        this.exception = throwable;
        
        // 记录异常统计
        AsyncMonitor.recordException(methodName, throwable);
    }
    
    /**
     * 传播到新线程
     */
    public AsyncContext propagateToNewThread() {
        AsyncContext newContext = new AsyncContext(methodName + "@" + 
                                                 Thread.currentThread().getName(), this);
        return newContext;
    }
    
    /**
     * 设置属性
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
    
    /**
     * 获取属性
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
    
    /**
     * 获取属性，带默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        return (T) attributes.getOrDefault(key, defaultValue);
    }
    
    // Getter方法
    public String getContextId() { return contextId; }
    public String getMethodName() { return methodName; }
    public String getThreadName() { return threadName; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public boolean isCompleted() { return completed; }
    public boolean hasException() { return hasException; }
    public Throwable getException() { return exception; }
    public AsyncContext getParent() { return parent; }
    public List<AsyncContext> getCallChain() { return new ArrayList<>(callChain); }
    
    /**
     * 获取执行时间（纳秒）
     */
    public long getElapsedTime() {
        if (completed) {
            return endTime - startTime;
        } else {
            return System.nanoTime() - startTime;
        }
    }
    
    /**
     * 获取执行时间（毫秒）
     */
    public double getElapsedTimeMs() {
        return getElapsedTime() / 1_000_000.0;
    }
    
    /**
     * 获取调用链深度
     */
    public int getCallDepth() {
        return callChain.size();
    }
    
    /**
     * 是否是根上下文
     */
    public boolean isRoot() {
        return parent == null;
    }
    
    /**
     * 获取根上下文
     */
    public AsyncContext getRoot() {
        AsyncContext root = this;
        while (root.parent != null) {
            root = root.parent;
        }
        return root;
    }
    
    /**
     * 清理全局上下文
     */
    public static void cleanup() {
        GLOBAL_CONTEXTS.clear();
        THREAD_LOCAL_CONTEXT.remove();
    }
    
    /**
     * 获取所有活跃上下文
     */
    public static java.util.Collection<AsyncContext> getAllActiveContexts() {
        return GLOBAL_CONTEXTS.values().stream()
                             .filter(ctx -> !ctx.isCompleted())
                             .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * 获取上下文统计信息
     */
    public static ContextStatistics getStatistics() {
        return new ContextStatistics(GLOBAL_CONTEXTS.values());
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AsyncContext{");
        sb.append("id='").append(contextId).append('\'');
        sb.append(", method='").append(methodName).append('\'');
        sb.append(", thread='").append(threadName).append('\'');
        sb.append(", elapsed=").append(String.format("%.2f", getElapsedTimeMs())).append("ms");
        sb.append(", depth=").append(getCallDepth());
        sb.append(", completed=").append(completed);
        if (hasException) {
            sb.append(", exception=").append(exception.getClass().getSimpleName());
        }
        sb.append('}');
        return sb.toString();
    }
    
    /**
     * 上下文统计信息
     */
    public static class ContextStatistics {
        private final int totalContexts;
        private final int activeContexts;
        private final int completedContexts;
        private final int exceptionContexts;
        private final double averageExecutionTime;
        private final int maxCallDepth;
        
        public ContextStatistics(java.util.Collection<AsyncContext> contexts) {
            this.totalContexts = contexts.size();
            this.activeContexts = (int) contexts.stream().filter(ctx -> !ctx.isCompleted()).count();
            this.completedContexts = (int) contexts.stream().filter(AsyncContext::isCompleted).count();
            this.exceptionContexts = (int) contexts.stream().filter(AsyncContext::hasException).count();
            
            this.averageExecutionTime = contexts.stream()
                .filter(AsyncContext::isCompleted)
                .mapToDouble(AsyncContext::getElapsedTimeMs)
                .average()
                .orElse(0.0);
            
            this.maxCallDepth = contexts.stream()
                .mapToInt(AsyncContext::getCallDepth)
                .max()
                .orElse(0);
        }
        
        // Getter方法
        public int getTotalContexts() { return totalContexts; }
        public int getActiveContexts() { return activeContexts; }
        public int getCompletedContexts() { return completedContexts; }
        public int getExceptionContexts() { return exceptionContexts; }
        public double getAverageExecutionTime() { return averageExecutionTime; }
        public int getMaxCallDepth() { return maxCallDepth; }
        public double getExceptionRate() { 
            return totalContexts > 0 ? (double) exceptionContexts / totalContexts : 0; 
        }
        
        @Override
        public String toString() {
            return String.format(
                "ContextStatistics{total=%d, active=%d, completed=%d, exceptions=%d, " +
                "avgTime=%.2fms, maxDepth=%d, exceptionRate=%.2f%%}",
                totalContexts, activeContexts, completedContexts, exceptionContexts,
                averageExecutionTime, maxCallDepth, getExceptionRate() * 100
            );
        }
    }
}