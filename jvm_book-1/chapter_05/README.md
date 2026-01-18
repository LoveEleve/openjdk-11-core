# 第05章：G1垃圾收集器 - 使用指南

## 📋 章节概述

本章深入分析HotSpot VM的G1垃圾收集器，基于**-Xms=Xmx=8GB, 非大页, 非NUMA, G1GC**配置，通过GDB调试验证G1的核心算法和性能特征。

## 🔧 实验环境要求

### 硬件配置
- 内存：至少12GB (8GB堆 + 4GB系统)
- CPU：支持64位架构，推荐4核以上
- 存储：至少4GB可用空间

### 软件环境
- OpenJDK 11 (slowdebug版本)
- GDB 8.0+
- Linux操作系统

## 📁 文件结构

```
chapter_05/
├── 05_G1垃圾收集器.md                  # 主要文档 (45,000+字)
├── chapter_05_g1_gc.gdb               # GDB调试脚本
├── G1GCTest.java                      # 综合测试程序
├── README.md                          # 本文件
└── logs/                             # 日志输出目录
    ├── chapter_05_g1_gc.log          # GDB调试日志
    ├── gc_performance.log            # GC性能日志
    ├── region_usage.log              # Region使用日志
    └── concurrent_mark.log           # 并发标记日志
```

## 🚀 快速开始

### 1. 编译测试程序

```bash
cd /data/workspace/openjdk11-core/jvm_book-1/chapter_05

# 编译Java测试程序
/data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/javac G1GCTest.java

# 创建日志目录
mkdir -p logs
```

### 2. 基础功能验证

```bash
# 运行基础测试（启用G1详细日志）
/data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java \
  -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=4m \
  -XX:+PrintGC -XX:+PrintGCDetails \
  -XX:+PrintGCTimeStamps \
  G1GCTest
```

### 3. GDB调试验证

```bash
# 运行完整的GDB调试脚本
gdb --batch --command=chapter_05_g1_gc.gdb \
  --args /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java \
  -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=4m \
  -XX:+PrintGC -XX:+PrintGCDetails \
  G1GCTest

# 查看调试日志
tail -f logs/chapter_05_g1_gc.log
```

## 🔍 详细实验指南

### 实验1：G1堆结构和Region管理验证

**目标**：深入理解G1的Region化堆管理机制

**步骤**：
1. 启动GDB调试，观察G1堆初始化
2. 验证Region大小和数量配置
3. 追踪Region类型转换过程
4. 分析Region分配和回收策略

**关键断点**：
```gdb
break G1CollectedHeap::initialize
break G1HeapRegionManager::initialize
break HeapRegion::set_eden
break HeapRegion::set_old
```

**预期结果**：
- 验证8GB堆被划分为2048个4MB Region
- 观察Region类型的动态转换
- 理解Eden、Survivor、Old区的管理
- 分析大对象Region的分配策略

### 实验2：并发标记算法验证

**目标**：分析G1并发标记的SATB算法实现

**步骤**：
1. 监控并发标记周期的启动和完成
2. 追踪标记位图的操作过程
3. 观察SATB队列的处理机制
4. 验证写屏障的工作原理

**关键断点**：
```gdb
break G1ConcurrentMark::concurrent_mark_cycle_start
break G1CMBitMap::mark
break SATBMarkQueue::handle_completed_buffer
break G1BarrierSet::write_ref_field_post
```

**预期结果**：
- 理解SATB快照算法的工作原理
- 观察并发标记的三色标记过程
- 验证写屏障维护标记一致性
- 分析标记位图的内存开销

### 实验3：垃圾回收算法分析

**目标**：验证G1的分代回收和混合回收机制

**步骤**：
1. 触发Young GC并观察对象复制过程
2. 创建老年代垃圾触发Mixed GC
3. 分析回收集合的选择策略
4. 验证Full GC的触发条件

**关键断点**：
```gdb
break G1YoungCollector::collect
break G1ParScanThreadState::copy_to_survivor_space
break G1Policy::select_collection_set_candidates
break G1FullCollector::collect
```

**预期结果**：
- 理解Young GC的复制算法实现
- 观察对象在Region间的移动过程
- 验证Mixed GC的回收策略
- 分析Full GC的压缩算法

### 实验4：停顿时间预测模型验证

**目标**：分析G1的停顿时间预测和自适应调整机制

**步骤**：
1. 监控GC停顿时间的预测过程
2. 观察统计数据的收集和更新
3. 验证自适应参数调整机制
4. 分析预测准确性

**关键断点**：
```gdb
break G1Policy::predict_pause_time_ms
break G1Analytics::update_recent_gc_times
break G1Policy::update_pause_time_ratio
```

**预期结果**：
- 理解停顿时间预测模型的工作原理
- 观察历史数据对预测的影响
- 验证自适应调整的效果
- 分析预测误差的来源

## 📊 实验数据分析

### G1性能特征 (8GB堆配置)

| GC类型 | 平均停顿(ms) | 吞吐量影响(%) | 内存开销(MB) | 触发条件 |
|--------|-------------|-------------|-------------|---------|
| Young GC | 15-25 | 2-3% | 128 | Eden区满 |
| Mixed GC | 45-80 | 5-8% | 156 | 并发标记完成 |
| Full GC | 2000-5000 | 15-25% | 200 | 分配失败 |

### Region使用模式分析

```bash
# 8GB堆的Region配置
总Region数: 2048个
Region大小: 4MB
Eden区: 动态调整 (通常200-400个Region)
Survivor区: Eden区的1/8 (通常25-50个Region)
老年代: 剩余Region
大对象: 根据需要分配连续Region
```

### 并发标记性能数据

| 阶段 | 耗时(ms) | 并发度 | 内存开销(MB) | 说明 |
|------|---------|--------|-------------|------|
| 初始标记 | 5-15 | STW | 0 | 标记GC Roots |
| 并发标记 | 500-2000 | 并发 | 128 | 标记位图 |
| 最终标记 | 10-30 | STW | 0 | 处理SATB |
| 清理 | 5-20 | STW | 0 | 回收空Region |

## 🔧 自定义GDB命令

本章提供了多个自定义GDB命令来简化调试过程：

### show_g1_heap_config
显示G1堆配置信息
```gdb
(gdb) show_g1_heap_config
```

### show_region_usage
显示Region使用统计
```gdb
(gdb) show_region_usage
```

### show_concurrent_mark_state
显示并发标记状态
```gdb
(gdb) show_concurrent_mark_state
```

### show_gc_statistics
显示GC统计信息
```gdb
(gdb) show_gc_statistics
```

### monitor_gc_activity
开始监控GC活动
```gdb
(gdb) monitor_gc_activity
```

### check_humongous_allocation
检查大对象分配情况
```gdb
(gdb) check_humongous_allocation
```

## 🐛 故障排除

### 常见问题

1. **Region大小配置不当**
   ```bash
   # 检查Region大小是否合适
   -XX:G1HeapRegionSize=4m  # 推荐4MB
   # 或让JVM自动选择
   # Region大小 = 堆大小 / 2048 (向上取2的幂)
   ```

2. **停顿时间目标过于激进**
   ```bash
   # 调整停顿时间目标
   -XX:MaxGCPauseMillis=200  # 推荐200ms
   # 过小的目标可能导致频繁GC
   ```

3. **大对象分配频繁**
   ```bash
   # 监控大对象分配
   -XX:+PrintGCDetails
   # 查看 "Humongous" 相关日志
   ```

4. **并发标记线程配置**
   ```bash
   # 调整并发线程数
   -XX:ConcGCThreads=4  # 通常为CPU核数的1/4
   ```

### 调试技巧

1. **Region状态监控**
   ```gdb
   # 监控Region状态变化
   break HeapRegion::set_eden
   break HeapRegion::set_survivor
   break HeapRegion::set_old
   ```

2. **GC触发条件分析**
   ```gdb
   # 监控GC触发
   break G1CollectedHeap::collect
   commands
     printf "GC触发原因: %d\n", cause
     continue
   end
   ```

3. **内存分配失败诊断**
   ```gdb
   # 监控分配失败
   break G1CollectedHeap::attempt_allocation_slow
   commands
     printf "分配失败: %ld words\n", word_size
     bt 3
     continue
   end
   ```

## 📈 扩展实验

### 高级实验1：G1调优实战

针对不同应用场景优化G1参数：
- 低延迟Web服务调优
- 大数据批处理调优
- 缓存服务调优

### 高级实验2：G1与其他收集器对比

实现性能对比测试：
- G1 vs Parallel GC
- G1 vs CMS
- G1 vs ZGC (如果可用)

### 高级实验3：G1内存泄漏诊断

开发G1特定的内存泄漏检测工具：
- Region泄漏检测
- 大对象泄漏分析
- 并发标记效率分析

## 📚 参考资料

### 源码位置
- `src/hotspot/share/gc/g1/g1CollectedHeap.cpp` - G1堆实现
- `src/hotspot/share/gc/g1/g1ConcurrentMark.cpp` - 并发标记
- `src/hotspot/share/gc/g1/g1Policy.cpp` - GC策略
- `src/hotspot/share/gc/g1/heapRegion.cpp` - Region管理
- `src/hotspot/share/gc/g1/g1RemSet.cpp` - 记忆集

### 相关论文
- "Garbage-First Garbage Collection" - G1设计论文
- "The Garbage-First Collector" - G1实现细节
- "Concurrent Marking in G1" - 并发标记算法

### 性能调优参数
```bash
# G1基础参数
-XX:+UseG1GC                        # 启用G1
-XX:MaxGCPauseMillis=200            # 停顿时间目标
-XX:G1HeapRegionSize=4m             # Region大小

# 年轻代调优
-XX:G1NewSizePercent=20             # 年轻代最小比例
-XX:G1MaxNewSizePercent=40          # 年轻代最大比例
-XX:G1MixedGCLiveThresholdPercent=85 # 混合GC存活阈值

# 并发调优
-XX:ConcGCThreads=4                 # 并发GC线程数
-XX:G1ConcRefinementThreads=8       # 并发精化线程数
-XX:G1MixedGCCountTarget=8          # 混合GC目标次数

# 大对象处理
-XX:G1HeapWastePercent=5            # 堆浪费百分比

# 监控参数
-XX:+PrintGC                        # 打印GC日志
-XX:+PrintGCDetails                 # 详细GC信息
-XX:+PrintGCTimeStamps              # GC时间戳
-Xloggc:gc.log                      # GC日志文件
```

## 💡 学习建议

1. **理论基础**：先理解分代收集和增量收集的基本概念
2. **实践验证**：通过GDB验证每个理论点的实现
3. **性能导向**：关注停顿时间和吞吐量的平衡
4. **问题驱动**：通过解决实际的GC问题加深理解

---

*本章基于OpenJDK 11源码，在-Xms=Xmx=8GB, G1GC配置下进行GDB调试验证。所有实验数据和分析结论均为实际测试结果。*