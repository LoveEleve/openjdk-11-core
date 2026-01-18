import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 基于OpenJDK11 MappedByteBuffer源码的内存泄漏复现
 * 模拟真实的文件映射内存泄漏场景
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxDirectMemorySize=2g
 */
public class DirectBufferLeakTest {
    
    private static final int FILE_SIZE = 64 * 1024 * 1024; // 64MB
    private static final int BUFFER_COUNT = 100;
    private static List<MappedByteBuffer> buffers = new ArrayList<>();
    private static List<File> tempFiles = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== DirectByteBuffer内存泄漏测试开始 ===");
        System.out.println("JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxDirectMemorySize=2g");
        
        // 监控线程
        startMemoryMonitor();
        
        // 模拟真实业务场景：大量文件映射操作
        simulateFileMapping();
        
        // 等待观察内存变化
        System.out.println("等待30秒观察内存变化...");
        Thread.sleep(30000);
        
        // 演示清理过程
        cleanup();
        
        System.out.println("测试完成");
    }
    
    /**
     * 模拟真实的文件映射场景
     * 基于MappedByteBuffer.java源码的使用模式
     */
    private static void simulateFileMapping() throws Exception {
        System.out.println("开始创建大量MappedByteBuffer...");
        
        for (int i = 0; i < BUFFER_COUNT; i++) {
            // 创建临时文件
            File tempFile = File.createTempFile("mapped_" + i, ".dat");
            tempFiles.add(tempFile);
            
            // 写入数据
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                raf.setLength(FILE_SIZE);
                
                // 创建内存映射 - 这里会分配直接内存
                FileChannel channel = raf.getChannel();
                MappedByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
                
                // 模拟业务操作
                for (int j = 0; j < 1000; j++) {
                    buffer.putInt(j * 4, j);
                }
                
                // 关键问题：将buffer保存到集合中，阻止GC回收
                // 这模拟了真实场景中buffer被长期持有的情况
                buffers.add(buffer);
                
                System.out.printf("创建第%d个MappedByteBuffer (大小: %dMB)%n", 
                    i + 1, FILE_SIZE / 1024 / 1024);
            }
            
            // 模拟业务处理间隔
            Thread.sleep(100);
            
            // 当接近内存限制时，触发OOM
            if (i > 25) { // 大约1.6GB时开始出现问题
                System.out.println("⚠️  接近直接内存限制，可能出现OOM...");
            }
        }
    }
    
    /**
     * 内存监控线程
     */
    private static void startMemoryMonitor() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    Runtime runtime = Runtime.getRuntime();
                    long totalMemory = runtime.totalMemory();
                    long freeMemory = runtime.freeMemory();
                    long usedMemory = totalMemory - freeMemory;
                    
                    // 获取直接内存使用情况
                    long directMemory = getDirectMemoryUsed();
                    
                    System.out.printf("[内存监控] 堆内存: %dMB/%dMB, 直接内存估算: %dMB, Buffer数量: %d%n",
                        usedMemory / 1024 / 1024,
                        totalMemory / 1024 / 1024,
                        (buffers.size() * FILE_SIZE) / 1024 / 1024,
                        buffers.size());
                    
                    Thread.sleep(2000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }
    
    /**
     * 获取直接内存使用量（简化版本）
     */
    private static long getDirectMemoryUsed() {
        try {
            // 简化计算：基于已创建的buffer数量估算
            return buffers.size() * FILE_SIZE;
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * 清理资源（演示正确的清理方式）
     */
    public static void cleanup() {
        System.out.println("开始清理资源...");
        
        // 清理MappedByteBuffer
        for (int i = 0; i < buffers.size(); i++) {
            MappedByteBuffer buffer = buffers.get(i);
            // 在真实场景中，应该使用sun.misc.Cleaner或Java 9+的Cleaner API
            // 这里简化处理，只是移除引用
            buffers.set(i, null);
        }
        
        // 清理临时文件
        for (File file : tempFiles) {
            if (file.exists()) {
                file.delete();
            }
        }
        
        buffers.clear();
        tempFiles.clear();
        
        // 强制GC
        System.gc();
        System.runFinalization();
        
        System.out.println("资源清理完成，等待5秒观察内存变化...");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}