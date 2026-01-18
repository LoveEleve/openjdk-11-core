# 第1章：JVM架构总览

> **本章目标**：建立JVM的整体认知，理解各个子系统的职责，为后续深入学习打下基础。

---

## 1.1 HotSpot VM：工业级虚拟机

OpenJDK 11使用的是**HotSpot VM**，这是目前最成熟、性能最好的JVM实现之一。

### 为什么叫"HotSpot"？

HotSpot的名字来源于其核心特性：**热点代码检测**（HotSpot Detection）。

- JVM会监控代码的执行频率
- 频繁执行的代码被识别为"热点"（Hot Spot）
- 热点代码会被JIT编译器优化成本地机器码
- 冷代码继续用解释器执行，节省编译开销

**这就是"自适应优化"的精髓**：把优化资源集中在真正需要的地方。

---

## 1.2 JVM的三层执行引擎

HotSpot VM使用**分层编译**（Tiered Compilation）策略，有三个执行层次：

```
┌─────────────────────────────────────────┐
│          Java字节码 (.class)             │
└──────────────┬──────────────────────────┘
               │
    ┌──────────┼──────────┐
    │          │          │
    ▼          ▼          ▼
┌────────┐ ┌──────┐ ┌──────────┐
│ 解释器  │ │  C1  │ │    C2    │
│Template│ │Client│ │  Server  │
│Interp. │ │Comp. │ │ Compiler │
└────────┘ └──────┘ └──────────┘
    │          │          │
    └──────────┴──────────┘
              │
         本地机器码执行
```

### 层次0：解释器（Interpreter）
- **职责**：直接执行字节码
- **优点**：启动快，无编译开销
- **缺点**：执行慢（每条指令都需要解释）
- **实现**：`hotspot/src/share/interpreter/`

### 层次1-3：C1编译器（Client Compiler）
- **职责**：快速编译，轻量优化
- **特点**：
  - 编译速度快（几毫秒）
  - 优化较少（只做基础优化）
  - 适合客户端应用
- **实现**：`hotspot/src/share/c1/`

### 层次4：C2编译器（Server Compiler）
- **职责**：深度优化，生成高质量机器码
- **特点**：
  - 编译慢（可能几百毫秒）
  - 优化激进（逃逸分析、循环优化等）
  - 适合服务端长时间运行
- **实现**：`hotspot/src/share/opto/`

### 分层编译策略

```
方法首次调用 → 解释执行（收集profiling数据）
     ↓
调用次数达到阈值 → C1编译（快速优化）
     ↓
继续热点 → C2编译（深度优化）
```

**关键参数**：
```bash
-XX:CompileThreshold=10000      # C1编译阈值
-XX:Tier3InvocationThreshold=200 # 分层阈值
-XX:Tier4InvocationThreshold=5000 # C2编译阈值
```

---

## 1.3 JVM的核心子系统

HotSpot VM由多个子系统协同工作：

```
┌───────────────────────────────────────────────────────┐
│                    JVM 架构图                          │
├───────────────────────────────────────────────────────┤
│                                                       │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────┐ │
│  │  类加载器    │───→│ 执行引擎      │←───│ JIT编译器│ │
│  │ ClassLoader │    │  - 解释器     │    │ C1 / C2 │ │
│  └─────────────┘    │  - 字节码执行 │    └─────────┘ │
│         │           └──────────────┘          │     │
│         │                  │                  │     │
│         ▼                  ▼                  ▼     │
│  ┌──────────────────────────────────────────────┐  │
│  │            运行时数据区 (Runtime Data Area)   │  │
│  ├──────────────────────────────────────────────┤  │
│  │  Method Area (元空间) │ Heap (堆)            │  │
│  │  - 类元数据           │ - 对象实例            │  │
│  │  - 常量池             │ - 数组                │  │
│  ├──────────────────────────────────────────────┤  │
│  │  Java Stacks         │ Native Method Stacks │  │
│  │  - 栈帧               │ - JNI调用栈          │  │
│  ├──────────────────────────────────────────────┤  │
│  │  PC Registers        │ Code Cache           │  │
│  │  - 程序计数器         │ - 编译后的机器码      │  │
│  └──────────────────────────────────────────────┘  │
│                          │                         │
│                          ▼                         │
│  ┌──────────────────────────────────────────────┐  │
│  │          垃圾收集器 (Garbage Collector)       │  │
│  │                                              │  │
│  │  ┌──────┐  ┌──────┐  ┌─────┐  ┌─────────┐  │  │
│  │  │Serial│  │Parallel│ │ CMS │  │   G1    │  │  │
│  │  └──────┘  └──────┘  └─────┘  └─────────┘  │  │
│  │                                 (本书重点)   │  │
│  └──────────────────────────────────────────────┘  │
│                                                    │
└────────────────────────────────────────────────────┘
```

### 1.3.1 类加载子系统

**职责**：将`.class`文件加载到JVM，转换为内部表示。

**核心组件**：
- `ClassLoader`: 类加载器（Bootstrap、Ext、App）
- `ClassFileParser`: 字节码解析器
- `SystemDictionary`: 类字典（已加载类的注册表）
- `Verifier`: 字节码验证器

**源码位置**：`hotspot/src/share/classfile/`

---

### 1.3.2 运行时数据区

#### A. 方法区（Method Area）/ 元空间（Metaspace）

**存储内容**：
- 类的元数据（类名、方法、字段）
- 常量池（字面量、符号引用）
- 静态变量
- JIT编译后的代码

**JDK 8+的变化**：
```
JDK 7及之前：PermGen（永久代，在堆中）
JDK 8+：     Metaspace（元空间，在本地内存）
```

**为什么改成Metaspace？**
1. PermGen大小固定，容易OOM
2. Metaspace使用本地内存，可动态扩展
3. 简化GC（不需要对PermGen做Full GC）

**源码位置**：`hotspot/src/share/memory/metaspace.hpp`

---

#### B. 堆（Heap）

**存储内容**：所有对象实例、数组

**在G1中的布局**：
```
┌────────────────────────────────────────┐
│            G1 Heap (8GB)               │
├────────────────────────────────────────┤
│  Region 0  │  Region 1  │  Region 2   │ ← 每个Region 1-32MB
├────────────────────────────────────────┤
│   Eden     │   Eden     │  Survivor   │ ← 动态分配角色
├────────────────────────────────────────┤
│    Old     │    Old     │  Humongous  │ ← 大对象跨Region
└────────────────────────────────────────┘
```

**关键特性**：
- 所有线程共享
- GC的主要工作区域
- 占JVM内存的绝大部分（本书设定为8GB）

**源码位置**：`hotspot/src/share/gc/g1/g1CollectedHeap.hpp`

---

#### C. 栈（Stacks）

**Java虚拟机栈**（每个线程私有）：
```
┌─────────────────────┐  ← 栈顶
│   栈帧 3 (当前方法)  │
├─────────────────────┤
│   栈帧 2            │
├─────────────────────┤
│   栈帧 1 (main方法) │
└─────────────────────┘  ← 栈底
```

**每个栈帧包含**：
- 局部变量表
- 操作数栈
- 动态链接
- 方法返回地址

**栈溢出**：
```java
// 无限递归 → StackOverflowError
void recursive() { recursive(); }
```

**源码位置**：`hotspot/src/share/runtime/frame.hpp`

---

#### D. 代码缓存（Code Cache）

**存储内容**：JIT编译后的本地机器码

**分类**：
```
Non-Method Code:  VM内部代码（stub、adapter等）
Profiled Code:    C1编译的代码（带profiling）
Non-Profiled Code: C2编译的代码（高度优化）
```

**大小限制**：
```bash
-XX:ReservedCodeCacheSize=240m  # 默认240MB
```

**满了会怎样？**
- 停止JIT编译
- 性能下降到解释执行
- 日志警告：`CodeCache is full`

**源码位置**：`hotspot/src/share/code/codeCache.hpp`

---

### 1.3.3 执行引擎

负责执行字节码或本地代码，前面已详细介绍。

---

### 1.3.4 垃圾收集器（本书核心）

**G1 GC的设计目标**：
1. ✅ **可预测的停顿时间**（默认200ms）
2. ✅ **高吞吐量**（90%+的时间在运行应用）
3. ✅ **无内存碎片**（整理算法）
4. ✅ **支持大堆**（几十GB到上百GB）

**G1 vs 其他GC**：

| 特性 | Serial | Parallel | CMS | **G1** |
|------|--------|----------|-----|--------|
| 算法 | 复制+标整 | 复制+标整 | 标清 | 复制+标整 |
| 停顿 | 长 | 长 | 短但不可控 | **可预测** |
| 吞吐 | 低 | 高 | 中 | **高** |
| 碎片 | 无 | 无 | 有 | **无** |
| 适用 | 单核 | 多核批处理 | 低延迟 | **通用** |

**第12章将深入G1的源码实现！**

---

## 1.4 源码目录结构导航

OpenJDK 11 HotSpot的源码组织（以本书关注的为主）：

```
hotspot/src/share/
├── gc/                     ← GC实现（本书重点！）
│   ├── g1/                 ← G1收集器（★★★★★）
│   │   ├── g1CollectedHeap.cpp      # 堆管理
│   │   ├── g1ConcurrentMark.cpp     # 并发标记
│   │   ├── g1Policy.cpp             # GC策略
│   │   ├── heapRegion.cpp           # Region管理
│   │   └── ...
│   ├── shared/             ← GC通用框架
│   │   ├── collectedHeap.cpp
│   │   ├── gcCause.cpp
│   │   └── ...
│   ├── parallel/           ← Parallel GC
│   ├── serial/             ← Serial GC
│   └── ...
│
├── oops/                   ← 对象模型（第2章）
│   ├── oop.hpp             # 对象指针
│   ├── klass.hpp           # 类元数据
│   ├── method.hpp          # 方法
│   └── ...
│
├── runtime/                ← 运行时（第7章）
│   ├── thread.cpp          # 线程管理
│   ├── safepoint.cpp       # 安全点
│   ├── frame.cpp           # 栈帧
│   └── ...
│
├── interpreter/            ← 解释器（第4章）
│   ├── templateInterpreter.cpp
│   └── ...
│
├── compiler/               ← 编译器（第5章）
│   └── compileBroker.cpp
│
├── opto/                   ← C2优化编译器（第6章）
│   ├── compile.cpp
│   ├── escape.cpp          # 逃逸分析
│   └── ...
│
├── memory/                 ← 内存管理（第8章）
│   ├── metaspace.cpp
│   ├── universe.cpp
│   └── ...
│
└── services/               ← 监控服务（第18章）
    ├── management.cpp
    └── ...
```

---

## 1.5 如何阅读JVM源码？

### 策略1：从问题出发

不要漫无目的地看代码，而是带着问题：

❓ **问题**：对象是如何创建的？  
📁 **入口**：`instanceKlass::allocate_instance()` → `CollectedHeap::obj_allocate()`  
🎯 **收获**：理解TLAB、堆分配、初始化流程

❓ **问题**：G1如何选择要回收的Region？  
📁 **入口**：`G1Policy::finalize_collection_set()`  
🎯 **收获**：理解CSet选择算法、收益计算

### 策略2：自顶向下

从高层API入口开始，逐步深入：

```
java.lang.System.gc()               # Java API
  ↓
JVM_GC()                            # JNI入口
  ↓
Universe::heap()->collect()         # 堆接口
  ↓
G1CollectedHeap::do_collection()    # G1实现
  ↓
... 更多细节
```

### 策略3：关注数据结构

理解了数据结构，就理解了一半：

```cpp
// G1的Region表示
class HeapRegion {
  size_t _humongous_start_region;  // 大对象起始Region
  G1BlockOffsetTable* _bot;        // 块偏移表
  HeapRegionRemSet* _rem_set;      // Remember Set（核心！）
  // ...
};
```

### 工具推荐

1. **IDE**：CLion、VSCode（C++ IntelliSense）
2. **搜索**：`grep -r "关键字" hotspot/src/`
3. **调试**：编译Debug版JVM，用GDB调试
4. **可视化**：使用JFR、JMC分析运行时行为

---

## 1.6 本章小结

✅ **HotSpot VM = 自适应优化虚拟机**  
✅ **三层执行引擎**：解释器 → C1 → C2  
✅ **核心子系统**：类加载、运行时、执行引擎、GC  
✅ **G1 GC**：可预测停顿、高吞吐、支持大堆  
✅ **源码导航**：`hotspot/src/share/gc/g1/` 是本书重点  

---

## 下一章预告

**[第1.5章：JVM初始化深度剖析](./01.5-JVM初始化深度剖析.md)** 将带你深入JVM的启动过程：
- 基于GDB调试验证的24个初始化函数分析
- 真实的内存状态变化和函数执行地址
- 发现纯源码分析无法获得的关键信息
- 理解JVM启动优化和防御性编程策略

这是**全网首个基于调试验证的JVM初始化分析**，颠覆传统的源码分析方法！

然后是 **[第2章：对象模型](./02-对象模型.md)** 将回答：
- Java对象在内存中长什么样？
- 对象头的Mark Word存储了什么？
- Klass指针的作用是什么？
- 压缩指针如何节省内存？

这是理解JVM的基石，也是理解GC的前提！

---

📖 **思考题**

1. 为什么HotSpot要设计三层执行引擎，而不是只用一个？
2. Metaspace相比PermGen有什么优势？
3. 如果Code Cache满了，应用会有什么表现？

（答案在后续章节中）
