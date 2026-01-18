# G1ConcurrentMark 构造函数详细分析

## 一、构造函数签名和初始化列表

### 1. 构造函数参数详解

```cpp
G1ConcurrentMark::G1ConcurrentMark(
    G1CollectedHeap* g1h,                      // forcus 指向G1堆对象的指针，提供堆的全局访问能力
    G1RegionToSpaceMapper* prev_bitmap_storage, // forcus 前一轮标记位图的虚拟内存映射器，管理位图的内存提交/取消
    G1RegionToSpaceMapper* next_bitmap_storage  // forcus 当前标记位图的虚拟内存映射器，支持动态堆大小调整
) :
```

**参数作用说明**：

| 参数名 | 类型 | 作用 | 为什么需要它 |
|--------|------|------|-------------|
| `g1h` | `G1CollectedHeap*` | G1堆的主要管理对象 | 并发标记需要访问堆的区域信息、边界、存活性数据等全局状态 |
| `prev_bitmap_storage` | `G1RegionToSpaceMapper*` | 管理前一轮标记位图的虚拟内存 | 支持动态内存管理，只为已使用的堆区域提交物理内存，节省内存开销 |
| `next_bitmap_storage` | `G1RegionToSpaceMapper*` | 管理当前标记位图的虚拟内存 | 同上，两个位图独立管理，支持双缓冲机制 |

---

## 二、初始化列表详细分析（第347-398行）

### 第一组：基础引用和状态标志

```cpp
  // 第348-349行
  // forcus 保存G1堆对象指针，提供全局堆信息访问能力
  // note 并发标记需要频繁访问堆的区域数量(_g1h->max_regions())、堆边界、区域管理器等
  _g1h(g1h),
  
  // forcus 初始化完成标志，默认为false，初始化成功后设为true
  // note 用于在构造函数返回前验证所有初始化步骤是否成功，失败则终止VM启动
  _completed_initialization(false),
```

**设计要点**：
- `_g1h`：几乎所有并发标记操作都需要访问堆信息，存储指针避免重复传参
- `_completed_initialization`：多阶段初始化需要验证机制，任何失败都不能让VM继续运行

---

### 第二组：双缓冲标记位图系统

```cpp
  // 第351-354行
  // forcus 创建两个标记位图对象（双缓冲机制）
  // note 每个位图对应堆中所有对象的一位标记，位图大小 = 堆大小 / 对象对齐大小
  _mark_bitmap_1(),  // 默认构造，实际初始化在构造函数体400行
  _mark_bitmap_2(),  // 默认构造，实际初始化在构造函数体401行
  
  // forcus 初始化位图指针，实现双缓冲切换
  // note prev保存已完成的标记结果，next是当前工作区，通过swap_mark_bitmaps()交换指针实现O(1)切换
  _prev_mark_bitmap(&_mark_bitmap_1),  // 指向bitmap1作为"上一轮完成的标记"
  _next_mark_bitmap(&_mark_bitmap_2),  // 指向bitmap2作为"当前正在标记"
```

**核心设计原理：为什么需要双缓冲？**

| 设计目标 | 实现方式 | 优势 |
|---------|---------|------|
| **快速切换** | 指针交换而非位图复制 | O(1)时间复杂度，避免复制数百MB的位图数据 |
| **增量GC支持** | prev保存历史标记结果 | Mixed GC可以基于prev决策回收哪些老年代区域 |
| **并发安全** | next作为工作区，prev作为稳定快照 | 标记线程写next，应用线程读prev，避免数据竞争 |
| **Remark优化** | 基于prev的差异标记 | 只需处理变化的对象，减少STW时间 |

**位图切换示意**：
```
标记周期开始前：
  _prev_mark_bitmap -> _mark_bitmap_1 (上一轮结果)
  _next_mark_bitmap -> _mark_bitmap_2 (清空，准备标记)

标记完成后调用swap_mark_bitmaps()：
  _prev_mark_bitmap -> _mark_bitmap_2 (最新结果)
  _next_mark_bitmap -> _mark_bitmap_1 (清空，下一轮使用)
```

---

### 第三组：堆边界和根区域

```cpp
  // 第356行
  // forcus 保存G1堆的内存区域范围（起始地址和结束地址）
  // note 用于边界检查：判断指针是否指向堆内对象，计算对象所属区域等
  _heap(_g1h->reserved_region()),  // 返回MemRegion(start, end)
  
  // 第358行
  // forcus 根区域跟踪器，管理初始标记时需要扫描的survivor区域
  // note 根区域是Young GC后残留的对象，必须在并发标记开始前扫描完成
  _root_regions(),  // 默认构造，实际初始化在构造函数体414行
```

**根区域的特殊性**：

根区域（Root Regions）是初始标记时的存活区域（通常是survivor区域），需要特殊处理：

| 特性 | 说明 | 为什么重要 |
|------|------|-----------|
| **必须先扫描** | 在Young GC前必须完成根区域扫描 | 否则根区域中的对象可能在标记完成前被回收 |
| **只扫描一次** | SATB保证每个对象只需访问一次 | 扫描完成后可以在疏散暂停时安全复制对象 |
| **并发扫描** | 在应用线程运行时扫描 | 减少STW时间 |

---

### 第四组：全局标记栈

```cpp
  // 第360行
  // forcus 全局标记栈（溢出栈），用于处理任务队列溢出的灰色对象
  // note 当任务本地队列满时，将多余的对象批量推入全局栈，支持动态扩展
  _global_mark_stack(),  // 默认构造，实际初始化在构造函数体473行
```

**为什么需要全局标记栈？**

```
标记过程的数据流：

1. 正常情况（任务队列未满）：
   根对象 -> 任务本地队列 -> 标记线程处理 -> 子对象加入队列

2. 溢出情况（任务队列已满）：
   根对象 -> 任务本地队列（满）
           ↓
       全局标记栈（缓冲）-> 后续再取回处理
```

| 设计特点 | 作用 |
|---------|------|
| **批量操作** | 支持整个chunk的推入/弹出，减少同步开销 |
| **动态扩展** | 容量可以从MarkStackSize扩展到MarkStackSizeMax |
| **并发访问** | 使用CAS操作实现无锁并发访问 |

---

### 第五组：线程管理

```cpp
  // 第364-367行
  // forcus 工作线程ID偏移量，避免与其他GC线程ID冲突
  // note 并发标记线程ID = _worker_id_offset + 本地索引，确保全局唯一性
  _worker_id_offset(DirtyCardQueueSet::num_par_ids() + G1ConcRefinementThreads),
  
  // forcus 最大任务数量 = 并行GC线程数
  // note 每个ParallelGC线程对应一个标记任务，任务数在VM生命周期内固定
  _max_num_tasks(ParallelGCThreads),
  
  // note _num_active_tasks在set_non_marking_state()中设置
  // forcus 当前活跃任务数，不同GC阶段使用不同数量的线程
  // 例如：初始标记可能只用1个线程，并发标记用所有ConcGCThreads
```

**线程ID分配策略**：

```
全局线程ID空间分配：

├─ 0 ~ (num_par_ids-1)          : DirtyCardQueue处理线程
├─ num_par_ids ~ (offset-1)     : G1ConcRefinement线程
└─ offset ~ (offset+ParallelGCThreads-1) : 并发标记线程
```

这样设计避免了线程ID冲突，便于统一管理和监控。

---

### 第六组：任务队列系统

```cpp
  // 第369-370行
  // forcus 创建任务队列集合，管理所有标记任务的本地队列
  // note 每个标记线程有独立的任务队列，减少线程间竞争，支持工作窃取负载均衡
  _task_queues(new G1CMTaskQueueSet((int) _max_num_tasks)),
  
  // forcus 并行任务终止器，协调多个标记线程的终止时机
  // note 当所有线程的任务队列和全局栈都为空时，才能确认标记完成
  _terminator(ParallelTaskTerminator((int) _max_num_tasks, _task_queues)),
```

**任务队列架构**：

```
并发标记任务队列层次结构：

G1CMTaskQueueSet (_task_queues)
  ├─ G1CMTaskQueue[0]  ← 线程0的本地队列
  ├─ G1CMTaskQueue[1]  ← 线程1的本地队列
  ├─ ...
  └─ G1CMTaskQueue[N-1]
  
每个队列特点：
- 容量：TASKQUEUE_SIZE (默认1024)
- 支持：push、pop、steal（工作窃取）
- 无锁：使用Age-Tagged指针实现无锁操作
```

**ParallelTaskTerminator的工作原理**：

| 步骤 | 操作 | 目的 |
|-----|------|------|
| 1. 检查本地队列 | 本地队列为空？ | 确认自己没有工作 |
| 2. 尝试窃取 | 从其他线程队列窃取任务 | 负载均衡 |
| 3. 进入终止协议 | 如果窃取失败，标记自己为"终止状态" | 等待其他线程 |
| 4. 确认全局状态 | 所有线程都终止？全局栈为空？ | 确认标记完成 |

---

### 第七组：溢出同步屏障

```cpp
  // 第372-373行
  // forcus 两个溢出同步屏障，处理全局标记栈溢出时的线程同步
  // note 确保所有线程在重新初始化全局数据结构时处于一致状态
  _first_overflow_barrier_sync(),   // 第一屏障：停止操作全局数据
  _second_overflow_barrier_sync(),  // 第二屏障：确认新数据结构初始化完成
```

**溢出处理的两阶段同步协议**：

```
全局标记栈溢出时的同步流程：

线程0                线程1                线程N
  │                   │                    │
  ├─ 检测到溢出 ─────>│                    │
  ├─ 设置_has_overflown = true            │
  │                   │                    │
  ├─ 进入第一屏障 ────┼───> 进入第一屏障 ──┼───> 进入第一屏障
  │   (等待所有线程到达)                   │
  ├─ 所有线程到齐后 ──┴────────────────────┘
  │
  ├─ 任务0重新初始化全局标记栈
  │   - 扩展容量或清理数据
  │   - 重新设置_finger指针
  │
  ├─ 进入第二屏障 ────┬───> 进入第二屏障 ──┬───> 进入第二屏障
  │   (等待所有线程确认新数据)              │
  ├─ 所有线程看到新数据 ────────────────────┘
  │
  └─ 继续标记工作
```

**为什么需要两个屏障？**

| 屏障 | 作用 | 如果没有会怎样 |
|-----|------|---------------|
| 第一屏障 | 确保所有线程停止访问旧的全局数据 | 数据竞争：任务0重新初始化时其他线程可能还在读写 |
| 第二屏障 | 确保所有线程看到新的全局数据后再继续 | 内存可见性问题：某些线程可能看到未完全初始化的数据 |

---

### 第八组：状态标志

```cpp
  // 第375-378行
  // forcus 标记栈溢出标志，volatile确保多线程可见性
  // note 任何线程检测到溢出时设置，所有线程读取后进入溢出处理流程
  _has_overflown(false),
  
  // forcus 并发标记阶段标志，区分并发标记和最终标记(remark)
  // note true=与应用线程并发，false=STW的remark阶段
  _concurrent(false),
  
  // forcus 标记中止标志，Full GC等原因导致标记周期中止
  // note 设置后所有标记线程立即停止工作，清理资源
  _has_aborted(false),
  
  // forcus 溢出重启标志，remark阶段溢出时设置
  // note 指示需要重新开始一个完整的并发标记周期
  _restart_for_overflow(false),
```

**状态标志的并发语义**：

所有状态标志都使用`volatile`修饰，确保：
1. **可见性**：一个线程的修改立即对其他线程可见
2. **有序性**：防止指令重排序导致的状态不一致
3. **轻量级同步**：避免使用重量级锁的开销

---

### 第九组：监控和统计

```cpp
  // 第379-380行
  // forcus GC计时器，记录并发标记各阶段的时间戳
  // note 支持JFR、GC日志等监控工具，用于性能分析和调优
  _gc_timer_cm(new (ResourceObj::C_HEAP, mtGC) ConcurrentGCTimer()),
  
  // forcus GC追踪器，记录并发标记的详细事件
  // note 生成JFR事件、GC日志详情，支持问题诊断
  _gc_tracer_cm(new (ResourceObj::C_HEAP, mtGC) G1OldTracer()),
```

**监控数据的用途**：

| 工具 | 使用的数据 | 典型用途 |
|-----|-----------|---------|
| **JFR (Java Flight Recorder)** | GCTimer + GCTracer事件 | 低开销的生产环境性能分析 |
| **GC日志** | 时间统计(_init_times等) | 离线分析GC行为 |
| **JMX** | 累计时间、线程数等统计 | 实时监控 |
| **诊断工具** | 详细事件序列 | 问题根因分析 |

---

### 第十组：时间统计

```cpp
  // 第384-389行
  // forcus 各阶段时间统计序列，NumberSeq支持统计平均值、标准差等
  _init_times(),              // 初始标记时间
  _remark_times(),            // 最终标记时间
  _remark_mark_times(),       // remark阶段的标记时间
  _remark_weak_ref_times(),   // remark阶段的弱引用处理时间
  _cleanup_times(),           // 清理阶段时间
  _total_cleanup_time(0.0),   // 累计清理时间
```

**时间统计的用途**：

```
GC日志输出示例：
[GC concurrent-mark-start]
[GC concurrent-mark-end, 0.1234s]  ← 使用这些统计数据
[GC remark, 0.0056s]
```

---

### 第十一组：任务管理数组

```cpp
  // 第391行
  // forcus 任务虚拟时间累计数组，每个任务一个元素
  // note 用于统计每个标记线程的累计执行时间，支持负载均衡分析
  _accum_task_vtime(NULL),  // 在构造函数体478行分配
```

---

### 第十二组：并发工作线程池

```cpp
  // 第393-395行
  // forcus 并发工作线程池指针，实际执行并发标记工作
  // note 与ParallelGC工作线程不同，专门用于并发标记阶段
  _concurrent_workers(NULL),  // 在构造函数体436行创建
  
  // forcus 当前并发工作线程数，可以动态调整
  _num_concurrent_workers(0),  // 在构造函数体433行设置
  
  // forcus 最大并发工作线程数
  _max_concurrent_workers(0),  // 在构造函数体434行设置
```

**并发工作线程数的计算**：

```cpp
// 构造函数体416-421行的计算逻辑
if (ConcGCThreads == 0) {
    // 自动计算：ParallelGCThreads的25%-50%
    uint marking_thread_num = scale_concurrent_worker_threads(ParallelGCThreads);
    ConcGCThreads = marking_thread_num;
}
```

| ParallelGCThreads | 建议的ConcGCThreads | 比例 |
|-------------------|---------------------|------|
| 2 | 1 | 50% |
| 4 | 1 | 25% |
| 8 | 2 | 25% |
| 16 | 4 | 25% |

---

### 第十三组：区域统计数组

```cpp
  // 第397-398行
  // forcus 区域标记统计数组，每个堆区域对应一个统计对象
  // note 记录每个区域的存活字节数、标记时间等，用于Mixed GC的区域选择
  _region_mark_stats(NEW_C_HEAP_ARRAY(G1RegionMarkStats, _g1h->max_regions(), mtGC)),
  
  // forcus 重建记忆集起始位置数组，记录每个区域开始重建RSet时的top指针
  // note 用于确定哪些对象需要更新记忆集，避免扫描无效数据
  _top_at_rebuild_starts(NEW_C_HEAP_ARRAY(HeapWord*, _g1h->max_regions(), mtGC))
```

**数组大小为何是`max_regions()`？**

```
G1堆的区域数量：
- 区域大小：1MB ~ 32MB (通常4MB)
- 最大堆：例如8GB
- 最大区域数：8GB / 4MB = 2048个

数组内存占用：
- _region_mark_stats: 2048 * sizeof(G1RegionMarkStats)
- _top_at_rebuild_starts: 2048 * sizeof(HeapWord*) = 2048 * 8 = 16KB
```

---

## 三、构造函数体详细分析（第399-495行）

### 阶段1：初始化标记位图（400-401行）

```cpp
  // forcus 使用预留的虚拟内存映射器初始化两个标记位图
  // note 位图大小与堆大小成正比，支持动态提交/取消提交物理内存
  _mark_bitmap_1.initialize(g1h->reserved_region(), prev_bitmap_storage);
  _mark_bitmap_2.initialize(g1h->reserved_region(), next_bitmap_storage);
```

**位图初始化做了什么？**

| 操作 | 说明 |
|-----|------|
| **计算位图大小** | 堆大小 / 对象对齐大小 (8字节) = 需要的位数 |
| **关联虚拟内存** | 将位图数据结构与虚拟内存映射器关联 |
| **清空位图** | 将所有位设置为0（未标记） |
| **设置边界** | 记录位图对应的堆地址范围 |

**示例计算**：
```
8GB堆，8字节对齐：
- 需要的位数：8GB / 8B = 1G bits
- 位图大小：1G bits / 8 = 128MB
```

---

### 阶段2：创建并发标记线程（403-407行）

```cpp
  // forcus 创建并发标记控制线程，协调整个标记周期
  // note 这是一个独立的Java线程，生命周期与VM相同
  _cm_thread = new G1ConcurrentMarkThread(this);
  
  // forcus 检查线程是否成功创建（OS线程是否分配）
  // note 如果失败说明系统资源不足，必须终止VM启动
  if (_cm_thread->osthread() == NULL) {
    vm_shutdown_during_initialization("Could not create ConcurrentMarkThread");
  }
```

**G1ConcurrentMarkThread的职责**：

```
并发标记线程的生命周期：

1. 初始化阶段：
   - 启动后进入睡眠状态
   - 等待标记请求

2. 标记周期：
   ┌─> 等待标记请求
   │   ↓
   │   协调初始标记 (STW)
   │   ↓
   │   启动并发标记线程
   │   ↓
   │   监控标记进度
   │   ↓
   │   协调最终标记 (STW)
   │   ↓
   │   执行清理工作
   └───┘ 回到等待状态

3. VM关闭：
   - 收到终止信号
   - 清理资源后退出
```

---

### 阶段3：初始化SATB队列（409-412行）

```cpp
  // forcus 验证并发GC锁已初始化
  // note CGC_lock用于协调并发标记的启动和停止
  assert(CGC_lock != NULL, "CGC_lock must be initialized");
  
  // forcus 获取SATB标记队列集合，配置缓冲区大小
  // note SATB队列记录并发标记期间的引用更新，确保不丢失灰色对象
  SATBMarkQueueSet& satb_qs = G1BarrierSet::satb_mark_queue_set();
  satb_qs.set_buffer_size(G1SATBBufferSize);
```

**SATB (Snapshot-At-The-Beginning) 机制**：

| 组件 | 作用 | 并发标记的作用 |
|-----|------|---------------|
| **SATB队列** | 记录并发期间的引用更新 | 防止标记遗漏：如果A->B的引用被删除，B加入SATB队列确保被标记 |
| **写屏障** | 拦截引用字段的写操作 | 在引用删除前将旧值记录到SATB队列 |
| **缓冲区** | 批量处理SATB记录 | 减少同步开销，提高吞吐量 |

---

### 阶段4：初始化根区域跟踪器（414行）

```cpp
  // forcus 初始化根区域跟踪器，关联survivor区域和当前并发标记对象
  // note 根区域包含Young GC后的存活对象，必须在下次Young GC前完成扫描
  _root_regions.init(_g1h->survivor(), this);
```

**根区域扫描的重要性**：

```
时间线示例：

T0: Initial Mark (STW)
    - 标记GC Roots
    - Survivor区域成为根区域
    
T1: Root Region Scan (并发)
    - 扫描所有根区域
    - 标记可达对象
    
T2: Young GC可以进行 ✓
    - 根区域扫描完成
    - 可以安全复制对象
    
如果在T1之前进行Young GC：
    - 根区域中的对象可能被复制
    - 标记信息丢失 ✗
```

---

### 阶段5：计算并发GC线程数（416-434行）

```cpp
  // forcus 如果用户未设置ConcGCThreads，自动计算合理的线程数
  // note 并发标记线程过多会影响应用吞吐量，过少会延长标记时间
  if (FLAG_IS_DEFAULT(ConcGCThreads) || ConcGCThreads == 0) {
    // 自动计算：通常是ParallelGCThreads的25%-50%
    uint marking_thread_num = scale_concurrent_worker_threads(ParallelGCThreads);
    FLAG_SET_ERGO(uint, ConcGCThreads, marking_thread_num);
  }
  
  // forcus 验证ConcGCThreads的合理性
  // note 并发线程数不应超过并行GC线程数
  assert(ConcGCThreads > 0, "ConcGCThreads have been set.");
  if (ConcGCThreads > ParallelGCThreads) {
    log_warning(gc)("More ConcGCThreads (%u) than ParallelGCThreads (%u).",
                    ConcGCThreads, ParallelGCThreads);
    return;  // 初始化失败，_completed_initialization保持false
  }
  
  log_debug(gc)("ConcGCThreads: %u offset %u", ConcGCThreads, _worker_id_offset);
  log_debug(gc)("ParallelGCThreads: %u", ParallelGCThreads);
  
  // forcus 设置并发工作线程数
  _num_concurrent_workers = ConcGCThreads;
  _max_concurrent_workers = _num_concurrent_workers;
```

**线程数配置的权衡**：

| 线程数 | 优点 | 缺点 | 适用场景 |
|--------|------|------|---------|
| **少**（1-2） | 应用吞吐量高，CPU占用低 | 标记周期长，可能触发Full GC | CPU核心数少，延迟要求不高 |
| **中**（ParallelGCThreads的25%-50%） | 平衡吞吐量和暂停时间 | - | 大多数生产环境（推荐） |
| **多**（接近ParallelGCThreads） | 标记快，减少Full GC风险 | 应用吞吐量下降明显 | 延迟敏感，对象分配速率极高 |

---

### 阶段6：创建并发工作线程池（436-437行）

```cpp
  // forcus 创建并发工作线程池，名称为"G1 Conc"
  // note false=非动态线程池，true=是守护线程
  _concurrent_workers = new WorkGang("G1 Conc", _max_concurrent_workers, false, true);
  
  // forcus 初始化工作线程，创建OS线程并启动
  _concurrent_workers->initialize_workers();
```

**WorkGang参数说明**：

| 参数 | 值 | 含义 |
|-----|-----|------|
| name | `"G1 Conc"` | 线程名前缀，便于监控工具识别 |
| workers | `_max_concurrent_workers` | 线程池大小 |
| are_GC_task_threads | `false` | 不是传统的GC任务线程 |
| are_ConcurrentGC_threads | `true` | 是并发GC线程，设置合适的优先级 |

---

### 阶段7：计算和初始化标记栈大小（439-475行）

```cpp
  // forcus 如果用户未设置MarkStackSize，自动计算合理的大小
  if (FLAG_IS_DEFAULT(MarkStackSize)) {
    size_t mark_stack_size =
      MIN2(MarkStackSizeMax,
          MAX2(MarkStackSize, (size_t) (_max_concurrent_workers * TASKQUEUE_SIZE)));
    
    // forcus 验证计算出的值在有效范围内
    if (!(mark_stack_size >= 1 && mark_stack_size <= MarkStackSizeMax)) {
      log_warning(gc)("Invalid value calculated for MarkStackSize...");
      return;  // 初始化失败
    }
    FLAG_SET_ERGO(size_t, MarkStackSize, mark_stack_size);
  } else {
    // forcus 用户手动设置了MarkStackSize，验证其合理性
    // ... 验证代码 ...
  }
  
  // forcus 使用计算出的大小初始化全局标记栈
  // note 初始容量为MarkStackSize，最大可扩展到MarkStackSizeMax
  if (!_global_mark_stack.initialize(MarkStackSize, MarkStackSizeMax)) {
    vm_exit_during_initialization("Failed to allocate initial concurrent mark overflow mark stack.");
  }
```

**MarkStackSize的计算逻辑**：

```
默认计算公式：
MarkStackSize = max(MarkStackSize, ConcGCThreads * TASKQUEUE_SIZE)
                但不超过MarkStackSizeMax

示例：
- ConcGCThreads = 4
- TASKQUEUE_SIZE = 1024
- 计算结果 = 4 * 1024 = 4096个entry
- 内存占用 = 4096 * sizeof(oop*) = 32KB (64位系统)
```

**为什么需要最小值保证？**

- 每个任务队列容量为1024
- 如果全局栈小于所有任务队列的总和
- 溢出处理时可能再次溢出，导致标记不完整

---

### 阶段8：创建标记任务数组（477-491行）

```cpp
  // forcus 为每个并行GC线程分配标记任务对象
  _tasks = NEW_C_HEAP_ARRAY(G1CMTask*, _max_num_tasks, mtGC);
  _accum_task_vtime = NEW_C_HEAP_ARRAY(double, _max_num_tasks, mtGC);
  
  // forcus 临时设置活跃任务数，避免任务队列的断言失败
  // note 实际的活跃任务数在每个GC阶段动态设置
  _num_active_tasks = _max_num_tasks;
  
  // forcus 为每个任务创建独立的队列和任务对象
  for (uint i = 0; i < _max_num_tasks; ++i) {
    // 创建任务本地队列
    G1CMTaskQueue* task_queue = new G1CMTaskQueue();
    task_queue->initialize();
    _task_queues->register_queue(i, task_queue);
    
    // forcus 创建G1CMTask对象，关联队列、统计数组等
    // note 每个任务对象包含标记逻辑、本地缓存、统计信息等
    _tasks[i] = new G1CMTask(i, this, task_queue, _region_mark_stats, _g1h->max_regions());
    
    // 初始化虚拟时间累计为0
    _accum_task_vtime[i] = 0.0;
  }
```

**为什么是`_max_num_tasks`而不是`_max_concurrent_workers`？**

| 数量 | 用途 | 为什么 |
|-----|------|--------|
| `_max_num_tasks` = ParallelGCThreads | STW阶段（初始标记、最终标记） | STW阶段可以使用所有并行GC线程 |
| `_max_concurrent_workers` = ConcGCThreads | 并发标记阶段 | 并发阶段限制线程数避免影响应用 |

**任务对象的内部结构**：

```
G1CMTask主要成员：
├─ _task_id: 任务唯一标识
├─ _cm: 指向G1ConcurrentMark
├─ _task_queue: 本地任务队列（容量1024）
├─ _region_mark_stats: 区域统计数组引用
├─ _curr_region: 当前正在标记的区域
├─ _finger: 任务本地finger指针
├─ _mark_stats_cache: 标记统计缓存（减少数组访问）
└─ _aborted: 任务中止标志
```

---

### 阶段9：重置到标记完成状态（493-494行）

```cpp
  // forcus 将并发标记系统重置到标记完成状态
  // note 清空所有标记数据结构，准备下一次标记周期
  reset_at_marking_complete();
  
  // forcus 标记初始化成功完成
  // note 如果前面任何步骤失败，这个标志保持false，外部检查会终止VM
  _completed_initialization = true;
}
```

**reset_at_marking_complete() 做了什么？**

| 操作 | 说明 |
|-----|------|
| `reset_marking_for_restart()` | 清空所有标记状态，重置finger指针 |
| `_num_active_tasks = 0` | 设置活跃任务数为0 |
| 清空任务队列 | 所有任务本地队列和全局栈清空 |
| 重置统计数据 | 清空区域标记统计、时间统计等 |

---

## 四、总体流程图

```
┌──────────────────────────────────────────────────────────────────┐
│                    G1ConcurrentMark 构造函数流程                  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
    ┌─────────────────────────────────────────────┐
    │        初始化列表 (347-398行)                │
    ├─────────────────────────────────────────────┤
    │ • 保存G1堆指针 (_g1h)                       │
    │ • 创建双缓冲位图对象                         │
    │ • 设置位图指针 (prev/next)                  │
    │ • 初始化根区域跟踪器                         │
    │ • 创建任务队列集合                          │
    │ • 创建并行终止器                            │
    │ • 初始化状态标志                            │
    │ • 创建监控对象 (timer/tracer)               │
    │ • 分配统计数组                              │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │      构造函数体第一部分：基础组件 (400-414)  │
    ├─────────────────────────────────────────────┤
    │ 1. 初始化标记位图                           │
    │    └─> 关联虚拟内存映射器                   │
    │                                             │
    │ 2. 创建并发标记线程                         │
    │    ├─> new G1ConcurrentMarkThread()        │
    │    └─> 检查OS线程是否成功创建               │
    │                                             │
    │ 3. 配置SATB队列                             │
    │    └─> 设置缓冲区大小                       │
    │                                             │
    │ 4. 初始化根区域跟踪器                       │
    │    └─> 关联survivor区域                    │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │    构造函数体第二部分：并发线程池 (416-437)  │
    ├─────────────────────────────────────────────┤
    │ 1. 计算并发GC线程数                         │
    │    ├─> 自动计算或使用用户配置               │
    │    └─> 验证合理性                          │
    │                                             │
    │ 2. 创建并发工作线程池                       │
    │    ├─> new WorkGang("G1 Conc", ...)       │
    │    └─> initialize_workers()               │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │    构造函数体第三部分：标记栈 (439-475)      │
    ├─────────────────────────────────────────────┤
    │ 1. 计算MarkStackSize                        │
    │    ├─> 自动计算: workers * TASKQUEUE_SIZE │
    │    ├─> 或使用用户配置                       │
    │    └─> 验证范围                            │
    │                                             │
    │ 2. 初始化全局标记栈                         │
    │    ├─> _global_mark_stack.initialize()    │
    │    └─> 失败则终止VM启动                    │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │    构造函数体第四部分：任务创建 (477-491)    │
    ├─────────────────────────────────────────────┤
    │ for (i = 0; i < _max_num_tasks; i++) {    │
    │                                             │
    │   1. 创建任务本地队列                       │
    │      └─> new G1CMTaskQueue()              │
    │                                             │
    │   2. 注册到任务队列集合                     │
    │      └─> _task_queues->register_queue()   │
    │                                             │
    │   3. 创建G1CMTask对象                       │
    │      └─> new G1CMTask(i, this, queue, ...) │
    │                                             │
    │   4. 初始化虚拟时间累计                     │
    │      └─> _accum_task_vtime[i] = 0.0       │
    │ }                                           │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
    ┌─────────────────────────────────────────────┐
    │       构造函数体第五部分：完成 (493-494)     │
    ├─────────────────────────────────────────────┤
    │ 1. 重置到标记完成状态                       │
    │    └─> reset_at_marking_complete()        │
    │                                             │
    │ 2. 标记初始化成功                           │
    │    └─> _completed_initialization = true   │
    └─────────────┬───────────────────────────────┘
                  │
                  ▼
            ┌──────────┐
            │ 返回调用点 │
            └──────────┘
```

---

## 五、关键设计决策总结

### 1. **为什么需要双缓冲位图？**

| 原因 | 说明 |
|-----|------|
| **快速切换** | 指针交换O(1)，避免复制128MB+的位图数据 |
| **增量GC支持** | prev保存历史结果，用于Mixed GC的区域选择 |
| **并发安全** | next是工作区，prev是稳定快照，避免竞争 |

### 2. **为什么任务数是ParallelGCThreads而非ConcGCThreads？**

| 阶段 | 使用的线程数 | 原因 |
|-----|-------------|------|
| **初始标记(STW)** | ParallelGCThreads | STW可以使用所有线程，加速标记 |
| **并发标记** | ConcGCThreads | 限制并发线程避免影响应用 |
| **最终标记(STW)** | ParallelGCThreads | 同初始标记 |

预先创建`_max_num_tasks`个任务对象，不同阶段激活不同数量。

### 3. **为什么需要全局标记栈？**

| 场景 | 处理方式 |
|-----|---------|
| **任务队列未满** | 灰色对象直接加入本地队列 |
| **任务队列已满** | 批量推入全局标记栈 |
| **队列重新有空间** | 从全局栈取回继续处理 |

全局栈支持动态扩展，避免标记遗漏。

### 4. **为什么需要两个溢出屏障？**

```
目的：确保所有线程在一致的状态下重新初始化

第一屏障：停止所有线程的并发操作
第二屏障：确认所有线程看到新的数据结构

如果只有一个屏障：
- 任务0重新初始化时，其他线程可能还在访问 → 数据竞争
- 或者重新初始化完成后，某些线程看不到新数据 → 内存可见性问题
```

### 5. **为什么延迟初始化？**

```cpp
// (Must do this late, so that "max_regions" is defined.)
```

依赖关系链：
```
HeapRegionManager初始化
  └─> 确定max_regions
      └─> 分配_region_mark_stats[max_regions]
      └─> 分配_top_at_rebuild_starts[max_regions]
      └─> 创建G1CMTask需要max_regions参数
```

---

## 六、内存布局总结

```
G1ConcurrentMark内存占用估算（8GB堆，2048个区域）：

1. 标记位图：
   - _mark_bitmap_1: 128MB (8GB / 8字节 / 8位)
   - _mark_bitmap_2: 128MB
   - 小计：256MB

2. 任务队列系统：
   - G1CMTaskQueue * 8: 8KB * 8 = 64KB
   - G1CMTask * 8: ~1KB * 8 = 8KB
   - 小计：72KB

3. 统计数组：
   - _region_mark_stats: 2048 * sizeof(G1RegionMarkStats)
   - _top_at_rebuild_starts: 2048 * 8 = 16KB
   - 小计：~20KB

4. 全局标记栈：
   - 初始容量：4KB ~ 1MB (可配置)

总计：约 256MB + 少量KB的管理开销
```

---

## 七、并发标记的完整生命周期

```
1. 初始化（构造函数）
   └─> 分配所有数据结构，创建线程

2. 等待触发
   └─> _cm_thread在睡眠状态

3. 初始标记 (STW)
   ├─> 标记GC Roots
   ├─> Survivor区域成为根区域
   └─> 启动并发标记

4. 根区域扫描 (并发)
   └─> 扫描所有根区域

5. 并发标记 (并发)
   ├─> ConcGCThreads个线程并行标记
   ├─> 使用任务队列 + 全局栈
   └─> SATB队列记录并发更新

6. 最终标记 (STW)
   ├─> 处理SATB队列
   ├─> 完成剩余标记
   └─> 交换位图 (swap_mark_bitmaps)

7. 清理 (STW/并发)
   ├─> 统计区域存活率
   ├─> 回收空区域
   └─> 准备Mixed GC

8. 重置
   └─> reset_at_marking_complete()
   └─> 回到等待状态
```

---

## 八、常见问题

### Q1: 为什么构造函数这么复杂？
**A:** 并发标记涉及多线程、复杂数据结构、精细的同步机制，初始化需要确保所有组件正确配置，任何错误都可能导致GC不正确或VM崩溃。

### Q2: 如果初始化失败会怎样？
**A:** `_completed_initialization`保持`false`，调用方检测到后会调用`vm_shutdown_during_initialization()`终止VM启动，避免带着不完整的GC系统运行。

### Q3: 为什么不用更简单的标记算法？
**A:** G1的目标是可预测的暂停时间，简单的标记算法（如单线程标记）会导致长时间STW。并发标记虽然复杂，但能将大部分标记工作移到并发阶段，显著减少暂停时间。

### Q4: 位图为什么不直接用malloc分配？
**A:** 使用`G1RegionToSpaceMapper`的好处：
- 支持虚拟内存预留，实际使用时才提交物理内存
- 堆动态缩小时可以取消提交内存
- 与堆的虚拟内存管理统一

### Q5: 并发标记线程数如何选择？
**A:** 经验法则：
- CPU核心 ≤ 4：ConcGCThreads = 1
- CPU核心 > 4：ConcGCThreads = ParallelGCThreads / 4 ~ ParallelGCThreads / 2
- 根据应用特性调优：对象分配快则增加，延迟敏感则减少

---

## 总结

`G1ConcurrentMark`构造函数是G1垃圾收集器并发标记系统的初始化入口，它完成了：

1. **数据结构创建**：双缓冲位图、任务队列、全局标记栈等
2. **线程创建**：并发标记控制线程、工作线程池
3. **参数配置**：自动计算或验证用户配置的线程数、栈大小等
4. **资源分配**：统计数组、监控对象等
5. **状态初始化**：重置所有标记状态，准备第一次标记周期

这个设计体现了G1 GC的核心理念：**通过精细的并发控制和数据结构优化，在保证正确性的前提下，最大化吞吐量并最小化暂停时间**。
