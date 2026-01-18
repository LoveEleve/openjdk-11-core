package com.arthas.netty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Arthas WebSocket处理器
 * 处理Web Console的实时命令交互
 */
public class ArthasWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasWebSocketHandler.class);
    
    private final ArthasConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    
    public ArthasWebSocketHandler(ArthasConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        String connectionId = ctx.channel().id().asShortText();
        logger.info("WebSocket连接建立: {}", connectionId);
        
        // 发送欢迎消息
        sendWelcomeMessage(ctx);
    }
    
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        String connectionId = ctx.channel().id().asShortText();
        logger.info("WebSocket连接断开: {}", connectionId);
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof BinaryWebSocketFrame) {
            handleBinaryFrame(ctx, (BinaryWebSocketFrame) frame);
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
        }
    }
    
    /**
     * 处理文本帧（JSON消息）
     */
    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String connectionId = ctx.channel().id().asShortText();
        String message = frame.text();
        
        logger.info("收到WebSocket消息: {} - 内容: {}", connectionId, message);
        
        try {
            JsonNode messageNode = objectMapper.readTree(message);
            String type = messageNode.get("type").asText();
            
            switch (type) {
                case "command":
                    handleCommand(ctx, messageNode);
                    break;
                case "heartbeat":
                    handleHeartbeat(ctx, messageNode);
                    break;
                case "subscribe":
                    handleSubscribe(ctx, messageNode);
                    break;
                case "unsubscribe":
                    handleUnsubscribe(ctx, messageNode);
                    break;
                default:
                    logger.warn("未知消息类型: {}", type);
                    sendErrorMessage(ctx, "未知消息类型: " + type, null);
            }
            
        } catch (Exception e) {
            logger.error("处理WebSocket消息失败", e);
            sendErrorMessage(ctx, "消息处理失败: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理二进制帧
     */
    private void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        logger.info("收到二进制数据，长度: {}", frame.content().readableBytes());
        // 可以用于处理文件上传等功能
    }
    
    /**
     * 处理命令执行
     */
    private void handleCommand(ChannelHandlerContext ctx, JsonNode messageNode) {
        String command = messageNode.get("command").asText();
        String requestId = messageNode.has("id") ? messageNode.get("id").asText() : null;
        
        // 发送命令接收确认
        sendCommandAck(ctx, requestId, command);
        
        // 异步执行命令
        CompletableFuture.supplyAsync(() -> executeCommand(command))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        sendCommandResult(ctx, requestId, false, null, throwable.getMessage());
                        logger.error("命令执行失败: {}", command, throwable);
                    } else {
                        sendCommandResult(ctx, requestId, true, result, null);
                    }
                });
    }
    
    /**
     * 处理心跳
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, JsonNode messageNode) {
        String requestId = messageNode.has("id") ? messageNode.get("id").asText() : null;
        
        // 更新连接活动时间
        String connectionId = ctx.channel().id().asShortText();
        connectionManager.updateConnectionActivity(connectionId);
        
        // 发送心跳响应
        Map<String, Object> response = new HashMap<>();
        response.put("type", "heartbeat_ack");
        response.put("id", requestId);
        response.put("timestamp", System.currentTimeMillis());
        response.put("serverTime", new Date().toString());
        
        sendMessage(ctx, response);
    }
    
    /**
     * 处理订阅
     */
    private void handleSubscribe(ChannelHandlerContext ctx, JsonNode messageNode) {
        String topic = messageNode.get("topic").asText();
        String requestId = messageNode.has("id") ? messageNode.get("id").asText() : null;
        
        logger.info("客户端订阅主题: {}", topic);
        
        // 这里可以实现实时数据推送功能
        Map<String, Object> response = new HashMap<>();
        response.put("type", "subscribe_ack");
        response.put("id", requestId);
        response.put("topic", topic);
        response.put("success", true);
        response.put("message", "订阅成功: " + topic);
        
        sendMessage(ctx, response);
    }
    
    /**
     * 处理取消订阅
     */
    private void handleUnsubscribe(ChannelHandlerContext ctx, JsonNode messageNode) {
        String topic = messageNode.get("topic").asText();
        String requestId = messageNode.has("id") ? messageNode.get("id").asText() : null;
        
        logger.info("客户端取消订阅主题: {}", topic);
        
        Map<String, Object> response = new HashMap<>();
        response.put("type", "unsubscribe_ack");
        response.put("id", requestId);
        response.put("topic", topic);
        response.put("success", true);
        response.put("message", "取消订阅成功: " + topic);
        
        sendMessage(ctx, response);
    }
    
    /**
     * 执行命令（复用TCP处理器的逻辑）
     */
    private Object executeCommand(String command) {
        String[] parts = command.split("\\s+");
        String commandName = parts[0].toLowerCase();
        
        try {
            switch (commandName) {
                case "dashboard":
                    return getDashboardData();
                case "jvm":
                    return getJvmData();
                case "thread":
                    return getThreadData();
                case "memory":
                    return getMemoryData();
                case "gc":
                    return getGcData();
                case "connection":
                    return getConnectionData();
                default:
                    return Map.of("message", "未知命令: " + commandName);
            }
        } catch (Exception e) {
            logger.error("执行命令失败: {}", command, e);
            return Map.of("error", "命令执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取仪表板数据
     */
    private Map<String, Object> getDashboardData() {
        Map<String, Object> data = new HashMap<>();
        
        // 基本信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        data.put("processId", getProcessId());
        data.put("uptime", runtimeBean.getUptime());
        data.put("startTime", runtimeBean.getStartTime());
        
        // 内存信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        Map<String, Object> memory = new HashMap<>();
        memory.put("heapUsed", heapUsage.getUsed());
        memory.put("heapMax", heapUsage.getMax());
        memory.put("heapUsageRatio", (double) heapUsage.getUsed() / heapUsage.getMax());
        data.put("memory", memory);
        
        // 线程信息
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<String, Object> threads = new HashMap<>();
        threads.put("count", threadBean.getThreadCount());
        threads.put("daemonCount", threadBean.getDaemonThreadCount());
        threads.put("peakCount", threadBean.getPeakThreadCount());
        data.put("threads", threads);
        
        // GC信息
        Map<String, Object> gc = new HashMap<>();
        long totalGcTime = 0;
        long totalGcCount = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gcBean.getCollectionTime();
            totalGcCount += gcBean.getCollectionCount();
        }
        gc.put("totalTime", totalGcTime);
        gc.put("totalCount", totalGcCount);
        data.put("gc", gc);
        
        // 连接信息
        ArthasConnectionManager.ConnectionStats stats = connectionManager.getConnectionStats();
        Map<String, Object> connections = new HashMap<>();
        connections.put("active", stats.getActiveConnections());
        connections.put("total", stats.getTotalConnections());
        connections.put("tcp", stats.getTcpConnections());
        connections.put("web", stats.getWebConnections());
        data.put("connections", connections);
        
        return data;
    }
    
    /**
     * 获取JVM数据
     */
    private Map<String, Object> getJvmData() {
        Map<String, Object> data = new HashMap<>();
        
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        data.put("vmName", runtimeBean.getVmName());
        data.put("vmVersion", runtimeBean.getVmVersion());
        data.put("vmVendor", runtimeBean.getVmVendor());
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("javaHome", System.getProperty("java.home"));
        data.put("inputArguments", runtimeBean.getInputArguments());
        
        return data;
    }
    
    /**
     * 获取线程数据
     */
    private Map<String, Object> getThreadData() {
        Map<String, Object> data = new HashMap<>();
        
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        data.put("threadCount", threadBean.getThreadCount());
        data.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        data.put("peakThreadCount", threadBean.getPeakThreadCount());
        data.put("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());
        
        return data;
    }
    
    /**
     * 获取内存数据
     */
    private Map<String, Object> getMemoryData() {
        Map<String, Object> data = new HashMap<>();
        
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        // 堆内存
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        Map<String, Object> heap = new HashMap<>();
        heap.put("init", heapUsage.getInit());
        heap.put("used", heapUsage.getUsed());
        heap.put("committed", heapUsage.getCommitted());
        heap.put("max", heapUsage.getMax());
        data.put("heap", heap);
        
        // 非堆内存
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        Map<String, Object> nonHeap = new HashMap<>();
        nonHeap.put("used", nonHeapUsage.getUsed());
        nonHeap.put("committed", nonHeapUsage.getCommitted());
        if (nonHeapUsage.getMax() > 0) {
            nonHeap.put("max", nonHeapUsage.getMax());
        }
        data.put("nonHeap", nonHeap);
        
        return data;
    }
    
    /**
     * 获取GC数据
     */
    private Map<String, Object> getGcData() {
        Map<String, Object> data = new HashMap<>();
        
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gcInfo = new HashMap<>();
            gcInfo.put("collectionCount", gcBean.getCollectionCount());
            gcInfo.put("collectionTime", gcBean.getCollectionTime());
            gcInfo.put("memoryPoolNames", gcBean.getMemoryPoolNames());
            
            data.put(gcBean.getName(), gcInfo);
        }
        
        return data;
    }
    
    /**
     * 获取连接数据
     */
    private Map<String, Object> getConnectionData() {
        ArthasConnectionManager.ConnectionStats stats = connectionManager.getConnectionStats();
        
        Map<String, Object> data = new HashMap<>();
        data.put("activeConnections", stats.getActiveConnections());
        data.put("totalConnections", stats.getTotalConnections());
        data.put("tcpConnections", stats.getTcpConnections());
        data.put("webConnections", stats.getWebConnections());
        data.put("totalMessages", stats.getTotalMessages());
        data.put("oldestConnectionAge", stats.getOldestConnectionAge());
        
        return data;
    }
    
    /**
     * 发送欢迎消息
     */
    private void sendWelcomeMessage(ChannelHandlerContext ctx) {
        Map<String, Object> welcome = new HashMap<>();
        welcome.put("type", "welcome");
        welcome.put("message", "欢迎使用Arthas Web Console!");
        welcome.put("version", "3.6.7");
        welcome.put("timestamp", System.currentTimeMillis());
        
        // 服务器信息
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("processId", getProcessId());
        serverInfo.put("javaVersion", System.getProperty("java.version"));
        serverInfo.put("jvmName", System.getProperty("java.vm.name"));
        serverInfo.put("osName", System.getProperty("os.name"));
        serverInfo.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        
        Runtime runtime = Runtime.getRuntime();
        serverInfo.put("maxMemory", runtime.maxMemory());
        serverInfo.put("totalMemory", runtime.totalMemory());
        serverInfo.put("freeMemory", runtime.freeMemory());
        
        welcome.put("serverInfo", serverInfo);
        
        sendMessage(ctx, welcome);
    }
    
    /**
     * 发送命令确认
     */
    private void sendCommandAck(ChannelHandlerContext ctx, String requestId, String command) {
        Map<String, Object> ack = new HashMap<>();
        ack.put("type", "command_ack");
        ack.put("id", requestId);
        ack.put("command", command);
        ack.put("message", "命令已接收，正在执行...");
        ack.put("timestamp", System.currentTimeMillis());
        
        sendMessage(ctx, ack);
    }
    
    /**
     * 发送命令结果
     */
    private void sendCommandResult(ChannelHandlerContext ctx, String requestId, 
                                 boolean success, Object result, String error) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "command_result");
        response.put("id", requestId);
        response.put("success", success);
        response.put("timestamp", System.currentTimeMillis());
        
        if (success) {
            response.put("result", result);
        } else {
            response.put("error", error);
        }
        
        sendMessage(ctx, response);
    }
    
    /**
     * 发送错误消息
     */
    private void sendErrorMessage(ChannelHandlerContext ctx, String errorMessage, String requestId) {
        Map<String, Object> error = new HashMap<>();
        error.put("type", "error");
        error.put("id", requestId);
        error.put("message", errorMessage);
        error.put("timestamp", System.currentTimeMillis());
        
        sendMessage(ctx, error);
    }
    
    /**
     * 发送消息
     */
    private void sendMessage(ChannelHandlerContext ctx, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("发送WebSocket消息失败", e);
        }
    }
    
    /**
     * 获取进程ID
     */
    private String getProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("WebSocket处理异常", cause);
        sendErrorMessage(ctx, "服务器内部错误: " + cause.getMessage(), null);
    }
}