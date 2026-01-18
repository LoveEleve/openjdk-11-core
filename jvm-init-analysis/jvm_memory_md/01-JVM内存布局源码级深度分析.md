# JVM内存布局源码级深度分析

> **环境**: -Xms8g -Xmx8g, 非大页(UseLargePages=false), 非NUMA, 64位Linux, OpenJDK 11
> **验证方式**: 源码阅读 + NMT + GDB调试 + JVM Flag验证

## 一、JVM内存总体布局

### 1.1 NMT验证的内存分布

通过`-XX:NativeMemoryTracking=detail -XX:+PrintNMTStatistics`获取的精确数据：

```
Native Memory Tracking:

Total: reserved=10,359,922,424, committed=9,017,601,784

-                 Java Heap (reserved=8,589,934,592, committed=8,589,934,592)
                            (mmap: reserved=8,589,934,592, committed=8,589,934,592)

-                     Class (reserved=1,082,366,915, committed=6,921,155)
                            (classes #876)
                            (  Metadata:   )
                            (    reserved=8,388,608, committed=6,029,312)
                            (  Class space:)
                            (    reserved=1,073,741,824, committed=655,360)

-                    Thread (reserved=23,177,120, committed=1,144,736)
                            (thread #22)
                            (stack: reserved=23,068,672, committed=1,036,288)

-                      Code (reserved=254,852,778, committed=10,010,282)
                            (malloc=1,224,362 #34979)
                            (mmap: reserved=253,628,416, committed=8,785,920)

-                        GC (reserved=392,627,695, committed=392,627,695)
                            (malloc=39,499,247 #14804)
                            (mmap: reserved=353,128,448, committed=353,128,448)
```

### 1.2 内存区域占比分析

| 内存区域 | Reserved | Committed | 占比 |
|---------|----------|-----------|------|
| Java Heap | 8GB (8,589,934,592) | 8GB | 82.9% |
| Class Space | ~1GB (1,082,366,915) | ~6.6MB | 10.4% |
| GC辅助结构 | ~374MB (392,627,695) | ~374MB | 3.8% |
| Code Cache | ~243MB (254,852,778) | ~9.5MB | 2.5% |
| Thread | ~22MB (23,177,120) | ~1.1MB | 0.2% |
| 其他 | ~17MB | ~17MB | 0.2% |
| **总计** | **~9.9GB** | **~8.6GB** | **100%** |

---

## 二、堆内存(Java Heap)详解

### 2.1 堆预留源码分析

**关键源码**: `src/hotspot/share/gc/g1/g1CollectedHeap.cpp:1566-1640`

```cpp
jint G1CollectedHeap::initialize() {
  // 获取堆大小参数
  size_t init_byte_size = collector_policy()->initial_heap_byte_size(); // -Xms (8GB)
  size_t max_byte_size = collector_policy()->max_heap_byte_size();      // -Xmx (8GB)
  size_t heap_alignment = collector_policy()->heap_alignment();         // 堆对齐大小
  
  // 预留虚拟内存 - 调用mmap(PROT_NONE)
  ReservedSpace heap_rs = Universe::reserve_heap(max_byte_size, heap_alignment);
  
  // 保存堆空间信息
  initialize_reserved_region((HeapWord*)heap_rs.base(), 
                            (HeapWord*)(heap_rs.base() + heap_rs.size()));
}
```

### 2.2 压缩指针堆分配策略

**关键源码**: `src/hotspot/share/memory/virtualspace.cpp:540-799`

```cpp
void ReservedHeapSpace::initialize_compressed_heap(const size_t size, size_t alignment, bool large) {
    // 检查堆大小是否支持压缩指针 (OopEncodingHeapMax = 32GB)
    guarantee(size + noaccess_prefix_size(alignment) <= OopEncodingHeapMax,
              "can not allocate compressed oop heap for this size");
    
    // 计算堆基址的最小地址 (HeapBaseMinAddress = 2GB)
    char *aligned_heap_base_min_address = (char *) align_up((void *) HeapBaseMinAddress, alignment);
    
    // 尝试不同的压缩指针模式...
}
```

### 2.3 压缩指针模式选择

源码定义了三种压缩指针模式：

| 模式 | 条件 | 解码方式 | 性能 |
|------|------|----------|------|
| **Unscaled** | 堆 ≤ 4GB | 直接截断64位地址取低32位 | 最快(无计算) |
| **ZeroBased** | 堆 ≤ 32GB | 32位 << 3 | 较快(一次位移) |
| **HeapBased** | 堆 > 32GB | (32位 << 3) + base | 较慢(加法+位移) |

**8GB堆验证**：
```
[0.008s][debug][gc,heap] Minimum heap 8589934592  Initial heap 8589934592  Maximum heap 8589934592
UseCompressedOops = true (ergonomic)
```

8GB堆使用**ZeroBased模式**，因为 2GB < 8GB ≤ 32GB。

### 2.4 G1堆Region布局

**关键源码**: `src/hotspot/share/gc/g1/heapRegion.cpp:63-103`

```cpp
void HeapRegion::setup_heap_region_size(size_t initial_heap_size, size_t max_heap_size) {
  size_t region_size = G1HeapRegionSize;
  if (FLAG_IS_DEFAULT(G1HeapRegionSize)) {
    // 计算: region_size = max(average_heap_size / 2048, 1MB)
    size_t average_heap_size = (initial_heap_size + max_heap_size) / 2;
    region_size = MAX2(average_heap_size / HeapRegionBounds::target_number(),
                       HeapRegionBounds::min_size());
  }
  
  // 向下取整到2的幂次
  int region_size_log = log2_long((jlong) region_size);
  region_size = ((size_t)1 << region_size_log);
  
  // 边界检查: [1MB, 32MB]
  if (region_size < HeapRegionBounds::min_size()) {
    region_size = HeapRegionBounds::min_size();
  } else if (region_size > HeapRegionBounds::max_size()) {
    region_size = HeapRegionBounds::max_size();
  }
  
  // 设置全局变量
  GrainBytes = region_size;  // 4MB for 8GB heap
  GrainWords = GrainBytes >> LogHeapWordSize;  // 524,288
  CardsPerRegion = GrainBytes >> G1CardTable::card_shift;  // 8,192
}
```

**8GB堆精确计算**：
```
average_heap_size = 8GB
region_size_raw = max(8GB / 2048, 1MB) = 4MB
region_size_log = log2(4MB) = 22
region_size = 2^22 = 4,194,304 bytes = 4MB

最终结果:
  GrainBytes = 4,194,304 (4MB)
  GrainWords = 524,288
  LogOfHRGrainBytes = 22
  CardsPerRegion = 8,192
  Region数量 = 8GB / 4MB = 2,048
```

**JVM Flag验证**：
```
[0.008s][info][gc,heap] Heap region size: 4M
G1HeapRegionSize = 4194304 {product} {ergonomic}
```

---

## 三、GC辅助数据结构详解

### 3.1 CardTable (卡表)

**作用**: 跟踪跨代/跨Region引用，支持增量GC

**关键源码**: `src/hotspot/share/gc/shared/cardTable.hpp:232-237`

```cpp
enum SomePublicConstants {
  card_shift                  = 9,  // 地址右移9位得到卡索引
  card_size                   = 1 << card_shift,  // 512 bytes
  card_size_in_words          = card_size / sizeof(HeapWord)  // 64 words
};
```

**精确计算**：
```
卡表大小 = 堆大小 / card_size = 8GB / 512 = 16,777,216 bytes = 16MB
每Region卡数 = Region大小 / 512 = 4MB / 512 = 8,192 cards
```

### 3.2 Block Offset Table (BOT)

**作用**: 快速定位任意地址对应的对象起始位置

**关键源码**: `src/hotspot/share/gc/shared/blockOffsetTable.hpp:50-76`

```cpp
class BOTConstants : public AllStatic {
public:
  static const uint LogN = 9;                        // 512字节
  static const uint N_bytes = 1 << LogN;             // 512 bytes
  static const uint N_words = 1 << (LogN - LogHeapWordSize);  // 64 words
  
  // 跳跃编码常量
  static const uint LogBase = 4;
  static const uint Base = (1 << LogBase);           // 16
  static const uint N_powers = 14;                   // 最大跳跃层数
};
```

**精确计算**：
```
BOT大小 = 堆大小 / N_bytes = 8GB / 512 = 16,777,216 bytes = 16MB
每Region BOT条目数 = 4MB / 512 = 8,192 entries
```

### 3.3 并发标记位图 (CMBitMap)

**作用**: 标记对象存活状态，支持并发标记

**关键源码**: `src/hotspot/share/gc/g1/g1ConcurrentMarkBitMap.cpp:43`

```cpp
G1CMBitMap::G1CMBitMap() : MarkBitMap(), _listener() {
  // mark_distance = 64 bytes
  // _shifter = LogMinObjAlignment = 0
}
```

**精确计算 (已修正)**：
```cpp
// 源码: g1ConcurrentMarkBitMap.cpp:38-44
size_t G1CMBitMap::compute_size(size_t heap_size) {
  return ReservedSpace::allocation_align_size_up(heap_size / mark_distance());
}
size_t G1CMBitMap::mark_distance() {
  return MinObjAlignmentInBytes * BitsPerByte;  // 8 * 8 = 64
}
```

```
mark_distance = 8 × 8 = 64 bytes
单个位图大小 = 8GB / 64 = 134,217,728 bytes = 128MB ★修正
两个位图(prev+next) = 128MB × 2 = 256MB ★修正
```

**注意**: compute_size返回的是字节数，每64字节堆对应1字节位图(8位)，而非1位。

### 3.4 辅助数据结构汇总 (已修正)

| 数据结构 | 大小 | 粒度 | 作用 |
|---------|------|------|------|
| CardTable | 16MB | 512B→1B | 跨Region引用跟踪 |
| BOT | 16MB | 512B→1B | 对象起始地址定位 |
| **Prev Bitmap** | **128MB** | **64B→1B** | 上轮标记结果 ★修正 |
| **Next Bitmap** | **128MB** | **64B→1B** | 当前标记结果 ★修正 |
| Card Counts | 16MB | 512B→1B | 热卡缓存优化 |
| **总计** | **304MB** | - | - ★修正 |

---

## 四、非堆内存详解

### 4.1 Metaspace (元空间)

**关键源码**: `src/hotspot/share/memory/metaspace.cpp`

```cpp
void Metaspace::global_initialize() {
  // Metaspace分为两部分:
  // 1. Class Space (压缩类指针空间) - 固定预留1GB
  // 2. Non-Class Space (元数据空间) - 动态增长
}
```

**NMT验证**：
```
Class (reserved=1,082,366,915, committed=6,921,155)
      (  Metadata:   reserved=8,388,608, committed=6,029,312)
      (  Class space: reserved=1,073,741,824, committed=655,360)
```

### 4.2 Code Cache (代码缓存)

**作用**: 存储JIT编译后的本地代码

**关键源码**: `src/hotspot/share/code/codeCache.cpp:258-304`

```cpp
void CodeCache::initialize_heaps() {
  // JDK9+分段式CodeCache:
  // 1. non_nmethods: 存放VM内部生成的代码
  // 2. profiled_nmethods: 存放带profiling的编译代码
  // 3. non_profiled_nmethods: 存放优化后的编译代码
}
```

**默认参数**：
```
ReservedCodeCacheSize = 251658240 (240MB)
InitialCodeCacheSize = 2555904 (~2.4MB)
```

### 4.3 线程栈

**NMT验证**：
```
Thread (reserved=23,177,120, committed=1,144,736)
       (thread #22)
       (stack: reserved=23,068,672, committed=1,036,288)
```

**计算**：
```
每线程栈大小 ≈ 1MB (ThreadStackSize默认值)
22个线程 × 1MB ≈ 22MB reserved
实际committed仅约1MB (按需分配)
```

---

## 五、内存分配两阶段机制

### 5.1 Reserve阶段 (预留)

**系统调用**: `mmap(addr, size, PROT_NONE, MAP_PRIVATE|MAP_ANONYMOUS|MAP_NORESERVE, -1, 0)`

```cpp
// src/hotspot/os/linux/os_linux.cpp
char* os::reserve_memory(size_t bytes, char* requested_addr, size_t alignment_hint) {
  return anon_mmap(requested_addr, bytes, false);
}

static char* anon_mmap(char* requested_addr, size_t bytes, bool exec) {
  int prot = exec ? PROT_READ|PROT_WRITE|PROT_EXEC : PROT_NONE;  // 预留时PROT_NONE
  int flags = MAP_PRIVATE | MAP_NORESERVE | MAP_ANONYMOUS;
  return (char*)::mmap(requested_addr, bytes, prot, flags, -1, 0);
}
```

**特点**：
- 不分配物理内存
- 不消耗RSS (Resident Set Size)
- 仅占用虚拟地址空间
- PROT_NONE表示不可读写执行

### 5.2 Commit阶段 (提交)

**系统调用**: `mmap(addr, size, PROT_READ|PROT_WRITE, MAP_FIXED|MAP_PRIVATE|MAP_ANONYMOUS, -1, 0)`

```cpp
// src/hotspot/os/linux/os_linux.cpp
bool os::commit_memory(char* addr, size_t size, bool exec) {
  int prot = exec ? PROT_READ|PROT_WRITE|PROT_EXEC : PROT_READ|PROT_WRITE;
  uintptr_t res = (uintptr_t)::mmap(addr, size, prot, 
                                     MAP_PRIVATE|MAP_FIXED|MAP_ANONYMOUS, -1, 0);
  return res != (uintptr_t)MAP_FAILED;
}
```

**特点**：
- 设置页面可读写
- 访问时触发Page Fault分配物理页
- MAP_FIXED确保在指定地址分配

---

## 六、G1堆初始化完整流程

### 6.1 初始化顺序图

```
G1CollectedHeap::initialize()
    │
    ├── 1. 获取堆参数 (init_byte_size, max_byte_size, heap_alignment)
    │
    ├── 2. Universe::reserve_heap() ─→ mmap(PROT_NONE) 预留8GB虚拟地址
    │
    ├── 3. initialize_reserved_region() ─→ 保存堆边界信息
    │
    ├── 4. new G1CardTable() ─→ 创建卡表(16MB)
    │
    ├── 5. new G1BarrierSet() ─→ 创建写屏障
    │
    ├── 6. new G1HotCardCache() ─→ 创建热卡缓存
    │
    ├── 7. 创建6个G1RegionToSpaceMapper:
    │       ├── heap_storage (堆映射器)
    │       ├── bot_storage (BOT映射器, 16MB)
    │       ├── cardtable_storage (卡表映射器, 16MB)
    │       ├── card_counts_storage (卡计数映射器, 16MB)
    │       ├── prev_bitmap_storage (前位图映射器, 16MB)
    │       └── next_bitmap_storage (后位图映射器, 16MB)
    │
    ├── 8. HeapRegionManager::initialize() ─→ 初始化Region管理器
    │
    ├── 9. new G1BlockOffsetTable() ─→ 创建BOT
    │
    ├── 10. new G1RemSet() ─→ 创建记忆集
    │
    ├── 11. new G1ConcurrentMark() ─→ 创建并发标记器
    │
    ├── 12. expand(init_byte_size) ─→ 提交初始堆内存(mmap PROT_READ|PROT_WRITE)
    │
    └── 13. 初始化分配器、策略、线程等
```

### 6.2 核心数据结构关系

```
G1CollectedHeap
    ├── _card_table (G1CardTable*) ────────────────┐
    ├── _g1_rem_set (G1RemSet*) ───────────────────┤
    ├── _hot_card_cache (G1HotCardCache*) ─────────┤
    ├── _hrm (HeapRegionManager) ──────────────────┤
    │       ├── _regions (G1HeapRegionTable) ──────┤─→ HeapRegion* array[2048]
    │       └── _available_map (CHeapBitMap) ──────┘
    ├── _bot (G1BlockOffsetTable*)
    ├── _cm (G1ConcurrentMark*)
    │       ├── _prev_mark_bitmap (G1CMBitMap*)
    │       └── _next_mark_bitmap (G1CMBitMap*)
    └── _allocator (G1Allocator*)
```

---

## 七、精确参数验证总表

### 7.1 堆参数

| 参数 | 值 | 源码位置 |
|------|-----|---------|
| 堆大小 | 8,589,934,592 bytes (8GB) | -Xms/-Xmx |
| Region大小 | 4,194,304 bytes (4MB) | heapRegion.cpp:97 |
| Region数量 | 2,048 | 8GB / 4MB |
| LogOfHRGrainBytes | 22 | heapRegion.cpp:89 |
| CardsPerRegion | 8,192 | heapRegion.cpp:105 |
| Humongous阈值 | 2,097,152 bytes (2MB) | Region / 2 |

### 7.2 辅助结构参数

| 结构 | 大小 | 粒度 | 总条目数 |
|------|------|------|---------|
| CardTable | 16MB | 512B | 16,777,216 |
| BOT | 16MB | 512B | 16,777,216 |
| CMBitMap | 16MB×2 | 64B | 134,217,728 bits |
| CardCounts | 16MB | 512B | 16,777,216 |

### 7.3 压缩指针参数

| 参数 | 值 | 说明 |
|------|-----|------|
| UseCompressedOops | true | 启用压缩对象指针 |
| UseCompressedClassPointers | true | 启用压缩类指针 |
| ObjectAlignmentInBytes | 8 | 对象8字节对齐 |
| HeapBaseMinAddress | 2GB | 堆最小起始地址 |
| CompressedClassSpaceSize | 1GB | 压缩类空间大小 |

---

## 八、内存开销统计 (已修正)

### 8.1 固定开销 (Fixed Overhead)

```
CardTable:        16,777,216 bytes ( 16MB)
BOT:              16,777,216 bytes ( 16MB)
Prev Bitmap:     134,217,728 bytes (128MB) ★修正
Next Bitmap:     134,217,728 bytes (128MB) ★修正
Card Counts:      16,777,216 bytes ( 16MB)
────────────────────────────────────────────
固定总计:        318,767,104 bytes (304MB) ★修正
占堆比例:        3.71% ★修正
```

### 8.2 可变开销 (Variable Overhead)

```
RemSet:         ~6MB (取决于跨Region引用数量)
HeapRegion对象: ~800KB (2048 × 400 bytes)
管理结构:       ~200KB
────────────────────────────────────────
可变总计:       ~7MB
占堆比例:       ~0.09%
```

### 8.3 总开销

```
固定开销:  304MB (3.71%) ★修正
可变开销:  ~7MB (0.09%)
───────────────────────
总开销:    ~311MB (3.80%) ★修正
实际可用:  ~7,689MB (96.20%) ★修正
```

---

## 九、验证命令参考

### 9.1 查看内存布局

```bash
java -Xms8g -Xmx8g -XX:+UseG1GC \
     -XX:NativeMemoryTracking=detail \
     -XX:+PrintNMTStatistics \
     -version
```

### 9.2 查看Flag值

```bash
java -Xms8g -Xmx8g -XX:+UseG1GC \
     -XX:+PrintFlagsFinal \
     -version 2>&1 | grep -E "HeapRegion|Compressed|ObjectAlignment"
```

### 9.3 查看GC初始化日志

```bash
java -Xms8g -Xmx8g -XX:+UseG1GC \
     -Xlog:gc+heap=debug,gc+init=debug \
     -version
```

---

## 十、源码文件索引

| 功能 | 源码文件 |
|------|---------|
| G1堆初始化 | g1CollectedHeap.cpp:1566-1975 |
| Region大小计算 | heapRegion.cpp:63-109 |
| 压缩指针堆分配 | virtualspace.cpp:540-799 |
| 卡表定义 | cardTable.hpp:232-237 |
| BOT定义 | blockOffsetTable.hpp:50-76 |
| 位图定义 | g1ConcurrentMarkBitMap.cpp:43 |
| 内存预留 | os_linux.cpp:reserve_memory() |
| 内存提交 | os_linux.cpp:commit_memory() |
