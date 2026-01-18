package com.arthas.asm.advanced;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高级ASM字节码转换器
 * 
 * 实现功能：
 * 1. 异步方法监控和上下文传播
 * 2. 复杂AOP切面编程
 * 3. 性能热点检测和优化
 * 4. 动态代码注入和热更新
 * 5. 内存泄漏检测
 * 6. 线程安全分析
 */
public class AdvancedASMTransformer implements ClassFileTransformer {
    
    // 转换统计
    private static final AtomicLong TRANSFORM_COUNT = new AtomicLong(0);
    private static final ConcurrentHashMap<String, TransformStats> TRANSFORM_STATS = 
        new ConcurrentHashMap<>();
    
    // 配置选项
    private final TransformConfig config;
    
    public AdvancedASMTransformer(TransformConfig config) {
        this.config = config;
    }
    
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                          ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        
        // 跳过系统类和不需要转换的类
        if (shouldSkipTransform(className)) {
            return null;
        }
        
        long startTime = System.nanoTime();
        
        try {
            // 解析类文件
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, 
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            
            // 创建转换访问器链
            ClassVisitor visitor = createTransformChain(writer, className);
            
            // 执行转换
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            
            byte[] transformedBytes = writer.toByteArray();
            
            // 记录转换统计
            recordTransformStats(className, startTime, transformedBytes.length, 
                               classfileBuffer.length, true);
            
            // 如果启用了调试，输出转换后的字节码
            if (config.isDebugEnabled()) {
                debugTransformedClass(className, transformedBytes);
            }
            
            return transformedBytes;
            
        } catch (Exception e) {
            // 记录转换失败
            recordTransformStats(className, startTime, 0, classfileBuffer.length, false);
            
            System.err.println("转换类失败: " + className + ", 错误: " + e.getMessage());
            if (config.isDebugEnabled()) {
                e.printStackTrace();
            }
            
            return null; // 返回null表示不转换
        }
    }
    
    /**
     * 判断是否应该跳过转换
     */
    private boolean shouldSkipTransform(String className) {
        if (className == null) return true;
        
        // 跳过系统类
        if (className.startsWith("java/") || 
            className.startsWith("javax/") ||
            className.startsWith("sun/") ||
            className.startsWith("com/sun/") ||
            className.startsWith("org/objectweb/asm/")) {
            return true;
        }
        
        // 检查包含/排除规则
        return !config.shouldTransform(className);
    }
    
    /**
     * 创建转换访问器链
     */
    private ClassVisitor createTransformChain(ClassWriter writer, String className) {
        ClassVisitor visitor = writer;
        
        // 1. 异步监控增强
        if (config.isAsyncMonitoringEnabled()) {
            visitor = new AsyncMonitoringClassVisitor(visitor, className);
        }
        
        // 2. 性能监控增强
        if (config.isPerformanceMonitoringEnabled()) {
            visitor = new PerformanceMonitoringClassVisitor(visitor, className);
        }
        
        // 3. 内存监控增强
        if (config.isMemoryMonitoringEnabled()) {
            visitor = new MemoryMonitoringClassVisitor(visitor, className);
        }
        
        // 4. 线程安全分析增强
        if (config.isThreadSafetyAnalysisEnabled()) {
            visitor = new ThreadSafetyAnalysisClassVisitor(visitor, className);
        }
        
        // 5. 自定义AOP增强
        if (config.isCustomAopEnabled()) {
            visitor = new CustomAopClassVisitor(visitor, className, config.getAopRules());
        }
        
        return visitor;
    }
    
    /**
     * 记录转换统计
     */
    private void recordTransformStats(String className, long startTime, 
                                    int transformedSize, int originalSize, boolean success) {
        long elapsedTime = System.nanoTime() - startTime;
        
        TRANSFORM_COUNT.incrementAndGet();
        
        TRANSFORM_STATS.computeIfAbsent(className, k -> new TransformStats())
                      .recordTransform(elapsedTime, transformedSize, originalSize, success);
    }
    
    /**
     * 调试转换后的类
     */
    private void debugTransformedClass(String className, byte[] transformedBytes) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            
            ClassReader reader = new ClassReader(transformedBytes);
            TraceClassVisitor tracer = new TraceClassVisitor(pw);
            reader.accept(tracer, 0);
            
            System.out.println("=== 转换后的类: " + className + " ===");
            System.out.println(sw.toString());
            System.out.println("=== 转换结束 ===\n");
            
        } catch (Exception e) {
            System.err.println("调试输出失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取转换统计信息
     */
    public static TransformStatistics getTransformStatistics() {
        return new TransformStatistics(TRANSFORM_COUNT.get(), TRANSFORM_STATS);
    }
    
    /**
     * 异步监控类访问器
     */
    private static class AsyncMonitoringClassVisitor extends ClassVisitor {
        private final String className;
        
        public AsyncMonitoringClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            // 检查是否是异步方法（返回CompletableFuture或包含Async关键字）
            if (isAsyncMethod(name, descriptor)) {
                return new AsyncMonitoringMethodVisitor(mv, access, name, descriptor, className);
            }
            
            return mv;
        }
        
        private boolean isAsyncMethod(String methodName, String descriptor) {
            // 检查返回类型是否是CompletableFuture
            if (descriptor.contains("Ljava/util/concurrent/CompletableFuture;")) {
                return true;
            }
            
            // 检查方法名是否包含Async
            if (methodName.toLowerCase().contains("async")) {
                return true;
            }
            
            return false;
        }
    }
    
    /**
     * 异步监控方法访问器
     */
    private static class AsyncMonitoringMethodVisitor extends AdviceAdapter {
        private final String className;
        private final String methodName;
        private int contextVar;
        
        public AsyncMonitoringMethodVisitor(MethodVisitor mv, int access, String name, 
                                          String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.className = className;
            this.methodName = name;
        }
        
        @Override
        protected void onMethodEnter() {
            // 创建异步上下文
            // AsyncContext context = AsyncContext.create(methodName);
            
            mv.visitLdcInsn(className + "." + methodName);
            mv.visitMethodInsn(INVOKESTATIC, 
                "com/arthas/async/demo/AsyncContext", 
                "create", 
                "(Ljava/lang/String;)Lcom/arthas/async/demo/AsyncContext;", 
                false);
            
            contextVar = newLocal(Type.getType("Lcom/arthas/async/demo/AsyncContext;"));
            mv.visitVarInsn(ASTORE, contextVar);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // 完成异步上下文
            // context.complete();
            
            mv.visitVarInsn(ALOAD, contextVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, 
                "com/arthas/async/demo/AsyncContext", 
                "complete", 
                "()V", 
                false);
        }
        
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 2, maxLocals);
        }
    }
    
    /**
     * 性能监控类访问器
     */
    private static class PerformanceMonitoringClassVisitor extends ClassVisitor {
        private final String className;
        
        public PerformanceMonitoringClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            // 为所有public方法添加性能监控
            if ((access & Opcodes.ACC_PUBLIC) != 0 && !name.equals("<init>") && !name.equals("<clinit>")) {
                return new PerformanceMonitoringMethodVisitor(mv, access, name, descriptor, className);
            }
            
            return mv;
        }
    }
    
    /**
     * 性能监控方法访问器
     */
    private static class PerformanceMonitoringMethodVisitor extends AdviceAdapter {
        private final String className;
        private final String methodName;
        private int startTimeVar;
        
        public PerformanceMonitoringMethodVisitor(MethodVisitor mv, int access, String name, 
                                                String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.className = className;
            this.methodName = name;
        }
        
        @Override
        protected void onMethodEnter() {
            // long startTime = System.nanoTime();
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            startTimeVar = newLocal(Type.LONG_TYPE);
            mv.visitVarInsn(LSTORE, startTimeVar);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // long elapsedTime = System.nanoTime() - startTime;
            // PerformanceMonitor.recordExecution(className, methodName, elapsedTime);
            
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);
            
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitInsn(DUP2_X2);
            mv.visitInsn(POP2);
            
            mv.visitMethodInsn(INVOKESTATIC, 
                "com/arthas/performance/PerformanceMonitor", 
                "recordExecution", 
                "(Ljava/lang/String;Ljava/lang/String;J)V", 
                false);
        }
        
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 4, maxLocals);
        }
    }
    
    /**
     * 内存监控类访问器
     */
    private static class MemoryMonitoringClassVisitor extends ClassVisitor {
        private final String className;
        
        public MemoryMonitoringClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            // 监控构造函数和可能导致内存泄漏的方法
            if (name.equals("<init>") || isMemorySensitiveMethod(name)) {
                return new MemoryMonitoringMethodVisitor(mv, access, name, descriptor, className);
            }
            
            return mv;
        }
        
        private boolean isMemorySensitiveMethod(String methodName) {
            String lowerName = methodName.toLowerCase();
            return lowerName.contains("create") || 
                   lowerName.contains("new") || 
                   lowerName.contains("allocate") ||
                   lowerName.contains("cache") ||
                   lowerName.contains("pool");
        }
    }
    
    /**
     * 内存监控方法访问器
     */
    private static class MemoryMonitoringMethodVisitor extends AdviceAdapter {
        private final String className;
        private final String methodName;
        
        public MemoryMonitoringMethodVisitor(MethodVisitor mv, int access, String name, 
                                           String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.className = className;
            this.methodName = name;
        }
        
        @Override
        protected void onMethodEnter() {
            // MemoryMonitor.recordAllocation(className, methodName);
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitMethodInsn(INVOKESTATIC, 
                "com/arthas/memory/MemoryMonitor", 
                "recordAllocation", 
                "(Ljava/lang/String;Ljava/lang/String;)V", 
                false);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // MemoryMonitor.recordDeallocation(className, methodName);
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitMethodInsn(INVOKESTATIC, 
                "com/arthas/memory/MemoryMonitor", 
                "recordDeallocation", 
                "(Ljava/lang/String;Ljava/lang/String;)V", 
                false);
        }
        
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 2, maxLocals);
        }
    }
    
    /**
     * 线程安全分析类访问器
     */
    private static class ThreadSafetyAnalysisClassVisitor extends ClassVisitor {
        private final String className;
        private final Set<String> sharedFields = new HashSet<>();
        
        public ThreadSafetyAnalysisClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }
        
        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                     String signature, Object value) {
            // 检测共享字段
            if ((access & Opcodes.ACC_STATIC) != 0 || 
                (access & Opcodes.ACC_VOLATILE) == 0) {
                sharedFields.add(name);
            }
            
            return super.visitField(access, name, descriptor, signature, value);
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            // 分析可能的线程安全问题
            if (!isThreadSafeMethod(access, name)) {
                return new ThreadSafetyAnalysisMethodVisitor(mv, access, name, descriptor, 
                                                           className, sharedFields);
            }
            
            return mv;
        }
        
        private boolean isThreadSafeMethod(int access, String methodName) {
            return (access & Opcodes.ACC_SYNCHRONIZED) != 0 ||
                   methodName.equals("<init>") ||
                   methodName.equals("<clinit>");
        }
    }
    
    /**
     * 线程安全分析方法访问器
     */
    private static class ThreadSafetyAnalysisMethodVisitor extends MethodVisitor {
        private final String className;
        private final String methodName;
        private final Set<String> sharedFields;
        
        public ThreadSafetyAnalysisMethodVisitor(MethodVisitor mv, int access, String name, 
                                               String descriptor, String className, 
                                               Set<String> sharedFields) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.methodName = name;
            this.sharedFields = sharedFields;
        }
        
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // 检测对共享字段的访问
            if ((opcode == PUTFIELD || opcode == PUTSTATIC) && sharedFields.contains(name)) {
                // ThreadSafetyAnalyzer.recordUnsafeAccess(className, methodName, fieldName);
                mv.visitLdcInsn(className);
                mv.visitLdcInsn(methodName);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(INVOKESTATIC, 
                    "com/arthas/threadsafety/ThreadSafetyAnalyzer", 
                    "recordUnsafeAccess", 
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", 
                    false);
            }
            
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
    
    /**
     * 自定义AOP类访问器
     */
    private static class CustomAopClassVisitor extends ClassVisitor {
        private final String className;
        private final List<AopRule> aopRules;
        
        public CustomAopClassVisitor(ClassVisitor cv, String className, List<AopRule> aopRules) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.aopRules = aopRules;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            // 检查是否有匹配的AOP规则
            List<AopRule> matchingRules = aopRules.stream()
                .filter(rule -> rule.matches(className, name, descriptor))
                .collect(java.util.stream.Collectors.toList());
            
            if (!matchingRules.isEmpty()) {
                return new CustomAopMethodVisitor(mv, access, name, descriptor, 
                                                className, matchingRules);
            }
            
            return mv;
        }
    }
    
    /**
     * 自定义AOP方法访问器
     */
    private static class CustomAopMethodVisitor extends AdviceAdapter {
        private final String className;
        private final String methodName;
        private final List<AopRule> aopRules;
        
        public CustomAopMethodVisitor(MethodVisitor mv, int access, String name, 
                                    String descriptor, String className, List<AopRule> aopRules) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.className = className;
            this.methodName = name;
            this.aopRules = aopRules;
        }
        
        @Override
        protected void onMethodEnter() {
            for (AopRule rule : aopRules) {
                if (rule.hasBeforeAdvice()) {
                    // 调用before advice
                    mv.visitLdcInsn(className);
                    mv.visitLdcInsn(methodName);
                    mv.visitLdcInsn(rule.getRuleName());
                    mv.visitMethodInsn(INVOKESTATIC, 
                        rule.getAdviceClass(), 
                        rule.getBeforeMethod(), 
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", 
                        false);
                }
            }
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            for (AopRule rule : aopRules) {
                if (rule.hasAfterAdvice()) {
                    // 调用after advice
                    mv.visitLdcInsn(className);
                    mv.visitLdcInsn(methodName);
                    mv.visitLdcInsn(rule.getRuleName());
                    mv.visitMethodInsn(INVOKESTATIC, 
                        rule.getAdviceClass(), 
                        rule.getAfterMethod(), 
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", 
                        false);
                }
            }
        }
        
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 3, maxLocals);
        }
    }
    
    /**
     * 转换统计数据
     */
    private static class TransformStats {
        private final AtomicLong transformCount = new AtomicLong(0);
        private final AtomicLong totalTransformTime = new AtomicLong(0);
        private final AtomicLong totalOriginalSize = new AtomicLong(0);
        private final AtomicLong totalTransformedSize = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        
        public void recordTransform(long elapsedTime, int transformedSize, 
                                  int originalSize, boolean success) {
            transformCount.incrementAndGet();
            totalTransformTime.addAndGet(elapsedTime);
            totalOriginalSize.addAndGet(originalSize);
            totalTransformedSize.addAndGet(transformedSize);
            
            if (success) {
                successCount.incrementAndGet();
            }
        }
        
        public long getTransformCount() { return transformCount.get(); }
        public double getAverageTransformTime() {
            long count = transformCount.get();
            return count > 0 ? (double) totalTransformTime.get() / count / 1_000_000.0 : 0;
        }
        public double getAverageOriginalSize() {
            long count = transformCount.get();
            return count > 0 ? (double) totalOriginalSize.get() / count : 0;
        }
        public double getAverageTransformedSize() {
            long count = transformCount.get();
            return count > 0 ? (double) totalTransformedSize.get() / count : 0;
        }
        public double getSuccessRate() {
            long count = transformCount.get();
            return count > 0 ? (double) successCount.get() / count : 0;
        }
        public double getSizeIncrease() {
            long original = totalOriginalSize.get();
            long transformed = totalTransformedSize.get();
            return original > 0 ? ((double) transformed - original) / original * 100 : 0;
        }
    }
    
    /**
     * 转换统计信息
     */
    public static class TransformStatistics {
        private final long totalTransforms;
        private final Map<String, TransformStats> classStats;
        
        public TransformStatistics(long totalTransforms, Map<String, TransformStats> classStats) {
            this.totalTransforms = totalTransforms;
            this.classStats = new HashMap<>(classStats);
        }
        
        public long getTotalTransforms() { return totalTransforms; }
        
        public double getOverallAverageTime() {
            return classStats.values().stream()
                           .mapToDouble(TransformStats::getAverageTransformTime)
                           .average()
                           .orElse(0.0);
        }
        
        public double getOverallSuccessRate() {
            return classStats.values().stream()
                           .mapToDouble(TransformStats::getSuccessRate)
                           .average()
                           .orElse(0.0);
        }
        
        public double getOverallSizeIncrease() {
            return classStats.values().stream()
                           .mapToDouble(TransformStats::getSizeIncrease)
                           .average()
                           .orElse(0.0);
        }
        
        public Map<String, TransformStats> getClassStats() {
            return new HashMap<>(classStats);
        }
        
        @Override
        public String toString() {
            return String.format(
                "TransformStatistics{totalTransforms=%d, avgTime=%.2fms, " +
                "successRate=%.2f%%, sizeIncrease=%.2f%%}",
                totalTransforms, getOverallAverageTime(), 
                getOverallSuccessRate() * 100, getOverallSizeIncrease()
            );
        }
    }
}

/**
 * 转换配置
 */
class TransformConfig {
    private boolean asyncMonitoringEnabled = true;
    private boolean performanceMonitoringEnabled = true;
    private boolean memoryMonitoringEnabled = true;
    private boolean threadSafetyAnalysisEnabled = true;
    private boolean customAopEnabled = true;
    private boolean debugEnabled = false;
    
    private final Set<String> includePackages = new HashSet<>();
    private final Set<String> excludePackages = new HashSet<>();
    private final List<AopRule> aopRules = new ArrayList<>();
    
    // 构造函数和配置方法
    public TransformConfig() {
        // 默认包含用户代码包
        includePackages.add("com/example/");
        includePackages.add("com/arthas/");
        
        // 默认排除测试包
        excludePackages.add("test/");
        excludePackages.add("junit/");
    }
    
    public boolean shouldTransform(String className) {
        // 检查排除规则
        for (String exclude : excludePackages) {
            if (className.startsWith(exclude)) {
                return false;
            }
        }
        
        // 检查包含规则
        if (includePackages.isEmpty()) {
            return true; // 如果没有包含规则，默认包含所有
        }
        
        for (String include : includePackages) {
            if (className.startsWith(include)) {
                return true;
            }
        }
        
        return false;
    }
    
    // Getter和Setter方法
    public boolean isAsyncMonitoringEnabled() { return asyncMonitoringEnabled; }
    public void setAsyncMonitoringEnabled(boolean enabled) { this.asyncMonitoringEnabled = enabled; }
    
    public boolean isPerformanceMonitoringEnabled() { return performanceMonitoringEnabled; }
    public void setPerformanceMonitoringEnabled(boolean enabled) { this.performanceMonitoringEnabled = enabled; }
    
    public boolean isMemoryMonitoringEnabled() { return memoryMonitoringEnabled; }
    public void setMemoryMonitoringEnabled(boolean enabled) { this.memoryMonitoringEnabled = enabled; }
    
    public boolean isThreadSafetyAnalysisEnabled() { return threadSafetyAnalysisEnabled; }
    public void setThreadSafetyAnalysisEnabled(boolean enabled) { this.threadSafetyAnalysisEnabled = enabled; }
    
    public boolean isCustomAopEnabled() { return customAopEnabled; }
    public void setCustomAopEnabled(boolean enabled) { this.customAopEnabled = enabled; }
    
    public boolean isDebugEnabled() { return debugEnabled; }
    public void setDebugEnabled(boolean enabled) { this.debugEnabled = enabled; }
    
    public Set<String> getIncludePackages() { return new HashSet<>(includePackages); }
    public void addIncludePackage(String packageName) { includePackages.add(packageName); }
    
    public Set<String> getExcludePackages() { return new HashSet<>(excludePackages); }
    public void addExcludePackage(String packageName) { excludePackages.add(packageName); }
    
    public List<AopRule> getAopRules() { return new ArrayList<>(aopRules); }
    public void addAopRule(AopRule rule) { aopRules.add(rule); }
}

/**
 * AOP规则
 */
class AopRule {
    private final String ruleName;
    private final String classPattern;
    private final String methodPattern;
    private final String descriptorPattern;
    private final String adviceClass;
    private final String beforeMethod;
    private final String afterMethod;
    
    public AopRule(String ruleName, String classPattern, String methodPattern, 
                   String descriptorPattern, String adviceClass, 
                   String beforeMethod, String afterMethod) {
        this.ruleName = ruleName;
        this.classPattern = classPattern;
        this.methodPattern = methodPattern;
        this.descriptorPattern = descriptorPattern;
        this.adviceClass = adviceClass;
        this.beforeMethod = beforeMethod;
        this.afterMethod = afterMethod;
    }
    
    public boolean matches(String className, String methodName, String descriptor) {
        return matchesPattern(className, classPattern) &&
               matchesPattern(methodName, methodPattern) &&
               (descriptorPattern == null || matchesPattern(descriptor, descriptorPattern));
    }
    
    private boolean matchesPattern(String value, String pattern) {
        if (pattern == null || pattern.equals("*")) {
            return true;
        }
        
        // 简单的通配符匹配
        if (pattern.contains("*")) {
            String regex = pattern.replace("*", ".*");
            return value.matches(regex);
        }
        
        return value.equals(pattern);
    }
    
    public boolean hasBeforeAdvice() { return beforeMethod != null; }
    public boolean hasAfterAdvice() { return afterMethod != null; }
    
    // Getter方法
    public String getRuleName() { return ruleName; }
    public String getClassPattern() { return classPattern; }
    public String getMethodPattern() { return methodPattern; }
    public String getDescriptorPattern() { return descriptorPattern; }
    public String getAdviceClass() { return adviceClass; }
    public String getBeforeMethod() { return beforeMethod; }
    public String getAfterMethod() { return afterMethod; }
}