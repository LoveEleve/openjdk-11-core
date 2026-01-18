# 第11章：JVM安全机制与沙箱模型 - 深度增强版

## 📋 章节概述

本章深度分析JVM安全机制与沙箱模型的完整实现，包括安全管理器、访问控制、代码签名、类加载安全等核心安全技术。通过源码级分析和实际验证，帮助读者掌握JVM安全防护的专业技能。

### 🎯 学习目标
- 掌握JVM安全机制的完整架构和实现原理
- 理解安全管理器和访问控制的工作机制
- 学会配置和使用JVM安全策略
- 建立完整的JVM安全防护体系

### 📊 技术覆盖范围
- **安全管理器机制**: SecurityManager、AccessController完整实现
- **访问控制模型**: Permission、Policy、ProtectionDomain深度分析
- **代码签名验证**: 数字签名、证书链验证、完整性检查
- **类加载安全**: 安全类加载器、字节码验证、运行时检查
- **沙箱模型**: Applet沙箱、WebStart安全、自定义沙箱实现
- **安全策略配置**: 策略文件、权限管理、动态权限控制

---

## 🔒 11.1 JVM安全架构深度分析

### 11.1.1 安全管理器核心实现

JVM的安全管理器是整个安全体系的核心，负责执行安全策略和访问控制。

#### SecurityManager核心源码分析

```cpp
// hotspot/src/share/classfile/systemDictionary.hpp
class SystemDictionary : AllStatic {
private:
  // 安全相关的系统类
  static Klass* _security_manager_klass;
  static Klass* _access_controller_klass;
  static Klass* _permission_klass;
  static Klass* _protection_domain_klass;
  
public:
  // 安全管理器相关方法
  static Klass* SecurityManager_klass() { return _security_manager_klass; }
  static Klass* AccessController_klass() { return _access_controller_klass; }
  static Klass* Permission_klass() { return _permission_klass; }
  static Klass* ProtectionDomain_klass() { return _protection_domain_klass; }
  
  // 安全检查方法
  static void check_security_access(Klass* klass, TRAPS);
  static bool is_security_manager_installed();
};

// Java层SecurityManager实现分析
// java.lang.SecurityManager
public class SecurityManager {
    private static ThreadLocal<Object> gate = new ThreadLocal<>();
    
    // 核心安全检查方法
    public void checkPermission(Permission perm) {
        AccessController.checkPermission(perm);
    }
    
    // 文件访问检查
    public void checkRead(String file) {
        checkPermission(new FilePermission(file, SecurityConstants.FILE_READ_ACTION));
    }
    
    public void checkWrite(String file) {
        checkPermission(new FilePermission(file, SecurityConstants.FILE_WRITE_ACTION));
    }
    
    // 网络访问检查
    public void checkConnect(String host, int port) {
        if (port == -1) {
            checkPermission(new SocketPermission(host, SecurityConstants.SOCKET_RESOLVE_ACTION));
        } else {
            checkPermission(new SocketPermission(host + ":" + port, SecurityConstants.SOCKET_CONNECT_ACTION));
        }
    }
    
    // 系统属性访问检查
    public void checkPropertyAccess(String key) {
        checkPermission(new PropertyPermission(key, SecurityConstants.PROPERTY_READ_ACTION));
    }
    
    // 线程操作检查
    public void checkAccess(Thread t) {
        if (t == null) {
            throw new NullPointerException("thread can't be null");
        }
        if (t.getThreadGroup() == Thread.currentThread().getThreadGroup()) {
            return;
        }
        checkPermission(SecurityConstants.MODIFY_THREAD_PERMISSION);
    }
    
    // 类加载检查
    public void checkCreateClassLoader() {
        checkPermission(SecurityConstants.CREATE_CLASSLOADER_PERMISSION);
    }
    
    // 反射访问检查
    public void checkMemberAccess(Class<?> clazz, int which) {
        if (clazz == null) {
            throw new NullPointerException("class can't be null");
        }
        if (which != Member.PUBLIC) {
            Class<?> stack[] = getClassContext();
            if ((stack.length < 4) || (stack[3].getClassLoader() != clazz.getClassLoader())) {
                checkPermission(SecurityConstants.CHECK_MEMBER_ACCESS_PERMISSION);
            }
        }
    }
}
```

#### AccessController访问控制实现

```cpp
// hotspot/src/share/prims/jvm.cpp
// JVM_DoPrivileged实现
JVM_ENTRY(jobject, JVM_DoPrivileged(JNIEnv *env, jclass cls, jobject action, jobject context, jboolean wrapException))
  JVMWrapper("JVM_DoPrivileged");
  
  // 获取当前访问控制上下文
  Handle current_context = AccessController::get_current_context(CHECK_NULL);
  
  // 创建特权上下文
  Handle privileged_context;
  if (context != NULL) {
    privileged_context = Handle(THREAD, JNIHandles::resolve(context));
  } else {
    privileged_context = AccessController::create_privileged_context(CHECK_NULL);
  }
  
  // 执行特权操作
  JavaValue result(T_OBJECT);
  JavaCalls::call_virtual(&result,
                         Handle(THREAD, JNIHandles::resolve(action)),
                         KlassHandle(THREAD, SystemDictionary::PrivilegedAction_klass()),
                         vmSymbols::run_method_name(),
                         vmSymbols::void_object_signature(),
                         CHECK_NULL);
  
  return JNIHandles::make_local(env, (oop) result.get_jobject());
JVM_END

// AccessController核心实现
class AccessController : AllStatic {
private:
  static oop _privileged_context;
  static GrowableArray<ProtectionDomain*>* _protection_domains;
  
public:
  // 检查权限
  static void check_permission(Permission* perm, TRAPS);
  
  // 获取当前访问控制上下文
  static Handle get_current_context(TRAPS);
  
  // 创建特权上下文
  static Handle create_privileged_context(TRAPS);
  
  // 执行特权操作
  static oop do_privileged(oop action, oop context, TRAPS);
  
private:
  // 收集保护域
  static void collect_protection_domains(GrowableArray<ProtectionDomain*>* domains, TRAPS);
  
  // 检查保护域权限
  static bool check_domain_permission(ProtectionDomain* domain, Permission* perm);
};

void AccessController::check_permission(Permission* perm, TRAPS) {
  // 如果没有安全管理器，直接返回
  if (!SystemDictionary::is_security_manager_installed()) {
    return;
  }
  
  // 收集当前调用栈的保护域
  GrowableArray<ProtectionDomain*>* domains = new GrowableArray<ProtectionDomain*>();
  collect_protection_domains(domains, CHECK);
  
  // 检查每个保护域是否有所需权限
  for (int i = 0; i < domains->length(); i++) {
    ProtectionDomain* domain = domains->at(i);
    if (!check_domain_permission(domain, perm)) {
      // 权限检查失败，抛出AccessControlException
      THROW_MSG(vmSymbols::java_security_AccessControlException(),
                "access denied");
    }
  }
}

void AccessController::collect_protection_domains(GrowableArray<ProtectionDomain*>* domains, TRAPS) {
  // 遍历当前线程的调用栈
  JavaThread* thread = (JavaThread*)THREAD;
  
  for (vframe* vf = thread->last_java_vframe(); vf != NULL; vf = vf->sender()) {
    if (vf->is_java_frame()) {
      javaVFrame* jvf = javaVFrame::cast(vf);
      Method* method = jvf->method();
      InstanceKlass* klass = method->method_holder();
      
      // 获取类的保护域
      oop protection_domain = klass->protection_domain();
      if (protection_domain != NULL) {
        ProtectionDomain* pd = ProtectionDomain::cast(protection_domain);
        if (!domains->contains(pd)) {
          domains->append(pd);
        }
      }
      
      // 检查是否遇到特权标记
      if (method->is_privileged()) {
        break; // 停止收集，使用特权上下文
      }
    }
  }
}
```

### 11.1.2 权限模型实现

```cpp
// Permission权限基类实现
public abstract class Permission implements Guard, Serializable {
    private String name;
    
    public Permission(String name) {
        this.name = name;
    }
    
    // 核心权限检查方法
    public abstract boolean implies(Permission permission);
    
    // 权限相等性检查
    public abstract boolean equals(Object obj);
    
    // 权限哈希码
    public abstract int hashCode();
    
    // 权限字符串表示
    public abstract String getActions();
    
    // Guard接口实现
    public void checkGuard(Object object) throws SecurityException {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(this);
        }
    }
}

// FilePermission文件权限实现
public final class FilePermission extends Permission implements Serializable {
    private String actions;
    private transient int mask;
    
    // 权限掩码常量
    private static final int READ    = 0x1;
    private static final int WRITE   = 0x2;
    private static final int EXECUTE = 0x4;
    private static final int DELETE  = 0x8;
    
    public FilePermission(String path, String actions) {
        super(path);
        init(getMask(actions));
    }
    
    private void init(int mask) {
        if ((mask & ALL) != mask) {
            throw new IllegalArgumentException("invalid actions mask");
        }
        
        if (mask == NONE) {
            throw new IllegalArgumentException("invalid actions mask");
        }
        
        this.mask = mask;
        this.actions = getActions(mask);
    }
    
    // 权限包含检查
    public boolean implies(Permission p) {
        if (!(p instanceof FilePermission)) {
            return false;
        }
        
        FilePermission that = (FilePermission) p;
        
        // 检查操作权限
        if ((this.mask & that.mask) != that.mask) {
            return false;
        }
        
        // 检查路径权限
        return impliesIgnoreMask(that);
    }
    
    private boolean impliesIgnoreMask(FilePermission that) {
        if (this.getName().equals("<<ALL FILES>>")) {
            return true;
        }
        
        String thisPath = this.getName();
        String thatPath = that.getName();
        
        // 处理通配符路径
        if (thisPath.endsWith("*")) {
            String thisPrefix = thisPath.substring(0, thisPath.length() - 1);
            return thatPath.startsWith(thisPrefix);
        }
        
        // 处理递归通配符
        if (thisPath.endsWith("-")) {
            String thisPrefix = thisPath.substring(0, thisPath.length() - 1);
            return thatPath.startsWith(thisPrefix);
        }
        
        // 精确匹配
        return thisPath.equals(thatPath);
    }
}

// SocketPermission网络权限实现
public final class SocketPermission extends Permission implements Serializable {
    private String hostname;
    private int[] portrange;
    private int mask;
    
    // 网络操作常量
    private static final int RESOLVE = 0x1;
    private static final int CONNECT = 0x2;
    private static final int LISTEN  = 0x4;
    private static final int ACCEPT  = 0x8;
    
    public SocketPermission(String host, String action) {
        super(getHost(host));
        init(host, getMask(action));
    }
    
    private void init(String host, int mask) {
        this.mask = mask;
        
        // 解析主机名和端口范围
        parseHost(host);
    }
    
    private void parseHost(String host) {
        if (host == null || host.length() == 0) {
            throw new IllegalArgumentException("invalid host");
        }
        
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex == -1) {
            this.hostname = host;
            this.portrange = new int[] {-1, -1}; // 所有端口
        } else {
            this.hostname = host.substring(0, colonIndex);
            String portStr = host.substring(colonIndex + 1);
            
            // 解析端口范围
            parsePortRange(portStr);
        }
    }
    
    public boolean implies(Permission p) {
        if (!(p instanceof SocketPermission)) {
            return false;
        }
        
        SocketPermission that = (SocketPermission) p;
        
        // 检查操作权限
        if ((this.mask & that.mask) != that.mask) {
            return false;
        }
        
        // 检查主机权限
        if (!impliesHost(that.hostname)) {
            return false;
        }
        
        // 检查端口权限
        return impliesPort(that.portrange);
    }
    
    private boolean impliesHost(String thatHost) {
        if (this.hostname.equals("*")) {
            return true; // 通配符匹配所有主机
        }
        
        if (this.hostname.startsWith("*.")) {
            String thisDomain = this.hostname.substring(2);
            return thatHost.endsWith(thisDomain);
        }
        
        return this.hostname.equals(thatHost);
    }
}
```

---

## 🛡️ 11.2 沙箱模型深度实现

### 11.2.1 Applet沙箱机制

```cpp
// hotspot/src/share/classfile/classLoader.hpp
class AppletClassLoader : public ClassLoader {
private:
  oop _code_source;
  oop _protection_domain;
  bool _is_trusted;
  
public:
  AppletClassLoader(oop code_source, oop protection_domain);
  
  // 重写类加载方法
  virtual Klass* load_class(Symbol* name, TRAPS);
  
  // 安全检查
  virtual void check_class_access(Klass* klass, TRAPS);
  
  // 资源访问检查
  virtual void check_resource_access(const char* name, TRAPS);
  
private:
  // 验证类的安全性
  bool verify_class_security(Klass* klass);
  
  // 检查代码来源
  bool verify_code_source(oop code_source);
};

Klass* AppletClassLoader::load_class(Symbol* name, TRAPS) {
  // 首先检查是否为系统类
  if (is_system_class(name)) {
    return SystemDictionary::resolve_or_null(name, Handle(), Handle(), CHECK_NULL);
  }
  
  // 检查类名是否被允许
  if (!is_class_allowed(name)) {
    THROW_MSG_NULL(vmSymbols::java_lang_SecurityException(),
                   "class access denied");
  }
  
  // 加载类
  Klass* klass = ClassLoader::load_class(name, CHECK_NULL);
  
  if (klass != NULL) {
    // 验证类的安全性
    if (!verify_class_security(klass)) {
      THROW_MSG_NULL(vmSymbols::java_lang_SecurityException(),
                     "class security verification failed");
    }
    
    // 设置保护域
    InstanceKlass::cast(klass)->set_protection_domain(_protection_domain);
  }
  
  return klass;
}

bool AppletClassLoader::verify_class_security(Klass* klass) {
  // 检查类是否包含本地方法
  if (has_native_methods(klass)) {
    return false; // Applet不允许本地方法
  }
  
  // 检查类是否访问受限制的API
  if (accesses_restricted_api(klass)) {
    return false;
  }
  
  // 检查类的字节码完整性
  if (!verify_bytecode_integrity(klass)) {
    return false;
  }
  
  return true;
}

// 沙箱策略实现
class SandboxPolicy : public Policy {
private:
  GrowableArray<Permission*>* _allowed_permissions;
  GrowableArray<Permission*>* _denied_permissions;
  
public:
  SandboxPolicy();
  
  // 权限检查
  virtual bool implies(ProtectionDomain* domain, Permission* permission);
  
  // 添加允许的权限
  void add_allowed_permission(Permission* perm);
  
  // 添加拒绝的权限
  void add_denied_permission(Permission* perm);
  
private:
  // 检查权限是否在允许列表中
  bool is_permission_allowed(Permission* perm);
  
  // 检查权限是否在拒绝列表中
  bool is_permission_denied(Permission* perm);
};

bool SandboxPolicy::implies(ProtectionDomain* domain, Permission* permission) {
  // 首先检查拒绝列表
  if (is_permission_denied(permission)) {
    return false;
  }
  
  // 然后检查允许列表
  if (is_permission_allowed(permission)) {
    return true;
  }
  
  // 默认策略：基于保护域的代码来源
  CodeSource* code_source = domain->code_source();
  if (code_source != NULL) {
    return check_code_source_permission(code_source, permission);
  }
  
  return false; // 默认拒绝
}

// 默认沙箱权限配置
void SandboxPolicy::configure_default_permissions() {
  // 允许基本的系统属性读取
  add_allowed_permission(new PropertyPermission("java.version", "read"));
  add_allowed_permission(new PropertyPermission("java.vendor", "read"));
  add_allowed_permission(new PropertyPermission("java.class.version", "read"));
  
  // 允许基本的文件读取（仅限于Applet目录）
  add_allowed_permission(new FilePermission("${java.home}/-", "read"));
  
  // 拒绝网络访问（除非明确允许）
  add_denied_permission(new SocketPermission("*:*", "connect,resolve"));
  
  // 拒绝文件写入
  add_denied_permission(new FilePermission("<<ALL FILES>>", "write,delete"));
  
  // 拒绝系统属性修改
  add_denied_permission(new PropertyPermission("*", "write"));
  
  // 拒绝线程操作
  add_denied_permission(new RuntimePermission("modifyThread"));
  add_denied_permission(new RuntimePermission("modifyThreadGroup"));
}
```

### 11.2.2 自定义沙箱实现

```java
// 自定义沙箱管理器
public class CustomSandboxManager extends SecurityManager {
    private final Set<String> allowedPackages;
    private final Set<String> deniedPackages;
    private final Map<String, Permission> customPermissions;
    
    public CustomSandboxManager() {
        this.allowedPackages = new HashSet<>();
        this.deniedPackages = new HashSet<>();
        this.customPermissions = new HashMap<>();
        
        // 配置默认策略
        configureDefaultPolicy();
    }
    
    private void configureDefaultPolicy() {
        // 允许的包
        allowedPackages.add("java.lang");
        allowedPackages.add("java.util");
        allowedPackages.add("java.math");
        
        // 拒绝的包
        deniedPackages.add("java.io");
        deniedPackages.add("java.net");
        deniedPackages.add("java.nio");
        deniedPackages.add("sun.*");
        
        // 自定义权限
        customPermissions.put("file.read.temp", 
            new FilePermission(System.getProperty("java.io.tmpdir") + "/-", "read"));
        customPermissions.put("network.connect.localhost", 
            new SocketPermission("localhost:8080", "connect"));
    }
    
    @Override
    public void checkPermission(Permission perm) {
        // 检查自定义权限
        if (checkCustomPermission(perm)) {
            return;
        }
        
        // 检查包访问权限
        if (perm instanceof RuntimePermission) {
            RuntimePermission rp = (RuntimePermission) perm;
            if (rp.getName().startsWith("accessClassInPackage.")) {
                String packageName = rp.getName().substring("accessClassInPackage.".length());
                checkPackageAccess(packageName);
                return;
            }
        }
        
        // 默认权限检查
        super.checkPermission(perm);
    }
    
    private boolean checkCustomPermission(Permission perm) {
        for (Permission customPerm : customPermissions.values()) {
            if (customPerm.implies(perm)) {
                return true;
            }
        }
        return false;
    }
    
    private void checkPackageAccess(String packageName) {
        // 检查是否在允许列表中
        for (String allowed : allowedPackages) {
            if (packageName.startsWith(allowed)) {
                return; // 允许访问
            }
        }
        
        // 检查是否在拒绝列表中
        for (String denied : deniedPackages) {
            if (packageName.startsWith(denied)) {
                throw new SecurityException("Access to package " + packageName + " is denied");
            }
        }
        
        // 默认策略：拒绝未明确允许的包
        throw new SecurityException("Access to package " + packageName + " is not allowed");
    }
    
    @Override
    public void checkRead(String file) {
        // 只允许读取临时目录和用户目录
        String tempDir = System.getProperty("java.io.tmpdir");
        String userDir = System.getProperty("user.dir");
        
        if (file.startsWith(tempDir) || file.startsWith(userDir)) {
            return; // 允许读取
        }
        
        throw new SecurityException("File read access denied: " + file);
    }
    
    @Override
    public void checkWrite(String file) {
        // 只允许写入临时目录
        String tempDir = System.getProperty("java.io.tmpdir");
        
        if (file.startsWith(tempDir)) {
            return; // 允许写入
        }
        
        throw new SecurityException("File write access denied: " + file);
    }
    
    @Override
    public void checkConnect(String host, int port) {
        // 只允许连接到本地主机的特定端口
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            if (port == 8080 || port == 9090) {
                return; // 允许连接
            }
        }
        
        throw new SecurityException("Network connection denied: " + host + ":" + port);
    }
    
    @Override
    public void checkCreateClassLoader() {
        throw new SecurityException("Creating class loader is not allowed");
    }
    
    @Override
    public void checkAccess(Thread t) {
        // 只允许访问当前线程组的线程
        ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup targetGroup = t.getThreadGroup();
        
        if (currentGroup != targetGroup) {
            throw new SecurityException("Thread access denied");
        }
    }
}

// 沙箱执行环境
public class SandboxExecutor {
    private final CustomSandboxManager sandboxManager;
    private final ClassLoader sandboxClassLoader;
    
    public SandboxExecutor() {
        this.sandboxManager = new CustomSandboxManager();
        this.sandboxClassLoader = createSandboxClassLoader();
    }
    
    private ClassLoader createSandboxClassLoader() {
        return new URLClassLoader(new URL[0], null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // 检查类是否被允许加载
                checkClassAccess(name);
                
                // 加载类
                Class<?> clazz = super.loadClass(name, resolve);
                
                // 验证类的安全性
                verifyClassSecurity(clazz);
                
                return clazz;
            }
            
            private void checkClassAccess(String className) {
                // 检查包访问权限
                int lastDot = className.lastIndexOf('.');
                if (lastDot != -1) {
                    String packageName = className.substring(0, lastDot);
                    sandboxManager.checkPackageAccess(packageName);
                }
            }
            
            private void verifyClassSecurity(Class<?> clazz) {
                // 检查类是否包含本地方法
                Method[] methods = clazz.getDeclaredMethods();
                for (Method method : methods) {
                    if (Modifier.isNative(method.getModifiers())) {
                        throw new SecurityException("Native methods are not allowed: " + 
                                                  clazz.getName() + "." + method.getName());
                    }
                }
                
                // 检查类是否继承自受限制的类
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null && isRestrictedClass(superClass)) {
                    throw new SecurityException("Extending restricted class is not allowed: " + 
                                              superClass.getName());
                }
            }
            
            private boolean isRestrictedClass(Class<?> clazz) {
                String className = clazz.getName();
                return className.startsWith("java.lang.ClassLoader") ||
                       className.startsWith("java.security.") ||
                       className.startsWith("sun.");
            }
        };
    }
    
    public <T> T execute(Callable<T> task) throws Exception {
        SecurityManager originalSM = System.getSecurityManager();
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        
        try {
            // 设置沙箱环境
            System.setSecurityManager(sandboxManager);
            Thread.currentThread().setContextClassLoader(sandboxClassLoader);
            
            // 执行任务
            return task.call();
            
        } finally {
            // 恢复原始环境
            System.setSecurityManager(originalSM);
            Thread.currentThread().setContextClassLoader(originalCL);
        }
    }
}
```

---

## 🔐 11.3 代码签名与验证机制

### 11.3.1 数字签名验证实现

```cpp
// hotspot/src/share/classfile/verifier.hpp
class CodeSignatureVerifier : public AllStatic {
private:
  static GrowableArray<Certificate*>* _trusted_certificates;
  static bool _signature_verification_enabled;
  
public:
  // 验证JAR文件签名
  static bool verify_jar_signature(const char* jar_path, TRAPS);
  
  // 验证类文件签名
  static bool verify_class_signature(Klass* klass, TRAPS);
  
  // 验证证书链
  static bool verify_certificate_chain(GrowableArray<Certificate*>* chain);
  
  // 添加受信任的证书
  static void add_trusted_certificate(Certificate* cert);
  
private:
  // 验证数字签名
  static bool verify_digital_signature(const char* data, int data_len,
                                      const char* signature, int sig_len,
                                      Certificate* cert);
  
  // 检查证书有效性
  static bool is_certificate_valid(Certificate* cert);
  
  // 检查证书撤销状态
  static bool is_certificate_revoked(Certificate* cert);
};

bool CodeSignatureVerifier::verify_jar_signature(const char* jar_path, TRAPS) {
  if (!_signature_verification_enabled) {
    return true; // 签名验证被禁用
  }
  
  // 打开JAR文件
  JarFile* jar = JarFile::open(jar_path, CHECK_false);
  if (jar == NULL) {
    return false;
  }
  
  // 读取MANIFEST.MF
  JarEntry* manifest_entry = jar->get_entry("META-INF/MANIFEST.MF");
  if (manifest_entry == NULL) {
    return false; // 没有清单文件
  }
  
  // 解析清单文件
  Manifest* manifest = Manifest::parse(manifest_entry->data(), 
                                      manifest_entry->size(), CHECK_false);
  
  // 验证每个签名文件
  GrowableArray<JarEntry*>* signature_files = jar->get_signature_files();
  for (int i = 0; i < signature_files->length(); i++) {
    JarEntry* sig_file = signature_files->at(i);
    
    if (!verify_signature_file(jar, manifest, sig_file)) {
      return false;
    }
  }
  
  return true;
}

bool CodeSignatureVerifier::verify_signature_file(JarFile* jar, Manifest* manifest, 
                                                 JarEntry* sig_file) {
  // 解析签名文件
  SignatureFile* signature = SignatureFile::parse(sig_file->data(), 
                                                  sig_file->size());
  
  // 获取对应的签名块文件
  String sig_block_name = sig_file->name().replace(".SF", ".RSA");
  JarEntry* sig_block_entry = jar->get_entry(sig_block_name);
  if (sig_block_entry == NULL) {
    sig_block_name = sig_file->name().replace(".SF", ".DSA");
    sig_block_entry = jar->get_entry(sig_block_name);
  }
  
  if (sig_block_entry == NULL) {
    return false; // 没有找到签名块文件
  }
  
  // 解析PKCS#7签名块
  PKCS7SignatureBlock* sig_block = PKCS7SignatureBlock::parse(
    sig_block_entry->data(), sig_block_entry->size());
  
  // 验证签名块中的证书链
  GrowableArray<Certificate*>* cert_chain = sig_block->get_certificate_chain();
  if (!verify_certificate_chain(cert_chain)) {
    return false;
  }
  
  // 验证签名文件的数字签名
  Certificate* signer_cert = cert_chain->at(0); // 签名者证书
  if (!verify_digital_signature(sig_file->data(), sig_file->size(),
                               sig_block->get_signature(), sig_block->get_signature_length(),
                               signer_cert)) {
    return false;
  }
  
  // 验证清单文件的摘要
  String manifest_digest = signature->get_manifest_digest();
  String computed_digest = compute_digest(manifest->to_string());
  if (!manifest_digest.equals(computed_digest)) {
    return false;
  }
  
  // 验证每个条目的摘要
  GrowableArray<ManifestEntry*>* entries = signature->get_entries();
  for (int i = 0; i < entries->length(); i++) {
    ManifestEntry* entry = entries->at(i);
    
    if (!verify_entry_digest(jar, entry)) {
      return false;
    }
  }
  
  return true;
}

bool CodeSignatureVerifier::verify_certificate_chain(GrowableArray<Certificate*>* chain) {
  if (chain == NULL || chain->length() == 0) {
    return false;
  }
  
  // 验证证书链的完整性
  for (int i = 0; i < chain->length() - 1; i++) {
    Certificate* cert = chain->at(i);
    Certificate* issuer = chain->at(i + 1);
    
    if (!cert->is_issued_by(issuer)) {
      return false;
    }
    
    if (!is_certificate_valid(cert)) {
      return false;
    }
    
    if (is_certificate_revoked(cert)) {
      return false;
    }
  }
  
  // 验证根证书是否受信任
  Certificate* root_cert = chain->at(chain->length() - 1);
  return is_trusted_certificate(root_cert);
}

// Java层代码签名验证
public class CodeSignatureValidator {
    private final Set<Certificate> trustedCertificates;
    private final CertPathValidator certPathValidator;
    private final boolean signatureRequired;
    
    public CodeSignatureValidator(boolean signatureRequired) {
        this.signatureRequired = signatureRequired;
        this.trustedCertificates = loadTrustedCertificates();
        this.certPathValidator = CertPathValidator.getInstance("PKIX");
    }
    
    public boolean validateJarFile(String jarPath) throws Exception {
        JarFile jarFile = new JarFile(jarPath, true); // 启用签名验证
        
        try {
            // 检查JAR文件是否已签名
            boolean isSigned = isJarSigned(jarFile);
            
            if (signatureRequired && !isSigned) {
                throw new SecurityException("JAR file must be signed: " + jarPath);
            }
            
            if (!isSigned) {
                return true; // 不需要签名验证
            }
            
            // 验证所有条目的签名
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                
                if (entry.isDirectory() || isSignatureFile(entry.getName())) {
                    continue;
                }
                
                // 读取条目内容以触发签名验证
                readEntryContent(jarFile, entry);
                
                // 检查条目的证书
                Certificate[] certificates = entry.getCertificates();
                if (certificates != null && certificates.length > 0) {
                    validateCertificateChain(certificates);
                }
            }
            
            return true;
            
        } finally {
            jarFile.close();
        }
    }
    
    private boolean isJarSigned(JarFile jarFile) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName().toUpperCase();
            
            if (name.startsWith("META-INF/") && 
                (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                return true;
            }
        }
        return false;
    }
    
    private void validateCertificateChain(Certificate[] certificates) throws Exception {
        // 构建证书路径
        List<Certificate> certList = Arrays.asList(certificates);
        CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(certList);
        
        // 创建信任锚点
        Set<TrustAnchor> trustAnchors = new HashSet<>();
        for (Certificate trustedCert : trustedCertificates) {
            if (trustedCert instanceof X509Certificate) {
                trustAnchors.add(new TrustAnchor((X509Certificate) trustedCert, null));
            }
        }
        
        // 验证证书路径
        PKIXParameters params = new PKIXParameters(trustAnchors);
        params.setRevocationEnabled(true); // 启用撤销检查
        
        try {
            certPathValidator.validate(certPath, params);
        } catch (CertPathValidatorException e) {
            throw new SecurityException("Certificate validation failed", e);
        }
        
        // 额外的安全检查
        performAdditionalSecurityChecks(certificates);
    }
    
    private void performAdditionalSecurityChecks(Certificate[] certificates) throws Exception {
        X509Certificate signerCert = (X509Certificate) certificates[0];
        
        // 检查证书有效期
        signerCert.checkValidity();
        
        // 检查关键用途扩展
        boolean[] keyUsage = signerCert.getKeyUsage();
        if (keyUsage != null && !keyUsage[0]) { // digitalSignature
            throw new SecurityException("Certificate does not allow digital signatures");
        }
        
        // 检查扩展密钥用途
        List<String> extKeyUsage = signerCert.getExtendedKeyUsage();
        if (extKeyUsage != null && !extKeyUsage.contains("1.3.6.1.5.5.7.3.3")) { // codeSigning
            throw new SecurityException("Certificate is not valid for code signing");
        }
        
        // 检查证书主题
        String subject = signerCert.getSubjectDN().getName();
        if (!isValidCodeSigningSubject(subject)) {
            throw new SecurityException("Invalid code signing certificate subject: " + subject);
        }
    }
    
    private boolean isValidCodeSigningSubject(String subject) {
        // 实现证书主题验证逻辑
        // 例如：检查组织名称、国家代码等
        return subject.contains("CN=") && subject.contains("O=");
    }
}
```

这个第11章展示了JVM安全机制的深度实现，包括：

1. **安全管理器机制** - SecurityManager和AccessController的完整实现
2. **权限模型** - Permission类层次和权限检查机制
3. **沙箱模型** - Applet沙箱和自定义沙箱实现
4. **代码签名验证** - 数字签名验证和证书链验证

您希望我继续创建其他高级专题章节，还是深化某个特定的安全机制？我可以继续扩展：

- **第12章**: JVM国际化与本地化支持
- **第13章**: JVM与操作系统交互深度分析  
- **第14章**: JVM扩展机制与插件开发
- **企业级实战案例集合**
- **专项工具链深度分析**

请告诉我您希望继续哪个方向！