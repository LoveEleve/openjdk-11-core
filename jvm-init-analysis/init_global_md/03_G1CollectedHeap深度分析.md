# G1CollectedHeap深度分析 - 基于GDB真实调试数据

> **调试环境**: OpenJDK 11.0.17-internal (slowdebug build)  
> **测试程序**: HelloWorld.java  
> **JVM参数**: `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages`  
> **堆配置**: 8GB固定大小，禁用大页

## 一、G1CollectedHeap概述

`G1CollectedHeap` 是G1垃圾收集器的核心堆实现类，继承自 `CollectedHeap`。

### 1.1 GDB验证的基本信息

```
============================================================
=== G1CollectedHeap深度分析 - 基于HelloWorld程序 ===
=== JVM参数: -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages ===
============================================================

=== 基本信息 ===
G1CollectedHeap地址: 0x7ffff0032530
sizeof(G1CollectedHeap): 1864 bytes
```

### 1.2 源码位置

```
src/hotspot/share/gc/g1/g1CollectedHeap.hpp  - 类定义
src/hotspot/share/gc/g1/g1CollectedHeap.cpp  - 实现
```

## 二、8GB堆配置验证

### 2.1 堆大小配置

```
=== 验证8GB堆配置 ===
MaxHeapSize:     8589934592 (8GB)
InitialHeapSize: 8589934592 (8GB)

=== 验证大页已禁用 ===
UseLargePages:           false
UseTransparentHugePages: false
LargePageSizeInBytes:    0
```

### 2.2 Region配置 (8GB堆)

```
=== Region配置 ===
HeapRegion::GrainBytes:      4194304 (4MB)
HeapRegion::GrainWords:      524288
HeapRegion::LogOfHRGrainBytes: 22
HeapRegion::LogOfHRGrainWords: 19
HeapRegion::CardsPerRegion:  8192

=== 计算总Region数 ===
已分配Region数量: 2048
理论计算: 8GB / 4MB = 2048 个Region
```

**关键发现**:
- 8GB堆自动选择 **4MB** 的Region大小（而非默认2MB）
- 总共 **2048** 个Region
- 每个Region包含 **8192** 张卡片（每张512字节）

## 三、G1CollectedHeap核心组件

### 3.1 GDB验证的核心组件地址

```
=== G1核心组件地址 ===
_g1_policy:           0x7ffff0038980   <- G1策略对象
_allocator:           0x7ffff00413a0   <- G1分配器
_workers:             0x7ffff003f490   <- GC工作线程池
_card_table:          0x7ffff0042ae0   <- G1卡表
_g1_rem_set:          0x7ffff004c4f0   <- G1记忆集
_cm:                  0x7ffff005a1e0   <- G1并发标记
_ref_processor_stw:   0x0              <- STW引用处理器(初始化后)
_ref_processor_cm:    0x0              <- 并发标记引用处理器(初始化后)
_bot:                 0x7ffff0059000   <- 块偏移表
_hot_card_cache:      0x7ffff0042d50   <- 热卡缓存
```

### 3.2 核心组件详解

#### 3.2.1 G1Policy (_g1_policy)

```
地址: 0x7ffff0038980

G1Policy详情:
{
  _predictor = { _sigma = 0.5 },
  _analytics = 0x7ffff0038be0,
  _mmu_tracker = 0x7ffff003a110,
  _ihop_control = 0x7ffff003a570,
  _young_list_target_length = 102,
  _young_list_fixed_length = 0,
  _young_list_max_length = 108,
  _reserve_factor = 0.1,
  _reserve_regions = 205,
  _free_regions_at_end_of_collection = 2048,
  _tenuring_threshold = 15,
  _max_survivor_regions = 0
}

YoungGen配置:
{
  _sizer_kind = G1YoungGenSizer::SizerDefaults,
  _min_desired_young_length = 102,
  _max_desired_young_length = 1228,
  _adaptive_size = true
}
```

**主要职责**:
- 预测GC暂停时间
- 选择CSet（Collection Set）
- 调整堆大小
- 控制并发标记触发
- Young区大小: 最小102个Region，最大1228个Region

#### 3.2.2 G1Allocator (_allocator)

```
地址: 0x7ffff00413a0

G1Allocator详情:
{
  _g1h = 0x7ffff0032530,
  _survivor_is_full = false,
  _old_is_full = false,
  _mutator_alloc_region = {
    _alloc_region = 0x7ffff0c82700,
    _name = "Mutator Alloc Region",
    _wasted_bytes = 0,
    _retained_alloc_region = 0x0
  },
  _survivor_gc_alloc_region = {
    _alloc_region = 0x0,
    _name = "Survivor GC Alloc Region"
  },
  _old_gc_alloc_region = {
    _alloc_region = 0x0,
    _name = "Old GC Alloc Region",
    _bot_updates = true
  },
  _retained_old_gc_alloc_region = 0x0
}
```

**分配策略**:
- 小对象: TLAB快速分配 (Mutator Alloc Region)
- Survivor对象: Survivor GC Alloc Region
- 老年代对象: Old GC Alloc Region
- 大对象: Humongous Region分配

#### 3.2.3 WorkGang (_workers)

```
地址: 0x7ffff003f490

WorkGang详情:
{
  _workers = 0x7ffff003f640,
  _total_workers = 13,
  _active_workers = 1,
  _created_workers = 1,
  _name = "GC Thread",
  _are_GC_task_threads = true,
  _are_ConcurrentGC_threads = false,
  _dispatcher = 0x7ffff003f500
}
```

**GDB观察到的线程创建**:
```
[New Thread 0x7ffff780b6c0 (LWP 637945)]  <- 主VM线程
[New Thread 0x7ffff51ff6c0 (LWP 637946)]  <- GC工作线程1
[New Thread 0x7ffff43f06c0 (LWP 637947)]  <- GC工作线程2
[New Thread 0x7ffff42ee6c0 (LWP 637948)]  <- GC工作线程3
[New Thread 0x7fffc33f36c0 (LWP 637949)]  <- GC工作线程4
[New Thread 0x7fffc32f16c0 (LWP 637950)]  <- GC工作线程5
```

#### 3.2.4 G1CardTable (_card_table)

```
地址: 0x7ffff0042ae0

G1CardTable详情:
{
  _scanned_concurrently = true,
  _whole_heap = {
    _start = 0x600000000,
    _word_size = 1073741824
  },
  _guard_index = 16777216,
  _last_valid_index = 16777215,
  _page_size = 4096,
  _byte_map_size = 16777216,           <- 16MB卡表大小
  _byte_map = 0x7fffdb000000,
  _byte_map_base = 0x7fffd8000000,
  _cur_covered_regions = 1
}
```

**工作原理**:
- 将8GB堆内存划分为512字节的卡片
- 每个卡片用1字节标记
- 卡表总大小: 16MB (8GB / 512)
- 写屏障更新卡表

#### 3.2.5 G1RemSet (_g1_rem_set)

```
地址: 0x7ffff004c4f0

G1RemSet详情:
{
  _scan_state = 0x7ffff004c5a0,
  _g1h = 0x7ffff0032530,
  _num_conc_refined_cards = 0,
  _ct = 0x7ffff0042ae0,           <- 关联的CardTable
  _g1p = 0x7ffff0038980,          <- 关联的G1Policy
  _hot_card_cache = 0x7ffff0042d50
}
```

**与CardTable的关系**:
- CardTable是物理层面的记录
- RemSet是逻辑层面的Region间引用关系

#### 3.2.6 G1ConcurrentMark (_cm)

```
地址: 0x7ffff005a1e0

G1ConcurrentMark详情:
{
  _cm_thread = 0x7ffff0063000,
  _g1h = 0x7ffff0032530,
  _completed_initialization = true,
  _mark_bitmap_1 = {
    _covered = { _start = 0x600000000, _word_size = 1073741824 },
    _shifter = 0,
    _bm = { _map = 0x7fffd2000000, _size = 1073741824 }
  },
  _mark_bitmap_2 = {
    _covered = { _start = 0x600000000, _word_size = 1073741824 },
    _shifter = 0,
    _bm = { _map = 0x7fffca000000, _size = 1073741824 }
  },
  _prev_mark_bitmap = 0x7ffff005a200,
  _next_mark_bitmap = 0x7ffff005a238,
  _heap = { _start = 0x600000000, _word_size = 1073741824 },
  _num_concurrent_workers = 3,
  _max_concurrent_workers = 3,
  _max_num_tasks = 13,
  _num_active_tasks = 0
}
```

**并发标记阶段**:
1. 初始标记 (STW)
2. 并发标记 (3个并发工作线程)
3. 最终标记 (STW)
4. 清理 (部分STW)

**位图大小计算**:
- 每个位图覆盖 8GB 堆
- 位图大小: 1073741824 bits = 128MB
- 两个位图交替使用（双缓冲）

#### 3.2.7 G1BlockOffsetTable (_bot)

```
地址: 0x7ffff0059000

G1BlockOffsetTable详情:
{
  _reserved = {
    _start = 0x600000000,
    _word_size = 1073741824
  },
  _offset_array = 0x7fffe0000000
}
```

**用途**: 在GC扫描时，快速定位对象起始位置

#### 3.2.8 G1HotCardCache (_hot_card_cache)

```
地址: 0x7ffff0042d50

G1HotCardCache详情:
{
  _g1h = 0x7ffff0032530,
  _use_cache = true,
  _card_counts = {
    _g1h = 0x7ffff0032530,
    _ct = 0x7ffff0042ae0,
    _card_counts = 0x7fffda000000,
    _reserved_max_card_num = 16777216,  <- 最大卡片数
    _ct_bot = 0x7fffdb000000
  },
  _hot_cache = 0x7ffff004a4b0,
  _hot_cache_size = 1024,
  _hot_cache_par_chunk_size = 32
}
```

**功能**: 缓存频繁更新的热卡片，优化并发精炼处理

## 四、Humongous对象阈值

### 4.1 GDB验证的配置

```
=== Humongous对象阈值 ===
_humongous_object_threshold_in_words: 262144
换算字节数: 2097152 (2MB)
```

**参数解析**:
- `_humongous_object_threshold_in_words = 262144`
- 换算: 262144 * 8 = 2,097,152 字节 = **2MB**
- **含义**: 大于2MB的对象被视为Humongous对象

### 4.2 Humongous对象阈值计算

```cpp
// 阈值 = Region大小 / 2
// 8GB堆的Region大小 = 4MB
// 阈值 = 4MB / 2 = 2MB = 262144 words (64位)
```

## 五、HeapRegionManager详解

### 5.1 GDB验证的HeapRegionManager

```
=== HeapRegionManager ===
地址: (G1CollectedHeap内嵌)

HeapRegionManager详情:
{
  _regions = {
    _base = 0x7ffff0046300,
    _length = 2048,
    _biased_base = 0x7ffff003a300,
    _bias = 6144,
    _shift_by = 22
  },
  _heap_mapper = 0x7ffff0042f10,
  _prev_bitmap_mapper = 0x7ffff0043e10,
  _next_bitmap_mapper = 0x7ffff0045060,
  _bot_mapper = 0x7ffff0043120,
  _cardtable_mapper = 0x7ffff0043570,
  _card_counts_mapper = 0x7ffff00439c0,
  _free_list = {
    _is_humongous = false,
    _is_free = true,
    _length = 2048,
    _name = "Free list",
    _head = 0x7ffff009e2e0,
    _tail = 0x7ffff0c7cb20
  },
  _available_map = {
    _map = 0x7ffff004a370,
    _size = 2048
  },
  _num_committed = 2048,
  _allocated_heapregions_length = 2048
}
```

### 5.2 HeapRegionManager核心功能

| 组件 | 功能 |
|------|------|
| _regions | 存储所有HeapRegion的biased数组 (2048个) |
| _heap_mapper | 管理堆内存的虚拟内存映射 |
| _prev_bitmap_mapper | 管理上次GC的标记位图 (128MB) |
| _next_bitmap_mapper | 管理当前GC的标记位图 (128MB) |
| _bot_mapper | 管理块偏移表的内存映射 |
| _cardtable_mapper | 管理卡表的内存映射 (16MB) |
| _card_counts_mapper | 管理卡计数的内存映射 |
| _free_list | 空闲Region列表 (初始2048个全部空闲) |

## 六、HeapRegion详解

### 6.1 GDB验证的HeapRegion结构

```
=== 查看第一个HeapRegion ===
Region地址: 0x7ffff009e2e0

HeapRegion详情:
{
  members of Space:
    _bottom = 0x600000000,
    _end = 0x600400000,
  
  members of G1ContiguousSpace:
    _top = 0x600000000,
    _bot_part = {
      _next_offset_threshold = 0x600000200,
      _next_offset_index = 1,
      _object_can_span = false,
      _bot = 0x7ffff0059000
    },
    _par_alloc_lock = { _name = "OffsetTableContigSpace par alloc lock" }
  
  members of HeapRegion:
    _rem_set = 0x7ffff009e4d0,
    _hrm_index = 0,
    _type = { _tag = HeapRegionType::FreeTag },
    _humongous_start_region = 0x0,
    _evacuation_failed = false,
    _next = 0x7ffff009faa0,
    _prev = 0x0,
    _containing_set = 0x7ffff0032840,
    _prev_marked_bytes = 0,
    _next_marked_bytes = 0,
    _gc_efficiency = 0,
    _young_index_in_cset = -1,
    _surv_rate_group = 0x0,
    _age_index = -1,
    _prev_top_at_mark_start = 0x600000000,
    _next_top_at_mark_start = 0x600000000,
    
    static LogOfHRGrainBytes = 22,
    static LogOfHRGrainWords = 19,
    static GrainBytes = 4194304,     <- 4MB
    static GrainWords = 524288,
    static CardsPerRegion = 8192
}
```

### 6.2 验证Region大小和连续性

```
=== 验证Region大小 (4MB) ===
Region0 大小(words): 524288
Region0 大小(bytes): 4194304 (4MB)

=== 查看多个Region ===
Region0: _hrm_index=0, _bottom=0x600000000
Region1: _hrm_index=1, _bottom=0x600400000
Region2: _hrm_index=2, _bottom=0x600800000

=== 验证Region连续性 ===
Region1._bottom - Region0._bottom = 524288 words = 4MB
```

### 6.3 HeapRegion的RemSet

```
=== Region 0 的RemSet ===
HeapRegionRemSet详情:
{
  _bot = 0x7ffff0059000,
  _code_roots = { _table = 0x0, _length = 0 },
  _m = { _name = "HeapRegionRemSet lock #0" },
  _other_regions = {
    _g1h = 0x7ffff0032530,
    _hr = 0x7ffff009e2e0,
    _coarse_map = { _size = 2048 },
    _n_coarse_entries = 0,
    _fine_grain_regions = 0x7ffff009ea60,
    _n_fine_entries = 0,
    _sparse_table = {
      _cur = 0x7ffff009e790,
      _hr = 0x7ffff009e2e0,
      _expanded = false
    }
  },
  _state = HeapRegionRemSet::Untracked
}
```

## 七、Reserved内存区域

### 7.1 GDB验证的内存布局

```
=== Reserved内存区域 ===
_reserved = {
  _start = 0x600000000,
  _word_size = 1073741824
}

计算:
- _start: 0x600000000 (堆起始地址，约25.6GB处)
- _word_size: 1073741824 words
- 总大小: 1073741824 * 8 = 8,589,934,592 bytes = 8GB
```

## 八、区域集合状态

### 8.1 GDB验证的区域集合

```
=== Old区域集合 ===
HeapRegionSet {
  _is_humongous = false,
  _is_free = false,
  _length = 0,                    <- 初始时无Old区域
  _name = "Old Set"
}

=== Humongous区域集合 ===
HeapRegionSet {
  _is_humongous = true,
  _is_free = false,
  _length = 0,                    <- 初始时无Humongous对象
  _name = "Master Humongous Set"
}

=== Free区域列表 ===
FreeRegionList {
  _is_humongous = false,
  _is_free = true,
  _length = 2048,                 <- 初始时全部空闲
  _name = "Free list",
  _head = 0x7ffff009e2e0,
  _tail = 0x7ffff0c7cb20
}
```

## 九、G1 GC配置参数

### 9.1 GDB验证的参数

```
=== G1 GC配置参数 ===
G1HeapRegionSize:              4194304 (4MB)
G1ConcRefinementThreads:       13
MaxGCPauseMillis:              200
G1HeapWastePercent:            5
G1MixedGCCountTarget:          8
G1NewSizePercent:              5
G1MaxNewSizePercent:           60
G1ReservePercent:              10
InitiatingHeapOccupancyPercent: 45
```

### 9.2 IHOP控制

```
=== IHOP控制 ===
G1AdaptiveIHOPControl {
  _initial_ihop_percent = 45,
  _target_occupancy = 8589934592,  <- 8GB
  _last_allocation_time_s = 0,
  _heap_reserve_percent = 10,
  _heap_waste_percent = 5,
  _marking_times_s = { _num = 0, _length = 10 },
  _allocation_rate_s = { _num = 0, _length = 10 },
  _last_unrestrained_young_size = 0
}
```

### 9.3 MMU (Minimum Mutator Utilization) Tracker

```
=== MMUTracker ===
G1MMUTrackerQueue {
  _time_slice = 0.201,    <- 200ms时间片
  _max_gc_time = 0.2,     <- 最大GC时间200ms
  _head_index = 0,
  _tail_index = 1,
  _no_entries = 0
}
```

## 十、G1CollectionSet

### 10.1 GDB验证的CollectionSet

```
=== G1CollectionSet ===
{
  _g1h = 0x7ffff0032530,
  _policy = 0x7ffff0038980,
  _cset_chooser = 0x7ffff003f090,
  _eden_region_length = 0,
  _survivor_region_length = 0,
  _old_region_length = 0,
  _collection_set_regions = 0x7ffff0c88510,
  _collection_set_cur_length = 0,
  _collection_set_max_length = 2048,
  _bytes_used_before = 0,
  _recorded_rs_lengths = 0,
  _inc_build_state = G1CollectionSet::Active
}
```

## 十一、G1BarrierSet

### 11.1 GDB验证的BarrierSet

```
=== BarrierSet ===
地址: 0x7ffff0042c00
类型: G1BarrierSet

G1BarrierSet详情:
{
  members of BarrierSet:
    _fake_rtti = { _concrete_tag = BarrierSet::G1BarrierSet },
    _barrier_set_assembler = 0x7ffff0042d10,
    _barrier_set_c1 = 0x7ffff0042cc0,
    _barrier_set_c2 = 0x7ffff0042c80
  
  members of CardTableBarrierSet:
    _defer_initial_card_mark = true,
    _card_table = 0x7ffff0042ae0
  
  static _satb_mark_queue_set = {
    _buffer_size = 1024,
    _n_completed_buffers = 0,
    _process_completed_threshold = 20,
    _all_active = false
  },
  
  static _dirty_card_queue_set = {
    _buffer_size = 256,
    _n_completed_buffers = 0,
    _process_completed_threshold = 39,
    _all_active = true,
    _max_completed_queue = 65
  }
}
```

### 11.2 SATB和DirtyCard队列

```
=== G1 SATB和DirtyCard队列 ===
SATB队列缓冲区大小: 1024
DirtyCard队列缓冲区大小: 256
```

## 十二、GC状态标志

```
=== GC状态标志 ===
_is_gc_active: false
_old_marking_cycles_started: 0
_old_marking_cycles_completed: 0
```

## 十三、并发线程

```
=== 并发标记线程 ===
G1ConcurrentMarkThread地址: 0x7ffff0063000

=== 并发精炼线程 ===
G1ConcurrentRefine地址: 0x7ffff0c7e2e0
并发精炼线程数: 13 (G1ConcRefinementThreads)
```

## 十四、G1CollectedHeap内存布局

```
G1CollectedHeap对象布局 (1864字节):
┌─────────────────────────────────────────────────────────────────────┐
│ CollectedHeap父类成员                                                │
│   _reserved: MemRegion { 0x600000000, 1073741824 words }            │
│   _is_gc_active: false                                              │
│   ...                                                               │
├─────────────────────────────────────────────────────────────────────┤
│ G1CollectedHeap特有成员                                              │
│   _hrm: HeapRegionManager (内嵌对象)                                │
│       _regions: 2048个HeapRegion*                                   │
│       _num_committed: 2048                                          │
│       _free_list: 2048个空闲Region                                  │
│   _g1_policy: G1Policy*              [0x7ffff0038980]               │
│       _young_list_target_length: 102                                │
│       _young_list_max_length: 108                                   │
│   _allocator: G1Allocator*           [0x7ffff00413a0]               │
│   _workers: WorkGang*                [0x7ffff003f490]               │
│       _total_workers: 13                                            │
│   _card_table: G1CardTable*          [0x7ffff0042ae0]               │
│       _byte_map_size: 16MB                                          │
│   _g1_rem_set: G1RemSet*             [0x7ffff004c4f0]               │
│   _cm: G1ConcurrentMark*             [0x7ffff005a1e0]               │
│       _num_concurrent_workers: 3                                    │
│   _bot: G1BlockOffsetTable*          [0x7ffff0059000]               │
│   _hot_card_cache: G1HotCardCache*   [0x7ffff0042d50]               │
│   _humongous_object_threshold_in_words: 262144 (2MB)                │
│   _survivor: G1SurvivorRegions                                      │
│   _old_set: HeapRegionSet { _length: 0 }                            │
│   _humongous_set: HeapRegionSet { _length: 0 }                      │
│   _collection_set: G1CollectionSet { _max_length: 2048 }            │
│   _cr: G1ConcurrentRefine*           [0x7ffff0c7e2e0]               │
│   ...                                                               │
└─────────────────────────────────────────────────────────────────────┘
```

## 十五、G1CollectedHeap初始化流程

### 15.1 GDB验证的初始化时序

```
Thread 2 "java" hit Breakpoint at G1CollectedHeap::G1CollectedHeap
#0  G1CollectedHeap::G1CollectedHeap (this=0x7ffff0032530)
#1  GCArguments::create_heap_with_policy<G1CollectedHeap, G1CollectorPolicy>
#2  G1Arguments::create_heap
#3  Universe::create_heap()
#4  Universe::initialize_heap()

构造函数this指针: 0x7ffff0032530
对象大小: 1864 bytes

继续到 G1CollectedHeap::initialize
Thread 2 "java" hit Breakpoint at G1CollectedHeap::initialize
#0  G1CollectedHeap::initialize (this=0x7ffff0032530)
#1  Universe::initialize_heap()
#2  universe_init()
#3  init_globals()
#4  Threads::create_vm

[创建GC工作线程...]
[New Thread 0x7ffff51ff6c0]  <- GC线程1
[New Thread 0x7ffff43f06c0]  <- GC线程2
[New Thread 0x7ffff42ee6c0]  <- GC线程3
[New Thread 0x7fffc33f36c0]  <- GC线程4
[New Thread 0x7fffc32f16c0]  <- GC线程5
```

### 15.2 初始化顺序

1. **构造函数**: 初始化成员变量
2. **initialize()**: 
   - 创建HeapRegionManager（管理2048个4MB Region）
   - 初始化卡表（16MB）
   - 创建G1Policy
   - 创建G1Allocator
   - 创建GC工作线程池（13个线程）
   - 创建并发标记器（3个并发工作线程）
   - 创建记忆集
   - 创建引用处理器

## 十六、8GB堆关键数据汇总

| 配置项 | 值 |
|--------|-----|
| 堆大小 | 8GB (8,589,934,592 bytes) |
| Region大小 | 4MB |
| Region数量 | 2048 |
| Humongous阈值 | 2MB |
| 卡表大小 | 16MB |
| 标记位图大小 | 128MB × 2 |
| GC工作线程数 | 13 |
| 并发标记线程数 | 3 |
| 并发精炼线程数 | 13 |
| Young区目标 | 102-108 个Region |
| Young区最大 | 1228 个Region (60%) |
| 预留Region | 205 (10%) |
| IHOP触发阈值 | 45% |
| 最大GC暂停目标 | 200ms |

## 十七、G1堆组件依赖关系

```
                        G1CollectedHeap (0x7ffff0032530)
                                │
            ┌───────────────────┼───────────────────┐
            │                   │                   │
            ▼                   ▼                   ▼
    HeapRegionManager     G1Policy            G1Allocator
    (2048 Regions)      (0x7ffff0038980)    (0x7ffff00413a0)
            │                   │                   │
            │                   ▼                   │
            │          ┌───────────────┐            │
            │          │ G1Analytics   │            │
            │          │ G1MMUTracker  │            │
            │          │ G1IHOPControl │            │
            │          └───────────────┘            │
            │                                       │
            ▼                                       ▼
    ┌─────────────┐                        ┌─────────────┐
    │ HeapRegion  │◄───────────────────────│ Alloc Region│
    │   Array     │                        │   (Eden)    │
    │ (4MB each)  │                        └─────────────┘
    └─────────────┘
            │
            ▼
    ┌───────────────────────────────────────────────────┐
    │                  辅助数据结构                       │
    │  ┌──────────────┐ ┌──────────┐ ┌───────────────┐  │
    │  │ G1CardTable  │ │ G1RemSet │ │ G1BlockOffset │  │
    │  │   (16MB)     │ │          │ │    Table      │  │
    │  └──────────────┘ └──────────┘ └───────────────┘  │
    └───────────────────────────────────────────────────┘
            │
            ▼
    ┌───────────────────────────────────────────────────┐
    │                  GC执行组件                        │
    │  ┌──────────────┐ ┌──────────┐ ┌───────────────┐  │
    │  │G1ConcMark    │ │ RefProc  │ │  WorkGang     │  │
    │  │(3 workers)   │ │          │ │ (13 threads)  │  │
    │  └──────────────┘ └──────────┘ └───────────────┘  │
    └───────────────────────────────────────────────────┘
            │
            ▼
    ┌───────────────────────────────────────────────────┐
    │               G1BarrierSet (写屏障)                │
    │  ┌──────────────────┐ ┌────────────────────────┐  │
    │  │ SATB Queue Set   │ │ DirtyCard Queue Set    │  │
    │  │ (buffer: 1024)   │ │ (buffer: 256)          │  │
    │  └──────────────────┘ └────────────────────────┘  │
    └───────────────────────────────────────────────────┘
```
