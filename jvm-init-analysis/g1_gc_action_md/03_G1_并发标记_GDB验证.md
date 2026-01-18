# G1 并发标记 (Concurrent Marking) - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms256m -Xmx256m -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=20`

---

## 1. 并发标记概述

### 1.1 什么是并发标记

并发标记是G1 GC的核心特性之一，用于：
- **标记老年代中的存活对象**
- **计算各Region的存活数据量**
- **为Mixed GC提供回收依据**

### 1.2 触发条件

当堆使用率达到IHOP (Initiating Heap Occupancy Percent) 阈值时触发：

```cpp
// 默认IHOP: 45%
-XX:InitiatingHeapOccupancyPercent=45

// 本测试设置: 20% (更容易触发)
-XX:InitiatingHeapOccupancyPercent=20
```

---

## 2. 并发标记阶段

### 2.1 完整流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    G1 并发标记流程                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────┐                        │
│  │ 阶段1: Initial Mark (初始标记)          │ ◀── STW               │
│  │ • 标记GC根直接引用的对象                 │                        │
│  │ • 与Young GC一起执行 (Piggyback)         │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 阶段2: Root Region Scan (根区域扫描)    │ ◀── 并发               │
│  │ • 扫描Survivor区域中对老年代的引用      │                        │
│  │ • 必须在下次Young GC前完成              │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 阶段3: Concurrent Mark (并发标记)       │ ◀── 并发               │
│  │ • 遍历整个堆，标记存活对象              │                        │
│  │ • 使用SATB算法处理并发修改              │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 阶段4: Remark (重新标记)                │ ◀── STW               │
│  │ • 处理SATB缓冲区                        │                        │
│  │ • 完成最终标记                          │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 阶段5: Cleanup (清理)                   │ ◀── 部分STW           │
│  │ • 统计各Region存活数据                  │                        │
│  │ • 回收完全空闲的Region                  │                        │
│  │ • 重置标记数据结构                      │                        │
│  └─────────────────────────────────────────┘                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 GDB验证数据

```
========== 并发标记 ==========
[CM开始] G1ConcurrentMark::mark_from_roots
  G1ConcurrentMark: 0x7ffff004a3f0
  并发遍历堆标记存活对象

[CM清理] G1ConcurrentMark::cleanup
  回收完全空闲的Region
```

---

## 3. 阶段1: Initial Mark (初始标记)

### 3.1 工作内容

- 标记GC根直接引用的对象
- 这些对象一定是存活的

### 3.2 与Young GC的关系

Initial Mark通常**搭载在Young GC上执行**：

```
GC日志:
[1.078s] GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation)
                        ^^^^^^^^^^^^^^^^
                        表示这是一次触发并发标记的Young GC
```

### 3.3 源码分析

```cpp
// g1CollectedHeap.cpp
void G1CollectedHeap::do_collection_pause_at_safepoint(double target_pause_time_ms) {
  // ...
  
  if (collector_state()->in_concurrent_start_gc()) {
    // 这是一次Initial Mark GC
    concurrent_mark()->checkpoint_roots_initial_pre();
    
    // 执行Young GC...
    
    concurrent_mark()->checkpoint_roots_initial_post();
  }
  
  // ...
}
```

---

## 4. 阶段2: Root Region Scan (根区域扫描)

### 4.1 工作内容

扫描Survivor区域，找出其中指向老年代的引用。

### 4.2 GC日志验证

```
[1.122s][gc,marking] GC(1) Concurrent Scan Root Regions
[1.144s][gc,marking] GC(1) Concurrent Scan Root Regions 22.437ms
```

### 4.3 为什么需要扫描Survivor

```
┌─────────────────────────────────────────────────────────────────────┐
│ Root Region Scan 原理                                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Survivor Region            Old Region                              │
│  ┌──────────────┐           ┌──────────────┐                        │
│  │  Object A    │──────────▶│  Object B    │                        │
│  │  (存活)      │           │  (存活!)     │                        │
│  │              │           │              │                        │
│  └──────────────┘           └──────────────┘                        │
│                                                                     │
│  Survivor中的对象引用了Old中的对象                                   │
│  必须扫描Survivor才能正确标记Old中的存活对象                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.4 源码分析

```cpp
// g1ConcurrentMark.cpp
void G1ConcurrentMark::scan_root_regions() {
  // 扫描所有Survivor Region
  G1RootRegionScanClosure cl(_g1h, this);
  _g1h->survivor()->iterate(&cl);
}
```

---

## 5. 阶段3: Concurrent Mark (并发标记)

### 5.1 工作内容

遍历整个堆，使用三色标记算法标记存活对象。

### 5.2 GDB验证

```
[CM开始] G1ConcurrentMark::mark_from_roots
  G1ConcurrentMark: 0x7ffff004a3f0
  并发遍历堆标记存活对象
```

### 5.3 GC日志验证

```
[1.144s][gc,marking] GC(1) Concurrent Mark (1.144s)
[1.144s][gc,marking] GC(1) Concurrent Mark From Roots
[1.144s][gc,task   ] GC(1) Using 3 workers of 3 for marking
[1.145s][gc,marking] GC(1) Concurrent Mark From Roots 0.624ms
```

### 5.4 三色标记算法

```
┌─────────────────────────────────────────────────────────────────────┐
│ 三色标记 (Tri-color Marking)                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  颜色     │ 含义                                                    │
│ ─────────┼────────────────────────────────────────────────────────  │
│  白色     │ 未被访问，GC结束后回收                                   │
│  灰色     │ 已被访问，但子引用未完全处理                             │
│  黑色     │ 已被访问，且所有子引用已处理，确定存活                   │
│                                                                     │
│  标记过程:                                                          │
│  1. 初始：所有对象为白色                                            │
│  2. 从GC根开始，访问到的对象变灰色                                  │
│  3. 处理灰色对象的所有引用后，灰色变黑色                            │
│  4. 重复直到没有灰色对象                                            │
│  5. 剩余的白色对象即为垃圾                                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.5 源码分析

```cpp
// g1ConcurrentMark.cpp
void G1ConcurrentMark::mark_from_roots() {
  _parallel_workers->run_task(&marking_task);
}

// 标记任务
class G1CMRootRegionScanTask : public AbstractGangTask {
  void work(uint worker_id) {
    // 遍历标记栈
    while (_cm->claim_region(worker_id, &hr)) {
      // 标记Region中的存活对象
      _cm->scan_region(hr, worker_id);
    }
  }
};
```

---

## 6. SATB (Snapshot-At-The-Beginning)

### 6.1 什么是SATB

SATB是G1处理并发标记期间引用变更的算法。

**核心思想**: 标记开始时的对象快照决定存活性。

### 6.2 问题场景

```
并发标记期间，应用可能修改引用：

时刻T1 (标记器看到):      时刻T2 (应用修改):
    A ──▶ B                  A ──X B  (断开)
                             C ──▶ B  (新引用)

如果标记器在T1处理完A后，B变成孤儿，可能被误判为垃圾。
```

### 6.3 SATB解决方案

```cpp
// 写屏障记录引用变更
void G1BarrierSet::write_ref_field_pre(oop* field, oop new_val) {
  oop previous_val = *field;  // 旧值
  
  if (previous_val != NULL) {
    // 记录旧值到SATB缓冲区
    enqueue(previous_val);
  }
}
```

### 6.4 SATB工作流程

```
┌─────────────────────────────────────────────────────────────────────┐
│ SATB 工作流程                                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. 并发标记开始，创建堆的逻辑快照                                   │
│                                                                     │
│  2. 应用修改引用 A.field = null                                     │
│     ┌────────────────────────────────────┐                          │
│     │ 写屏障捕获旧值                      │                          │
│     │ old_value = A.field                 │                          │
│     │ SATB_buffer.add(old_value)          │                          │
│     └────────────────────────────────────┘                          │
│                                                                     │
│  3. Remark阶段处理SATB缓冲区                                        │
│     ┌────────────────────────────────────┐                          │
│     │ for each obj in SATB_buffer:        │                          │
│     │   mark_as_live(obj)                 │                          │
│     └────────────────────────────────────┘                          │
│                                                                     │
│  4. 确保快照时存活的对象不会被回收                                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 阶段4: Remark (重新标记)

### 7.1 工作内容

- 处理SATB缓冲区中的引用
- 完成最终标记
- 需要STW

### 7.2 GC日志验证

```
[1.148s][gc,start] GC(1) Pause Remark
[1.162s][gc       ] GC(1) Pause Remark 73M->73M(256M) 14.623ms
```

### 7.3 源码分析

```cpp
// g1ConcurrentMark.cpp
void G1ConcurrentMark::checkpoint_roots_final(bool clear_all_soft_refs) {
  // 处理SATB缓冲区
  flush_all_satb_buffers();
  
  // 完成标记
  finalize_marking();
}
```

---

## 8. 阶段5: Cleanup (清理)

### 8.1 工作内容

1. 统计各Region的存活数据量
2. 按垃圾比例排序Region (用于Mixed GC选择)
3. 回收完全空闲的Region
4. 重置标记数据结构

### 8.2 GDB验证

```
[CM清理] G1ConcurrentMark::cleanup
  回收完全空闲的Region
```

### 8.3 GC日志验证

```
[1.162s][gc,marking] GC(1) Concurrent Rebuild Remembered Sets
[1.166s][gc,marking] GC(1) Concurrent Rebuild Remembered Sets 3.720ms
[1.166s][gc,marking] GC(1) Concurrent Cleanup for Next Mark
[1.166s][gc,marking] GC(1) Concurrent Cleanup for Next Mark 0.042ms
[1.166s][gc        ] GC(1) Concurrent Cycle 44.161ms
```

### 8.4 源码分析

```cpp
// g1ConcurrentMark.cpp
void G1ConcurrentMark::cleanup() {
  // 统计Region存活数据
  _g1h->heap_region_iterate(&note_end_of_marking_cl);
  
  // 排序Region（按回收效率）
  _g1h->g1_policy()->record_concurrent_mark_cleanup_end();
  
  // 回收空Region
  _g1h->tear_down_region_sets(true /* free_list_only */);
}
```

---

## 9. 并发标记与Mixed GC的关系

### 9.1 数据流

```
┌─────────────────────────────────────────────────────────────────────┐
│ 并发标记 → Mixed GC 数据流                                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  并发标记输出:                                                       │
│  ┌────────────────────────────────────────┐                         │
│  │ Region存活数据:                         │                         │
│  │   Region #5:  存活率 10%  ← 优先回收    │                         │
│  │   Region #8:  存活率 25%                │                         │
│  │   Region #12: 存活率 50%                │                         │
│  │   Region #3:  存活率 90%  ← 最后回收    │                         │
│  └────────────────────────────────────────┘                         │
│                     │                                               │
│                     ▼                                               │
│  Mixed GC使用:                                                       │
│  ┌────────────────────────────────────────┐                         │
│  │ CSet选择:                               │                         │
│  │   1. 所有Young Regions (必须)           │                         │
│  │   2. Region #5  (垃圾最多)              │                         │
│  │   3. Region #8  (垃圾较多)              │                         │
│  │   ...                                   │                         │
│  │   在时间预算内选择尽可能多的Region       │                         │
│  └────────────────────────────────────────┘                         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 9.2 Mixed GC触发

并发标记完成后，如果有足够的老年代垃圾：
1. 下一次Young GC变为Mixed GC
2. Mixed GC会额外回收部分老年代Region
3. 持续多轮直到老年代垃圾降到阈值以下

---

## 10. 关键配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-XX:InitiatingHeapOccupancyPercent` | 45 | 触发并发标记的堆占用阈值 |
| `-XX:ConcGCThreads` | (CPU数/4) | 并发标记线程数 |
| `-XX:G1MixedGCCountTarget` | 8 | Mixed GC目标次数 |
| `-XX:G1HeapWastePercent` | 5 | 停止Mixed GC的垃圾阈值 |
| `-XX:G1MixedGCLiveThresholdPercent` | 85 | Region加入CSet的存活阈值 |

---

## 11. 关键数据汇总

| 阶段 | 耗时 | STW | 说明 |
|------|------|-----|------|
| Initial Mark | ~1ms | 是 | 搭载在Young GC |
| Root Region Scan | 22.4ms | 否 | 扫描Survivor |
| Concurrent Mark | 0.9ms | 否 | 标记存活对象 |
| Remark | 14.6ms | 是 | 处理SATB |
| Cleanup | ~4ms | 部分 | 统计和清理 |
| **总计** | **44.2ms** | - | 并发周期 |

---

## 12. GDB调试命令

```bash
# 断点设置
break G1ConcurrentMark::mark_from_roots
break G1ConcurrentMark::cleanup
break G1ConcurrentMark::checkpoint_roots_final

# 查看并发标记状态
print *this
print _g1h->_cm
print _parallel_workers->active_workers()

# SATB缓冲区
print _g1h->_satb_mark_queue_set
```
