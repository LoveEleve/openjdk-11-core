# G1 GC 执行流程真实GC日志深度分析

> 基于OpenJDK 11源码，8GB堆配置（-Xms=Xmx=8GB），Region=4MB
> 通过构造真实GC场景，生成并分析详细GC日志，验证源码分析结论

## 1. 测试环境与参数配置

### 1.1 JVM启动参数

```bash
java -Xms8g -Xmx8g -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -XX:G1HeapRegionSize=4m \
     -Xlog:gc*=debug:file=gc_detailed.log:time,uptime,level,tags:filecount=5,filesize=100m \
     G1GCTestScenarios
```

### 1.2 关键参数验证（从GC日志提取）

```
[0.010s][info][gc,heap] Heap region size: 4M
[0.010s][debug][gc,heap] Minimum heap 8589934592  Initial heap 8589934592  Maximum heap 8589934592
[0.019s][debug][gc     ] ConcGCThreads: 3 offset 29
[0.019s][debug][gc     ] ParallelGCThreads: 13
[0.019s][debug][gc     ] Initialize mark stack with 4096 chunks, maximum 16384
[0.067s][debug][gc,ihop] Target occupancy update: old: 0B, new: 8589934592B
[0.067s][debug][gc,ergo,refine] Initial Refinement Zones: green: 13, yellow: 39, red: 65, min yellow size: 26
```

**验证结论**:
| 参数 | 配置值 | 计算依据 |
|-----|-------|---------|
| Region大小 | 4MB | 显式配置 `-XX:G1HeapRegionSize=4m` |
| Region数量 | 2,048个 | 8GB / 4MB = 2,048 |
| 并行GC线程 | 13 | `ParallelGCThreads = 13` (基于CPU核心数) |
| 并发标记线程 | 3 | `ConcGCThreads = ParallelGCThreads / 4 ≈ 3` |
| 目标堆占用 | 8GB | `Target occupancy: 8589934592B` |
| IHOP阈值 | 45% | 8GB × 45% = 3.6GB (`3865470566B`) |
| Refinement绿区 | 13 | 基于Region数量计算 |
| Refinement黄区 | 39 | `green × 3` |
| Refinement红区 | 65 | `green × 5` |

## 2. Young GC 详细日志分析

### 2.1 GC触发与基本信息

```
[1.358s][debug][gc,heap] GC(0) Heap before GC invocations=0 (full 0): 
                        garbage-first heap total 8388608K, used 413696K
[1.358s][debug][gc,heap] GC(0) region size 4096K, 102 young (417792K), 0 survivors (0K)
[1.360s][info ][gc,start] GC(0) Pause Young (Normal) (G1 Evacuation Pause)
[1.362s][info ][gc,task ] GC(0) Using 13 workers of 13 for evacuation
```

**验证分析**:
- **GC编号**: `GC(0)` 表示第一次GC
- **GC类型**: `Pause Young (Normal)` - 普通Young GC
- **触发原因**: `G1 Evacuation Pause` - Eden区满触发
- **堆使用前**: 413696K ≈ 404MB (102个Eden Region × 4MB)
- **Worker线程**: 13个并行线程参与Evacuation

### 2.2 TLAB统计信息

```
[1.362s][debug][gc,tlab] GC(0) TLAB totals: 
    thrds: 6  refills: 19 max: 14 
    slow allocs: 5963 max 5963 
    waste: 22.2% gc: 6356424B max: 2096872B 
    slow: 485208B max: 485208B 
    fast: 0B max: 0B
```

**TLAB统计解读**:
| 指标 | 值 | 含义 |
|-----|---|-----|
| thrds | 6 | 6个线程使用了TLAB |
| refills | 19 | TLAB重新填充19次 |
| slow allocs | 5963 | 5963次慢速分配(TLAB外分配) |
| waste | 22.2% | TLAB浪费率22.2% |
| gc waste | 6356424B | GC时TLAB剩余空间 ≈6MB |

### 2.3 Mutator分配Region统计

```
[1.363s][debug][gc,alloc,region] GC(0) Mutator Allocation stats, 
    regions: 102, wasted size: 5987K ( 1.4%)
```

**分析**: 
- 分配了102个Eden Region
- 浪费空间5987K (1.4%)，符合预期

### 2.4 年龄阈值计算

```
[1.363s][debug][gc,age] GC(0) Desired survivor size 27262976 bytes, 
    new threshold 15 (max threshold 15)
```

**计算验证**:
- **期望Survivor大小**: 27262976B ≈ 26MB
- **计算公式**: `SurvivorRegions × RegionSize × TargetSurvivorRatio`
- **年龄阈值**: 15 (最大值)，表示首次GC，Survivor足够

### 2.5 Collection Set选择

```
[1.363s][debug][gc,ergo,cset] GC(0) Finish choosing CSet. 
    old: 0 regions, predicted old region time: 0.00ms, time remaining: 0.00
```

**分析**: Young GC的CSet仅包含Young Region，不包含Old Region

### 2.6 三大阶段时间分解

```
[1.846s][info ][gc,phases] GC(0)   Pre Evacuate Collection Set: 0.2ms
[1.846s][debug][gc,phases] GC(0)     Prepare TLABs: 0.9ms
[1.846s][debug][gc,phases] GC(0)     Choose Collection Set: 0.0ms
[1.846s][debug][gc,phases] GC(0)     Humongous Register: 0.2ms

[1.846s][info ][gc,phases] GC(0)   Evacuate Collection Set: 341.2ms
[1.846s][debug][gc,phases] GC(0)     Ext Root Scanning (ms):   Min:0.9, Avg:4.8, Max:49.7, Sum:62.9, Workers:13
[1.846s][debug][gc,phases] GC(0)     Update RS (ms):           Min:0.0, Avg:0.0, Max:0.0, Sum:0.0, Workers:13
[1.846s][debug][gc,phases] GC(0)       Processed Buffers:      Min:0, Avg:0.0, Max:0, Sum:0, Workers:13
[1.846s][debug][gc,phases] GC(0)       Scanned Cards:          Min:0, Avg:0.0, Max:0, Sum:0, Workers:13
[1.846s][debug][gc,phases] GC(0)     Scan RS (ms):             Min:0.0, Avg:0.0, Max:0.0, Sum:0.1, Workers:13
[1.846s][debug][gc,phases] GC(0)     Code Root Scanning (ms):  Min:0.0, Avg:0.1, Max:0.9, Sum:1.8, Workers:13
[1.846s][debug][gc,phases] GC(0)     Object Copy (ms):         Min:290.3, Avg:335.2, Max:339.6, Sum:4357.8, Workers:13
[1.846s][debug][gc,phases] GC(0)     Termination (ms):         Min:0.0, Avg:0.5, Max:0.9, Sum:6.2, Workers:13
[1.846s][debug][gc,phases] GC(0)     GC Worker Total (ms):     Min:340.3, Avg:340.6, Max:340.9, Sum:4428.2, Workers:13

[1.846s][info ][gc,phases] GC(0)   Post Evacuate Collection Set: 140.4ms
[1.846s][debug][gc,phases] GC(0)     Code Roots Fixup: 0.1ms
[1.846s][debug][gc,phases] GC(0)     Clear Card Table: 0.0ms
[1.846s][debug][gc,phases] GC(0)     Reference Processing: 0.1ms
[1.846s][debug][gc,phases] GC(0)     Weak Processing: 0.1ms
[1.846s][debug][gc,phases] GC(0)     Merge Per-Thread State: 0.1ms
[1.846s][debug][gc,phases] GC(0)     Code Roots Purge: 0.0ms
[1.846s][debug][gc,phases] GC(0)     Redirty Cards: 0.1ms
[1.846s][debug][gc,phases] GC(0)     Free Collection Set: 139.0ms
[1.846s][debug][gc,phases] GC(0)     Humongous Reclaim: 0.2ms
[1.846s][debug][gc,phases] GC(0)     Start New Collection Set: 0.1ms
[1.846s][debug][gc,phases] GC(0)     Resize TLABs: 0.0ms

[1.846s][info ][gc,phases] GC(0)   Other: 6.3ms
```

**阶段时间分析**:

| 主阶段 | 时间 | 占比 | 关键子阶段 |
|-------|------|-----|-----------|
| Pre Evacuate | 0.2ms | 0.04% | Prepare TLABs, Humongous Register |
| **Evacuate** | **341.2ms** | **69.6%** | Object Copy (335.2ms avg) |
| Post Evacuate | 140.4ms | 28.7% | Free Collection Set (139.0ms) |
| Other | 6.3ms | 1.3% | 其他开销 |
| **总计** | **487.3ms** | - | 超过200ms目标 |

**关键发现**:
1. **Object Copy占主导**: 平均335.2ms，是暂停时间主要来源
2. **Free Collection Set耗时异常**: 139.0ms，占Post阶段99%
3. **Update RS为0**: 首次GC无Old→Young引用
4. **Termination负载均衡良好**: Min:0.0, Max:0.9ms

### 2.7 Region变化统计

```
[1.846s][info ][gc,heap] GC(0) Eden regions: 102->0(89)
[1.846s][info ][gc,heap] GC(0) Survivor regions: 0->13(13)
[1.846s][info ][gc,heap] GC(0) Old regions: 0->86
[1.846s][info ][gc,heap] GC(0) Humongous regions: 0->0
```

**变化分析**:
| Region类型 | GC前 | GC后 | 分析 |
|-----------|------|------|-----|
| Eden | 102 | 0(89) | 102个Region被清空，下次分配89个 |
| Survivor | 0 | 13(13) | 13个Region用于存活对象 |
| Old | 0 | 86 | 86个Region晋升到Old区 |
| Humongous | 0 | 0 | 无大对象 |

**晋升率**: 86/(102+0) = 84.3%，说明大量对象存活并晋升

### 2.8 GC结果汇总

```
[1.847s][info ][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 407M->395M(8192M) 487.304ms
[1.847s][info ][gc,cpu] GC(0) User=0.63s Sys=0.35s Real=0.48s
```

**GC效果**:
- **回收前**: 407MB
- **回收后**: 395MB  
- **回收量**: 12MB (回收率仅3%)
- **堆总大小**: 8192MB
- **暂停时间**: 487.304ms (超过200ms目标)
- **CPU时间**: User 0.63s + Sys 0.35s = 0.98s
- **并行效率**: 0.98s / (0.48s × 13) ≈ 15.7%

### 2.9 IHOP统计更新

```
[1.846s][debug][gc,ihop] GC(0) Basic information (value update):
    threshold: 3865470566B (45.00)
    target occupancy: 8589934592B
    current occupancy: 414974976B
    recent allocation size: 280704B
    recent allocation duration: 1352.44ms
    recent old gen allocation rate: 207554.47B/s
    recent marking phase length: 0.00ms

[1.846s][debug][gc,ihop] GC(0) Adaptive IHOP information:
    threshold: 3865470566B (52.94)
    internal target occupancy: 7301444403B
    occupancy: 414974976B
    additional buffer size: 427819008B
    predicted old gen allocation rate: 415108.94B/s
    prediction active: false
```

**IHOP验证**:
- **基础阈值**: 3865470566B = 3.6GB (45%)
- **自适应阈值**: 52.94%（尚未激活）
- **当前Old占用**: 414974976B ≈ 395MB
- **Old分配速率**: 207KB/s
- **预测标记时长**: 0ms（无历史数据）

## 3. 并发标记周期详细分析

### 3.1 触发条件验证

```
[14.692s][debug][gc,ergo,ihop] GC(15) Request concurrent cycle initiation 
    (occupancy higher than threshold) 
    occupancy: 3959422976B 
    allocation request: 0B 
    threshold: 3865470566B (45.00) 
    source: end of GC
```

**触发分析**:
- **当前Old占用**: 3959422976B = 3.69GB
- **IHOP阈值**: 3865470566B = 3.60GB (45%)
- **触发条件**: `3.69GB > 3.60GB` → 触发并发标记
- **触发时机**: `end of GC` (Young GC结束时)

### 3.2 Concurrent Start阶段

```
[14.832s][info ][gc,start] GC(16) Pause Young (Concurrent Start) (G1 Evacuation Pause)
[14.971s][info ][gc     ] GC(16) Pause Young (Concurrent Start) (G1 Evacuation Pause) 
    4181M->3773M(8192M) 138.837ms
```

**分析**: GC(16)标记为`Concurrent Start`，表示同时执行Young GC并启动并发标记

### 3.3 并发标记各阶段时间

```
[14.971s][info ][gc,marking] GC(17) Concurrent Clear Claimed Marks
[14.971s][info ][gc,marking] GC(17) Concurrent Clear Claimed Marks 0.031ms

[14.971s][info ][gc,marking] GC(17) Concurrent Scan Root Regions
[14.971s][info ][gc,marking] GC(17) Concurrent Scan Root Regions 0.034ms (Survivor扫描)

[14.971s][info ][gc,marking] GC(17) Concurrent Mark (14.971s)
[14.971s][info ][gc,marking] GC(17) Concurrent Mark From Roots
[15.029s][info ][gc,marking] GC(17) Concurrent Mark From Roots 57.970ms
[15.030s][info ][gc,marking] GC(17) Concurrent Mark (14.971s, 15.030s) 58.422ms

[15.030s][info ][gc,marking] GC(17) Concurrent Preclean
[15.030s][debug][gc,ref    ] GC(17) Preclean SoftReferences 0.081ms
[15.030s][debug][gc,ref    ] GC(17) Preclean WeakReferences 0.135ms
[15.030s][debug][gc,ref    ] GC(17) Preclean FinalReferences 0.072ms
[15.030s][debug][gc,ref    ] GC(17) Preclean PhantomReferences 0.075ms
[15.030s][info ][gc,marking] GC(17) Concurrent Preclean 0.426ms
```

**并发阶段时间表**:

| 阶段 | 时间 | 说明 |
|-----|------|-----|
| Clear Claimed Marks | 0.031ms | 清除标记位 |
| Scan Root Regions | 0.034ms | 扫描Survivor Region |
| **Mark From Roots** | **57.970ms** | 从根开始并发标记 |
| Preclean | 0.426ms | 预清理引用 |
| **Concurrent Mark Total** | **58.422ms** | 并发标记总时间 |

### 3.4 Remark阶段(STW)

```
[15.030s][info ][gc,start] GC(17) Pause Remark
[15.031s][debug][gc,phases,start] GC(17) Finalize Marking
[15.031s][debug][gc,stats] Marking Stats, task = 0, calls = 60
[15.031s][debug][gc,stats]   Elapsed time = 0.16ms, Termination time = 15.00ms
[15.031s][debug][gc,stats]   Mark Stats Cache: hits 13928 misses 117 ratio 99.167

[15.068s][debug][gc,phases] GC(17) System Dictionary Unloading 36.545ms
[15.068s][debug][gc,phases] GC(17) Flush Task Caches 0.125ms
[15.069s][debug][gc,phases] GC(17) Update Remembered Set Tracking Before Rebuild 0.381ms
[15.069s][debug][gc,remset,tracking] GC(17) Remembered Set Tracking update regions total 2048, selected 270

[15.194s][debug][gc] GC(17) Reclaimed 674 empty regions
[15.195s][debug][gc,phases] GC(17) Reclaim Empty Regions 126.236ms
[15.195s][debug][gc,phases] GC(17) Purge Metaspace 0.010ms

[15.196s][info ][gc] GC(17) Pause Remark 3885M->1189M(8192M) 165.588ms
```

**Remark关键操作时间**:

| 操作 | 时间 | 说明 |
|-----|------|-----|
| Finalize Marking | 0.16ms | 完成标记 |
| System Dictionary Unloading | 36.545ms | 卸载未使用类 |
| Update RemSet Tracking | 0.381ms | 更新RemSet追踪 |
| **Reclaim Empty Regions** | **126.236ms** | 回收空Region |
| Purge Metaspace | 0.010ms | 清理Metaspace |
| **Remark Total** | **165.588ms** | 总暂停时间 |

**空间回收**:
- **Remark前**: 3885MB
- **Remark后**: 1189MB
- **回收**: 2696MB (674个空Region)

### 3.5 Cleanup阶段(STW)

```
[15.226s][info ][gc,start] GC(17) Pause Cleanup
[15.227s][debug][gc,phases] GC(17) Update Remembered Set Tracking After Rebuild 0.410ms
[15.228s][debug][gc,ergo] GC(17) request young-only gcs (reclaimable percentage not over threshold). 
    candidate old regions: 103 
    reclaimable: 307008072 (3.57) 
    threshold: 5

[15.229s][info ][gc] GC(17) Pause Cleanup 1259M->1259M(8192M) 2.138ms
```

**Cleanup分析**:
- **暂停时间**: 2.138ms (很短)
- **候选Old Region**: 103个
- **可回收空间**: 307MB (3.57%)
- **回收阈值**: 5%
- **决策**: `request young-only gcs` (可回收比例<5%，不触发Mixed GC)

### 3.6 Concurrent Cleanup for Next Mark

```
[15.229s][info ][gc,marking] GC(17) Concurrent Cleanup for Next Mark
[15.229s][debug][gc,ergo  ] GC(17) Running G1 Clear Bitmap with 3 workers for 128 work units.
[15.226s][info ][gc,marking] GC(17) Concurrent Rebuild Remembered Sets 30.518ms
```

### 3.7 并发标记周期完整时间线

```
[14.971s][info ][gc] GC(17) Concurrent Cycle
... (各阶段执行)
[15.259s][info ][gc] GC(17) Concurrent Cycle 288.587ms
```

**并发周期时间分解**:

| 类型 | 阶段 | 时间 | STW |
|-----|-----|------|-----|
| 并发 | Clear Claimed Marks | 0.031ms | 否 |
| 并发 | Scan Root Regions | 0.034ms | 否 |
| 并发 | Mark From Roots | 57.970ms | 否 |
| 并发 | Preclean | 0.426ms | 否 |
| **STW** | **Remark** | **165.588ms** | **是** |
| 并发 | Rebuild RemSet | 30.518ms | 否 |
| **STW** | **Cleanup** | **2.138ms** | **是** |
| 并发 | Cleanup for Next Mark | ~30ms | 否 |
| - | **总计** | **288.587ms** | - |

**STW总时间**: 165.588ms + 2.138ms = **167.726ms**

## 4. Mixed GC触发条件分析

### 4.1 为什么没有触发Mixed GC

从日志中我们看到多次并发标记后的判断：

```
[15.228s][debug][gc,ergo] GC(17) request young-only gcs (reclaimable percentage not over threshold). 
    candidate old regions: 103 
    reclaimable: 307008072 (3.57) 
    threshold: 5

[33.795s][debug][gc,ergo] GC(40) request young-only gcs (reclaimable percentage not over threshold). 
    candidate old regions: 13 
    reclaimable: 28940000 (0.34) 
    threshold: 5

[35.766s][debug][gc,ergo] GC(44) request young-only gcs (reclaimable percentage not over threshold). 
    candidate old regions: 13 
    reclaimable: 28940000 (0.34) 
    threshold: 5
```

**Mixed GC触发失败原因**:
- **G1HeapWastePercent**: 默认5%
- **实际可回收比例**: 
  - GC(17): 3.57% < 5%
  - GC(40): 0.34% < 5%
  - GC(44): 0.34% < 5%
- **结论**: 可回收垃圾比例未达到阈值，G1选择继续Young GC

### 4.2 Mixed GC触发公式验证

```cpp
// 源码: g1CollectionSet.cpp
if (reclaimable_bytes_percent <= G1HeapWastePercent) {
  log_debug(gc, ergo)("request young-only gcs (reclaimable percentage not over threshold).");
  return false;
}
```

**计算验证**:
- 堆总大小: 8GB = 8589934592B
- GC(17)可回收: 307008072B / 8589934592B = 3.57%
- 阈值: 5%
- 判断: 3.57% < 5% → 不触发Mixed GC

## 5. Full GC详细分析

### 5.1 触发原因

```
[25.589s][info ][gc,start] GC(30) Pause Full (System.gc())
[25.589s][debug][gc,heap ] GC(30) Heap before GC invocations=30 (full 0): 
    garbage-first heap total 8388608K, used 3822272K
[25.589s][debug][gc,heap ] GC(30) region size 4096K, 78 young (319488K), 13 survivors (53248K)
```

**触发分析**:
- **触发原因**: `System.gc()` 显式调用
- **GC前堆使用**: 3822272K = 3.65GB
- **Young Region**: 78个
- **Survivor Region**: 13个

### 5.2 Full GC四阶段时间

```
[25.590s][info ][gc,phases,start] GC(30) Phase 1: Mark live objects
[25.597s][debug][gc,phases,start] GC(30) Phase 1: Reference Processing
[25.597s][debug][gc,phases      ] GC(30) Phase 1: Reference Processing 0.489ms
[25.597s][debug][gc,phases,start] GC(30) Phase 1: Weak Processing
[25.597s][debug][gc,phases      ] GC(30) Phase 1: Weak Processing 0.039ms
[25.597s][debug][gc,phases,start] GC(30) Phase 1: Class Unloading and Cleanup
[25.636s][debug][gc,phases      ] GC(30) Phase 1: Class Unloading and Cleanup 39.041ms
[25.637s][info ][gc,phases      ] GC(30) Phase 1: Mark live objects 46.428ms

[25.637s][info ][gc,phases,start] GC(30) Phase 2: Prepare for compaction
[25.684s][info ][gc,phases      ] GC(30) Phase 2: Prepare for compaction 47.436ms

[25.684s][info ][gc,phases,start] GC(30) Phase 3: Adjust pointers
[25.698s][info ][gc,phases      ] GC(30) Phase 3: Adjust pointers 14.129ms

[25.698s][info ][gc,phases,start] GC(30) Phase 4: Compact heap
[26.250s][info ][gc,phases      ] GC(30) Phase 4: Compact heap 552.203ms
```

**Full GC四阶段时间表**:

| 阶段 | 时间 | 占比 | 说明 |
|-----|------|-----|-----|
| Phase 1: Mark | 46.428ms | 6.9% | 标记存活对象 |
| Phase 2: Prepare | 47.436ms | 7.1% | 准备压缩 |
| Phase 3: Adjust | 14.129ms | 2.1% | 调整指针 |
| **Phase 4: Compact** | **552.203ms** | **82.5%** | 堆压缩 |
| **总计** | **669.224ms** | - | - |

**Phase 1子阶段**:
- Reference Processing: 0.489ms
- Weak Processing: 0.039ms  
- Class Unloading: 39.041ms

### 5.3 Full GC结果

```
[26.258s][info ][gc] GC(30) Pause Full (System.gc()) 3737M->945M(8192M) 669.224ms
```

**效果分析**:
- **回收前**: 3737MB
- **回收后**: 945MB
- **回收量**: 2792MB (74.7%回收率)
- **暂停时间**: 669.224ms (非常长!)

## 6. GC类型统计汇总

从GC日志中提取的完整GC事件统计：

### 6.1 所有GC事件

| GC类型 | 次数 | 平均时间 | 最短 | 最长 |
|-------|------|---------|------|------|
| Young (Normal) | 44 | ~300ms | 138ms | 493ms |
| Young (Concurrent Start) | 4 | ~450ms | 139ms | 488ms |
| Remark | 4 | ~100ms | 42ms | 166ms |
| Cleanup | 4 | ~2ms | 2ms | 2ms |
| Full (System.gc()) | 1 | 669ms | - | - |
| **总计** | **57** | - | - | - |

### 6.2 并发周期统计

| 周期 | GC编号 | 总时间 | Remark | Cleanup |
|-----|-------|-------|--------|---------|
| 1 | GC(17) | 288.6ms | 165.6ms | 2.1ms |
| 2 | GC(40) | 761.3ms | 42.2ms | 2.2ms |
| 3 | GC(44) | 704.6ms | 42.5ms | 2.2ms |
| 4 | GC(48) | 727.8ms | 43.0ms | ~2ms |

## 7. 关键发现与验证结论

### 7.1 源码分析验证结果

| 验证项 | 源码预期 | 实际日志 | 验证状态 |
|-------|---------|---------|---------|
| Region大小 | 4MB | `region size 4096K` | ✅ 一致 |
| IHOP阈值 | 45% = 3.6GB | `threshold: 3865470566B (45.00)` | ✅ 一致 |
| ParallelGCThreads | ~CPU核心数 | 13 | ✅ 合理 |
| ConcGCThreads | ParallelGC/4 | 3 | ✅ 一致 |
| G1HeapWastePercent | 5% | Mixed GC判断使用5% | ✅ 一致 |
| Young GC三阶段 | Pre/Evacuate/Post | 日志完整输出 | ✅ 一致 |
| 并发标记7阶段 | Clear/Scan/Mark/Preclean/Remark/Rebuild/Cleanup | 日志完整输出 | ✅ 一致 |
| Full GC四阶段 | Mark/Prepare/Adjust/Compact | 日志完整输出 | ✅ 一致 |

### 7.2 性能问题诊断

1. **Young GC暂停时间过长** (平均300ms，目标200ms)
   - 原因: Object Copy阶段耗时长(存活对象多)
   - 原因: Free Collection Set阶段异常(139ms)
   
2. **Full GC暂停时间严重** (669ms)
   - 主要耗时: Phase 4 Compact (552ms, 82.5%)
   - 建议: 避免System.gc()调用

3. **Mixed GC未触发**
   - 原因: 可回收垃圾比例<5%
   - 建议: 调整G1HeapWastePercent参数

### 7.3 8GB堆关键参数验证总结

| 参数 | 值 | 来源 |
|-----|---|-----|
| Region数量 | 2,048 | 8GB/4MB |
| Eden Region初始 | ~100 | 动态调整 |
| Survivor Region | 13 | 基于存活率 |
| IHOP阈值 | 3.6GB (45%) | 参数配置 |
| 期望Survivor大小 | 26MB | 动态计算 |
| 最大年龄阈值 | 15 | MaxTenuringThreshold |
| 并行GC线程 | 13 | CPU核心数 |
| 并发标记线程 | 3 | ParallelGC/4 |

## 8. GC日志解读速查表

### 8.1 日志Tag含义

| Tag | 含义 |
|-----|-----|
| gc | 基本GC信息 |
| gc,start | GC开始 |
| gc,heap | 堆状态 |
| gc,phases | 阶段时间 |
| gc,task | Worker任务 |
| gc,age | 年龄信息 |
| gc,ihop | IHOP统计 |
| gc,ergo | 自适应决策 |
| gc,marking | 并发标记 |
| gc,cpu | CPU时间 |
| gc,tlab | TLAB统计 |
| gc,ref | 引用处理 |
| gc,remset | RemSet信息 |

### 8.2 GC类型标识

| 日志输出 | 含义 |
|---------|-----|
| Pause Young (Normal) | 普通Young GC |
| Pause Young (Concurrent Start) | 启动并发标记的Young GC |
| Pause Young (Prepare Mixed) | 准备Mixed GC |
| Pause Young (Mixed) | Mixed GC |
| Pause Remark | 最终标记(STW) |
| Pause Cleanup | 清理(STW) |
| Pause Full | Full GC |
| Concurrent Cycle | 并发标记周期 |

---

> 本文档通过真实GC日志验证了G1 GC源码分析结论，所有关键参数和执行流程均与源码一致。
