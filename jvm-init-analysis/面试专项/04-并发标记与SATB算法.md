# JVM技术专家面试专项 - 并发标记与SATB算法

> **环境**: Linux, -Xms8g -Xmx8g, G1GC, Region=4MB, 非大页, 非NUMA
> **难度**: ⭐⭐⭐⭐⭐ JVM技术专家级

---

## 面试问题 1：为什么并发标记需要特殊处理？

### 👨‍💼 面试官提问

> "并发标记过程中，GC线程和应用线程同时运行，会有什么问题？"

### 👨‍🎓 面试者回答

#### 1. 核心问题：三色标记的漏标

**三色标记法**：
- ⚪ **白色**：未被GC线程访问（可能是垃圾）
- ⚫ **黑色**：已被GC线程访问完成（及其引用）
- 🔘 **灰色**：已被访问但引用未扫描完

**漏标发生条件**（同时满足）：
1. 应用线程添加了**黑色对象→白色对象**的引用
2. 应用线程删除了**灰色对象→该白色对象**的引用

```
         时刻T1                      时刻T2（应用线程修改后）
        ┌─────┐                    ┌─────┐
        │  A  │ ⚫黑色              │  A  │ ⚫黑色
        └──┬──┘                    └──┬──┘
           │                          │ 新增引用 A→C
        ┌──▼──┐                    ┌──▼──┐
        │  B  │ 🔘灰色              │  B  │ 🔘灰色
        └──┬──┘                    └─────┘
           │                          ↓ 删除引用 B→C
        ┌──▼──┐                    
        │  C  │ ⚪白色              C变成不可达！(漏标)
        └─────┘                    
                                   
        GC认为C不可达 → 错误回收！
```

#### 2. 两种解决方案

| 方案 | 名称 | 原理 | 代表GC |
|------|------|------|--------|
| **增量更新** | Incremental Update | 记录新增的黑→白引用 | CMS |
| **SATB** | Snapshot-At-The-Beginning | 记录被删除的引用 | G1, Shenandoah |

---

## 面试问题 2：SATB算法的核心原理是什么？

### 👨‍💼 面试官提问

> "G1使用的SATB算法是如何工作的？为什么叫'Snapshot-At-The-Beginning'？"

### 👨‍🎓 面试者回答

#### 1. SATB核心思想

**快照时刻**：在并发标记**开始时**，记录所有存活对象的"快照"。

**保守策略**：在这个时刻存活的对象，即使后来变成垃圾，也**不会被回收**（这次GC周期内）。

#### 2. 实现机制

**Pre-Write Barrier（写前屏障）**：

```cpp
// 伪代码：SATB写屏障
void pre_write_barrier(oop* field) {
  oop old_value = *field;  // 记录旧值
  
  // 如果正在并发标记，且旧值不为NULL
  if (marking_active && old_value != NULL) {
    // 将旧值加入SATB队列
    satb_queue.enqueue(old_value);
  }
}

// 实际写入
void write_field(oop* field, oop new_value) {
  pre_write_barrier(field);
  *field = new_value;
}
```

#### 3. 源码分析

```cpp
// satbMarkQueue.hpp:45
class SATBMarkQueue: public PtrQueue {
private:
  // 过滤掉不需要的条目
  void filter();
  
public:
  // 将引用入队
  void enqueue(void* ptr);
  
  // 处理缓冲区
  void flush();
};
```

**SATB队列结构**：

```
                每个线程的SATB队列
┌─────────────────────────────────────────────────┐
│                                                 │
│  Thread 1           Thread 2          Thread N  │
│  ┌──────────┐       ┌──────────┐     ┌────────┐│
│  │SATBQueue │       │SATBQueue │     │SATBQueue│
│  │ ┌──────┐ │       │ ┌──────┐ │     │┌──────┐││
│  │ │Buffer│ │       │ │Buffer│ │     ││Buffer│││
│  │ │ old1 │ │       │ │ old3 │ │     ││ old5│││
│  │ │ old2 │ │       │ │ old4 │ │     ││ old6│││
│  │ └──────┘ │       │ └──────┘ │     │└──────┘││
│  └────┬─────┘       └────┬─────┘     └───┬────┘│
│       └─────────────────┬┴───────────────┘     │
│                         ↓                       │
│               ┌─────────────────┐              │
│               │ SATBMarkQueueSet│              │
│               │ (全局完成队列)   │              │
│               └─────────────────┘              │
└─────────────────────────────────────────────────┘
```

#### 4. 为什么叫"Snapshot-At-The-Beginning"

- 标记**开始时**，认为所有可达对象都是存活的
- 之后被删除的引用（旧值）都被记录
- 这些被删除引用的对象，在Remark时会被标记为存活
- 相当于保留了标记开始时的"快照"

---

## 面试问题 3：SATB vs Incremental Update，哪个更好？

### 👨‍💼 面试官提问

> "G1为什么选择SATB而不是CMS的增量更新？两者有什么优劣？"

### 👨‍🎓 面试者回答

#### 1. 对比表

| 特性 | SATB (G1) | Incremental Update (CMS) |
|------|-----------|--------------------------|
| **屏障类型** | Pre-write (写前) | Post-write (写后) |
| **记录内容** | 被覆盖的旧引用 | 新增的引用 |
| **Remark工作量** | 仅处理SATB队列 | 需要重新扫描脏卡 |
| **Remark时间** | ★短 | 长 |
| **浮动垃圾** | 可能较多 | 较少 |
| **实现复杂度** | 较高 | 较低 |

#### 2. G1选择SATB的原因

**原因1：Remark时间可控**

```
SATB Remark:
  只需处理SATB缓冲区中的引用（数量有限）
  时间 ≈ O(SATB队列大小)

Incremental Update Remark:
  需要重新扫描所有被标记为脏的卡片
  时间 ≈ O(脏卡数量 × 平均扫描对象数)
```

**原因2：配合Region化设计**

G1的Region化设计需要快速确定每个Region的存活率，SATB可以更精确地估算。

**原因3：可预测停顿**

SATB的Remark停顿时间更可预测，符合G1的设计目标。

#### 3. 真实GC日志对比

```
# G1 Remark (SATB)
[gc] GC(4) Pause Remark 14.623ms   ★ 很短

# CMS Remark (Incremental Update)
[gc] CMS Remark 156.234ms         ★ 较长
```

---

## 面试问题 4：并发标记的五个阶段详解

### 👨‍💼 面试官提问

> "请详细说明G1并发标记的五个阶段，每个阶段做什么？哪些是STW？"

### 👨‍🎓 面试者回答

#### 1. 五阶段概览

```
┌───────────────────────────────────────────────────────────────────┐
│                     G1并发标记周期                                  │
├─────────┬─────────────┬──────────────┬──────────┬─────────────────┤
│ Initial │ Root Region │  Concurrent  │  Remark  │     Cleanup     │
│  Mark   │    Scan     │     Mark     │          │                 │
│ (STW)   │   (并发)    │    (并发)    │  (STW)   │  (STW+并发)     │
│ ~5ms    │   ~22ms     │   ~624ms     │  ~15ms   │    ~8ms         │
└─────────┴─────────────┴──────────────┴──────────┴─────────────────┘
```

#### 2. 各阶段详解

**Phase 1: Initial Mark (STW, ~5ms)**

```cpp
// 在Young GC中piggyback执行
void G1ConcurrentMark::checkpoint_roots_initial() {
  // 标记GC Roots直接引用的对象
  for each root in gc_roots:
    if (is_in_heap(root)) {
      mark_object(root);
    }
}
```

**Phase 2: Root Region Scan (并发, ~22ms)**

```cpp
// 扫描Survivor区引用的Old对象
void G1ConcurrentMark::scan_root_regions() {
  for each survivor_region in survivors:
    for each object in survivor_region:
      scan_object_references(object);
}
```

**Phase 3: Concurrent Mark (并发, ~624ms)**

```cpp
// g1ConcurrentMark.cpp
void G1ConcurrentMark::mark_from_roots() {
  // 使用3个并发标记线程
  G1CMConcurrentMarkingTask task(this);
  _concurrent_workers->run_task(&task);
}

class G1CMTask {
  void do_marking_step() {
    while (!task_queue->is_empty() || !mark_stack->is_empty()) {
      oop obj = get_next_object();
      scan_object(obj);  // 遍历对象引用，标记可达对象
    }
  }
};
```

**Phase 4: Remark (STW, ~15ms)**

```cpp
void G1ConcurrentMark::remark() {
  // 1. 处理SATB缓冲区
  drain_satb_buffers();
  
  // 2. 处理引用（软/弱/虚引用）
  process_discovered_references();
  
  // 3. 完成所有标记
  finalize_marking();
}
```

**Phase 5: Cleanup (STW+并发, ~8ms)**

```cpp
void G1ConcurrentMark::cleanup() {
  // STW部分
  // 1. 统计每个Region的存活对象
  count_live_per_region();
  
  // 2. 识别完全空的Region
  identify_empty_regions();
  
  // 3. 清理空Region（STW）
  reclaim_empty_regions();
  
  // 并发部分
  // 4. 重建RSet（并发）
  rebuild_rem_set_concurrently();
}
```

#### 3. 真实GC日志

```
[2.235s][info ][gc] GC(4) Concurrent Cycle
[2.240s][debug][gc,marking] GC(4) Concurrent Clear Claimed Marks
[2.242s][debug][gc,marking] GC(4) Concurrent Scan Root Regions
[2.264s][info ][gc,marking] GC(4) Concurrent Mark From Roots
[2.264s][debug][gc,marking] GC(4)   Using 3 workers of 3 for marking
[2.889s][info ][gc,marking] GC(4) Concurrent Mark From Roots 624.538ms
[2.891s][info ][gc] GC(4) Pause Remark 14.623ms
[2.892s][debug][gc,phases] GC(4)   Finalize Marking 2.123ms
[2.892s][debug][gc,phases] GC(4)   System Purge 3.456ms
[2.893s][info ][gc] GC(4) Pause Cleanup 8.234ms
```

---

## 面试问题 5：SATB队列是如何处理的？

### 👨‍💼 面试官提问

> "SATB队列什么时候会被处理？处理逻辑是什么？"

### 👨‍🎓 面试者回答

#### 1. 两个处理时机

| 时机 | 类型 | 处理方式 |
|------|------|----------|
| **并发标记中** | 并发 | 标记线程定期处理已满缓冲区 |
| **Remark阶段** | STW | 处理所有剩余缓冲区 |

#### 2. 并发处理流程

```cpp
// g1ConcurrentMark.cpp
void G1CMTask::do_marking_step(double time_target_ms) {
  // 标记过程中定期处理SATB
  while (has_work_to_do()) {
    // 处理SATB缓冲区
    drain_satb_buffers();
    
    // 处理本地标记队列
    drain_local_queue();
    
    // 处理全局标记栈
    drain_global_mark_stack();
  }
}

void G1CMTask::drain_satb_buffers() {
  // 从全局SATB队列集获取已完成的缓冲区
  while (satb_mq_set->apply_closure_to_completed_buffer(&closure)) {
    // closure会标记缓冲区中的对象
  }
}
```

#### 3. Remark阶段处理

```cpp
void G1ConcurrentMark::remark() {
  // 1. 先flush所有线程的SATB队列
  for each java_thread:
    java_thread->satb_queue().flush();
  
  // 2. 处理所有完成的缓冲区
  while (satb_mq_set->has_completed_buffers()) {
    satb_mq_set->apply_closure_to_completed_buffer(&mark_closure);
  }
  
  // 3. 标记SATB中的对象
  for each object in satb_buffers:
    if (!is_marked(object)) {
      mark_object(object);
      push_to_mark_stack(object);
    }
  drain_mark_stack();  // 递归标记
}
```

#### 4. 缓冲区过滤

```cpp
// satbMarkQueue.cpp
void SATBMarkQueue::filter() {
  // 过滤掉不需要处理的条目
  for each entry in buffer:
    if (entry == NULL) continue;
    if (!is_in_heap(entry)) continue;
    if (is_already_marked(entry)) continue;  // 已标记的跳过
    // 保留需要处理的条目
    retain(entry);
}
```

---

## 面试问题 6：如何分析并发标记的性能问题？

### 👨‍💼 面试官提问

> "如果发现并发标记时间过长，应该如何分析和优化？"

### 👨‍🎓 面试者回答

#### 1. 性能指标分析

```
[gc,marking] GC(4) Concurrent Mark From Roots 624.538ms
[gc,marking] GC(4)   Using 3 workers of 3 for marking
```

| 指标 | 理想值 | 分析 |
|------|--------|------|
| 并发标记时间 | < 1s | 624ms正常 |
| 标记线程数 | CPU核心/4 | 3个线程正常 |
| Remark时间 | < 50ms | 14.6ms优秀 |

#### 2. 问题诊断

**问题1：并发标记时间过长**
```bash
# 可能原因
1. 堆中存活对象过多
2. 对象图复杂（深度链表等）
3. 并发线程数不足
```

**问题2：Remark时间过长**
```bash
# 可能原因
1. SATB队列积压过多
2. 引用处理耗时（大量软/弱引用）
3. 应用线程修改频繁
```

#### 3. 调优参数

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `-XX:ConcGCThreads` | CPU/4 | 并发标记线程数 |
| `-XX:G1ConcRefinementThreads` | auto | 并发细化线程数 |
| `-XX:InitiatingHeapOccupancyPercent` | 45 | 触发并发标记阈值 |
| `-XX:G1SATBBufferSize` | 1K | SATB缓冲区大小 |

#### 4. 调优示例

```bash
# 场景：并发标记来不及完成
java -XX:ConcGCThreads=4 \                 # 增加并发线程
     -XX:InitiatingHeapOccupancyPercent=35 \  # 提前触发
     -Xlog:gc+marking=debug ...

# 场景：Remark时间过长
java -XX:G1SATBBufferSize=2048 \           # 增大缓冲区
     -XX:ParallelRefProcEnabled=true \     # 并行引用处理
     ...
```

---

## 面试问题 7：并发标记失败会怎样？

### 👨‍💼 面试官提问

> "如果并发标记来不及完成，堆满了会发生什么？"

### 👨‍🎓 面试者回答

#### 1. 触发Full GC

当并发标记无法跟上分配速度时，会触发**Allocation Failure**，进而触发Full GC。

```
[gc] GC(10) Pause Full (Allocation Failure)
[gc] GC(10) 6144M->3072M(8192M) 2345.678ms
```

#### 2. 源码分析

```cpp
// g1CollectedHeap.cpp
HeapWord* G1CollectedHeap::attempt_allocation_slow(...) {
  // 尝试多种方式
  result = attempt_allocation_at_safepoint(...);
  
  if (result == NULL) {
    // 尝试扩展堆
    result = expand_and_allocate(...);
  }
  
  if (result == NULL) {
    // 触发Full GC
    return do_full_collection_and_retry(...);
  }
}
```

#### 3. 避免Full GC的策略

| 策略 | 方法 |
|------|------|
| **提前启动并发标记** | 降低IHOP (35%→30%) |
| **加快并发标记** | 增加ConcGCThreads |
| **减少分配压力** | 优化应用分配模式 |
| **增加堆大小** | 给并发标记更多时间 |

#### 4. 监控指标

```bash
# 监控Full GC
jstat -gcutil <pid> 1000 | grep -E "FGC|FGCT"

# GC日志中查找
grep "Pause Full" gc.log

# NMT检查内存使用
jcmd <pid> VM.native_memory summary
```

---

## 实战验证：GDB调试并发标记

### 1. 断点设置

```gdb
# 并发标记入口
break g1ConcurrentMark.cpp:mark_from_roots

# SATB处理
break g1ConcurrentMark.cpp:drain_satb_buffers

# Remark
break g1ConcurrentMark.cpp:remark
```

### 2. 查看标记位图状态

```gdb
(gdb) p _next_mark_bitmap->_bm._map
$1 = (BitMap::bm_word_t *) 0x7f0010000000

(gdb) p _next_mark_bitmap->_covered
$2 = {_start = 0x600000000, _word_size = 1073741824}
```

### 3. 查看SATB队列

```gdb
(gdb) p G1BarrierSet::satb_mark_queue_set()._cbl_list_head
$3 = (BufferList::Node *) 0x7f0050001000

(gdb) p ((BufferList::Node *)0x7f0050001000)->_elem_count
$4 = 256
```

---

## 总结：高频考点

| 考点 | 答案要点 |
|------|----------|
| 漏标问题 | 黑→白引用+删除灰→白引用 |
| SATB原理 | 写前屏障记录旧值，保留快照 |
| SATB vs IU | SATB Remark快，IU浮动垃圾少 |
| 五阶段 | Initial Mark/Root Scan/Mark/Remark/Cleanup |
| SATB处理 | 并发标记中+Remark阶段 |
| 性能调优 | ConcGCThreads/IHOP/SATBBufferSize |
| 并发失败 | 触发Full GC，需提前预防 |

---

**下一篇**: [05-记忆集与卡表](./05-记忆集与卡表.md)
