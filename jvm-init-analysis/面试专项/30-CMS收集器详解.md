# 30. CMS收集器详解

## 一、概述

CMS(Concurrent Mark Sweep)收集器是一款以低延迟为目标的垃圾收集器。它通过将大部分GC工作与应用线程并发执行，来减少停顿时间。

### 1.1 设计目标

```
┌─────────────────────────────────────────────────────────────┐
│                    CMS设计目标                               │
├─────────────────────────────────────────────────────────────┤
│  1. 最小化停顿时间（低延迟优先）                              │
│  2. 与应用线程并发执行标记和清除                              │
│  3. 只收集老年代（年轻代配合ParNew使用）                      │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 核心特点

- **并发标记**：标记阶段与应用线程并发执行
- **增量更新**：使用写屏障跟踪并发期间的引用变化
- **标记-清除算法**：不进行压缩，可能产生碎片

---

## 二、CMSCollector核心结构

### 2.1 类定义

**源码**（`concurrentMarkSweepGeneration.hpp:495-640`）：
```cpp
class CMSCollector: public CHeapObj<mtGC> {
  friend class VMStructs;
  friend class ConcurrentMarkSweepThread;
  friend class ConcurrentMarkSweepGeneration;
  
 private:
  jlong _time_of_last_gc;              // 上次GC时间
  OopTaskQueueSet* _task_queues;       // 并行任务队列

  // 溢出列表（用于并发标记）
  oopDesc* volatile _overflow_list;
  Stack<oop, mtGC>     _preserved_oop_stack;
  Stack<markOop, mtGC> _preserved_mark_stack;

  // 并发工作线程
  YieldingFlexibleWorkGang* _conc_workers;

  // 性能计数器
  CollectorCounters* _gc_counters;
  CollectorCounters* _cgc_counters;

  // 是否卸载类
  bool _should_unload_classes;
  unsigned int _concurrent_cycles_since_last_unload;

 protected:
  ConcurrentMarkSweepGeneration* _cmsGen;  // 老年代
  MemRegion                      _span;     // 内存范围
  CardTableRS*                   _ct;       // 卡表

  // CMS标记支持结构
  CMSBitMap     _markBitMap;       // 标记位图
  CMSBitMap     _modUnionTable;    // Mod Union表
  CMSMarkStack  _markStack;        // 标记栈

  HeapWord*     _restart_addr;     // 标记栈溢出时的重启地址
```

### 2.2 收集器状态

```cpp
// CMS收集器状态
enum CollectorState {
  Idling,           // 空闲状态
  InitialMarking,   // 初始标记（STW）
  Marking,          // 并发标记
  Precleaning,      // 预清理
  AbortablePreclean,// 可中断预清理
  FinalMarking,     // 最终标记（STW）
  Sweeping,         // 并发清除
  Resizing,         // 调整大小
  Resetting         // 重置状态
};
```

---

## 三、CMS收集阶段详解

### 3.1 收集流程概览

**源码**（`concurrentMarkSweepGeneration.cpp:1715-1902`）：
```cpp
void CMSCollector::collect_in_background(GCCause::Cause cause) {
  assert(Thread::current()->is_ConcurrentGC_thread(),
    "A CMS asynchronous collection is only allowed on a CMS thread.");

  CMSHeap* heap = CMSHeap::heap();
  
  // 设置初始状态
  _collectorState = InitialMarking;
  
  // 主循环执行各阶段
  while (_collectorState != Idling) {
    switch (_collectorState) {
      case InitialMarking:
        {
          // 阶段1: 初始标记（STW）
          ReleaseForegroundGC x(this);
          stats().record_cms_begin();
          VM_CMS_Initial_Mark initial_mark_op(this);
          VMThread::execute(&initial_mark_op);
        }
        break;
        
      case Marking:
        // 阶段2: 并发标记
        if (markFromRoots()) {
          assert(_collectorState == Precleaning, "...");
        }
        break;
        
      case Precleaning:
        // 阶段3: 预清理
        preclean();
        break;
        
      case AbortablePreclean:
        // 阶段4: 可中断预清理
        abortable_preclean();
        break;
        
      case FinalMarking:
        {
          // 阶段5: 最终标记（STW）
          ReleaseForegroundGC x(this);
          VM_CMS_Final_Remark final_remark_op(this);
          VMThread::execute(&final_remark_op);
        }
        break;
        
      case Sweeping:
        // 阶段6: 并发清除
        sweep();
        break;
        
      case Resizing:
        // 阶段7: 调整堆大小
        compute_new_size();
        _collectorState = Resetting;
        break;
        
      case Resetting:
        // 阶段8: 重置状态
        reset_concurrent();
        break;
    }
  }
}
```

### 3.2 初始标记(Initial Mark)

**STW阶段，标记GC Roots直接可达的对象**

**源码**（`concurrentMarkSweepGeneration.cpp:2809-2830`）：
```cpp
void CMSCollector::checkpointRootsInitial() {
  assert(_collectorState == InitialMarking, "Wrong collector state");
  check_correct_thread_executing();
  TraceCMSMemoryManagerStats tms(_collectorState, CMSHeap::heap()->gc_cause());

  save_heap_summary();
  report_heap_summary(GCWhen::BeforeGC);

  ReferenceProcessor* rp = ref_processor();
  assert(_restart_addr == NULL, "Control point invariant");
  {
    MutexLockerEx x(bitMapLock(), Mutex::_no_safepoint_check_flag);
    // 执行初始标记工作
    checkpointRootsInitialWork();
    // 启用弱引用发现
    rp->enable_discovery();
    // 状态转换到Marking
    _collectorState = Marking;
  }
}
```

**初始标记工作**：
```cpp
void CMSCollector::checkpointRootsInitialWork() {
  assert(SafepointSynchronize::is_at_safepoint(), "world should be stopped");
  assert(_collectorState == InitialMarking, "just checking");

  // 确保位图已清空
  assert(_markBitMap.isAllClear(), "was reset at end of previous cycle");

  // 设置类卸载和验证状态
  setup_cms_unloading_and_verification_state();

  // 标记GC Roots
  // 包括：线程栈、JNI Handles、System Dictionary等
  CMSParInitialMarkTask task(this, strong_roots_scope, n_workers);
  workers()->run_task(&task);
}
```

### 3.3 并发标记(Concurrent Marking)

**与应用线程并发执行，遍历对象图**

```cpp
bool CMSCollector::markFromRoots() {
  // 并发标记从初始标记的对象开始
  CMSConcMarkingTask markingTask(this, _span, ...);
  
  if (CMSConcurrentMTEnabled && ConcGCThreads > 0) {
    // 多线程并发标记
    _conc_workers->start_task(&markingTask);
    _conc_workers->wait_for_task();
  } else {
    // 单线程标记
    markingTask.work(0);
  }
  
  // 标记完成后转换到预清理状态
  _collectorState = Precleaning;
  return true;
}
```

### 3.4 预清理(Preclean)

**处理并发标记期间的引用变化**

```cpp
void CMSCollector::preclean() {
  // 处理卡表中的脏卡
  // 这些脏卡记录了并发标记期间发生变化的区域
  
  // 扫描Mod Union Table
  preclean_mod_union_table(_cmsGen, &markFromDirtyCardsClosure);
  
  // 扫描卡表
  preclean_card_table(_cmsGen);
  
  // 转换状态
  if (CMSPrecleaningEnabled) {
    _collectorState = AbortablePreclean;
  } else {
    _collectorState = FinalMarking;
  }
}
```

### 3.5 可中断预清理(Abortable Preclean)

**在等待适当时机进入最终标记**

```cpp
void CMSCollector::abortable_preclean() {
  // 循环执行预清理，直到满足条件
  while (should_continue_preclean()) {
    // 继续处理脏卡
    do_preclean_work();
    
    // 检查是否应该中断
    if (should_abort_preclean()) {
      break;
    }
    
    // 让出CPU，避免独占
    ConcurrentMarkSweepThread::yield();
  }
  
  _collectorState = FinalMarking;
}

bool CMSCollector::should_abort_preclean() {
  // 当Eden使用率达到阈值时，中断预清理
  // 这样可以在下次Young GC时进入Final Remark
  // 减少Final Remark的工作量
  return eden_usage >= CMSScheduleRemarkEdenPenetration;
}
```

### 3.6 最终标记(Final Remark)

**STW阶段，处理并发期间遗漏的对象**

```cpp
void CMSCollector::checkpointRootsFinal() {
  assert(_collectorState == FinalMarking, "incorrect state transition?");
  
  // 在安全点执行
  checkpointRootsFinalWork();
  
  // 处理引用
  refProcessingWork();
  
  // 转换到清除状态
  _collectorState = Sweeping;
}

void CMSCollector::checkpointRootsFinalWork() {
  // 1. 再次扫描GC Roots
  // 2. 处理Dirty Cards（并发期间修改的卡）
  // 3. 遍历Mod Union Table
  // 4. 处理Java引用类型
  
  // 并行执行最终标记
  CMSParRemarkTask remarkTask(this, n_workers);
  workers()->run_task(&remarkTask);
}
```

### 3.7 并发清除(Concurrent Sweep)

**与应用线程并发，回收未标记的对象**

```cpp
void CMSCollector::sweep() {
  // 并发清除老年代
  _cmsGen->cmsSpace()->sweep();
  
  // 更新空闲列表
  _cmsGen->cmsSpace()->coalesceFreeSpace();
  
  // 转换状态
  _collectorState = Resizing;
}

// CompactibleFreeListSpace的清除实现
void CompactibleFreeListSpace::sweep() {
  // 遍历老年代，回收未标记对象
  HeapWord* addr = bottom();
  while (addr < end()) {
    if (!_markBitMap->isMarked(addr)) {
      // 对象未标记，回收到空闲列表
      size_t size = object_size(addr);
      add_to_free_list(addr, size);
      addr += size;
    } else {
      // 对象存活，跳过
      addr += object_size(addr);
    }
  }
}
```

---

## 四、增量更新(Incremental Update)

### 4.1 写屏障实现

CMS使用增量更新(Incremental Update)来处理并发标记期间的引用变化：

```cpp
// CMS写后屏障
void CMSBarrierSet::write_ref_field_work(oop* field, oop new_val) {
  // 标记卡表
  if (_card_table->is_in(field)) {
    _card_table->mark_card(field);
  }
}
```

### 4.2 Mod Union Table

**记录并发期间的修改**：
```cpp
// Mod Union Table是卡表的镜像
// 用于记录并发标记期间哪些卡被修改过
class CMSBitMap {
  // 每个bit对应一个卡
  BitMap _bm;
  
  void mark(HeapWord* addr) {
    _bm.set_bit(addr_to_bit(addr));
  }
  
  bool isMarked(HeapWord* addr) {
    return _bm.at(addr_to_bit(addr));
  }
};
```

---

## 五、Concurrent Mode Failure

### 5.1 触发条件

当CMS无法完成并发收集时，会触发Concurrent Mode Failure：

```cpp
// 检查是否需要Full GC
bool CMSCollector::concurrent_mode_failed() {
  // 1. 老年代空间不足
  // 2. 晋升失败
  // 3. 无法分配大对象
  return _cmsGen->promotion_failed() || 
         !_cmsGen->cmsSpace()->check_free_space(size);
}
```

### 5.2 处理方式

```cpp
// 退化为Serial Old收集
void CMSCollector::do_concurrent_mode_failure() {
  // 停止所有应用线程
  // 执行Full GC（Serial Old Mark-Compact）
  VM_GenCollectFull op(GenCollectCause::_gc_locker, ...);
  VMThread::execute(&op);
}
```

---

## 六、CMS参数配置

### 6.1 关键参数

```bash
# 启用CMS收集器
-XX:+UseConcMarkSweepGC

# 并行GC线程数（Young GC）
-XX:ParallelGCThreads=N

# 并发GC线程数（CMS并发阶段）
-XX:ConcGCThreads=N

# 老年代使用率达到该值时触发CMS
-XX:CMSInitiatingOccupancyFraction=70
-XX:+UseCMSInitiatingOccupancyOnly

# 在Full GC时压缩老年代
-XX:+UseCMSCompactAtFullCollection
-XX:CMSFullGCsBeforeCompaction=N

# 类卸载
-XX:+CMSClassUnloadingEnabled

# 预清理参数
-XX:+CMSPrecleaningEnabled
-XX:CMSScheduleRemarkEdenPenetration=50
```

### 6.2 调优建议

```bash
# 典型配置示例
-XX:+UseConcMarkSweepGC
-XX:+UseParNewGC
-XX:CMSInitiatingOccupancyFraction=75
-XX:+UseCMSInitiatingOccupancyOnly
-XX:+CMSParallelRemarkEnabled
-XX:+CMSScavengeBeforeRemark
```

---

## 七、面试要点

### 7.1 常见问题

**Q1: CMS的收集阶段有哪些？哪些是STW的？**
```
A: CMS有7个阶段：
1. 初始标记(Initial Mark) - STW，标记GC Roots直接可达对象
2. 并发标记(Concurrent Mark) - 并发，遍历对象图
3. 预清理(Preclean) - 并发，处理卡表脏卡
4. 可中断预清理(Abortable Preclean) - 并发，等待适当时机
5. 最终标记(Final Remark) - STW，处理并发期间的变化
6. 并发清除(Concurrent Sweep) - 并发，回收未标记对象
7. 并发重置(Concurrent Reset) - 并发，重置数据结构

只有Initial Mark和Final Remark是STW的
```

**Q2: CMS为什么会产生内存碎片？如何解决？**
```
A: 原因：
- CMS使用标记-清除算法，不进行压缩
- 清除后形成不连续的空闲空间

解决方案：
1. -XX:+UseCMSCompactAtFullCollection：Full GC时压缩
2. -XX:CMSFullGCsBeforeCompaction=N：N次Full GC后压缩
3. 考虑迁移到G1收集器
```

**Q3: 什么是Concurrent Mode Failure？如何避免？**
```
A: 原因：
- CMS并发收集期间，老年代空间不足
- 无法为新晋升的对象分配空间

避免方法：
1. 降低-XX:CMSInitiatingOccupancyFraction，提前触发GC
2. 增加老年代大小
3. 减少对象晋升速度（调整年轻代大小）
4. 使用-XX:+UseCMSInitiatingOccupancyOnly固定触发阈值
```

**Q4: CMS和G1的主要区别？**
```
A: 主要区别：
1. 内存布局：
   - CMS：传统分代，连续空间
   - G1：Region分区，灵活布局

2. 停顿预测：
   - CMS：无停顿目标
   - G1：可预测停顿时间(-XX:MaxGCPauseMillis)

3. 碎片问题：
   - CMS：标记-清除，有碎片
   - G1：复制算法，无碎片

4. 适用场景：
   - CMS：中等堆大小，低延迟
   - G1：大堆，平衡吞吐量和延迟
```

### 7.2 源码关键类

| 类 | 源文件 | 功能 |
|---|--------|------|
| CMSCollector | `concurrentMarkSweepGeneration.cpp` | CMS收集器核心 |
| ConcurrentMarkSweepThread | `concurrentMarkSweepThread.cpp` | CMS后台线程 |
| CMSBitMap | `concurrentMarkSweepGeneration.hpp` | 标记位图 |
| CompactibleFreeListSpace | `compactibleFreeListSpace.cpp` | 老年代空间实现 |

---

## 八、CMS状态图

```
                    ┌─────────────────────────────────────────────┐
                    │                                             │
                    ▼                                             │
┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌──────────┐
│  Idling  │───▶│InitialMarking│───▶│  Marking   │───▶│Precleaning│
└──────────┘    │   (STW)      │    │(Concurrent)│    │(Concurrent)│
     ▲          └──────────────┘    └────────────┘    └─────┬──────┘
     │                                                      │
     │                                                      ▼
     │          ┌──────────────┐    ┌────────────┐    ┌──────────────┐
     │          │   Resetting  │◀───│  Sweeping  │◀───│ FinalMarking │
     │          │ (Concurrent) │    │(Concurrent)│    │    (STW)     │
     │          └──────┬───────┘    └────────────┘    └──────────────┘
     │                 │                                    ▲
     │                 ▼                                    │
     │          ┌──────────────┐    ┌────────────────────┘
     └──────────│   Resizing   │    │AbortablePreclean
                │              │◀───│  (Concurrent)
                └──────────────┘    └────────────────────
```

---

## 九、总结

### 9.1 CMS优势

1. **低延迟**：大部分工作并发执行
2. **适合交互式应用**：减少停顿时间
3. **成熟稳定**：经过多年生产验证

### 9.2 CMS劣势

1. **内存碎片**：标记-清除算法的固有问题
2. **CPU敏感**：并发阶段占用CPU资源
3. **浮动垃圾**：并发清除期间产生的新垃圾
4. **已废弃**：JDK 9标记为废弃，JDK 14移除

### 9.3 迁移建议

```
CMS用户迁移路径：
1. JDK 8-11：可以继续使用CMS
2. JDK 11+：建议迁移到G1
3. JDK 14+：必须迁移，CMS已移除

迁移命令：
# CMS配置
-XX:+UseConcMarkSweepGC -XX:CMSInitiatingOccupancyFraction=75

# 对应G1配置
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
```
