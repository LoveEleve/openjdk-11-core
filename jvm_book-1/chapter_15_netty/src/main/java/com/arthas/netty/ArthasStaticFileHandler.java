package com.arthas.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Arthas静态文件处理器
 * 提供Web Console的HTML、CSS、JS文件服务
 */
@ChannelHandler.Sharable
public class ArthasStaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasStaticFileHandler.class);
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // 只处理HTTP GET请求
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
        
        logger.debug("请求静态文件: {}", path);
        
        // 发送静态文件
        sendStaticFile(ctx, path, request);
    }
    
    /**
     * 发送静态文件
     */
    private void sendStaticFile(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
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
            
            // 设置响应头
            HttpHeaders headers = response.headers();
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
            headers.set(HttpHeaderNames.CONTENT_LENGTH, content.length);
            headers.set(HttpHeaderNames.CACHE_CONTROL, "max-age=3600"); // 缓存1小时
            
            // 处理Keep-Alive
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (keepAlive) {
                headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            
            // 发送响应
            ChannelFuture future = ctx.writeAndFlush(response);
            
            // 如果不是Keep-Alive，发送完毕后关闭连接
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            
            logger.debug("发送静态文件成功: {} - 大小: {}字节", path, content.length);
            
        } catch (IOException e) {
            logger.error("读取静态文件失败: {}", path, e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    /**
     * 发送错误响应
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        String content = generateErrorPage(status);
        
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, 
            Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
        
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length());
        
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
    
    /**
     * 生成错误页面
     */
    private String generateErrorPage(HttpResponseStatus status) {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "    <title>Arthas - " + status.code() + " " + status.reasonPhrase() + "</title>\n" +
               "    <style>\n" +
               "        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }\n" +
               "        .error { color: #d32f2f; }\n" +
               "        .code { font-size: 48px; font-weight: bold; }\n" +
               "        .message { font-size: 18px; margin: 20px 0; }\n" +
               "        .back { margin-top: 30px; }\n" +
               "        a { color: #1976d2; text-decoration: none; }\n" +
               "        a:hover { text-decoration: underline; }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <div class=\"error\">\n" +
               "        <div class=\"code\">" + status.code() + "</div>\n" +
               "        <div class=\"message\">" + status.reasonPhrase() + "</div>\n" +
               "        <div class=\"back\"><a href=\"/\">返回首页</a></div>\n" +
               "    </div>\n" +
               "</body>\n" +
               "</html>";
    }
    
    /**
     * 清理URI，防止目录遍历攻击
     */
    private String sanitizeUri(String uri) {
        try {
            uri = URLDecoder.decode(uri, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        
        if (uri.isEmpty() || uri.charAt(0) != '/') {
            return null;
        }
        
        // 移除查询参数
        int queryIndex = uri.indexOf('?');
        if (queryIndex != -1) {
            uri = uri.substring(0, queryIndex);
        }
        
        // 防止目录遍历攻击
        if (uri.contains("/../") || uri.contains("..\\") || 
            uri.startsWith("../") || uri.endsWith("..") ||
            uri.contains("./") || uri.contains(".\\")) {
            return null;
        }
        
        return uri;
    }
    
    /**
     * 根据文件扩展名确定内容类型
     */
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
        } else if (path.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (path.endsWith(".ico")) {
            return "image/x-icon";
        } else if (path.endsWith(".woff")) {
            return "font/woff";
        } else if (path.endsWith(".woff2")) {
            return "font/woff2";
        } else if (path.endsWith(".ttf")) {
            return "font/ttf";
        } else {
            return "application/octet-stream";
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("静态文件处理异常", cause);
        if (ctx.channel().isActive()) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }
}