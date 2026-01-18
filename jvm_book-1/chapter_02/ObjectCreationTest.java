/**
 * ObjectCreationTest.java - 对象创建流程GDB验证测试程序
 * 
 * 用途：
 * 1. 触发不同类型对象的创建过程
 * 2. 验证TLAB分配机制
 * 3. 观察对象内存布局
 * 4. 测试压缩指针编码解码
 * 
 * 编译：javac ObjectCreationTest.java
 * 运行：java -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest
 * 调试：gdb --batch --command=chapter_02_object_creation.gdb --args java -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest
 */
public class ObjectCreationTest {
    
    public static void main(String[] args) {
        System.out.println("=== 对象创建流程测试开始 ===");
        
        // 输出JVM内存配置信息
        printMemoryInfo();
        
        // 测试1: 基本对象创建
        testBasicObjectCreation();
        
        // 测试2: 字符串对象创建
        testStringObjectCreation();
        
        // 测试3: 数组对象创建
        testArrayObjectCreation();
        
        // 测试4: 复杂对象创建
        testComplexObjectCreation();
        
        // 测试5: 大对象创建 (可能触发TLAB重填)
        testLargeObjectCreation();
        
        System.out.println("\n=== 对象创建流程测试完成 ===");
    }
    
    /**
     * 输出JVM内存配置信息
     */
    private static void printMemoryInfo() {
        System.out.println("\n=== JVM内存配置信息 ===");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        System.out.printf("最大堆内存: %.2f GB\n", maxMemory / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("当前堆内存: %.2f GB\n", totalMemory / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("可用堆内存: %.2f GB\n", freeMemory / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("已用堆内存: %.2f MB\n", (totalMemory - freeMemory) / 1024.0 / 1024.0);
        
        // 输出GC信息
        System.out.println("垃圾收集器: " + 
            java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .map(gc -> gc.getName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Unknown"));
    }
    
    /**
     * 测试1: 基本对象创建
     * 验证最简单的对象分配流程
     */
    private static void testBasicObjectCreation() {
        System.out.println("\n=== 测试1: 基本对象创建 ===");
        
        // 创建基本Object实例
        Object obj1 = new Object();
        Object obj2 = new Object();
        Object obj3 = new Object();
        
        System.out.println("创建了3个Object实例:");
        System.out.println("obj1: " + obj1 + " (hashCode: " + System.identityHashCode(obj1) + ")");
        System.out.println("obj2: " + obj2 + " (hashCode: " + System.identityHashCode(obj2) + ")");
        System.out.println("obj3: " + obj3 + " (hashCode: " + System.identityHashCode(obj3) + ")");
        
        // 验证对象大小 (通过创建大量对象观察内存变化)
        Runtime runtime = Runtime.getRuntime();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
        
        Object[] objects = new Object[1000];
        for (int i = 0; i < 1000; i++) {
            objects[i] = new Object();
        }
        
        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = afterMemory - beforeMemory;
        
        System.out.printf("1000个Object实例内存使用: %d bytes (平均%.1f bytes/对象)\n", 
                         memoryUsed, memoryUsed / 1000.0);
    }
    
    /**
     * 测试2: 字符串对象创建
     * 验证String对象的分配和字符数组的创建
     */
    private static void testStringObjectCreation() {
        System.out.println("\n=== 测试2: 字符串对象创建 ===");
        
        // 创建不同类型的字符串
        String str1 = new String("Hello World");           // 显式创建
        String str2 = "Hello World";                       // 字符串常量池
        String str3 = new String(new char[]{'T', 'e', 's', 't'}); // 从字符数组创建
        
        System.out.println("创建了3个String实例:");
        System.out.println("str1: \"" + str1 + "\" (new String)");
        System.out.println("str2: \"" + str2 + "\" (字符串字面量)");
        System.out.println("str3: \"" + str3 + "\" (从字符数组)");
        
        // 验证字符串相等性
        System.out.println("str1 == str2: " + (str1 == str2));         // false (不同对象)
        System.out.println("str1.equals(str2): " + str1.equals(str2)); // true (内容相同)
        
        // 创建大字符串测试
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("A");
        }
        String largeString = sb.toString();
        System.out.println("大字符串长度: " + largeString.length() + " 字符");
    }
    
    /**
     * 测试3: 数组对象创建
     * 验证不同类型数组的分配
     */
    private static void testArrayObjectCreation() {
        System.out.println("\n=== 测试3: 数组对象创建 ===");
        
        // 创建不同类型的数组
        int[] intArray = new int[100];                    // int数组
        String[] stringArray = new String[50];            // 对象引用数组
        double[] doubleArray = new double[200];           // double数组
        Object[][] multiArray = new Object[10][20];       // 多维数组
        
        System.out.println("创建了4种类型的数组:");
        System.out.printf("intArray: %d个int元素 (约%d bytes)\n", 
                         intArray.length, intArray.length * 4 + 16);
        System.out.printf("stringArray: %d个String引用 (约%d bytes)\n", 
                         stringArray.length, stringArray.length * 4 + 16);
        System.out.printf("doubleArray: %d个double元素 (约%d bytes)\n", 
                         doubleArray.length, doubleArray.length * 8 + 16);
        System.out.printf("multiArray: %dx%d多维数组\n", 
                         multiArray.length, multiArray[0].length);
        
        // 填充数组数据
        for (int i = 0; i < intArray.length; i++) {
            intArray[i] = i * i;
        }
        
        for (int i = 0; i < stringArray.length; i++) {
            stringArray[i] = "String_" + i;
        }
        
        System.out.println("数组填充完成");
        System.out.println("intArray[99] = " + intArray[99]);
        System.out.println("stringArray[49] = " + stringArray[49]);
    }
    
    /**
     * 测试4: 复杂对象创建
     * 验证包含多种字段类型的对象分配
     */
    private static void testComplexObjectCreation() {
        System.out.println("\n=== 测试4: 复杂对象创建 ===");
        
        // 创建复杂对象实例
        ComplexObject obj1 = new ComplexObject();
        ComplexObject obj2 = new ComplexObject("Custom", 12345);
        
        System.out.println("创建了2个ComplexObject实例:");
        System.out.println("obj1: " + obj1);
        System.out.println("obj2: " + obj2);
        
        // 修改对象状态
        obj1.setName("Modified");
        obj1.setValue(99999);
        obj1.setActive(false);
        
        System.out.println("修改后的obj1: " + obj1);
        
        // 创建对象数组
        ComplexObject[] complexArray = new ComplexObject[10];
        for (int i = 0; i < complexArray.length; i++) {
            complexArray[i] = new ComplexObject("Object_" + i, i * 100);
        }
        
        System.out.println("创建了包含10个ComplexObject的数组");
    }
    
    /**
     * 测试5: 大对象创建
     * 可能触发TLAB重填或直接在Eden区分配
     */
    private static void testLargeObjectCreation() {
        System.out.println("\n=== 测试5: 大对象创建 ===");
        
        // 创建大数组 (可能超出TLAB大小)
        int[] largeArray1 = new int[100000];    // ~400KB
        double[] largeArray2 = new double[50000]; // ~400KB
        String[] largeStringArray = new String[10000]; // 引用数组
        
        System.out.println("创建了3个大数组:");
        System.out.printf("largeArray1: %d个int (约%.1f KB)\n", 
                         largeArray1.length, (largeArray1.length * 4 + 16) / 1024.0);
        System.out.printf("largeArray2: %d个double (约%.1f KB)\n", 
                         largeArray2.length, (largeArray2.length * 8 + 16) / 1024.0);
        System.out.printf("largeStringArray: %d个引用 (约%.1f KB)\n", 
                         largeStringArray.length, (largeStringArray.length * 4 + 16) / 1024.0);
        
        // 填充大数组
        for (int i = 0; i < largeArray1.length; i++) {
            largeArray1[i] = i;
        }
        
        for (int i = 0; i < largeStringArray.length; i++) {
            largeStringArray[i] = "LargeString_" + i;
        }
        
        System.out.println("大数组填充完成");
        
        // 触发一次GC来观察对象存活情况
        System.gc();
        System.out.println("手动触发GC完成");
        
        // 输出最终内存使用情况
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf("当前内存使用: %.2f MB\n", usedMemory / 1024.0 / 1024.0);
    }
    
    /**
     * 复杂对象类 - 包含多种字段类型
     */
    static class ComplexObject {
        private String name;
        private int value;
        private boolean active;
        private double ratio;
        private Object reference;
        private int[] data;
        
        public ComplexObject() {
            this("Default", 0);
        }
        
        public ComplexObject(String name, int value) {
            this.name = name;
            this.value = value;
            this.active = true;
            this.ratio = Math.random();
            this.reference = new Object();
            this.data = new int[10];
            
            // 初始化数组
            for (int i = 0; i < data.length; i++) {
                data[i] = i * value;
            }
        }
        
        // Getter和Setter方法
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        
        @Override
        public String toString() {
            return String.format("ComplexObject{name='%s', value=%d, active=%s, ratio=%.3f}", 
                               name, value, active, ratio);
        }
    }
}