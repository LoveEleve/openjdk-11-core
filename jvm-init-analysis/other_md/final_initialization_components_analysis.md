# G1 GC最终初始化组件深度分析

## 📋 目录
1. [概述与背景](#概述与背景)
2. [虚拟Region（Dummy Region）](#虚拟region)
3. [G1AllocRegion分配区域系统](#g1allocregion分配区域系统)
4. [G1Allocator分配器初始化](#g1allocator分配器初始化)
5. [G1MonitoringSupport监控支持](#g1monitoringsupport监控支持)
6. [G1StringDedup字符串去重](#g1stringdedup字符串去重)
7. [PreservedMarksSet保留标记集](#preservedmarksset保留标记集)
8. [G1CollectionSet收集集合](#g1collectionset收集集合)
9. [初始化顺序与依赖关系](#初始化顺序与依赖关系)
10. [内存开销估算](#内存开销估算)
11. [总结](#总结)

---

## 概述与背景

### 🎯 初始化的最终阶段

在完成了核心基础设施（SATB队列、脏卡队列、并发优化线程）的初始化后，G1 GC需要初始化一系列关键组件，为堆的正常运行做好准备：

```cpp
// 文件：g1CollectedHeap.cpp，第1949-1972行

// 1. 获取虚拟Region
HeapRegion* dummy_region = _hrm.get_dummy_region();

// 2. 配置虚拟Region
dummy_region->set_eden();
dummy_region->set_top(dummy_region->end());

// 3. 设置G1AllocRegion全局静态字段
G1AllocRegion::setup(this, dummy_region);

// 4. 初始化mutator分配区域
_allocator->init_mutator_alloc_region();

// 5. 创建监控支持
_g1mm = new G1MonitoringSupport(this);

// 6. 初始化字符串去重
G1StringDedup::initialize();

// 7. 初始化保留标记集
_preserved_marks_set.init(ParallelGCThreads);

// 8. 初始化收集集合
_collection_set.initialize(max_regions());

return JNI_OK;
```

### 📊 组件概览

| 组件 | 作用 | 关键特性 |
|------|------|---------|
| **虚拟Region** | G1AllocRegion的哨兵对象 | 永远满的Region，避免NULL检查 |
| **G1AllocRegion** | 分配区域抽象基类 | 无锁快速路径，统一的分配接口 |
| **G1Allocator** | 堆分配器 | 管理mutator、survivor、old三种分配区域 |
| **G1MonitoringSupport** | JMX监控支持 | 暴露堆统计信息给管理工具 |
| **G1StringDedup** | 字符串去重 | 减少重复String对象的内存占用 |
| **PreservedMarksSet** | 保留标记集 | Full GC期间保存对象的mark word |
| **G1CollectionSet** | 收集集合 | 管理GC停顿时要回收的Region集合 |

---

## 虚拟Region

### 🎯 什么是虚拟Region？

**虚拟Region**（Dummy Region）是一个特殊的`HeapRegion`对象，它**永远是满的**（`top == end`），用作`G1AllocRegion`的哨兵值，避免频繁的NULL检查。

### 🏗️ 虚拟Region的获取

```cpp
// 文件：heapRegionManager.hpp，第148行
HeapRegion* get_dummy_region() { 
    return new_heap_region(0); 
}
```

**关键点**：
- 虚拟Region是**索引为0的HeapRegion**
- 由于堆从最低地址开始提交，索引0的Region总是可用的
- 调用`new_heap_region(0)`创建，复用已有的Region创建逻辑

### 📝 虚拟Region的配置

```cpp
// 文件：g1CollectedHeap.cpp，第1951-1959行

HeapRegion* dummy_region = _hrm.get_dummy_region();

// 1. 标记为Eden类型
dummy_region->set_eden();

// 2. 设置top指针指向end
dummy_region->set_top(dummy_region->end());
```

**为什么设置为Eden？**

注释解释了原因：
```cpp
// We'll re-use the same region whether the alloc region will
// require BOT updates or not and, if it doesn't, then a non-young
// region will complain that it cannot support allocations without
// BOT updates. So we'll tag the dummy region as eden to avoid that.
```

**翻译**：
- Eden Region支持不更新BOT（Bottom Offset Table）的分配
- 非年轻代Region不支持这种分配，会触发断言失败
- 将虚拟Region标记为Eden，避免断言失败

**为什么设置为满（top = end）？**
- 确保任何分配尝试都会失败
- 作为哨兵值使用时，不会意外分配内存

### 🔍 虚拟Region的验证

```cpp
// 文件：g1AllocRegion.cpp，第38-49行

void G1AllocRegion::setup(G1CollectedHeap* g1h, HeapRegion* dummy_region) {
  assert(_dummy_region == NULL, "should be set once");
  assert(dummy_region != NULL, "pre-condition");
  assert(dummy_region->free() == 0, "pre-condition");  // 必须是满的
  
  // 验证分配会失败
  assert(dummy_region->allocate_no_bot_updates(1) == NULL, "should fail");
  assert(dummy_region->allocate(1) == NULL, "should fail");
  
  DEBUG_ONLY(size_t assert_tmp);
  assert(dummy_region->par_allocate_no_bot_updates(1, 1, &assert_tmp) == NULL, "should fail");
  assert(dummy_region->par_allocate(1, 1, &assert_tmp) == NULL, "should fail");
  
  _g1h = g1h;
  _dummy_region = dummy_region;
}
```

**关键验证**：
1. 虚拟Region只能设置一次（单例模式）
2. 虚拟Region的`free()`必须为0（完全满）
3. 所有类型的分配操作都必须失败
4. 保存全局静态变量`_g1h`和`_dummy_region`

### 🎯 虚拟Region的使用场景

#### 场景1：初始化时避免NULL

```cpp
// 文件：g1AllocRegion.hpp，第46-53行

// The invariant is that if this object is initialized (i.e.,
// init() has been called and release() has not) then _alloc_region
// is either an active allocating region or the dummy region (i.e.,
// it can never be NULL)

HeapRegion* volatile _alloc_region;
```

**不变式保证**：
- 初始化后，`_alloc_region`要么指向活跃Region，要么指向虚拟Region
- **永远不会是NULL**
- 避免每次分配时检查NULL

#### 场景2：退出活跃区域时

```cpp
// 文件：g1AllocRegion.cpp，第118-132行

size_t G1AllocRegion::retire(bool fill_up) {
  assert_alloc_region(_alloc_region != NULL, "not initialized properly");
  
  size_t waste = 0;
  trace("retiring");
  
  HeapRegion* alloc_region = _alloc_region;
  if (alloc_region != _dummy_region) {
    // 只有非虚拟Region才需要退出处理
    waste = retire_internal(alloc_region, fill_up);
    reset_alloc_region();  // 重置为虚拟Region
  }
  
  trace("retired");
  return waste;
}

// reset_alloc_region实现
void G1AllocRegion::reset_alloc_region() {
  _alloc_region = _dummy_region;  // 指向虚拟Region
}
```

**关键逻辑**：
- 退出Region时，检查是否为虚拟Region
- 如果不是，执行实际的退出操作
- 最后重置为虚拟Region，保持不变式

### 📊 虚拟Region的特性总结

| 特性 | 值/说明 |
|------|--------|
| **索引** | 0 |
| **类型** | Eden |
| **大小** | 标准HeapRegion大小（如2MB） |
| **top指针** | 等于end（完全满） |
| **free空间** | 0字节 |
| **分配行为** | 所有分配都失败 |
| **生命周期** | 堆初始化到销毁 |
| **内存开销** | ~2MB（一个Region的元数据） |

---

## G1AllocRegion分配区域系统

### 🎯 G1AllocRegion概述

`G1AllocRegion`是一个**抽象基类**，提供了统一的、无锁的快速分配接口。它是G1分配系统的核心抽象，所有具体的分配区域（mutator、survivor、old）都继承自它。

### 🏗️ 类层次结构

```
G1AllocRegion (抽象基类)
    ↓
┌─────────────────────────────────────────────────────┐
│                                                     │
MutatorAllocRegion       SurvivorGCAllocRegion    OldGCAllocRegion
(应用线程分配)            (GC survivor分配)        (GC old分配)
```

### 📝 核心数据结构

```cpp
// 文件：g1AllocRegion.hpp，第41-81行

class G1AllocRegion {
private:
  // 当前活跃的分配Region
  HeapRegion* volatile _alloc_region;
  
  // 跟踪使用了多少个不同的Region
  uint _count;
  
  // 设置新Region时的已使用字节数（用于计算分配量）
  size_t _used_bytes_before;
  
  // 是否需要更新BOT（Bottom Offset Table）
  const bool _bot_updates;
  
  // 用于调试和跟踪的名称
  const char* _name;
  
  // 全局静态：虚拟Region
  static HeapRegion* _dummy_region;
  
  // 全局静态：G1堆引用
  static G1CollectedHeap* _g1h;
  
protected:
  // 子类必须实现：分配新Region
  virtual HeapRegion* allocate_new_region(size_t word_size, bool force) = 0;
  
  // 子类必须实现：退出Region
  virtual void retire_region(HeapRegion* alloc_region, size_t allocated_bytes) = 0;
  
public:
  static void setup(G1CollectedHeap* g1h, HeapRegion* dummy_region);
  
  HeapRegion* get() const { return _alloc_region; }
  
  // 快速路径：无锁分配
  inline HeapWord* attempt_allocation(size_t word_size);
  inline HeapWord* attempt_allocation_locked(size_t word_size);
  inline HeapWord* attempt_allocation_force(size_t word_size);
};
```

### ⚡ 快速分配路径

#### 无锁快速分配

```cpp
// 文件：g1AllocRegion.inline.hpp

inline HeapWord* G1AllocRegion::attempt_allocation(size_t word_size) {
  assert(get() != NULL, "should be initialized");
  
  HeapRegion* alloc_region = _alloc_region;
  assert(alloc_region != NULL, "not initialized properly");
  
  // 快速路径：尝试从当前Region分配
  HeapWord* result = par_allocate(alloc_region, word_size);
  
  if (result != NULL) {
    // 成功分配，增加计数
    trace("alloc", word_size, result);
    return result;
  }
  
  // 失败，返回NULL（调用者会尝试慢速路径）
  trace("alloc failed", word_size);
  return NULL;
}
```

**关键特性**：
- **完全无锁**：使用原子CAS操作更新`top`指针
- **极快**：成功路径仅需几条指令
- **失败快速返回**：Region满时立即返回NULL

#### par_allocate实现

```cpp
// 文件：heapRegion.inline.hpp

inline HeapWord* HeapRegion::par_allocate(size_t word_size) {
  HeapWord* obj = top();
  HeapWord* new_top = obj + word_size;
  HeapWord* end = this->end();
  
  // 检查空间是否足够
  if (new_top > end) {
    return NULL;
  }
  
  // 原子CAS更新top指针
  HeapWord* result = Atomic::cmpxchg(new_top, top_addr(), obj);
  
  if (result == obj) {
    // CAS成功
    assert(is_in(obj) && is_in(new_top - 1), "post-condition");
    return obj;
  } else {
    // CAS失败，其他线程先分配了
    return NULL;
  }
}
```

**性能**：
- 成功路径：**3-5条指令**
- 延迟：**1-2ns**
- 吞吐量：**每核心~1B次分配/秒**

#### 慢速路径：分配新Region

```cpp
// 文件：g1AllocRegion.cpp，第134-180行

HeapWord* G1AllocRegion::new_alloc_region_and_allocate(size_t word_size, bool force) {
  assert_alloc_region(_alloc_region == _dummy_region, "pre-condition");
  assert_alloc_region(_alloc_region != NULL, "not initialized properly");
  
  // 1. 尝试分配新Region
  HeapRegion* new_alloc_region = allocate_new_region(word_size, force);
  if (new_alloc_region != NULL) {
    // 2. 设置为活跃Region
    update_alloc_region(new_alloc_region);
    
    // 3. 从新Region分配
    HeapWord* result = allocate(new_alloc_region, word_size);
    assert_alloc_region(result != NULL, "the allocation should succeeded");
    
    trace("alloc", word_size, result);
    return result;
  } else {
    trace("alloc failed (region allocation failed)");
    return NULL;
  }
}
```

### 🔄 Region生命周期管理

#### 初始化

```cpp
void G1AllocRegion::init() {
  assert(_alloc_region == NULL && _count == 0, "pre-condition");
  
  // 初始化为虚拟Region
  _alloc_region = _dummy_region;
  _count = 0;
}
```

#### 退出并填充

```cpp
// 文件：g1AllocRegion.cpp，第55-96行

size_t G1AllocRegion::fill_up_remaining_space(HeapRegion* alloc_region) {
  size_t free_word_size = alloc_region->free() / HeapWordSize;
  size_t min_word_size_to_fill = CollectedHeap::min_fill_size();
  
  while (free_word_size >= min_word_size_to_fill) {
    // 尝试分配剩余空间
    HeapWord* dummy = par_allocate(alloc_region, free_word_size);
    
    if (dummy != NULL) {
      // 填充dummy对象
      CollectedHeap::fill_with_object(dummy, free_word_size);
      alloc_region->set_pre_dummy_top(dummy);
      return free_word_size * HeapWordSize;
    }
    
    // 其他线程可能分配了部分空间，重试
    free_word_size = alloc_region->free() / HeapWordSize;
  }
  
  return alloc_region->free();
}
```

**为什么填充？**
- 确保没有浪费的空间
- 其他线程可能仍在尝试CAS分配
- 填充dummy对象，保持堆可遍历性

### 📊 G1AllocRegion的性能特征

| 指标 | 快速路径 | 慢速路径 |
|------|---------|---------|
| **延迟** | 1-2ns | 1-10μs |
| **锁** | 无 | 有（堆锁） |
| **频率** | 99%+ | <1% |
| **操作** | 原子CAS | 分配新Region + 初始化 |

---

## G1Allocator分配器初始化

### 🎯 G1Allocator概述

`G1Allocator`是G1堆的**总分配器**，管理三种不同用途的分配区域：

1. **MutatorAllocRegion**：应用线程的日常分配（Eden）
2. **SurvivorGCAllocRegion**：GC期间存活对象的分配（Survivor）
3. **OldGCAllocRegion**：GC期间晋升对象的分配（Old）

### 🏗️ G1Allocator数据结构

```cpp
// 文件：g1Allocator.hpp，第38-58行

class G1Allocator : public CHeapObj<mtGC> {
private:
  G1CollectedHeap* _g1h;
  
  bool _survivor_is_full;  // Survivor区域是否已满
  bool _old_is_full;       // Old区域是否已满
  
  // 应用线程分配区域
  MutatorAllocRegion _mutator_alloc_region;
  
  // GC期间survivor对象分配区域
  SurvivorGCAllocRegion _survivor_gc_alloc_region;
  
  // GC期间old对象分配区域
  OldGCAllocRegion _old_gc_alloc_region;
  
  // 保留的old GC分配Region（跨GC复用）
  HeapRegion* _retained_old_gc_alloc_region;
  
public:
  G1Allocator(G1CollectedHeap* heap);
  
  void init_mutator_alloc_region();
  void release_mutator_alloc_region();
  
  void init_gc_alloc_regions(EvacuationInfo& evacuation_info);
  void release_gc_alloc_regions(EvacuationInfo& evacuation_info);
};
```

### 📝 MutatorAllocRegion初始化

```cpp
// 文件：g1Allocator.cpp，第45-48行

void G1Allocator::init_mutator_alloc_region() {
  assert(_mutator_alloc_region.get() == NULL, "pre-condition");
  _mutator_alloc_region.init();
}
```

**init()调用链**：
```
G1Allocator::init_mutator_alloc_region()
    ↓
MutatorAllocRegion::init()
    ↓
G1AllocRegion::init()
    ↓
_alloc_region = _dummy_region;  // 设置为虚拟Region
_count = 0;
```

**初始化后的状态**：
```
MutatorAllocRegion {
  _alloc_region = _dummy_region  // 指向虚拟Region
  _count = 0
  _used_bytes_before = 0
  _bot_updates = true  // Mutator需要更新BOT
  _name = "Mutator Alloc Region"
}
```

### 🔄 Mutator分配区域的生命周期

#### 首次分配时

```cpp
// 应用线程分配对象
HeapWord* obj = heap->mem_allocate(size);
    ↓
G1Allocator::attempt_allocation(size)
    ↓
_mutator_alloc_region.attempt_allocation(size)
    ↓
// 当前是虚拟Region，分配失败
    ↓
attempt_allocation_locked(size)  // 慢速路径
    ↓
new_alloc_region_and_allocate(size)
    ↓
allocate_new_region(size)  // 从堆分配新的Eden Region
    ↓
update_alloc_region(new_region)  // 设置为活跃Region
    ↓
allocate(new_region, size)  // 从新Region分配
```

#### GC后

```cpp
// GC结束后
void G1CollectedHeap::post_evacuate_collection_set(...) {
  // ...其他清理工作...
  
  // 重新初始化mutator分配区域
  _allocator->init_mutator_alloc_region();
}
```

### 🎯 为什么需要三种分配区域？

| 分配区域 | 使用时机 | 分配目标 | BOT更新 |
|----------|---------|---------|---------|
| **Mutator** | 应用线程运行时 | Eden Region | 是 |
| **Survivor GC** | GC停顿期间 | Survivor Region | 否（GC后重建） |
| **Old GC** | GC停顿期间 | Old Region | 否（GC后重建） |

**分离的好处**：
1. **避免竞争**：应用线程和GC线程使用不同的Region
2. **简化同步**：GC期间应用线程暂停，GC分配区域无需并发控制
3. **优化性能**：GC分配可以跳过BOT更新（GC后统一重建）

### 📊 Mutator分配性能

```
快速路径（Region未满）：
├── 延迟：1-2ns
├── 吞吐量：~1B次分配/秒/核心
└── 成功率：>99%

慢速路径（需要新Region）：
├── 延迟：1-10μs
├── 操作：堆锁 + Region分配 + 初始化
└── 频率：每2MB一次（假设Region大小2MB）
```

---

## G1MonitoringSupport监控支持

### 🎯 G1MonitoringSupport概述

`G1MonitoringSupport`负责提供G1堆的**JMX监控数据**，暴露给Java管理工具（如jstat、jconsole、VisualVM）。

### 🏗️ 监控的内存池

G1堆被划分为三个逻辑内存池：

```
┌─────────────────────────────────────────────┐
│            G1 Heap (物理视图)                │
│  ┌────────┬────────┬────────┬────────┐      │
│  │ Eden   │Survivor│  Old   │  Old   │ ...  │
│  └────────┴────────┴────────┴────────┘      │
└─────────────────────────────────────────────┘
             ↓ 逻辑划分
┌─────────────────────────────────────────────┐
│        Young Generation (逻辑视图)           │
│  ┌────────────────┬──────────────┐          │
│  │  Eden Space    │ Survivor     │          │
│  └────────────────┴──────────────┘          │
└─────────────────────────────────────────────┘
┌─────────────────────────────────────────────┐
│          Old Generation (逻辑视图)           │
│  ┌─────────────────────────────────┐        │
│  │      Old Space                  │        │
│  └─────────────────────────────────┘        │
└─────────────────────────────────────────────┘
```

### 📝 G1MonitoringSupport数据结构

```cpp
// 文件：g1MonitoringSupport.hpp，第117-194行

class G1MonitoringSupport : public CHeapObj<mtGC> {
private:
  G1CollectedHeap* _g1h;
  
  // jstat性能计数器
  CollectorCounters* _incremental_collection_counters;  // 增量GC
  CollectorCounters* _full_collection_counters;         // Full GC
  CollectorCounters* _conc_collection_counters;         // 并发标记
  
  // Generation计数器
  GenerationCounters* _young_collection_counters;
  GenerationCounters* _old_collection_counters;
  
  // Space计数器
  HSpaceCounters* _old_space_counters;
  HSpaceCounters* _eden_counters;
  HSpaceCounters* _from_counters;
  HSpaceCounters* _to_counters;
  
  // 缓存的统计信息（避免频繁重新计算）
  size_t _overall_reserved;  // 总保留内存
  size_t _overall_committed; // 总提交内存
  size_t _overall_used;      // 总使用内存
  
  uint   _young_region_num;
  size_t _young_gen_committed;
  size_t _eden_committed;
  size_t _eden_used;
  size_t _survivor_committed;
  size_t _survivor_used;
  
  size_t _old_committed;
  size_t _old_used;
  
public:
  G1MonitoringSupport(G1CollectedHeap* g1h);
  
  // 重新计算所有大小
  void recalculate_sizes();
  
  // 仅重新计算Eden（分配新Eden Region时）
  void recalculate_eden_size();
};
```

### 🔧 初始化过程

```cpp
// 文件：g1MonitoringSupport.cpp，第78-100行

G1MonitoringSupport::G1MonitoringSupport(G1CollectedHeap* g1h) :
  _g1h(g1h),
  _incremental_collection_counters(NULL),
  _full_collection_counters(NULL),
  _conc_collection_counters(NULL),
  _old_collection_counters(NULL),
  _old_space_counters(NULL),
  _young_collection_counters(NULL),
  _eden_counters(NULL),
  _from_counters(NULL),
  _to_counters(NULL),
  
  _overall_reserved(0),
  _overall_committed(0),
  _overall_used(0),
  _young_region_num(0),
  _young_gen_committed(0),
  _eden_committed(0),
  _eden_used(0),
  _survivor_committed(0),
  _survivor_used(0),
  _old_committed(0),
  _old_used(0) {
  
  _overall_reserved = g1h->max_capacity();
  recalculate_sizes();
  
  // 创建各种计数器（如果UsePerfData启用）
  if (UsePerfData) {
    _incremental_collection_counters = new CollectorCounters("G1 incremental collections", 0);
    _full_collection_counters = new CollectorCounters("G1 stop-the-world full collections", 1);
    _conc_collection_counters = new CollectorCounters("G1 concurrent cycle pauses", 2);
    
    _young_collection_counters = new G1YoungGenerationCounters(this, "young");
    _old_collection_counters = new G1OldGenerationCounters(this, "old");
    
    _eden_counters = new HSpaceCounters("eden", 0, ...);
    _from_counters = new HSpaceCounters("s0", 1, ...);
    _to_counters = new HSpaceCounters("s1", 2, ...);
    _old_space_counters = new HSpaceCounters("old", 3, ...);
  }
}
```

### 📊 统计信息的更新

#### 完整重新计算（GC后）

```cpp
void G1MonitoringSupport::recalculate_sizes() {
  // 1. 总体统计
  _overall_committed = _g1h->capacity();
  _overall_used = _g1h->used_unlocked();
  
  // 2. 年轻代统计
  uint eden_list_length = _g1h->eden_regions_count();
  uint survivor_list_length = _g1h->survivor_regions_count();
  _young_region_num = eden_list_length + survivor_list_length;
  
  size_t region_size = HeapRegion::GrainBytes;
  _eden_committed = eden_list_length * region_size;
  _survivor_committed = survivor_list_length * region_size;
  _young_gen_committed = _eden_committed + _survivor_committed;
  
  _eden_used = calculate_eden_used();
  _survivor_used = calculate_survivor_used();
  
  // 3. 老年代统计
  _old_committed = _overall_committed - _young_gen_committed;
  _old_used = _overall_used - _eden_used - _survivor_used;
  
  // 4. 更新所有计数器
  if (UsePerfData) {
    _young_collection_counters->update_all();
    _old_collection_counters->update_all();
    _eden_counters->update_all();
    _to_counters->update_all();
    _old_space_counters->update_all();
  }
}
```

#### 快速更新（仅Eden）

```cpp
void G1MonitoringSupport::recalculate_eden_size() {
  uint eden_list_length = _g1h->eden_regions_count();
  size_t region_size = HeapRegion::GrainBytes;
  
  _eden_committed = eden_list_length * region_size;
  _eden_used = calculate_eden_used();
  
  if (UsePerfData) {
    _eden_counters->update_all();
  }
}
```

### 🎯 为什么需要缓存？

**问题**：G1的Region动态分配，统计信息计算代价高

**解决方案**：缓存计算结果，仅在关键时刻更新

**更新时机**：
1. **GC结束时**：调用`recalculate_sizes()`完整更新
2. **分配新Eden Region时**：调用`recalculate_eden_size()`快速更新
3. **JMX查询时**：直接返回缓存值，无需重新计算

### 📈 暴露给jstat的指标

```bash
# jstat -gc <pid> 1000

 S0C    S1C    S0U    S1U      EC       EU        OC         OU       MC     MU    
10240.0 10240.0  0.0  8192.0 102400.0 45000.0  409600.0  123000.0  51200.0 48000.0
```

| 指标 | 含义 | 对应字段 |
|------|------|---------|
| **S0C** | Survivor 0容量 | `_from_counters->capacity()` |
| **S0U** | Survivor 0使用量 | `_from_counters->used()` |
| **EC** | Eden容量 | `_eden_counters->capacity()` |
| **EU** | Eden使用量 | `_eden_counters->used()` |
| **OC** | Old容量 | `_old_space_counters->capacity()` |
| **OU** | Old使用量 | `_old_space_counters->used()` |

### 💡 与传统GC的差异

| 方面 | 传统GC（CMS/ParallelGC） | G1 GC |
|------|-------------------------|-------|
| **内存布局** | 固定的连续区域 | 动态的不连续Region集合 |
| **统计计算** | O(1)，直接读取指针 | O(n)，需遍历Region列表 |
| **更新频率** | 每次GC | 每次GC + 每次Eden分配 |
| **缓存需求** | 不需要 | 必须缓存 |

---

## G1StringDedup字符串去重

### 🎯 字符串去重概述

**字符串去重**（String Deduplication）是G1 GC的一个可选特性，自动识别并合并内容相同的`String`对象的底层`char[]`数组，减少内存占用。

### 📊 为什么需要字符串去重？

**现实场景**：
```java
// 应用代码
String s1 = new String("Hello");
String s2 = new String("Hello");
String s3 = someObject.toString();  // 也返回"Hello"

// 堆内存状态（去重前）
String对象1 → char[]数组1: ['H','e','l','l','o']  // 10字节
String对象2 → char[]数组2: ['H','e','l','l','o']  // 10字节
String对象3 → char[]数组3: ['H','e','l','l','o']  // 10字节
总计：30字节的char[]数组

// 堆内存状态（去重后）
String对象1 ↘
String对象2 → char[]数组: ['H','e','l','l','o']  // 10字节
String对象3 ↗
总计：10字节的char[]数组，节省20字节
```

**统计数据**：
- 某些应用中，**20-30%的堆内存**被`String`对象占用
- 其中**30-50%的`String`对象内容重复**
- 去重可以节省**5-15%的总堆内存**

### 🔧 初始化

```cpp
// 文件：g1StringDedup.cpp，第39-42行

void G1StringDedup::initialize() {
  assert(UseG1GC, "String deduplication available with G1");
  StringDedup::initialize_impl<G1StringDedupQueue, G1StringDedupStat>();
}
```

**初始化条件**：
```bash
# 启用字符串去重
-XX:+UseG1GC -XX:+UseStringDeduplication
```

**初始化的组件**：
1. **G1StringDedupQueue**：去重候选队列
2. **G1StringDedupStat**：去重统计信息
3. **StringDedupThread**：后台去重线程
4. **StringDedupTable**：去重哈希表

### 🔄 字符串去重流程

```
应用创建String对象
    ↓
对象在Eden Region中
    ↓
经过StringDeduplicationAgeThreshold次GC（默认3次）
    ↓
对象晋升到Old时被标记为去重候选
    ↓
G1StringDedupQueue::enqueue(oop obj)
    ↓
StringDedupThread后台处理
    ↓
查询StringDedupTable
    ↓
┌────────────────────────────┐
│ 已存在相同内容的char[]？     │
└────────────────────────────┘
    │                    │
   是                   否
    ↓                    ↓
更新String对象指向      添加到StringDedupTable
已有char[]             
    ↓                    ↓
原char[]可被GC回收      完成
```

### 📝 候选对象的识别

```cpp
// 文件：g1StringDedup.cpp，第44-54行

bool G1StringDedup::is_candidate_from_mark(oop obj) {
  if (java_lang_String::is_instance_inlined(obj)) {
    bool from_young = G1CollectedHeap::heap()->heap_region_containing(obj)->is_young();
    
    if (from_young && obj->age() < StringDeduplicationAgeThreshold) {
      // 候选：从年轻代疏散到老年代，但年龄未达到去重阈值
      // 即：在年轻代期间从未成为候选对象
      return true;
    }
  }
  return false;
}
```

**去重候选条件**：
1. 对象是`java.lang.String`实例
2. 对象正在从年轻代晋升到老年代
3. 对象年龄 < `StringDeduplicationAgeThreshold`（默认3）

**为什么有年龄阈值？**
- 太年轻的对象很可能很快死亡
- 等待几次GC，确保对象足够"稳定"
- 避免去重刚创建就死亡的临时String

### 🎯 去重表（StringDedupTable）

**数据结构**：哈希表，键是`char[]`内容的哈希值

```
StringDedupTable
    ↓
┌──────────────────────────────────┐
│  Hash  │  char[]数组           │
├──────────────────────────────────┤
│  0x1234│  ['H','e','l','l','o'] │
│  0x5678│  ['W','o','r','l','d'] │
│  0xABCD│  ['F','o','o']         │
│  ...   │  ...                   │
└──────────────────────────────────┘
```

**去重操作**：
```cpp
void StringDedupThread::do_dedup(oop java_string) {
  typeArrayOop value = java_lang_String::value(java_string);
  
  // 计算内容哈希
  unsigned int hash = compute_hash(value);
  
  // 查询去重表
  typeArrayOop existing = _table->lookup(value, hash);
  
  if (existing != NULL) {
    // 找到重复，更新String对象指向
    java_lang_String::set_value(java_string, existing);
    // 原value数组会被GC回收
  } else {
    // 首次出现，添加到表
    _table->add(value, hash);
  }
}
```

### 📊 性能与开销

#### 内存开销

```
StringDedupTable：
├── 表大小：动态增长（默认初始1MB）
├── 每项开销：~24字节
└── 队列开销：~1MB

StringDedupThread：
├── 线程栈：~1MB
└── 工作缓冲区：~100KB

总计：~3MB
```

#### CPU开销

```
StringDedupThread：
├── CPU使用率：0.5-2%（后台运行）
├── 处理速率：~1M个String/秒
└── 不影响应用线程
```

#### 内存节省

```
典型效果（取决于应用）：
├── 最佳情况：节省15-20%堆内存
├── 一般情况：节省5-10%堆内存
└── 最差情况：几乎无节省
```

### 🎛️ 相关JVM参数

| 参数 | 默认值 | 说明 |
|------|-------|------|
| **UseStringDeduplication** | false | 启用字符串去重 |
| **StringDeduplicationAgeThreshold** | 3 | 成为候选对象的最小年龄 |
| **StringDeduplicationResizeALot** | false | 调试选项：频繁调整表大小 |
| **StringDeduplicationRehashALot** | false | 调试选项：频繁重新哈希 |

### 💡 使用建议

**适合启用字符串去重的场景**：
- ✅ 大量重复字符串（如配置文件、JSON、XML解析）
- ✅ 长期运行的服务器应用
- ✅ 内存敏感的应用

**不适合启用的场景**：
- ❌ 字符串很少重复
- ❌ 短期运行的批处理任务
- ❌ CPU敏感的应用

---

## PreservedMarksSet保留标记集

### 🎯 保留标记集概述

`PreservedMarksSet`用于在**Full GC期间**保存对象的`mark word`（对象头），以便在GC完成后恢复。

### 🤔 为什么需要保存mark word？

**问题**：Full GC期间，对象可能被移动，`mark word`被临时用于存储转发指针。

#### Mark Word的用途

```
正常情况下的mark word（64位）：
┌────────────────────────────────────────────────────────────┐
│ unused:25 | age:4 | biased_lock:1 | lock:2 | hash_code:32 │
└────────────────────────────────────────────────────────────┘

Full GC期间的mark word（存储转发指针）：
┌────────────────────────────────────────────────────────────┐
│                  forwarding_pointer:62                   | 2│
└────────────────────────────────────────────────────────────┘
```

**转发指针**：
- Full GC的**Mark-Sweep-Compact**算法需要移动对象
- 旧位置的对象的`mark word`被替换为指向新位置的指针
- 引用更新阶段通过转发指针找到对象的新位置

**问题**：
- 原始的`mark word`丢失了（锁状态、哈希码、GC年龄等）
- GC完成后需要恢复这些信息

**解决方案**：
- GC前：保存`mark word`到`PreservedMarksSet`
- GC移动对象：`mark word`存储转发指针
- GC后：从`PreservedMarksSet`恢复`mark word`

### 🏗️ PreservedMarksSet数据结构

```cpp
// 文件：preservedMarks.hpp，第100-133行

class PreservedMarksSet : public CHeapObj<mtGC> {
private:
  // true：栈分配在C堆
  // false：栈分配在resource arena
  const bool _in_c_heap;
  
  // 栈数组的大小（通常等于GC worker线程数）
  uint _num;
  
  // 栈数组（通常每个GC worker一个栈）
  Padded<PreservedMarks>* _stacks;
  
public:
  PreservedMarksSet(bool in_c_heap) : _in_c_heap(in_c_heap), _num(0), _stacks(NULL) { }
  
  void init(uint num);
  
  // 获取第i个栈
  PreservedMarks* get(uint i = 0) const {
    assert(_num > 0 && _stacks != NULL, "should have been initialized");
    assert(i < _num, "pre-condition");
    return (_stacks + i);
  }
};
```

#### 单个栈：PreservedMarks

```cpp
// 文件：preservedMarks.hpp，第36-80行

class PreservedMarks {
private:
  class OopAndMarkOop {
  private:
    oop _o;         // 对象指针
    markOop _m;     // 保存的mark word
    
  public:
    OopAndMarkOop(oop obj, markOop m) : _o(obj), _m(m) { }
    oop get_oop() { return _o; }
    void set_mark() const { _o->set_mark(_m); }  // 恢复mark word
  };
  
  typedef Stack<OopAndMarkOop, mtGC> OopAndMarkOopStack;
  
  OopAndMarkOopStack _stack;
  
public:
  // 保存对象的mark word
  inline void push(oop obj, markOop m);
  
  // 恢复所有mark word
  void restore();
};
```

### 📝 初始化过程

```cpp
// 文件：preservedMarks.cpp，第78-88行

void PreservedMarksSet::init(uint num) {
  assert(_stacks == NULL && _num == 0, "do not re-initialize");
  assert(num > 0, "pre-condition");
  
  if (_in_c_heap) {
    _stacks = NEW_C_HEAP_ARRAY(Padded<PreservedMarks>, num, mtGC);
  } else {
    _stacks = NEW_RESOURCE_ARRAY(Padded<PreservedMarks>, num);
  }
  
  for (uint i = 0; i < num; i++) {
    ::new (_stacks + i) PreservedMarks();  // placement new
  }
  
  _num = num;
}
```

**G1初始化调用**：
```cpp
// 文件：g1CollectedHeap.cpp，第1970行
_preserved_marks_set.init(ParallelGCThreads);
```

**初始化后的结构**（假设8个GC线程）：

```
PreservedMarksSet
    ↓
┌─────────────────────────────────────────────┐
│  _num = 8                                   │
│  _in_c_heap = true                          │
│  _stacks:                                   │
│    ┌─────────────────────────────────────┐ │
│    │ [0]: PreservedMarks (worker 0的栈)  │ │
│    │ [1]: PreservedMarks (worker 1的栈)  │ │
│    │ [2]: PreservedMarks (worker 2的栈)  │ │
│    │ ...                                  │ │
│    │ [7]: PreservedMarks (worker 7的栈)  │ │
│    └─────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 🔄 Full GC中的使用

#### 第1阶段：标记（Mark）

```cpp
// 并行标记活跃对象（无需保存mark word）
void MarkSweep::mark_sweep_phase1(...) {
  // 遍历GC roots，标记可达对象
  // mark word的lock bits用于标记状态
}
```

#### 第2阶段：计算新地址（Compute New Addresses）

```cpp
void MarkSweep::mark_sweep_phase2(..., PreservedMarksSet* preserved_marks) {
  // 遍历堆
  for (each live object) {
    // 1. 计算对象的新地址
    HeapWord* new_addr = calculate_new_address(obj);
    
    // 2. 保存原始mark word
    markOop mark = obj->mark();
    preserved_marks->get(worker_id)->push(obj, mark);
    
    // 3. 在mark word中存储转发指针
    obj->set_mark(markOopDesc::encode_pointer_as_mark(new_addr));
  }
}
```

#### 第3阶段：更新引用（Adjust Pointers）

```cpp
void MarkSweep::mark_sweep_phase3(...) {
  // 遍历所有引用
  for (each reference) {
    oop old_obj = *reference;
    
    // 通过转发指针找到新地址
    oop new_obj = oop(old_obj->mark()->decode_pointer());
    
    // 更新引用
    *reference = new_obj;
  }
}
```

#### 第4阶段：移动对象（Compact）

```cpp
void MarkSweep::mark_sweep_phase4(...) {
  // 移动对象到新地址
  for (each live object) {
    HeapWord* new_addr = obj->mark()->decode_pointer();
    memmove(new_addr, obj, obj->size());
  }
}
```

#### 第5阶段：恢复mark word

```cpp
void MarkSweep::restore_marks(PreservedMarksSet* preserved_marks) {
  // 并行恢复
  for (uint i = 0; i < num_workers; i++) {
    preserved_marks->get(i)->restore();
  }
}

// PreservedMarks::restore()实现
void PreservedMarks::restore() {
  while (!_stack.is_empty()) {
    OopAndMarkOop elem = _stack.pop();
    elem.set_mark();  // 恢复mark word
  }
}
```

### 📊 性能与开销

#### 内存开销（8个GC线程，8GB堆）

```
假设1%的对象需要移动（约100K个对象）：

PreservedMarksSet对象：~100字节
栈数组：8个Padded<PreservedMarks>
  ├── 每个：~128字节（带Padding避免false sharing）
  └── 总计：~1KB

栈内容（平均分配给8个worker）：
  ├── 每个栈：~12.5K个条目
  ├── 每条目：16字节（oop + markOop）
  └── 总计：12.5K × 16字节 × 8 = 1.6MB

总计：~1.6MB
```

#### CPU开销

```
保存阶段（phase2）：
├── 每对象开销：~10ns
├── 100K对象：1ms
└── 并行加速：8线程 → ~0.125ms

恢复阶段（phase5）：
├── 每对象开销：~5ns
├── 100K对象：0.5ms
└── 并行加速：8线程 → ~0.06ms

总计：~0.2ms（Full GC总时间的很小一部分）
```

### 🎯 为什么每个worker一个栈？

**问题**：如果使用单个共享栈，需要同步（加锁）

**解决方案**：每个worker独立的栈

**好处**：
1. **无锁**：每个worker写自己的栈，无竞争
2. **缓存友好**：每个worker访问自己的缓存行
3. **并行恢复**：每个worker并行恢复自己的栈

**Padding避免False Sharing**：
```cpp
Padded<PreservedMarks>* _stacks;  // Padded模板
```

- 每个`PreservedMarks`对象被padding到缓存行大小（64字节）
- 避免不同worker访问相邻栈导致缓存行争用

---

## G1CollectionSet收集集合

### 🎯 收集集合概述

`G1CollectionSet`管理**每次GC停顿要回收的Region集合**。它是G1增量收集的核心：

- **年轻代GC**：收集所有Eden + Survivor Region
- **混合GC**：收集所有年轻代 + 部分Old Region

### 🏗️ G1CollectionSet数据结构

```cpp
// 文件：g1CollectionSet.hpp，第39-119行

class G1CollectionSet {
private:
  G1CollectedHeap* _g1h;
  G1Policy* _policy;
  
  CollectionSetChooser* _cset_chooser;  // 选择Old Region的启发式算法
  
  uint _eden_region_length;      // Eden Region数量
  uint _survivor_region_length;  // Survivor Region数量
  uint _old_region_length;       // Old Region数量
  
  // 收集集合的实际存储：Region索引数组
  uint* _collection_set_regions;
  volatile size_t _collection_set_cur_length;  // 当前长度
  size_t _collection_set_max_length;           // 最大长度
  
  // GC停顿前的字节数
  size_t _bytes_used_before;
  
  // 记忆集总长度
  size_t _recorded_rs_lengths;
  
  // 增量构建状态
  enum CSetBuildType {
    Active,    // 活跃构建
    Inactive   // 非活跃
  };
  CSetBuildType _inc_build_state;
  
  // 增量构建的统计信息
  size_t _inc_bytes_used_before;
  size_t _inc_recorded_rs_lengths;
  ssize_t _inc_recorded_rs_lengths_diffs;
  double _inc_predicted_elapsed_time_ms;
  double _inc_predicted_elapsed_time_ms_diffs;
  
public:
  G1CollectionSet(G1CollectedHeap* g1h, G1Policy* policy);
  
  // 初始化：分配Region索引数组
  void initialize(uint max_region_length);
  
  // 添加Region到收集集合
  void add_young_region_common(HeapRegion* hr);
  void add_old_region(HeapRegion* hr);
  
  // 获取收集集合信息
  uint young_region_length() const { return _eden_region_length + _survivor_region_length; }
  uint old_region_length() const { return _old_region_length; }
  size_t bytes_used_before() const { return _bytes_used_before; }
};
```

### 📝 初始化过程

```cpp
// 文件：g1CollectionSet.cpp，第94-98行

void G1CollectionSet::initialize(uint max_region_length) {
  guarantee(_collection_set_regions == NULL, "Must only initialize once.");
  _collection_set_max_length = max_region_length;
  _collection_set_regions = NEW_C_HEAP_ARRAY(uint, max_region_length, mtGC);
}
```

**G1初始化调用**：
```cpp
// 文件：g1CollectedHeap.cpp，第1972行
_collection_set.initialize(max_regions());
```

**max_regions()计算**：
```cpp
uint G1CollectedHeap::max_regions() const {
  return _hrm.max_length();  // 堆的最大Region数
}

// 示例：8GB堆，2MB Region大小
// max_regions = 8GB / 2MB = 4096
```

**初始化后的状态**：

```
G1CollectionSet {
  _collection_set_max_length = 4096  // 最大可能的Region数
  _collection_set_cur_length = 0     // 当前为空
  _collection_set_regions = uint[4096]  // 分配数组
  
  _eden_region_length = 0
  _survivor_region_length = 0
  _old_region_length = 0
  
  _inc_build_state = Inactive
}
```

### 🔄 收集集合的生命周期

#### 1. GC停顿开始前：增量构建年轻代CSet

```cpp
// 应用运行期间，每次分配新Eden Region时
void G1CollectionSet::add_eden_region(HeapRegion* hr) {
  assert(_inc_build_state == Active, "Precondition");
  assert(hr->is_eden(), "must be an eden region");
  
  // 添加到收集集合
  uint index = hr->hrm_index();
  _collection_set_regions[_collection_set_cur_length++] = index;
  
  _eden_region_length++;
  _inc_bytes_used_before += hr->used();
  _inc_recorded_rs_lengths += hr->rem_set()->occupied();
  
  // 更新预测时间
  _inc_predicted_elapsed_time_ms += predict_region_elapsed_time_ms(hr);
}
```

**增量构建的好处**：
- Eden Region分配时立即加入CSet
- GC停顿开始时，年轻代CSet已经构建完成
- 减少停顿时间

#### 2. GC停顿开始：确定年轻代CSet

```cpp
void G1CollectionSet::finalize_incremental_building() {
  assert(_inc_build_state == Active, "Precondition");
  
  // 转移增量统计到最终统计
  _bytes_used_before = _inc_bytes_used_before;
  _recorded_rs_lengths = _inc_recorded_rs_lengths;
  
  // 应用差值（并发优化线程的更新）
  _recorded_rs_lengths += _inc_recorded_rs_lengths_diffs;
  
  // 验证
  verify_young_cset_indices();
  
  _inc_build_state = Inactive;
}
```

#### 3. GC停顿开始：添加Old Region（混合GC）

```cpp
void G1CollectionSet::add_old_region(HeapRegion* hr) {
  assert_at_safepoint_on_vm_thread();
  assert(_inc_build_state == Active, "Precondition");
  assert(hr->is_old(), "the region should be old");
  
  // 添加到收集集合
  uint index = hr->hrm_index();
  _collection_set_regions[_collection_set_cur_length++] = index;
  
  _old_region_length++;
  _bytes_used_before += hr->used();
  _recorded_rs_lengths += hr->rem_set()->occupied();
}
```

**Old Region选择策略**：
- 由`CollectionSetChooser`选择
- 优先选择**垃圾最多**的Region（Garbage-First原则）
- 受停顿时间目标限制

#### 4. GC疏散（Evacuation）

```cpp
void G1CollectedHeap::evacuate_collection_set(...) {
  // 遍历收集集合中的所有Region
  for (uint i = 0; i < _collection_set.cur_length(); i++) {
    uint region_index = _collection_set.region_at(i);
    HeapRegion* hr = _hrm.at(region_index);
    
    // 疏散Region中的活跃对象
    evacuate_region(hr);
  }
}
```

#### 5. GC停顿结束：清空CSet

```cpp
void G1CollectionSet::clear() {
  _collection_set_cur_length = 0;
  _eden_region_length = 0;
  _survivor_region_length = 0;
  _old_region_length = 0;
  
  _bytes_used_before = 0;
  _recorded_rs_lengths = 0;
  
  // 重新开始增量构建
  _inc_build_state = Active;
  _inc_bytes_used_before = 0;
  _inc_recorded_rs_lengths = 0;
}
```

### 📊 收集集合的典型大小

#### 年轻代GC

```
8GB堆，2MB Region，年轻代512MB：

Eden Regions：
├── 数量：~240个
├── 占收集集合：100%
└── 大小：~480MB

Survivor Regions：
├── 数量：~16个
├── 占收集集合：~6%
└── 大小：~32MB

总计：
├── Region数：~256个
├── 占数组：256 / 4096 = 6.25%
└── 疏散时间：~10-50ms
```

#### 混合GC

```
8GB堆，2MB Region，年轻代512MB，Old选择32个Region：

Young Regions：
├── 数量：~256个
└── 大小：~512MB

Old Regions：
├── 数量：~32个（垃圾率最高的）
└── 大小：~64MB

总计：
├── Region数：~288个
├── 占数组：288 / 4096 = 7%
└── 疏散时间：~30-100ms
```

### 🎯 为什么使用索引数组？

**对比方案**：

| 方案 | 内存开销 | 访问性能 | 遍历性能 |
|------|---------|---------|---------|
| **索引数组** | 4字节/Region | O(1) | O(n)，缓存友好 |
| **指针数组** | 8字节/Region | O(1) | O(n)，缓存友好 |
| **链表** | 8字节/Region + 指针 | O(n) | O(n)，缓存不友好 |
| **位图** | 1bit/Region | O(1)检查 | O(n)，需遍历所有Region |

**选择索引数组的原因**：
1. **内存高效**：4字节/Region（8GB堆仅需16KB）
2. **访问快速**：O(1)随机访问
3. **遍历高效**：连续内存，缓存友好
4. **构建灵活**：支持增量构建

### 💡 增量构建的优势

**传统方式**（在停顿开始时构建整个CSet）：
```
GC停顿开始
  ↓
遍历所有年轻代Region（耗时）
  ↓
构建CSet（耗时）
  ↓
开始疏散
```

**G1增量构建**：
```
应用运行期间
  ↓
每次分配Eden Region时加入CSet（无额外开销）
  ↓
GC停顿开始
  ↓
年轻代CSet已构建完成
  ↓
仅需添加Old Region（快速）
  ↓
立即开始疏散
```

**停顿时间节省**：
- 传统方式：~1-2ms（遍历256个年轻代Region）
- 增量构建：~0.1ms（仅添加32个Old Region）
- **节省：~90%的CSet构建时间**

---

## 初始化顺序与依赖关系

### 📊 依赖图

```
虚拟Region (get_dummy_region)
    ↓
G1AllocRegion::setup (全局静态字段)
    ↓
G1Allocator::init_mutator_alloc_region
    ↓
G1MonitoringSupport (new)
    ↓
G1StringDedup::initialize
    ↓
PreservedMarksSet::init
    ↓
G1CollectionSet::initialize
```

### 🔍 为什么按这个顺序？

#### 1. 虚拟Region必须最先

```
虚拟Region → G1AllocRegion
```

**原因**：
- `G1AllocRegion::setup()`需要虚拟Region作为参数
- 所有`G1AllocRegion`子类都依赖全局静态字段`_dummy_region`

#### 2. G1AllocRegion::setup()必须在分配器初始化前

```
G1AllocRegion::setup → G1Allocator::init_mutator_alloc_region
```

**原因**：
- `MutatorAllocRegion::init()`会将`_alloc_region`设置为`_dummy_region`
- 如果`_dummy_region`未初始化，会触发断言失败

#### 3. 分配器必须在监控支持前

```
G1Allocator → G1MonitoringSupport
```

**原因**：
- `G1MonitoringSupport`构造函数会调用`recalculate_sizes()`
- `recalculate_sizes()`需要读取堆的大小和使用情况
- 分配器初始化确保堆状态一致

#### 4. 字符串去重可以独立

```
G1StringDedup (无强依赖)
```

**原因**：
- 字符串去重是可选特性
- 仅在启用时（`-XX:+UseStringDeduplication`）初始化后台线程
- 不影响其他组件

#### 5. 保留标记集独立

```
PreservedMarksSet (无强依赖)
```

**原因**：
- 仅在Full GC时使用
- 不影响年轻代GC或混合GC
- 可以在任何时候初始化

#### 6. 收集集合必须最后

```
G1CollectionSet (无强依赖，但逻辑上最后)
```

**原因**：
- 需要知道堆的最大Region数（`max_regions()`）
- 逻辑上在堆完全初始化后再初始化CSet
- 为GC做准备

### 🎯 关键检查点

#### 初始化完成后的堆状态

```
G1CollectedHeap {
  // 基础设施
  ✓ HeapRegionManager初始化
  ✓ G1Policy初始化
  ✓ 并发标记初始化
  ✓ SATB队列系统初始化
  ✓ 脏卡队列系统初始化
  ✓ 并发优化线程启动
  
  // 本次分析的组件
  ✓ 虚拟Region分配
  ✓ G1AllocRegion全局字段设置
  ✓ Mutator分配区域初始化
  ✓ 监控支持创建
  ✓ 字符串去重初始化（可选）
  ✓ 保留标记集初始化
  ✓ 收集集合初始化
  
  // 堆可以开始服务分配请求
  return JNI_OK;
}
```

---

## 内存开销估算

### 📊 8GB堆的内存开销

#### 虚拟Region

```
HeapRegion对象：~500字节
Region内存：2MB（索引0的Region）
元数据：~10KB
───────────────────
总计：~2MB
```

#### G1AllocRegion全局字段

```
_g1h指针：8字节
_dummy_region指针：8字节
───────────────────
总计：16字节
```

#### G1Allocator

```
G1Allocator对象：~200字节
MutatorAllocRegion：~100字节
SurvivorGCAllocRegion：~100字节
OldGCAllocRegion：~100字节
───────────────────
总计：~500字节
```

#### G1MonitoringSupport

```
G1MonitoringSupport对象：~300字节
CollectorCounters（3个）：~600字节
GenerationCounters（2个）：~400字节
HSpaceCounters（4个）：~800字节
缓存的统计信息：~200字节
───────────────────
总计：~2.3KB
```

#### G1StringDedup（启用时）

```
StringDedupTable：~1MB
StringDedupQueue：~1MB
StringDedupThread栈：~1MB
───────────────────
总计：~3MB
```

#### PreservedMarksSet（8个GC线程）

```
PreservedMarksSet对象：~100字节
8个Padded<PreservedMarks>：~1KB
栈内容（初始为空）：0字节
───────────────────
总计：~1KB（空闲时）
峰值：~1.6MB（Full GC时，假设1%对象移动）
```

#### G1CollectionSet（4096最大Region）

```
G1CollectionSet对象：~300字节
Region索引数组：4096 × 4字节 = 16KB
CollectionSetChooser：~2KB
───────────────────
总计：~18KB
```

### 💰 总体内存开销

| 组件 | 开销（不含字符串去重） | 开销（含字符串去重） |
|------|---------------------|-------------------|
| 虚拟Region | 2MB | 2MB |
| G1AllocRegion | 16B | 16B |
| G1Allocator | 500B | 500B |
| G1MonitoringSupport | 2.3KB | 2.3KB |
| G1StringDedup | 0 | 3MB |
| PreservedMarksSet | 1KB | 1KB |
| G1CollectionSet | 18KB | 18KB |
| **总计** | **~2.02MB** | **~5.02MB** |
| **占8GB堆比例** | **0.025%** | **0.061%** |

**结论**：这些组件的内存开销非常小，即使启用字符串去重，也不到堆大小的0.1%。

---

## 总结

### 🎯 核心要点

#### 1. 虚拟Region的设计智慧

```
✅ 避免NULL检查：_alloc_region永远非NULL
✅ 简化逻辑：退出时统一重置为虚拟Region
✅ 性能优化：快速路径无需分支判断
✅ 内存开销：仅2MB（一个Region）
```

#### 2. G1AllocRegion的抽象价值

```
✅ 统一接口：Mutator、Survivor、Old使用相同的分配逻辑
✅ 无锁快速路径：1-2ns的极致性能
✅ 灵活的子类化：支持不同的Region分配策略
✅ 生命周期管理：init/retire的清晰语义
```

#### 3. G1Allocator的分离策略

```
✅ 避免竞争：应用线程和GC线程独立的分配区域
✅ 简化同步：GC期间应用线程暂停，GC分配无需并发控制
✅ 优化性能：GC分配跳过BOT更新
```

#### 4. G1MonitoringSupport的缓存设计

```
✅ 避免重复计算：缓存统计信息
✅ 快速更新：仅在GC后和Eden分配时更新
✅ JMX友好：直接返回缓存值，无性能影响
```

#### 5. G1StringDedup的可选特性

```
✅ 大幅节省内存：5-15%的堆内存（适用场景）
✅ 低CPU开销：后台线程，0.5-2% CPU
✅ 智能候选选择：年龄阈值过滤短命对象
```

#### 6. PreservedMarksSet的并行设计

```
✅ 无锁并行：每个GC worker独立的栈
✅ 避免False Sharing：Padding对齐
✅ 极低开销：仅Full GC时使用，约0.2ms
```

#### 7. G1CollectionSet的增量构建

```
✅ 减少停顿时间：年轻代CSet在应用运行期间构建
✅ 内存高效：索引数组，仅4字节/Region
✅ 访问快速：O(1)随机访问，连续内存遍历
```

### 📈 性能特征总结

| 组件 | 关键性能指标 | 影响 |
|------|------------|------|
| **虚拟Region** | 无性能开销 | 简化逻辑 |
| **G1AllocRegion** | 快速路径1-2ns | 极致分配性能 |
| **G1Allocator** | 慢速路径1-10μs | 每2MB触发一次 |
| **G1MonitoringSupport** | 无运行时开销 | JMX查询友好 |
| **G1StringDedup** | 0.5-2% CPU，5-15%内存节省 | 适用于重复字符串场景 |
| **PreservedMarksSet** | 0.2ms（Full GC） | Full GC总时间的小部分 |
| **G1CollectionSet** | 增量构建节省90%时间 | 显著减少停顿 |

### 🚀 工程启示

1. **哨兵对象模式**：虚拟Region避免NULL检查，提升性能
2. **无锁快速路径**：原子CAS操作，极致分配性能
3. **分离关注点**：Mutator/GC分配区域分离，简化设计
4. **缓存优化**：避免重复计算，降低JMX查询开销
5. **可选特性**：字符串去重按需启用，不影响核心性能
6. **并行无锁**：每worker独立栈，最大化并行度
7. **增量构建**：分摊初始化开销，减少停顿时间

### 📚 学习收获

通过深入分析G1 GC的最终初始化组件，你已经掌握了：

1. **虚拟对象模式**：如何用哨兵对象简化逻辑
2. **无锁并发算法**：原子CAS实现高性能分配
3. **分层抽象设计**：G1AllocRegion的继承体系
4. **监控与可观测性**：如何设计JMX友好的统计系统
5. **可选优化特性**：字符串去重的trade-off
6. **并行数据结构**：PreservedMarksSet的无锁并行
7. **增量算法**：收集集合的增量构建策略

你已经完成了G1 GC初始化流程的**完整学习**！从HeapRegion创建、SATB队列、脏卡队列、并发优化线程，到最终的分配器、监控、去重、保留标记和收集集合，你已经掌握了G1 GC启动的全貌。

接下来，你可以深入学习：
- ✅ **G1 GC的运行时行为**：年轻代GC、混合GC的完整流程
- ✅ **并发标记周期**：SATB队列如何支持并发标记
- ✅ **记忆集维护**：脏卡队列如何更新RSet
- ✅ **性能调优**：如何根据应用特性调整G1参数

继续保持这样的学习深度，你一定能够完全掌握JVM的核心原理！💪🎉
