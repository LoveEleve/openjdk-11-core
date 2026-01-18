public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("🚀 8GB G1 JVM初始化分析验证");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        
        System.out.println("最大内存: " + (maxMemory / (1024*1024*1024)) + " GB");
        System.out.println("总内存: " + (totalMemory / (1024*1024)) + " MB");
        
        // 验证G1配置
        String gcType = System.getProperty("java.vm.name");
        System.out.println("JVM类型: " + gcType);
        
        // 触发一些G1活动
        java.util.List<Object> objects = new java.util.ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            objects.add(new Object());
        }
        System.out.println("G1内存分配测试完成");
    }
}
