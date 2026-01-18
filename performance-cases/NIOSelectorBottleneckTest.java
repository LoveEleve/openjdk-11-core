import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 基于OpenJDK11 Selector源码的性能问题复现
 * 模拟真实的NIO服务器性能瓶颈场景
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC
 */
public class NIOSelectorBottleneckTest {
    
    private static final int SERVER_PORT = 8080;
    private static final int CLIENT_COUNT = 1000; // 减少客户端数量避免系统负载过高
    private static final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final AtomicInteger messageCount = new AtomicInteger(0);
    private static volatile boolean running = true;
    
    // 模拟问题：不正确的SelectionKey管理
    private static final Set<SelectionKey> LEAKED_KEYS = ConcurrentHashMap.newKeySet();
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== NIO Selector性能瓶颈测试开始 ===");
        System.out.println("JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC");
        System.out.println("测试将运行60秒，观察性能变化...");
        
        // 启动监控
        startPerformanceMonitor();
        
        // 启动有问题的NIO服务器
        startProblematicNIOServer();
        
        // 等待服务器启动
        Thread.sleep(2000);
        
        // 启动客户端压测
        startClientLoad();
        
        // 运行测试
        Thread.sleep(60000);
        
        running = false;
        System.out.println("测试完成");
        System.exit(0);
    }
    
    /**
     * 启动有性能问题的NIO服务器
     */
    private static void startProblematicNIOServer() {
        Thread serverThread = new Thread(() -> {
            try {
                runProblematicServer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setName("ProblematicNIOServer");
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    /**
     * 运行有问题的NIO服务器
     * 基于Selector.java源码，故意引入性能问题
     */
    private static void runProblematicServer() throws Exception {
        Selector selector = Selector.open();
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(SERVER_PORT));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        System.out.println("NIO服务器启动在端口: " + SERVER_PORT);
        
        while (running) {
            try {
                // 问题1：不合理的超时设置，导致频繁空轮询
                int readyChannels = selector.select(1); // 1ms超时，过于频繁
                
                if (readyChannels == 0) {
                    // 问题2：空轮询时不进行任何优化处理
                    continue;
                }
                
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    
                    try {
                        if (key.isValid()) {
                            if (key.isAcceptable()) {
                                handleAccept(selector, key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            }
                        }
                    } catch (Exception e) {
                        // 问题3：异常处理不当，没有正确清理SelectionKey
                        System.err.println("处理连接异常: " + e.getMessage());
                        // 错误做法：不清理key，导致泄漏
                        LEAKED_KEYS.add(key);
                        try {
                            key.channel().close();
                        } catch (Exception ignored) {}
                    } finally {
                        // 问题4：忘记从selectedKeys中移除已处理的key
                        keyIterator.remove(); // 这里正确移除，但其他地方有问题
                    }
                }
                
                // 问题5：selectedKeys集合处理不当
                // 这里故意不清理，模拟常见错误
                if (selectedKeys.size() > 100) {
                    selectedKeys.clear(); // 只有在累积过多时才清理
                }
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 处理新连接
     */
    private static void handleAccept(Selector selector, SelectionKey key) throws Exception {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        
        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            
            // 问题6：为每个连接创建过大的缓冲区
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 64KB per connection
            
            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            clientKey.attach(buffer);
            
            connectionCount.incrementAndGet();
            
            // 模拟连接处理延迟
            Thread.sleep(1);
        }
    }
    
    /**
     * 处理读取数据
     */
    private static void handleRead(SelectionKey key) throws Exception {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        
        if (buffer == null) {
            buffer = ByteBuffer.allocate(1024);
            key.attach(buffer);
        }
        
        try {
            int bytesRead = channel.read(buffer);
            
            if (bytesRead > 0) {
                messageCount.incrementAndGet();
                
                // 问题7：每次读取都进行耗时的处理
                processMessage(buffer);
                
                buffer.clear();
            } else if (bytesRead == -1) {
                // 连接关闭
                closeConnection(key);
            }
        } catch (IOException e) {
            // 问题8：IO异常时没有正确清理资源
            System.err.println("读取数据异常: " + e.getMessage());
            closeConnection(key);
        }
    }
    
    /**
     * 模拟消息处理（耗时操作）
     */
    private static void processMessage(ByteBuffer buffer) {
        // 问题9：在NIO线程中进行耗时的业务处理
        try {
            Thread.sleep(2); // 模拟2ms的处理时间
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 关闭连接
     */
    private static void closeConnection(SelectionKey key) {
        try {
            key.channel().close();
            key.cancel();
            connectionCount.decrementAndGet();
        } catch (Exception e) {
            System.err.println("关闭连接异常: " + e.getMessage());
        }
    }
    
    /**
     * 启动客户端压测
     */
    private static void startClientLoad() {
        ExecutorService clientExecutor = Executors.newFixedThreadPool(20);
        
        for (int i = 0; i < CLIENT_COUNT && running; i++) {
            final int clientId = i;
            clientExecutor.submit(() -> {
                try {
                    runClient(clientId);
                } catch (Exception e) {
                    // 忽略客户端异常，专注于服务器性能问题
                }
            });
            
            // 控制连接建立速度
            if (i % 50 == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        clientExecutor.shutdown();
    }
    
    /**
     * 运行单个客户端
     */
    private static void runClient(int clientId) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", SERVER_PORT), 5000);
            socket.setSoTimeout(10000);
            
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                // 发送消息
                for (int i = 0; i < 5 && running; i++) {
                    out.println("Message from client " + clientId + ", seq " + i);
                    Thread.sleep(200);
                }
                
                // 保持连接一段时间
                Thread.sleep(3000);
            }
            
        } catch (Exception e) {
            // 忽略连接异常
        }
    }
    
    /**
     * 性能监控线程
     */
    private static void startPerformanceMonitor() {
        Thread monitor = new Thread(() -> {
            long lastMessageCount = 0;
            long lastTime = System.currentTimeMillis();
            
            while (running) {
                try {
                    Thread.sleep(5000);
                    
                    long currentTime = System.currentTimeMillis();
                    long currentMessageCount = messageCount.get();
                    
                    long timeDiff = currentTime - lastTime;
                    long messageDiff = currentMessageCount - lastMessageCount;
                    
                    double tps = (double) messageDiff * 1000 / timeDiff;
                    
                    // 获取内存使用情况
                    Runtime runtime = Runtime.getRuntime();
                    long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
                    
                    System.out.printf("[性能监控] 连接数: %d, 消息总数: %d, TPS: %.2f, " +
                        "泄漏Key数: %d, 内存使用: %dMB%n",
                        connectionCount.get(), currentMessageCount, tps, 
                        LEAKED_KEYS.size(), usedMemory);
                    
                    // 检查性能问题
                    if (tps < 50 && connectionCount.get() > 100) {
                        System.out.println("⚠️  检测到性能瓶颈：TPS过低，可能存在Selector问题");
                    }
                    
                    if (LEAKED_KEYS.size() > 50) {
                        System.out.println("⚠️  检测到SelectionKey泄漏：" + LEAKED_KEYS.size() + "个");
                    }
                    
                    if (connectionCount.get() > 500) {
                        System.out.println("⚠️  连接数过多，可能影响性能");
                    }
                    
                    lastTime = currentTime;
                    lastMessageCount = currentMessageCount;
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        monitor.setDaemon(true);
        monitor.setName("PerformanceMonitor");
        monitor.start();
    }
}