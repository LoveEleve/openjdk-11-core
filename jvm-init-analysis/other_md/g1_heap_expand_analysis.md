# G1堆初始化时的内存扩展详细分析

## 一、代码上下文

### 源代码位置

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/g1CollectedHeap.cpp:1887-1891

// forcus 将堆扩展到初始堆大小
// note 这是堆初始化的最后关键步骤，确保有足够的可用内存供应用启动
// Now expand into the initial heap size.
if (!expand(init_byte_size, _workers)) {
    // forcus 如果扩展失败，VM无法继续启动，必须终止
    // note 返回JNI_ENOMEM错误码，表示内存不足
    vm_shutdown_during_initialization("Failed to allocate initial heap.");
    return JNI_ENOMEM;
}
```

### 调用时机

这段代码位于 `G1CollectedHeap::initialize()` 方法中，是堆初始化流程的关键步骤：

```
G1CollectedHeap初始化流程：
├─ 1. 预留虚拟地址空间 (ReservedSpace)
├─ 2. 创建HeapRegionManager
├─ 3. 创建各种Mapper (heap/bitmap/bot/cardtable)
├─ 4. 初始化并发标记 (G1ConcurrentMark)
└─ 5. 扩展到初始堆大小 (expand) ← 我们分析的代码
      ├─ 提交物理内存
      ├─ 创建HeapRegion对象
      └─ 加入空闲列表
```

---

## 二、核心概念理解

### 1. 虚拟内存 vs 物理内存

在G1堆初始化过程中，涉及两个重要的内存概念：

| 阶段 | 操作 | 内存状态 | 系统调用 |
|-----|------|---------|---------|
| **预留 (Reserve)** | 分配虚拟地址空间 | 虚拟地址已分配，无物理内存 | `mmap(PROT_NONE)` |
| **提交 (Commit)** | 映射物理内存 | 虚拟地址→物理内存映射 | `mmap(PROT_READ\|WRITE)` |

**为什么分两步？**

```
假设配置：-Xms2g -Xmx8g

初始化时：
┌─────────────────────────────────────────┐
│  预留8GB虚拟地址空间                     │  ← Reserve (便宜，不占物理内存)
│  ┌─────────────────┐                    │
│  │ 提交2GB物理内存  │  未提交6GB        │  ← Commit (实际占用物理内存)
│  └─────────────────┘                    │
└─────────────────────────────────────────┘

优势：
1. 堆可以动态增长（最大8GB）
2. 初始时只占用2GB物理内存
3. 节省系统内存资源
```

### 2. init_byte_size 是什么？

```cpp
// 计算初始堆大小
size_t init_byte_size = collector_policy()->initial_heap_byte_size();

// 典型值示例：
// -Xms2g    → init_byte_size = 2GB
// -Xms512m  → init_byte_size = 512MB
// 默认      → init_byte_size = 物理内存的1/64
```

---

## 三、expand() 方法详细分析

### 方法签名

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/g1CollectedHeap.cpp:1337

bool G1CollectedHeap::expand(
    size_t expand_bytes,        // forcus 期望扩展的字节数（初始化时为init_byte_size）
    WorkGang* pretouch_workers, // forcus 预触摸工作线程池（可选，用于预热内存页）
    double* expand_time_ms      // forcus 输出参数：扩展耗时（毫秒）
)
```

### 完整带注释的代码

```cpp
bool G1CollectedHeap::expand(size_t expand_bytes, WorkGang* pretouch_workers, double* expand_time_ms) {
  // ============================================
  // 阶段1：内存对齐
  // ============================================
  
  // forcus 第一次对齐：按页大小对齐（通常4KB或2MB大页）
  // note 操作系统以页为单位管理内存，必须按页大小对齐
  size_t aligned_expand_bytes = ReservedSpace::page_align_size_up(expand_bytes);
  
  // forcus 第二次对齐：按Region大小对齐（通常1-32MB，默认4MB）
  // note G1以Region为单位管理堆，必须是Region大小的整数倍
  aligned_expand_bytes = align_up(aligned_expand_bytes, HeapRegion::GrainBytes);

  // forcus 记录日志：请求的扩展大小和实际对齐后的大小
  log_debug(gc, ergo, heap)("Expand the heap. requested expansion amount: " SIZE_FORMAT "B expansion amount: " SIZE_FORMAT "B",
                            expand_bytes, aligned_expand_bytes);

  // ============================================
  // 阶段2：检查是否可以扩展
  // ============================================
  
  // forcus 检查堆是否已经达到最大值
  // note 如果已经扩展到最大堆大小（MaxHeapSize），不能继续扩展
  if (is_maximal_no_gc()) {
    log_debug(gc, ergo, heap)("Did not expand the heap (heap already fully expanded)");
    return false;
  }

  // ============================================
  // 阶段3：计算需要扩展的Region数量
  // ============================================
  
  // forcus 记录扩展开始时间
  double expand_heap_start_time_sec = os::elapsedTime();
  
  // forcus 计算需要扩展多少个Region
  // note 例如：aligned_expand_bytes=8GB, GrainBytes=4MB → regions_to_expand=2048
  uint regions_to_expand = (uint)(aligned_expand_bytes / HeapRegion::GrainBytes);
  assert(regions_to_expand > 0, "Must expand by at least one region");

  // ============================================
  // 阶段4：实际执行扩展（核心操作）
  // ============================================
  
  // forcus 调用HeapRegionManager执行实际的扩展操作
  // note 返回实际扩展的Region数量（可能小于请求的数量）
  uint expanded_by = _hrm.expand_by(regions_to_expand, pretouch_workers);
  
  // forcus 如果调用者关心扩展耗时，计算并返回
  if (expand_time_ms != NULL) {
    *expand_time_ms = (os::elapsedTime() - expand_heap_start_time_sec) * MILLIUNITS;
  }

  // ============================================
  // 阶段5：处理扩展结果
  // ============================================
  
  if (expanded_by > 0) {
    // forcus 扩展成功：计算实际扩展的字节数
    size_t actual_expand_bytes = expanded_by * HeapRegion::GrainBytes;
    assert(actual_expand_bytes <= aligned_expand_bytes, "post-condition");
    
    // forcus 通知GC策略：堆大小已改变
    // note G1Policy需要更新统计信息，调整GC触发阈值等
    g1_policy()->record_new_heap_size(num_regions());
  } else {
    // forcus 扩展失败：记录日志
    log_debug(gc, ergo, heap)("Did not expand the heap (heap expansion operation failed)");

    // forcus 检查是否因为交换空间不足导致失败
    // note 如果虚拟地址空间足够但提交失败，可能是物理内存/交换空间不足
    if (G1ExitOnExpansionFailure &&
        _hrm.available() >= regions_to_expand) {
      // forcus 有足够的预留空间但提交失败，说明系统资源不足
      // note 如果设置了G1ExitOnExpansionFailure（默认false），终止VM
      vm_exit_out_of_memory(aligned_expand_bytes, OOM_MMAP_ERROR, "G1 heap expansion");
    }
  }
  
  // forcus 返回是否成功扩展了至少一个Region
  // note 对于初始化时的调用，如果返回false，VM将无法启动
  return expanded_by > 0;
}
```

### 内存对齐示例

```
假设：expand_bytes = 2097152000 (约2GB)
      HeapRegion::GrainBytes = 4194304 (4MB)

步骤1：页对齐（假设4KB页）
  2097152000 → 2097152000 (已经是4KB的倍数)

步骤2：Region对齐
  2097152000 / 4194304 = 499.99... ≈ 500个Region
  aligned_expand_bytes = 500 * 4194304 = 2097152000字节

步骤3：计算Region数
  regions_to_expand = 2097152000 / 4194304 = 500
```

---

## 四、HeapRegionManager::expand_by() 详细分析

### 调用链

```
G1CollectedHeap::expand()
    └─> HeapRegionManager::expand_by()
            └─> HeapRegionManager::expand_at()
                    └─> HeapRegionManager::make_regions_available()
                            ├─> HeapRegionManager::commit_regions()
                            │       ├─> 提交堆内存 (_heap_mapper)
                            │       ├─> 提交位图内存 (_prev/next_bitmap_mapper)
                            │       ├─> 提交BOT (_bot_mapper)
                            │       ├─> 提交卡表 (_cardtable_mapper)
                            │       └─> 提交卡计数 (_card_counts_mapper)
                            │
                            ├─> new_heap_region() - 创建HeapRegion对象
                            ├─> 设置available位图
                            └─> insert_into_free_list() - 加入空闲列表
```

### expand_by() 方法

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/heapRegionManager.cpp:174-176

uint HeapRegionManager::expand_by(uint num_regions, WorkGang* pretouch_workers) {
  // forcus 从索引0开始扩展指定数量的Region
  // note 初始化时堆为空，从第一个Region开始分配
  return expand_at(0, num_regions, pretouch_workers);
}
```

### expand_at() 方法

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/heapRegionManager.cpp:178-199

uint HeapRegionManager::expand_at(uint start, uint num_regions, WorkGang* pretouch_workers) {
  // forcus 边界检查：请求的Region数量必须大于0
  if (num_regions == 0) {
    return 0;
  }

  // forcus 初始化查找变量
  uint cur = start;           // 当前搜索位置
  uint idx_last_found = 0;    // 最后找到的不可用Region的起始索引
  uint num_last_found = 0;    // 最后找到的连续不可用Region数量

  uint expanded = 0;  // forcus 已成功扩展的Region数量

  // forcus 循环：查找并扩展不可用的Region
  // note 继续扩展直到达到请求数量或没有更多可用空间
  while (expanded < num_regions &&
         (num_last_found = find_unavailable_from_idx(cur, &idx_last_found)) > 0) {
    
    // forcus 计算本次要扩展的Region数
    // note 取请求剩余数量和找到的连续Region数量的较小值
    uint to_expand = MIN2(num_regions - expanded, num_last_found);
    
    // forcus 使这些Region可用：提交内存并初始化
    make_regions_available(idx_last_found, to_expand, pretouch_workers);
    
    // forcus 更新已扩展计数
    expanded += to_expand;
    
    // forcus 更新搜索位置：跳过已处理的Region
    cur = idx_last_found + num_last_found + 1;
  }

  // forcus 可选的验证：检查数据结构一致性
  verify_optional();
  
  // forcus 返回实际扩展的Region数量
  return expanded;
}
```

**查找逻辑图示**：

```
Region数组状态（A=Available可用，U=Unavailable不可用）：

初始状态（空堆）：
[U U U U U U U U U U U U ...] ← 所有Region都未提交

查找过程（请求扩展500个Region）：
第1次查找：从索引0开始
  └─> 找到0-499: 500个连续不可用Region
  └─> 扩展0-499: make_regions_available(0, 500)
  └─> expanded = 500, cur = 500

结果：
[A A A A A A ... (500个) U U U ...] ← 前500个Region已提交
```

### make_regions_available() 方法

这是实际执行内存提交和Region初始化的核心方法：

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/heapRegionManager.cpp:128-154

void HeapRegionManager::make_regions_available(uint start, uint num_regions, WorkGang* pretouch_gang) {
  // forcus 断言：必须至少扩展一个Region
  guarantee(num_regions > 0, "No point in calling this for zero regions");
  
  // ============================================
  // 步骤1：提交所有相关的内存
  // ============================================
  
  // forcus 提交物理内存到预留的虚拟地址空间
  // note 这是最耗时的操作，涉及系统调用
  commit_regions(start, num_regions, pretouch_gang);
  
  // ============================================
  // 步骤2：创建HeapRegion对象
  // ============================================
  
  for (uint i = start; i < start + num_regions; i++) {
    // forcus 检查该索引位置是否已有HeapRegion对象
    if (_regions.get_by_index(i) == NULL) {
      // forcus 创建新的HeapRegion对象
      // note HeapRegion是Java对象在堆中的容器，管理一个Region的元数据
      HeapRegion* new_hr = new_heap_region(i);
      
      // forcus 内存屏障：确保HeapRegion对象完全初始化后再设置指针
      // note 防止其他线程看到未完全初始化的对象
      OrderAccess::storestore();
      
      // forcus 将HeapRegion对象存储到数组中
      _regions.set_by_index(i, new_hr);
      
      // forcus 更新已分配的HeapRegion数组长度
      _allocated_heapregions_length = MAX2(_allocated_heapregions_length, i + 1);
    }
  }

  // ============================================
  // 步骤3：标记这些Region为可用
  // ============================================
  
  // forcus 在available位图中设置这些Region为可用
  // note 位图用于快速查询Region是否可用，避免遍历
  _available_map.par_set_range(start, start + num_regions, BitMap::unknown_range);

  // ============================================
  // 步骤4：初始化HeapRegion并加入空闲列表
  // ============================================
  
  for (uint i = start; i < start + num_regions; i++) {
    // forcus 断言：确认Region确实标记为可用
    assert(is_available(i), "Just made region %u available but is apparently not.", i);
    HeapRegion* hr = at(i);
    
    // forcus 如果启用了Region打印器，记录提交事件
    if (G1CollectedHeap::heap()->hr_printer()->is_active()) {
      G1CollectedHeap::heap()->hr_printer()->commit(hr);
    }
    
    // forcus 计算Region对应的堆地址范围
    HeapWord* bottom = G1CollectedHeap::heap()->bottom_addr_for_region(i);
    MemRegion mr(bottom, bottom + HeapRegion::GrainWords);

    // forcus 初始化HeapRegion对象
    // note 设置边界、类型（Eden/Survivor/Old）等元数据
    hr->initialize(mr);
    
    // forcus 将新Region插入到空闲列表
    // note 空闲列表用于分配新对象时快速查找可用Region
    insert_into_free_list(at(i));
  }
}
```

---

## 五、commit_regions() - 物理内存提交

这是整个扩展过程中最关键的步骤，涉及实际的系统调用。

### commit_regions() 方法

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/heapRegionManager.cpp:83-99

void HeapRegionManager::commit_regions(uint index, size_t num_regions, WorkGang* pretouch_gang) {
  // forcus 断言：至少提交一个Region
  guarantee(num_regions > 0, "Must commit more than zero regions");
  
  // forcus 断言：不能超过最大Region数量
  guarantee(_num_committed + num_regions <= max_length(), 
            "Cannot commit more than the maximum amount of regions");

  // forcus 更新已提交Region计数
  _num_committed += (uint)num_regions;

  // ============================================
  // 提交堆内存和所有辅助数据结构
  // ============================================
  
  // forcus 1. 提交实际的堆内存（用于存储Java对象）
  // note 这是主要的内存占用，例如500个4MB的Region = 2GB
  _heap_mapper->commit_regions(index, num_regions, pretouch_gang);

  // forcus 2. 提交标记位图内存（两个位图）
  // note 用于并发标记，记录对象的存活状态
  _prev_bitmap_mapper->commit_regions(index, num_regions, pretouch_gang);
  _next_bitmap_mapper->commit_regions(index, num_regions, pretouch_gang);

  // forcus 3. 提交BOT (Block Offset Table) 内存
  // note 用于快速定位对象起始位置，支持指针扫描
  _bot_mapper->commit_regions(index, num_regions, pretouch_gang);
  
  // forcus 4. 提交Card Table内存
  // note 用于记录跨Region引用，支持增量式GC
  _cardtable_mapper->commit_regions(index, num_regions, pretouch_gang);

  // forcus 5. 提交Card Counts内存
  // note 用于记录卡表更新频率，优化记忆集维护
  _card_counts_mapper->commit_regions(index, num_regions, pretouch_gang);
}
```

### 内存占用计算

以初始堆2GB（500个4MB Region）为例：

| 数据结构 | 大小计算 | 实际占用 |
|---------|---------|---------|
| **堆内存** | 500 * 4MB | 2GB |
| **prev_bitmap** | 2GB / 8字节 / 8位 | 32MB |
| **next_bitmap** | 2GB / 8字节 / 8位 | 32MB |
| **BOT** | 500 * (4MB/512字节) | 4MB |
| **Card Table** | 2GB / 512字节 | 4MB |
| **Card Counts** | 2GB / 512字节 | 4MB |
| **HeapRegion对象** | 500 * ~200字节 | ~100KB |
| **总计** | - | **约2.08GB** |

---

## 六、G1RegionToSpaceMapper::commit_regions()

这是实际执行操作系统内存提交的底层方法。

### 实现（Region大于等于Page的情况）

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/g1RegionToSpaceMapper.cpp:75-87

virtual void commit_regions(uint start_idx, size_t num_regions, WorkGang* pretouch_gang) {
  // ============================================
  // 步骤1：计算起始页号
  // ============================================
  
  // forcus 计算Region对应的起始页号
  // note 例如：Region索引10，每个Region 8个页 → 起始页号 80
  size_t const start_page = (size_t)start_idx * _pages_per_region;
  
  // ============================================
  // 步骤2：调用底层存储提交内存
  // ============================================
  
  // forcus 调用G1PageBasedVirtualSpace::commit()提交物理内存
  // note 这里会调用os::commit_memory()系统调用（Linux上是mmap）
  // 返回值：内存是否已清零（zero_filled）
  bool zero_filled = _storage.commit(start_page, num_regions * _pages_per_region);
  
  // ============================================
  // 步骤3：可选的内存预触摸（Pretouch）
  // ============================================
  
  // forcus 如果启用了AlwaysPreTouch，预先访问所有内存页
  // note 目的：强制操作系统立即分配物理页，避免首次访问时的缺页中断
  if (AlwaysPreTouch) {
    _storage.pretouch(start_page, num_regions * _pages_per_region, pretouch_gang);
  }
  
  // ============================================
  // 步骤4：更新提交位图
  // ============================================
  
  // forcus 在提交位图中标记这些Region为已提交
  // note 用于快速查询Region的提交状态
  _commit_map.set_range(start_idx, start_idx + num_regions);
  
  // ============================================
  // 步骤5：触发监听器回调
  // ============================================
  
  // forcus 通知所有注册的监听器：这些Region已提交
  // note 例如：更新JMX统计信息、记录日志等
  fire_on_commit(start_idx, num_regions, zero_filled);
}
```

### G1PageBasedVirtualSpace::commit()

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/g1PageBasedVirtualSpace.cpp:189

bool G1PageBasedVirtualSpace::commit(size_t start_page, size_t size_in_pages) {
  // forcus 检查是否已经提交
  // note 避免重复提交，节省系统调用开销
  size_t commit_start_page = start_page;
  size_t commit_end_page = start_page + size_in_pages;
  
  // forcus 查找实际需要提交的页范围
  bool result = _committed.get_next_zero_offset(commit_start_page, &commit_start_page);
  if (!result) {
    // 所有页都已提交
    return true;
  }
  
  // forcus 计算地址和大小
  char* start_addr = page_start(commit_start_page);
  size_t size = size_in_pages * _page_size;
  
  // forcus 调用操作系统API提交内存
  // note Linux: mmap(addr, size, PROT_READ|PROT_WRITE, MAP_FIXED|MAP_ANONYMOUS)
  // note Windows: VirtualAlloc(addr, size, MEM_COMMIT)
  os::commit_memory_or_exit(start_addr, size, _page_size, _executable,
                           err_msg("Failed to commit area from " PTR_FORMAT " to " PTR_FORMAT " of length " SIZE_FORMAT ".",
                                   p2i(start_addr), p2i(start_addr + size), size));
  
  // forcus 更新已提交位图
  _committed.set_range(commit_start_page, commit_end_page);
  
  // forcus 返回内存是否已清零
  // note Linux上新分配的匿名页保证清零，Windows需要手动清零
  return is_zero_filled(start_addr, size);
}
```

### 系统调用层面（Linux）

```cpp
// openjdk11-core/src/hotspot/os/linux/os_linux.cpp

bool os::commit_memory(char* addr, size_t bytes, size_t alignment_hint, bool executable) {
  // forcus Linux上使用mmap提交内存
  int prot = executable ? PROT_READ|PROT_WRITE|PROT_EXEC : PROT_READ|PROT_WRITE;
  
  // forcus MAP_FIXED: 强制使用指定地址（预留时已分配）
  // forcus MAP_ANONYMOUS: 匿名内存（不关联文件）
  // forcus MAP_PRIVATE: 私有映射
  void* result = ::mmap(addr, bytes, prot,
                        MAP_FIXED | MAP_ANONYMOUS | MAP_PRIVATE,
                        -1, 0);
  
  if (result == MAP_FAILED) {
    return false;
  }
  
  return true;
}
```

---

## 七、完整的内存提交流程图

```
┌────────────────────────────────────────────────────────────────┐
│                     expand(init_byte_size)                     │
│                    请求扩展到初始堆大小                          │
└────────────────────────┬───────────────────────────────────────┘
                         │
         ┌───────────────┴───────────────┐
         │  内存对齐                      │
         │  - 页对齐 (4KB/2MB)            │
         │  - Region对齐 (4MB)            │
         └───────────────┬───────────────┘
                         │
         ┌───────────────┴───────────────┐
         │  检查堆是否已达最大值          │
         │  is_maximal_no_gc()           │
         └───────────────┬───────────────┘
                         │
         ┌───────────────┴───────────────┐
         │  计算Region数量                │
         │  regions_to_expand = 500      │
         └───────────────┬───────────────┘
                         │
         ┌───────────────┴───────────────┐
         │  HeapRegionManager::expand_by │
         └───────────────┬───────────────┘
                         │
         ┌───────────────┴───────────────┐
         │  expand_at(0, 500)            │
         │  查找不可用Region              │
         └───────────────┬───────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  make_regions_available(0, 500)   │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  commit_regions(0, 500)           │
         │  提交所有相关内存                  │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴──────────────────────────────┐
         │                                              │
    ┌────▼────┐  ┌────▼────┐  ┌────▼────┐  ┌────▼────┐
    │ 堆内存   │  │ 位图     │  │ BOT     │  │ 卡表     │
    │ 2GB     │  │ 64MB    │  │ 4MB     │  │ 8MB     │
    └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘
         │            │            │            │
         └────────────┴────────────┴────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  G1RegionToSpaceMapper::commit_regions │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  G1PageBasedVirtualSpace::commit   │
         │  计算页范围、检查已提交状态         │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  os::commit_memory()               │
         │  Linux: mmap(addr, size,           │
         │              PROT_READ|PROT_WRITE, │
         │              MAP_FIXED|MAP_ANON)   │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  内核分配物理页                     │
         │  更新页表映射                       │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  可选：预触摸 (AlwaysPreTouch)     │
         │  强制访问所有页，分配物理内存       │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  创建HeapRegion对象                │
         │  500个HeapRegion实例               │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  初始化HeapRegion                  │
         │  设置边界、类型等元数据             │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  插入到空闲列表                     │
         │  可供对象分配使用                   │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  更新统计信息                       │
         │  - _num_committed += 500           │
         │  - g1_policy()->record_new_heap_size() │
         └───────────────┬────────────────────┘
                         │
         ┌───────────────┴────────────────────┐
         │  返回成功                           │
         │  return true                       │
         └────────────────────────────────────┘
```

---

## 八、失败处理机制

### 失败场景

| 失败原因 | 检测点 | 处理方式 |
|---------|-------|---------|
| **物理内存不足** | `os::commit_memory()` | 返回false，expand()失败 |
| **交换空间不足** | `os::commit_memory()` | 返回false，expand()失败 |
| **地址空间不足** | `_hrm.available() < regions` | 返回false，可能触发OOM |
| **堆已达最大值** | `is_maximal_no_gc()` | 返回false，正常结束 |

### 初始化失败的处理

```cpp
// openjdk11-core/src/hotspot/share/gc/g1/g1CollectedHeap.cpp:1888-1891

if (!expand(init_byte_size, _workers)) {
    // forcus 扩展失败，无法继续初始化
    // note 打印错误消息并终止VM启动
    vm_shutdown_during_initialization("Failed to allocate initial heap.");
    
    // forcus 返回JNI错误码：内存不足
    // note JNI_ENOMEM = -4，表示内存分配失败
    return JNI_ENOMEM;
}

// 如果到达这里，说明扩展成功，继续后续初始化...
```

### vm_shutdown_during_initialization()

```cpp
void vm_shutdown_during_initialization(const char* error_msg) {
  // forcus 打印错误消息到stderr
  tty->print_cr("Error occurred during initialization of VM");
  if (error_msg != NULL) {
    tty->print_cr("%s", error_msg);
  }
  
  // forcus 调用vm_exit()终止进程
  // note 退出码1表示初始化失败
  vm_exit(1);
}
```

**用户看到的错误示例**：

```bash
$ java -Xms8g -jar myapp.jar

Error occurred during initialization of VM
Failed to allocate initial heap.

# 可能的原因：
# 1. 系统物理内存 < 8GB
# 2. ulimit -v (虚拟内存限制) 太小
# 3. 其他进程占用了太多内存
# 4. 32位JVM无法分配超过4GB的堆
```

---

## 九、性能考虑

### 1. 内存预触摸 (AlwaysPreTouch)

```cpp
// forcus 预触摸选项：-XX:+AlwaysPreTouch
if (AlwaysPreTouch) {
    _storage.pretouch(start_page, num_regions * _pages_per_region, pretouch_gang);
}
```

| 特性 | 不启用PreTouch | 启用PreTouch (-XX:+AlwaysPreTouch) |
|-----|---------------|----------------------------------|
| **启动速度** | 快（虚拟内存提交即返回） | 慢（需要访问所有页） |
| **首次GC性能** | 慢（缺页中断） | 快（页已分配） |
| **内存占用** | 延迟分配（按需） | 立即全部分配 |
| **适用场景** | 开发环境、快速启动 | 生产环境、稳定性能 |

**示例**：

```bash
# 不启用（默认）
$ time java -Xms2g -Xmx2g -jar app.jar
VM初始化: 0.5秒
首次GC: 0.2秒（缺页中断）

# 启用PreTouch
$ time java -Xms2g -Xmx2g -XX:+AlwaysPreTouch -jar app.jar
VM初始化: 2.0秒（预触摸2GB内存）
首次GC: 0.05秒（无缺页中断）
```

### 2. 大页支持 (Large Pages)

```cpp
// forcus 使用大页（2MB或1GB）替代4KB页
// 配置：-XX:+UseLargePages
```

| 页大小 | 优势 | 劣势 |
|-------|------|------|
| **4KB** | 灵活，内存利用率高 | TLB miss多，性能一般 |
| **2MB** | TLB miss少，性能提升5-10% | 内存浪费，配置复杂 |
| **1GB** | TLB miss最少，性能最优 | 内存浪费严重，限制多 |

**TLB (Translation Lookaside Buffer) 优势**：

```
4KB页：
- 2GB堆需要 524,288个页表项
- TLB容量有限（几百到几千项）
- TLB miss频繁，需要访问页表（慢）

2MB页：
- 2GB堆只需要 1,024个页表项
- TLB可以缓存更多映射
- TLB miss减少90%+
```

### 3. 并行预触摸

```cpp
// forcus 使用WorkGang并行预触摸内存
_storage.pretouch(start_page, num_pages, pretouch_gang);

// 实现伪代码：
void pretouch(size_t start, size_t num, WorkGang* gang) {
    if (gang != NULL) {
        // forcus 分配任务给多个线程并行触摸
        PretouchTask task(start, num);
        gang->run_task(&task);  // 8个线程并行
    } else {
        // forcus 单线程顺序触摸
        for (size_t i = 0; i < num; i++) {
            volatile char c = memory[i * page_size];  // 强制访问
        }
    }
}
```

**性能对比**：

```
2GB堆，启用PreTouch：

单线程预触摸：
  - 时间：2000ms

8线程并行预触摸：
  - 时间：300ms
  - 加速比：6.7x
```

---

## 十、内存布局示例

### 初始化后的内存布局

```
假设：-Xms2g -Xmx8g，Region大小4MB

虚拟地址空间（8GB预留）：
┌────────────────────────────────────────────────────────────┐
│  0x600000000 - 0x800000000 (8GB虚拟地址空间)               │
│  ┌──────────────────────────────────┐                      │
│  │  已提交物理内存 (2GB)             │  未提交 (6GB)        │
│  │  ┌──────┬──────┬──────┬────┐    │                      │
│  │  │Region│Region│Region│... │    │                      │
│  │  │  0   │  1   │  2   │499 │    │                      │
│  │  │ 4MB  │ 4MB  │ 4MB  │4MB │    │                      │
│  │  └──────┴──────┴──────┴────┘    │                      │
│  │  500个Region，每个4MB            │  1524个未提交Region   │
│  └──────────────────────────────────┘                      │
└────────────────────────────────────────────────────────────┘

辅助数据结构（也已提交）：
┌────────────────────────────────────────────────────────────┐
│  标记位图 (prev_bitmap)：32MB                              │
│  标记位图 (next_bitmap)：32MB                              │
│  BOT (Block Offset Table)：4MB                            │
│  Card Table：4MB                                          │
│  Card Counts：4MB                                         │
│  HeapRegion对象数组：~100KB                               │
└────────────────────────────────────────────────────────────┘

HeapRegionManager状态：
- _num_committed = 500  （已提交Region数）
- _allocated_heapregions_length = 500  （已分配HeapRegion对象数）
- max_length() = 2048  （最大Region数，8GB/4MB）
- available() = 1524  （可用于扩展的Region数）

空闲列表：
FreeList: [Region0] -> [Region1] -> [Region2] -> ... -> [Region499]
  └─> 所有500个Region都在空闲列表中，可供分配
```

---

## 十一、常见问题

### Q1: 为什么初始化失败会直接终止VM？

**A:** 
1. **无法继续**：没有堆内存，JVM无法创建任何对象，包括必要的系统对象
2. **一致性保证**：半初始化的VM状态不可预测，可能导致崩溃或数据损坏
3. **明确失败**：清晰的错误消息比神秘的崩溃更有价值
4. **标准做法**：所有JVM实现（HotSpot/OpenJ9/GraalVM）都采用这种方式

### Q2: init_byte_size 和 max_heap_size 的关系？

**A:**

```
配置：-Xms2g -Xmx8g

init_byte_size = 2GB   ← expand()提交的大小
max_heap_size = 8GB    ← 预留的虚拟地址空间

初始化后：
- 已提交：2GB（物理内存占用）
- 已预留：8GB（虚拟地址空间）
- 可扩展：6GB（按需提交）

运行时扩展：
- 堆使用率达到阈值 → 触发expand()
- 动态提交更多物理内存（最多到8GB）
- 无需重启应用
```

### Q3: 如果expand()部分成功怎么办？

**A:**

```cpp
uint expanded_by = _hrm.expand_by(500, _workers);

if (expanded_by == 300) {  // 只成功扩展了300个Region
    // 对于初始化调用：
    return false;  // 失败，VM终止
    
    // 对于运行时调用：
    return true;   // 部分成功，继续运行
    // 堆大小 = 1.2GB（不是2GB），但仍然可用
}
```

初始化时要求全部成功，运行时允许部分成功。

### Q4: 为什么需要这么多辅助数据结构？

**A:**

| 数据结构 | 占用 | 不使用的后果 |
|---------|------|------------|
| **标记位图** | 3% | 无法并发标记，Full GC时间增加10x+ |
| **BOT** | 0.2% | 对象查找变慢，GC暂停增加 |
| **Card Table** | 0.2% | 无法增量式GC，每次扫描整个堆 |
| **Card Counts** | 0.2% | 记忆集维护低效，吞吐量下降 |

虽然占用额外内存，但性能提升远超成本。

### Q5: AlwaysPreTouch 应该启用吗？

**A:**

| 场景 | 建议 | 原因 |
|-----|------|------|
| **生产环境** | ✅ 启用 | 稳定性能，避免运行时缺页 |
| **容器环境** | ✅ 启用 | 确保内存限制在启动时验证 |
| **开发环境** | ❌ 不启用 | 快速启动更重要 |
| **小堆(<1GB)** | ❌ 不启用 | 预触摸时间短，收益不明显 |
| **大堆(>8GB)** | ✅ 启用 | 避免首次GC时大量缺页 |

---

## 十二、总结

### 核心要点

```cpp
if (!expand(init_byte_size, _workers)) {
    vm_shutdown_during_initialization("Failed to allocate initial heap.");
    return JNI_ENOMEM;
}
```

这段代码的本质是：**将预留的虚拟地址空间转换为实际可用的物理内存**。

### 完整流程

1. **内存对齐**：按页大小和Region大小对齐
2. **计算Region数**：init_byte_size / 4MB = 需要的Region数
3. **查找可用空间**：在Region数组中查找未提交的Region
4. **提交物理内存**：
   - 堆内存（主要占用）
   - 标记位图（并发标记）
   - BOT（对象查找）
   - 卡表（增量GC）
5. **创建元数据**：HeapRegion对象
6. **初始化Region**：设置边界、类型等
7. **加入空闲列表**：可供对象分配

### 关键设计原则

| 原则 | 体现 | 优势 |
|-----|------|------|
| **延迟分配** | 虚拟内存预留，按需提交 | 节省物理内存 |
| **动态扩展** | 支持运行时扩展到max_heap_size | 灵活适应负载 |
| **失败快速** | 初始化失败立即终止 | 避免不可预测行为 |
| **并行优化** | 多线程预触摸 | 减少启动时间 |
| **操作系统抽象** | os::commit_memory() | 跨平台兼容 |

### 性能影响

```
初始化时间分解（2GB堆）：

不启用PreTouch：
  expand() ≈ 100ms
    ├─ 内存对齐：<1ms
    ├─ commit_regions()：80ms
    │   ├─ mmap系统调用：60ms
    │   └─ 位图等辅助结构：20ms
    └─ HeapRegion创建：20ms

启用PreTouch（8线程）：
  expand() ≈ 400ms
    ├─ ... (同上) ≈ 100ms
    └─ 预触摸：300ms
        └─ 强制分配所有物理页
```

### 最佳实践

1. **生产环境**：启用 `-XX:+AlwaysPreTouch`
2. **容器环境**：设置 `-Xms` = `-Xmx`（避免运行时扩展）
3. **大堆**：考虑 `-XX:+UseLargePages`（需要OS配置）
4. **内存受限**：谨慎设置 `-Xms`，确保物理内存充足

---

## 附录：相关JVM参数

| 参数 | 默认值 | 作用 | 示例 |
|-----|-------|------|------|
| `-Xms` | 物理内存/64 | 初始堆大小 | `-Xms2g` |
| `-Xmx` | 物理内存/4 | 最大堆大小 | `-Xmx8g` |
| `-XX:+AlwaysPreTouch` | false | 启动时预触摸内存 | - |
| `-XX:+UseLargePages` | false | 使用大页 | - |
| `-XX:LargePageSizeInBytes` | 2MB | 大页大小 | `-XX:LargePageSizeInBytes=1g` |
| `-XX:G1HeapRegionSize` | 自动 | Region大小 | `-XX:G1HeapRegionSize=4m` |
| `-XX:+G1ExitOnExpansionFailure` | false | 扩展失败时退出 | - |
| `-XX:ParallelGCThreads` | CPU核心数 | 并行GC线程数 | `-XX:ParallelGCThreads=8` |

---

**文档创建时间**：2025-01-13  
**JDK版本**：OpenJDK 11  
**分析深度**：从用户代码到操作系统调用的完整链路
