/**
 * 第03章：类加载机制测试程序
 * 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 配置
 * 
 * 功能：
 * 1. 验证类加载器层次结构
 * 2. 测试类加载五阶段
 * 3. 观察Metaspace内存使用
 * 4. 分析类加载性能
 */

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ClassLoadingTest {
    
    // 静态变量 - 准备阶段设置默认值，初始化阶段设置实际值
    private static final String STATIC_STRING = "Hello ClassLoading";
    private static final int STATIC_INT = 42;
    private static final List<String> STATIC_LIST = new ArrayList<>();
    
    // 静态初始化块 - 初始化阶段执行
    static {
        System.out.println("=== ClassLoadingTest 静态初始化开始 ===");
        STATIC_LIST.add("Item1");
        STATIC_LIST.add("Item2");
        System.out.println("静态变量初始化完成: " + STATIC_STRING);
        System.out.println("=== ClassLoadingTest 静态初始化结束 ===");
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("第03章：类加载机制验证测试");
        System.out.println("JVM配置: -Xms=Xmx=8GB, G1GC");
        System.out.println("========================================\n");
        
        // 1. 显示类加载器层次结构
        testClassLoaderHierarchy();
        
        // 2. 测试类加载五阶段
        testClassLoadingPhases();
        
        // 3. 测试动态类加载
        testDynamicClassLoading();
        
        // 4. 测试自定义类加载器
        testCustomClassLoader();
        
        // 5. 内存使用分析
        analyzeMemoryUsage();
        
        // 6. 性能测试
        performanceTest();
        
        System.out.println("\n========================================");
        System.out.println("类加载机制验证完成");
        System.out.println("========================================");
    }
    
    /**
     * 1. 测试类加载器层次结构
     */
    private static void testClassLoaderHierarchy() {
        System.out.println("=== 1. 类加载器层次结构验证 ===");
        
        // 获取当前类的类加载器
        ClassLoader currentLoader = ClassLoadingTest.class.getClassLoader();
        System.out.println("当前类加载器: " + currentLoader);
        System.out.println("类加载器类型: " + currentLoader.getClass().getName());
        
        // 遍历类加载器层次
        ClassLoader loader = currentLoader;
        int level = 0;
        while (loader != null) {
            System.out.println("层次 " + level + ": " + loader.getClass().getName());
            System.out.println("  toString: " + loader);
            loader = loader.getParent();
            level++;
        }
        System.out.println("层次 " + level + ": Bootstrap ClassLoader (null)");
        
        // 测试系统类的类加载器
        System.out.println("\n系统类的类加载器:");
        System.out.println("String.class: " + String.class.getClassLoader());
        System.out.println("ArrayList.class: " + ArrayList.class.getClassLoader());
        
        System.out.println();
    }
    
    /**
     * 2. 测试类加载五阶段
     */
    private static void testClassLoadingPhases() throws Exception {
        System.out.println("=== 2. 类加载五阶段验证 ===");
        
        // 动态加载一个新类来观察加载过程
        String className = "TestClass_" + System.currentTimeMillis();
        
        System.out.println("准备加载类: " + className);
        
        // 使用反射触发类加载
        try {
            // 这会触发类加载的完整过程
            Class<?> clazz = Class.forName("java.util.concurrent.ConcurrentHashMap");
            System.out.println("成功加载类: " + clazz.getName());
            System.out.println("类加载器: " + clazz.getClassLoader());
            System.out.println("父类: " + clazz.getSuperclass());
            System.out.println("接口数量: " + clazz.getInterfaces().length);
            
            // 触发类初始化
            Object instance = clazz.getDeclaredConstructor().newInstance();
            System.out.println("创建实例: " + instance.getClass().getName());
            
        } catch (Exception e) {
            System.out.println("类加载失败: " + e.getMessage());
        }
        
        System.out.println();
    }
    
    /**
     * 3. 测试动态类加载
     */
    private static void testDynamicClassLoading() throws Exception {
        System.out.println("=== 3. 动态类加载测试 ===");
        
        // 加载多个不同的类
        String[] classNames = {
            "java.util.HashMap",
            "java.util.TreeMap", 
            "java.util.LinkedHashMap",
            "java.util.concurrent.ConcurrentSkipListMap",
            "java.lang.StringBuilder",
            "java.math.BigDecimal"
        };
        
        long startTime = System.nanoTime();
        
        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                System.out.println("加载类: " + clazz.getSimpleName() + 
                                 " (加载器: " + clazz.getClassLoader() + ")");
                
                // 创建实例以触发完整初始化
                if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    System.out.println("  实例化成功: " + instance.getClass().getSimpleName());
                }
                
            } catch (Exception e) {
                System.out.println("  加载失败: " + className + " - " + e.getMessage());
            }
        }
        
        long endTime = System.nanoTime();
        System.out.println("动态加载耗时: " + (endTime - startTime) / 1_000_000 + " ms");
        System.out.println();
    }
    
    /**
     * 4. 测试自定义类加载器
     */
    private static void testCustomClassLoader() throws Exception {
        System.out.println("=== 4. 自定义类加载器测试 ===");
        
        // 创建自定义类加载器
        CustomClassLoader customLoader = new CustomClassLoader();
        
        // 使用自定义类加载器加载类
        try {
            Class<?> clazz = customLoader.loadClass("java.util.ArrayList");
            System.out.println("自定义加载器加载类: " + clazz.getName());
            System.out.println("实际类加载器: " + clazz.getClassLoader());
            
            // 验证双亲委派模型
            ClassLoader expectedLoader = ClassLoadingTest.class.getClassLoader();
            System.out.println("预期类加载器: " + expectedLoader);
            System.out.println("双亲委派验证: " + (clazz.getClassLoader() != customLoader));
            
        } catch (Exception e) {
            System.out.println("自定义类加载器测试失败: " + e.getMessage());
        }
        
        System.out.println();
    }
    
    /**
     * 5. 内存使用分析
     */
    private static void analyzeMemoryUsage() {
        System.out.println("=== 5. 内存使用分析 ===");
        
        // 获取内存使用情况
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        System.out.println("堆内存使用情况:");
        System.out.println("  最大内存: " + formatBytes(maxMemory));
        System.out.println("  总内存: " + formatBytes(totalMemory));
        System.out.println("  已使用: " + formatBytes(usedMemory));
        System.out.println("  空闲内存: " + formatBytes(freeMemory));
        System.out.println("  使用率: " + String.format("%.2f%%", 
                          (double) usedMemory / totalMemory * 100));
        
        // 获取Metaspace信息（通过MXBean）
        try {
            java.lang.management.MemoryMXBean memoryBean = 
                java.lang.management.ManagementFactory.getMemoryMXBean();
            
            java.lang.management.MemoryUsage metaspaceUsage = 
                memoryBean.getNonHeapMemoryUsage();
            
            System.out.println("\nMetaspace使用情况:");
            System.out.println("  已使用: " + formatBytes(metaspaceUsage.getUsed()));
            System.out.println("  已提交: " + formatBytes(metaspaceUsage.getCommitted()));
            System.out.println("  最大值: " + formatBytes(metaspaceUsage.getMax()));
            
        } catch (Exception e) {
            System.out.println("无法获取Metaspace信息: " + e.getMessage());
        }
        
        System.out.println();
    }
    
    /**
     * 6. 性能测试
     */
    private static void performanceTest() throws Exception {
        System.out.println("=== 6. 类加载性能测试 ===");
        
        // 测试Class.forName性能
        String[] testClasses = {
            "java.util.Vector",
            "java.util.Stack", 
            "java.util.Properties",
            "java.text.SimpleDateFormat",
            "java.net.URL",
            "java.io.BufferedReader"
        };
        
        // 预热
        for (int i = 0; i < 100; i++) {
            Class.forName("java.lang.Object");
        }
        
        // 性能测试
        long startTime = System.nanoTime();
        int iterations = 1000;
        
        for (int i = 0; i < iterations; i++) {
            for (String className : testClasses) {
                Class.forName(className);
            }
        }
        
        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        
        System.out.println("性能测试结果:");
        System.out.println("  测试类数量: " + testClasses.length);
        System.out.println("  迭代次数: " + iterations);
        System.out.println("  总耗时: " + totalTime / 1_000_000 + " ms");
        System.out.println("  平均每次: " + totalTime / (iterations * testClasses.length) + " ns");
        System.out.println("  每秒加载: " + String.format("%.0f", 
                          (double) (iterations * testClasses.length) / (totalTime / 1_000_000_000.0)));
        
        System.out.println();
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
     * 自定义类加载器
     */
    static class CustomClassLoader extends ClassLoader {
        
        public CustomClassLoader() {
            super(ClassLoadingTest.class.getClassLoader());
        }
        
        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            System.out.println("CustomClassLoader.loadClass: " + name);
            
            // 委派给父类加载器（双亲委派模型）
            return super.loadClass(name);
        }
        
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            System.out.println("CustomClassLoader.findClass: " + name);
            
            // 实际的类查找逻辑
            throw new ClassNotFoundException("Cannot find class: " + name);
        }
    }
    
    /**
     * 测试类 - 用于观察类加载过程
     */
    static class TestClass {
        private static final String TEST_FIELD = "test";
        
        static {
            System.out.println("TestClass 静态初始化块执行");
        }
        
        public TestClass() {
            System.out.println("TestClass 构造方法执行");
        }
        
        public void testMethod() {
            System.out.println("TestClass.testMethod() 执行");
        }
    }
}