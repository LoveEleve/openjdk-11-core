package com.arthas.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业级Arthas风格诊断工具服务器
 * 集成所有Netty最佳实践和性能优化
 * 
 * 基于JVM标准配置：-Xms=8GB -Xmx=8GB，G1 GC，Region=4MB
 */
public class EnterpriseArthasServer {
    
    private static final Logger logger = LoggerFactory.getLogger(EnterpriseArthasServer.class);
    
    private final int tcpPort;
    private final int webPort;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ArthasConnectionManager connectionManager;
    private final ArthasMemoryMonitor memoryMonitor;
    
    private Channel tcpServerChannel;
    private Channel webServerChannel;
    
    public EnterpriseArthasServer(int tcpPort, int webPort) {
        this.tcpPort = tcpPort;
        this.webPort = webPort;
        
        // 配置Netty内存优化
        configureNettyOptimizations();
        
        // 创建优化的EventLoopGroup
        this.bossGroup = createOptimizedEventLoopGroup(true);
        this.workerGroup = createOptimizedEventLoopGroup(false);
        
        // 初始化连接管理器
        this.connectionManager = new ArthasConnectionManager();
        
        // 启动内存监控
        this.memoryMonitor = new ArthasMemoryMonitor();
        this.memoryMonitor.startMonitoring();
        
        logger.info("企业级Arthas服务器初始化完成 - TCP端口: {}, Web端口: {}", tcpPort, webPort);
    }
    
    /**
     * 配置Netty性能优化参数
     */
    private void configureNettyOptimizations() {
        // 设置内存泄漏检测级别
        System.setProperty("io.netty.leakDetection.level", "SIMPLE");
        System.setProperty("io.netty.leakDetection.samplingInterval", "1024");
        
        // 在8GB堆环境下，设置2GB直接内存限制
        long directMemoryLimit = 2L * 1024 * 1024 * 1024;
        System.setProperty("io.netty.maxDirectMemory", String.valueOf(directMemoryLimit));
        
        // 优化缓冲区分配
        System.setProperty("io.netty.allocator.numHeapArenas", "2");
        System.setProperty("io.netty.allocator.numDirectArenas", "4");
        System.setProperty("io.netty.allocator.pageSize", "8192");
        System.setProperty("io.netty.allocator.maxOrder", "11");
        
        logger.info("Netty优化配置完成 - 直接内存限制: {}MB", directMemoryLimit / 1024 / 1024);
    }
    
    /**
     * 创建优化的EventLoopGroup
     */
    private EventLoopGroup createOptimizedEventLoopGroup(boolean isBoss) {
        int threadCount = isBoss ? 1 : Runtime.getRuntime().availableProcessors() * 2;
        
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            private final String prefix = isBoss ? "Arthas-Boss-" : "Arthas-Worker-";
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + counter.incrementAndGet());
                t.setDaemon(false);
                
                // Boss线程设置较高优先级
                if (isBoss) {
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                } else {
                    t.setPriority(Thread.NORM_PRIORITY);
                }
                
                return t;
            }
        };
        
        return new NioEventLoopGroup(threadCount, threadFactory);
    }
    
    /**
     * 启动服务器
     */
    public void start() throws InterruptedException {
        try {
            logger.info("开始启动企业级Arthas服务器...");
            
            // 启动TCP服务器
            startTcpServer();
            
            // 启动WebSocket服务器  
            startWebSocketServer();
            
            logger.info("🎉 企业级Arthas服务器启动成功！");
            logger.info("📡 TCP连接地址: telnet localhost {}", tcpPort);
            logger.info("🌐 Web Console地址: http://localhost:{}/", webPort);
            logger.info("💾 JVM信息: {} - 最大内存: {}MB", 
                       System.getProperty("java.vm.name"),
                       Runtime.getRuntime().maxMemory() / 1024 / 1024);
            
        } catch (Exception e) {
            logger.error("❌ 服务器启动失败", e);
            shutdown();
            throw e;
        }
    }
    
    /**
     * 启动TCP服务器
     */
    private void startTcpServer() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ArthasTcpServerInitializer(connectionManager));
        
        // 应用性能优化配置
        configureServerBootstrap(bootstrap);
        
        ChannelFuture future = bootstrap.bind(tcpPort).sync();
        tcpServerChannel = future.channel();
        
        logger.info("✅ TCP服务器启动成功，监听端口: {}", tcpPort);
    }
    
    /**
     * 启动WebSocket服务器
     */
    private void startWebSocketServer() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ArthasWebServerInitializer(connectionManager));
        
        // 应用性能优化配置
        configureServerBootstrap(bootstrap);
        
        ChannelFuture future = bootstrap.bind(webPort).sync();
        webServerChannel = future.channel();
        
        logger.info("✅ WebSocket服务器启动成功，监听端口: {}", webPort);
    }
    
    /**
     * 配置ServerBootstrap性能参数
     */
    private void configureServerBootstrap(ServerBootstrap bootstrap) {
        // 服务端选项
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true);
        
        // 客户端连接选项
        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_SNDBUF, 65536)
                .childOption(ChannelOption.SO_RCVBUF, 65536)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, 
                           new WriteBufferWaterMark(32 * 1024, 64 * 1024));
    }
    
    /**
     * 获取服务器统计信息
     */
    public ServerStats getServerStats() {
        return new ServerStats(
            tcpPort,
            webPort,
            connectionManager.getActiveConnectionCount(),
            connectionManager.getTotalConnectionCount(),
            System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime(),
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            Runtime.getRuntime().maxMemory()
        );
    }
    
    /**
     * 优雅关闭服务器
     */
    public void shutdown() {
        logger.info("🔄 开始优雅关闭企业级Arthas服务器...");
        
        try {
            // 关闭服务器Channel
            if (tcpServerChannel != null) {
                tcpServerChannel.close().sync();
                logger.info("✅ TCP服务器已关闭");
            }
            
            if (webServerChannel != null) {
                webServerChannel.close().sync();
                logger.info("✅ WebSocket服务器已关闭");
            }
            
            // 关闭连接管理器
            connectionManager.shutdown();
            logger.info("✅ 连接管理器已关闭");
            
            // 停止内存监控
            memoryMonitor.stop();
            logger.info("✅ 内存监控已停止");
            
        } catch (InterruptedException e) {
            logger.warn("关闭过程被中断", e);
            Thread.currentThread().interrupt();
        } finally {
            // 关闭EventLoopGroup
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            logger.info("✅ 线程池已关闭");
        }
        
        logger.info("🎉 企业级Arthas服务器已完全关闭");
    }
    
    /**
     * 服务器统计信息
     */
    public static class ServerStats {
        private final int tcpPort;
        private final int webPort;
        private final int activeConnections;
        private final long totalConnections;
        private final long uptime;
        private final long usedMemory;
        private final long maxMemory;
        
        public ServerStats(int tcpPort, int webPort, int activeConnections, 
                          long totalConnections, long uptime, long usedMemory, long maxMemory) {
            this.tcpPort = tcpPort;
            this.webPort = webPort;
            this.activeConnections = activeConnections;
            this.totalConnections = totalConnections;
            this.uptime = uptime;
            this.usedMemory = usedMemory;
            this.maxMemory = maxMemory;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ServerStats{TCP端口=%d, Web端口=%d, 活动连接=%d, 总连接数=%d, " +
                "运行时长=%d秒, 内存使用=%dMB/%dMB (%.1f%%)}",
                tcpPort, webPort, activeConnections, totalConnections,
                uptime / 1000, usedMemory / 1024 / 1024, maxMemory / 1024 / 1024,
                (double) usedMemory / maxMemory * 100
            );
        }
        
        // Getters
        public int getTcpPort() { return tcpPort; }
        public int getWebPort() { return webPort; }
        public int getActiveConnections() { return activeConnections; }
        public long getTotalConnections() { return totalConnections; }
        public long getUptime() { return uptime; }
        public long getUsedMemory() { return usedMemory; }
        public long getMaxMemory() { return maxMemory; }
    }
    
    /**
     * 主方法 - 服务器启动入口
     */
    public static void main(String[] args) {
        // 解析命令行参数
        int tcpPort = args.length > 0 ? Integer.parseInt(args[0]) : 3658;
        int webPort = args.length > 1 ? Integer.parseInt(args[1]) : 8563;
        
        // 打印启动信息
        logger.info("🚀 启动企业级Arthas服务器");
        logger.info("📋 JVM配置: {} {}", 
                   System.getProperty("java.vm.name"), 
                   System.getProperty("java.vm.version"));
        logger.info("💾 内存配置: 最大={}MB, 初始={}MB", 
                   Runtime.getRuntime().maxMemory() / 1024 / 1024,
                   Runtime.getRuntime().totalMemory() / 1024 / 1024);
        
        // 创建服务器实例
        EnterpriseArthasServer server = new EnterpriseArthasServer(tcpPort, webPort);
        
        // 添加优雅关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("🛑 收到关闭信号，开始优雅关闭...");
            server.shutdown();
        }, "Shutdown-Hook"));
        
        try {
            // 启动服务器
            server.start();
            
            // 定期打印服务器状态
            scheduleStatusReport(server);
            
            // 等待TCP服务器关闭
            server.tcpServerChannel.closeFuture().sync();
            
        } catch (InterruptedException e) {
            logger.error("❌ 服务器运行被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("❌ 服务器运行异常", e);
        } finally {
            server.shutdown();
        }
    }
    
    /**
     * 定期打印服务器状态
     */
    private static void scheduleStatusReport(EnterpriseArthasServer server) {
        Thread statusThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000); // 每分钟打印一次
                    ServerStats stats = server.getServerStats();
                    logger.info("📊 服务器状态: {}", stats);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Status-Reporter");
        
        statusThread.setDaemon(true);
        statusThread.start();
    }
}