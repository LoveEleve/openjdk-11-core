# init_globals() 深度解析 - 基于GDB真实调试数据

> **验证环境**: OpenJDK 11.0.17-internal (slowdebug build)
> **调试工具**: GDB 批处理模式
> **JVM参数**: `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages`
> **测试程序**: HelloWorld.java
> **验证时间**: 2026-01-14

## 一、函数概述

`init_globals()` 是JVM启动过程中最核心的初始化函数之一，位于 `src/hotspot/share/runtime/init.cpp`。

### 1.1 GDB验证的函数入口信息

```
========================================
=== init_globals() 入口 ===
========================================
#0  management_init () at management.cpp:86
#1  init_globals () at init.cpp:111
#2  Threads::create_vm (args=0x7ffff780add0, canTryAgain=0x7ffff780acc3) at thread.cpp:4060
#3  JNI_CreateJavaVM_inner at jni.cpp:4010
#4  JNI_CreateJavaVM at jni.cpp:4103
```

### 1.2 函数签名

```cpp
// src/hotspot/share/runtime/init.cpp:104
jint init_globals() {
  HandleMark hm;
  // ... 26个初始化函数调用
  return JNI_OK;
}
```

### 1.3 初始化流程概览

`init_globals()` 共调用约 **26个初始化函数**，可分为以下几个阶段：

| 阶段 | 函数数量 | 核心功能 |
|------|----------|----------|
| 第一阶段：基础设施 | 7个 | JMX管理、字节码表、编译策略、代码缓存、CPU特性、桩代码Phase1 |
| 第二阶段：Universe核心 | 2个 | 创建Java堆、元空间、符号表等核心数据结构 |
| 第三阶段：运行时系统 | 10个 | 解释器、模板表、Java类初始化、引用处理器 |
| 第四阶段：编译系统 | 5个 | vtable、内联缓存、编译器 |
| 第五阶段：后处理 | 2个 | universe_post_init、StubRoutines第二阶段 |

## 二、完整初始化函数列表

| 序号 | 函数 | 源文件 | 说明 | 详细文档 |
|------|------|--------|------|----------|
| 1 | management_init() | services/management.cpp | JMX管理接口 | 04_management_init |
| 2 | bytecodes_init() | interpreter/bytecodes.cpp | 字节码表 | 05_bytecodes_init |
| 3 | classLoader_init1() | classfile/classLoader.cpp | 类加载器(空方法) | - |
| 4 | compilationPolicy_init() | runtime/compilationPolicy.cpp | 编译策略 | 06_compilationPolicy_init |
| 5 | codeCache_init() | code/codeCache.cpp | 代码缓存 | 07_codeCache_init |
| 6 | VM_Version_init() | runtime/vm_version.cpp | CPU特性检测 | 08_VM_Version_init |
| 7 | os_init_globals() | runtime/os.cpp | OS全局初始化(空) | - |
| 8 | stubRoutines_init1() | runtime/stubRoutines.cpp | 桩代码Phase1 | 09_stubRoutines_init |
| 9 | universe_init() | memory/universe.cpp | 宇宙初始化 | 02_Universe对象深度分析 |
| 10 | gc_barrier_stubs_init() | gc/shared/barrierSet.cpp | GC屏障桩 | - |
| 11 | interpreter_init() | interpreter/interpreter.cpp | 解释器 | 10_interpreter_init |
| 12 | invocationCounter_init() | interpreter/invocationCounter.cpp | 调用计数器 | 11_其他初始化函数 |
| 13 | accessFlags_init() | oops/accessFlags.cpp | 访问标志 | - |
| 14 | templateTable_init() | interpreter/templateTable.cpp | 模板表 | 11_其他初始化函数 |
| 15 | InterfaceSupport_init() | runtime/interfaceSupport.cpp | 接口支持 | - |
| 16 | VMRegImpl::set_regName() | code/vmreg.cpp | 寄存器名 | - |
| 17 | SharedRuntime::generate_stubs() | runtime/sharedRuntime.cpp | 共享运行时桩 | 11_其他初始化函数 |
| 18 | universe2_init() | memory/universe.cpp | 宇宙初始化Phase2 | 02_Universe对象深度分析 |
| 19 | javaClasses_init() | classfile/javaClasses.cpp | Java类偏移量 | - |
| 20 | referenceProcessor_init() | gc/shared/referenceProcessor.cpp | 引用处理器 | - |
| 21 | jni_handles_init() | runtime/jniHandles.cpp | JNI句柄 | 11_其他初始化函数 |
| 22 | vtableStubs_init() | code/vtableStubs.cpp | 虚表桩 | - |
| 23 | InlineCacheBuffer_init() | code/icBuffer.cpp | 内联缓存 | 11_其他初始化函数 |
| 24 | compilerOracle_init() | compiler/compilerOracle.cpp | 编译器Oracle | - |
| 25 | dependencyContext_init() | code/dependencyContext.cpp | 依赖上下文 | - |
| 26 | compileBroker_init() | compiler/compileBroker.cpp | 编译代理 | 11_其他初始化函数 |
| 27 | universe_post_init() | memory/universe.cpp | 宇宙后初始化 | - |
| 28 | stubRoutines_init2() | runtime/stubRoutines.cpp | 桩代码Phase2 | 09_stubRoutines_init |
| 29 | MethodHandles::generate_adapters() | prims/methodHandles.cpp | 方法句柄适配器 | - |

## 三、GDB验证的调试数据摘要

### 3.1 编译策略参数

| 参数 | 值 | 说明 |
|------|-----|------|
| TieredCompilation | true | 启用分层编译 |
| TieredStopAtLevel | 4 | 最高编译层级 |
| Tier3InvocationThreshold | 200 | Tier3调用阈值 |
| Tier4InvocationThreshold | 5000 | Tier4调用阈值 |
| CICompilerCount | 12 | 编译器线程数 (4 C1 + 8 C2) |

### 3.2 代码缓存配置

| 参数 | 值 | 说明 |
|------|-----|------|
| ReservedCodeCacheSize | 240MB | 预留代码缓存大小 |
| SegmentedCodeCache | true | 启用分段代码缓存 |
| CodeHeap数量 | 3 | non-profiled / profiled / non-nmethods |

### 3.3 CPU特性

| 特性 | 值 |
|------|-----|
| UseSSE | 4 (SSE4.2) |
| UseAVX | 2 (AVX2) |
| UseAES | true |
| UseSHA | true |

### 3.4 堆配置 (8GB)

| 参数 | 值 |
|------|-----|
| MaxHeapSize | 8GB |
| InitialHeapSize | 8GB |
| UseG1GC | true |
| UseLargePages | false |
| Region大小 | 4MB |
| Region总数 | 2048 |
| Humongous阈值 | 2MB |

### 3.5 元空间配置

| 参数 | 值 |
|------|-----|
| MaxMetaspaceSize | 无限制 |
| MetaspaceSize | ~21MB |
| CompressedClassSpaceSize | 1GB |
| UseCompressedOops | true |
| UseCompressedClassPointers | true |

### 3.6 解释器

| 属性 | 值 |
|------|-----|
| 代码大小 | ~162KB |
| 桩数量 | 271 |

### 3.7 StubRoutines

| 阶段 | 大小 |
|------|------|
| Phase 1 | ~29KB |
| Phase 2 | ~45KB |
| 总计 | ~74KB |

## 四、线程创建情况

在 `init_globals()` 执行过程中，GDB观察到以下线程创建：

```
[New Thread 0x7ffff780b6c0 (LWP xxx)]  <- 主线程切换
[New Thread 0x7ffff51ff6c0 (LWP xxx)]  <- G1堆初始化期间
[New Thread 0x7ffff43f06c0 (LWP xxx)]  <- G1堆初始化期间
[New Thread 0x7ffff42ee6c0 (LWP xxx)]  <- G1堆初始化期间
[New Thread 0x7fffc33f36c0 (LWP xxx)]  <- G1堆初始化期间
[New Thread 0x7fffc32f16c0 (LWP xxx)]  <- G1堆初始化期间
... 共计8+个新线程（主要是GC工作线程）
```

这些线程主要是：
- **G1 GC工作线程**: 13个并行GC线程
- **G1 并发标记线程**: 3个并发标记线程
- **编译器线程**: 延迟创建

## 五、关键对象创建时序

```
时序图:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
时间 ──────────────────────────────────────────────────────────────►

init_globals()入口
    │
    ├─ management_init()
    │   └─ PerfVariable创建 (VM时间戳)
    │
    ├─ bytecodes_init()
    │   └─ Bytecodes::_is_initialized = true
    │
    ├─ compilationPolicy_init()
    │   └─ TieredThresholdPolicy创建 [C1:4, C2:8]
    │
    ├─ codeCache_init()
    │   └─ 3个CodeHeap创建 (240MB预留)
    │
    ├─ VM_Version_init()
    │   └─ CPU特性检测 (SSE4.2, AVX2, AES, SHA)
    │
    ├─ stubRoutines_init1()
    │   └─ StubRoutines (1) 创建 (~29KB)
    │
    ├─ universe_init() ════════════════════════════════════════════
    │   │                        ↑
    │   │                    最核心的部分
    │   │                        ↓
    │   ├─ G1CollectedHeap创建 [0x7ffff0032530, 1864字节]
    │   │   ├─ HeapRegionManager (2048 regions, 4MB each)
    │   │   ├─ G1Policy [C1:4, C2:8]
    │   │   ├─ G1CardTable (16MB)
    │   │   ├─ G1ConcurrentMark (3 threads)
    │   │   └─ WorkGang (13 GC threads)
    │   │
    │   ├─ Metaspace初始化 (1GB CompressedClassSpace)
    │   │
    │   ├─ SymbolTable创建
    │   │
    │   └─ StringTable创建
    │   ═══════════════════════════════════════════════════════════
    │
    ├─ interpreter_init()
    │   └─ 解释器模板代码生成 (~162KB, 271个桩)
    │
    ├─ SharedRuntime::generate_stubs()
    │   └─ IC miss, wrong method, resolve等桩
    │
    ├─ universe2_init()
    │   └─ 加载核心类（Object、Class、数组Klass）
    │
    ├─ javaClasses_init()
    │   └─ java.lang.* 类偏移量计算
    │
    ├─ compileBroker_init()
    │   └─ 编译器准备（线程延迟创建）
    │
    ├─ universe_post_init()
    │   └─ OutOfMemoryError预创建
    │
    └─ stubRoutines_init2()
        └─ StubRoutines (2) 创建 (~45KB)
            ├─ arraycopy桩
            ├─ CRC32/AES/SHA桩
            └─ 数学运算桩
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

## 六、文档索引

| 文档编号 | 文件名 | 内容 |
|----------|--------|------|
| 01 | 01_init_globals_概述.md | 本文档 - 总览 |
| 02 | 02_Universe对象深度分析.md | Universe初始化详解 |
| 03 | 03_G1CollectedHeap深度分析.md | G1堆详细分析 |
| 04 | 04_management_init_JMX管理接口.md | JMX管理接口 |
| 05 | 05_bytecodes_init_字节码表.md | 字节码表初始化 |
| 06 | 06_compilationPolicy_init_编译策略.md | 编译策略 |
| 07 | 07_codeCache_init_代码缓存.md | 代码缓存 |
| 08 | 08_VM_Version_init_CPU特性检测.md | CPU特性检测 |
| 09 | 09_stubRoutines_init_汇编桩代码.md | 汇编桩代码 |
| 10 | 10_interpreter_init_解释器.md | 解释器初始化 |
| 11 | 11_其他初始化函数.md | 其他初始化函数 |
