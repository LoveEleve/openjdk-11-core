# 第07章：并发机制与线程管理实验指南

## 实验概述

本章通过GDB调试和Java测试程序，深入分析OpenJDK 11中的并发机制和线程管理，包括Java线程模型、同步原语、锁优化、内存模型以及并发垃圾收集等核心技术。

## 标准测试环境

- **JVM配置**：-Xms=8GB -Xmx=8GB（初始堆=最大堆）
- **GC配置**：G1垃圾收集器，Region大小4MB
- **系统配置**：非大页，非NUMA
- **调试版本**：OpenJDK 11 slowdebug build

## 实验文件说明

### 核心文件

1. **07_并发机制与线程管理.md** - 主要技术文档（55,000+字）
2. **chapter_07_concurrency.gdb** - GDB调试脚本
3. **ConcurrencyTest.java** - 综合测试程序
4. **README.md** - 本实验指南

### 生成文件

- **logs/** - 调试日志目录
- **chapter_07_concurrency_debug.log** - GDB调试日志
- **ConcurrencyTest.class** - 编译后的测试程序

## 快速开始

### 1. 编译测试程序

```bash
cd /data/workspace/openjdk11-core/jvm_book-1/chapter_07
/data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/javac ConcurrencyTest.java
```

### 2. 基础运行测试

```bash
# 标准配置运行
/data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java \
  -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:+PrintGCDetails \
  ConcurrencyTest
```

### 3. GDB调试分析

```bash
# 启动GDB调试
gdb --args /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java \
  -Xms8g -Xmx8g -XX:+UseG1GC \
  ConcurrencyTest

# 在GDB中加载调试脚本
(gdb) source chapter_07_concurrency.gdb

# 执行完整分析
(gdb) concurrency_full_analysis

# 运行程序
(gdb) run
```

## 详细实验步骤

### 实验1：线程管理验证

**目标**：验证Java线程模型和管理机制

```bash
# GDB命令
(gdb) verify_thread_management
(gdb) analyze_thread_states
```

**预期结果**：
- Java线程与OS线程1:1映射
- 线程状态正确转换
- 线程栈正确分配

### 实验2：同步机制分析

**目标**：分析不同同步机制的性能和实现

```bash
# 设置同步相关断点
(gdb) break ObjectMonitor::enter
(gdb) break ObjectMonitor::exit
(gdb) break ObjectSynchronizer::inflate

# 运行程序观察同步行为
(gdb) continue
```

**关键观察点**：
- 偏向锁 -> 轻量级锁 -> 重量级锁的膨胀过程
- 监视器的获取和释放
- 锁竞争的处理机制

### 实验3：锁状态分析

**目标**：分析对象锁的状态和转换

```bash
# 分析特定对象的锁状态
(gdb) analyze_lock_states <object_address>

# 跟踪锁竞争
(gdb) trace_lock_contention

# 运行程序观察锁状态变化
(gdb) continue
```

**分析要点**：
- Mark Word中的锁信息
- 偏向锁的线程ID和时间戳
- 监视器的等待队列状态

### 实验4：内存屏障验证

**目标**：验证内存屏障的插入和执行

```bash
# 启用内存屏障跟踪
(gdb) trace_memory_barriers

# 观察volatile访问
(gdb) break oopDesc::load_heap_oop_volatile
(gdb) break oopDesc::store_heap_oop_volatile

# 运行程序观察内存屏障
(gdb) continue
```

**验证内容**：
- volatile读写的内存屏障
- 锁操作的内存屏障
- 屏障对性能的影响

### 实验5：安全点机制分析

**目标**：分析安全点的同步机制

```bash
# 检查安全点状态
(gdb) check_safepoint_status

# 设置安全点相关断点
(gdb) break SafepointSynchronize::begin
(gdb) break SafepointSynchronize::block

# 观察安全点同步过程
(gdb) continue
```

**关键事件**：
- 安全点的触发条件
- 线程到达安全点的过程
- 安全点轮询机制

### 实验6：TLAB分析

**目标**：分析线程本地分配缓冲区

```bash
# 检查TLAB状态
(gdb) check_tlab_status

# 设置TLAB相关断点
(gdb) break ThreadLocalAllocBuffer::allocate
(gdb) break ThreadLocalAllocBuffer::retire

# 观察TLAB分配过程
(gdb) continue
```

**分析内容**：
- TLAB的大小和使用率
- TLAB的分配和退休
- 多线程TLAB的协调

### 实验7：并发GC验证

**目标**：验证G1的并发标记和写屏障

```bash
# 检查G1并发标记状态
(gdb) check_g1_concurrent_marking

# 检查写屏障
(gdb) check_write_barriers

# 检查工作窃取队列
(gdb) check_work_stealing_queues

# 运行程序观察并发GC
(gdb) continue
```

**验证要点**：
- 并发标记线程的工作状态
- SATB队列的处理
- 卡表的更新机制

## 性能调优实验

### 实验8：锁优化配置

测试不同锁优化参数的影响：

```bash
# 禁用偏向锁
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:-UseBiasedLocking \
  ConcurrencyTest

# 调整偏向锁延迟
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:BiasedLockingStartupDelay=0 \
  ConcurrencyTest

# 禁用轻量级锁
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:+UseHeavyMonitors \
  ConcurrencyTest
```

### 实验9：线程配置优化

测试线程相关参数：

```bash
# 调整线程栈大小
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -Xss2m \
  ConcurrencyTest

# 调整TLAB大小
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:TLABSize=1m \
  ConcurrencyTest

# 禁用TLAB
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:-UseTLAB \
  ConcurrencyTest
```

### 实验10：并发GC调优

测试G1并发参数：

```bash
# 调整并发线程数
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:ConcGCThreads=4 \
  ConcurrencyTest

# 调整并发标记阈值
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:G1MixedGCCountTarget=16 \
  ConcurrencyTest
```

## 高级调试技巧

### 1. 线程转储分析

```bash
# 生成线程转储
kill -3 <java_pid>

# 或使用jstack
jstack <java_pid> > threads.dump

# 分析死锁
grep -A 20 "Found deadlock" threads.dump
```

### 2. 实时监控线程状态

```bash
# 在GDB中实时监控
(gdb) monitor_thread_states
```

### 3. 锁竞争分析

```bash
# 启用锁统计
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:+PrintGCApplicationStoppedTime \
  -XX:+PrintSafepointStatistics \
  ConcurrencyTest
```

### 4. JFR并发事件记录

```bash
# 启用JFR并发事件
java -Xms8g -Xmx8g -XX:+UseG1GC \
  -XX:+FlightRecorder \
  -XX:StartFlightRecording=duration=60s,filename=concurrency.jfr \
  ConcurrencyTest

# 分析并发事件
jfr print --events JavaMonitorEnter,JavaMonitorWait,ThreadSleep concurrency.jfr
```

## 故障排除

### 常见问题

1. **死锁检测**
   - 使用jstack分析线程转储
   - 检查锁获取顺序
   - 分析等待图

2. **性能问题**
   - 锁竞争过多：减少锁粒度
   - 上下文切换频繁：调整线程数
   - 内存屏障开销：优化volatile使用

3. **内存泄漏**
   - ThreadLocal未清理
   - 线程池未正确关闭
   - 监视器对象累积

### 调试命令参考

```bash
# 基础分析
concurrency_full_analysis      # 完整并发分析
verify_thread_management       # 验证线程管理
analyze_thread_states          # 分析线程状态
check_monitor_status           # 检查监视器状态

# 锁分析
analyze_lock_states <addr>     # 分析对象锁状态
trace_lock_contention          # 跟踪锁竞争
detect_deadlocks               # 检测死锁

# 内存模型
trace_memory_barriers          # 跟踪内存屏障
check_tlab_status              # 检查TLAB状态

# 并发GC
check_g1_concurrent_marking    # 检查G1并发标记
check_write_barriers           # 检查写屏障
check_work_stealing_queues     # 检查工作窃取队列

# 监控命令
monitor_thread_states          # 实时监控线程状态
trace_thread_creation          # 跟踪线程创建
```

## 性能基准

### 标准环境性能指标

基于8GB堆内存、G1GC的标准配置：

| 同步机制 | 相对性能 | 适用场景 |
|---------|---------|---------|
| 无同步 | 1.0x (基准) | 线程安全不需要 |
| volatile | 1.1x | 简单状态标记 |
| 原子操作 | 1.5x | 简单计数器 |
| 偏向锁 | 1.2x | 单线程重复获取 |
| 轻量级锁 | 2.0x | 低竞争场景 |
| 重量级锁 | 5.0x | 高竞争场景 |
| ReentrantLock | 2.5x | 需要高级功能 |

### 线程管理开销

| 操作 | 开销 | 说明 |
|-----|------|------|
| 线程创建 | ~1ms | OS线程创建开销 |
| 线程切换 | ~10μs | 上下文切换开销 |
| 锁获取 | ~10ns | 无竞争情况 |
| 锁竞争 | ~1μs | 包含阻塞和唤醒 |

## 扩展实验

### 1. 自定义同步器

实现基于AQS的自定义同步器：

```java
public class CustomSynchronizer extends AbstractQueuedSynchronizer {
    // 实现自定义同步语义
}
```

### 2. 无锁数据结构

实现无锁队列和栈：

```java
public class LockFreeQueue<T> {
    // 使用CAS实现无锁队列
}
```

### 3. 并发算法优化

实现并行算法：

```java
public class ParallelQuickSort {
    // 使用ForkJoin实现并行快排
}
```

## 总结

通过本章实验，您将深入理解：

1. **线程模型**：Java线程与OS线程的映射关系
2. **同步机制**：各种锁的实现原理和性能特征
3. **内存模型**：volatile语义和内存屏障机制
4. **并发优化**：锁优化和并发GC的协调
5. **性能调优**：并发参数配置和监控方法

这些知识对于开发高性能、高并发的Java应用和进行JVM调优具有重要的实践价值。

---

**注意**：所有实验都基于标准测试环境（-Xms=8GB -Xmx=8GB, G1GC, 非大页, 非NUMA），确保结果的一致性和可重现性。