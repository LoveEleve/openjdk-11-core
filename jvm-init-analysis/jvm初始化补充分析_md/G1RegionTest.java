public class G1RegionTest {
    public static void main(String[] args) {
        System.out.println("=== G1 Region Size Verification ===");
        System.out.println("Heap Size: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        System.out.println("Available Processors: " + Runtime.getRuntime().availableProcessors());
        
        // 打印GC信息
        System.gc();
        
        // 保持程序运行以便调试
        try {
            Thread.sleep(30000); // 30秒，足够调试
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}