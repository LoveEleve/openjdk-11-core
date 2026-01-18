# JVM技术专家面试专项 - JVM调优实战

> **环境**: Linux, -Xms8g -Xmx8g, G1GC, Region=4MB, 非大页, 非NUMA
> **难度**: ⭐⭐⭐⭐⭐ JVM技术专家级

---

## 面试问题 1：如何分析GC日志？

### 👨‍💼 面试官提问

> "给你一段GC日志，如何快速定位性能问题？"

### 👨‍🎓 面试者回答

#### 1. 关键指标提取

```
[1.846s][info ][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 407M->395M(8192M) 487.304ms
```

| 指标 | 值 | 分析 |
|------|-----|------|
| GC类型 | Pause Young (Normal) | 普通Young GC |
| 触发原因 | G1 Evacuation Pause | Eden满触发 |
| 回收前 | 407M | - |
| 回收后 | 395M | 仅回收12M |
| 堆大小 | 8192M | 8GB |
| **停顿时间** | **487ms** | **超过200ms目标！** |

#### 2. 分阶段分析

```
[gc,phases] GC(0)   Pre Evacuate Collection Set: 0.2ms
[gc,phases] GC(0)   Evacuate Collection Set: 341.2ms   ★ 70%
[gc,phases] GC(0)   Post Evacuate Collection Set: 140.4ms ★ 29%
[gc,phases] GC(0)   Other: 6.3ms
```

**问题定位**：Object Copy占主导，存活对象过多

#### 3. 诊断结论

| 问题 | 原因 | 建议 |
|------|------|------|
| 停顿487ms | 存活对象多(回收率3%) | 减小Eden、调整年龄阈值 |
| 晋升率84% | 对象生命周期长 | 检查对象池设计 |
| Free CSet慢 | Region数量多 | 减少一次GC的Region数 |

---

## 面试问题 2：如何设置合理的GC参数？

### 👨‍💼 面试官提问

> "8GB堆，要求P99延迟100ms，如何配置G1参数？"

### 👨‍🎓 面试者回答

#### 推荐配置

```bash
java -Xms8g -Xmx8g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=80 \          # 目标80ms
     -XX:G1HeapRegionSize=4m \
     -XX:InitiatingHeapOccupancyPercent=35 \
     -XX:G1NewSizePercent=20 \
     -XX:G1MaxNewSizePercent=30 \
     -XX:G1ReservePercent=15 \
     -Xlog:gc*:file=gc.log:time,tags
```

| 参数 | 值 | 理由 |
|------|-----|------|
| MaxGCPauseMillis | 80ms | 留20ms余量 |
| IHOP | 35% | 提前触发并发标记 |
| G1MaxNewSizePercent | 30% | 控制年轻代大小 |

---

## 面试问题 3：Full GC频繁如何排查？

### 👨‍💼 面试官提问

> "生产环境频繁Full GC，如何排查？"

### 👨‍🎓 面试者回答

#### 1. 常见原因

| 原因 | 日志特征 | 解决方案 |
|------|----------|----------|
| Allocation Failure | `Pause Full (Allocation Failure)` | 增大堆/优化分配 |
| Metadata不足 | `Metadata GC Threshold` | 增大MetaspaceSize |
| System.gc() | `System.gc()` | -XX:+DisableExplicitGC |
| 并发标记失败 | `to-space exhausted` | 降低IHOP |

#### 2. 排查命令

```bash
# 实时监控
jstat -gcutil <pid> 1000

# 堆分析
jmap -histo:live <pid> | head -20

# GC原因
grep "Full GC\|Pause Full" gc.log
```

---

## 面试问题 4：如何分析内存泄漏？

### 👨‍💼 面试官提问

> "老年代持续增长，如何确认是否内存泄漏？"

### 👨‍🎓 面试者回答

#### 1. 诊断步骤

```bash
# Step 1: 确认老年代增长趋势
jstat -gcutil <pid> 5000 20

# Step 2: 触发Full GC后观察
jcmd <pid> GC.run
# 如果Full GC后老年代仍然很高 → 可能泄漏

# Step 3: 导出堆转储
jmap -dump:live,format=b,file=heap.hprof <pid>

# Step 4: 分析
# 使用MAT/VisualVM分析Dominator Tree
```

#### 2. 常见泄漏模式

| 模式 | 特征 | 解决 |
|------|------|------|
| 集合泄漏 | HashMap持续增长 | 检查remove逻辑 |
| 监听器泄漏 | Listener未注销 | 添加注销代码 |
| 连接泄漏 | Connection未关闭 | 使用try-with-resources |
| ThreadLocal | 线程池+ThreadLocal | 清理ThreadLocal |

---

## 面试问题 5：JIT编译对GC的影响？

### 👨‍💼 面试官提问

> "JIT编译会影响GC吗？如何影响？"

### 👨‍🎓 面试者回答

#### 1. 影响点

| 方面 | 影响 | 原因 |
|------|------|------|
| CodeCache | GC需要扫描Code Roots | JIT代码中可能有堆引用 |
| 逃逸分析 | 减少堆分配 | 栈上分配/标量替换 |
| 内联 | 改变对象生命周期 | 可能延长或缩短引用 |

#### 2. 监控

```bash
# 查看CodeCache
jcmd <pid> Compiler.codecache

# GC日志中Code Root扫描时间
[gc,phases] GC(0)     Code Root Scanning (ms): Avg:0.1
```

---

## 面试问题 6：如何用NMT诊断Native内存？

### 👨‍💼 面试官提问

> "如何分析JVM的Native内存使用？"

### 👨‍🎓 面试者回答

#### 1. 启用NMT

```bash
java -XX:NativeMemoryTracking=detail -jar app.jar
```

#### 2. 查看内存分布

```bash
jcmd <pid> VM.native_memory summary

# 输出解读
Total: reserved=10359MB, committed=9017MB
-                 Java Heap: reserved=8192MB, committed=8192MB
-                     Class: reserved=1082MB, committed=6MB
-                    Thread: reserved=22MB, committed=1MB
-                        GC: reserved=392MB, committed=392MB  ★
-                      Code: reserved=254MB, committed=10MB
```

#### 3. 8GB堆G1内存分布

| 组件 | 大小 | 占比 |
|------|------|------|
| Java Heap | 8192MB | 90.4% |
| **GC辅助结构** | **392MB** | **4.3%** |
| CodeCache | 254MB | 2.8% |
| Thread栈 | 22MB | 0.2% |
| 其他 | ~200MB | 2.2% |

---

## 面试问题 7：GDB调试JVM的技巧？

### 👨‍💼 面试官提问

> "如何用GDB调试HotSpot源码？"

### 👨‍🎓 面试者回答

#### 1. 启动调试

```bash
# 编译debug版本
./configure --enable-debug
make images

# GDB启动
gdb --args java -Xms8g -Xmx8g -XX:+UseG1GC MainClass
```

#### 2. 常用断点

```gdb
# GC入口
break g1CollectedHeap.cpp:do_collection_pause_at_safepoint

# 对象分配
break g1CollectedHeap.cpp:allocate_new_tlab

# 并发标记
break g1ConcurrentMark.cpp:mark_from_roots
```

#### 3. 查看G1状态

```gdb
# 堆信息
(gdb) p G1CollectedHeap::_g1h->_g1_policy->_young_list_target_length

# Region信息
(gdb) p HeapRegion::GrainBytes

# CMBitMap大小
(gdb) p G1CMBitMap::compute_size(8589934592)
```

---

## 总结：调优Checklist

### GC日志分析

- [ ] 停顿时间是否超标
- [ ] 三阶段时间占比是否正常
- [ ] Worker负载是否均衡
- [ ] 回收率是否合理

### 参数配置

- [ ] MaxGCPauseMillis设置合理
- [ ] IHOP根据实际调整
- [ ] 年轻代比例控制得当
- [ ] Reserve空间足够

### 问题排查

- [ ] Full GC原因确认
- [ ] 内存泄漏排查
- [ ] Native内存分析
- [ ] 应用代码优化

---

## 附录：常用命令速查

```bash
# GC监控
jstat -gcutil <pid> 1000

# 堆分析
jmap -histo:live <pid>
jmap -dump:live,format=b,file=heap.hprof <pid>

# Native内存
jcmd <pid> VM.native_memory summary

# 线程分析
jstack <pid> > thread.txt

# GC日志启用
-Xlog:gc*=debug:file=gc.log:time,uptime,level,tags
```
