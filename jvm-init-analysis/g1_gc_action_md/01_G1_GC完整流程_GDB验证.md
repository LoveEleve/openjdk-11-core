# G1 GC 完整流程 - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms256m -Xmx256m -XX:+UseG1GC -XX:G1HeapRegionSize=1m -XX:MaxGCPauseMillis=200`  
> **测试程序**: HelloWorld.java (构造GC场景)

---

## 1. 测试代码

```java
import java.util.ArrayList;
import java.util.List;

public class HelloWorld {
    static List<byte[]> survivors = new ArrayList<>();  // 存活对象
    static List<byte[]> garbage = new ArrayList<>();    // 垃圾对象
    
    public static void main(String[] args) {
        // 阶段1：分配对象触发Young GC
        triggerYoungGC();
        
        // 阶段2：让对象晋升到老年代
        promoteToOld();
        
        // 阶段3：触发Mixed GC
        triggerMixedGC();
    }
    
    static void triggerYoungGC() {
        // 分配约500MB临时对象
        for (int i = 0; i < 500; i++) {
            garbage.add(new byte[1024 * 1024]);
            if (i % 100 == 99) garbage.clear();
        }
    }
    
    // ... 更多方法
}
```

---

## 2. G1 GC 核心流程概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                    G1 GC 完整流程                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  应用线程分配对象                                                    │
│        │                                                            │
│        ▼ Eden区满 / 分配失败                                        │
│  ┌─────────────────────────────────────────┐                        │
│  │ VM_G1CollectForAllocation::doit         │                        │
│  │ 触发GC操作                               │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ G1CollectedHeap::do_collection_pause_at_safepoint               │
│  │ GC暂停入口 (STW开始)                     │                        │
│  │ 目标暂停时间: 200ms                      │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ G1CollectionSet::finalize_young_part    │ 选择回收Region          │
│  │ G1CollectionSet::finalize_old_part      │ (Young/Mixed)          │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ G1ParEvacuateFollowersClosure::do_void  │ 并行疏散                │
│  │ G1ParScanThreadState::copy_to_survivor  │ 复制存活对象            │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │ G1Policy::record_collection_pause_end   │ 统计GC结果              │
│  │ 更新GC预测模型                           │                        │
│  └────────────────┬────────────────────────┘                        │
│                   ▼                                                 │
│               STW结束                                               │
│                   │                                                 │
│                   ▼ (如果需要并发标记)                               │
│  ┌─────────────────────────────────────────┐                        │
│  │ G1ConcurrentMark::mark_from_roots       │ 并发标记                │
│  │ G1ConcurrentMark::cleanup               │ 清理阶段                │
│  └─────────────────────────────────────────┘                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Young GC 流程 (GDB验证)

### 3.1 Step 1: GC触发入口

**GDB断点**: `G1CollectedHeap::do_collection_pause_at_safepoint`

```
========== GC #1 ==========
[入口] G1CollectedHeap::do_collection_pause_at_safepoint
  G1CollectedHeap: 0x7ffff0031e20
  目标暂停时间: 200.00 ms

调用栈:
#0  G1CollectedHeap::do_collection_pause_at_safepoint
    at g1CollectedHeap.cpp:3035
#1  VM_G1CollectForAllocation::doit
    at vm_operations_g1.cpp:132
#2  VM_Operation::evaluate
    at vmOperations.cpp:67
#3  VMThread::evaluate_operation
    at vmThread.cpp:413
#4  VMThread::loop
    at vmThread.cpp:548
```

**源码分析**:
```cpp
// g1CollectedHeap.cpp:3035
bool G1CollectedHeap::do_collection_pause_at_safepoint(double target_pause_time_ms) {
  // 1. 判断GC类型（Young/Mixed）
  // 2. 选择回收Region (CSet)
  // 3. 执行疏散
  // 4. 更新统计信息
}
```

### 3.2 Step 2: 选择回收集合 (CSet)

**GDB断点**: `G1CollectionSet::finalize_young_part`

```
[CSet] G1CollectionSet::finalize_young_part
  G1CollectionSet: 0x7ffff0032338
  目标暂停时间: 200.00 ms
  选择年轻代Regions加入回收集合
```

**源码分析**:
```cpp
// g1CollectionSet.cpp
void G1CollectionSet::finalize_young_part(double target_pause_time_ms,
                                          G1SurvivorRegions* survivors) {
  // 将所有Eden和Survivor区域加入CSet
  add_eden_regions();
  add_survivor_regions(survivors);
}
```

**CSet构成**:
- **Eden Regions**: 所有Eden区域 (必须回收)
- **Survivor Regions**: 所有Survivor区域 (必须回收)
- **Old Regions**: 根据Mixed GC策略选择 (可选)

### 3.3 Step 3: 老年代Region选择 (Mixed GC)

**GDB断点**: `G1CollectionSet::finalize_old_part`

```
[CSet] G1CollectionSet::finalize_old_part
  time_remaining_ms: 83.45
  选择老年代Regions (Mixed GC)
```

**选择策略**:
```cpp
// g1CollectionSet.cpp
void G1CollectionSet::finalize_old_part(double time_remaining_ms) {
  // 1. 按垃圾比例排序老年代Region
  // 2. 在时间预算内选择垃圾最多的Region
  while (time_remaining_ms > 0 && has_more_regions()) {
    HeapRegion* r = get_next_old_region();
    add_old_region(r);
    time_remaining_ms -= predicted_time(r);
  }
}
```

### 3.4 Step 4: 并行疏散

**GDB断点**: `G1ParEvacuateFollowersClosure::do_void`

```
[疏散] G1ParEvacuateFollowersClosure::do_void
  多个GC线程并行复制存活对象
```

**并行疏散架构**:
```
┌─────────────────────────────────────────────────────────────────────┐
│ 并行疏散 (Parallel Evacuation)                                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ GC Thread #0 │  │ GC Thread #1 │  │ GC Thread #N │              │
│  │              │  │              │  │              │              │
│  │ G1ParScan    │  │ G1ParScan    │  │ G1ParScan    │              │
│  │ ThreadState  │  │ ThreadState  │  │ ThreadState  │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                 │                      │
│         ▼                 ▼                 ▼                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │              CSet (Collection Set)                          │    │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐               │    │
│  │  │ Eden   │ │ Eden   │ │Survivor│ │  Old   │               │    │
│  │  │Region 0│ │Region 1│ │Region  │ │Region  │               │    │
│  │  └────────┘ └────────┘ └────────┘ └────────┘               │    │
│  └────────────────────────────────────────────────────────────┘    │
│                            │                                       │
│                            ▼                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │              目标区域 (To-Space)                            │    │
│  │  ┌────────┐ ┌────────┐ ┌────────┐                          │    │
│  │  │Survivor│ │Survivor│ │  Old   │  (晋升)                  │    │
│  │  │ To     │ │ To     │ │Region  │                          │    │
│  │  └────────┘ └────────┘ └────────┘                          │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.5 Step 5: 对象复制

**GDB断点**: `G1ParScanThreadState::copy_to_survivor_space`

```
[复制#1] G1ParScanThreadState::copy_to_survivor_space
  源对象: 0xfff08ae8, mark: 0x1

[复制#2] G1ParScanThreadState::copy_to_survivor_space
  源对象: 0xfff20c48, mark: 0x1

[复制#3] G1ParScanThreadState::copy_to_survivor_space
  源对象: 0xfff090c0, mark: 0x1
```

**源码分析**:
```cpp
// g1ParScanThreadState.cpp:217
oop G1ParScanThreadState::copy_to_survivor_space(InCSetState state,
                                                  oop old,
                                                  markOop old_mark) {
  // 1. 计算对象大小
  size_t word_sz = old->size();
  
  // 2. 根据年龄决定目标区域（Survivor或Old）
  uint age = old_mark->age() + 1;
  if (age >= _tenuring_threshold) {
    // 晋升到老年代
    dest = allocate_in_old(word_sz);
  } else {
    // 复制到Survivor
    dest = allocate_in_survivor(word_sz);
  }
  
  // 3. 复制对象数据
  Copy::aligned_disjoint_words(old, dest, word_sz);
  
  // 4. 更新mark word（设置年龄，安装转发指针）
  new_obj->set_mark(old_mark->set_age(age));
  old->set_mark(markOopDesc::encode_pointer_as_mark(new_obj));
  
  return new_obj;
}
```

### 3.6 Step 6: GC完成统计

**GDB断点**: `G1Policy::record_collection_pause_end`

```
[完成] G1Policy::record_collection_pause_end
  实际暂停时间: 43.75 ms
```

---

## 4. GC日志分析 (真实数据)

运行程序获取的真实GC日志：

```
[1.078s][info][gc,start] GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation)
[1.078s][info][gc,task ] GC(0) Using 6 workers of 13 for evacuation
[1.121s][info][gc,phases] GC(0)   Pre Evacuate Collection Set: 0.0ms
[1.121s][info][gc,phases] GC(0)   Evacuate Collection Set: 39.9ms
[1.121s][info][gc,phases] GC(0)   Post Evacuate Collection Set: 2.3ms
[1.121s][info][gc,phases] GC(0)   Other: 1.5ms
[1.121s][info][gc,heap  ] GC(0) Eden regions: 8->0(152)
[1.121s][info][gc,heap  ] GC(0) Survivor regions: 0->1(3)
[1.121s][info][gc,heap  ] GC(0) Old regions: 0->0
[1.121s][info][gc,heap  ] GC(0) Humongous regions: 52->52
[1.121s][info][gc       ] GC(0) Pause Young (Concurrent Start) 59M->52M(256M) 43.746ms
```

### 日志解读

| 指标 | 值 | 说明 |
|------|-----|------|
| GC类型 | Pause Young (Concurrent Start) | Young GC + 触发并发标记 |
| GC原因 | G1 Humongous Allocation | 大对象分配触发 |
| GC线程数 | 6 workers | 并行疏散使用6个线程 |
| Pre Evacuate | 0.0ms | 疏散前准备 |
| Evacuate | 39.9ms | 实际疏散时间 |
| Post Evacuate | 2.3ms | 疏散后处理 |
| Eden变化 | 8->0(152) | 8个Eden Region被回收 |
| Survivor变化 | 0->1(3) | 新增1个Survivor Region |
| 堆使用变化 | 59M->52M | 回收7MB内存 |
| 总暂停时间 | 43.746ms | 目标200ms，实际43.7ms |

---

## 5. 并发标记流程 (GDB验证)

### 5.1 并发标记触发条件

当堆使用率达到IHOP阈值时触发并发标记：
- 默认IHOP: 45%
- 本测试设置: 20% (`-XX:InitiatingHeapOccupancyPercent=20`)

### 5.2 并发标记阶段

```
========== 并发标记 ==========
[CM开始] G1ConcurrentMark::mark_from_roots
  G1ConcurrentMark: 0x7ffff004a3f0
  并发遍历堆标记存活对象

[CM清理] G1ConcurrentMark::cleanup
  回收完全空闲的Region
```

### 5.3 并发标记日志

```
[1.122s][info][gc         ] GC(1) Concurrent Cycle
[1.122s][info][gc,marking ] GC(1) Concurrent Clear Claimed Marks
[1.122s][info][gc,marking ] GC(1) Concurrent Clear Claimed Marks 0.009ms
[1.122s][info][gc,marking ] GC(1) Concurrent Scan Root Regions
[1.144s][info][gc,marking ] GC(1) Concurrent Scan Root Regions 22.437ms
[1.144s][info][gc,marking ] GC(1) Concurrent Mark (1.144s)
[1.144s][info][gc,marking ] GC(1) Concurrent Mark From Roots
[1.144s][info][gc,task    ] GC(1) Using 3 workers of 3 for marking
[1.145s][info][gc,marking ] GC(1) Concurrent Mark From Roots 0.624ms
[1.145s][info][gc,marking ] GC(1) Concurrent Preclean
[1.145s][info][gc,marking ] GC(1) Concurrent Preclean 0.273ms
[1.145s][info][gc,marking ] GC(1) Concurrent Mark (1.144s, 1.145s) 0.924ms
[1.148s][info][gc,start   ] GC(1) Pause Remark
[1.162s][info][gc         ] GC(1) Pause Remark 73M->73M(256M) 14.623ms
```

### 5.4 并发标记阶段详解

```
┌─────────────────────────────────────────────────────────────────────┐
│ G1 并发标记 (Concurrent Marking)                                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ 阶段1: Initial Mark (初始标记) - STW                                 │
│   • 标记GC根直接引用的对象                                           │
│   • 通常与Young GC一起执行                                          │
│   • 耗时: ~1ms                                                      │
│                                                                     │
│ 阶段2: Root Region Scan (根区域扫描) - 并发                          │
│   • 扫描Survivor区域中对老年代的引用                                 │
│   • 耗时: 22.437ms                                                  │
│                                                                     │
│ 阶段3: Concurrent Mark (并发标记) - 并发                             │
│   • 遍历整个堆标记存活对象                                           │
│   • 使用SATB (Snapshot-At-The-Beginning)                            │
│   • 耗时: 0.924ms                                                   │
│                                                                     │
│ 阶段4: Remark (重新标记) - STW                                       │
│   • 处理SATB缓冲区中的引用变更                                       │
│   • 耗时: 14.623ms                                                  │
│                                                                     │
│ 阶段5: Cleanup (清理) - 部分STW                                      │
│   • 统计各Region存活数据                                            │
│   • 回收完全空闲的Region                                            │
│   • 重置标记数据结构                                                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. 关键GDB数据汇总

| 对象 | GDB地址 | 说明 |
|------|---------|------|
| G1CollectedHeap | `0x7ffff0031e20` | G1堆管理核心 |
| G1CollectionSet | `0x7ffff0032338` | 回收集合 |
| G1ConcurrentMark | `0x7ffff004a3f0` | 并发标记器 |
| G1ParScanThreadState | `0x7fffb4000d70` | GC线程状态 |

| 参数 | 值 | 说明 |
|------|-----|------|
| 目标暂停时间 | 200ms | -XX:MaxGCPauseMillis |
| 实际暂停时间 | 43.75ms | 第一次Young GC |
| GC线程数 | 6/13 | 使用6个worker线程 |
| time_remaining_ms | 83.45ms | 选择Old Region剩余时间 |

---

## 7. GDB调试命令参考

```bash
# 进入调试
cd /path/to/openjdk11/build/.../jdk/bin
gdb ./java

# 设置断点
break G1CollectedHeap::do_collection_pause_at_safepoint
break G1CollectionSet::finalize_young_part
break G1CollectionSet::finalize_old_part
break G1ParScanThreadState::copy_to_survivor_space
break G1Policy::record_collection_pause_end
break G1ConcurrentMark::mark_from_roots

# 运行
run -Xms256m -Xmx256m -XX:+UseG1GC -XX:G1HeapRegionSize=1m \
    -XX:InitiatingHeapOccupancyPercent=20 -Xint -cp /path HelloWorld

# 查看G1堆状态
print *this
print this->_g1_policy
print this->_collection_set

# 继续执行
continue
```

---

## 8. 总结

### Young GC 核心步骤

1. **触发**: Eden区满或分配失败
2. **选择CSet**: 所有Eden + Survivor Regions
3. **并行疏散**: 多线程复制存活对象
4. **更新引用**: 修复所有指向移动对象的指针
5. **释放Region**: 回收空闲Region

### Mixed GC 额外步骤

1. **并发标记**: 标记老年代存活对象
2. **选择老年代Region**: 按垃圾比例排序选择
3. **混合回收**: Young + 部分Old Regions

### 关键源码文件

- `g1CollectedHeap.cpp` - G1堆核心实现
- `g1CollectionSet.cpp` - 回收集合选择
- `g1ParScanThreadState.cpp` - 对象复制
- `g1ConcurrentMark.cpp` - 并发标记
- `g1Policy.cpp` - GC策略决策
