package com.arthas.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * Arthas TCP命令处理器
 * 处理通过TCP连接发送的Arthas命令
 */
public class ArthasTcpCommandHandler extends SimpleChannelInboundHandler<String> {
    
    private static final Logger logger = LoggerFactory.getLogger(ArthasTcpCommandHandler.class);
    
    private final ArthasConnectionManager connectionManager;
    
    public ArthasTcpCommandHandler(ArthasConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 发送欢迎信息
        String welcome = buildWelcomeMessage();
        ctx.writeAndFlush(welcome + "\n");
        
        // 显示提示符
        ctx.writeAndFlush("[arthas@" + getProcessId() + "]$ ");
        
        super.channelActive(ctx);
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String command) throws Exception {
        String connectionId = ctx.channel().id().asShortText();
        logger.info("收到TCP命令: {} - 内容: {}", connectionId, command);
        
        // 处理命令
        handleCommand(ctx, command.trim());
    }
    
    /**
     * 处理命令
     */
    private void handleCommand(ChannelHandlerContext ctx, String command) {
        if (command.isEmpty()) {
            showPrompt(ctx);
            return;
        }
        
        // 异步执行命令，避免阻塞EventLoop
        CompletableFuture.supplyAsync(() -> executeCommand(command))
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        String error = "命令执行失败: " + throwable.getMessage();
                        ctx.writeAndFlush(error + "\n");
                        logger.error("命令执行异常: {}", command, throwable);
                    } else {
                        ctx.writeAndFlush(result + "\n");
                    }
                    showPrompt(ctx);
                });
    }
    
    /**
     * 执行Arthas命令
     */
    private String executeCommand(String command) {
        String[] parts = command.split("\\s+");
        String commandName = parts[0].toLowerCase();
        
        try {
            switch (commandName) {
                case "help":
                case "h":
                    return getHelpMessage();
                    
                case "dashboard":
                case "dash":
                    return getDashboardInfo();
                    
                case "jvm":
                    return getJvmInfo();
                    
                case "thread":
                    return getThreadInfo(parts);
                    
                case "memory":
                case "mem":
                    return getMemoryInfo();
                    
                case "gc":
                    return getGcInfo();
                    
                case "sysprop":
                    return getSystemProperties(parts);
                    
                case "sysenv":
                    return getSystemEnvironment(parts);
                    
                case "version":
                case "v":
                    return getVersionInfo();
                    
                case "quit":
                case "exit":
                case "q":
                    return "再见！连接将关闭。";
                    
                case "cls":
                case "clear":
                    return "\033[2J\033[H"; // ANSI清屏命令
                    
                default:
                    return "未知命令: " + commandName + "\n输入 'help' 查看可用命令";
            }
        } catch (Exception e) {
            logger.error("执行命令失败: {}", command, e);
            return "命令执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取帮助信息
     */
    private String getHelpMessage() {
        return "\n=== Arthas 命令帮助 ===\n" +
               "dashboard  - 显示系统仪表板\n" +
               "jvm        - 显示JVM信息\n" +
               "thread     - 显示线程信息\n" +
               "memory     - 显示内存信息\n" +
               "gc         - 显示GC信息\n" +
               "sysprop    - 显示系统属性\n" +
               "sysenv     - 显示环境变量\n" +
               "version    - 显示版本信息\n" +
               "clear      - 清屏\n" +
               "quit       - 退出\n" +
               "help       - 显示此帮助信息\n";
    }
    
    /**
     * 获取仪表板信息
     */
    private String getDashboardInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 系统仪表板 ===\n");
        
        // 基本信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        sb.append("进程ID: ").append(getProcessId()).append("\n");
        sb.append("运行时长: ").append(formatDuration(runtimeBean.getUptime())).append("\n");
        sb.append("当前时间: ").append(new Date()).append("\n");
        
        // 内存信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        sb.append("堆内存: ").append(formatBytes(heapUsage.getUsed()))
          .append("/").append(formatBytes(heapUsage.getMax()))
          .append(" (").append(String.format("%.1f%%", 
                (double) heapUsage.getUsed() / heapUsage.getMax() * 100)).append(")\n");
        
        // 线程信息
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        sb.append("线程数: ").append(threadBean.getThreadCount())
          .append(" (守护线程: ").append(threadBean.getDaemonThreadCount()).append(")\n");
        
        // GC信息
        long totalGcTime = 0;
        long totalGcCount = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gcBean.getCollectionTime();
            totalGcCount += gcBean.getCollectionCount();
        }
        sb.append("GC次数: ").append(totalGcCount)
          .append(", GC时间: ").append(totalGcTime).append("ms\n");
        
        // 连接信息
        ArthasConnectionManager.ConnectionStats stats = connectionManager.getConnectionStats();
        sb.append("活动连接: ").append(stats.getActiveConnections())
          .append(" (TCP: ").append(stats.getTcpConnections())
          .append(", Web: ").append(stats.getWebConnections()).append(")\n");
        
        return sb.toString();
    }
    
    /**
     * 获取JVM信息
     */
    private String getJvmInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== JVM信息 ===\n");
        
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        sb.append("JVM名称: ").append(runtimeBean.getVmName()).append("\n");
        sb.append("JVM版本: ").append(runtimeBean.getVmVersion()).append("\n");
        sb.append("JVM厂商: ").append(runtimeBean.getVmVendor()).append("\n");
        sb.append("Java版本: ").append(System.getProperty("java.version")).append("\n");
        sb.append("Java Home: ").append(System.getProperty("java.home")).append("\n");
        sb.append("类路径: ").append(runtimeBean.getClassPath()).append("\n");
        sb.append("启动参数: ").append(String.join(" ", runtimeBean.getInputArguments())).append("\n");
        
        return sb.toString();
    }
    
    /**
     * 获取线程信息
     */
    private String getThreadInfo(String[] parts) {
        StringBuilder sb = new StringBuilder();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        
        if (parts.length > 1 && parts[1].equals("-all")) {
            // 显示所有线程详情
            sb.append("\n=== 所有线程详情 ===\n");
            ThreadInfo[] threadInfos = threadBean.dumpAllThreads(false, false);
            for (ThreadInfo threadInfo : threadInfos) {
                sb.append("线程ID: ").append(threadInfo.getThreadId())
                  .append(", 名称: ").append(threadInfo.getThreadName())
                  .append(", 状态: ").append(threadInfo.getThreadState())
                  .append("\n");
            }
        } else {
            // 显示线程统计
            sb.append("\n=== 线程统计 ===\n");
            sb.append("活动线程数: ").append(threadBean.getThreadCount()).append("\n");
            sb.append("守护线程数: ").append(threadBean.getDaemonThreadCount()).append("\n");
            sb.append("峰值线程数: ").append(threadBean.getPeakThreadCount()).append("\n");
            sb.append("总启动线程数: ").append(threadBean.getTotalStartedThreadCount()).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 获取内存信息
     */
    private String getMemoryInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 内存信息 ===\n");
        
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        // 堆内存
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        sb.append("堆内存:\n");
        sb.append("  初始: ").append(formatBytes(heapUsage.getInit())).append("\n");
        sb.append("  已用: ").append(formatBytes(heapUsage.getUsed())).append("\n");
        sb.append("  已提交: ").append(formatBytes(heapUsage.getCommitted())).append("\n");
        sb.append("  最大: ").append(formatBytes(heapUsage.getMax())).append("\n");
        
        // 非堆内存
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        sb.append("非堆内存:\n");
        sb.append("  已用: ").append(formatBytes(nonHeapUsage.getUsed())).append("\n");
        sb.append("  已提交: ").append(formatBytes(nonHeapUsage.getCommitted())).append("\n");
        
        return sb.toString();
    }
    
    /**
     * 获取GC信息
     */
    private String getGcInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== GC信息 ===\n");
        
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("GC名称: ").append(gcBean.getName()).append("\n");
            sb.append("  收集次数: ").append(gcBean.getCollectionCount()).append("\n");
            sb.append("  收集时间: ").append(gcBean.getCollectionTime()).append("ms\n");
            sb.append("  内存池: ").append(String.join(", ", gcBean.getMemoryPoolNames())).append("\n\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 获取系统属性
     */
    private String getSystemProperties(String[] parts) {
        StringBuilder sb = new StringBuilder();
        
        if (parts.length > 1) {
            // 显示特定属性
            String key = parts[1];
            String value = System.getProperty(key);
            if (value != null) {
                sb.append(key).append(" = ").append(value).append("\n");
            } else {
                sb.append("属性不存在: ").append(key).append("\n");
            }
        } else {
            // 显示所有属性
            sb.append("\n=== 系统属性 ===\n");
            System.getProperties().forEach((key, value) -> 
                sb.append(key).append(" = ").append(value).append("\n"));
        }
        
        return sb.toString();
    }
    
    /**
     * 获取环境变量
     */
    private String getSystemEnvironment(String[] parts) {
        StringBuilder sb = new StringBuilder();
        
        if (parts.length > 1) {
            // 显示特定环境变量
            String key = parts[1];
            String value = System.getenv(key);
            if (value != null) {
                sb.append(key).append(" = ").append(value).append("\n");
            } else {
                sb.append("环境变量不存在: ").append(key).append("\n");
            }
        } else {
            // 显示所有环境变量
            sb.append("\n=== 环境变量 ===\n");
            System.getenv().forEach((key, value) -> 
                sb.append(key).append(" = ").append(value).append("\n"));
        }
        
        return sb.toString();
    }
    
    /**
     * 获取版本信息
     */
    private String getVersionInfo() {
        return "\n=== Arthas版本信息 ===\n" +
               "Arthas版本: 3.6.7 (企业级定制版)\n" +
               "Java版本: " + System.getProperty("java.version") + "\n" +
               "构建时间: " + new Date() + "\n";
    }
    
    /**
     * 显示命令提示符
     */
    private void showPrompt(ChannelHandlerContext ctx) {
        ctx.writeAndFlush("[arthas@" + getProcessId() + "]$ ");
    }
    
    /**
     * 构建欢迎消息
     */
    private String buildWelcomeMessage() {
        return "\n" +
               "  ,---.  ,------. ,--------.,--.  ,--.  ,---.   ,---.  \n" +
               " /  O  \\ |  .--. ''--.  .--'|  '--'  | /  O  \\ '   .-' \n" +
               "|  .-.  ||  '--'.'   |  |   |  .--.  ||  .-.  |`.  `-. \n" +
               "|  | |  ||  |\\  \\    |  |   |  |  |  ||  | |  |.-'    |\n" +
               "`--' `--'`--' '--'   `--'   `--'  `--'`--' `--'`-----' \n" +
               "\n" +
               "欢迎使用 Arthas 企业级定制版!\n" +
               "版本: 3.6.7\n" +
               "进程ID: " + getProcessId() + "\n" +
               "时间: " + new Date() + "\n" +
               "\n" +
               "输入 'help' 查看可用命令\n";
    }
    
    /**
     * 获取进程ID
     */
    private String getProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }
    
    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
    
    /**
     * 格式化持续时间
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return String.format("%d天%d小时%d分钟", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("TCP命令处理异常", cause);
        ctx.writeAndFlush("服务器内部错误: " + cause.getMessage() + "\n");
        showPrompt(ctx);
    }
}