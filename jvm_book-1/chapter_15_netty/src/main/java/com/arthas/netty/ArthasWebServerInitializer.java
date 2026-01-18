package com.arthas.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * Arthas Web服务器管道初始化器
 * 配置HTTP和WebSocket连接的处理链路
 */
public class ArthasWebServerInitializer extends ChannelInitializer<SocketChannel> {
    
    private final ArthasConnectionManager connectionManager;
    
    public ArthasWebServerInitializer(ArthasConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // 1. 连接生命周期管理
        pipeline.addLast("connectionManager", 
                        new ArthasConnectionManager.ConnectionLifecycleHandler(connectionManager, "WEB"));
        
        // 2. 空闲状态检测（读空闲120秒，写空闲60秒）
        pipeline.addLast("idleStateHandler", 
                        new IdleStateHandler(120, 60, 0, TimeUnit.SECONDS));
        
        // 3. 日志记录（开发环境可启用）
        if (Boolean.getBoolean("arthas.debug")) {
            pipeline.addLast("loggingHandler", new LoggingHandler(LogLevel.DEBUG));
        }
        
        // 4. HTTP编解码器
        pipeline.addLast("httpServerCodec", new HttpServerCodec());
        
        // 5. HTTP对象聚合器（最大64KB）
        pipeline.addLast("httpObjectAggregator", new HttpObjectAggregator(65536));
        
        // 6. 静态文件处理器
        pipeline.addLast("staticFileHandler", new ArthasStaticFileHandler());
        
        // 7. WebSocket协议处理器
        pipeline.addLast("webSocketHandler", 
                        new WebSocketServerProtocolHandler("/websocket", null, true));
        
        // 8. 空闲连接处理器
        pipeline.addLast("idleHandler", new ArthasIdleStateHandler());
        
        // 9. Arthas WebSocket处理器
        pipeline.addLast("arthasWebSocketHandler", new ArthasWebSocketHandler(connectionManager));
        
        // 10. 异常处理器（最后一个）
        pipeline.addLast("exceptionHandler", new ArthasExceptionHandler());
    }
}