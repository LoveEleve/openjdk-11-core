package com.arthas.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Arthas连接管理器
 * 负责管理所有客户端连接的生命周期、统计信息和资源清理
 */
public class ArthasConnectionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasConnectionManager.class);
    
    private final Map<String, ArthasConnection> activeConnections = new ConcurrentHashMap<>();
    private final AtomicLong totalConnectionCounter = new AtomicLong(0);
    private final ScheduledExecutorService cleanupExecutor;
    
    // 连接超时配置（30分钟）
    private static final long CONNECTION_TIMEOUT_MS = 30 * 60 * 1000;
    
    public ArthasConnectionManager() {
        // 创建清理任务执行器
        this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Arthas-Connection-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // 每5分钟执行一次连接清理
        cleanupExecutor.scheduleAtFixedRate(this::cleanupInactiveConnections, 
                                          5, 5, TimeUnit.MINUTES);
        
        logger.info("连接管理器初始化完成");
    }
    
    /**
     * 注册新连接
     */
    public ArthasConnection registerConnection(Channel channel, String clientType) {
        String connectionId = generateConnectionId(channel);
        
        ArthasConnection connection = new ArthasConnection(
            connectionId, 
            channel, 
            clientType,
            System.currentTimeMillis()
        );
        
        activeConnections.put(connectionId, connection);
        totalConnectionCounter.incrementAndGet();
        
        logger.info("新连接注册: {} - 类型: {}, 远程地址: {}, 当前活动连接数: {}", 
                   connectionId, clientType, channel.remoteAddress(), activeConnections.size());
        
        return connection;
    }
    
    /**
     * 注销连接
     */
    public void unregisterConnection(String connectionId) {
        ArthasConnection connection = activeConnections.remove(connectionId);
        if (connection != null) {
            long duration = System.currentTimeMillis() - connection.getCreateTime();
            
            logger.info("连接注销: {} - 类型: {}, 持续时长: {}秒, 处理消息数: {}, 剩余活动连接数: {}", 
                       connectionId, connection.getClientType(), duration / 1000, 
                       connection.getMessageCount(), activeConnections.size());
        }
    }
    
    /**
     * 更新连接活动时间
     */
    public void updateConnectionActivity(String connectionId) {
        ArthasConnection connection = activeConnections.get(connectionId);
        if (connection != null) {
            connection.updateLastActiveTime();
        }
    }
    
    /**
     * 增加消息计数
     */
    public void incrementMessageCount(String connectionId) {
        ArthasConnection connection = activeConnections.get(connectionId);
        if (connection != null) {
            connection.incrementMessageCount();
        }
    }
    
    /**
     * 获取连接信息
     */
    public ArthasConnection getConnection(String connectionId) {
        return activeConnections.get(connectionId);
    }
    
    /**
     * 获取活动连接数
     */
    public int getActiveConnectionCount() {
        return activeConnections.size();
    }
    
    /**
     * 获取总连接数
     */
    public long getTotalConnectionCount() {
        return totalConnectionCounter.get();
    }
    
    /**
     * 清理非活动连接
     */
    private void cleanupInactiveConnections() {
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;
        
        activeConnections.entrySet().removeIf(entry -> {
            ArthasConnection connection = entry.getValue();
            long inactiveTime = currentTime - connection.getLastActiveTime();
            
            if (inactiveTime > CONNECTION_TIMEOUT_MS) {
                // 关闭超时连接
                try {
                    connection.getChannel().close();
                    logger.info("清理超时连接: {} - 非活动时长: {}分钟", 
                               entry.getKey(), inactiveTime / 60000);
                    return true;
                } catch (Exception e) {
                    logger.warn("关闭超时连接失败: {}", entry.getKey(), e);
                }
            }
            return false;
        });
        
        if (cleanedCount > 0) {
            logger.info("连接清理完成，清理了 {} 个超时连接", cleanedCount);
        }
    }
    
    /**
     * 生成连接ID
     */
    private String generateConnectionId(Channel channel) {
        return channel.id().asShortText();
    }
    
    /**
     * 获取连接统计信息
     */
    public ConnectionStats getConnectionStats() {
        long currentTime = System.currentTimeMillis();
        int tcpConnections = 0;
        int webConnections = 0;
        long totalMessages = 0;
        long oldestConnectionAge = 0;
        
        for (ArthasConnection connection : activeConnections.values()) {
            if ("TCP".equals(connection.getClientType())) {
                tcpConnections++;
            } else if ("WEB".equals(connection.getClientType())) {
                webConnections++;
            }
            
            totalMessages += connection.getMessageCount();
            
            long age = currentTime - connection.getCreateTime();
            if (age > oldestConnectionAge) {
                oldestConnectionAge = age;
            }
        }
        
        return new ConnectionStats(
            activeConnections.size(),
            totalConnectionCounter.get(),
            tcpConnections,
            webConnections,
            totalMessages,
            oldestConnectionAge
        );
    }
    
    /**
     * 关闭所有连接并停止管理器
     */
    public void shutdown() {
        logger.info("开始关闭连接管理器...");
        
        // 关闭所有活动连接
        for (ArthasConnection connection : activeConnections.values()) {
            try {
                connection.getChannel().close();
            } catch (Exception e) {
                logger.warn("关闭连接失败: {}", connection.getConnectionId(), e);
            }
        }
        
        activeConnections.clear();
        
        // 关闭清理任务执行器
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("连接管理器已关闭");
    }
    
    /**
     * Arthas连接信息
     */
    public static class ArthasConnection {
        private final String connectionId;
        private final Channel channel;
        private final String clientType;
        private final long createTime;
        private volatile long lastActiveTime;
        private volatile long messageCount;
        
        public ArthasConnection(String connectionId, Channel channel, 
                               String clientType, long createTime) {
            this.connectionId = connectionId;
            this.channel = channel;
            this.clientType = clientType;
            this.createTime = createTime;
            this.lastActiveTime = createTime;
            this.messageCount = 0;
        }
        
        public void updateLastActiveTime() {
            this.lastActiveTime = System.currentTimeMillis();
        }
        
        public void incrementMessageCount() {
            this.messageCount++;
        }
        
        // Getters
        public String getConnectionId() { return connectionId; }
        public Channel getChannel() { return channel; }
        public String getClientType() { return clientType; }
        public long getCreateTime() { return createTime; }
        public long getLastActiveTime() { return lastActiveTime; }
        public long getMessageCount() { return messageCount; }
        
        @Override
        public String toString() {
            long duration = System.currentTimeMillis() - createTime;
            return String.format(
                "ArthasConnection{id='%s', type='%s', duration=%ds, messages=%d, remote=%s}",
                connectionId, clientType, duration / 1000, messageCount, 
                channel.remoteAddress()
            );
        }
    }
    
    /**
     * 连接统计信息
     */
    public static class ConnectionStats {
        private final int activeConnections;
        private final long totalConnections;
        private final int tcpConnections;
        private final int webConnections;
        private final long totalMessages;
        private final long oldestConnectionAge;
        
        public ConnectionStats(int activeConnections, long totalConnections,
                              int tcpConnections, int webConnections,
                              long totalMessages, long oldestConnectionAge) {
            this.activeConnections = activeConnections;
            this.totalConnections = totalConnections;
            this.tcpConnections = tcpConnections;
            this.webConnections = webConnections;
            this.totalMessages = totalMessages;
            this.oldestConnectionAge = oldestConnectionAge;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ConnectionStats{活动连接=%d, 总连接数=%d, TCP连接=%d, Web连接=%d, " +
                "总消息数=%d, 最老连接=%d秒}",
                activeConnections, totalConnections, tcpConnections, webConnections,
                totalMessages, oldestConnectionAge / 1000
            );
        }
        
        // Getters
        public int getActiveConnections() { return activeConnections; }
        public long getTotalConnections() { return totalConnections; }
        public int getTcpConnections() { return tcpConnections; }
        public int getWebConnections() { return webConnections; }
        public long getTotalMessages() { return totalMessages; }
        public long getOldestConnectionAge() { return oldestConnectionAge; }
    }
    
    /**
     * 连接生命周期处理器
     * 自动管理连接的注册和注销
     */
    public static class ConnectionLifecycleHandler extends ChannelInboundHandlerAdapter {
        
        private final ArthasConnectionManager connectionManager;
        private final String clientType;
        private String connectionId;
        
        public ConnectionLifecycleHandler(ArthasConnectionManager connectionManager, String clientType) {
            this.connectionManager = connectionManager;
            this.clientType = clientType;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 注册连接
            ArthasConnection connection = connectionManager.registerConnection(ctx.channel(), clientType);
            this.connectionId = connection.getConnectionId();
            
            super.channelActive(ctx);
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // 注销连接
            if (connectionId != null) {
                connectionManager.unregisterConnection(connectionId);
            }
            
            super.channelInactive(ctx);
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // 更新活动时间和消息计数
            if (connectionId != null) {
                connectionManager.updateConnectionActivity(connectionId);
                connectionManager.incrementMessageCount(connectionId);
            }
            
            super.channelRead(ctx, msg);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("连接异常: {} - {}", connectionId, cause.getMessage(), cause);
            ctx.close();
        }
    }
}