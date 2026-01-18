# G1 Mixed GC 详解 - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms256m -Xmx256m -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=20`

---

## 1. Mixed GC 概述

### 1.1 什么是Mixed GC

Mixed GC是G1特有的GC类型：
- **回收所有年轻代** (Young Regions)
- **回收部分老年代** (部分Old Regions)
- 选择垃圾最多的老年代Region回收

### 1.2 与Young GC的区别

| 特性 | Young GC | Mixed GC |
|------|----------|----------|
| 回收范围 | 仅年轻代 | 年轻代 + 部分老年代 |
| 触发条件 | Eden满 | 并发标记完成后 |
| CSet构成 | Eden + Survivor | Eden + Survivor + Old |
| 耗时 | 较短 | 略长 |

---

## 2. Mixed GC 触发条件

### 2.1 前提条件

1. **并发标记完成**: 需要知道老年代各Region的存活数据
2. **有足够的老年代垃圾**: 回收有价值

### 2.2 GDB验证

```
[CSet] G1CollectionSet::finalize_old_part
  time_remaining_ms: 83.45
  选择老年代Regions (Mixed GC)
```

---

## 3. CSet选择策略

### 3.1 选择流程

```
┌─────────────────────────────────────────────────────────────────────┐
│ Mixed GC CSet 选择流程                                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. 添加年轻代Regions (必须)                                        │
│     ┌────────────────────────────────────────┐                      │
│     │ Eden Regions: 全部加入                  │                      │
│     │ Survivor Regions: 全部加入              │                      │
│     └────────────────────────────────────────┘                      │
│                     │                                               │
│                     ▼                                               │
│  2. 计算剩余时间预算                                                 │
│     time_remaining = target_pause - young_gc_time                   │
│                     │                                               │
│                     ▼                                               │
│  3. 选择老年代Regions                                               │
│     ┌────────────────────────────────────────┐                      │
│     │ while (time_remaining > 0):             │                      │
│     │   region = get_next_old_region()       │                      │
│     │   // 按垃圾比例排序                     │                      │
│     │   if (region.live_ratio < threshold):  │                      │
│     │     add_to_cset(region)                │                      │
│     │     time_remaining -= predicted_time   │                      │
│     └────────────────────────────────────────┘                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 源码分析

```cpp
// g1CollectionSet.cpp
void G1CollectionSet::finalize_old_part(double time_remaining_ms) {
  // 获取并发标记后排序的Region列表
  G1CollectionSetChooser* cset_chooser = _g1h->cset_chooser();
  
  while (time_remaining_ms > 0.0) {
    HeapRegion* hr = cset_chooser->pop();
    if (hr == NULL) break;
    
    // 检查Region是否值得回收
    if (hr->live_bytes() > _live_threshold) {
      // 存活数据太多，跳过
      continue;
    }
    
    // 预测回收耗时
    double predicted_time = predict_region_time(hr);
    
    if (predicted_time <= time_remaining_ms) {
      // 加入CSet
      add_old_region(hr);
      time_remaining_ms -= predicted_time;
      _old_region_length++;
    }
  }
}
```

### 3.3 GDB验证

```
[CSet] G1CollectionSet::finalize_old_part
  time_remaining_ms: 83.45
  
# 第二次GC
[CSet] G1CollectionSet::finalize_old_part
  time_remaining_ms: 0.00
  # 时间已用尽，不再添加Old Region
```

---

## 4. Region选择优先级

### 4.1 排序依据

Region按**垃圾比例**排序（回收效率）：

```
垃圾比例 = (Region大小 - 存活数据) / Region大小

Region #5:  垃圾比例 90%  ← 优先回收
Region #8:  垃圾比例 75%
Region #12: 垃圾比例 50%
Region #3:  垃圾比例 10%  ← 最后回收
```

### 4.2 选择阈值

```cpp
// 存活比例阈值 (默认85%)
-XX:G1MixedGCLiveThresholdPercent=85

// 超过此阈值的Region不会被选中
if (live_bytes > region_size * 0.85) {
  skip_region();  // 存活数据太多，不值得回收
}
```

---

## 5. Mixed GC 执行过程

### 5.1 与Young GC的相同点

- 同样在STW期间执行
- 同样使用并行疏散
- 同样复制存活对象

### 5.2 与Young GC的不同点

```
┌─────────────────────────────────────────────────────────────────────┐
│ Mixed GC 特殊处理                                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. Old Region中的对象 → 复制到其他Old Region                       │
│                                                                     │
│  Young Region (Eden/Survivor)    Old Region (被选中)                │
│  ┌──────────────┐                ┌──────────────┐                   │
│  │ 存活对象     │                │ 存活对象     │                   │
│  │      │       │                │      │       │                   │
│  └──────┼───────┘                └──────┼───────┘                   │
│         │                               │                           │
│         ▼                               ▼                           │
│  ┌──────────────┐                ┌──────────────┐                   │
│  │ To-Survivor  │                │ To-Old       │                   │
│  │ 或 To-Old    │                │              │                   │
│  └──────────────┘                └──────────────┘                   │
│                                                                     │
│  2. 老年代对象不增加年龄（已经是老年代）                             │
│                                                                     │
│  3. 可能产生更多跨代引用更新                                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. Mixed GC 次数控制

### 6.1 目标次数

```cpp
// 目标Mixed GC次数 (默认8次)
-XX:G1MixedGCCountTarget=8

// 每次Mixed GC回收 1/8 的候选老年代Region
```

### 6.2 停止条件

Mixed GC循环在以下条件时停止：

```cpp
// 1. 老年代垃圾比例低于阈值
-XX:G1HeapWastePercent=5  // 默认5%

if (reclaimable_percent < G1HeapWastePercent) {
  stop_mixed_gc_cycle();
}

// 2. 没有更多候选Region
if (cset_chooser->is_empty()) {
  stop_mixed_gc_cycle();
}
```

---

## 7. Mixed GC 性能考量

### 7.1 暂停时间控制

```cpp
// 目标暂停时间
-XX:MaxGCPauseMillis=200

// G1会尽量在目标时间内完成
// 通过选择合适数量的Old Region
```

### 7.2 GDB验证

```
第一次GC:
  目标暂停时间: 200.00 ms
  time_remaining_ms: 83.45  ← 年轻代回收后剩余时间
  
第二次GC:
  time_remaining_ms: 0.00   ← 时间已用尽
```

### 7.3 调优建议

| 场景 | 调整参数 |
|------|----------|
| 暂停时间过长 | 减小`-XX:MaxGCPauseMillis` |
| 老年代增长快 | 增大`-XX:G1MixedGCCountTarget` |
| 回收效果差 | 降低`-XX:G1MixedGCLiveThresholdPercent` |

---

## 8. GC日志分析

### 8.1 Mixed GC日志特征

```
# Young GC (非Mixed)
[1.078s] GC(0) Pause Young (Normal)

# Mixed GC
[1.234s] GC(2) Pause Young (Mixed)
                         ^^^^^
                         Mixed GC标识
```

### 8.2 Region变化

```
# Mixed GC的Region变化
GC(2) Eden regions: 8->0(152)
GC(2) Survivor regions: 1->1(3)
GC(2) Old regions: 5->3          ← 老年代Region减少
GC(2) Humongous regions: 52->50  ← Humongous可能被回收
```

---

## 9. 与Full GC的关系

### 9.1 Mixed GC失败

如果Mixed GC无法回收足够内存：
1. 触发更激进的Mixed GC
2. 最终可能触发Full GC

### 9.2 避免Full GC

```cpp
// 提前触发并发标记
-XX:InitiatingHeapOccupancyPercent=30  // 提前触发

// 增加Mixed GC频率
-XX:G1MixedGCCountTarget=12

// 扩大CSet选择范围
-XX:G1MixedGCLiveThresholdPercent=90
```

---

## 10. 关键数据汇总

| 参数 | GDB验证值 | 说明 |
|------|-----------|------|
| finalize_old_part调用 | ✓ | Mixed GC触发 |
| time_remaining_ms | 83.45ms | 老年代Region选择时间预算 |
| G1CollectionSet | 0x7ffff0032338 | CSet地址 |

| 配置参数 | 默认值 | 说明 |
|----------|--------|------|
| G1MixedGCCountTarget | 8 | 目标Mixed GC次数 |
| G1HeapWastePercent | 5% | 停止阈值 |
| G1MixedGCLiveThresholdPercent | 85% | Region选择阈值 |

---

## 11. GDB调试命令

```bash
# Mixed GC相关断点
break G1CollectionSet::finalize_old_part
break G1CollectionSetChooser::pop

# 查看CSet状态
print this->_old_region_length
print this->_eden_region_length
print this->_survivor_region_length

# 查看时间预算
print time_remaining_ms
```

---

## 12. 总结

### Mixed GC核心要点

1. **触发条件**: 并发标记完成后
2. **回收范围**: Young + 部分Old
3. **选择策略**: 按垃圾比例排序
4. **时间控制**: 在目标暂停时间内选择Region
5. **循环执行**: 多轮Mixed GC直到垃圾降低

### 关键源码

- `g1CollectionSet.cpp::finalize_old_part` - Old Region选择
- `g1CollectionSetChooser.cpp` - Region排序和选择
- `g1Policy.cpp` - Mixed GC决策
