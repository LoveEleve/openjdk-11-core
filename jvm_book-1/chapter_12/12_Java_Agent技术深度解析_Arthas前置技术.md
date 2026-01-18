# 第12章：Java Agent技术深度解析 - Arthas前置核心技术

## 📋 **章节概述**

Java Agent技术是Arthas等JVM诊断工具的核心基础技术。本章将从源码级别深度解析Java Agent的工作原理、实现机制和实战应用，为学习Arthas源码打下坚实的技术基础。

**学习目标**:
- 🎯 深度理解Java Agent的工作原理和生命周期
- 🎯 掌握Instrumentation API的核心功能和高级用法
- 🎯 理解ClassFileTransformer的实现机制
- 🎯 具备开发企业级Java Agent的能力
- 🎯 为深度学习Arthas源码做好技术准备

---

## 🏗️ **Java Agent技术架构深度分析**

### **1.1 Java Agent概述**

Java Agent是一种特殊的Java程序，它可以在JVM启动时或运行时动态加载，用于监控、分析和修改Java应用程序的行为。

#### **核心特性**:
- **字节码修改**: 在类加载时修改字节码
- **运行时监控**: 监控方法调用、内存使用等
- **动态加载**: 支持运行时动态注入
- **透明性**: 对目标应用程序透明

#### **应用场景**:
- **性能监控**: APM工具(如Arthas、SkyWalking)
- **代码覆盖率**: JaCoCo等工具
- **安全检测**: 运行时安全扫描
- **调试诊断**: 动态调试和问题诊断

### **1.2 Java Agent工作原理**

```
JVM启动流程中的Agent加载时机:

JVM启动 → 解析命令行参数 → 加载Agent → 初始化JVM → 加载主类 → 执行main方法
           ↓
    -javaagent:agent.jar=options
           ↓
    调用Agent的premain方法
           ↓
    注册ClassFileTransformer
           ↓
    后续类加载时触发字节码转换
```

#### **Agent加载方式**:

1. **启动时加载(premain)**:
```bash
java -javaagent:myagent.jar=option1,option2 MyApp
```

2. **运行时加载(agentmain)**:
```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("myagent.jar", "options");
```

---

## 🔧 **Instrumentation API深度解析**

### **2.1 Instrumentation接口核心功能**

```java
public interface Instrumentation {
    // 添加类文件转换器
    void addTransformer(ClassFileTransformer transformer);
    void addTransformer(ClassFileTransformer transformer, boolean canRetransform);
    
    // 移除类文件转换器
    boolean removeTransformer(ClassFileTransformer transformer);
    
    // 类重定义和重转换
    void redefineClasses(ClassDefinition... definitions) 
        throws ClassNotFoundException, UnmodifiableClassException;
    void retransformClasses(Class<?>... classes) 
        throws UnmodifiableClassException;
    
    // 获取已加载的类
    Class[] getAllLoadedClasses();
    Class[] getInitiatedClasses(ClassLoader loader);
    
    // 对象大小计算
    long getObjectSize(Object objectToSize);
    
    // 检查能力
    boolean isRedefineClassesSupported();
    boolean isRetransformClassesSupported();
    boolean isNativeMethodPrefixSupported();
}
```

### **2.2 ClassFileTransformer接口详解**

```java
public interface ClassFileTransformer {
    /**
     * 类文件转换方法
     * @param loader 类加载器
     * @param className 类名(内部格式，如com/example/MyClass)
     * @param classBeingRedefined 被重定义的类(重定义时非null)
     * @param protectionDomain 保护域
     * @param classfileBuffer 原始字节码
     * @return 转换后的字节码，null表示不转换
     */
    byte[] transform(ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer)
        throws IllegalClassFormatException;
}
```

#### **转换器实现要点**:

1. **性能考虑**: 转换器会被频繁调用，需要高效实现
2. **异常处理**: 转换失败不应影响类加载
3. **线程安全**: 转换器可能被多线程并发调用
4. **内存管理**: 避免内存泄漏和过度内存使用

---

## 💻 **Java Agent实战开发**

### **3.1 基础Agent开发**

#### **Agent入口类实现**:

```java
package com.example.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent入口类
 * 必须包含premain或agentmain方法
 */
public class BasicAgent {
    
    private static Instrumentation instrumentation;
    
    /**
     * JVM启动时调用的方法
     * @param agentArgs Agent参数
     * @param inst Instrumentation实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("BasicAgent premain called with args: " + agentArgs);
        instrumentation = inst;
        
        // 添加类文件转换器
        inst.addTransformer(new BasicClassTransformer(), true);
        
        System.out.println("BasicAgent initialized successfully");
    }
    
    /**
     * 运行时动态加载时调用的方法
     * @param agentArgs Agent参数
     * @param inst Instrumentation实例
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("BasicAgent agentmain called with args: " + agentArgs);
        instrumentation = inst;
        
        // 添加类文件转换器，支持重转换
        inst.addTransformer(new BasicClassTransformer(), true);
        
        // 重转换已加载的类
        try {
            Class<?>[] loadedClasses = inst.getAllLoadedClasses();
            for (Class<?> clazz : loadedClasses) {
                if (shouldTransform(clazz.getName())) {
                    inst.retransformClasses(clazz);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        System.out.println("BasicAgent attached successfully");
    }
    
    /**
     * 判断是否需要转换指定类
     */
    private static boolean shouldTransform(String className) {
        // 过滤系统类和Agent自身的类
        return !className.startsWith("java.") && 
               !className.startsWith("javax.") &&
               !className.startsWith("sun.") &&
               !className.startsWith("com.sun.") &&
               !className.startsWith("com.example.agent.");
    }
    
    /**
     * 获取Instrumentation实例
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
```

#### **MANIFEST.MF配置**:

```
Manifest-Version: 1.0
Premain-Class: com.example.agent.BasicAgent
Agent-Class: com.example.agent.BasicAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true
```

### **3.2 高级ClassFileTransformer实现**

```java
package com.example.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高级类文件转换器
 * 实现方法执行时间监控
 */
public class AdvancedClassTransformer implements ClassFileTransformer {
    
    // 转换统计
    private final AtomicLong transformCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> transformTimes = new ConcurrentHashMap<>();
    
    // 配置参数
    private final boolean enableTiming;
    private final String[] includePackages;
    private final String[] excludePackages;
    
    public AdvancedClassTransformer(boolean enableTiming, 
                                  String[] includePackages, 
                                  String[] excludePackages) {
        this.enableTiming = enableTiming;
        this.includePackages = includePackages;
        this.excludePackages = excludePackages;
    }
    
    @Override
    public byte[] transform(ClassLoader loader,
                           String className,
                           Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) throws IllegalClassFormatException {
        
        long startTime = System.nanoTime();
        
        try {
            // 快速过滤不需要转换的类
            if (!shouldTransform(className)) {
                return null;
            }
            
            // 记录转换次数
            transformCount.incrementAndGet();
            
            // 执行字节码转换
            byte[] transformedBytes = doTransform(className, classfileBuffer);
            
            // 记录转换时间
            if (enableTiming) {
                long duration = System.nanoTime() - startTime;
                transformTimes.put(className, duration);
                
                if (duration > 10_000_000) { // 超过10ms记录警告
                    System.out.println("Warning: Transform " + className + 
                                     " took " + (duration / 1_000_000) + "ms");
                }
            }
            
            return transformedBytes;
            
        } catch (Exception e) {
            System.err.println("Error transforming class: " + className);
            e.printStackTrace();
            return null; // 返回null表示不转换
        }
    }
    
    /**
     * 判断是否需要转换
     */
    private boolean shouldTransform(String className) {
        if (className == null) {
            return false;
        }
        
        // 转换为点分隔格式
        String dotClassName = className.replace('/', '.');
        
        // 检查排除包
        if (excludePackages != null) {
            for (String excludePackage : excludePackages) {
                if (dotClassName.startsWith(excludePackage)) {
                    return false;
                }
            }
        }
        
        // 检查包含包
        if (includePackages != null && includePackages.length > 0) {
            for (String includePackage : includePackages) {
                if (dotClassName.startsWith(includePackage)) {
                    return true;
                }
            }
            return false;
        }
        
        // 默认过滤系统类
        return !dotClassName.startsWith("java.") &&
               !dotClassName.startsWith("javax.") &&
               !dotClassName.startsWith("sun.") &&
               !dotClassName.startsWith("com.sun.");
    }
    
    /**
     * 执行实际的字节码转换
     */
    private byte[] doTransform(String className, byte[] classfileBuffer) {
        try {
            // 这里可以使用ASM等字节码操作框架
            // 暂时返回原始字节码，在后续章节中实现具体转换逻辑
            System.out.println("Transforming class: " + className);
            return null; // 暂不修改
        } catch (Exception e) {
            throw new RuntimeException("Transform failed for " + className, e);
        }
    }
    
    /**
     * 获取转换统计信息
     */
    public TransformStats getStats() {
        return new TransformStats(
            transformCount.get(),
            transformTimes.size(),
            transformTimes.values().stream().mapToLong(Long::longValue).average().orElse(0.0)
        );
    }
    
    /**
     * 转换统计信息
     */
    public static class TransformStats {
        private final long totalTransforms;
        private final int uniqueClasses;
        private final double averageTime;
        
        public TransformStats(long totalTransforms, int uniqueClasses, double averageTime) {
            this.totalTransforms = totalTransforms;
            this.uniqueClasses = uniqueClasses;
            this.averageTime = averageTime;
        }
        
        @Override
        public String toString() {
            return String.format("TransformStats{totalTransforms=%d, uniqueClasses=%d, averageTime=%.2fms}",
                               totalTransforms, uniqueClasses, averageTime / 1_000_000);
        }
    }
}
```

### **3.3 Agent配置管理**

```java
package com.example.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Agent配置管理器
 */
public class AgentConfig {
    
    private static final String DEFAULT_CONFIG_FILE = "agent.properties";
    
    private final Properties properties;
    private final String agentArgs;
    
    public AgentConfig(String agentArgs) {
        this.agentArgs = agentArgs;
        this.properties = new Properties();
        loadConfig();
        parseAgentArgs();
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_FILE)) {
            if (is != null) {
                properties.load(is);
                System.out.println("Loaded agent config from " + DEFAULT_CONFIG_FILE);
            }
        } catch (IOException e) {
            System.err.println("Failed to load agent config: " + e.getMessage());
        }
    }
    
    /**
     * 解析Agent参数
     */
    private void parseAgentArgs() {
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                String[] kv = arg.split("=", 2);
                if (kv.length == 2) {
                    properties.setProperty(kv[0].trim(), kv[1].trim());
                } else {
                    properties.setProperty(kv[0].trim(), "true");
                }
            }
        }
    }
    
    /**
     * 获取配置值
     */
    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public String[] getStringArray(String key, String[] defaultValue) {
        String value = properties.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.split("[,;]");
        }
        return defaultValue;
    }
    
    /**
     * 打印所有配置
     */
    public void printConfig() {
        System.out.println("Agent Configuration:");
        properties.forEach((key, value) -> 
            System.out.println("  " + key + " = " + value));
    }
}
```

---

## 🔍 **Agent生命周期管理**

### **4.1 Agent生命周期阶段**

```java
package com.example.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent生命周期管理器
 */
public class AgentLifecycleManager {
    
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean shutdown = new AtomicBoolean(false);
    
    private static AgentConfig config;
    private static AdvancedClassTransformer transformer;
    private static Instrumentation instrumentation;
    
    /**
     * 初始化Agent
     */
    public static synchronized void initialize(String agentArgs, Instrumentation inst) {
        if (initialized.compareAndSet(false, true)) {
            try {
                System.out.println("Initializing Agent...");
                
                // 保存Instrumentation实例
                instrumentation = inst;
                
                // 加载配置
                config = new AgentConfig(agentArgs);
                config.printConfig();
                
                // 创建类转换器
                transformer = new AdvancedClassTransformer(
                    config.getBoolean("enable.timing", true),
                    config.getStringArray("include.packages", new String[0]),
                    config.getStringArray("exclude.packages", new String[]{
                        "java.", "javax.", "sun.", "com.sun.", "com.example.agent."
                    })
                );
                
                // 注册转换器
                inst.addTransformer(transformer, true);
                
                // 注册关闭钩子
                Runtime.getRuntime().addShutdownHook(new Thread(AgentLifecycleManager::shutdown));
                
                System.out.println("Agent initialized successfully");
                
            } catch (Exception e) {
                System.err.println("Failed to initialize Agent: " + e.getMessage());
                e.printStackTrace();
                initialized.set(false);
            }
        }
    }
    
    /**
     * 关闭Agent
     */
    public static synchronized void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            try {
                System.out.println("Shutting down Agent...");
                
                // 移除转换器
                if (transformer != null && instrumentation != null) {
                    instrumentation.removeTransformer(transformer);
                }
                
                // 打印统计信息
                if (transformer != null) {
                    System.out.println("Transform statistics: " + transformer.getStats());
                }
                
                System.out.println("Agent shutdown completed");
                
            } catch (Exception e) {
                System.err.println("Error during Agent shutdown: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 检查Agent是否已初始化
     */
    public static boolean isInitialized() {
        return initialized.get() && !shutdown.get();
    }
    
    /**
     * 获取配置
     */
    public static AgentConfig getConfig() {
        return config;
    }
    
    /**
     * 获取转换器
     */
    public static AdvancedClassTransformer getTransformer() {
        return transformer;
    }
    
    /**
     * 获取Instrumentation实例
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
```

### **4.2 更新的Agent入口类**

```java
package com.example.agent;

import java.lang.instrument.Instrumentation;

/**
 * 改进的Agent入口类
 */
public class ImprovedAgent {
    
    /**
     * JVM启动时调用
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("ImprovedAgent premain called");
        AgentLifecycleManager.initialize(agentArgs, inst);
    }
    
    /**
     * 运行时动态加载时调用
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("ImprovedAgent agentmain called");
        AgentLifecycleManager.initialize(agentArgs, inst);
        
        // 对于动态加载，需要重转换已加载的类
        retransformLoadedClasses(inst);
    }
    
    /**
     * 重转换已加载的类
     */
    private static void retransformLoadedClasses(Instrumentation inst) {
        try {
            Class<?>[] loadedClasses = inst.getAllLoadedClasses();
            int retransformCount = 0;
            
            for (Class<?> clazz : loadedClasses) {
                if (shouldRetransform(clazz)) {
                    try {
                        inst.retransformClasses(clazz);
                        retransformCount++;
                    } catch (Exception e) {
                        System.err.println("Failed to retransform " + clazz.getName() + ": " + e.getMessage());
                    }
                }
            }
            
            System.out.println("Retransformed " + retransformCount + " classes");
            
        } catch (Exception e) {
            System.err.println("Error during class retransformation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 判断是否需要重转换
     */
    private static boolean shouldRetransform(Class<?> clazz) {
        if (clazz == null || clazz.isArray() || clazz.isPrimitive()) {
            return false;
        }
        
        String className = clazz.getName();
        
        // 过滤系统类和Agent自身的类
        return !className.startsWith("java.") &&
               !className.startsWith("javax.") &&
               !className.startsWith("sun.") &&
               !className.startsWith("com.sun.") &&
               !className.startsWith("com.example.agent.") &&
               !className.contains("$$Lambda$") && // 过滤Lambda类
               !className.contains("$Proxy"); // 过滤代理类
    }
}
```

---

## 🧪 **Agent测试和验证**

### **5.1 测试目标应用**

```java
package com.example.test;

/**
 * 用于测试Agent的目标应用
 */
public class TestApplication {
    
    public static void main(String[] args) {
        System.out.println("TestApplication started");
        
        TestApplication app = new TestApplication();
        
        // 测试方法调用
        for (int i = 0; i < 5; i++) {
            app.businessMethod("test-" + i);
            app.calculateSomething(i * 10);
            
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("TestApplication finished");
    }
    
    public String businessMethod(String input) {
        System.out.println("businessMethod called with: " + input);
        
        // 模拟一些业务逻辑
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return "processed-" + input;
    }
    
    public int calculateSomething(int value) {
        System.out.println("calculateSomething called with: " + value);
        
        // 模拟计算
        int result = 0;
        for (int i = 0; i < value; i++) {
            result += i * i;
        }
        
        return result;
    }
}
```

### **5.2 Agent配置文件**

```properties
# agent.properties
# Agent配置文件

# 是否启用执行时间统计
enable.timing=true

# 包含的包(空表示包含所有)
include.packages=com.example.test

# 排除的包
exclude.packages=java.,javax.,sun.,com.sun.,com.example.agent.

# 调试模式
debug.mode=true

# 最大转换时间(毫秒)
max.transform.time=50
```

### **5.3 构建脚本**

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.example</groupId>
    <artifactId>java-agent-demo</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                </configuration>
            </plugin>
            
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.2.0</version>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Premain-Class>com.example.agent.ImprovedAgent</Premain-Class>
                            <Agent-Class>com.example.agent.ImprovedAgent</Agent-Class>
                            <Can-Redefine-Classes>true</Can-Redefine-Classes>
                            <Can-Retransform-Classes>true</Can-Retransform-Classes>
                            <Can-Set-Native-Method-Prefix>true</Can-Set-Native-Method-Prefix>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### **5.4 测试脚本**

```bash
#!/bin/bash
# test-agent.sh

# 编译Agent
echo "Building agent..."
mvn clean package

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

AGENT_JAR="target/java-agent-demo-1.0.0.jar"

# 测试1: 启动时加载Agent
echo "Test 1: Loading agent at startup..."
java -javaagent:${AGENT_JAR}=enable.timing=true,debug.mode=true \
     -cp ${AGENT_JAR} \
     com.example.test.TestApplication

echo "----------------------------------------"

# 测试2: 运行时动态加载Agent (需要先启动目标应用)
echo "Test 2: Dynamic agent loading..."
echo "Starting target application in background..."

# 启动目标应用(循环运行)
java -cp ${AGENT_JAR} com.example.test.LongRunningApp &
TARGET_PID=$!

sleep 2

# 动态加载Agent
java -cp ${AGENT_JAR} com.example.agent.AgentAttacher ${TARGET_PID} ${AGENT_JAR}

# 等待一段时间观察效果
sleep 10

# 停止目标应用
kill ${TARGET_PID}

echo "Test completed!"
```

---

## 🔧 **Agent动态加载工具**

### **6.1 JVM Attach工具实现**

```java
package com.example.agent;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.util.List;

/**
 * Agent动态加载工具
 */
public class AgentAttacher {
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java AgentAttacher <pid> <agent-jar> [agent-options]");
            System.out.println("   or: java AgentAttacher list");
            return;
        }
        
        if ("list".equals(args[0])) {
            listJavaProcesses();
            return;
        }
        
        String pid = args[0];
        String agentJar = args[1];
        String agentOptions = args.length > 2 ? args[2] : "";
        
        attachAgent(pid, agentJar, agentOptions);
    }
    
    /**
     * 列出所有Java进程
     */
    private static void listJavaProcesses() {
        System.out.println("Available Java processes:");
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        
        if (vms.isEmpty()) {
            System.out.println("No Java processes found.");
            return;
        }
        
        for (VirtualMachineDescriptor vmd : vms) {
            System.out.printf("PID: %s, Display Name: %s%n", 
                            vmd.id(), vmd.displayName());
        }
    }
    
    /**
     * 动态加载Agent到指定进程
     */
    private static void attachAgent(String pid, String agentJar, String agentOptions) {
        try {
            System.out.println("Attaching agent to process " + pid + "...");
            
            // 附加到目标JVM
            VirtualMachine vm = VirtualMachine.attach(pid);
            
            try {
                // 加载Agent
                vm.loadAgent(agentJar, agentOptions);
                System.out.println("Agent loaded successfully!");
                
                // 获取系统属性验证连接
                String javaVersion = vm.getSystemProperties().getProperty("java.version");
                System.out.println("Target JVM Java version: " + javaVersion);
                
            } finally {
                // 分离
                vm.detach();
                System.out.println("Detached from target JVM");
            }
            
        } catch (Exception e) {
            System.err.println("Failed to attach agent: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

### **6.2 长时间运行的测试应用**

```java
package com.example.test;

/**
 * 长时间运行的测试应用，用于测试动态Agent加载
 */
public class LongRunningApp {
    
    private volatile boolean running = true;
    
    public static void main(String[] args) {
        LongRunningApp app = new LongRunningApp();
        
        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook called");
            app.running = false;
        }));
        
        app.run();
    }
    
    public void run() {
        System.out.println("LongRunningApp started, PID: " + 
                          ProcessHandle.current().pid());
        
        int counter = 0;
        while (running) {
            try {
                // 执行一些业务逻辑
                doSomeWork(counter++);
                
                Thread.sleep(2000);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("LongRunningApp stopped");
    }
    
    private void doSomeWork(int counter) {
        System.out.println("Working... counter=" + counter);
        
        // 模拟一些计算
        calculatePrime(counter % 100 + 10);
        
        // 模拟一些字符串操作
        processString("data-" + counter);
    }
    
    private int calculatePrime(int n) {
        if (n < 2) return 2;
        
        for (int i = 2; i <= Math.sqrt(n); i++) {
            if (n % i == 0) {
                return calculatePrime(n + 1);
            }
        }
        return n;
    }
    
    private String processString(String input) {
        return input.toUpperCase().replace("-", "_") + "_PROCESSED";
    }
}
```

---

## 📊 **Agent性能监控和调优**

### **7.1 性能监控实现**

```java
package com.example.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agent性能监控器
 */
public class AgentPerformanceMonitor {
    
    private static final AgentPerformanceMonitor INSTANCE = new AgentPerformanceMonitor();
    
    // 性能计数器
    private final AtomicLong transformCount = new AtomicLong(0);
    private final LongAdder totalTransformTime = new LongAdder();
    private final AtomicLong maxTransformTime = new AtomicLong(0);
    private final ConcurrentHashMap<String, ClassTransformStats> classStats = new ConcurrentHashMap<>();
    
    // 监控开关
    private volatile boolean monitoringEnabled = true;
    
    private AgentPerformanceMonitor() {}
    
    public static AgentPerformanceMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * 记录类转换性能
     */
    public void recordTransform(String className, long duration) {
        if (!monitoringEnabled) {
            return;
        }
        
        transformCount.incrementAndGet();
        totalTransformTime.add(duration);
        
        // 更新最大转换时间
        long currentMax = maxTransformTime.get();
        while (duration > currentMax && !maxTransformTime.compareAndSet(currentMax, duration)) {
            currentMax = maxTransformTime.get();
        }
        
        // 更新类级别统计
        classStats.computeIfAbsent(className, k -> new ClassTransformStats())
                  .addTransform(duration);
    }
    
    /**
     * 获取性能统计报告
     */
    public PerformanceReport getReport() {
        long count = transformCount.get();
        long totalTime = totalTransformTime.sum();
        long maxTime = maxTransformTime.get();
        double avgTime = count > 0 ? (double) totalTime / count : 0.0;
        
        return new PerformanceReport(count, totalTime, maxTime, avgTime, classStats.size());
    }
    
    /**
     * 获取最慢的类转换统计
     */
    public void printTopSlowClasses(int topN) {
        System.out.println("Top " + topN + " slowest class transformations:");
        
        classStats.entrySet().stream()
                  .sorted((e1, e2) -> Long.compare(e2.getValue().getMaxTime(), e1.getValue().getMaxTime()))
                  .limit(topN)
                  .forEach(entry -> {
                      String className = entry.getKey();
                      ClassTransformStats stats = entry.getValue();
                      System.out.printf("  %s: max=%.2fms, avg=%.2fms, count=%d%n",
                                      className,
                                      stats.getMaxTime() / 1_000_000.0,
                                      stats.getAverageTime() / 1_000_000.0,
                                      stats.getCount());
                  });
    }
    
    /**
     * 重置统计数据
     */
    public void reset() {
        transformCount.set(0);
        totalTransformTime.reset();
        maxTransformTime.set(0);
        classStats.clear();
    }
    
    /**
     * 启用/禁用监控
     */
    public void setMonitoringEnabled(boolean enabled) {
        this.monitoringEnabled = enabled;
    }
    
    /**
     * 类转换统计
     */
    private static class ClassTransformStats {
        private final AtomicLong count = new AtomicLong(0);
        private final LongAdder totalTime = new LongAdder();
        private final AtomicLong maxTime = new AtomicLong(0);
        
        void addTransform(long duration) {
            count.incrementAndGet();
            totalTime.add(duration);
            
            long currentMax = maxTime.get();
            while (duration > currentMax && !maxTime.compareAndSet(currentMax, duration)) {
                currentMax = maxTime.get();
            }
        }
        
        long getCount() { return count.get(); }
        long getTotalTime() { return totalTime.sum(); }
        long getMaxTime() { return maxTime.get(); }
        double getAverageTime() { 
            long c = count.get();
            return c > 0 ? (double) totalTime.sum() / c : 0.0;
        }
    }
    
    /**
     * 性能报告
     */
    public static class PerformanceReport {
        private final long totalTransforms;
        private final long totalTime;
        private final long maxTime;
        private final double averageTime;
        private final int uniqueClasses;
        
        public PerformanceReport(long totalTransforms, long totalTime, long maxTime, 
                               double averageTime, int uniqueClasses) {
            this.totalTransforms = totalTransforms;
            this.totalTime = totalTime;
            this.maxTime = maxTime;
            this.averageTime = averageTime;
            this.uniqueClasses = uniqueClasses;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Performance Report:\n" +
                "  Total Transforms: %d\n" +
                "  Unique Classes: %d\n" +
                "  Total Time: %.2fms\n" +
                "  Average Time: %.2fms\n" +
                "  Max Time: %.2fms",
                totalTransforms, uniqueClasses,
                totalTime / 1_000_000.0,
                averageTime / 1_000_000.0,
                maxTime / 1_000_000.0
            );
        }
    }
}
```

### **7.2 集成性能监控的转换器**

```java
package com.example.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * 集成性能监控的类文件转换器
 */
public class MonitoredClassTransformer implements ClassFileTransformer {
    
    private final AdvancedClassTransformer delegate;
    private final AgentPerformanceMonitor monitor;
    
    public MonitoredClassTransformer(AdvancedClassTransformer delegate) {
        this.delegate = delegate;
        this.monitor = AgentPerformanceMonitor.getInstance();
    }
    
    @Override
    public byte[] transform(ClassLoader loader,
                           String className,
                           Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) throws IllegalClassFormatException {
        
        long startTime = System.nanoTime();
        
        try {
            byte[] result = delegate.transform(loader, className, classBeingRedefined, 
                                             protectionDomain, classfileBuffer);
            
            // 只有实际进行了转换才记录性能
            if (result != null) {
                long duration = System.nanoTime() - startTime;
                monitor.recordTransform(className, duration);
                
                // 如果转换时间过长，记录警告
                if (duration > 50_000_000) { // 50ms
                    System.err.printf("Warning: Transform %s took %.2fms%n", 
                                    className, duration / 1_000_000.0);
                }
            }
            
            return result;
            
        } catch (Exception e) {
            long duration = System.nanoTime() - startTime;
            monitor.recordTransform(className + " (FAILED)", duration);
            throw e;
        }
    }
}
```

---

## 📋 **本章总结**

### **🎯 核心知识点回顾**

1. **Java Agent基础**:
   - ✅ Agent的工作原理和加载机制
   - ✅ premain和agentmain方法的区别
   - ✅ MANIFEST.MF配置要点

2. **Instrumentation API**:
   - ✅ ClassFileTransformer接口实现
   - ✅ 类重定义和重转换机制
   - ✅ Agent能力检查和限制

3. **高级特性**:
   - ✅ Agent生命周期管理
   - ✅ 配置管理和参数解析
   - ✅ 性能监控和调优

4. **实战技能**:
   - ✅ Agent开发和测试
   - ✅ 动态加载工具开发
   - ✅ 性能问题诊断和优化

### **🚀 为Arthas学习做好的准备**

通过本章的深度学习，您已经具备了：

1. **Agent技术基础**: 完全理解Arthas的Agent加载机制
2. **字节码转换能力**: 为学习Arthas的字节码增强做好准备
3. **动态加载技术**: 理解Arthas的运行时注入原理
4. **性能优化思维**: 具备分析和优化Agent性能的能力

### **🎯 下一步学习建议**

1. **实践练习**: 完成本章的所有实战项目
2. **深入ASM**: 学习字节码操作框架(下一章内容)
3. **网络编程**: 掌握Netty框架使用
4. **Arthas源码**: 开始分析Arthas的Agent实现

---

**🎉 恭喜！您已经掌握了Java Agent技术的核心要点，为深度学习Arthas源码打下了坚实的基础！** 🎉