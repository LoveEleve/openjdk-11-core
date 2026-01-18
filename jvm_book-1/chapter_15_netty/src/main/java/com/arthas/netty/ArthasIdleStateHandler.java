package com.arthas.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arthas空闲状态处理器
 * 处理连接超时和心跳机制
 */
public class ArthasIdleStateHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasIdleStateHandler.class);
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            String channelId = ctx.channel().id().asShortText();
            String remoteAddress = ctx.channel().remoteAddress().toString();
            
            switch (event.state()) {
                case READER_IDLE:
                    logger.warn("连接读空闲: {} - 远程地址: {}", channelId, remoteAddress);
                    // 发送心跳请求
                    sendHeartbeat(ctx);
                    break;
                    
                case WRITER_IDLE:
                    logger.warn("连接写空闲: {} - 远程地址: {}", channelId, remoteAddress);
                    // 发送心跳
                    sendHeartbeat(ctx);
                    break;
                    
                case ALL_IDLE:
                    logger.warn("连接全空闲，关闭连接: {} - 远程地址: {}", channelId, remoteAddress);
                    ctx.close();
                    break;
            }
        }
        super.userEventTriggered(ctx, evt);
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        // 检查连接是否还活跃
        if (ctx.channel().isActive()) {
            // 对于TCP连接，发送简单的心跳消息
            ctx.writeAndFlush("HEARTBEAT\n").addListener(future -> {
                if (!future.isSuccess()) {
                    logger.error("发送心跳失败，关闭连接: {}", 
                               ctx.channel().id().asShortText());
                    ctx.close();
                }
            });
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("空闲状态处理异常: {}", ctx.channel().id().asShortText(), cause);
        ctx.close();
    }
}