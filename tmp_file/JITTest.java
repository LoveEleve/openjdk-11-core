public class JITTest {
    private static int counter = 0;
    
    // 热点方法 - 触发JIT编译
    public static int hotMethod(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i * i;
        }
        return sum;
    }
    
    // 递归方法 - 测试内联优化
    public static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
    
    // 虚拟调用 - 测试去虚拟化
    public static void virtualCall(Object obj) {
        obj.toString();
    }
    
    public static void main(String[] args) {
        System.out.println("=== JIT编译调试测试 ===");
        
        // 预热阶段 - 触发C1编译
        System.out.println("预热阶段开始...");
        for (int i = 0; i < 1000; i++) {
            hotMethod(100);
        }
        
        // 大量调用 - 触发C2编译
        System.out.println("大量调用阶段...");
        for (int i = 0; i < 20000; i++) {
            hotMethod(1000);
            if (i % 5000 == 0) {
                System.out.println("调用次数: " + i);
            }
        }
        
        // 测试递归优化
        System.out.println("递归测试...");
        for (int i = 0; i < 1000; i++) {
            fibonacci(20);
        }
        
        // 测试虚拟调用优化
        System.out.println("虚拟调用测试...");
        String str = "test";
        for (int i = 0; i < 10000; i++) {
            virtualCall(str);
        }
        
        System.out.println("JIT编译测试完成");
    }
}