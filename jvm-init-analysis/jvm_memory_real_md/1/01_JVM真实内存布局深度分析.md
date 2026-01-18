# JVM真实内存布局深度分析

> **约定**：基于 **OpenJDK 11**、**Linux x86-64**、**8GB堆内存**、**G1垃圾收集器**
> 测试命令：`java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages HelloWorld`
> 本文档通过 `-Xlog` 和 NativeMemoryTracking 获取真实JVM内存数据

---

## 一、传统"八股文" vs 真实内存布局

### 1.1 传统八股文版本

```
┌─────────────────────────────────────────┐
│              JVM 内存区域                │
├─────────────────────────────────────────┤
│  方法区 (Method Area)                   │  ← 类信息、常量池
│  堆 (Heap)                              │  ← 对象实例
│  虚拟机栈 (JVM Stack)                   │  ← 方法调用栈帧
│  本地方法栈 (Native Method Stack)       │  ← native方法
│  程序计数器 (PC Register)               │  ← 当前执行地址
└─────────────────────────────────────────┘
```

### 1.2 真实内存布局 (NMT验证)

通过 `-XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics` 获取：

```
Native Memory Tracking:

Total: reserved=10,359,276,889 (~9.65GB), committed=9,017,218,393 (~8.4GB)

-                 Java Heap (reserved=8,589,934,592, committed=8,589,934,592)  ← 8GB
-                     Class (reserved=1,082,380,651, committed=7,197,035)       ← ~1GB reserved
-                    Thread (reserved=23,177,120, committed=1,144,736)          ← ~22MB
-                      Code (reserved=254,935,735, committed=10,093,239)        ← ~243MB
-                        GC (reserved=392,628,487, committed=392,628,487)       ← ~374MB
-                  Compiler (reserved=187,802, committed=187,802)               ← ~183KB
-                  Internal (reserved=604,603, committed=604,603)               ← ~590KB
-                    Symbol (reserved=2,626,280, committed=2,626,280)           ← ~2.5MB
-    Native Memory Tracking (reserved=961,096, committed=961,096)               ← ~938KB
-               Arena Chunk (reserved=11,683,200, committed=11,683,200)         ← ~11MB
-                   Tracing (reserved=120, committed=120)
-                   Logging (reserved=6,276, committed=6,276)
-                 Arguments (reserved=20,639, committed=20,639)
-                    Module (reserved=77,344, committed=77,344)
-              Synchronizer (reserved=44,752, committed=44,752)
-                 Safepoint (reserved=8,192, committed=8,192)
```

---

## 二、64位虚拟地址空间布局

### 2.1 完整内存地图 (JVM日志验证)

```
64位虚拟地址空间布局
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

低地址
    │
    │  ... 用户空间低地址区域 ...
    │
0x0000_0006_0000_0000 (24GB) ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
                             ┃                                              ┃
                             ┃            Java Heap (8GB)                   ┃
                             ┃                                              ┃
                             ┃    2048个Region × 4MB/Region = 8GB          ┃
                             ┃                                              ┃
                             ┃  日志验证:                                   ┃
                             ┃  [gc,heap] Heap region size: 4M              ┃
                             ┃  [gc,heap,coops] Heap address: 0x600000000   ┃
                             ┃                 size: 8192 MB                ┃
                             ┃                 Compressed Oops mode: Zero   ┃
                             ┃                 Oop shift amount: 3          ┃
                             ┃                                              ┃
0x0000_0008_0000_0000 (32GB) ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
                             ┃                                              ┃
                             ┃       Compressed Class Space (1GB)           ┃
                             ┃                                              ┃
                             ┃  日志验证:                                   ┃
                             ┃  [gc,metaspace] node reserved=1048576.00 KB  ┃
                             ┃  [gc,metaspace] Narrow klass base:           ┃
                             ┃                 0x0000000800000000           ┃
                             ┃                 Narrow klass shift: 0        ┃
                             ┃  [gc,metaspace] Compressed class space:      ┃
                             ┃                 1073741824 (1GB)             ┃
                             ┃                 Address: 0x800000000         ┃
                             ┃                                              ┃
0x0000_0008_4000_0000 (33GB) ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

                             ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
                             ┃       非类元空间 (Non-class Metaspace)        ┃
                             ┃                                              ┃
                             ┃  日志验证:                                   ┃
                             ┃  [gc,metaspace] node reserved=8192.00 KB     ┃
                             ┃  [0x00007fc020b800000...0x00007fc020c000000) ┃
                             ┃                                              ┃
                             ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

高地址区域 (~0x7f....)
                             ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
0x00007f10e4000000           ┃              G1CardTable (16MB)              ┃
  ~                          ┃                                              ┃
0x00007f10e4ffffff           ┃  日志验证:                                   ┃
                             ┃  [gc,barrier] &_byte_map[0]: 0x7f10e4000000  ┃
                             ┃  [gc,barrier] &_byte_map[last]: 0x7f10e4ffffff┃
                             ┃  [gc,barrier] _byte_map_base: 0x7f10e1000000 ┃
                             ┃                                              ┃
                             ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
0x00007f10f8bf1000           ┃        G1BlockOffsetTable (16MB)             ┃
  ~                          ┃                                              ┃
0x00007f10f9bf1000           ┃  日志验证:                                   ┃
                             ┃  [gc,bot] rs.base(): 0x7f10f8bf1000          ┃
                             ┃  [gc,bot] rs.size(): 16777216                ┃
                             ┃  [gc,bot] rs end(): 0x7f10f9bf1000           ┃
                             ┃                                              ┃
                             ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
                             ┃              CodeCache (~245MB)              ┃
                             ┃                                              ┃
                             ┃  ├── non-nmethods: 7,420KB                   ┃
                             ┃  ├── profiled nmethods: 119,168KB            ┃
                             ┃  └── non-profiled nmethods: 119,172KB        ┃
                             ┃                                              ┃
                             ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
                             ┃              Thread Stacks                   ┃
                             ┃                                              ┃
                             ┃  每个线程: 1024KB stack + 4KB guard          ┃
                             ┃  示例: Thread 1304434 stack:                 ┃
                             ┃        0x7f10fa900000-0x7f10faa00000         ┃
                             ┃                                              ┃
                             ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

高地址
    │
```

---

## 三、各内存区域详解

### 3.1 Java Heap (堆)

**日志验证数据**：
```
[gc,heap     ] Heap region size: 4M
[gc,heap     ] Minimum heap 8589934592  Initial heap 8589934592  Maximum heap 8589934592
[gc,heap,coops] Trying to allocate at address 0x0000000600000000 heap of size 0x200000000
[gc,heap,coops] Heap address: 0x0000000600000000, size: 8192 MB, 
                Compressed Oops mode: Zero based, Oop shift amount: 3
[gc,ergo,heap] Expand the heap. requested: 8589934592B, expansion: 8589934592B
```

| 属性 | 值 | 说明 |
|------|-----|------|
| 起始地址 | `0x600000000` | 24GB位置 |
| 结束地址 | `0x800000000` | 32GB位置 |
| 大小 | 8,589,934,592 bytes | 8GB |
| Region数量 | 2048个 | 8GB / 4MB |
| Region大小 | 4MB | 自动计算 |
| 压缩指针模式 | Zero based | base=0, shift=3 |

**Region分配日志**：
```
[gc,region] G1HR COMMIT(FREE) [0x0000000600000000, 0x0000000600000000, 0x0000000600400000]
[gc,region] G1HR COMMIT(FREE) [0x0000000600400000, 0x0000000600400000, 0x0000000600800000]
[gc,region] G1HR COMMIT(FREE) [0x0000000600800000, 0x0000000600800000, 0x0000000600c00000]
... (共2048个Region)
```

---

### 3.2 Metaspace (元空间)

**日志验证数据**：
```
[gc,metaspace] node @0x00007f0240ccb5c0: reserved=1048576.00 KB, committed=0.00 KB
               [0x0000000800000000, 0x0000000800000000, 0x0000000800000000, 0x0000000840000000)

[gc,metaspace] Narrow klass base: 0x0000000800000000, Narrow klass shift: 0
[gc,metaspace] Compressed class space size: 1073741824 Address: 0x0000000800000000

[gc,metaspace] node @0x00007f0240ccb7f0: reserved=8192.00 KB, committed=0.00 KB
               [0x00007f020b800000, 0x00007f020b800000, 0x00007f020b800000, 0x00007f020c000000)
```

**Metaspace结构**：

| 类型 | 地址范围 | 预留大小 | 说明 |
|------|----------|----------|------|
| **Compressed Class Space** | `0x800000000` ~ `0x840000000` | 1GB | 存储类元数据（Klass） |
| **Non-class Metaspace** | `0x7f020b800000` ~ `0x7f020c000000` | 8MB初始 | 方法、常量池等 |

**Metachunk分配日志**：
```
[gc,metaspace,freelist] SpaceManager::added chunk:
[gc,metaspace,freelist] Metachunk: bottom 0x00007f020b800000 top 0x00007f020b800040 
                         end 0x00007f020bc00000 size 524288 (humongous)

[gc,metaspace,freelist] SpaceManager::added chunk:
[gc,metaspace,freelist] Metachunk: bottom 0x0000000800000000 top 0x0000000800000040 
                         end 0x0000000800060000 size 49152 (humongous)
```

**NMT验证**：
```
Class (reserved=1,082,380,651, committed=7,197,035)
      (classes #911)
      (instance classes #814, array classes #97)
      (mmap: reserved=1,082,130,432, committed=6,946,816)
          (reserved=8,388,608, committed=6,291,456)
      (Class space:)
          (reserved=1,073,741,824, committed=655,360)
          (used=572,008)
```

---

### 3.3 G1CardTable (卡表)

**日志验证数据**：
```
[gc,barrier] G1CardTable::G1CardTable:
[gc,barrier]     &_byte_map[0]: 0x00007f10e4000000
[gc,barrier]     &_byte_map[_last_valid_index]: 0x00007f10e4ffffff
[gc,barrier]     _byte_map_base: 0x00007f10e1000000
```

| 属性 | 值 | 计算公式 |
|------|-----|----------|
| 起始地址 | `0x7f10e4000000` | - |
| 结束地址 | `0x7f10e4ffffff` | - |
| 大小 | 16MB | 8GB / 512B = 16,777,216 |
| 基地址 | `0x7f10e1000000` | 用于地址计算 |
| 每卡覆盖 | 512 bytes | CardTableBarrierSet::card_size |

---

### 3.4 G1BlockOffsetTable (块偏移表)

**日志验证数据**：
```
[gc,bot] G1BlockOffsetTable::G1BlockOffsetTable:
[gc,bot]     rs.base(): 0x00007f10f8bf1000
[gc,bot]     rs.size(): 16777216
[gc,bot]     rs end(): 0x00007f10f9bf1000
```

| 属性 | 值 | 说明 |
|------|-----|------|
| 起始地址 | `0x7f10f8bf1000` | - |
| 结束地址 | `0x7f10f9bf1000` | - |
| 大小 | 16MB | 与卡表相同 |
| 用途 | 快速定位对象起始位置 | GC扫描优化 |

---

### 3.5 CodeCache (代码缓存)

**日志验证数据**：
```
CodeHeap 'non-profiled nmethods': size=119172Kb used=1Kb max_used=1Kb free=119170Kb
CodeHeap 'profiled nmethods':     size=119168Kb used=1Kb max_used=1Kb free=119166Kb
CodeHeap 'non-nmethods':          size=7420Kb  used=1615Kb max_used=1626Kb free=5805Kb
```

| CodeHeap类型 | 预留大小 | 用途 |
|-------------|----------|------|
| **non-profiled nmethods** | 119,172KB (~116MB) | C2编译的高度优化代码 |
| **profiled nmethods** | 119,168KB (~116MB) | C1编译的带profiling代码 |
| **non-nmethods** | 7,420KB (~7MB) | 解释器、桩代码、适配器等 |
| **总计** | 245,760KB (~240MB) | - |

**NMT验证**：
```
Code (reserved=254,935,735, committed=10,093,239)
     (malloc=1,300,781 #36926)
     (mmap: reserved=253,628,416, committed=8,785,920)
```

---

### 3.6 Thread Stacks (线程栈)

**日志验证数据**：
```
[os,thread] Thread "GC Thread#0" started (stacksize: 1024k, guardsize: 4k)
[os,thread] Thread 1304434 stack dimensions: 0x7f10fa900000-0x7f10faa00000 (1024k)
[os,thread] Thread 1304434 stack guard pages activated: 0x7f10fa900000-0x7f10fa904000

[os,thread] Thread "G1 Main Marker" started (stacksize: 1024k, guardsize: 4k)
[os,thread] Thread "G1 Conc#0" started (stacksize: 1024k, guardsize: 4k)
[os,thread] Thread "G1 Refine#0" started (stacksize: 1024k, guardsize: 4k)
[os,thread] Thread "G1 Young RemSet Sampling" started (stacksize: 1024k, guardsize: 4k)
[os,thread] Thread "VM Thread" started (stacksize: 1024k, guardsize: 4k)
```

**典型线程**：

| 线程名 | 栈大小 | 保护页 | 用途 |
|--------|--------|--------|------|
| GC Thread#0-12 | 1024KB | 4KB | 并行GC工作线程 |
| G1 Main Marker | 1024KB | 4KB | 并发标记主线程 |
| G1 Conc#0-2 | 1024KB | 4KB | 并发GC线程 |
| G1 Refine#0 | 1024KB | 4KB | RSet细化线程 |
| G1 Young RemSet Sampling | 1024KB | 4KB | 年轻代采样 |
| VM Thread | 1024KB | 4KB | VM操作线程 |
| Java线程 | 1024KB | 4KB/0KB | 应用线程 |

**NMT验证**：
```
Thread (reserved=23,177,120, committed=1,144,736)
       (thread #22)
       (stack: reserved=23,068,672, committed=1,036,288)
```

---

### 3.7 GC相关内存

**NMT验证**：
```
GC (reserved=392,628,487, committed=392,628,487)
   (malloc=39,500,711 #14827)
   (mmap: reserved=353,128,448, committed=353,128,448)
```

**GC内存组成**：

| 组件 | 大小 | 说明 |
|------|------|------|
| G1CardTable | 16MB | 写屏障卡表 |
| G1BlockOffsetTable | 16MB | 块偏移表 |
| Marking Bitmap (prev) | 16MB | 上一轮标记位图 |
| Marking Bitmap (next) | 16MB | 当前标记位图 |
| MarkStack | 4-16MB | 标记栈 |
| HeapRegionManager | ~40MB | Region管理数据 |
| RSet (per Region) | 动态 | 记忆集 |
| SATB Queue | 动态 | 写屏障队列 |
| **总计** | ~374MB | - |

**MarkStack日志**：
```
[gc] MarkStackSize: 4096k  MarkStackSizeMax: 16384k
[gc] Initialize mark stack with 4096 chunks, maximum 16384
```

---

## 四、完整内存布局图

```
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃                    JVM 真实内存布局 (8GB堆/G1GC)                            ┃
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃                                                                            ┃
┃  ┌─────────────────────────────────────────────────────────────────────┐  ┃
┃  │ NMT Summary (Total: reserved=9.65GB, committed=8.4GB)               │  ┃
┃  └─────────────────────────────────────────────────────────────────────┘  ┃
┃                                                                            ┃
┃  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┃
┃  ┃ 1. Java Heap (8GB) - 地址: 0x600000000~0x800000000                 ┃   ┃
┃  ┃    ├── 2048个Region (每个4MB)                                     ┃   ┃
┃  ┃    ├── 压缩指针: Zero-based, shift=3                              ┃   ┃
┃  ┃    └── reserved=8GB, committed=8GB                                ┃   ┃
┃  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┃
┃                                                                            ┃
┃  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┃
┃  ┃ 2. Metaspace (~1GB)                                                ┃   ┃
┃  ┃    ├── Compressed Class Space (1GB) - 0x800000000~0x840000000      ┃   ┃
┃  ┃    │   └── Narrow klass base=0x800000000, shift=0                  ┃   ┃
┃  ┃    ├── Non-class Metaspace (8MB初始)                               ┃   ┃
┃  ┃    │   └── 0x7f020b800000~0x7f020c000000                           ┃   ┃
┃  ┃    └── classes: 911 (instance: 814, array: 97)                    ┃   ┃
┃  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┃
┃                                                                            ┃
┃  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┃
┃  ┃ 3. GC Data Structures (~374MB)                                     ┃   ┃
┃  ┃    ├── G1CardTable (16MB) - 0x7f10e4000000~0x7f10e4ffffff          ┃   ┃
┃  ┃    ├── G1BlockOffsetTable (16MB) - 0x7f10f8bf1000~0x7f10f9bf1000   ┃   ┃
┃  ┃    ├── Marking Bitmaps (32MB) - prev + next                        ┃   ┃
┃  ┃    ├── MarkStack (4-16MB)                                          ┃   ┃
┃  ┃    └── 其他GC元数据 (~300MB)                                       ┃   ┃
┃  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┃
┃                                                                            ┃
┃  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┃
┃  ┃ 4. CodeCache (~245MB)                                              ┃   ┃
┃  ┃    ├── non-nmethods (7.4MB) - 桩代码、解释器                       ┃   ┃
┃  ┃    ├── profiled nmethods (116MB) - C1编译代码                      ┃   ┃
┃  ┃    └── non-profiled nmethods (116MB) - C2编译代码                  ┃   ┃
┃  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┃
┃                                                                            ┃
┃  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┃
┃  ┃ 5. Thread Stacks (~22MB)                                           ┃   ┃
┃  ┃    ├── 22个线程, 每个1MB栈 + 4KB保护页                             ┃   ┃
┃  ┃    ├── GC Thread#0-12 (13个)                                       ┃   ┃
┃  ┃    ├── G1 Conc#0-2 (3个)                                           ┃   ┃
┃  ┃    ├── G1 Refine#0                                                 ┃   ┃
┃  ┃    └── VM Thread, Main Thread, 等                                  ┃   ┃
┃  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┃
┃                                                                            ┃
┃  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓   ┃
┃  ┃ 6. 其他内存区域                                                    ┃   ┃
┃  ┃    ├── Symbol (2.5MB) - 符号表                                     ┃   ┃
┃  ┃    ├── Arena Chunk (11MB) - 内存池                                 ┃   ┃
┃  ┃    ├── Compiler (183KB) - 编译器元数据                             ┃   ┃
┃  ┃    ├── Internal (590KB) - 内部使用                                 ┃   ┃
┃  ┃    ├── NMT (938KB) - Native Memory Tracking开销                    ┃   ┃
┃  ┃    ├── Module (77KB)                                               ┃   ┃
┃  ┃    ├── Synchronizer (45KB) - 锁相关                                ┃   ┃
┃  ┃    └── Safepoint (8KB)                                             ┃   ┃
┃  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛   ┃
┃                                                                            ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

---

## 五、压缩指针详解

### 5.1 Compressed Oops (压缩对象指针)

**日志验证**：
```
[gc,heap,coops] Heap address: 0x0000000600000000, size: 8192 MB, 
                Compressed Oops mode: Zero based, Oop shift amount: 3
```

| 属性 | 值 | 说明 |
|------|-----|------|
| 模式 | Zero based | 最优模式 |
| base | 0 | 无需加基址 |
| shift | 3 | 左移3位 (×8) |
| 解码公式 | `addr = narrow_oop << 3` | 高效 |

### 5.2 Compressed Class Pointers (压缩类指针)

**日志验证**：
```
[gc,metaspace] Narrow klass base: 0x0000000800000000, Narrow klass shift: 0
[gc,metaspace] Compressed class space size: 1073741824 Address: 0x0000000800000000
```

| 属性 | 值 | 说明 |
|------|-----|------|
| base | `0x800000000` | 32GB位置，紧接堆后 |
| shift | 0 | 无需位移 |
| 解码公式 | `addr = narrow_klass + 0x800000000` | |

---

## 六、JVM启动参数与日志命令

### 6.1 获取完整内存信息的命令

```bash
# 获取NMT汇总信息
java -XX:NativeMemoryTracking=summary \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:+PrintNMTStatistics \
     YourMainClass

# 获取详细GC/内存日志
java -Xlog:gc*=trace \
     -Xlog:gc+heap*=trace \
     -Xlog:gc+metaspace*=trace \
     -Xlog:gc+bot*=trace \
     -Xlog:gc+barrier*=trace \
     -Xlog:os=trace \
     -Xlog:os+thread=trace \
     YourMainClass

# 获取CodeCache信息
java -XX:+PrintCodeCacheOnCompilation YourMainClass
```

### 6.2 本文档使用的测试命令

```bash
java -Xms8g -Xmx8g \
     -XX:+UseG1GC \
     -XX:-UseLargePages \
     -XX:NativeMemoryTracking=summary \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:+PrintNMTStatistics \
     -Xlog:gc+heap*=trace,gc+metaspace*=trace,gc+bot*=trace,gc+barrier*=trace \
     HelloWorld
```

---

## 七、关键结论

### 7.1 真实内存远比"八股文"复杂

| 八股文概念 | 真实实现 | 内存占用 |
|-----------|----------|----------|
| 堆 | Java Heap (G1: 2048 Regions) | 8GB |
| 方法区 | Metaspace (Class + Non-class) | ~1GB reserved |
| 虚拟机栈 | Thread Stack (1MB/线程) | ~22MB |
| 本地方法栈 | 与虚拟机栈合并 | - |
| PC寄存器 | 每线程一个寄存器 | 极小 |
| **未提及** | GC数据结构 | ~374MB |
| **未提及** | CodeCache | ~245MB |
| **未提及** | Symbol/Arena/Compiler等 | ~15MB |

### 7.2 8GB堆的实际内存需求

```
Java Heap:        8,589,934,592 (8.0GB)
GC Structures:      392,628,487 (374MB)
CodeCache:          254,935,735 (243MB)
Metaspace:        1,082,380,651 (1GB reserved, ~7MB committed)
Thread Stacks:       23,177,120 (22MB)
其他:                15,000,000 (~15MB)
────────────────────────────────────────
总计 Reserved:     10,359,276,889 (~9.65GB)
总计 Committed:     9,017,218,393 (~8.4GB)
```

### 7.3 为什么 8GB 堆需要 ~9.65GB 虚拟内存？

1. **GC开销 (374MB)**: 卡表、BOT、标记位图等
2. **CodeCache (243MB)**: JIT编译代码缓存
3. **Metaspace (1GB reserved)**: 类元数据
4. **Thread Stacks (22MB)**: 线程栈
5. **其他开销 (15MB)**: 符号表、编译器等

---

## 八、日志文件说明

本分析基于以下日志文件：

| 文件 | 内容 |
|------|------|
| `gc_trace.log` | G1 GC完整trace日志 |
| `heap_trace.log` | 堆和类加载器信息 |
| `os_trace.log` | 操作系统和线程信息 |
| `codecache_trace.log` | CodeCache编译信息 |

所有日志位于 `/data/workspace/openjdk11-core/md/jvm_memory_real_md/` 目录。

---

*文档生成时间: 2026-01-15*
*OpenJDK版本: 11.0.17-internal*
*测试环境: Linux x86-64, AMD EPYC 7K62, 16核, 32GB RAM*
