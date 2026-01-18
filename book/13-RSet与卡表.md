# 第13章 RSet与卡表

> **环境声明**：本章所有分析基于本地真实源码，目标平台为 **Linux x86-64**，堆大小 **8GB**，Region大小 **4MB**，共 **2048个Region**。

RSet（Remembered Set）和卡表（Card Table）是G1实现高效跨Region引用追踪的核心数据结构。本章深入分析它们的实现细节。

## 13.1 卡表基础

### 13.1.1 CardTable结构

```
源码位置: src/hotspot/share/gc/shared/cardTable.hpp:33-107
```

```cpp
class CardTable: public CHeapObj<mtGC> {
protected:
  const bool      _scanned_concurrently;
  const MemRegion _whole_heap;       // 卡表覆盖的堆区域
  size_t          _guard_index;      // 最后一个元素索引（守卫值）
  size_t          _last_valid_index; // 最后有效索引
  const size_t    _page_size;        // 页大小
  size_t          _byte_map_size;    // 字节映射大小
  jbyte*          _byte_map;         // 卡标记数组
  jbyte*          _byte_map_base;    // 基地址

  enum CardValues {
    clean_card                  = -1,   // 干净卡
    clean_card_mask             = clean_card - 31,
    dirty_card                  =  0,   // 脏卡
    precleaned_card             =  1,   // 预清理卡
    claimed_card                =  2,   // 已声明卡
    deferred_card               =  4,   // 延迟卡
    last_card                   =  8,
    CT_MR_BS_last_reserved      = 16
  };
};
```

### 13.1.2 卡表大小计算

```
8GB堆的卡表大小:

卡大小 = 512字节
堆大小 = 8GB = 8 × 1024 × 1024 × 1024 = 8,589,934,592字节

卡表大小 = 堆大小 / 卡大小
         = 8,589,934,592 / 512
         = 16,777,216字节
         = 16MB

每个Region的卡数:
Region大小 = 4MB = 4,194,304字节
CardsPerRegion = 4,194,304 / 512 = 8,192卡
```

### 13.1.3 卡表内存布局

```
8GB堆的卡表布局:

堆地址空间:
0x00000000_00000000 ─────────────────────────────── 0x00000002_00000000
│                           8GB                                       │
└─────────────────────────────────────────────────────────────────────┘

卡表映射:
┌─────────────────────────────────────────────────────────────────────┐
│                         16MB 卡表                                    │
│  每个字节对应堆中512字节                                              │
│  卡[0]    卡[1]    卡[2]   ...   卡[16777215]                        │
│  ┌────┐  ┌────┐  ┌────┐        ┌────┐                               │
│  │-1  │  │ 0  │  │-1  │  ...   │-1  │                               │
│  │clean│ │dirty│ │clean│       │clean│                              │
│  └────┘  └────┘  └────┘        └────┘                               │
└─────────────────────────────────────────────────────────────────────┘
     │        │        │              │
     ▼        ▼        ▼              ▼
   堆[0-511] 堆[512-1023] ...     堆[最后512字节]
```

### 13.1.4 G1CardTable

```
源码位置: src/hotspot/share/gc/g1/g1CardTable.hpp:47-122
```

```cpp
// G1特有的卡表实现
class G1CardTable: public CardTable {
  G1CardTableChangedListener _listener;

  enum G1CardValues {
    g1_young_gen = CT_MR_BS_last_reserved << 1  // G1年轻代卡值
  };

public:
  G1CardTable(MemRegion whole_heap)
    : CardTable(whole_heap, /* scanned concurrently */ true), 
      _listener() {
    _listener.set_card_table(this);
  }

  bool is_card_dirty(size_t card_index) {
    return _byte_map[card_index] == dirty_card_val();
  }

  static jbyte g1_young_card_val() { return g1_young_gen; }

  bool is_card_claimed(size_t card_index) {
    jbyte val = _byte_map[card_index];
    return (val & (clean_card_mask_val() | claimed_card_val())) == claimed_card_val();
  }

  bool mark_card_deferred(size_t card_index);

  bool is_card_deferred(size_t card_index) {
    jbyte val = _byte_map[card_index];
    return (val & (clean_card_mask_val() | deferred_card_val())) == deferred_card_val();
  }
};
```

### 13.1.5 卡状态转换

```
卡状态转换图:

                    ┌─────────────┐
                    │   clean     │
                    │    (-1)     │
                    └──────┬──────┘
                           │ 写操作
                           ▼
                    ┌─────────────┐
           ┌────────│   dirty     │────────┐
           │        │    (0)      │        │
           │        └──────┬──────┘        │
           │               │               │
    并发细化│               │GC期间扫描     │GC期间声明
           │               │               │
           ▼               ▼               ▼
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │ precleaned  │ │  deferred   │ │  claimed    │
    │    (1)      │ │    (4)      │ │    (2)      │
    └─────────────┘ └─────────────┘ └─────────────┘
           │               │               │
           └───────────────┼───────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │   clean     │
                    │    (-1)     │
                    └─────────────┘
```

## 13.2 HeapRegionRemSet

### 13.2.1 RSet结构概述

```
源码位置: src/hotspot/share/gc/g1/heapRegionRemSet.hpp:170-349
```

```cpp
class HeapRegionRemSet : public CHeapObj<mtGC> {
private:
  G1BlockOffsetTable* _bot;

  // 代码根集合（nmethod指针）
  G1CodeRootSet _code_roots;

  Mutex _m;

  // 其他Region表（核心数据结构）
  OtherRegionsTable _other_regions;

  enum RemSetState {
    Untracked,    // 未追踪
    Updating,     // 更新中
    Complete      // 完成
  };

  RemSetState _state;

public:
  // 添加引用
  void add_reference(OopOrNarrowOopStar from, uint tid) {
    RemSetState state = _state;
    if (state == Untracked) {
      return;
    }
    _other_regions.add_reference(from, tid);
  }

  // 获取占用卡数
  size_t occupied() {
    MutexLockerEx x(&_m, Mutex::_no_safepoint_check_flag);
    return occupied_locked();
  }
};
```

### 13.2.2 RSet三级结构

```
源码位置: src/hotspot/share/gc/g1/heapRegionRemSet.hpp:74-168
```

G1的RSet采用三级结构来平衡空间和时间效率：

```cpp
class OtherRegionsTable {
  G1CollectedHeap* _g1h;
  Mutex*           _m;
  HeapRegion*      _hr;

  // 第一级：粗粒度位图（每Region一位）
  CHeapBitMap _coarse_map;
  size_t      _n_coarse_entries;

  // 第二级：细粒度表（每Region一个PRT）
  PerRegionTable** _fine_grain_regions;
  size_t           _n_fine_entries;
  PerRegionTable * _first_all_fine_prts;
  PerRegionTable * _last_all_fine_prts;

  // 第三级：稀疏表（少量卡的Region）
  SparsePRT   _sparse_table;

  static size_t _max_fine_entries;
};
```

### 13.2.3 三级结构示意图

```
RSet三级结构（8GB堆，2048个Region）:

┌─────────────────────────────────────────────────────────────────────┐
│                     HeapRegionRemSet (Region X)                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ 第一级: Coarse Map (粗粒度位图)                              │    │
│  │ 大小: 2048位 = 256字节                                       │    │
│  │ 每位代表一个Region是否可能包含指向本Region的引用              │    │
│  │ ┌─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┐                           │    │
│  │ │0│1│0│0│1│0│0│0│1│0│...│0│1│0│0│0│ (2048位)                │    │
│  │ └─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┘                           │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ 第二级: Fine Grain Table (细粒度表)                          │    │
│  │ 开放哈希表，每个条目是PerRegionTable                          │    │
│  │ 最大条目数: G1RSetRegionEntries (默认根据堆大小计算)          │    │
│  │                                                               │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                    │    │
│  │  │ PRT[R1]  │  │ PRT[R5]  │  │ PRT[R9]  │  ...               │    │
│  │  │ 8192位图 │  │ 8192位图 │  │ 8192位图 │                    │    │
│  │  └──────────┘  └──────────┘  └──────────┘                    │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ 第三级: Sparse PRT (稀疏表)                                  │    │
│  │ 用于引用数量少的Region                                        │    │
│  │ 每个条目存储: Region索引 + 少量卡索引                         │    │
│  │                                                               │    │
│  │  ┌─────────────────────────────────────────┐                 │    │
│  │  │ Entry[R3]: cards=[100, 200, 350, ...]   │                 │    │
│  │  │ Entry[R7]: cards=[50, 1024, ...]        │                 │    │
│  │  └─────────────────────────────────────────┘                 │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## 13.3 PerRegionTable

### 13.3.1 PRT结构

```
源码位置: src/hotspot/share/gc/g1/heapRegionRemSet.cpp:45-200
```

```cpp
class PerRegionTable: public CHeapObj<mtGC> {
  HeapRegion*     _hr;
  CHeapBitMap     _bm;        // 卡位图
  jint            _occupied;  // 占用卡数

  PerRegionTable* _next;      // 链表指针
  PerRegionTable* _prev;
  PerRegionTable* _collision_list_next;  // 哈希冲突链

  static PerRegionTable* volatile _free_list;  // 全局空闲链表

protected:
  PerRegionTable(HeapRegion* hr) :
    _hr(hr),
    _occupied(0),
    _bm(HeapRegion::CardsPerRegion, mtGC),  // 8192位
    _collision_list_next(NULL), _next(NULL), _prev(NULL)
  {}

  void add_card_work(CardIdx_t from_card, bool par) {
    if (!_bm.at(from_card)) {
      if (par) {
        if (_bm.par_at_put(from_card, 1)) {
          Atomic::inc(&_occupied);
        }
      } else {
        _bm.at_put(from_card, 1);
        _occupied++;
      }
    }
  }

public:
  jint occupied() const { return _occupied; }

  // 内存大小
  size_t mem_size() const {
    return sizeof(PerRegionTable) + _bm.size_in_words() * HeapWordSize;
  }
};
```

### 13.3.2 PRT内存布局

```
PerRegionTable内存布局（4MB Region）:

┌─────────────────────────────────────────────────────────────────────┐
│                        PerRegionTable                                │
├─────────────────────────────────────────────────────────────────────┤
│ _hr: HeapRegion*         (8字节)   指向源Region                      │
│ _bm: CHeapBitMap         (位图)    8192位 = 1024字节                 │
│ _occupied: jint          (4字节)   占用卡数                          │
│ _next: PerRegionTable*   (8字节)   链表指针                          │
│ _prev: PerRegionTable*   (8字节)   链表指针                          │
│ _collision_list_next     (8字节)   哈希冲突链                        │
├─────────────────────────────────────────────────────────────────────┤
│ 总大小: ~1060字节                                                    │
└─────────────────────────────────────────────────────────────────────┘

位图详情:
┌─────────────────────────────────────────────────────────────────────┐
│ 位图大小 = CardsPerRegion = 4MB / 512B = 8192位 = 1024字节          │
│                                                                      │
│ 每位对应源Region中的一个512字节卡                                    │
│ 位[i]=1 表示源Region的第i个卡包含指向本Region的引用                  │
│                                                                      │
│ ┌─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬───────────────────────────────┐  │
│ │0│1│0│0│1│0│0│0│1│0│0│1│0│0│0│0│...                            │  │
│ └─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴───────────────────────────────┘  │
│  卡0 卡1 卡2 ...                                     卡8191          │
└─────────────────────────────────────────────────────────────────────┘
```

## 13.4 SparsePRT

### 13.4.1 SparsePRT结构

```
源码位置: src/hotspot/share/gc/g1/sparsePRT.hpp:36-150
```

```cpp
// 稀疏PRT条目
class SparsePRTEntry: public CHeapObj<mtGC> {
private:
  typedef uint16_t card_elem_t;

  RegionIdx_t _region_ind;     // Region索引
  int         _next_index;     // 链表索引
  int         _next_null;      // 下一个空槽位
  card_elem_t _cards[...];     // 卡数组（变长）

public:
  static int cards_num() {
    return align_up((int)G1RSetSparseRegionEntries, (int)card_array_alignment);
  }

  // 添加卡
  AddCardResult add_card(CardIdx_t card_index);

  // 检查是否包含卡
  bool contains_card(CardIdx_t card_index) const;
};

// 稀疏PRT哈希表
class RSHashTable : public CHeapObj<mtGC> {
  static float TableOccupancyFactor;

  size_t _num_entries;
  size_t _capacity;
  size_t _capacity_mask;
  size_t _occupied_entries;
  size_t _occupied_cards;

  SparsePRTEntry* _entries;
  int* _buckets;
  int  _free_region;
  int  _free_list;
};
```

### 13.4.2 SparsePRT工作原理

```
SparsePRT用于引用数量少的Region:

场景: Region A 只有少量引用指向 Region X

传统方式（PRT）:
┌─────────────────────────────────────────────────────────────────────┐
│ PRT for Region A: 8192位位图 = 1024字节                             │
│ 但只有3个位被设置 → 浪费大量空间                                     │
└─────────────────────────────────────────────────────────────────────┘

SparsePRT方式:
┌─────────────────────────────────────────────────────────────────────┐
│ SparsePRTEntry for Region A:                                        │
│ ┌────────────────┬───────────────────────────────────────────┐     │
│ │ _region_ind: A │ _cards: [100, 500, 7000]                   │     │
│ └────────────────┴───────────────────────────────────────────┘     │
│ 只存储实际的卡索引，节省空间                                         │
└─────────────────────────────────────────────────────────────────────┘

G1RSetSparseRegionEntries (默认4):
- 每个SparsePRTEntry最多存储4个卡索引
- 超过4个卡时，升级为PerRegionTable
```

### 13.4.3 RSet升级策略

```
RSet升级流程:

                    ┌─────────────────┐
                    │   新引用到达     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ 查找SparsePRT   │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
        ┌─────────┐   ┌─────────────┐  ┌─────────────┐
        │ 未找到   │   │ 找到且未满  │  │ 找到但已满  │
        └────┬────┘   └──────┬──────┘  └──────┬──────┘
             │               │                │
             ▼               ▼                ▼
      ┌────────────┐  ┌────────────┐   ┌────────────┐
      │创建新Entry │  │ 添加卡索引  │   │升级为PRT   │
      │在SparsePRT│  │            │   │            │
      └────────────┘  └────────────┘   └─────┬──────┘
                                             │
                                    ┌────────▼────────┐
                                    │ Fine表是否已满？│
                                    └────────┬────────┘
                                             │
                              ┌──────────────┼──────────────┐
                              │              │              │
                              ▼              ▼              ▼
                        ┌─────────┐   ┌─────────────┐
                        │  未满   │   │    已满     │
                        └────┬────┘   └──────┬──────┘
                             │               │
                             ▼               ▼
                      ┌────────────┐  ┌────────────────┐
                      │添加到Fine表│  │驱逐一个PRT     │
                      │           │  │设置Coarse位    │
                      └────────────┘  └────────────────┘
```

## 13.5 G1RemSet

### 13.5.1 G1RemSet结构

```
源码位置: src/hotspot/share/gc/g1/g1RemSet.hpp:57-135
```

```cpp
class G1RemSet: public CHeapObj<mtGC> {
private:
  G1RemSetScanState* _scan_state;
  G1RemSetSummary _prev_period_summary;

  G1CollectedHeap* _g1h;
  size_t _num_conc_refined_cards;  // 并发细化的卡数

  G1CardTable*           _ct;
  G1Policy*              _g1p;
  G1HotCardCache*        _hot_card_cache;

public:
  // 扫描CSet的RSet
  void scan_rem_set(G1ParScanThreadState* pss, uint worker_i);

  // 更新RSet（处理脏卡）
  void update_rem_set(G1ParScanThreadState* pss, uint worker_i);

  // 处理CSet中的所有引用
  void oops_into_collection_set_do(G1ParScanThreadState* pss, uint worker_i);

  // 并发细化卡
  void refine_card_concurrently(jbyte* card_ptr, uint worker_i);

  // GC期间细化卡
  bool refine_card_during_gc(jbyte* card_ptr, 
                             G1ScanObjsDuringUpdateRSClosure* update_rs_cl);
};
```

### 13.5.2 RSet扫描流程

```
GC期间RSet扫描流程:

┌─────────────────────────────────────────────────────────────────────┐
│                    oops_into_collection_set_do                       │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
                ▼                ▼                ▼
        ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
        │ scan_rem_set  │ │ update_rem_set│ │ code_roots    │
        │ 扫描RSet      │ │ 处理脏卡缓冲  │ │ 扫描代码根    │
        └───────┬───────┘ └───────┬───────┘ └───────────────┘
                │                 │
                ▼                 ▼
        ┌───────────────┐ ┌───────────────┐
        │ 遍历CSet中    │ │ 处理DCQ中的   │
        │ 每个Region的  │ │ 脏卡          │
        │ RSet          │ │               │
        └───────┬───────┘ └───────┬───────┘
                │                 │
                ▼                 ▼
        ┌───────────────┐ ┌───────────────┐
        │ 扫描Coarse位图│ │ 扫描卡对应的  │
        │ 扫描Fine表    │ │ 对象          │
        │ 扫描Sparse表  │ │               │
        └───────┬───────┘ └───────────────┘
                │
                ▼
        ┌───────────────┐
        │ 扫描卡中的    │
        │ 对象引用      │
        │ 找到CSet引用  │
        └───────────────┘
```

## 13.6 DirtyCardQueue

### 13.6.1 脏卡队列结构

```
源码位置: src/hotspot/share/gc/g1/dirtyCardQueue.hpp:44-150
```

```cpp
// 单个脏卡队列
class DirtyCardQueue: public PtrQueue {
public:
  DirtyCardQueue(DirtyCardQueueSet* qset, bool permanent = false);
  ~DirtyCardQueue();

  void flush() { flush_impl(); }
};

// 脏卡队列集合
class DirtyCardQueueSet: public PtrQueueSet {
  DirtyCardQueue _shared_dirty_card_queue;

  // 已完成缓冲区的处理
  bool apply_closure_to_completed_buffer(CardTableEntryClosure* cl,
                                         uint worker_i,
                                         size_t stop_at,
                                         bool during_pause);

  // Mutator和细化线程处理的缓冲区计数
  jint _processed_buffers_mut;
  jint _processed_buffers_rs_thread;

  BufferNode* volatile _cur_par_buffer_node;

public:
  // 并发细化已完成缓冲区
  bool refine_completed_buffer_concurrently(uint worker_i, size_t stop_at);

  // GC期间应用闭包
  bool apply_closure_during_gc(CardTableEntryClosure* cl, uint worker_i);
};
```

### 13.6.2 脏卡队列工作流程

```
脏卡队列工作流程:

1. 写屏障产生脏卡
   ┌─────────────────────────────────────────────────────────────────┐
   │ Mutator线程执行写操作:                                          │
   │   obj.field = new_value;                                        │
   │                                                                  │
   │ Post-Write Barrier:                                              │
   │   card_ptr = card_table_base + (obj_addr >> 9);                 │
   │   if (*card_ptr != dirty) {                                     │
   │     *card_ptr = dirty;                                          │
   │     enqueue(card_ptr);  // 加入线程本地DCQ                       │
   │   }                                                              │
   └─────────────────────────────────────────────────────────────────┘

2. 线程本地队列
   ┌─────────────────────────────────────────────────────────────────┐
   │ Thread 1 DCQ:  [card1, card2, card3, ...]                       │
   │ Thread 2 DCQ:  [card5, card6, ...]                              │
   │ Thread 3 DCQ:  [card8, card9, card10, ...]                      │
   │                                                                  │
   │ 当本地队列满时，提交到全局队列                                    │
   └─────────────────────────────────────────────────────────────────┘

3. 全局已完成队列
   ┌─────────────────────────────────────────────────────────────────┐
   │ Completed Buffer Queue:                                         │
   │   ┌─────────┐  ┌─────────┐  ┌─────────┐                        │
   │   │ Buffer1 │→ │ Buffer2 │→ │ Buffer3 │→ ...                   │
   │   └─────────┘  └─────────┘  └─────────┘                        │
   │                                                                  │
   │ 并发细化线程从队列取缓冲区处理                                    │
   └─────────────────────────────────────────────────────────────────┘
```

## 13.7 G1ConcurrentRefine

### 13.7.1 并发细化结构

```
源码位置: src/hotspot/share/gc/g1/g1ConcurrentRefine.hpp:71-137
```

```cpp
class G1ConcurrentRefine : public CHeapObj<mtGC> {
  G1ConcurrentRefineThreadControl _thread_control;

  // 三区域阈值
  size_t _green_zone;   // 绿区：不处理，利用缓存效应
  size_t _yellow_zone;  // 黄区：逐步激活细化线程
  size_t _red_zone;     // 红区：所有线程运行，Mutator也参与

  size_t _min_yellow_zone_size;

public:
  // 获取线程激活阈值
  size_t activation_threshold(uint worker_id) const;
  size_t deactivation_threshold(uint worker_id) const;

  // 执行单步细化
  bool do_refinement_step(uint worker_id);

  // 区域访问器
  size_t green_zone() const  { return _green_zone;  }
  size_t yellow_zone() const { return _yellow_zone; }
  size_t red_zone() const    { return _red_zone;    }
};
```

### 13.7.2 三区域机制

```
并发细化三区域机制:

已完成缓冲区数量
     │
     │    ┌─────────────────────────────────────────────────────────┐
     │    │                      RED ZONE                           │
     │    │  所有细化线程运行 + Mutator参与处理                      │
     │    │  紧急状态：必须立即处理                                  │
     ├────┼─────────────────────────────────────────────────────────┤
     │    │                     YELLOW ZONE                         │
     │    │  逐步激活细化线程                                        │
     │    │  线程N激活条件: buffers > activation_threshold(N)       │
     │    │  线程N停止条件: buffers < deactivation_threshold(N)     │
     ├────┼─────────────────────────────────────────────────────────┤
     │    │                     GREEN ZONE                          │
     │    │  不处理，利用脏卡缓存效应                                │
     │    │  同一卡多次写入只需处理一次                              │
     ├────┼─────────────────────────────────────────────────────────┤
     │    │                       ZERO                              │
     0────┴─────────────────────────────────────────────────────────┘

默认阈值（可通过参数调整）:
- Green Zone: ~buffers_to_start_refinement
- Yellow Zone: Green + (num_threads * step)
- Red Zone: Yellow + additional_buffer
```

### 13.7.3 细化线程激活

```
细化线程激活示例（假设4个细化线程）:

缓冲区数量    激活的线程
────────────────────────────────
    0-99      无（绿区）
  100-149     Thread 0
  150-199     Thread 0, 1
  200-249     Thread 0, 1, 2
  250-299     Thread 0, 1, 2, 3
  300+        全部 + Mutator（红区）

线程激活/停止阈值:
Thread 0: activate=100, deactivate=50
Thread 1: activate=150, deactivate=100
Thread 2: activate=200, deactivate=150
Thread 3: activate=250, deactivate=200
```

## 13.8 G1HotCardCache

### 13.8.1 热卡缓存结构

```
源码位置: src/hotspot/share/gc/g1/g1HotCardCache.hpp:56-120
```

```cpp
class G1HotCardCache: public CHeapObj<mtGC> {
  G1CollectedHeap*  _g1h;
  bool              _use_cache;
  G1CardCounts      _card_counts;

  // 热卡缓存表
  jbyte**           _hot_cache;
  size_t            _hot_cache_size;
  size_t            _hot_cache_par_chunk_size;

  volatile size_t _hot_cache_idx;
  volatile size_t _hot_cache_par_claimed_idx;

  static const int ClaimChunkSize = 32;

public:
  // 插入卡，返回需要立即细化的卡（或NULL）
  jbyte* insert(jbyte* card_ptr);

  // 排空热卡缓存
  void drain(CardTableEntryClosure* cl, uint worker_i);
};
```

### 13.8.2 热卡缓存工作原理

```
热卡缓存目的:
- 延迟频繁写入的卡的细化
- 减少写屏障开销
- 利用时间局部性

工作流程:
┌─────────────────────────────────────────────────────────────────────┐
│                         写屏障触发                                   │
│                              │                                       │
│                              ▼                                       │
│                    ┌─────────────────┐                              │
│                    │ 卡是否已脏？     │                              │
│                    └────────┬────────┘                              │
│                        Yes  │  No                                    │
│                    ┌────────┘  │                                    │
│                    │           ▼                                    │
│                    │   ┌─────────────────┐                          │
│                    │   │ 标记卡为脏      │                          │
│                    │   └────────┬────────┘                          │
│                    │            │                                    │
│                    │            ▼                                    │
│                    │   ┌─────────────────┐                          │
│                    │   │ 检查卡计数      │                          │
│                    │   │ (G1CardCounts)  │                          │
│                    │   └────────┬────────┘                          │
│                    │            │                                    │
│                    │     ┌──────┴──────┐                            │
│                    │     │             │                            │
│                    │     ▼             ▼                            │
│                    │  ┌──────┐    ┌──────────┐                      │
│                    │  │ 冷卡 │    │  热卡    │                      │
│                    │  │count<N│   │ count>=N │                      │
│                    │  └──┬───┘    └────┬─────┘                      │
│                    │     │             │                            │
│                    │     ▼             ▼                            │
│                    │  ┌──────────┐ ┌──────────────┐                 │
│                    │  │立即细化  │ │加入热卡缓存  │                 │
│                    │  │加入DCQ  │ │延迟细化      │                 │
│                    │  └──────────┘ └──────────────┘                 │
│                    │                    │                            │
│                    ▼                    ▼                            │
│                  跳过              ┌──────────────┐                  │
│                                   │ 缓存满时驱逐 │                  │
│                                   │ 被驱逐卡细化 │                  │
│                                   └──────────────┘                  │
└─────────────────────────────────────────────────────────────────────┘
```

## 13.9 写屏障与RSet更新

### 13.9.1 Post-Write Barrier

```cpp
// 伪代码：Post-Write Barrier
void post_write_barrier(oop* field, oop new_value) {
  // 1. 计算卡地址
  jbyte* card_ptr = card_table_base + ((uintptr_t)field >> 9);
  
  // 2. 检查是否已脏
  if (*card_ptr == dirty_card) {
    return;  // 已脏，跳过
  }
  
  // 3. 检查是否跨Region引用
  if (same_region(field, new_value)) {
    return;  // 同Region，跳过
  }
  
  // 4. 检查是否指向Young Region
  if (is_young_region(region_of(new_value))) {
    return;  // 指向Young，跳过（Young总是在CSet中）
  }
  
  // 5. 标记卡为脏
  *card_ptr = dirty_card;
  
  // 6. 加入脏卡队列
  enqueue_card(card_ptr);
}
```

### 13.9.2 RSet更新流程

```
RSet更新流程:

1. 并发细化（Mutator运行时）
   ┌─────────────────────────────────────────────────────────────────┐
   │ Refinement Thread:                                              │
   │   while (has_work()) {                                          │
   │     card_ptr = get_card_from_dcq();                             │
   │     refine_card_concurrently(card_ptr);                         │
   │   }                                                              │
   │                                                                  │
   │ refine_card_concurrently(card_ptr):                             │
   │   region = region_for_card(card_ptr);                           │
   │   for (obj in card_region) {                                    │
   │     for (ref in obj.references) {                               │
   │       target_region = region_of(*ref);                          │
   │       if (target_region != region) {                            │
   │         target_region.rem_set.add_reference(ref);               │
   │       }                                                          │
   │     }                                                            │
   │   }                                                              │
   └─────────────────────────────────────────────────────────────────┘

2. GC期间更新
   ┌─────────────────────────────────────────────────────────────────┐
   │ 在疏散暂停期间:                                                  │
   │   1. 排空热卡缓存                                                │
   │   2. 处理剩余DCQ缓冲区                                           │
   │   3. 扫描CSet Region的RSet                                       │
   └─────────────────────────────────────────────────────────────────┘
```

## 13.10 RSet内存开销分析

### 13.10.1 8GB堆RSet开销估算

```
8GB堆RSet内存开销分析:

基础参数:
- 堆大小: 8GB
- Region数: 2048
- Region大小: 4MB
- CardsPerRegion: 8192

每个Region的RSet开销:

1. 基础结构:
   HeapRegionRemSet: ~100字节
   OtherRegionsTable: ~200字节
   Mutex: ~50字节
   
2. Coarse Map:
   大小 = 2048位 = 256字节
   
3. Fine Grain Table:
   哈希表指针数组: 假设512个槽 × 8字节 = 4KB
   
4. 每个PerRegionTable:
   ~1060字节（含1024字节位图）
   
5. SparsePRT:
   RSHashTable: ~2KB（假设默认大小）

最小开销（每Region）: ~7KB
最大开销（每Region，满载Fine表）: ~500KB+

总堆开销估算:
- 最小: 2048 × 7KB = 14MB
- 典型: 2048 × 50KB = 100MB
- 最大: 2048 × 500KB = 1GB

RSet开销占比:
- 最小: 14MB / 8GB = 0.17%
- 典型: 100MB / 8GB = 1.2%
- 最大: 1GB / 8GB = 12.5%
```

### 13.10.2 RSet开销优化

```
RSet开销优化策略:

1. 减少跨Region引用
   - 对象分配局部性
   - 相关对象分配在同一Region
   
2. 调整RSet参数
   -XX:G1RSetRegionEntries=N     # Fine表最大条目数
   -XX:G1RSetSparseRegionEntries=N  # Sparse条目卡数
   
3. 使用Coarse粒度
   - 高引用密度Region自动升级为Coarse
   - 牺牲精度换取空间
   
4. 及时清理
   - GC后清理无效RSet条目
   - 回收空闲Region的RSet
```

## 13.11 RSet与GC性能

### 13.11.1 RSet对GC的影响

```
RSet对GC性能的影响:

1. 扫描时间
   ┌─────────────────────────────────────────────────────────────────┐
   │ RSet扫描时间 ∝ RSet大小                                         │
   │                                                                  │
   │ 扫描阶段:                                                        │
   │   - Coarse: O(regions)                                          │
   │   - Fine: O(cards_in_fine_tables)                               │
   │   - Sparse: O(sparse_entries)                                   │
   │                                                                  │
   │ 典型Young GC RSet扫描: 5-20ms                                   │
   │ Mixed GC RSet扫描: 10-50ms                                      │
   └─────────────────────────────────────────────────────────────────┘

2. 并发细化开销
   ┌─────────────────────────────────────────────────────────────────┐
   │ 细化线程CPU开销 ∝ 写入频率 × 跨Region引用比例                   │
   │                                                                  │
   │ 高写入场景:                                                      │
   │   - 更多脏卡产生                                                 │
   │   - 更多细化工作                                                 │
   │   - 可能触发红区，影响Mutator                                    │
   └─────────────────────────────────────────────────────────────────┘

3. 内存开销
   ┌─────────────────────────────────────────────────────────────────┐
   │ RSet内存 ∝ 跨Region引用数量                                     │
   │                                                                  │
   │ 高引用密度:                                                      │
   │   - 更多PRT                                                     │
   │   - 更多Coarse位                                                │
   │   - 减少可用堆空间                                               │
   └─────────────────────────────────────────────────────────────────┘
```

### 13.11.2 监控RSet

```
监控RSet的GC日志:

-Xlog:gc+remset*=debug

输出示例:
[gc,remset] Remembered Set Summary
[gc,remset]   Total per-region rem set size = 102400K
[gc,remset]     Coarse entries = 50
[gc,remset]     Fine entries = 1500
[gc,remset]     Sparse entries = 300
[gc,remset]   Static structures = 1024K
[gc,remset]   Free list = 512K

关键指标:
- Total per-region rem set size: RSet总大小
- Coarse entries: 粗粒度条目数（高值可能表示引用过于分散）
- Fine entries: 细粒度条目数
- Sparse entries: 稀疏条目数
```

## 13.12 本章小结

本章详细分析了G1的RSet和卡表实现：

1. **卡表**：512字节粒度，8GB堆需要16MB卡表，支持多种卡状态

2. **HeapRegionRemSet**：每个Region的记忆集，包含OtherRegionsTable和代码根集合

3. **三级RSet结构**：
   - Coarse Map：每Region一位，256字节
   - Fine Grain Table：每Region一个PRT，1KB位图
   - SparsePRT：少量引用的优化存储

4. **DirtyCardQueue**：线程本地脏卡队列，批量提交到全局队列

5. **G1ConcurrentRefine**：三区域机制（绿/黄/红），渐进激活细化线程

6. **G1HotCardCache**：热卡缓存，延迟频繁写入卡的细化

7. **内存开销**：8GB堆典型RSet开销约100MB（1.2%）

**关键源码文件**：
- `heapRegionRemSet.hpp/cpp`: RSet核心实现
- `g1CardTable.hpp`: G1卡表
- `sparsePRT.hpp`: 稀疏PRT
- `g1RemSet.hpp`: RemSet管理
- `dirtyCardQueue.hpp`: 脏卡队列
- `g1ConcurrentRefine.hpp`: 并发细化
- `g1HotCardCache.hpp`: 热卡缓存
