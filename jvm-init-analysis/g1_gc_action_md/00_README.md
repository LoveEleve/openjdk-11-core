# G1 GC 完整流程 - GDB验证文档

> **验证环境**: OpenJDK 11 slowdebug  
> **测试程序**: HelloWorld.java (构造GC场景)

---

## 文档目录

| 文档 | 内容 | 关键GDB数据 |
|------|------|-------------|
| [01_G1_GC完整流程](./01_G1_GC完整流程_GDB验证.md) | G1 GC整体流程概览 | 调用栈、核心对象地址 |
| [02_G1_Young_GC详解](./02_G1_Young_GC详解_GDB验证.md) | Young GC详细分析 | CSet、并行疏散、对象复制 |
| [03_G1_并发标记](./03_G1_并发标记_GDB验证.md) | 并发标记5阶段 | SATB、三色标记 |
| [04_G1_Mixed_GC详解](./04_G1_Mixed_GC详解_GDB验证.md) | Mixed GC策略 | Region选择、时间预算 |

---

## 测试环境

```bash
# JVM参数
-Xms256m -Xmx256m
-XX:+UseG1GC
-XX:G1HeapRegionSize=1m
-XX:MaxGCPauseMillis=200
-XX:InitiatingHeapOccupancyPercent=20
-XX:-UseLargePages
```

---

## 测试代码

```java
import java.util.ArrayList;
import java.util.List;

public class HelloWorld {
    static List<byte[]> survivors = new ArrayList<>();
    static List<byte[]> garbage = new ArrayList<>();
    
    public static void main(String[] args) {
        // 阶段1：触发Young GC
        triggerYoungGC();
        
        // 阶段2：对象晋升到老年代
        promoteToOld();
        
        // 阶段3：触发Mixed GC
        triggerMixedGC();
    }
    
    static void triggerYoungGC() {
        for (int i = 0; i < 500; i++) {
            garbage.add(new byte[1024 * 1024]);
            if (i % 100 == 99) garbage.clear();
        }
    }
    // ...
}
```

---

## 核心GDB验证数据

### G1核心对象

| 对象 | GDB地址 | 说明 |
|------|---------|------|
| G1CollectedHeap | `0x7ffff0031e20` | G1堆管理核心 |
| G1CollectionSet | `0x7ffff0032338` | 回收集合 |
| G1ConcurrentMark | `0x7ffff004a3f0` | 并发标记器 |
| G1Policy | `0x7ffff0038200` | GC策略 |

### Young GC数据

| 指标 | 值 | 说明 |
|------|-----|------|
| 目标暂停时间 | 200ms | MaxGCPauseMillis |
| 实际暂停时间 | 43.75ms | 第一次GC |
| GC线程数 | 6/13 | workers |
| Eden变化 | 8→0 | Region数 |
| Survivor变化 | 0→1 | Region数 |

### 并发标记数据

| 阶段 | 耗时 | STW |
|------|------|-----|
| Initial Mark | ~1ms | 是 |
| Root Region Scan | 22.4ms | 否 |
| Concurrent Mark | 0.9ms | 否 |
| Remark | 14.6ms | 是 |
| Cleanup | ~4ms | 部分 |

### Mixed GC数据

| 指标 | 值 | 说明 |
|------|-----|------|
| time_remaining_ms | 83.45ms | 选择Old Region的时间预算 |
| finalize_old_part | 触发 | Mixed GC标识 |

---

## G1 GC 完整流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    G1 GC 完整生命周期                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  正常运行                                                            │
│     │                                                               │
│     ▼ Eden满                                                        │
│  ┌─────────────────────────────────────────┐                        │
│  │          Young GC (STW)                 │                        │
│  │  • 回收Eden + Survivor                  │                        │
│  │  • 复制存活对象到Survivor/Old           │                        │
│  │  • 耗时: ~40ms                          │                        │
│  └────────────────┬────────────────────────┘                        │
│                   │                                                 │
│                   ▼ 堆占用 > IHOP                                   │
│  ┌─────────────────────────────────────────┐                        │
│  │       并发标记 (大部分并发)              │                        │
│  │  1. Initial Mark (STW, ~1ms)            │                        │
│  │  2. Root Region Scan (并发, ~22ms)      │                        │
│  │  3. Concurrent Mark (并发, ~1ms)        │                        │
│  │  4. Remark (STW, ~15ms)                 │                        │
│  │  5. Cleanup (部分STW, ~4ms)             │                        │
│  └────────────────┬────────────────────────┘                        │
│                   │                                                 │
│                   ▼                                                 │
│  ┌─────────────────────────────────────────┐                        │
│  │          Mixed GC (多轮)                │                        │
│  │  • 回收Young + 部分Old                  │                        │
│  │  • 按垃圾比例选择Old Region             │                        │
│  │  • 循环直到垃圾 < 5%                    │                        │
│  └────────────────┬────────────────────────┘                        │
│                   │                                                 │
│                   ▼                                                 │
│              回到正常运行                                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## GDB调试命令汇总

```bash
# 进入调试
cd /path/to/openjdk11/build/.../jdk/bin
gdb ./java

# Young GC断点
break G1CollectedHeap::do_collection_pause_at_safepoint
break G1CollectionSet::finalize_young_part
break G1ParScanThreadState::copy_to_survivor_space
break G1Policy::record_collection_pause_end

# 并发标记断点
break G1ConcurrentMark::mark_from_roots
break G1ConcurrentMark::cleanup

# Mixed GC断点
break G1CollectionSet::finalize_old_part

# 运行
run -Xms256m -Xmx256m -XX:+UseG1GC -XX:G1HeapRegionSize=1m \
    -XX:InitiatingHeapOccupancyPercent=20 -Xint -cp /path HelloWorld
```

---

## 关键JVM参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-XX:MaxGCPauseMillis` | 200 | 目标暂停时间 |
| `-XX:G1HeapRegionSize` | 自动 | Region大小(1-32MB) |
| `-XX:InitiatingHeapOccupancyPercent` | 45 | 触发并发标记阈值 |
| `-XX:G1MixedGCCountTarget` | 8 | Mixed GC目标次数 |
| `-XX:G1HeapWastePercent` | 5 | 停止Mixed GC阈值 |
| `-XX:G1MixedGCLiveThresholdPercent` | 85 | Region选择存活阈值 |
| `-XX:ConcGCThreads` | CPU/4 | 并发GC线程数 |
| `-XX:ParallelGCThreads` | 自动 | 并行GC线程数 |

---

## 关键源码文件

| 文件 | 功能 |
|------|------|
| `g1CollectedHeap.cpp` | G1堆核心实现 |
| `g1CollectionSet.cpp` | 回收集合选择 |
| `g1ParScanThreadState.cpp` | 对象复制 |
| `g1ConcurrentMark.cpp` | 并发标记 |
| `g1Policy.cpp` | GC策略决策 |
| `vm_operations_g1.cpp` | GC操作入口 |

---

## 学习路径

1. **入门**: 阅读 [01_G1_GC完整流程](./01_G1_GC完整流程_GDB验证.md)
2. **深入Young GC**: 阅读 [02_G1_Young_GC详解](./02_G1_Young_GC详解_GDB验证.md)
3. **理解并发标记**: 阅读 [03_G1_并发标记](./03_G1_并发标记_GDB验证.md)
4. **掌握Mixed GC**: 阅读 [04_G1_Mixed_GC详解](./04_G1_Mixed_GC详解_GDB验证.md)
5. **实践**: 使用GDB自己调试验证
