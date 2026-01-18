package com.arthas.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;

import java.util.concurrent.TimeUnit;

/**
 * Arthas TCP服务器管道初始化器
 * 配置TCP连接的处理链路
 */
public class ArthasTcpServerInitializer extends ChannelInitializer<SocketChannel> {
    
    private final ArthasConnectionManager connectionManager;
    
    public ArthasTcpServerInitializer(ArthasConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // 1. 连接生命周期管理
        pipeline.addLast("connectionManager", 
                        new ArthasConnectionManager.ConnectionLifecycleHandler(connectionManager, "TCP"));
        
        // 2. 空闲状态检测（读空闲60秒，写空闲30秒）
        pipeline.addLast("idleStateHandler", 
                        new IdleStateHandler(60, 30, 0, TimeUnit.SECONDS));
        
        // 3. 日志记录（开发环境可启用）
        if (Boolean.getBoolean("arthas.debug")) {
            pipeline.addLast("loggingHandler", new LoggingHandler(LogLevel.DEBUG));
        }
        
        // 4. 帧解码器（基于换行符分割，最大行长度8192）
        pipeline.addLast("frameDecoder", 
                        new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
        
        // 5. 字符串解码器
        pipeline.addLast("stringDecoder", new StringDecoder(CharsetUtil.UTF_8));
        
        // 6. 字符串编码器
        pipeline.addLast("stringEncoder", new StringEncoder(CharsetUtil.UTF_8));
        
        // 7. 空闲连接处理器
        pipeline.addLast("idleHandler", new ArthasIdleStateHandler());
        
        // 8. Arthas命令处理器
        pipeline.addLast("commandHandler", new ArthasTcpCommandHandler(connectionManager));
        
        // 9. 异常处理器（最后一个）
        pipeline.addLast("exceptionHandler", new ArthasExceptionHandler());
    }
}