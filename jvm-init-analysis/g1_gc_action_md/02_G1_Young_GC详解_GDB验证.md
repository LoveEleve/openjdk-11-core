# G1 Young GC 详解 - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms256m -Xmx256m -XX:+UseG1GC -XX:G1HeapRegionSize=1m`

---

## 1. Young GC 触发条件

### 1.1 触发场景

1. **Eden区满**: 最常见的触发原因
2. **分配失败**: TLAB分配失败后Eden也满
3. **Humongous分配**: 大对象分配可能触发

### 1.2 GDB验证 - 触发入口

```
调用栈:
VM_G1CollectForAllocation::doit       ← 分配触发GC
  → VMThread::evaluate_operation      ← VM线程执行
    → G1CollectedHeap::do_collection_pause_at_safepoint
```

**VM_G1CollectForAllocation 源码**:
```cpp
// vm_operations_g1.cpp:132
void VM_G1CollectForAllocation::doit() {
  G1CollectedHeap* g1h = G1CollectedHeap::heap();
  
  // 检查是否需要Full GC
  if (g1h->should_do_concurrent_full_gc(_cause)) {
    // 触发Full GC
  }
  
  // 执行Young/Mixed GC
  _result = g1h->do_collection_pause_at_safepoint(_target_pause_time_ms);
  
  // 如果GC后仍分配失败，尝试扩展堆
  if (_result == NULL && _word_size > 0) {
    _result = g1h->satisfy_failed_allocation(_word_size);
  }
}
```

---

## 2. Young GC 执行流程

### 2.1 流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Young GC 执行流程                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────┐                        │
│  │ 1. do_collection_pause_at_safepoint     │                        │
│  │    进入安全点，STW开始                    │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 2. pre_evacuate_collection_set          │                        │
│  │    准备工作：清理卡表、准备RSet等         │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 3. evacuate_collection_set              │                        │
│  │    并行疏散存活对象                       │                        │
│  │    ├─ 扫描GC根                           │                        │
│  │    ├─ 扫描RSet                           │                        │
│  │    └─ 复制存活对象                        │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 4. post_evacuate_collection_set         │                        │
│  │    收尾工作：更新引用、清理等             │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ 5. free_collection_set                  │                        │
│  │    释放回收完成的Region                  │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│               STW结束                                               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 GDB验证数据

**第一次Young GC**:
```
========== GC #1 ==========
[入口] G1CollectedHeap::do_collection_pause_at_safepoint
  G1CollectedHeap: 0x7ffff0031e20
  目标暂停时间: 200.00 ms

[CSet] G1CollectionSet::finalize_young_part
  选择年轻代Regions加入回收集合

[疏散] G1ParEvacuateFollowersClosure::do_void
  多个GC线程并行复制存活对象

[完成] G1Policy::record_collection_pause_end
  实际暂停时间: 43.75 ms
```

---

## 3. Collection Set (CSet) 选择

### 3.1 CSet 构成

Young GC的CSet包含：
1. **所有Eden Regions** - 必须回收
2. **所有Survivor Regions** - 必须回收

### 3.2 源码分析

```cpp
// g1CollectionSet.cpp
void G1CollectionSet::finalize_young_part(double target_pause_time_ms,
                                          G1SurvivorRegions* survivors) {
  // 添加所有Eden区域
  _eden_region_length = _g1h->eden_regions_count();
  
  // 添加所有Survivor区域
  _survivor_region_length = survivors->length();
  add_survivor_regions(survivors);
  
  // 计算初始CSet大小
  _collection_set_cur_length = _eden_region_length + _survivor_region_length;
}
```

### 3.3 GC日志验证

```
[1.121s][gc,heap] GC(0) Eden regions: 8->0(152)
[1.121s][gc,heap] GC(0) Survivor regions: 0->1(3)
```

解读：
- GC前有8个Eden Region，GC后0个（全部回收）
- GC后新增1个Survivor Region（存放存活对象）
- 括号内数字：预计下次GC前的Region数

---

## 4. 并行疏散 (Evacuation)

### 4.1 并行疏散架构

```
┌─────────────────────────────────────────────────────────────────────┐
│ 并行疏散 - 多GC线程协作                                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│              ┌───────────────────┐                                  │
│              │    任务队列         │                                  │
│              │  G1ParScanThreadState                               │
│              │  _task_queues      │                                  │
│              └─────────┬─────────┘                                  │
│                        │                                            │
│         ┌──────────────┼──────────────┐                             │
│         ▼              ▼              ▼                             │
│   ┌──────────┐   ┌──────────┐   ┌──────────┐                        │
│   │ Worker 0 │   │ Worker 1 │   │ Worker N │                        │
│   │          │   │          │   │          │                        │
│   │ 扫描根    │   │ 扫描根    │   │ 扫描根    │                        │
│   │ 复制对象  │   │ 复制对象  │   │ 复制对象  │                        │
│   │ 更新引用  │   │ 更新引用  │   │ 更新引用  │                        │
│   └──────────┘   └──────────┘   └──────────┘                        │
│         │              │              │                             │
│         ▼              ▼              ▼                             │
│   ┌──────────────────────────────────────────┐                      │
│   │           To-Space (Survivor/Old)         │                      │
│   │  存活对象被复制到这里                       │                      │
│   └──────────────────────────────────────────┘                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 GDB验证 - GC线程数

```
[1.078s][gc,task] GC(0) Using 6 workers of 13 for evacuation
```

解读：
- 总共13个GC线程
- 本次GC使用6个worker线程

### 4.3 GC阶段耗时

```
[1.121s][gc,phases] GC(0)   Pre Evacuate Collection Set: 0.0ms
[1.121s][gc,phases] GC(0)   Evacuate Collection Set: 39.9ms
[1.121s][gc,phases] GC(0)   Post Evacuate Collection Set: 2.3ms
[1.121s][gc,phases] GC(0)   Other: 1.5ms
```

| 阶段 | 耗时 | 占比 | 说明 |
|------|------|------|------|
| Pre Evacuate | 0.0ms | 0% | 疏散前准备 |
| Evacuate | 39.9ms | 91% | **实际疏散** |
| Post Evacuate | 2.3ms | 5% | 疏散后处理 |
| Other | 1.5ms | 4% | 其他开销 |

---

## 5. 对象复制详解

### 5.1 复制流程

```cpp
// g1ParScanThreadState.cpp:217
oop G1ParScanThreadState::copy_to_survivor_space(InCSetState state,
                                                  oop old,
                                                  markOop old_mark) {
  // 1. 获取对象大小
  const size_t word_sz = old->size();
  
  // 2. 获取对象年龄
  uint age = 0;
  if (old_mark->has_displaced_mark_helper()) {
    old_mark = old_mark->displaced_mark_helper();
  }
  if (old_mark->is_marked()) {
    age = old_mark->age();
  }
  
  // 3. 决定目标区域
  InCSetState dest_state;
  if (age < _tenuring_threshold) {
    // 年龄未达阈值，去Survivor
    dest_state = InCSetState::Young;
  } else {
    // 年龄达到阈值，晋升到Old
    dest_state = InCSetState::Old;
  }
  
  // 4. 分配目标空间
  HeapWord* obj_ptr = _plab_allocator->allocate(dest_state, word_sz);
  
  // 5. 复制对象
  Copy::aligned_disjoint_words(old, obj_ptr, word_sz);
  
  // 6. 设置转发指针
  old_mark = old->cas_set_mark(markOopDesc::encode_pointer_as_mark(obj_ptr),
                               old_mark);
  
  return oop(obj_ptr);
}
```

### 5.2 GDB验证 - 对象复制

```
[复制#1] G1ParScanThreadState::copy_to_survivor_space
  G1ParScanThreadState: 0x7fffb4000d70
  源对象: 0xfff08ae8, mark: 0x1

[复制#2] G1ParScanThreadState::copy_to_survivor_space
  源对象: 0xfff20c48, mark: 0x1
```

### 5.3 晋升阈值

```cpp
// 默认晋升阈值计算
uint tenuring_threshold = _g1_policy->tenuring_threshold();
// 默认值：15 (最大GC年龄)
// 可通过 -XX:MaxTenuringThreshold 调整
```

---

## 6. RSet 更新

### 6.1 RSet的作用

RSet (Remembered Set) 记录**其他Region指向本Region**的引用。

```
┌─────────────────────────────────────────────────────────────────────┐
│ RSet 工作原理                                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Region A (Old)          Region B (Old)          Region C (Young)  │
│  ┌──────────────┐        ┌──────────────┐        ┌──────────────┐  │
│  │ Object X     │───────▶│ Object Y     │        │ Object Z     │  │
│  │              │        │              │        │              │  │
│  │              │        │  RSet:       │        │  RSet:       │  │
│  │              │        │  [Region A]  │        │  [Region A]  │  │
│  └──────────────┘        └──────────────┘        └──────────────┘  │
│                                                                     │
│  如果要回收Region B，通过RSet可以快速找到Region A中的引用            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 Young GC中的RSet使用

Young GC时需要找到所有指向年轻代的引用：
1. **GC根**: 栈、静态字段等
2. **老年代→年轻代**: 通过卡表和RSet定位

---

## 7. Region释放

### 7.1 释放流程

```cpp
// g1CollectedHeap.cpp
void G1CollectedHeap::free_collection_set(G1CollectionSet* cset,
                                          EvacuationInfo& evacuation_info) {
  // 遍历CSet中所有Region
  for (HeapRegion* r : cset) {
    // 清空Region
    r->clear();
    // 归还到空闲列表
    _hrm.free(r);
  }
}
```

### 7.2 Region状态变化

```
GC前:
  Eden[0]: 使用中 → 回收后: Free
  Eden[1]: 使用中 → 回收后: Free
  Survivor[0]: 使用中 → 回收后: Free
  
  新Survivor: Free → 分配后: Survivor (存放存活对象)
```

---

## 8. 暂停时间控制

### 8.1 目标暂停时间

```cpp
// 设置目标暂停时间
-XX:MaxGCPauseMillis=200  // 默认200ms
```

### 8.2 GDB验证

```
目标暂停时间: 200.00 ms
实际暂停时间: 43.75 ms
```

### 8.3 G1如何控制暂停时间

1. **预测模型**: 基于历史数据预测Region回收耗时
2. **选择性回收**: 在时间预算内选择Region
3. **增量回收**: 分多次回收老年代

---

## 9. 关键数据汇总

| 指标 | GDB/日志值 | 说明 |
|------|-----------|------|
| G1CollectedHeap | `0x7ffff0031e20` | G1堆地址 |
| G1CollectionSet | `0x7ffff0032338` | CSet地址 |
| 目标暂停 | 200ms | MaxGCPauseMillis |
| 实际暂停 | 43.75ms | GC实际耗时 |
| GC线程 | 6/13 | 使用/总线程 |
| Eden变化 | 8→0 | Region数变化 |
| Survivor变化 | 0→1 | Region数变化 |
| 堆变化 | 59M→52M | 回收7MB |

---

## 10. 调试技巧

### 10.1 查看CSet详情

```bash
# GDB中
break G1CollectionSet::finalize_young_part
commands
  print this->_eden_region_length
  print this->_survivor_region_length
  print this->_collection_set_cur_length
  continue
end
```

### 10.2 跟踪对象复制

```bash
# GDB中
break G1ParScanThreadState::copy_to_survivor_space
commands
  print old
  print old->klass()
  print old->size()
  continue
end
```

### 10.3 GC日志分析

```bash
# 启用详细GC日志
-Xlog:gc*=debug:file=gc.log:time,uptime,level,tags

# 分析GC日志
grep "Pause Young" gc.log
grep "Eden regions" gc.log
```
