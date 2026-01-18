import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import java.lang.management.*;

/**
 * 基于OpenJDK11 ClassLoader源码的内存泄漏复现
 * 模拟真实的类加载器内存泄漏场景
 * 
 * 运行参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=1g
 */
public class ClassLoaderLeakTest {
    
    // 模拟静态缓存 - 这是内存泄漏的常见原因
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final List<ClassLoader> LOADER_CACHE = new ArrayList<>();
    private static final List<Object> PROXY_CACHE = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== ClassLoader内存泄漏测试开始 ===");
        System.out.println("JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=1g");
        
        // 启动监控
        startClassLoaderMonitor();
        
        // 模拟不同的内存泄漏场景
        simulateClassLoaderLeak();
        
        // 等待观察
        System.out.println("等待30秒观察内存变化...");
        Thread.sleep(30000);
        
        // 尝试清理
        attemptCleanup();
        
        System.out.println("测试完成");
    }
    
    /**
     * 模拟ClassLoader内存泄漏场景
     */
    private static void simulateClassLoaderLeak() throws Exception {
        System.out.println("开始模拟ClassLoader内存泄漏...");
        
        // 场景1：动态创建大量ClassLoader
        simulateDynamicClassLoading();
        
        // 场景2：反射和动态代理导致的类爆炸
        simulateReflectionClassExplosion();
        
        // 场景3：模拟Web应用类加载器泄漏
        simulateWebAppClassLoaderLeak();
    }
    
    /**
     * 场景1：动态创建大量ClassLoader
     * 模拟Web应用热部署或插件系统
     */
    private static void simulateDynamicClassLoading() throws Exception {
        System.out.println("场景1: 动态创建ClassLoader...");
        
        for (int i = 0; i < 20; i++) {
            // 创建自定义ClassLoader
            CustomClassLoader loader = new CustomClassLoader("AppLoader_" + i);
            LOADER_CACHE.add(loader); // 持有引用，阻止GC
            
            // 使用ClassLoader加载类
            for (int j = 0; j < 50; j++) {
                String className = "com.example.DynamicClass_" + i + "_" + j;
                
                try {
                    // 模拟加载自定义类
                    Class<?> clazz = loader.loadClass("java.lang.String"); // 简化处理
                    
                    // 缓存类引用 - 这是内存泄漏的关键
                    CLASS_CACHE.put(className, clazz);
                } catch (Exception e) {
                    // 忽略加载失败
                }
            }
            
            System.out.printf("创建ClassLoader %d, 缓存类数量: %d%n", 
                i + 1, CLASS_CACHE.size());
            
            Thread.sleep(200);
        }
    }
    
    /**
     * 场景2：反射和动态代理导致的类爆炸
     */
    private static void simulateReflectionClassExplosion() throws Exception {
        System.out.println("场景2: 反射和动态代理类爆炸...");
        
        for (int i = 0; i < 500; i++) {
            // 创建动态代理类 - 每次都会生成新的类
            TestInterface proxy = (TestInterface) Proxy.newProxyInstance(
                ClassLoaderLeakTest.class.getClassLoader(),
                new Class[]{TestInterface.class},
                new InvocationHandler() {
                    private final int id = i;
                    
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return "Dynamic_" + id;
                    }
                }
            );
            
            // 缓存代理对象 - 间接持有生成的代理类
            PROXY_CACHE.add(proxy);
            CLASS_CACHE.put("Proxy_" + i, proxy.getClass());
            
            if (i % 50 == 0) {
                System.out.printf("生成动态代理类: %d%n", i);
            }
            
            Thread.sleep(10);
        }
    }
    
    /**
     * 场景3：模拟Web应用类加载器泄漏
     */
    private static void simulateWebAppClassLoaderLeak() throws Exception {
        System.out.println("场景3: Web应用ClassLoader泄漏...");
        
        for (int i = 0; i < 10; i++) {
            // 模拟Web应用部署
            WebAppClassLoader webAppLoader = new WebAppClassLoader("WebApp_" + i);
            
            // 模拟加载Web应用类
            for (int j = 0; j < 100; j++) {
                String className = "com.webapp" + i + ".Class" + j;
                // 在真实场景中，这里会加载实际的Web应用类
                CLASS_CACHE.put(className, String.class); // 简化处理
            }
            
            // 关键问题：Web应用卸载时，ClassLoader没有被正确清理
            LOADER_CACHE.add(webAppLoader);
            
            System.out.printf("部署Web应用 %d%n", i + 1);
            Thread.sleep(500);
        }
    }
    
    /**
     * 自定义ClassLoader实现
     */
    private static class CustomClassLoader extends ClassLoader {
        private final String name;
        private final Map<String, Class<?>> loadedClasses = new ConcurrentHashMap<>();
        
        public CustomClassLoader(String name) {
            super(ClassLoaderLeakTest.class.getClassLoader());
            this.name = name;
        }
        
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // 检查是否已经加载
            Class<?> clazz = loadedClasses.get(name);
            if (clazz != null) {
                return clazz;
            }
            
            // 委托给父类加载器
            clazz = super.loadClass(name, resolve);
            
            // 缓存已加载的类 - 这可能导致内存泄漏
            loadedClasses.put(name, clazz);
            
            return clazz;
        }
        
        @Override
        public String toString() {
            return "CustomClassLoader[" + name + "]";
        }
    }
    
    /**
     * Web应用ClassLoader实现
     */
    private static class WebAppClassLoader extends URLClassLoader {
        private final String webAppName;
        
        public WebAppClassLoader(String webAppName) {
            super(new URL[0], ClassLoaderLeakTest.class.getClassLoader());
            this.webAppName = webAppName;
        }
        
        @Override
        public String toString() {
            return "WebAppClassLoader[" + webAppName + "]";
        }
    }
    
    /**
     * 测试接口
     */
    private interface TestInterface {
        String getValue();
    }
    
    /**
     * ClassLoader监控线程
     */
    private static void startClassLoaderMonitor() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    // 获取类加载统计信息
                    ClassLoadingMXBean classLoadingMXBean = 
                        ManagementFactory.getClassLoadingMXBean();
                    
                    long loadedClassCount = classLoadingMXBean.getLoadedClassCount();
                    long totalLoadedClassCount = classLoadingMXBean.getTotalLoadedClassCount();
                    long unloadedClassCount = classLoadingMXBean.getUnloadedClassCount();
                    
                    // 获取内存使用情况
                    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
                    MemoryUsage metaspaceUsage = memoryMXBean.getNonHeapMemoryUsage();
                    
                    System.out.printf("[ClassLoader监控] 当前类: %d, 总加载: %d, 已卸载: %d, " +
                        "Metaspace: %dMB/%dMB, 缓存ClassLoader: %d, 缓存Class: %d, 代理对象: %d%n",
                        loadedClassCount, totalLoadedClassCount, unloadedClassCount,
                        metaspaceUsage.getUsed() / 1024 / 1024,
                        metaspaceUsage.getMax() / 1024 / 1024,
                        LOADER_CACHE.size(), CLASS_CACHE.size(), PROXY_CACHE.size());
                    
                    // 检查是否接近Metaspace限制
                    double usage = (double) metaspaceUsage.getUsed() / metaspaceUsage.getMax();
                    if (usage > 0.8) {
                        System.out.println("⚠️  Metaspace使用率超过80%，可能出现OOM!");
                    }
                    
                    Thread.sleep(3000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }
    
    /**
     * 尝试清理资源
     */
    private static void attemptCleanup() {
        System.out.println("尝试清理ClassLoader缓存...");
        
        int originalClassCacheSize = CLASS_CACHE.size();
        int originalLoaderCacheSize = LOADER_CACHE.size();
        int originalProxyCacheSize = PROXY_CACHE.size();
        
        // 清理缓存
        CLASS_CACHE.clear();
        LOADER_CACHE.clear();
        PROXY_CACHE.clear();
        
        System.out.printf("清理前 - 类缓存: %d, ClassLoader缓存: %d, 代理缓存: %d%n",
            originalClassCacheSize, originalLoaderCacheSize, originalProxyCacheSize);
        
        // 强制GC
        System.gc();
        System.runFinalization();
        System.gc(); // 再次GC确保清理
        
        System.out.println("清理完成，等待10秒观察类卸载效果...");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 检查清理效果
        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        long unloadedAfterCleanup = classLoadingMXBean.getUnloadedClassCount();
        System.out.printf("清理后卸载的类数量: %d%n", unloadedAfterCleanup);
    }
}