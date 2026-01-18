# JVM 真实内存布局深度分析（已验证）

> **验证环境**: OpenJDK 11.0.17 slowdebug, G1GC, Linux x86_64
> **验证时间**: 2026-01-15
> **堆配置**: -Xms8g -Xmx8g

本文档通过实际运行JVM并解析其输出日志，展示JVM**真实**的内存布局，而非传统"八股文"的简化描述。

---

## 一、传统"八股文" vs 真实内存结构对比

### 1.1 传统八股文描述

```
┌─────────────────────────────────────┐
│             JVM 内存结构              │
├─────────────────────────────────────┤
│  堆 (Heap)                           │
│  ├─ 新生代 (Eden + S0 + S1)          │
│  └─ 老年代                           │
├─────────────────────────────────────┤
│  方法区 / 元空间 (Metaspace)          │
├─────────────────────────────────────┤
│  虚拟机栈 (VM Stack)                  │
├─────────────────────────────────────┤
│  本地方法栈 (Native Method Stack)     │
├─────────────────────────────────────┤
│  程序计数器 (PC Register)             │
└─────────────────────────────────────┘
```

### 1.2 真实内存结构（NMT统计验证）

通过 `-XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics` 获取：

```
Native Memory Tracking:
Total: reserved=10,360,132,708 bytes (~9.65GB), committed=9,018,074,212 bytes (~8.4GB)

┌──────────────────────────────────────────────────────────────────────────────┐
│ 内存区域              │  Reserved (保留)   │  Committed (提交)  │ 说明        │
├──────────────────────────────────────────────────────────────────────────────┤
│ Java Heap            │ 8,589,934,592 (8GB) │ 8,589,934,592 (8GB)│ 用户堆       │
│ Class (Metaspace)    │ 1,082,380,587 (~1GB)│ 7,196,971 (~7MB)   │ 类元数据     │
│ Thread               │ 23,177,120 (~22MB)  │ 1,144,736 (~1.1MB) │ 线程栈       │
│ Code                 │ 254,930,300 (~243MB)│ 10,087,804 (~9.6MB)│ JIT代码缓存  │
│ GC                   │ 392,629,063 (~374MB)│ 392,629,063 (~374MB)│ GC数据结构  │
│ Compiler             │ 2,549,106 (~2.4MB)  │ 2,549,106 (~2.4MB) │ 编译器       │
│ Internal             │ 604,601 (~590KB)    │ 604,601 (~590KB)   │ 内部结构     │
│ Symbol               │ 2,626,280 (~2.5MB)  │ 2,626,280 (~2.5MB) │ 符号表       │
│ NMT                  │ 1,262,720 (~1.2MB)  │ 1,262,720 (~1.2MB) │ NMT追踪开销  │
│ Arena Chunk          │ 9,879,800 (~9.4MB)  │ 9,879,800 (~9.4MB) │ Arena分配    │
│ Module               │ 77,344 (~75KB)      │ 77,344 (~75KB)     │ 模块系统     │
│ Safepoint            │ 8,192 (8KB)         │ 8,192 (8KB)        │ 安全点       │
│ Synchronizer         │ 45,968 (~45KB)      │ 45,968 (~45KB)     │ 同步器       │
│ Arguments            │ 20,639 (~20KB)      │ 20,639 (~20KB)     │ 启动参数     │
│ Logging              │ 6,276 (~6KB)        │ 6,276 (~6KB)       │ 日志         │
│ Tracing              │ 120 bytes           │ 120 bytes          │ 跟踪         │
└──────────────────────────────────────────────────────────────────────────────┘
```

**关键发现**: 8GB堆的JVM实际需要约 **9.65GB** 的虚拟地址空间保留！

---

## 二、64位虚拟地址空间布局（日志验证）

### 2.1 完整地址布局图

```
虚拟地址 (64-bit)                                          大小
    │
    ▼
0x0000000600000000 (24GB) ─┬─────────────────────────────  Java Heap 起始
                           │
                           │   G1 Heap: 2048个 Region
                           │   每个 Region: 4MB
                           │   总大小: 8GB
                           │
                           │   第一个Region: 0x0000000600000000 ~ 0x0000000600400000
                           │   ...
                           │   最后Region: 0x00000007ffc00000 ~ 0x0000000800000000
                           │
0x0000000800000000 (32GB) ─┴─────────────────────────────  Java Heap 结束
                           │
                           ├─────────────────────────────  Compressed Class Space 起始
                           │   大小: 1GB (1,073,741,824 bytes)
                           │   地址: 0x0000000800000000 ~ 0x0000000840000000
                           │
0x0000000840000000 (33GB) ─┴─────────────────────────────  Compressed Class Space 结束

... (高地址区域 - 非压缩指针可访问) ...

0x00007f7445000000        ─┬─────────────────────────────  G1CardTable _byte_map_base
                           │
0x00007f7448000000        ─┼─────────────────────────────  G1CardTable 起始
                           │   大小: 16MB (16,777,216 bytes)
                           │   覆盖: 8GB Heap (1 byte = 512 bytes)
0x00007f7448ffffff        ─┴─────────────────────────────  G1CardTable 结束

0x00007f745cd08000        ─┬─────────────────────────────  G1BlockOffsetTable 起始
                           │   大小: 16MB (16,777,216 bytes)
                           │   覆盖: 8GB Heap (1 byte = 512 bytes)
0x00007f745dd08000        ─┴─────────────────────────────  G1BlockOffsetTable 结束

0x00007fd5bd000000        ─┬─────────────────────────────  CodeHeap 'non-nmethods' 起始
                           │   大小: ~7.4MB
0x00007fd5bd73f000        ─┼─────────────────────────────  CodeHeap 'profiled nmethods' 起始
                           │   大小: ~119MB
0x00007fd5c4b9f000        ─┼─────────────────────────────  CodeHeap 'non-profiled nmethods' 起始
                           │   大小: ~119MB
0x00007fd5cc000000        ─┴─────────────────────────────  CodeCache 结束

0x00007f7e352f9000        ─┬─────────────────────────────  Non-class Metaspace 起始
                           │   Reserved: 8MB
                           │   用于方法、常量池等
0x00007f7e35af9000        ─┴─────────────────────────────  Non-class Metaspace 结束
```

### 2.2 日志验证数据

#### Heap 地址验证
```log
[0.063s][info][gc,heap,coops] Heap address: 0x0000000600000000, size: 8192 MB, 
                              Compressed Oops mode: Zero based, Oop shift amount: 3
[0.008s][info][gc,heap] Heap region size: 4M
```

#### Region 数量验证
```
Region 总数: 2048 个 (通过日志行数统计)
第一个 Region: [0x0000000600000000, 0x0000000600400000)
最后一个 Region: [0x00000007ffc00000, 0x0000000800000000)

验证计算:
- 8GB / 4MB = 2048 ✓
- 0x800000000 - 0x600000000 = 0x200000000 = 8GB ✓
```

#### G1CardTable 验证
```log
[0.015s][trace][gc,barrier] G1CardTable::G1CardTable:
[0.015s][trace][gc,barrier]     &_byte_map[0]: 0x00007f7448000000  &_byte_map[_last_valid_index]: 0x00007f7448ffffff
[0.015s][trace][gc,barrier]     _byte_map_base: 0x00007f7445000000

验证计算:
- CardTable大小 = 8GB / 512 = 16,777,216 bytes (16MB) ✓
- 0x7f7448ffffff - 0x7f7448000000 = 0xffffff = 16MB - 1 ✓
```

#### G1BlockOffsetTable 验证
```log
[0.016s][trace][gc,bot] G1BlockOffsetTable::G1BlockOffsetTable:
[0.016s][trace][gc,bot]     rs.base(): 0x00007f745cd08000  rs.size(): 16777216  rs end(): 0x00007f745dd08000

验证计算:
- BOT大小 = 8GB / 512 = 16,777,216 bytes (16MB) ✓
- 0x7f745dd08000 - 0x7f745cd08000 = 0x1000000 = 16MB ✓
```

#### Compressed Class Space 验证
```log
[0.064s][trace][gc,metaspace] node @0x00007f7e64c8b960: reserved=1048576.00 KB, committed=0.00 KB
[0.064s][trace][gc,metaspace]    [0x0000000800000000, 0x0000000800000000, 0x0000000800000000, 0x0000000840000000)
[0.064s][trace][gc,metaspace] Narrow klass base: 0x0000000800000000, Narrow klass shift: 0
[0.064s][trace][gc,metaspace] Compressed class space size: 1073741824 Address: 0x0000000800000000

验证计算:
- Class Space大小 = 0x40000000 = 1GB ✓
- 位置紧跟Heap结束地址 (0x800000000) ✓
```

---

## 三、G1 Heap 详细结构

### 3.1 Region 布局

```
G1 Heap (8GB = 2048 Regions × 4MB)
┌─────────────────────────────────────────────────────────────────┐
│ Region #0    │ Region #1    │ Region #2    │ ... │ Region #2047 │
│ 0x600000000  │ 0x600400000  │ 0x600800000  │     │ 0x7ffc00000  │
│   ~ +4MB     │   ~ +4MB     │   ~ +4MB     │     │ ~ 0x800000000│
└─────────────────────────────────────────────────────────────────┘

每个 Region 可以是:
- FREE: 空闲
- EDEN: 新生代Eden区
- SURV: Survivor区  
- OLD:  老年代
- HUMONGOUS: 大对象 (>= Region大小的50%)
```

### 3.2 日志中的 Region 状态
```log
G1HR COMMIT(FREE) [0x0000000600000000, 0x0000000600000000, 0x0000000600400000]
                   ^-- bottom            ^-- top              ^-- end

说明: [bottom, top) 为已使用空间, [top, end) 为可用空间
```

---

## 四、GC 辅助数据结构详解

### 4.1 NMT 报告的 GC 内存分布

```
GC (reserved=392,629,063, committed=392,629,063) ~374MB

细分 (从详细NMT输出):
├── 134,217,728 (128MB) - Prev/Next Bitmap (两个，各128MB)
├── 134,217,728 (128MB) - 第二个 Bitmap
├── 33,554,432 (32MB)   - Marking相关
├── 16,777,216 (16MB)   - G1CardTable
├── 16,777,216 (16MB)   - G1BlockOffsetTable  
├── 16,777,216 (16MB)   - 其他G1结构
└── 806,912 (~788KB)    - G1FromCardCache
```

### 4.2 各结构作用

| 结构 | 大小 | 计算公式 | 作用 |
|------|------|----------|------|
| **G1CardTable** | 16MB | Heap/512 | 跨Region引用追踪，脏卡标记 |
| **G1BlockOffsetTable** | 16MB | Heap/512 | 快速定位对象起始位置 |
| **Prev Bitmap** | 128MB | Heap/64 | 上一次标记结果 (1bit/8字节) |
| **Next Bitmap** | 128MB | Heap/64 | 当前标记结果 |
| **G1FromCardCache** | ~788KB | Regions×线程数 | 优化RSet更新 |

### 4.3 Bitmap 大小验证
```
Bitmap 大小计算:
- 8GB Heap / 8 bytes per bit / 8 bits per byte = 128MB
- 即每个对象（最小8字节对齐）用1bit标记
- 两个Bitmap共256MB
```

---

## 五、CodeCache 三段式结构

### 5.1 日志验证
```log
CodeHeap 'non-nmethods': size=7420Kb used=3433Kb max_used=3461Kb free=3986Kb
 bounds [0x00007fd5bd000000, 0x00007fd5bd370000, 0x00007fd5bd73f000]
 
CodeHeap 'profiled nmethods': size=119168Kb used=891Kb max_used=891Kb free=118276Kb
 bounds [0x00007fd5bd73f000, 0x00007fd5bd9af000, 0x00007fd5c4b9f000]
 
CodeHeap 'non-profiled nmethods': size=119172Kb used=262Kb max_used=262Kb free=118909Kb
 bounds [0x00007fd5c4b9f000, 0x00007fd5c4e0f000, 0x00007fd5cc000000]

total_blobs=1384 nmethods=509 adapters=794
```

### 5.2 CodeCache 结构图

```
CodeCache (总计 ~245MB reserved)
┌────────────────────────────────────────────────────────────────────────────┐
│ non-nmethods (~7.4MB)   │ profiled nmethods (~119MB) │ non-profiled (~119MB)│
│ ──────────────────────  │ ────────────────────────── │ ──────────────────── │
│ • VM stubs              │ • C1编译的带profile代码     │ • C2编译的优化代码    │
│ • Adapters (794个)       │ • 热点分析中的方法          │ • 完全优化的方法      │
│ • Runtime routines      │ • 可能被重编译             │ • 最终稳定代码        │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 六、Metaspace 详细结构

### 6.1 NMT 报告

```
Class (reserved=1,082,380,587, committed=7,196,971)

├── Non-class space (reserved=8,388,608, committed=6,291,456)
│   地址: [0x00007f7e352f9000, 0x00007f7e35af9000)
│   存储: 方法字节码、常量池、注解等
│
└── Class space (reserved=1,073,741,824, committed=655,360)
    地址: [0x0000000800000000, 0x0000000840000000)
    存储: Klass* 压缩指针指向的类元数据
```

### 6.2 Metaspace 使用统计

```
Metaspace (程序结束时):
Usage:
  Non-class:  6,100,992 bytes capacity, 6,060,080 bytes (>99%) used
      Class:    634,880 bytes capacity,   572,008 bytes ( 90%) used
       Both:  6,735,872 bytes capacity, 6,632,088 bytes ( 98%) used

Virtual space:
  Non-class space:  8,388,608 bytes reserved, 6,291,456 bytes ( 75%) committed
      Class space:  1,073,741,824 bytes reserved,  655,360 bytes ( <1%) committed
```

---

## 七、Thread Stack 布局

### 7.1 NMT 报告

```
Thread (reserved=23,177,120, committed=1,144,736)
       (stack: reserved=23,068,672, committed=1,036,288)

推算:
- 约22个线程 × 1MB/线程 ≈ 22MB reserved
- 实际只提交了约1MB (按需分配)
```

### 7.2 线程栈结构

```
每个 Java 线程:
┌──────────────────────────────────────┐
│ Stack (默认1MB)                      │
│ ├── Guard Pages (保护页)              │
│ ├── Stack Frames (栈帧)              │
│ │   ├── 局部变量表                    │
│ │   ├── 操作数栈                      │
│ │   └── 动态链接                      │
│ └── Thread-Local Data                │
└──────────────────────────────────────┘
```

---

## 八、完整内存布局总结

### 8.1 配置 8GB 堆的实际内存需求

| 区域 | Reserved | Committed | 占比 |
|------|----------|-----------|------|
| Java Heap | 8.00 GB | 8.00 GB | 82.9% |
| Class/Metaspace | 1.01 GB | 6.87 MB | 10.4% |
| GC | 374.51 MB | 374.51 MB | 3.79% |
| Code | 243.15 MB | 9.62 MB | 2.47% |
| Thread | 22.10 MB | 1.09 MB | 0.22% |
| 其他 | ~15 MB | ~15 MB | 0.15% |
| **总计** | **~9.65 GB** | **~8.40 GB** | 100% |

### 8.2 内存比例可视化

```
8GB 堆配置的真实内存分布:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Java Heap (8GB, 82.9%)  ████████████████████████████████████████████████████████████████████████
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Class/Metaspace (~1GB)  ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GC (~374MB)             ███░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Code (~243MB)           ██░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Thread+Other (~37MB)    ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
（█ = committed, ░ = reserved but not committed）
```

---

## 九、验证用的 JVM 参数

```bash
# 获取完整 GC/Heap 日志
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -Xlog:gc+heap*=trace,gc+metaspace*=trace,gc+bot*=trace,gc+barrier*=trace,gc+region*=trace \
     HelloWorld

# 获取 NMT 内存统计
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics \
     HelloWorld

# 获取 CodeCache 信息
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -XX:+PrintCodeCache \
     HelloWorld
```

---

## 十、核心结论

1. **传统八股文只覆盖了约85%的内存**: 漏掉了GC数据结构（~374MB）、CodeCache（~243MB）等关键区域

2. **8GB堆需要约9.65GB虚拟地址空间**: 额外的1.65GB用于GC、Metaspace、CodeCache、线程栈等

3. **G1的额外开销约4.7%**: 对于8GB堆，G1需要约374MB的辅助数据结构
   - CardTable: 16MB
   - BOT: 16MB  
   - 2个Bitmap: 256MB
   - 其他: ~86MB

4. **Compressed Oops/Class Pointers 要求堆在低地址**: Heap起始于0x600000000，Class Space紧随其后在0x800000000

5. **Reserved ≠ Committed**: 系统保留的虚拟地址(9.65GB)远大于实际提交的物理内存(8.4GB)
