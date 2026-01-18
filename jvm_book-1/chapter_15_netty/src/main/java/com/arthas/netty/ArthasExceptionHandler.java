package com.arthas.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

/**
 * Arthas全局异常处理器
 * 统一处理管道中的异常
 */
public class ArthasExceptionHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasExceptionHandler.class);
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        String remoteAddress = ctx.channel().remoteAddress() != null ? 
                              ctx.channel().remoteAddress().toString() : "unknown";
        
        // 根据异常类型进行不同处理
        if (cause instanceof ClosedChannelException) {
            // 连接已关闭，正常情况，只记录调试日志
            logger.debug("连接已关闭: {} - 远程地址: {}", channelId, remoteAddress);
        } else if (cause instanceof IOException) {
            // I/O异常，可能是网络问题
            logger.warn("I/O异常: {} - 远程地址: {} - 错误: {}", 
                       channelId, remoteAddress, cause.getMessage());
        } else {
            // 其他异常，记录完整堆栈
            logger.error("未处理的异常: {} - 远程地址: {}", 
                        channelId, remoteAddress, cause);
        }
        
        // 关闭连接
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }
}