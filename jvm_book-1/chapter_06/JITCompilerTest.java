/**
 * JIT编译器优化测试程序
 * 
 * 测试环境：
 * - JVM配置：-Xms8g -Xmx8g -XX:+UseG1GC
 * - 编译器：分层编译，C1+C2
 * - 系统：非大页，非NUMA
 * 
 * 功能：
 * 1. 验证编译触发机制
 * 2. 测试内联优化效果
 * 3. 验证循环优化
 * 4. 测试逃逸分析
 * 5. 分析去优化机制
 */

import java.util.*;
import java.util.concurrent.*;

public class JITCompilerTest {
    
    // 测试计数器
    private static volatile long globalCounter = 0;
    private static final int WARMUP_ITERATIONS = 20000;
    private static final int TEST_ITERATIONS = 100000;
    
    public static void main(String[] args) {
        System.out.println("=== JIT编译器优化测试 ===");
        System.out.println("JVM版本: " + System.getProperty("java.version"));
        System.out.println("最大堆内存: " + Runtime.getRuntime().maxMemory() / (1024*1024) + "MB");
        System.out.println();
        
        // 1. 编译触发机制测试
        testCompilationTrigger();
        
        // 2. 内联优化测试
        testInliningOptimization();
        
        // 3. 循环优化测试
        testLoopOptimization();
        
        // 4. 逃逸分析测试
        testEscapeAnalysis();
        
        // 5. 去优化测试
        testDeoptimization();
        
        // 6. 性能基准测试
        performanceBenchmark();
        
        System.out.println("=== 测试完成 ===");
    }
    
    /**
     * 测试编译触发机制
     * 验证方法调用计数器和回边计数器的工作
     */
    private static void testCompilationTrigger() {
        System.out.println("1. 编译触发机制测试");
        
        // 简单方法调用测试
        long start = System.nanoTime();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            simpleMethod(i);
        }
        long warmupTime = System.nanoTime() - start;
        
        // 测试编译后性能
        start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            simpleMethod(i);
        }
        long compiledTime = System.nanoTime() - start;
        
        System.out.printf("  预热时间: %.2f ms\n", warmupTime / 1_000_000.0);
        System.out.printf("  编译后时间: %.2f ms\n", compiledTime / 1_000_000.0);
        System.out.printf("  性能提升: %.2fx\n", (double)warmupTime / compiledTime);
        System.out.println();
    }
    
    /**
     * 简单测试方法
     */
    private static int simpleMethod(int x) {
        return x * 2 + 1;
    }
    
    /**
     * 测试内联优化
     * 验证方法内联对性能的影响
     */
    private static void testInliningOptimization() {
        System.out.println("2. 内联优化测试");
        
        // 测试小方法内联
        testSmallMethodInlining();
        
        // 测试虚方法内联
        testVirtualMethodInlining();
        
        // 测试接口方法内联
        testInterfaceMethodInlining();
        
        System.out.println();
    }
    
    /**
     * 小方法内联测试
     */
    private static void testSmallMethodInlining() {
        System.out.println("  小方法内联测试:");
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            inlineableMethod(i);
        }
        
        // 性能测试
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            sum += inlineableMethod(i);
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    内联方法调用时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, sum);
    }
    
    private static int inlineableMethod(int x) {
        return x + 1;  // 小方法，容易被内联
    }
    
    /**
     * 虚方法内联测试
     */
    private static void testVirtualMethodInlining() {
        System.out.println("  虚方法内联测试:");
        
        Shape[] shapes = new Shape[1000];
        // 单态调用 - 容易内联
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = new Circle(i);
        }
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Shape shape : shapes) {
                shape.area();
            }
        }
        
        // 性能测试
        long start = System.nanoTime();
        double totalArea = 0;
        for (int i = 0; i < 1000; i++) {
            for (Shape shape : shapes) {
                totalArea += shape.area();
            }
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    单态虚方法调用时间: %.2f ms (结果: %.2f)\n", 
                         time / 1_000_000.0, totalArea);
        
        // 多态调用 - 难以内联
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = (i % 2 == 0) ? new Circle(i) : new Rectangle(i, i+1);
        }
        
        start = System.nanoTime();
        totalArea = 0;
        for (int i = 0; i < 1000; i++) {
            for (Shape shape : shapes) {
                totalArea += shape.area();
            }
        }
        time = System.nanoTime() - start;
        
        System.out.printf("    多态虚方法调用时间: %.2f ms (结果: %.2f)\n", 
                         time / 1_000_000.0, totalArea);
    }
    
    /**
     * 接口方法内联测试
     */
    private static void testInterfaceMethodInlining() {
        System.out.println("  接口方法内联测试:");
        
        Drawable[] drawables = new Drawable[1000];
        for (int i = 0; i < drawables.length; i++) {
            drawables[i] = new Circle(i);
        }
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Drawable drawable : drawables) {
                drawable.draw();
            }
        }
        
        // 性能测试
        long start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS / 10; i++) {
            for (Drawable drawable : drawables) {
                drawable.draw();
            }
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    接口方法调用时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    /**
     * 测试循环优化
     * 验证循环展开、不变式外提等优化
     */
    private static void testLoopOptimization() {
        System.out.println("3. 循环优化测试");
        
        // 测试循环展开
        testLoopUnrolling();
        
        // 测试循环不变式外提
        testLoopInvariantHoisting();
        
        // 测试循环向量化
        testLoopVectorization();
        
        System.out.println();
    }
    
    /**
     * 循环展开测试
     */
    private static void testLoopUnrolling() {
        System.out.println("  循环展开测试:");
        
        int[] array = new int[10000];
        for (int i = 0; i < array.length; i++) {
            array[i] = i;
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            sumArray(array);
        }
        
        // 性能测试
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < 10000; i++) {
            sum += sumArray(array);
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    数组求和时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, sum);
    }
    
    private static long sumArray(int[] array) {
        long sum = 0;
        for (int i = 0; i < array.length; i++) {
            sum += array[i];  // 可能被循环展开优化
        }
        return sum;
    }
    
    /**
     * 循环不变式外提测试
     */
    private static void testLoopInvariantHoisting() {
        System.out.println("  循环不变式外提测试:");
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            loopWithInvariant(1000);
        }
        
        // 性能测试
        long start = System.nanoTime();
        double result = 0;
        for (int i = 0; i < TEST_ITERATIONS / 100; i++) {
            result += loopWithInvariant(1000);
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    不变式外提优化时间: %.2f ms (结果: %.2f)\n", 
                         time / 1_000_000.0, result);
    }
    
    private static double loopWithInvariant(int n) {
        double sum = 0;
        double constant = Math.PI;  // 循环不变式
        for (int i = 0; i < n; i++) {
            sum += i * constant;  // constant应该被外提
        }
        return sum;
    }
    
    /**
     * 循环向量化测试
     */
    private static void testLoopVectorization() {
        System.out.println("  循环向量化测试:");
        
        int[] a = new int[10000];
        int[] b = new int[10000];
        int[] c = new int[10000];
        
        for (int i = 0; i < a.length; i++) {
            a[i] = i;
            b[i] = i * 2;
        }
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            vectorizableLoop(a, b, c);
        }
        
        // 性能测试
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            vectorizableLoop(a, b, c);
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    向量化循环时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    private static void vectorizableLoop(int[] a, int[] b, int[] c) {
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] + b[i];  // 可能被向量化
        }
    }
    
    /**
     * 测试逃逸分析
     * 验证栈上分配和标量替换优化
     */
    private static void testEscapeAnalysis() {
        System.out.println("4. 逃逸分析测试");
        
        // 测试栈上分配
        testStackAllocation();
        
        // 测试标量替换
        testScalarReplacement();
        
        // 测试同步消除
        testSynchronizationElimination();
        
        System.out.println();
    }
    
    /**
     * 栈上分配测试
     */
    private static void testStackAllocation() {
        System.out.println("  栈上分配测试:");
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            noEscapeAllocation();
        }
        
        // 性能测试
        long start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            noEscapeAllocation();
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    不逃逸对象分配时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    private static int noEscapeAllocation() {
        Point p = new Point(1, 2);  // 不逃逸，可能栈上分配
        return p.x + p.y;
    }
    
    /**
     * 标量替换测试
     */
    private static void testScalarReplacement() {
        System.out.println("  标量替换测试:");
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            scalarReplaceable(i);
        }
        
        // 性能测试
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            sum += scalarReplaceable(i);
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    标量替换优化时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, sum);
    }
    
    private static int scalarReplaceable(int x) {
        Point p = new Point(x, x + 1);  // 可能被标量替换
        return p.x * p.y;
    }
    
    /**
     * 同步消除测试
     */
    private static void testSynchronizationElimination() {
        System.out.println("  同步消除测试:");
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            synchronizationElimination();
        }
        
        // 性能测试
        long start = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            synchronizationElimination();
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    同步消除优化时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    private static int synchronizationElimination() {
        Object obj = new Object();  // 不逃逸对象
        synchronized (obj) {        // 同步可能被消除
            return 42;
        }
    }
    
    /**
     * 测试去优化机制
     * 验证类型推测失败时的去优化
     */
    private static void testDeoptimization() {
        System.out.println("5. 去优化测试");
        
        // 测试类型推测去优化
        testTypeSpeculationDeopt();
        
        // 测试数组边界检查去优化
        testBoundsCheckDeopt();
        
        System.out.println();
    }
    
    /**
     * 类型推测去优化测试
     */
    private static void testTypeSpeculationDeopt() {
        System.out.println("  类型推测去优化测试:");
        
        Shape[] shapes = new Shape[1000];
        
        // 阶段1：单态调用，建立类型推测
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = new Circle(i);
        }
        
        // 预热，建立类型推测
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Shape shape : shapes) {
                shape.area();
            }
        }
        
        long start = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < 10000; i++) {
            for (Shape shape : shapes) {
                sum += shape.area();
            }
        }
        long monomorphicTime = System.nanoTime() - start;
        
        // 阶段2：引入多态，触发去优化
        for (int i = 0; i < shapes.length / 2; i++) {
            shapes[i] = new Rectangle(i, i + 1);  // 破坏类型推测
        }
        
        start = System.nanoTime();
        sum = 0;
        for (int i = 0; i < 10000; i++) {
            for (Shape shape : shapes) {
                sum += shape.area();
            }
        }
        long polymorphicTime = System.nanoTime() - start;
        
        System.out.printf("    单态调用时间: %.2f ms\n", monomorphicTime / 1_000_000.0);
        System.out.printf("    多态调用时间: %.2f ms\n", polymorphicTime / 1_000_000.0);
        System.out.printf("    性能下降: %.2fx\n", (double)polymorphicTime / monomorphicTime);
    }
    
    /**
     * 数组边界检查去优化测试
     */
    private static void testBoundsCheckDeopt() {
        System.out.println("  数组边界检查去优化测试:");
        
        int[] array = new int[1000];
        for (int i = 0; i < array.length; i++) {
            array[i] = i;
        }
        
        // 正常访问，边界检查可能被优化掉
        long start = System.nanoTime();
        long sum = 0;
        for (int iter = 0; iter < 10000; iter++) {
            for (int i = 0; i < array.length; i++) {
                sum += array[i];
            }
        }
        long normalTime = System.nanoTime() - start;
        
        System.out.printf("    正常数组访问时间: %.2f ms\n", normalTime / 1_000_000.0);
        
        // 可能触发边界检查异常的访问
        try {
            start = System.nanoTime();
            sum = 0;
            for (int iter = 0; iter < 10000; iter++) {
                for (int i = 0; i <= array.length; i++) {  // 可能越界
                    if (i < array.length) {
                        sum += array[i];
                    }
                }
            }
            long boundsCheckTime = System.nanoTime() - start;
            System.out.printf("    边界检查访问时间: %.2f ms\n", boundsCheckTime / 1_000_000.0);
        } catch (Exception e) {
            System.out.println("    触发数组越界异常");
        }
    }
    
    /**
     * 性能基准测试
     * 综合测试JIT编译器的优化效果
     */
    private static void performanceBenchmark() {
        System.out.println("6. 性能基准测试");
        
        // CPU密集型测试
        testCPUIntensive();
        
        // 内存密集型测试
        testMemoryIntensive();
        
        // 混合负载测试
        testMixedWorkload();
        
        System.out.println();
    }
    
    /**
     * CPU密集型测试
     */
    private static void testCPUIntensive() {
        System.out.println("  CPU密集型测试:");
        
        // 预热
        for (int i = 0; i < 1000; i++) {
            fibonacci(20);
        }
        
        // 性能测试
        long start = System.nanoTime();
        long result = 0;
        for (int i = 0; i < 100000; i++) {
            result += fibonacci(20);
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    斐波那契计算时间: %.2f ms (结果: %d)\n", 
                         time / 1_000_000.0, result);
    }
    
    private static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
    
    /**
     * 内存密集型测试
     */
    private static void testMemoryIntensive() {
        System.out.println("  内存密集型测试:");
        
        List<Object> objects = new ArrayList<>();
        
        long start = System.nanoTime();
        for (int i = 0; i < 1000000; i++) {
            objects.add(new Point(i, i + 1));
            if (i % 100000 == 0) {
                objects.clear();  // 触发GC
            }
        }
        long time = System.nanoTime() - start;
        
        System.out.printf("    对象分配测试时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    /**
     * 混合负载测试
     */
    private static void testMixedWorkload() {
        System.out.println("  混合负载测试:");
        
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        
        long start = System.nanoTime();
        
        // 启动多个线程执行不同类型的任务
        for (int i = 0; i < 4; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 10000; j++) {
                        switch (threadId % 3) {
                            case 0:
                                fibonacci(15);  // CPU密集
                                break;
                            case 1:
                                new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5));  // 内存分配
                                break;
                            case 2:
                                Math.sin(j) * Math.cos(j);  // 数学计算
                                break;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long time = System.nanoTime() - start;
        executor.shutdown();
        
        System.out.printf("    混合负载测试时间: %.2f ms\n", time / 1_000_000.0);
    }
    
    // 辅助类定义
    
    /**
     * 形状接口
     */
    interface Shape {
        double area();
    }
    
    /**
     * 可绘制接口
     */
    interface Drawable {
        void draw();
    }
    
    /**
     * 圆形类
     */
    static class Circle implements Shape, Drawable {
        private final double radius;
        
        public Circle(double radius) {
            this.radius = radius;
        }
        
        @Override
        public double area() {
            return Math.PI * radius * radius;
        }
        
        @Override
        public void draw() {
            globalCounter++;  // 简单操作
        }
    }
    
    /**
     * 矩形类
     */
    static class Rectangle implements Shape {
        private final double width;
        private final double height;
        
        public Rectangle(double width, double height) {
            this.width = width;
            this.height = height;
        }
        
        @Override
        public double area() {
            return width * height;
        }
    }
    
    /**
     * 点类
     */
    static class Point {
        final int x;
        final int y;
        
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}