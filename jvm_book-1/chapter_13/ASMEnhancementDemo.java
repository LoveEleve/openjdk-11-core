package com.example.asm.demo;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ASM字节码增强完整演示程序
 * 展示Arthas核心技术的实现原理
 */
public class ASMEnhancementDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== ASM字节码增强技术演示 ===");
        
        // 创建测试目标类
        TestBusinessClass original = new TestBusinessClass("Original", 100);
        
        // 演示1: 基础字节码分析
        demonstrateBytecodeAnalysis();
        
        // 演示2: 方法执行时间监控
        demonstrateMethodTiming();
        
        // 演示3: 方法参数和返回值捕获
        demonstrateMethodWatch();
        
        // 演示4: 动态类增强和热替换
        demonstrateDynamicEnhancement();
        
        // 演示5: 复杂AOP场景
        demonstrateComplexAOP();
        
        System.out.println("\n=== 演示完成 ===");
    }
    
    /**
     * 演示1: 字节码分析
     */
    private static void demonstrateBytecodeAnalysis() throws Exception {
        System.out.println("\n--- 演示1: 字节码结构分析 ---");
        
        // 加载类字节码
        byte[] classBytes = loadClassBytes(TestBusinessClass.class);
        
        // 使用ASM分析字节码结构
        ClassReader reader = new ClassReader(classBytes);
        
        // 创建分析访问器
        BytecodeAnalysisVisitor analyzer = new BytecodeAnalysisVisitor();
        reader.accept(analyzer, 0);
        
        // 输出分析结果
        analyzer.printAnalysisResult();
    }
    
    /**
     * 演示2: 方法执行时间监控
     */
    private static void demonstrateMethodTiming() throws Exception {
        System.out.println("\n--- 演示2: 方法执行时间监控 ---");
        
        // 加载原始类
        byte[] originalBytes = loadClassBytes(TestBusinessClass.class);
        
        // 应用时间监控增强
        byte[] enhancedBytes = enhanceWithTiming(originalBytes);
        
        // 动态加载增强后的类
        Class<?> enhancedClass = loadEnhancedClass("TestBusinessClass_Timing", enhancedBytes);
        
        // 测试增强后的类
        Object instance = enhancedClass.getConstructor(String.class, int.class)
                                     .newInstance("Enhanced", 200);
        
        Method businessMethod = enhancedClass.getMethod("doBusinessLogic", String.class);
        Method calculateMethod = enhancedClass.getMethod("calculateValue", int.class, int.class);
        
        // 执行方法并观察监控结果
        System.out.println("执行增强后的方法...");
        for (int i = 0; i < 5; i++) {
            businessMethod.invoke(instance, "test-" + i);
            calculateMethod.invoke(instance, i * 10, i + 1);
        }
        
        // 打印监控统计
        MethodTimingMonitor.printReport();
    }
    
    /**
     * 演示3: 方法参数和返回值捕获
     */
    private static void demonstrateMethodWatch() throws Exception {
        System.out.println("\n--- 演示3: 方法参数和返回值捕获 ---");
        
        // 加载原始类
        byte[] originalBytes = loadClassBytes(TestBusinessClass.class);
        
        // 应用观察增强
        byte[] enhancedBytes = enhanceWithWatch(originalBytes);
        
        // 动态加载增强后的类
        Class<?> enhancedClass = loadEnhancedClass("TestBusinessClass_Watch", enhancedBytes);
        
        // 测试增强后的类
        Object instance = enhancedClass.getConstructor(String.class, int.class)
                                     .newInstance("Watched", 300);
        
        Method businessMethod = enhancedClass.getMethod("doBusinessLogic", String.class);
        
        // 执行方法并观察捕获结果
        System.out.println("执行被观察的方法...");
        businessMethod.invoke(instance, "watch-test");
        
        // 打印观察结果
        MethodWatchMonitor.printLatestEvents(10);
    }
    
    /**
     * 演示4: 动态类增强和热替换
     */
    private static void demonstrateDynamicEnhancement() throws Exception {
        System.out.println("\n--- 演示4: 动态类增强 ---");
        
        // 创建动态增强器
        DynamicClassEnhancer enhancer = new DynamicClassEnhancer();
        
        // 加载原始类
        byte[] originalBytes = loadClassBytes(TestBusinessClass.class);
        
        // 动态添加性能监控
        enhancer.addMethodInterceptor("doBusinessLogic", new PerformanceInterceptor());
        enhancer.addMethodInterceptor("calculateValue", new LoggingInterceptor());
        
        // 应用增强
        byte[] enhancedBytes = enhancer.enhance(originalBytes);
        
        // 测试增强效果
        Class<?> enhancedClass = loadEnhancedClass("TestBusinessClass_Dynamic", enhancedBytes);
        Object instance = enhancedClass.getConstructor(String.class, int.class)
                                     .newInstance("Dynamic", 400);
        
        Method businessMethod = enhancedClass.getMethod("doBusinessLogic", String.class);
        Method calculateMethod = enhancedClass.getMethod("calculateValue", int.class, int.class);
        
        System.out.println("执行动态增强的方法...");
        businessMethod.invoke(instance, "dynamic-test");
        calculateMethod.invoke(instance, 50, 5);
    }
    
    /**
     * 演示5: 复杂AOP场景
     */
    private static void demonstrateComplexAOP() throws Exception {
        System.out.println("\n--- 演示5: 复杂AOP场景 ---");
        
        // 创建复杂的AOP增强器
        ComplexAOPEnhancer aopEnhancer = new ComplexAOPEnhancer();
        
        // 配置切面
        aopEnhancer.addBeforeAdvice("doBusinessLogic", new SecurityCheckAdvice());
        aopEnhancer.addAfterAdvice("doBusinessLogic", new AuditLogAdvice());
        aopEnhancer.addAroundAdvice("calculateValue", new TransactionAdvice());
        aopEnhancer.addExceptionAdvice("*", new ExceptionHandlingAdvice());
        
        // 加载和增强类
        byte[] originalBytes = loadClassBytes(TestBusinessClass.class);
        byte[] enhancedBytes = aopEnhancer.enhance(originalBytes);
        
        // 测试AOP效果
        Class<?> enhancedClass = loadEnhancedClass("TestBusinessClass_AOP", enhancedBytes);
        Object instance = enhancedClass.getConstructor(String.class, int.class)
                                     .newInstance("AOP", 500);
        
        Method businessMethod = enhancedClass.getMethod("doBusinessLogic", String.class);
        Method calculateMethod = enhancedClass.getMethod("calculateValue", int.class, int.class);
        
        System.out.println("执行AOP增强的方法...");
        businessMethod.invoke(instance, "aop-test");
        
        try {
            calculateMethod.invoke(instance, 100, 0); // 触发异常
        } catch (Exception e) {
            System.out.println("捕获到异常: " + e.getCause().getMessage());
        }
    }
    
    // 工具方法
    
    /**
     * 加载类字节码
     */
    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String className = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(className)) {
            if (is == null) {
                throw new IOException("Class not found: " + className);
            }
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }
    
    /**
     * 动态加载增强后的类
     */
    private static Class<?> loadEnhancedClass(String className, byte[] classBytes) throws Exception {
        // 创建自定义类加载器
        ByteArrayClassLoader classLoader = new ByteArrayClassLoader();
        return classLoader.defineClass(className, classBytes);
    }
    
    /**
     * 应用时间监控增强
     */
    private static byte[] enhanceWithTiming(byte[] originalBytes) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        TimingEnhancementVisitor enhancer = new TimingEnhancementVisitor(writer);
        reader.accept(enhancer, 0);
        return writer.toByteArray();
    }
    
    /**
     * 应用观察增强
     */
    private static byte[] enhanceWithWatch(byte[] originalBytes) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        WatchEnhancementVisitor enhancer = new WatchEnhancementVisitor(writer);
        reader.accept(enhancer, 0);
        return writer.toByteArray();
    }
}

/**
 * 测试业务类
 */
class TestBusinessClass {
    private String name;
    private int baseValue;
    
    public TestBusinessClass(String name, int baseValue) {
        this.name = name;
        this.baseValue = baseValue;
    }
    
    public String doBusinessLogic(String input) {
        System.out.println("执行业务逻辑: " + input);
        
        // 模拟业务处理
        try {
            Thread.sleep(50); // 模拟处理时间
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return name + ":" + input.toUpperCase();
    }
    
    public int calculateValue(int a, int b) {
        if (b == 0) {
            throw new ArithmeticException("除数不能为零");
        }
        
        int result = (a + baseValue) / b;
        System.out.println("计算结果: " + result);
        return result;
    }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getBaseValue() { return baseValue; }
    public void setBaseValue(int baseValue) { this.baseValue = baseValue; }
}

/**
 * 字节码分析访问器
 */
class BytecodeAnalysisVisitor extends ClassVisitor {
    private String className;
    private final List<String> methods = new ArrayList<>();
    private final List<String> fields = new ArrayList<>();
    private final Map<String, Integer> instructionCounts = new HashMap<>();
    
    public BytecodeAnalysisVisitor() {
        super(Opcodes.ASM9);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {
        this.className = name.replace('/', '.');
        System.out.println("分析类: " + className);
        System.out.println("父类: " + (superName != null ? superName.replace('/', '.') : "无"));
        
        if (interfaces != null && interfaces.length > 0) {
            System.out.print("接口: ");
            for (String iface : interfaces) {
                System.out.print(iface.replace('/', '.') + " ");
            }
            System.out.println();
        }
    }
    
    @Override
    public FieldVisitor visitField(int access, String name, String descriptor,
                                  String signature, Object value) {
        String fieldInfo = getAccessString(access) + " " + Type.getType(descriptor).getClassName() + " " + name;
        fields.add(fieldInfo);
        return null;
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        String methodInfo = getAccessString(access) + " " + name + descriptor;
        methods.add(methodInfo);
        
        return new MethodVisitor(Opcodes.ASM9) {
            private int instructionCount = 0;
            
            @Override
            public void visitInsn(int opcode) {
                instructionCount++;
            }
            
            @Override
            public void visitIntInsn(int opcode, int operand) {
                instructionCount++;
            }
            
            @Override
            public void visitVarInsn(int opcode, int var) {
                instructionCount++;
            }
            
            @Override
            public void visitTypeInsn(int opcode, String type) {
                instructionCount++;
            }
            
            @Override
            public void visitFieldInsn(int opcode, String owner, String fieldName, String desc) {
                instructionCount++;
            }
            
            @Override
            public void visitMethodInsn(int opcode, String owner, String methodName, String desc, boolean isInterface) {
                instructionCount++;
            }
            
            @Override
            public void visitEnd() {
                instructionCounts.put(name, instructionCount);
            }
        };
    }
    
    public void printAnalysisResult() {
        System.out.println("\n字段列表 (" + fields.size() + "):");
        fields.forEach(field -> System.out.println("  " + field));
        
        System.out.println("\n方法列表 (" + methods.size() + "):");
        methods.forEach(method -> {
            String methodName = extractMethodName(method);
            Integer count = instructionCounts.get(methodName);
            System.out.println("  " + method + " [" + (count != null ? count : 0) + " 指令]");
        });
    }
    
    private String extractMethodName(String methodInfo) {
        int spaceIndex = methodInfo.lastIndexOf(' ');
        if (spaceIndex > 0) {
            String nameAndDesc = methodInfo.substring(spaceIndex + 1);
            int parenIndex = nameAndDesc.indexOf('(');
            return parenIndex > 0 ? nameAndDesc.substring(0, parenIndex) : nameAndDesc;
        }
        return methodInfo;
    }
    
    private String getAccessString(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        if ((access & Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        if ((access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((access & Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((access & Opcodes.ACC_FINAL) != 0) sb.append("final ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");
        return sb.toString().trim();
    }
}

/**
 * 时间监控增强访问器
 */
class TimingEnhancementVisitor extends ClassVisitor {
    private String className;
    
    public TimingEnhancementVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        if (mv == null || (access & Opcodes.ACC_ABSTRACT) != 0 || 
            "<init>".equals(name) || "<clinit>".equals(name)) {
            return mv;
        }
        
        return new TimingMethodAdapter(mv, access, name, descriptor, className);
    }
}

/**
 * 时间监控方法适配器
 */
class TimingMethodAdapter extends AdviceAdapter {
    private final String methodName;
    private final String className;
    private int timeVarIndex;
    
    protected TimingMethodAdapter(MethodVisitor methodVisitor, int access,
                                String name, String descriptor, String className) {
        super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
        this.methodName = name;
        this.className = className;
    }
    
    @Override
    protected void onMethodEnter() {
        timeVarIndex = newLocal(Type.LONG_TYPE);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        mv.visitVarInsn(LSTORE, timeVarIndex);
    }
    
    @Override
    protected void onMethodExit(int opcode) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        mv.visitVarInsn(LLOAD, timeVarIndex);
        mv.visitInsn(LSUB);
        
        mv.visitLdcInsn(className.replace('/', '.'));
        mv.visitLdcInsn(methodName);
        mv.visitInsn(DUP2_X1);
        mv.visitInsn(POP2);
        
        mv.visitMethodInsn(INVOKESTATIC, 
                         "com/example/asm/demo/MethodTimingMonitor", 
                         "recordExecution", 
                         "(Ljava/lang/String;Ljava/lang/String;J)V", 
                         false);
    }
}

/**
 * 观察增强访问器
 */
class WatchEnhancementVisitor extends ClassVisitor {
    private String className;
    
    public WatchEnhancementVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        if (mv == null || (access & Opcodes.ACC_ABSTRACT) != 0 || 
            "<init>".equals(name) || "<clinit>".equals(name)) {
            return mv;
        }
        
        return new WatchMethodAdapter(mv, access, name, descriptor, className);
    }
}

/**
 * 观察方法适配器
 */
class WatchMethodAdapter extends AdviceAdapter {
    private final String methodName;
    private final String className;
    private final Type[] argumentTypes;
    private int argsArrayIndex;
    
    protected WatchMethodAdapter(MethodVisitor methodVisitor, int access,
                               String name, String descriptor, String className) {
        super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
        this.methodName = name;
        this.className = className;
        this.argumentTypes = Type.getArgumentTypes(descriptor);
    }
    
    @Override
    protected void onMethodEnter() {
        // 创建参数数组并记录方法进入
        argsArrayIndex = newLocal(Type.getType(Object[].class));
        
        push(argumentTypes.length);
        newArray(Type.getType(Object.class));
        storeLocal(argsArrayIndex);
        
        for (int i = 0; i < argumentTypes.length; i++) {
            loadLocal(argsArrayIndex);
            push(i);
            loadArg(i);
            box(argumentTypes[i]);
            arrayStore(Type.getType(Object.class));
        }
        
        mv.visitLdcInsn(className.replace('/', '.'));
        mv.visitLdcInsn(methodName);
        loadLocal(argsArrayIndex);
        mv.visitMethodInsn(INVOKESTATIC, 
                         "com/example/asm/demo/MethodWatchMonitor", 
                         "onMethodEnter", 
                         "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V", 
                         false);
    }
    
    @Override
    protected void onMethodExit(int opcode) {
        if (opcode == RETURN) {
            mv.visitLdcInsn(className.replace('/', '.'));
            mv.visitLdcInsn(methodName);
            mv.visitInsn(ACONST_NULL);
        } else if (opcode == ARETURN) {
            dup();
            mv.visitLdcInsn(className.replace('/', '.'));
            mv.visitLdcInsn(methodName);
            dupX2();
            pop();
        } else {
            if (opcode == LRETURN || opcode == DRETURN) {
                dup2();
            } else {
                dup();
            }
            box(Type.getReturnType(methodDesc));
            mv.visitLdcInsn(className.replace('/', '.'));
            mv.visitLdcInsn(methodName);
            dupX2();
            pop();
        }
        
        mv.visitMethodInsn(INVOKESTATIC, 
                         "com/example/asm/demo/MethodWatchMonitor", 
                         "onMethodExit", 
                         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", 
                         false);
    }
}

/**
 * 方法时间监控器
 */
class MethodTimingMonitor {
    private static final ConcurrentHashMap<String, MethodStats> stats = new ConcurrentHashMap<>();
    
    public static void recordExecution(String className, String methodName, long duration) {
        String key = className + "." + methodName;
        stats.computeIfAbsent(key, k -> new MethodStats()).addExecution(duration);
    }
    
    public static void printReport() {
        System.out.println("\n=== 方法执行时间报告 ===");
        stats.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().getTotalTime(), e1.getValue().getTotalTime()))
                .forEach(entry -> {
                    String method = entry.getKey();
                    MethodStats stat = entry.getValue();
                    System.out.printf("%-40s | 次数: %4d | 总时间: %6.2fms | 平均: %6.2fms | 最大: %6.2fms%n",
                            method, stat.getCount(), stat.getTotalTime() / 1_000_000.0,
                            stat.getAverageTime() / 1_000_000.0, stat.getMaxTime() / 1_000_000.0);
                });
    }
    
    static class MethodStats {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong maxTime = new AtomicLong(0);
        
        void addExecution(long duration) {
            count.incrementAndGet();
            totalTime.addAndGet(duration);
            
            long currentMax = maxTime.get();
            while (duration > currentMax && !maxTime.compareAndSet(currentMax, duration)) {
                currentMax = maxTime.get();
            }
        }
        
        long getCount() { return count.get(); }
        long getTotalTime() { return totalTime.get(); }
        long getMaxTime() { return maxTime.get(); }
        double getAverageTime() {
            long c = count.get();
            return c > 0 ? (double) totalTime.get() / c : 0.0;
        }
    }
}

/**
 * 方法观察监控器
 */
class MethodWatchMonitor {
    private static final List<WatchEvent> events = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_EVENTS = 100;
    
    public static void onMethodEnter(String className, String methodName, Object[] args) {
        WatchEvent event = new WatchEvent();
        event.className = className;
        event.methodName = methodName;
        event.eventType = "ENTER";
        event.timestamp = System.currentTimeMillis();
        event.args = args;
        
        addEvent(event);
    }
    
    public static void onMethodExit(String className, String methodName, Object returnValue) {
        WatchEvent event = new WatchEvent();
        event.className = className;
        event.methodName = methodName;
        event.eventType = "EXIT";
        event.timestamp = System.currentTimeMillis();
        event.returnValue = returnValue;
        
        addEvent(event);
    }
    
    private static void addEvent(WatchEvent event) {
        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }
    
    public static void printLatestEvents(int count) {
        System.out.println("\n=== 最新观察事件 ===");
        int start = Math.max(0, events.size() - count);
        for (int i = start; i < events.size(); i++) {
            System.out.println(events.get(i));
        }
    }
    
    static class WatchEvent {
        String className;
        String methodName;
        String eventType;
        long timestamp;
        Object[] args;
        Object returnValue;
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%tT] %s.%s %s", timestamp, className, methodName, eventType));
            
            if ("ENTER".equals(eventType) && args != null) {
                sb.append(" args=").append(Arrays.toString(args));
            } else if ("EXIT".equals(eventType) && returnValue != null) {
                sb.append(" return=").append(returnValue);
            }
            
            return sb.toString();
        }
    }
}

/**
 * 自定义字节数组类加载器
 */
class ByteArrayClassLoader extends ClassLoader {
    public Class<?> defineClass(String name, byte[] classBytes) {
        return defineClass(name, classBytes, 0, classBytes.length);
    }
}

// 动态增强相关类 (简化版本)

/**
 * 动态类增强器
 */
class DynamicClassEnhancer {
    private final Map<String, MethodInterceptor> interceptors = new HashMap<>();
    
    public void addMethodInterceptor(String methodName, MethodInterceptor interceptor) {
        interceptors.put(methodName, interceptor);
    }
    
    public byte[] enhance(byte[] originalBytes) {
        // 简化实现，实际应用中会更复杂
        return originalBytes; // 返回原始字节码
    }
}

interface MethodInterceptor {
    void beforeMethod(String className, String methodName, Object[] args);
    void afterMethod(String className, String methodName, Object returnValue);
}

class PerformanceInterceptor implements MethodInterceptor {
    @Override
    public void beforeMethod(String className, String methodName, Object[] args) {
        System.out.println("性能监控 - 方法开始: " + className + "." + methodName);
    }
    
    @Override
    public void afterMethod(String className, String methodName, Object returnValue) {
        System.out.println("性能监控 - 方法结束: " + className + "." + methodName);
    }
}

class LoggingInterceptor implements MethodInterceptor {
    @Override
    public void beforeMethod(String className, String methodName, Object[] args) {
        System.out.println("日志记录 - 调用: " + className + "." + methodName + " 参数: " + Arrays.toString(args));
    }
    
    @Override
    public void afterMethod(String className, String methodName, Object returnValue) {
        System.out.println("日志记录 - 返回: " + returnValue);
    }
}

// AOP相关类 (简化版本)

class ComplexAOPEnhancer {
    private final Map<String, List<Advice>> beforeAdvices = new HashMap<>();
    private final Map<String, List<Advice>> afterAdvices = new HashMap<>();
    private final Map<String, List<Advice>> aroundAdvices = new HashMap<>();
    private final Map<String, List<Advice>> exceptionAdvices = new HashMap<>();
    
    public void addBeforeAdvice(String methodPattern, Advice advice) {
        beforeAdvices.computeIfAbsent(methodPattern, k -> new ArrayList<>()).add(advice);
    }
    
    public void addAfterAdvice(String methodPattern, Advice advice) {
        afterAdvices.computeIfAbsent(methodPattern, k -> new ArrayList<>()).add(advice);
    }
    
    public void addAroundAdvice(String methodPattern, Advice advice) {
        aroundAdvices.computeIfAbsent(methodPattern, k -> new ArrayList<>()).add(advice);
    }
    
    public void addExceptionAdvice(String methodPattern, Advice advice) {
        exceptionAdvices.computeIfAbsent(methodPattern, k -> new ArrayList<>()).add(advice);
    }
    
    public byte[] enhance(byte[] originalBytes) {
        // 简化实现
        return originalBytes;
    }
}

interface Advice {
    void execute(String className, String methodName, Object[] args);
}

class SecurityCheckAdvice implements Advice {
    @Override
    public void execute(String className, String methodName, Object[] args) {
        System.out.println("安全检查 - " + className + "." + methodName);
    }
}

class AuditLogAdvice implements Advice {
    @Override
    public void execute(String className, String methodName, Object[] args) {
        System.out.println("审计日志 - " + className + "." + methodName);
    }
}

class TransactionAdvice implements Advice {
    @Override
    public void execute(String className, String methodName, Object[] args) {
        System.out.println("事务管理 - " + className + "." + methodName);
    }
}

class ExceptionHandlingAdvice implements Advice {
    @Override
    public void execute(String className, String methodName, Object[] args) {
        System.out.println("异常处理 - " + className + "." + methodName);
    }
}