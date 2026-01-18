/**
 * HelloWorld.java - JVM启动流程分析标准测试程序
 * 
 * 用途：
 * 1. 作为JVM启动流程GDB分析的目标程序
 * 2. 触发JVM完整初始化过程
 * 3. 验证各个子系统的正常工作
 * 
 * 编译：javac HelloWorld.java
 * 运行：java -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld
 * 调试：gdb --batch --command=chapter_01_startup.gdb --args java -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld
 */
public class HelloWorld {
    
    /**
     * 主方法 - JVM启动流程的最终目标
     * 当这个方法开始执行时，说明JVM所有子系统都已成功初始化
     */
    public static void main(String[] args) {
        // 输出启动成功信息
        System.out.println("=== JVM启动成功 ===");
        System.out.println("Hello, OpenJDK 11 World!");
        
        // 输出JVM基本信息
        System.out.println("\n=== JVM运行时信息 ===");
        System.out.println("Java版本: " + System.getProperty("java.version"));
        System.out.println("JVM名称: " + System.getProperty("java.vm.name"));
        System.out.println("JVM版本: " + System.getProperty("java.vm.version"));
        
        // 输出内存信息
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        System.out.println("\n=== 内存配置信息 ===");
        System.out.printf("最大堆内存: %.2f GB\n", maxMemory / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("当前堆内存: %.2f GB\n", totalMemory / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("可用堆内存: %.2f GB\n", freeMemory / 1024.0 / 1024.0 / 1024.0);
        
        // 触发一些基本的JVM操作来验证各子系统工作正常
        testBasicOperations();
        
        System.out.println("\n=== JVM启动流程分析完成 ===");
    }
    
    /**
     * 测试基本JVM操作
     * 验证各个子系统是否正常工作
     */
    private static void testBasicOperations() {
        System.out.println("\n=== 基本操作测试 ===");
        
        // 1. 测试对象创建 (堆内存分配)
        String testString = new String("测试对象创建");
        System.out.println("对象创建测试: " + testString);
        
        // 2. 测试类加载 (类加载器)
        Class<?> stringClass = String.class;
        System.out.println("类加载测试: " + stringClass.getName());
        
        // 3. 测试方法调用 (解释器/JIT编译器)
        int result = fibonacci(10);
        System.out.println("方法调用测试: fibonacci(10) = " + result);
        
        // 4. 测试异常处理 (异常处理机制)
        try {
            int[] array = new int[5];
            array[10] = 100; // 故意触发数组越界异常
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("异常处理测试: 捕获到 " + e.getClass().getSimpleName());
        }
        
        // 5. 测试垃圾回收提示 (G1垃圾收集器)
        System.gc();
        System.out.println("垃圾回收测试: 已调用System.gc()");
    }
    
    /**
     * 斐波那契数列计算 - 用于测试方法调用和可能的JIT编译
     */
    private static int fibonacci(int n) {
        if (n <= 1) {
            return n;
        }
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
}