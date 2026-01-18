# JVM 真实内存布局深度分析（第三次验证）

> **验证环境**: OpenJDK 11.0.17 slowdebug, G1GC, Linux x86_64
> **验证时间**: 2026-01-15
> **堆配置**: `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages`

---

## 一、传统"八股文" vs 真实内存结构

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

### 1.2 真实内存结构（本次NMT验证）

```
Native Memory Tracking:
Total: reserved=10,360,591,563 (~9.65GB), committed=9,018,533,067 (~8.40GB)

┌──────────────────────────────────────────────────────────────────────────────┐
│ 内存区域              │  Reserved (保留)    │  Committed (提交)  │ 说明        │
├──────────────────────────────────────────────────────────────────────────────┤
│ Java Heap            │ 8,589,934,592 (8GB) │ 8,589,934,592 (8GB)│ 用户对象堆   │
│ Class (Metaspace)    │ 1,082,380,651 (~1GB)│ 7,197,035 (~6.9MB) │ 类元数据     │
│ Thread               │ 23,177,120 (~22MB)  │ 1,144,736 (~1.1MB) │ 线程栈       │
│ Code                 │ 254,936,025 (~243MB)│ 10,093,529 (~9.6MB)│ JIT代码缓存  │
│ GC                   │ 392,629,063 (~374MB)│ 392,629,063 (~374MB)│ GC辅助结构  │
│ Compiler             │ 1,662,234 (~1.6MB)  │ 1,662,234 (~1.6MB) │ 编译器       │
│ Internal             │ 604,603 (~590KB)    │ 604,603 (~590KB)   │ 内部结构     │
│ Symbol               │ 2,626,280 (~2.5MB)  │ 2,626,280 (~2.5MB) │ 符号表       │
│ NMT                  │ 961,736 (~939KB)    │ 961,736 (~939KB)   │ NMT追踪开销  │
│ Arena Chunk          │ 11,520,872 (~11MB)  │ 11,520,872 (~11MB) │ Arena分配    │
│ Module               │ 77,344 (~75KB)      │ 77,344 (~75KB)     │ 模块系统     │
│ Synchronizer         │ 45,816 (~45KB)      │ 45,816 (~45KB)     │ 同步器       │
│ Arguments            │ 20,639 (~20KB)      │ 20,639 (~20KB)     │ 启动参数     │
│ Safepoint            │ 8,192 (8KB)         │ 8,192 (8KB)        │ 安全点       │
│ Logging              │ 6,276 (~6KB)        │ 6,276 (~6KB)       │ 日志         │
│ Tracing              │ 120 bytes           │ 120 bytes          │ 跟踪         │
└──────────────────────────────────────────────────────────────────────────────┘
```

**关键发现**: 8GB堆的JVM实际需要约 **9.65GB** 虚拟地址空间保留，**8.40GB** 物理内存提交！

---

## 二、64位虚拟地址空间布局

### 2.1 完整地址布局图（本次日志验证）

```
虚拟地址 (64-bit)                                           大小
    │
    ▼
0x0000000600000000 (24GB) ─┬──────────────────────────────  Java Heap 起始
                           │
                           │   G1 Heap: 2048个 Region
                           │   每个 Region: 4MB
                           │   总大小: 8GB
                           │
                           │   第一个Region: [0x600000000, 0x600400000)
                           │   ...
                           │   最后Region: [0x7ffc00000, 0x800000000)
                           │
0x0000000800000000 (32GB) ─┴──────────────────────────────  Java Heap 结束
                           │
                           ├──────────────────────────────  Compressed Class Space 起始
                           │   大小: 1GB (1,073,741,824 bytes)
                           │   地址: [0x800000000, 0x840000000)
                           │   Narrow klass base: 0x800000000
                           │   Narrow klass shift: 0
                           │
0x0000000840000000 (33GB) ─┴──────────────────────────────  Compressed Class Space 结束

... (高地址区域) ...

0x00007f4d59908000        ─┬──────────────────────────────  G1CardTable _byte_map_base
                           │
0x00007f4d5c908000        ─┼──────────────────────────────  G1CardTable 起始 (&_byte_map[0])
                           │   大小: 16MB (16,777,216 bytes)
                           │   覆盖: 8GB Heap (1 byte = 512 bytes)
0x00007f4d5d907fff        ─┴──────────────────────────────  G1CardTable 结束

0x00007f4d5d908000        ─┬──────────────────────────────  G1BlockOffsetTable 起始
                           │   大小: 16MB (16,777,216 bytes)
                           │   覆盖: 8GB Heap (1 byte = 512 bytes)
0x00007f4d5e908000        ─┴──────────────────────────────  G1BlockOffsetTable 结束

0x00007f79c9000000        ─┬──────────────────────────────  CodeHeap 'non-nmethods' 起始
                           │   大小: 7,420KB (~7.2MB)
0x00007f79c973f000        ─┼──────────────────────────────  CodeHeap 'profiled nmethods' 起始
                           │   大小: 119,168KB (~116MB)
0x00007f79d0b9f000        ─┼──────────────────────────────  CodeHeap 'non-profiled nmethods' 起始
                           │   大小: 119,172KB (~116MB)
0x00007f79d8000000        ─┴──────────────────────────────  CodeCache 结束

0x00007f0e194fb000        ─┬──────────────────────────────  Non-class Metaspace 起始
                           │   Reserved: 8MB
0x00007f0e19cfb000        ─┴──────────────────────────────  Non-class Metaspace 结束
```

---

## 三、核心数据验证

### 3.1 Heap 地址验证

```log
[0.007s][info][gc,heap] Heap region size: 4M
[0.061s][info][gc,heap,coops] Heap address: 0x0000000600000000, size: 8192 MB, 
                              Compressed Oops mode: Zero based, Oop shift amount: 3
```

| 属性 | 值 | 验证 |
|------|-----|------|
| 起始地址 | 0x600000000 (24GB) | ✓ |
| 结束地址 | 0x800000000 (32GB) | ✓ |
| 大小 | 8,589,934,592 bytes (8GB) | ✓ |
| Region数量 | 2048个 | ✓ (日志行数统计) |
| Region大小 | 4MB | ✓ |
| 压缩指针模式 | Zero based, shift=3 | ✓ |

### 3.2 Region 验证

```log
# 第一个 Region
G1HR COMMIT(FREE) [0x0000000600000000, 0x0000000600000000, 0x0000000600400000]

# 最后三个 Region
G1HR COMMIT(FREE) [0x00000007ff400000, 0x00000007ff400000, 0x00000007ff800000]
G1HR COMMIT(FREE) [0x00000007ff800000, 0x00000007ff800000, 0x00000007ffc00000]
G1HR COMMIT(FREE) [0x00000007ffc00000, 0x00000007ffc00000, 0x0000000800000000]

# 总数统计
Region 总数: 2048 个 ✓
```

**验证计算**:
```
8GB / 4MB = 2048 ✓
0x800000000 - 0x600000000 = 0x200000000 = 8,589,934,592 bytes = 8GB ✓
```

### 3.3 G1CardTable 验证

```log
[0.016s][trace][gc,barrier] G1CardTable::G1CardTable:
[0.016s][trace][gc,barrier]     &_byte_map[0]: 0x00007f4d5c908000
[0.016s][trace][gc,barrier]     &_byte_map[_last_valid_index]: 0x00007f4d5d907fff
[0.016s][trace][gc,barrier]     _byte_map_base: 0x00007f4d59908000
```

**验证计算**:
```
CardTable 大小 = 0x7f4d5d907fff - 0x7f4d5c908000 + 1
              = 0xffffff + 1
              = 0x1000000
              = 16,777,216 bytes (16MB) ✓

理论值: 8GB / 512 = 16,777,216 bytes ✓
```

### 3.4 G1BlockOffsetTable 验证

```log
[0.016s][trace][gc,bot] G1BlockOffsetTable::G1BlockOffsetTable:
[0.016s][trace][gc,bot]     rs.base(): 0x00007f4d5d908000
[0.016s][trace][gc,bot]     rs.size(): 16777216
[0.016s][trace][gc,bot]     rs end(): 0x00007f4d5e908000
```

**验证计算**:
```
BOT 大小 = rs.size() = 16,777,216 bytes (16MB) ✓
0x7f4d5e908000 - 0x7f4d5d908000 = 0x1000000 = 16MB ✓
```

### 3.5 Compressed Class Space 验证

```log
[0.063s][trace][gc,metaspace] node @0x00007f0e48c8b960: reserved=1048576.00 KB
[0.063s][trace][gc,metaspace]    [0x0000000800000000, 0x0000000800000000, 0x0000000800000000, 0x0000000840000000)
[0.063s][trace][gc,metaspace] Narrow klass base: 0x0000000800000000, Narrow klass shift: 0
[0.063s][trace][gc,metaspace] Compressed class space size: 1073741824 Address: 0x0000000800000000
```

**验证**:
```
Class Space 大小 = 0x840000000 - 0x800000000 = 0x40000000 = 1,073,741,824 bytes (1GB) ✓
紧跟 Heap 结束地址 (0x800000000) ✓
```

### 3.6 Non-class Metaspace 验证

```log
[0.063s][trace][gc,metaspace] node @0x00007f0e48c8bda0: reserved=8192.00 KB
[0.063s][trace][gc,metaspace]    [0x00007f0e194fb000, 0x00007f0e194fb000, 0x00007f0e194fb000, 0x00007f0e19cfb000)
```

**验证**:
```
Non-class space reserved = 8192 KB = 8MB ✓
地址范围: 0x7f0e19cfb000 - 0x7f0e194fb000 = 0x800000 = 8MB ✓
```

---

## 四、GC 辅助数据结构详解

### 4.1 GC 内存分布（NMT detail验证）

```
GC (reserved=392,629,063, committed=392,629,063) ~374MB

详细分布:
├── 134,217,728 (128MB) - Prev Bitmap
├── 134,217,728 (128MB) - Next Bitmap  
├── 33,554,432 (32MB)   - Marking相关结构
├── 16,777,216 (16MB)   - G1CardTable
├── 16,777,216 (16MB)   - G1BlockOffsetTable
├── 16,777,216 (16MB)   - 其他G1结构
└── 806,912 (~788KB)    - G1FromCardCache
────────────────────────
总计: 352,906,272 + 其他malloc ≈ 374MB
```

### 4.2 各结构作用和计算公式

| 结构 | 大小 | 计算公式 | 作用 |
|------|------|----------|------|
| **Prev Bitmap** | 128MB | Heap / 64 | 上一次并发标记结果 |
| **Next Bitmap** | 128MB | Heap / 64 | 当前并发标记结果 |
| **G1CardTable** | 16MB | Heap / 512 | 跨Region引用追踪，脏卡标记 |
| **G1BlockOffsetTable** | 16MB | Heap / 512 | 快速定位对象起始位置 |
| **G1FromCardCache** | ~788KB | Regions × 线程数 | 优化RSet更新 |

### 4.3 Bitmap 大小验证

```
Bitmap 计算原理:
- 堆内存中每8字节（对象最小对齐）用1bit标记
- 8GB = 8 × 1024 × 1024 × 1024 bytes
- 需要的bit数 = 8GB / 8 = 1GB bits
- 需要的byte数 = 1GB bits / 8 = 128MB

两个Bitmap (prev + next) = 256MB ✓
```

---

## 五、CodeCache 三段式结构

### 5.1 本次日志验证

```log
CodeHeap 'non-nmethods': size=7420Kb used=3433Kb max_used=3455Kb free=3986Kb
 bounds [0x00007f79c9000000, 0x00007f79c9370000, 0x00007f79c973f000]

CodeHeap 'profiled nmethods': size=119168Kb used=902Kb max_used=902Kb free=118265Kb
 bounds [0x00007f79c973f000, 0x00007f79c99af000, 0x00007f79d0b9f000]

CodeHeap 'non-profiled nmethods': size=119172Kb used=255Kb max_used=255Kb free=118916Kb
 bounds [0x00007f79d0b9f000, 0x00007f79d0e0f000, 0x00007f79d8000000]

total_blobs=1386 nmethods=511 adapters=794
```

### 5.2 CodeCache 结构图

```
CodeCache (总计 ~245MB reserved, ~10MB committed)
┌─────────────────────────────────────────────────────────────────────────────┐
│  non-nmethods (7.2MB)  │  profiled nmethods (116MB) │ non-profiled (116MB)  │
│  ─────────────────────  │  ───────────────────────── │ ───────────────────── │
│  • VM stubs            │  • C1编译的带profile代码    │ • C2编译的优化代码     │
│  • Adapters (794个)     │  • 热点分析中的方法         │ • 完全优化的方法       │
│  • Runtime routines    │  • 可能被重编译            │ • 最终稳定代码         │
│  used: 3,433KB         │  used: 902KB              │ used: 255KB           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 六、Metaspace 详细结构

### 6.1 NMT 报告

```
Class (reserved=1,082,380,651, committed=7,197,035)
      (mmap: reserved=1,082,130,432, committed=6,946,816)

├── Non-class space
│   reserved=8,388,608 (8MB), committed=6,291,456 (6MB)
│   地址: [0x00007f0e194fb000, 0x00007f0e19cfb000)
│   存储: 方法字节码、常量池、注解等
│
└── Class space  
    reserved=1,073,741,824 (1GB), committed=655,360 (640KB)
    地址: [0x0000000800000000, 0x0000000840000000)
    存储: Klass* 压缩指针指向的类元数据
```

### 6.2 Metaspace 使用统计

```
Metaspace (程序结束时):
Usage:
  Non-class:  6,100,992 bytes capacity, 6,035,040 bytes (99%) used
      Class:    634,880 bytes capacity,   572,008 bytes (90%) used
       Both:  6,735,872 bytes capacity, 6,607,048 bytes (98%) used

Virtual space:
  Non-class space:  8,388,608 bytes reserved, 6,291,456 bytes (75%) committed
      Class space:  1,073,741,824 bytes reserved, 655,360 bytes (<1%) committed

CompressedClassSpaceSize: 1,073,741,824 bytes (1GB)
MaxMetaspaceSize: unlimited
CDS: off
```

---

## 七、Thread Stack 布局

### 7.1 NMT 报告

```
Thread (reserved=23,177,120, committed=1,144,736)
       (stack: reserved=23,068,672, committed=1,036,288)

推算:
- 约22个线程 × 1MB/线程 ≈ 22MB reserved
- 实际只提交了约1MB (按需分配，栈空间懒提交)
```

### 7.2 线程栈结构

```
每个 Java/VM 线程:
┌──────────────────────────────────────┐
│ Stack (默认1MB = 1024KB)             │
│ ├── Guard Pages (4KB保护页)           │
│ ├── Stack Frames (栈帧)              │
│ │   ├── 局部变量表                    │
│ │   ├── 操作数栈                      │
│ │   └── 动态链接 + 返回地址            │
│ └── Thread-Local Data                │
└──────────────────────────────────────┘
```

---

## 八、完整内存布局总结

### 8.1 配置 8GB 堆的实际内存需求

| 区域 | Reserved | Committed | 占比(Reserved) |
|------|----------|-----------|----------------|
| Java Heap | 8,589,934,592 (8.00 GB) | 8,589,934,592 (8.00 GB) | 82.90% |
| Class/Metaspace | 1,082,380,651 (1.01 GB) | 7,197,035 (6.87 MB) | 10.44% |
| GC | 392,629,063 (374.51 MB) | 392,629,063 (374.51 MB) | 3.79% |
| Code | 254,936,025 (243.15 MB) | 10,093,529 (9.63 MB) | 2.46% |
| Thread | 23,177,120 (22.10 MB) | 1,144,736 (1.09 MB) | 0.22% |
| Arena Chunk | 11,520,872 (10.99 MB) | 11,520,872 (10.99 MB) | 0.11% |
| 其他 | ~6 MB | ~6 MB | 0.06% |
| **总计** | **~9.65 GB** | **~8.40 GB** | 100% |

### 8.2 内存比例可视化

```
8GB 堆配置的真实内存分布 (Reserved):
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Java Heap (8GB, 82.9%)  █████████████████████████████████████████████████████████████████████
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Class/Metaspace (~1GB)  ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GC (~374MB)             ███░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Code (~243MB)           ██░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Thread+Other (~33MB)    ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
（█ = committed, ░ = reserved but not committed）
```

---

## 九、与前两次验证的数据对比

| 数据项 | 第1次 | 第2次 | 第3次(本次) | 稳定性 |
|--------|-------|-------|-------------|--------|
| Total Reserved | 10,359,276,889 | 10,360,132,708 | 10,360,591,563 | ~0.01% 波动 |
| Total Committed | 9,017,218,393 | 9,018,074,212 | 9,018,533,067 | ~0.01% 波动 |
| Java Heap | 8,589,934,592 | 8,589,934,592 | 8,589,934,592 | **完全一致** |
| Class Reserved | 1,082,380,651 | 1,082,380,587 | 1,082,380,651 | 波动64B |
| Thread | 23,177,120 | 23,177,120 | 23,177,120 | **完全一致** |
| GC | 392,628,487 | 392,629,063 | 392,629,063 | 波动576B |
| Region数量 | 2048 | 2048 | 2048 | **完全一致** |
| CardTable | 16MB | 16MB | 16MB | **完全一致** |
| BOT | 16MB | 16MB | 16MB | **完全一致** |
| Bitmap×2 | 错误(32MB) | 256MB | 256MB | 第1次错误已修正 |

**结论**: 核心架构数据完全稳定，动态分配数据有微小波动（<0.01%），属于正常现象。

---

## 十、验证用的 JVM 参数

```bash
# 获取 NMT 内存统计
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics \
     HelloWorld

# 获取详细 GC/Heap 日志
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -Xlog:gc+heap*=trace,gc+metaspace*=trace,gc+bot*=trace,gc+barrier*=trace,gc+region*=trace \
     HelloWorld

# 获取 GC 内存详细分布
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -XX:NativeMemoryTracking=detail -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics \
     HelloWorld

# 获取 CodeCache 信息
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages \
     -XX:+PrintCodeCache \
     HelloWorld
```

---

## 十一、核心结论

1. **传统八股文只覆盖了约85%的内存**: 漏掉了GC数据结构（~374MB）、CodeCache（~243MB）等关键区域

2. **8GB堆需要约9.65GB虚拟地址空间**: 额外的1.65GB用于：
   - GC辅助结构: 374MB
   - CodeCache: 243MB  
   - Metaspace: 1GB reserved
   - Thread: 22MB
   - 其他: ~16MB

3. **G1的额外开销约4.7%**: 对于8GB堆，G1需要约374MB的辅助数据结构
   - 2个 Bitmap: 256MB (128MB × 2)
   - CardTable: 16MB
   - BOT: 16MB
   - 其他: ~86MB

4. **Compressed Oops/Class Pointers 布局**:
   - Heap: 0x600000000 ~ 0x800000000 (8GB)
   - Class Space: 0x800000000 ~ 0x840000000 (1GB)
   - 连续布局优化压缩指针解码效率

5. **Reserved ≠ Committed**: 
   - Reserved (9.65GB): 虚拟地址空间保留
   - Committed (8.40GB): 实际提交的物理内存

---

*文档生成时间: 2026-01-15*
*OpenJDK版本: 11.0.17-internal (slowdebug)*
*测试环境: Linux x86-64*
