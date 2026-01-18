# 20-引用类型与ReferenceHandler

## 面试官：Java有哪几种引用类型？它们的区别是什么？

### 答案

#### 1. 四种引用类型

| 引用类型 | 类 | 回收时机 | 用途 |
|----------|-------|----------|------|
| 强引用 | 普通引用 | 从不回收（除非不可达） | 普通对象引用 |
| 软引用 | SoftReference | 内存不足时回收 | 缓存 |
| 弱引用 | WeakReference | 下次GC时回收 | WeakHashMap |
| 虚引用 | PhantomReference | 任何时候（仅通知） | 跟踪对象回收 |

#### 2. 引用强度排序

```
强引用 > 软引用 > 弱引用 > 虚引用

Strong → GC永不回收（对象可达时）
  │
  ▼
Soft → 内存不足时回收
  │
  ▼
Weak → 下次GC回收
  │
  ▼
Phantom → 对象被回收后通知（不能获取referent）
```

#### 3. Reference类结构

```java
public abstract class Reference<T> {
    private T referent;           // 指向的对象
    volatile ReferenceQueue<? super T> queue;  // 关联的队列
    volatile Reference next;      // 队列链表
    transient private Reference<T> discovered;  // GC发现链表
    
    // 状态转换:
    // Active -> Pending -> Enqueued -> Inactive
}
```

---

## 面试官：JVM内部如何处理不同类型的引用？

### 答案

#### 1. ReferenceProcessor职责

```cpp:96:135:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
ReferenceProcessor::ReferenceProcessor(BoolObjectClosure* is_subject_to_discovery,
                                       bool      mt_processing,
                                       uint      mt_processing_degree,
                                       bool      mt_discovery,
                                       uint      mt_discovery_degree,
                                       bool      atomic_discovery,
                                       BoolObjectClosure* is_alive_non_header,
                                       bool      adjust_no_of_processing_threads)  :
  _is_subject_to_discovery(is_subject_to_discovery),
  _discovering_refs(false),
  _enqueuing_is_done(false),
  _is_alive_non_header(is_alive_non_header),
  _processing_is_mt(mt_processing),
  _next_id(0),
  _adjust_no_of_processing_threads(adjust_no_of_processing_threads)
{
  assert(is_subject_to_discovery != NULL, "must be set");

  _discovery_is_atomic = atomic_discovery;
  _discovery_is_mt     = mt_discovery;
  _num_queues          = MAX2(1U, mt_processing_degree);
  _max_num_queues      = MAX2(_num_queues, mt_discovery_degree);
  _discovered_refs     = NEW_C_HEAP_ARRAY(DiscoveredList,
            _max_num_queues * number_of_subclasses_of_ref(), mtGC);

  if (_discovered_refs == NULL) {
    vm_exit_during_initialization("Could not allocated RefProc Array");
  }
  _discoveredSoftRefs    = &_discovered_refs[0];
  _discoveredWeakRefs    = &_discoveredSoftRefs[_max_num_queues];
  _discoveredFinalRefs   = &_discoveredWeakRefs[_max_num_queues];
  _discoveredPhantomRefs = &_discoveredFinalRefs[_max_num_queues];
```

#### 2. 四种Discovered链表

```
ReferenceProcessor维护四组发现列表:

_discoveredSoftRefs[0..N]     // 软引用列表
_discoveredWeakRefs[0..N]     // 弱引用列表
_discoveredFinalRefs[0..N]    // Final引用列表
_discoveredPhantomRefs[0..N]  // 虚引用列表

每组有N个列表用于并行处理
```

---

## 面试官：引用发现(Reference Discovery)过程是怎样的？

### 答案

#### 1. 发现入口

```cpp:1099:1109:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
bool ReferenceProcessor::discover_reference(oop obj, ReferenceType rt) {
  // Make sure we are discovering refs (rather than processing discovered refs).
  if (!_discovering_refs || !RegisterReferences) {
    return false;
  }

  if ((rt == REF_FINAL) && (java_lang_ref_Reference::next(obj) != NULL)) {
    // Don't rediscover non-active FinalReferences.
    return false;
  }
```

#### 2. 发现时机

```
GC标记过程中:

1. 扫描到Reference对象
2. 检查referent是否存活
3. 如果referent不可达：
   - 将Reference对象加入对应的discovered列表
   - 不标记referent（允许被回收）
```

#### 3. 发现流程伪代码

```cpp
bool discover_reference(oop ref, ReferenceType rt) {
    // 1. 检查是否正在发现阶段
    if (!_discovering_refs) return false;
    
    // 2. 获取referent
    oop referent = java_lang_ref_Reference::referent(ref);
    
    // 3. 检查referent是否存活
    if (is_alive(referent)) {
        return false;  // referent存活，不发现
    }
    
    // 4. 根据类型选择列表
    DiscoveredList* list = get_list_for_type(rt);
    
    // 5. 加入发现列表
    add_to_discovered_list(ref, list);
    
    return true;
}
```

---

## 面试官：GC过程中引用处理的四个阶段是什么？

### 答案

#### 1. 处理入口

```cpp:201:260:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
ReferenceProcessorStats ReferenceProcessor::process_discovered_references(
  BoolObjectClosure*            is_alive,
  OopClosure*                   keep_alive,
  VoidClosure*                  complete_gc,
  AbstractRefProcTaskExecutor*  task_executor,
  ReferenceProcessorPhaseTimes* phase_times) {

  double start_time = os::elapsedTime();

  assert(!enqueuing_is_done(), "If here enqueuing should not be complete");
  // Stop treating discovered references specially.
  disable_discovery();
  
  ...
  
  // Phase 1: 软引用策略判断
  {
    RefProcTotalPhaseTimesTracker tt(RefPhase1, phase_times, this);
    process_soft_ref_reconsider(is_alive, keep_alive, complete_gc,
                                task_executor, phase_times);
  }

  update_soft_ref_master_clock();

  // Phase 2: 处理软引用、弱引用、Final引用
  {
    RefProcTotalPhaseTimesTracker tt(RefPhase2, phase_times, this);
    process_soft_weak_final_refs(is_alive, keep_alive, complete_gc, task_executor, phase_times);
  }

  // Phase 3: Final引用特殊处理
  {
    RefProcTotalPhaseTimesTracker tt(RefPhase3, phase_times, this);
    process_final_keep_alive(keep_alive, complete_gc, task_executor, phase_times);
  }

  // Phase 4: 处理虚引用
  {
    RefProcTotalPhaseTimesTracker tt(RefPhase4, phase_times, this);
    process_phantom_refs(is_alive, keep_alive, complete_gc, task_executor, phase_times);
  }
```

#### 2. 四阶段详解

```
Phase 1: Soft Reference策略判断
┌─────────────────────────────────────────────────────────────┐
│ 对每个SoftReference:                                        │
│   if (referent存活 || 策略决定保留) {                       │
│       从discovered列表移除                                   │
│       保持referent存活                                       │
│   }                                                         │
└─────────────────────────────────────────────────────────────┘
                        ↓
Phase 2: 处理Soft/Weak/Final引用
┌─────────────────────────────────────────────────────────────┐
│ 对每个引用:                                                  │
│   if (referent已被回收 || referent存活) {                   │
│       从列表移除                                             │
│   } else {                                                   │
│       清除referent字段                                       │
│       加入pending队列                                        │
│   }                                                         │
└─────────────────────────────────────────────────────────────┘
                        ↓
Phase 3: Final引用特殊处理
┌─────────────────────────────────────────────────────────────┐
│ 对每个FinalReference:                                       │
│   保持referent存活（让Finalizer能执行）                      │
│   加入pending队列                                            │
│   设置next指向自己（标记为非活跃）                           │
└─────────────────────────────────────────────────────────────┘
                        ↓
Phase 4: 处理Phantom引用
┌─────────────────────────────────────────────────────────────┐
│ 对每个PhantomReference:                                     │
│   if (referent存活) {                                       │
│       从列表移除                                             │
│   } else {                                                   │
│       清除referent                                           │
│       加入pending队列                                        │
│   }                                                         │
└─────────────────────────────────────────────────────────────┘
```

---

## 面试官：软引用的清理策略是什么？

### 答案

#### 1. 软引用策略初始化

```cpp:60:68:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
  _always_clear_soft_ref_policy = new AlwaysClearPolicy();
  if (is_server_compilation_mode_vm()) {
    _default_soft_ref_policy = new LRUMaxHeapPolicy();
  } else {
    _default_soft_ref_policy = new LRUCurrentHeapPolicy();
  }
```

#### 2. 两种策略

**LRUCurrentHeapPolicy（客户端）**:
```java
// 清理条件: timestamp > clock - 空闲内存(MB) * ms_per_mb
// ms_per_mb = SoftRefLRUPolicyMSPerMB (默认1000ms)

// 例如: 100MB空闲，则保留最近100秒内访问过的软引用
should_clear = (clock - timestamp) > free_heap_MB * 1000;
```

**LRUMaxHeapPolicy（服务端）**:
```java
// 清理条件: timestamp > clock - 最大堆(MB) * 比例因子 * ms_per_mb
// 比例因子 = (free_heap / max_heap)

// 堆压力越大，清理阈值越低
```

#### 3. 策略应用

```cpp:341:370:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
size_t ReferenceProcessor::process_soft_ref_reconsider_work(DiscoveredList&    refs_list,
                                                            ReferencePolicy*   policy,
                                                            BoolObjectClosure* is_alive,
                                                            OopClosure*        keep_alive,
                                                            VoidClosure*       complete_gc) {
  assert(policy != NULL, "Must have a non-NULL policy");
  DiscoveredListIterator iter(refs_list, keep_alive, is_alive);
  // Decide which softly reachable refs should be kept alive.
  while (iter.has_next()) {
    iter.load_ptrs(DEBUG_ONLY(!discovery_is_atomic() /* allow_null_referent */));
    bool referent_is_dead = (iter.referent() != NULL) && !iter.is_referent_alive();
    if (referent_is_dead &&
        !policy->should_clear_reference(iter.obj(), _soft_ref_timestamp_clock)) {
      log_dropped_ref(iter, "by policy");
      // Remove Reference object from list
      iter.remove();
      // keep the referent around
      iter.make_referent_alive();
      iter.move_to_next();
    } else {
      iter.next();
    }
  }
```

---

## 面试官：ReferenceHandler线程是做什么的？

### 答案

#### 1. ReferenceHandler职责

```java
// java.lang.ref.Reference$ReferenceHandler
private static class ReferenceHandler extends Thread {
    public void run() {
        while (true) {
            processPendingReferences();
        }
    }
    
    private static void processPendingReferences() {
        // 等待pending列表非空
        waitForReferencePendingList();
        
        // 获取pending引用
        Reference<?> pendingList;
        synchronized (processPendingLock) {
            pendingList = getAndClearReferencePendingList();
        }
        
        // 处理每个引用
        while (pendingList != null) {
            Reference<?> ref = pendingList;
            pendingList = ref.discovered;
            ref.discovered = null;
            
            if (ref instanceof Cleaner) {
                // Cleaner特殊处理：直接执行clean()
                ((Cleaner)ref).clean();
            } else {
                // 普通引用：入队到ReferenceQueue
                ReferenceQueue<? super Object> q = ref.queue;
                if (q != ReferenceQueue.NULL) {
                    q.enqueue(ref);
                }
            }
        }
    }
}
```

#### 2. Pending链表交接

```
GC线程完成引用处理后:
┌───────────────────────────────────────────────────────────┐
│                     GC 线程                               │
│  ┌──────────────────────────────────────────────────┐    │
│  │ 将processed references链接到pending_list          │    │
│  │ Universe::swap_reference_pending_list(refs_head)  │    │
│  └──────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│               Reference Pending List                       │
│  ┌────────┐    ┌────────┐    ┌────────┐                   │
│  │ SoftRef│───→│ WeakRef│───→│ Phantom│───→ NULL          │
│  └────────┘    └────────┘    └────────┘                   │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│              ReferenceHandler Thread                       │
│  ┌──────────────────────────────────────────────────┐    │
│  │ 获取pending_list                                  │    │
│  │ 遍历链表，将每个Reference加入其ReferenceQueue     │    │
│  └──────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────┘
```

#### 3. 入队操作

```cpp:313:321:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
void DiscoveredListIterator::complete_enqueue() {
  if (_prev_discovered != NULL) {
    // This is the last object.
    // Swap refs_list into pending list and set obj's
    // discovered to what we read from the pending list.
    oop old = Universe::swap_reference_pending_list(_refs_list.head());
    HeapAccess<AS_NO_KEEPALIVE>::oop_store_at(_prev_discovered, java_lang_ref_Reference::discovered_offset, old);
  }
}
```

---

## 面试官：Cleaner和普通引用有什么区别？

### 答案

#### 1. Cleaner特殊性

```java
public class Cleaner extends PhantomReference<Object> {
    private final Runnable thunk;  // 清理动作
    
    // Cleaner不需要ReferenceQueue
    private Cleaner(Object referent, Runnable thunk) {
        super(referent, dummyQueue);  // 使用虚拟队列
        this.thunk = thunk;
    }
    
    public void clean() {
        // 从链表移除
        if (remove(this)) {
            try {
                thunk.run();  // 执行清理动作
            } catch (final Throwable x) {
                // 忽略异常
            }
        }
    }
}
```

#### 2. 处理流程差异

```
普通Reference:
  GC → pending list → ReferenceHandler → ReferenceQueue → 用户poll/remove

Cleaner:
  GC → pending list → ReferenceHandler → 直接执行clean()
```

#### 3. 使用场景

```java
// DirectByteBuffer使用Cleaner释放native内存
DirectByteBuffer(int cap) {
    // 分配native内存
    long base = unsafe.allocateMemory(size);
    
    // 注册Cleaner
    cleaner = Cleaner.create(this, new Deallocator(base, size, cap));
}

private static class Deallocator implements Runnable {
    public void run() {
        unsafe.freeMemory(address);  // 释放native内存
    }
}
```

---

## 面试官：FinalReference和Finalization机制是怎样的？

### 答案

#### 1. FinalReference

```java
// 对于重写了finalize()方法的类
class Finalizer extends FinalReference<Object> {
    private static ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private Runnable finalizerAction;
}
```

#### 2. 对象创建时的处理

```
当创建重写了finalize()的对象时:
1. 分配对象内存
2. 执行构造函数
3. 创建Finalizer对象，包装新对象
4. 将Finalizer注册到全局unfinalized链表
```

#### 3. GC时的处理

```cpp:417:441:openjdk11-core/src/hotspot/share/gc/shared/referenceProcessor.cpp
size_t ReferenceProcessor::process_final_keep_alive_work(DiscoveredList& refs_list,
                                                         OopClosure*     keep_alive,
                                                         VoidClosure*    complete_gc) {
  DiscoveredListIterator iter(refs_list, keep_alive, NULL);
  while (iter.has_next()) {
    iter.load_ptrs(DEBUG_ONLY(false /* allow_null_referent */));
    // keep the referent and followers around
    iter.make_referent_alive();  // 保持对象存活，让finalize能执行

    // Self-loop next, to mark the FinalReference not active.
    assert(java_lang_ref_Reference::next(iter.obj()) == NULL, "enqueued FinalReference");
    java_lang_ref_Reference::set_next_raw(iter.obj(), iter.obj());

    iter.enqueue();
    log_enqueued_ref(iter, "Final");
    iter.next();
  }
```

#### 4. Finalizer线程

```java
// FinalizerThread处理
private static class FinalizerThread extends Thread {
    public void run() {
        while (true) {
            Finalizer f = (Finalizer)queue.remove();
            f.runFinalizer();  // 调用对象的finalize()方法
        }
    }
}
```

#### 5. Finalization问题

```
性能问题:
1. 对象生命周期延长（至少两次GC）
2. Finalizer线程单独执行（串行瓶颈）
3. 可能导致内存泄漏（finalize执行慢）

建议:
- 使用try-with-resources
- 使用Cleaner替代finalize
- 避免重写finalize()
```

---

## 知识图谱

```
                    ┌─────────────────────────────────────────────────┐
                    │              Java引用类型处理体系                │
                    └─────────────────────────────────────────────────┘
                                          │
       ┌──────────────────────────────────┼──────────────────────────────────┐
       ▼                                  ▼                                  ▼
┌─────────────────┐            ┌─────────────────┐            ┌─────────────────┐
│  引用发现阶段    │            │  引用处理阶段    │            │  入队阶段        │
├─────────────────┤            ├─────────────────┤            ├─────────────────┤
│ GC标记时发现    │            │ Phase 1: Soft策略│            │ pending_list    │
│ 检查referent   │            │ Phase 2: 清除引用│            │ ReferenceHandler│
│ 加入discovered │            │ Phase 3: Final  │            │ ReferenceQueue  │
│ 列表           │            │ Phase 4: Phantom│            │                 │
└─────────────────┘            └─────────────────┘            └─────────────────┘

引用强度与GC关系:
┌───────────────────────────────────────────────────────────────────────────────┐
│ Strong ──────→ GC时不回收（可达时）                                           │
│    │                                                                          │
│    ▼                                                                          │
│ Soft ────────→ 内存不足时回收，有LRU策略                                      │
│    │           SoftRefLRUPolicyMSPerMB控制                                    │
│    ▼                                                                          │
│ Weak ────────→ 下次GC回收，无论内存是否充足                                   │
│    │                                                                          │
│    ▼                                                                          │
│ Phantom ─────→ 对象被回收后通知，不能获取referent                             │
└───────────────────────────────────────────────────────────────────────────────┘
```
