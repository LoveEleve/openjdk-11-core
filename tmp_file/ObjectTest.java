public class ObjectTest {
    private int age = 25;
    private String name = "JVM Debug";
    private static int count = 0;
    
    public static void main(String[] args) {
        System.out.println("=== 对象模型调试测试 ===");
        
        // 创建对象观察内存分配
        ObjectTest obj1 = new ObjectTest();
        ObjectTest obj2 = new ObjectTest();
        
        // 触发字符串池
        String s1 = "Hello";
        String s2 = "World";
        String s3 = s1 + s2;
        
        // 触发数组分配
        int[] array = new int[1000];
        Object[] objArray = new Object[100];
        
        // 触发GC
        for (int i = 0; i < 1000; i++) {
            new Object();
        }
        
        System.out.println("对象创建完成，观察内存布局");
        System.gc(); // 显式触发GC
        
        System.out.println("GC完成");
    }
}