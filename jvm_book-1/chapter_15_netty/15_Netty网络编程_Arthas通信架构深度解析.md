# 第15章：Netty网络编程框架 - Arthas通信架构深度解析

## 🎯 **学习目标**

通过本章学习，您将：
1. **深度掌握Netty核心架构**：理解Reactor模式、Channel、Handler、Pipeline的设计原理
2. **精通Arthas通信机制**：完全理解Arthas客户端-服务端的网络通信实现
3. **构建企业级网络应用**：具备开发高性能、高可靠性网络服务的能力
4. **掌握WebSocket和HTTP协议**：理解Arthas Web Console的实现原理
5. **性能优化和故障诊断**：掌握网络层面的性能调优和问题排查

---

## 📚 **第一部分：Netty核心架构深度解析**

### **1.1 Reactor模式与EventLoop机制**

#### **理论基础**

Netty基于Reactor模式实现高性能网络通信：

```java
/**
 * Netty Reactor模式核心组件
 * 基于JVM标准配置：-Xms=8GB -Xmx=8GB，G1 GC，Region=4MB
 */
public class NettyReactorArchitecture {
    
    /**
     * Boss Group - 负责接受连接
     * 通常设置为1个线程，因为一个端口只需要一个Acceptor
     */
    private final EventLoopGroup bossGroup;
    
    /**
     * Worker Group - 负责处理I/O操作
     * 线程数 = CPU核心数 * 2（对于I/O密集型应用）
     */
    private final EventLoopGroup workerGroup;
    
    public NettyReactorArchitecture() {
        // 在8GB堆内存环境下的优化配置
        int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
        
        this.bossGroup = new NioEventLoopGroup(1, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Arthas-Boss-" + counter.incrementAndGet());
                t.setDaemon(false);
                // 设置较高优先级，确保连接接受的及时性
                t.setPriority(Thread.NORM_PRIORITY + 1);
                return t;
            }
        });
        
        this.workerGroup = new NioEventLoopGroup(workerThreads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Arthas-Worker-" + counter.incrementAndGet());
                t.setDaemon(false);
                return t;
            }
        });
    }
    
    /**
     * EventLoop任务调度机制
     * 理解Arthas中命令执行的异步处理
     */
    public void demonstrateEventLoopScheduling() {
        EventLoop eventLoop = workerGroup.next();
        
        // 1. 立即执行任务
        eventLoop.execute(() -> {
            System.out.println("立即执行的任务 - 线程: " + Thread.currentThread().getName());
        });
        
        // 2. 延迟执行任务（类似Arthas的定时命令）
        ScheduledFuture<?> future = eventLoop.schedule(() -> {
            System.out.println("延迟执行的任务 - 线程: " + Thread.currentThread().getName());
        }, 5, TimeUnit.SECONDS);
        
        // 3. 周期性执行任务（类似Arthas的watch命令）
        ScheduledFuture<?> periodicFuture = eventLoop.scheduleAtFixedRate(() -> {
            System.out.println("周期性任务 - 时间: " + System.currentTimeMillis());
        }, 0, 1, TimeUnit.SECONDS);
        
        // 任务取消机制
        eventLoop.schedule(() -> {
            periodicFuture.cancel(false);
            System.out.println("周期性任务已取消");
        }, 10, TimeUnit.SECONDS);
    }
}
```

#### **EventLoop深度分析**

```java
/**
 * EventLoop内部机制深度解析
 * 理解Arthas命令处理的底层原理
 */
public class EventLoopInternals {
    
    /**
     * EventLoop的任务队列机制
     * 对应Arthas中命令的排队和执行
     */
    public static class TaskQueueAnalysis {
        
        public void analyzeTaskQueue(EventLoop eventLoop) {
            // EventLoop内部使用的任务队列类型
            System.out.println("EventLoop类型: " + eventLoop.getClass().getSimpleName());
            
            // 提交不同类型的任务
            submitIOTask(eventLoop);
            submitComputeTask(eventLoop);
            submitScheduledTask(eventLoop);
        }
        
        private void submitIOTask(EventLoop eventLoop) {
            // I/O任务：类似Arthas的网络通信
            eventLoop.execute(() -> {
                try {
                    // 模拟I/O操作
                    Thread.sleep(100);
                    System.out.println("I/O任务完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        private void submitComputeTask(EventLoop eventLoop) {
            // 计算任务：类似Arthas的数据分析
            eventLoop.execute(() -> {
                // 模拟CPU密集型操作
                long sum = 0;
                for (int i = 0; i < 1000000; i++) {
                    sum += i;
                }
                System.out.println("计算任务完成，结果: " + sum);
            });
        }
        
        private void submitScheduledTask(EventLoop eventLoop) {
            // 定时任务：类似Arthas的watch、monitor命令
            eventLoop.scheduleAtFixedRate(() -> {
                System.out.println("定时监控任务 - " + new Date());
            }, 0, 2, TimeUnit.SECONDS);
        }
    }
}
```

### **1.2 Channel与ChannelPipeline架构**

#### **Channel生命周期管理**

```java
/**
 * Channel生命周期与Arthas连接管理
 * 理解Arthas客户端连接的完整生命周期
 */
public class ArthasChannelLifecycle extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasChannelLifecycle.class);
    private final Map<String, ChannelMetrics> channelMetrics = new ConcurrentHashMap<>();
    
    /**
     * Channel注册事件
     * 对应Arthas客户端开始连接过程
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        ChannelMetrics metrics = new ChannelMetrics();
        metrics.registeredTime = System.currentTimeMillis();
        channelMetrics.put(channelId, metrics);
        
        logger.info("Channel注册: {} - 远程地址: {}", 
                   channelId, ctx.channel().remoteAddress());
        
        super.channelRegistered(ctx);
    }
    
    /**
     * Channel激活事件
     * 对应Arthas客户端成功连接到服务端
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        ChannelMetrics metrics = channelMetrics.get(channelId);
        if (metrics != null) {
            metrics.activeTime = System.currentTimeMillis();
            metrics.connectionDuration = metrics.activeTime - metrics.registeredTime;
        }
        
        // 发送欢迎消息（类似Arthas的启动信息）
        String welcomeMessage = buildWelcomeMessage(ctx.channel());
        ctx.writeAndFlush(welcomeMessage);
        
        logger.info("Channel激活: {} - 连接耗时: {}ms", 
                   channelId, metrics != null ? metrics.connectionDuration : 0);
        
        super.channelActive(ctx);
    }
    
    /**
     * Channel非激活事件
     * 对应Arthas客户端断开连接
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        ChannelMetrics metrics = channelMetrics.get(channelId);
        if (metrics != null) {
            metrics.inactiveTime = System.currentTimeMillis();
            metrics.totalDuration = metrics.inactiveTime - metrics.activeTime;
            
            logger.info("Channel断开: {} - 总连接时长: {}ms, 处理消息数: {}", 
                       channelId, metrics.totalDuration, metrics.messageCount);
        }
        
        // 清理资源
        cleanupChannelResources(ctx.channel());
        channelMetrics.remove(channelId);
        
        super.channelInactive(ctx);
    }
    
    /**
     * 消息读取事件
     * 对应Arthas命令的接收和处理
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        ChannelMetrics metrics = channelMetrics.get(channelId);
        if (metrics != null) {
            metrics.messageCount++;
            metrics.lastMessageTime = System.currentTimeMillis();
        }
        
        // 处理不同类型的消息
        if (msg instanceof String) {
            handleStringMessage(ctx, (String) msg);
        } else if (msg instanceof ByteBuf) {
            handleByteBufMessage(ctx, (ByteBuf) msg);
        } else {
            logger.warn("未知消息类型: {}", msg.getClass().getSimpleName());
        }
        
        super.channelRead(ctx, msg);
    }
    
    /**
     * 异常处理
     * 对应Arthas的错误处理机制
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        logger.error("Channel异常: {} - 错误: {}", channelId, cause.getMessage(), cause);
        
        // 发送错误信息给客户端
        String errorMessage = "服务器内部错误: " + cause.getMessage();
        ctx.writeAndFlush(errorMessage).addListener(ChannelFutureListener.CLOSE);
    }
    
    // 辅助方法
    private String buildWelcomeMessage(Channel channel) {
        return String.format(
            "欢迎连接到Arthas服务器!\n" +
            "连接ID: %s\n" +
            "本地地址: %s\n" +
            "远程地址: %s\n" +
            "连接时间: %s\n",
            channel.id().asShortText(),
            channel.localAddress(),
            channel.remoteAddress(),
            new Date()
        );
    }
    
    private void handleStringMessage(ChannelHandlerContext ctx, String message) {
        logger.info("收到字符串消息: {}", message);
        // 这里可以添加Arthas命令解析逻辑
    }
    
    private void handleByteBufMessage(ChannelHandlerContext ctx, ByteBuf byteBuf) {
        int readableBytes = byteBuf.readableBytes();
        logger.info("收到字节消息，长度: {}", readableBytes);
        // 这里可以添加二进制协议解析逻辑
    }
    
    private void cleanupChannelResources(Channel channel) {
        // 清理与该Channel相关的资源
        logger.info("清理Channel资源: {}", channel.id().asShortText());
    }
    
    /**
     * Channel指标数据
     */
    private static class ChannelMetrics {
        long registeredTime;
        long activeTime;
        long inactiveTime;
        long lastMessageTime;
        long connectionDuration;
        long totalDuration;
        int messageCount;
    }
}
```

#### **ChannelPipeline处理链**

```java
/**
 * Arthas风格的ChannelPipeline配置
 * 完整的消息处理链路
 */
public class ArthasPipelineInitializer extends ChannelInitializer<SocketChannel> {
    
    private final boolean enableSsl;
    private final SslContext sslContext;
    
    public ArthasPipelineInitializer(boolean enableSsl, SslContext sslContext) {
        this.enableSsl = enableSsl;
        this.sslContext = sslContext;
    }
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // 1. SSL/TLS支持（企业级安全）
        if (enableSsl && sslContext != null) {
            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
        }
        
        // 2. 连接空闲检测（防止僵尸连接）
        pipeline.addLast("idleStateHandler", new IdleStateHandler(
            60, 30, 0, TimeUnit.SECONDS));
        
        // 3. 日志记录（调试和监控）
        pipeline.addLast("loggingHandler", new LoggingHandler(LogLevel.DEBUG));
        
        // 4. 帧解码器（处理TCP粘包/拆包）
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
            1024 * 1024, 0, 4, 0, 4));
        
        // 5. 帧编码器
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(4));
        
        // 6. 字符串解码器
        pipeline.addLast("stringDecoder", new StringDecoder(CharsetUtil.UTF_8));
        
        // 7. 字符串编码器
        pipeline.addLast("stringEncoder", new StringEncoder(CharsetUtil.UTF_8));
        
        // 8. 空闲连接处理
        pipeline.addLast("idleHandler", new IdleConnectionHandler());
        
        // 9. Arthas协议处理器
        pipeline.addLast("arthasProtocolHandler", new ArthasProtocolHandler());
        
        // 10. Arthas命令处理器
        pipeline.addLast("arthasCommandHandler", new ArthasCommandHandler());
        
        // 11. 异常处理器（最后一个）
        pipeline.addLast("exceptionHandler", new GlobalExceptionHandler());
    }
}

/**
 * 空闲连接处理器
 * 处理客户端超时和心跳机制
 */
class IdleConnectionHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(IdleConnectionHandler.class);
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            String channelId = ctx.channel().id().asShortText();
            
            switch (event.state()) {
                case READER_IDLE:
                    logger.warn("Channel读空闲: {} - 60秒内未收到数据", channelId);
                    // 发送心跳请求
                    ctx.writeAndFlush("PING").addListener(future -> {
                        if (!future.isSuccess()) {
                            logger.error("发送心跳失败: {}", channelId);
                            ctx.close();
                        }
                    });
                    break;
                    
                case WRITER_IDLE:
                    logger.warn("Channel写空闲: {} - 30秒内未发送数据", channelId);
                    // 发送心跳
                    ctx.writeAndFlush("HEARTBEAT");
                    break;
                    
                case ALL_IDLE:
                    logger.warn("Channel全空闲: {} - 关闭连接", channelId);
                    ctx.close();
                    break;
            }
        }
        super.userEventTriggered(ctx, evt);
    }
}
```

---

## 📚 **第二部分：Arthas协议设计与实现**

### **2.1 Arthas通信协议深度解析**

#### **协议格式设计**

```java
/**
 * Arthas通信协议定义
 * 基于JSON的可扩展协议格式
 */
public class ArthasProtocol {
    
    /**
     * 协议版本
     */
    public static final String PROTOCOL_VERSION = "3.6.7";
    
    /**
     * 消息类型枚举
     */
    public enum MessageType {
        // 连接管理
        CONNECT_REQUEST("connect_request"),
        CONNECT_RESPONSE("connect_response"),
        DISCONNECT("disconnect"),
        
        // 命令执行
        COMMAND_REQUEST("command_request"),
        COMMAND_RESPONSE("command_response"),
        COMMAND_RESULT("command_result"),
        
        // 事件通知
        EVENT_NOTIFICATION("event_notification"),
        
        // 心跳机制
        HEARTBEAT("heartbeat"),
        HEARTBEAT_ACK("heartbeat_ack"),
        
        // 错误处理
        ERROR("error");
        
        private final String type;
        
        MessageType(String type) {
            this.type = type;
        }
        
        public String getType() {
            return type;
        }
    }
    
    /**
     * 协议消息基类
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ConnectRequest.class, name = "connect_request"),
        @JsonSubTypes.Type(value = ConnectResponse.class, name = "connect_response"),
        @JsonSubTypes.Type(value = CommandRequest.class, name = "command_request"),
        @JsonSubTypes.Type(value = CommandResponse.class, name = "command_response"),
        @JsonSubTypes.Type(value = EventNotification.class, name = "event_notification"),
        @JsonSubTypes.Type(value = ErrorMessage.class, name = "error")
    })
    public static abstract class Message {
        @JsonProperty("id")
        private String id = UUID.randomUUID().toString();
        
        @JsonProperty("timestamp")
        private long timestamp = System.currentTimeMillis();
        
        @JsonProperty("version")
        private String version = PROTOCOL_VERSION;
        
        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
    
    /**
     * 连接请求消息
     */
    public static class ConnectRequest extends Message {
        @JsonProperty("clientInfo")
        private ClientInfo clientInfo;
        
        @JsonProperty("authToken")
        private String authToken;
        
        public static class ClientInfo {
            private String clientId;
            private String clientVersion;
            private String javaVersion;
            private String osName;
            private String osVersion;
            
            // Getters and setters
            public String getClientId() { return clientId; }
            public void setClientId(String clientId) { this.clientId = clientId; }
            
            public String getClientVersion() { return clientVersion; }
            public void setClientVersion(String clientVersion) { this.clientVersion = clientVersion; }
            
            public String getJavaVersion() { return javaVersion; }
            public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }
            
            public String getOsName() { return osName; }
            public void setOsName(String osName) { this.osName = osName; }
            
            public String getOsVersion() { return osVersion; }
            public void setOsVersion(String osVersion) { this.osVersion = osVersion; }
        }
        
        // Getters and setters
        public ClientInfo getClientInfo() { return clientInfo; }
        public void setClientInfo(ClientInfo clientInfo) { this.clientInfo = clientInfo; }
        
        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
    }
    
    /**
     * 连接响应消息
     */
    public static class ConnectResponse extends Message {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("sessionId")
        private String sessionId;
        
        @JsonProperty("serverInfo")
        private ServerInfo serverInfo;
        
        @JsonProperty("errorMessage")
        private String errorMessage;
        
        public static class ServerInfo {
            private String serverId;
            private String serverVersion;
            private String jvmInfo;
            private long startTime;
            private int activeConnections;
            
            // Getters and setters
            public String getServerId() { return serverId; }
            public void setServerId(String serverId) { this.serverId = serverId; }
            
            public String getServerVersion() { return serverVersion; }
            public void setServerVersion(String serverVersion) { this.serverVersion = serverVersion; }
            
            public String getJvmInfo() { return jvmInfo; }
            public void setJvmInfo(String jvmInfo) { this.jvmInfo = jvmInfo; }
            
            public long getStartTime() { return startTime; }
            public void setStartTime(long startTime) { this.startTime = startTime; }
            
            public int getActiveConnections() { return activeConnections; }
            public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }
        }
        
        // Getters and setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public ServerInfo getServerInfo() { return serverInfo; }
        public void setServerInfo(ServerInfo serverInfo) { this.serverInfo = serverInfo; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
    
    /**
     * 命令请求消息
     */
    public static class CommandRequest extends Message {
        @JsonProperty("sessionId")
        private String sessionId;
        
        @JsonProperty("commandLine")
        private String commandLine;
        
        @JsonProperty("commandArgs")
        private Map<String, Object> commandArgs;
        
        @JsonProperty("async")
        private boolean async = false;
        
        @JsonProperty("timeout")
        private long timeout = 30000; // 30秒默认超时
        
        // Getters and setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public String getCommandLine() { return commandLine; }
        public void setCommandLine(String commandLine) { this.commandLine = commandLine; }
        
        public Map<String, Object> getCommandArgs() { return commandArgs; }
        public void setCommandArgs(Map<String, Object> commandArgs) { this.commandArgs = commandArgs; }
        
        public boolean isAsync() { return async; }
        public void setAsync(boolean async) { this.async = async; }
        
        public long getTimeout() { return timeout; }
        public void setTimeout(long timeout) { this.timeout = timeout; }
    }
    
    /**
     * 命令响应消息
     */
    public static class CommandResponse extends Message {
        @JsonProperty("requestId")
        private String requestId;
        
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("result")
        private Object result;
        
        @JsonProperty("errorMessage")
        private String errorMessage;
        
        @JsonProperty("executionTime")
        private long executionTime;
        
        @JsonProperty("hasMore")
        private boolean hasMore = false;
        
        // Getters and setters
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public long getExecutionTime() { return executionTime; }
        public void setExecutionTime(long executionTime) { this.executionTime = executionTime; }
        
        public boolean isHasMore() { return hasMore; }
        public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    }
    
    /**
     * 事件通知消息
     */
    public static class EventNotification extends Message {
        @JsonProperty("eventType")
        private String eventType;
        
        @JsonProperty("eventData")
        private Object eventData;
        
        @JsonProperty("source")
        private String source;
        
        // Getters and setters
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        
        public Object getEventData() { return eventData; }
        public void setEventData(Object eventData) { this.eventData = eventData; }
        
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }
    
    /**
     * 错误消息
     */
    public static class ErrorMessage extends Message {
        @JsonProperty("errorCode")
        private String errorCode;
        
        @JsonProperty("errorMessage")
        private String errorMessage;
        
        @JsonProperty("stackTrace")
        private String stackTrace;
        
        // Getters and setters
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public String getStackTrace() { return stackTrace; }
        public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    }
}
```

#### **协议处理器实现**

```java
/**
 * Arthas协议处理器
 * 负责协议消息的编解码和路由
 */
@ChannelHandler.Sharable
public class ArthasProtocolHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasProtocolHandler.class);
    private final ObjectMapper objectMapper;
    private final Map<String, ArthasSession> sessions = new ConcurrentHashMap<>();
    
    public ArthasProtocolHandler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof String) {
            String jsonMessage = (String) msg;
            
            try {
                // 解析协议消息
                ArthasProtocol.Message message = objectMapper.readValue(
                    jsonMessage, ArthasProtocol.Message.class);
                
                // 路由到相应的处理方法
                handleMessage(ctx, message);
                
            } catch (JsonProcessingException e) {
                logger.error("协议解析失败: {}", jsonMessage, e);
                sendErrorResponse(ctx, "PROTOCOL_PARSE_ERROR", 
                                "协议解析失败: " + e.getMessage(), null);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }
    
    /**
     * 消息路由处理
     */
    private void handleMessage(ChannelHandlerContext ctx, ArthasProtocol.Message message) {
        try {
            if (message instanceof ArthasProtocol.ConnectRequest) {
                handleConnectRequest(ctx, (ArthasProtocol.ConnectRequest) message);
            } else if (message instanceof ArthasProtocol.CommandRequest) {
                handleCommandRequest(ctx, (ArthasProtocol.CommandRequest) message);
            } else if (message instanceof ArthasProtocol.EventNotification) {
                handleEventNotification(ctx, (ArthasProtocol.EventNotification) message);
            } else {
                logger.warn("未知消息类型: {}", message.getClass().getSimpleName());
            }
        } catch (Exception e) {
            logger.error("消息处理失败", e);
            sendErrorResponse(ctx, "MESSAGE_HANDLE_ERROR", 
                            "消息处理失败: " + e.getMessage(), message.getId());
        }
    }
    
    /**
     * 处理连接请求
     */
    private void handleConnectRequest(ChannelHandlerContext ctx, 
                                    ArthasProtocol.ConnectRequest request) {
        String channelId = ctx.channel().id().asShortText();
        logger.info("处理连接请求: {} - 客户端: {}", 
                   channelId, request.getClientInfo().getClientId());
        
        // 验证认证令牌
        if (!validateAuthToken(request.getAuthToken())) {
            sendConnectResponse(ctx, false, null, "认证失败", request.getId());
            return;
        }
        
        // 创建会话
        ArthasSession session = createSession(ctx, request);
        sessions.put(session.getSessionId(), session);
        
        // 发送连接响应
        sendConnectResponse(ctx, true, session, null, request.getId());
        
        logger.info("连接建立成功: {} - 会话ID: {}", channelId, session.getSessionId());
    }
    
    /**
     * 处理命令请求
     */
    private void handleCommandRequest(ChannelHandlerContext ctx, 
                                    ArthasProtocol.CommandRequest request) {
        String sessionId = request.getSessionId();
        ArthasSession session = sessions.get(sessionId);
        
        if (session == null) {
            sendErrorResponse(ctx, "INVALID_SESSION", 
                            "无效的会话ID: " + sessionId, request.getId());
            return;
        }
        
        logger.info("处理命令请求: {} - 命令: {}", 
                   sessionId, request.getCommandLine());
        
        // 异步执行命令
        if (request.isAsync()) {
            executeCommandAsync(ctx, session, request);
        } else {
            executeCommandSync(ctx, session, request);
        }
    }
    
    /**
     * 同步执行命令
     */
    private void executeCommandSync(ChannelHandlerContext ctx, 
                                  ArthasSession session, 
                                  ArthasProtocol.CommandRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 解析命令
            String[] args = parseCommandLine(request.getCommandLine());
            String commandName = args[0];
            
            // 执行命令
            Object result = executeCommand(session, commandName, args, request.getCommandArgs());
            
            // 发送响应
            long executionTime = System.currentTimeMillis() - startTime;
            sendCommandResponse(ctx, request.getId(), true, result, null, executionTime, false);
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            logger.error("命令执行失败: {}", request.getCommandLine(), e);
            sendCommandResponse(ctx, request.getId(), false, null, 
                              e.getMessage(), executionTime, false);
        }
    }
    
    /**
     * 异步执行命令
     */
    private void executeCommandAsync(ChannelHandlerContext ctx, 
                                   ArthasSession session, 
                                   ArthasProtocol.CommandRequest request) {
        // 使用EventLoop执行异步任务
        ctx.executor().execute(() -> {
            executeCommandSync(ctx, session, request);
        });
        
        // 立即发送确认响应
        sendCommandResponse(ctx, request.getId(), true, 
                          "命令已提交异步执行", null, 0, true);
    }
    
    /**
     * 发送连接响应
     */
    private void sendConnectResponse(ChannelHandlerContext ctx, boolean success, 
                                   ArthasSession session, String errorMessage, String requestId) {
        ArthasProtocol.ConnectResponse response = new ArthasProtocol.ConnectResponse();
        response.setId(requestId);
        response.setSuccess(success);
        
        if (success && session != null) {
            response.setSessionId(session.getSessionId());
            
            // 设置服务器信息
            ArthasProtocol.ConnectResponse.ServerInfo serverInfo = 
                new ArthasProtocol.ConnectResponse.ServerInfo();
            serverInfo.setServerId("arthas-server-" + System.currentTimeMillis());
            serverInfo.setServerVersion(ArthasProtocol.PROTOCOL_VERSION);
            serverInfo.setJvmInfo(System.getProperty("java.vm.name") + " " + 
                                System.getProperty("java.vm.version"));
            serverInfo.setStartTime(ManagementFactory.getRuntimeMXBean().getStartTime());
            serverInfo.setActiveConnections(sessions.size());
            response.setServerInfo(serverInfo);
        } else {
            response.setErrorMessage(errorMessage);
        }
        
        sendMessage(ctx, response);
    }
    
    /**
     * 发送命令响应
     */
    private void sendCommandResponse(ChannelHandlerContext ctx, String requestId, 
                                   boolean success, Object result, String errorMessage, 
                                   long executionTime, boolean hasMore) {
        ArthasProtocol.CommandResponse response = new ArthasProtocol.CommandResponse();
        response.setRequestId(requestId);
        response.setSuccess(success);
        response.setResult(result);
        response.setErrorMessage(errorMessage);
        response.setExecutionTime(executionTime);
        response.setHasMore(hasMore);
        
        sendMessage(ctx, response);
    }
    
    /**
     * 发送错误响应
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, String errorCode, 
                                 String errorMessage, String requestId) {
        ArthasProtocol.ErrorMessage error = new ArthasProtocol.ErrorMessage();
        if (requestId != null) {
            error.setId(requestId);
        }
        error.setErrorCode(errorCode);
        error.setErrorMessage(errorMessage);
        
        sendMessage(ctx, error);
    }
    
    /**
     * 发送消息
     */
    private void sendMessage(ChannelHandlerContext ctx, ArthasProtocol.Message message) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            ctx.writeAndFlush(jsonMessage);
        } catch (JsonProcessingException e) {
            logger.error("消息序列化失败", e);
        }
    }
    
    // 辅助方法
    private boolean validateAuthToken(String authToken) {
        // 这里实现认证逻辑
        return authToken != null && !authToken.isEmpty();
    }
    
    private ArthasSession createSession(ChannelHandlerContext ctx, 
                                      ArthasProtocol.ConnectRequest request) {
        return new ArthasSession(
            UUID.randomUUID().toString(),
            ctx.channel(),
            request.getClientInfo()
        );
    }
    
    private String[] parseCommandLine(String commandLine) {
        // 简单的命令行解析
        return commandLine.trim().split("\\s+");
    }
    
    private Object executeCommand(ArthasSession session, String commandName, 
                                String[] args, Map<String, Object> commandArgs) {
        // 这里实现具体的命令执行逻辑
        return "命令 " + commandName + " 执行完成";
    }
    
    private void handleEventNotification(ChannelHandlerContext ctx, 
                                       ArthasProtocol.EventNotification notification) {
        // 处理事件通知
        logger.info("收到事件通知: {} - 数据: {}", 
                   notification.getEventType(), notification.getEventData());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 清理会话
        String channelId = ctx.channel().id().asShortText();
        sessions.entrySet().removeIf(entry -> 
            entry.getValue().getChannel().id().asShortText().equals(channelId));
        
        super.channelInactive(ctx);
    }
}

/**
 * Arthas会话管理
 */
class ArthasSession {
    private final String sessionId;
    private final Channel channel;
    private final ArthasProtocol.ConnectRequest.ClientInfo clientInfo;
    private final long createTime;
    private volatile long lastActiveTime;
    
    public ArthasSession(String sessionId, Channel channel, 
                        ArthasProtocol.ConnectRequest.ClientInfo clientInfo) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.clientInfo = clientInfo;
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = createTime;
    }
    
    public void updateLastActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }
    
    // Getters
    public String getSessionId() { return sessionId; }
    public Channel getChannel() { return channel; }
    public ArthasProtocol.ConnectRequest.ClientInfo getClientInfo() { return clientInfo; }
    public long getCreateTime() { return createTime; }
    public long getLastActiveTime() { return lastActiveTime; }
}
```

---

## 📚 **第三部分：WebSocket与HTTP支持**

### **3.1 Arthas Web Console实现**

#### **WebSocket服务器实现**

```java
/**
 * Arthas WebSocket服务器
 * 支持Web Console的实时通信
 */
public class ArthasWebSocketServer {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasWebSocketServer.class);
    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;
    
    public ArthasWebSocketServer(int port) {
        this.port = port;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
    }
    
    /**
     * 启动WebSocket服务器
     */
    public void start() throws InterruptedException {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new WebSocketServerInitializer())
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);
            
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            
            logger.info("Arthas WebSocket服务器启动成功，端口: {}", port);
            logger.info("Web Console访问地址: http://localhost:{}/", port);
            
        } catch (Exception e) {
            logger.error("WebSocket服务器启动失败", e);
            throw e;
        }
    }
    
    /**
     * 停止服务器
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        logger.info("Arthas WebSocket服务器已停止");
    }
}

/**
 * WebSocket服务器初始化器
 */
class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // HTTP编解码器
        pipeline.addLast(new HttpServerCodec());
        
        // HTTP对象聚合器
        pipeline.addLast(new HttpObjectAggregator(65536));
        
        // 静态文件处理器
        pipeline.addLast(new HttpStaticFileHandler());
        
        // WebSocket协议处理器
        pipeline.addLast(new WebSocketServerProtocolHandler("/websocket", null, true));
        
        // Arthas WebSocket处理器
        pipeline.addLast(new ArthasWebSocketHandler());
    }
}

/**
 * HTTP静态文件处理器
 * 提供Web Console的HTML、CSS、JS文件
 */
@ChannelHandler.Sharable
class HttpStaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
    private static final Logger logger = LoggerFactory.getLogger(HttpStaticFileHandler.class);
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        
        if (request.method() != HttpMethod.GET) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }
        
        String uri = request.uri();
        String path = sanitizeUri(uri);
        
        if (path == null) {
            sendError(ctx, HttpResponseStatus.FORBIDDEN);
            return;
        }
        
        // 处理根路径
        if ("/".equals(path)) {
            path = "/index.html";
        }
        
        // 发送静态文件
        sendStaticFile(ctx, path);
    }
    
    private void sendStaticFile(ChannelHandlerContext ctx, String path) {
        try {
            // 从classpath加载静态资源
            InputStream inputStream = getClass().getResourceAsStream("/web" + path);
            if (inputStream == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }
            
            byte[] content = inputStream.readAllBytes();
            inputStream.close();
            
            // 确定内容类型
            String contentType = getContentType(path);
            
            // 创建响应
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, 
                Unpooled.wrappedBuffer(content));
            
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
            
            // 发送响应
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            
        } catch (IOException e) {
            logger.error("读取静态文件失败: {}", path, e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        String content = "HTTP " + status.code() + " " + status.reasonPhrase();
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, 
            Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
        
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length());
        
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
    
    private String sanitizeUri(String uri) {
        try {
            uri = URLDecoder.decode(uri, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        
        if (uri.isEmpty() || uri.charAt(0) != '/') {
            return null;
        }
        
        // 防止目录遍历攻击
        if (uri.contains("/../") || uri.contains("..\\") || 
            uri.startsWith("../") || uri.endsWith("..")) {
            return null;
        }
        
        return uri;
    }
    
    private String getContentType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        } else if (path.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        } else if (path.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        } else if (path.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        } else if (path.endsWith(".png")) {
            return "image/png";
        } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (path.endsWith(".gif")) {
            return "image/gif";
        } else {
            return "application/octet-stream";
        }
    }
}

/**
 * Arthas WebSocket处理器
 * 处理Web Console的实时命令交互
 */
@ChannelHandler.Sharable
class ArthasWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasWebSocketHandler.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        String sessionId = ctx.channel().id().asLongText();
        WebSocketSession session = new WebSocketSession(sessionId, ctx.channel());
        sessions.put(sessionId, session);
        
        logger.info("WebSocket连接建立: {}", sessionId);
        
        // 发送欢迎消息
        sendWelcomeMessage(ctx);
    }
    
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        String sessionId = ctx.channel().id().asLongText();
        sessions.remove(sessionId);
        logger.info("WebSocket连接断开: {}", sessionId);
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
     * 处理文本帧（命令输入）
     */
    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String sessionId = ctx.channel().id().asLongText();
        String message = frame.text();
        
        logger.info("收到WebSocket消息: {} - 内容: {}", sessionId, message);
        
        try {
            // 解析消息
            ObjectMapper mapper = new ObjectMapper();
            JsonNode messageNode = mapper.readTree(message);
            
            String type = messageNode.get("type").asText();
            
            switch (type) {
                case "command":
                    handleCommand(ctx, messageNode);
                    break;
                case "heartbeat":
                    handleHeartbeat(ctx);
                    break;
                default:
                    logger.warn("未知消息类型: {}", type);
            }
            
        } catch (Exception e) {
            logger.error("处理WebSocket消息失败", e);
            sendErrorMessage(ctx, "消息处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理二进制帧
     */
    private void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        // 处理二进制数据（如文件上传等）
        logger.info("收到二进制数据，长度: {}", frame.content().readableBytes());
    }
    
    /**
     * 处理命令执行
     */
    private void handleCommand(ChannelHandlerContext ctx, JsonNode messageNode) {
        String command = messageNode.get("command").asText();
        String requestId = messageNode.get("id").asText();
        
        // 异步执行命令
        ctx.executor().execute(() -> {
            try {
                // 模拟命令执行
                String result = executeArthasCommand(command);
                
                // 发送结果
                sendCommandResult(ctx, requestId, true, result, null);
                
            } catch (Exception e) {
                logger.error("命令执行失败: {}", command, e);
                sendCommandResult(ctx, requestId, false, null, e.getMessage());
            }
        });
        
        // 立即发送确认
        sendCommandAck(ctx, requestId);
    }
    
    /**
     * 处理心跳
     */
    private void handleHeartbeat(ChannelHandlerContext ctx) {
        String sessionId = ctx.channel().id().asLongText();
        WebSocketSession session = sessions.get(sessionId);
        if (session != null) {
            session.updateLastActiveTime();
        }
        
        // 发送心跳响应
        Map<String, Object> response = new HashMap<>();
        response.put("type", "heartbeat_ack");
        response.put("timestamp", System.currentTimeMillis());
        
        sendMessage(ctx, response);
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
        
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("javaVersion", System.getProperty("java.version"));
        serverInfo.put("jvmName", System.getProperty("java.vm.name"));
        serverInfo.put("osName", System.getProperty("os.name"));
        serverInfo.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        serverInfo.put("maxMemory", Runtime.getRuntime().maxMemory());
        serverInfo.put("totalMemory", Runtime.getRuntime().totalMemory());
        serverInfo.put("freeMemory", Runtime.getRuntime().freeMemory());
        
        welcome.put("serverInfo", serverInfo);
        
        sendMessage(ctx, welcome);
    }
    
    /**
     * 发送命令确认
     */
    private void sendCommandAck(ChannelHandlerContext ctx, String requestId) {
        Map<String, Object> ack = new HashMap<>();
        ack.put("type", "command_ack");
        ack.put("id", requestId);
        ack.put("message", "命令已接收，正在执行...");
        ack.put("timestamp", System.currentTimeMillis());
        
        sendMessage(ctx, ack);
    }
    
    /**
     * 发送命令结果
     */
    private void sendCommandResult(ChannelHandlerContext ctx, String requestId, 
                                 boolean success, String result, String error) {
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
    private void sendErrorMessage(ChannelHandlerContext ctx, String errorMessage) {
        Map<String, Object> error = new HashMap<>();
        error.put("type", "error");
        error.put("message", errorMessage);
        error.put("timestamp", System.currentTimeMillis());
        
        sendMessage(ctx, error);
    }
    
    /**
     * 发送消息
     */
    private void sendMessage(ChannelHandlerContext ctx, Object message) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(message);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("发送WebSocket消息失败", e);
        }
    }
    
    /**
     * 模拟Arthas命令执行
     */
    private String executeArthasCommand(String command) throws InterruptedException {
        // 模拟命令执行时间
        Thread.sleep(1000);
        
        // 根据命令类型返回不同结果
        if (command.startsWith("jvm")) {
            return getJvmInfo();
        } else if (command.startsWith("thread")) {
            return getThreadInfo();
        } else if (command.startsWith("dashboard")) {
            return getDashboardInfo();
        } else {
            return "命令 '" + command + "' 执行完成\n结果: 模拟输出数据";
        }
    }
    
    private String getJvmInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("JVM信息:\n");
        sb.append("Java版本: ").append(System.getProperty("java.version")).append("\n");
        sb.append("JVM名称: ").append(System.getProperty("java.vm.name")).append("\n");
        sb.append("JVM版本: ").append(System.getProperty("java.vm.version")).append("\n");
        sb.append("操作系统: ").append(System.getProperty("os.name")).append("\n");
        sb.append("处理器数量: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        sb.append("最大内存: ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB\n");
        sb.append("总内存: ").append(Runtime.getRuntime().totalMemory() / 1024 / 1024).append(" MB\n");
        sb.append("空闲内存: ").append(Runtime.getRuntime().freeMemory() / 1024 / 1024).append(" MB\n");
        return sb.toString();
    }
    
    private String getThreadInfo() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("线程信息:\n");
        sb.append("活动线程数: ").append(threadBean.getThreadCount()).append("\n");
        sb.append("守护线程数: ").append(threadBean.getDaemonThreadCount()).append("\n");
        sb.append("峰值线程数: ").append(threadBean.getPeakThreadCount()).append("\n");
        sb.append("总启动线程数: ").append(threadBean.getTotalStartedThreadCount()).append("\n");
        return sb.toString();
    }
    
    private String getDashboardInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("系统仪表板:\n");
        sb.append("当前时间: ").append(new Date()).append("\n");
        sb.append("运行时长: ").append(ManagementFactory.getRuntimeMXBean().getUptime()).append(" ms\n");
        sb.append("类加载数量: ").append(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()).append("\n");
        sb.append("编译时间: ").append(ManagementFactory.getCompilationMXBean().getTotalCompilationTime()).append(" ms\n");
        return sb.toString();
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("WebSocket处理异常", cause);
        ctx.close();
    }
}

/**
 * WebSocket会话管理
 */
class WebSocketSession {
    private final String sessionId;
    private final Channel channel;
    private final long createTime;
    private volatile long lastActiveTime;
    
    public WebSocketSession(String sessionId, Channel channel) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = createTime;
    }
    
    public void updateLastActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }
    
    // Getters
    public String getSessionId() { return sessionId; }
    public Channel getChannel() { return channel; }
    public long getCreateTime() { return createTime; }
    public long getLastActiveTime() { return lastActiveTime; }
}
```

---

## 📚 **第四部分：性能优化与故障诊断**

### **4.1 Netty性能调优策略**

#### **内存管理优化**

```java
/**
 * Netty内存管理优化
 * 基于8GB堆内存的最佳实践配置
 */
public class NettyMemoryOptimization {
    
    /**
     * 优化的EventLoopGroup配置
     * 针对8GB堆内存环境的线程池设置
     */
    public static EventLoopGroup createOptimizedEventLoopGroup(boolean isBoss) {
        int threadCount = isBoss ? 1 : Runtime.getRuntime().availableProcessors() * 2;
        
        return new NioEventLoopGroup(threadCount, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            private final String prefix = isBoss ? "Arthas-Boss-" : "Arthas-Worker-";
            
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + counter.incrementAndGet());
                t.setDaemon(false);
                
                // 在8GB堆环境下，适当提高线程优先级
                if (isBoss) {
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                } else {
                    t.setPriority(Thread.NORM_PRIORITY);
                }
                
                return t;
            }
        });
    }
    
    /**
     * ByteBuf分配器优化
     * 针对Arthas大量小消息的场景优化
     */
    public static ByteBufAllocator createOptimizedAllocator() {
        // 使用池化的直接内存分配器
        return new PooledByteBufAllocator(
            true,  // preferDirect: 使用直接内存
            2,     // nHeapArena: 堆内存区域数量（较少，因为主要使用直接内存）
            4,     // nDirectArena: 直接内存区域数量
            8192,  // pageSize: 页大小 8KB
            11,    // maxOrder: 最大块大小 = pageSize * 2^maxOrder = 16MB
            256,   // tinyCacheSize: 微小缓存大小
            256,   // smallCacheSize: 小缓存大小
            64,    // normalCacheSize: 正常缓存大小
            true   // useCacheForAllThreads: 所有线程使用缓存
        );
    }
    
    /**
     * Channel选项优化
     */
    public static void configureChannelOptions(ServerBootstrap bootstrap) {
        // 服务端选项
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)  // 增加连接队列长度
                .option(ChannelOption.SO_REUSEADDR, true)   // 允许地址重用
                .option(ChannelOption.ALLOCATOR, createOptimizedAllocator());
        
        // 客户端连接选项
        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true)     // 启用TCP keepalive
                .childOption(ChannelOption.TCP_NODELAY, true)        // 禁用Nagle算法
                .childOption(ChannelOption.SO_SNDBUF, 65536)         // 发送缓冲区64KB
                .childOption(ChannelOption.SO_RCVBUF, 65536)         // 接收缓冲区64KB
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, 
                           new WriteBufferWaterMark(32 * 1024, 64 * 1024))  // 写缓冲区水位
                .childOption(ChannelOption.ALLOCATOR, createOptimizedAllocator())
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, 
                           new AdaptiveRecvByteBufAllocator(64, 1024, 65536)); // 自适应接收缓冲区
    }
    
    /**
     * 内存泄漏检测配置
     * 在开发和测试环境启用详细检测
     */
    public static void configureLeakDetection() {
        // 设置内存泄漏检测级别
        String leakLevel = System.getProperty("io.netty.leakDetection.level", "SIMPLE");
        System.setProperty("io.netty.leakDetection.level", leakLevel);
        
        // 设置采样率（每1024次分配检测一次）
        System.setProperty("io.netty.leakDetection.samplingInterval", "1024");
        
        // 在8GB堆环境下，可以适当增加直接内存限制
        long directMemoryLimit = 2L * 1024 * 1024 * 1024; // 2GB直接内存
        System.setProperty("io.netty.maxDirectMemory", String.valueOf(directMemoryLimit));
        
        logger.info("Netty内存配置 - 泄漏检测级别: {}, 直接内存限制: {}MB", 
                   leakLevel, directMemoryLimit / 1024 / 1024);
    }
    
    /**
     * 监控内存使用情况
     */
    public static class MemoryMonitor {
        private final ScheduledExecutorService scheduler = 
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "Netty-Memory-Monitor");
                t.setDaemon(true);
                return t;
            });
        
        public void startMonitoring() {
            scheduler.scheduleAtFixedRate(this::logMemoryUsage, 0, 30, TimeUnit.SECONDS);
        }
        
        private void logMemoryUsage() {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
            
            // 获取直接内存使用情况
            long directMemoryUsed = getDirectMemoryUsed();
            
            logger.info("内存使用情况 - 堆内存: {}MB/{} MB, 非堆内存: {}MB/{}MB, 直接内存: {}MB",
                       heapUsage.getUsed() / 1024 / 1024,
                       heapUsage.getMax() / 1024 / 1024,
                       nonHeapUsage.getUsed() / 1024 / 1024,
                       nonHeapUsage.getMax() / 1024 / 1024,
                       directMemoryUsed / 1024 / 1024);
        }
        
        private long getDirectMemoryUsed() {
            try {
                // 通过反射获取直接内存使用量
                Class<?> vmClass = Class.forName("sun.misc.VM");
                Method maxDirectMemoryMethod = vmClass.getDeclaredMethod("maxDirectMemory");
                maxDirectMemoryMethod.setAccessible(true);
                
                // 这里简化处理，实际可以通过MXBean获取更准确的数据
                return 0;
            } catch (Exception e) {
                return 0;
            }
        }
        
        public void stop() {
            scheduler.shutdown();
        }
    }
}
```

#### **连接管理优化**

```java
/**
 * 连接管理和资源池化优化
 * 针对Arthas多客户端连接场景
 */
public class ConnectionManagementOptimization {
    
    /**
     * 连接池管理器
     * 管理客户端连接的生命周期和资源分配
     */
    public static class ConnectionPoolManager {
        private final Map<String, ConnectionPool> pools = new ConcurrentHashMap<>();
        private final ScheduledExecutorService cleanupExecutor;
        
        public ConnectionPoolManager() {
            this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "Connection-Pool-Cleanup");
                t.setDaemon(true);
                return t;
            });
            
            // 每分钟清理一次过期连接
            cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredConnections, 
                                              60, 60, TimeUnit.SECONDS);
        }
        
        /**
         * 获取或创建连接池
         */
        public ConnectionPool getOrCreatePool(String poolName, int maxConnections) {
            return pools.computeIfAbsent(poolName, name -> 
                new ConnectionPool(name, maxConnections));
        }
        
        /**
         * 清理过期连接
         */
        private void cleanupExpiredConnections() {
            long currentTime = System.currentTimeMillis();
            long expireTime = 30 * 60 * 1000; // 30分钟过期
            
            pools.values().forEach(pool -> pool.cleanupExpiredConnections(currentTime - expireTime));
        }
        
        /**
         * 关闭所有连接池
         */
        public void shutdown() {
            pools.values().forEach(ConnectionPool::shutdown);
            pools.clear();
            cleanupExecutor.shutdown();
        }
    }
    
    /**
     * 连接池实现
     */
    public static class ConnectionPool {
        private final String poolName;
        private final int maxConnections;
        private final Queue<PooledConnection> availableConnections = new ConcurrentLinkedQueue<>();
        private final Map<String, PooledConnection> activeConnections = new ConcurrentHashMap<>();
        private final AtomicInteger totalConnections = new AtomicInteger(0);
        
        public ConnectionPool(String poolName, int maxConnections) {
            this.poolName = poolName;
            this.maxConnections = maxConnections;
        }
        
        /**
         * 获取连接
         */
        public PooledConnection acquireConnection() throws InterruptedException {
            // 先尝试从可用连接中获取
            PooledConnection connection = availableConnections.poll();
            if (connection != null && connection.isValid()) {
                activeConnections.put(connection.getId(), connection);
                connection.markActive();
                return connection;
            }
            
            // 如果没有可用连接且未达到最大连接数，创建新连接
            if (totalConnections.get() < maxConnections) {
                connection = createNewConnection();
                if (connection != null) {
                    totalConnections.incrementAndGet();
                    activeConnections.put(connection.getId(), connection);
                    return connection;
                }
            }
            
            // 等待连接可用
            return waitForAvailableConnection();
        }
        
        /**
         * 释放连接
         */
        public void releaseConnection(PooledConnection connection) {
            if (connection == null) return;
            
            activeConnections.remove(connection.getId());
            
            if (connection.isValid()) {
                connection.markIdle();
                availableConnections.offer(connection);
            } else {
                // 连接无效，关闭并减少计数
                connection.close();
                totalConnections.decrementAndGet();
            }
        }
        
        /**
         * 创建新连接
         */
        private PooledConnection createNewConnection() {
            try {
                // 这里创建实际的网络连接
                return new PooledConnection(UUID.randomUUID().toString());
            } catch (Exception e) {
                logger.error("创建连接失败", e);
                return null;
            }
        }
        
        /**
         * 等待可用连接
         */
        private PooledConnection waitForAvailableConnection() throws InterruptedException {
            // 简化实现，实际应该使用条件变量等待
            Thread.sleep(100);
            return acquireConnection();
        }
        
        /**
         * 清理过期连接
         */
        public void cleanupExpiredConnections(long expireTime) {
            availableConnections.removeIf(connection -> {
                if (connection.getLastActiveTime() < expireTime) {
                    connection.close();
                    totalConnections.decrementAndGet();
                    return true;
                }
                return false;
            });
        }
        
        /**
         * 关闭连接池
         */
        public void shutdown() {
            // 关闭所有活动连接
            activeConnections.values().forEach(PooledConnection::close);
            activeConnections.clear();
            
            // 关闭所有可用连接
            availableConnections.forEach(PooledConnection::close);
            availableConnections.clear();
            
            totalConnections.set(0);
        }
        
        /**
         * 获取连接池统计信息
         */
        public ConnectionPoolStats getStats() {
            return new ConnectionPoolStats(
                poolName,
                totalConnections.get(),
                activeConnections.size(),
                availableConnections.size(),
                maxConnections
            );
        }
    }
    
    /**
     * 池化连接
     */
    public static class PooledConnection {
        private final String id;
        private final long createTime;
        private volatile long lastActiveTime;
        private volatile boolean valid = true;
        private Channel channel;
        
        public PooledConnection(String id) {
            this.id = id;
            this.createTime = System.currentTimeMillis();
            this.lastActiveTime = createTime;
        }
        
        public void markActive() {
            this.lastActiveTime = System.currentTimeMillis();
        }
        
        public void markIdle() {
            // 连接变为空闲状态的处理
        }
        
        public boolean isValid() {
            return valid && (channel == null || channel.isActive());
        }
        
        public void close() {
            this.valid = false;
            if (channel != null) {
                channel.close();
            }
        }
        
        // Getters
        public String getId() { return id; }
        public long getCreateTime() { return createTime; }
        public long getLastActiveTime() { return lastActiveTime; }
        public Channel getChannel() { return channel; }
        public void setChannel(Channel channel) { this.channel = channel; }
    }
    
    /**
     * 连接池统计信息
     */
    public static class ConnectionPoolStats {
        private final String poolName;
        private final int totalConnections;
        private final int activeConnections;
        private final int availableConnections;
        private final int maxConnections;
        
        public ConnectionPoolStats(String poolName, int totalConnections, 
                                 int activeConnections, int availableConnections, 
                                 int maxConnections) {
            this.poolName = poolName;
            this.totalConnections = totalConnections;
            this.activeConnections = activeConnections;
            this.availableConnections = availableConnections;
            this.maxConnections = maxConnections;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ConnectionPool[%s] - Total: %d, Active: %d, Available: %d, Max: %d, Usage: %.2f%%",
                poolName, totalConnections, activeConnections, availableConnections, 
                maxConnections, (double) totalConnections / maxConnections * 100
            );
        }
        
        // Getters
        public String getPoolName() { return poolName; }
        public int getTotalConnections() { return totalConnections; }
        public int getActiveConnections() { return activeConnections; }
        public int getAvailableConnections() { return availableConnections; }
        public int getMaxConnections() { return maxConnections; }
    }
}
```

---

## 📚 **第五部分：企业级实战案例**

### **5.1 完整的Arthas风格诊断工具**

现在让我创建一个完整的企业级Netty应用示例：

```java
/**
 * 企业级Arthas风格诊断工具
 * 集成所有Netty最佳实践
 */
public class EnterpriseArthasServer {
    
    private static final Logger logger = LoggerFactory.getLogger(EnterpriseArthasServer.class);
    
    private final int port;
    private final int webPort;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConnectionPoolManager connectionManager;
    private final NettyMemoryOptimization.MemoryMonitor memoryMonitor;
    
    private Channel serverChannel;
    private Channel webServerChannel;
    
    public EnterpriseArthasServer(int port, int webPort) {
        this.port = port;
        this.webPort = webPort;
        
        // 配置内存优化
        NettyMemoryOptimization.configureLeakDetection();
        
        // 创建优化的EventLoopGroup
        this.bossGroup = NettyMemoryOptimization.createOptimizedEventLoopGroup(true);
        this.workerGroup = NettyMemoryOptimization.createOptimizedEventLoopGroup(false);
        
        // 初始化连接管理器
        this.connectionManager = new ConnectionManagementOptimization.ConnectionPoolManager();
        
        // 启动内存监控
        this.memoryMonitor = new NettyMemoryOptimization.MemoryMonitor();
        this.memoryMonitor.startMonitoring();
    }
    
    /**
     * 启动服务器
     */
    public void start() throws InterruptedException {
        try {
            // 启动TCP服务器
            startTcpServer();
            
            // 启动WebSocket服务器
            startWebSocketServer();
            
            logger.info("企业级Arthas服务器启动成功");
            logger.info("TCP端口: {}, WebSocket端口: {}", port, webPort);
            
        } catch (Exception e) {
            logger.error("服务器启动失败", e);
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
                .childHandler(new ArthasPipelineInitializer(false, null));
        
        // 应用性能优化配置
        NettyMemoryOptimization.configureChannelOptions(bootstrap);
        
        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        
        logger.info("TCP服务器启动成功，端口: {}", port);
    }
    
    /**
     * 启动WebSocket服务器
     */
    private void startWebSocketServer() throws InterruptedException {
        ArthasWebSocketServer webSocketServer = new ArthasWebSocketServer(webPort);
        webSocketServer.start();
        
        logger.info("WebSocket服务器启动成功，端口: {}", webPort);
    }
    
    /**
     * 优雅关闭
     */
    public void shutdown() {
        logger.info("开始关闭企业级Arthas服务器...");
        
        // 关闭服务器Channel
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (webServerChannel != null) {
            webServerChannel.close();
        }
        
        // 关闭连接管理器
        connectionManager.shutdown();
        
        // 停止内存监控
        memoryMonitor.stop();
        
        // 关闭EventLoopGroup
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        
        logger.info("企业级Arthas服务器已关闭");
    }
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        int tcpPort = args.length > 0 ? Integer.parseInt(args[0]) : 3658;
        int webPort = args.length > 1 ? Integer.parseInt(args[1]) : 8563;
        
        EnterpriseArthasServer server = new EnterpriseArthasServer(tcpPort, webPort);
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，开始优雅关闭...");
            server.shutdown();
        }));
        
        try {
            server.start();
            
            // 等待服务器关闭
            server.serverChannel.closeFuture().sync();
            
        } catch (InterruptedException e) {
            logger.error("服务器运行被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("服务器运行异常", e);
        }
    }
}
```

现在让我创建配套的Web Console前端文件：