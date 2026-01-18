public class CompressedOopsTest {
    private static Object[] objects = new Object[1000];
    
    public static void main(String[] args) {
        System.out.println("=== 压缩指针验证测试 ===");
        
        // 分配一些对象来观察压缩指针行为
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new Object();
        }
        
        System.out.println("已分配 " + objects.length + " 个对象");
        System.out.println("等待调试...");
        
        // 保持程序运行
        try {
            Thread.sleep(60000); // 60秒
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}