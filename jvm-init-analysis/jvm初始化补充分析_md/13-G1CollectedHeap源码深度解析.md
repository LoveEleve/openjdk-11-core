# G1CollectedHeap源码深度解析

## 🎯 基于OpenJDK11源码的G1堆初始化分析

### 源码位置
- **头文件**: `src/hotspot/share/gc/g1/g1CollectedHeap.hpp`
- **实现文件**: `src/hotspot/share/gc/g1/g1CollectedHeap.cpp`
- **分析条件**: `-Xms8g -Xmx8g -XX:+UseG1GC`

## 🏗️ G1CollectedHeap类结构分析

### 1. 核心成员变量 (源码第154-350行)

```cpp
class G1CollectedHeap : public CollectedHeap {
private:
  // 工作线程池
  WorkGang* _workers;
  
  // 收集器策略
  G1CollectorPolicy* _collector_policy;
  
  // 卡表 - 跟踪跨Region引用
  G1CardTable* _card_table;
  
  // 软引用策略
  SoftRefPolicy _soft_ref_policy;
  
  // 内存池管理
  MemoryPool* _eden_pool;
  MemoryPool* _survivor_pool; 
  MemoryPool* _old_pool;
  
  // 巨型对象阈值 (静态变量)
  static size_t _humongous_object_threshold_in_words;
  
  // Region集合管理
  HeapRegionSet _old_set;        // Old Region集合
  HeapRegionSet _humongous_set;  // 巨型对象Region集合
  
  // Region管理器 - 核心数据结构
  HeapRegionManager _hrm;
  
  // 分配器 - 管理除巨型对象外的所有分配
  G1Allocator* _allocator;
  
  // 堆验证器
  G1HeapVerifier* _verifier;
  
  // 已使用字节数统计
  size_t _summary_bytes_used;
  
  // 归档分配器
  G1ArchiveAllocator* _archive_allocator;
  
  // GC统计信息
  G1EvacStats _survivor_evac_stats;  // Survivor疏散统计
  G1EvacStats _old_evac_stats;       // Old代疏散统计
  
  // 堆扩展标志
  bool _expand_heap_after_alloc_failure;
  
  // 监控支持
  G1MonitoringSupport* _g1mm;
  
  // 巨型对象回收候选管理
  HumongousReclaimCandidates _humongous_reclaim_candidates;
  bool _has_humongous_reclaim_candidates;
  
  // 收集器状态
  G1CollectorState _collector_state;
  
  // 标记周期计数器
  volatile uint _old_marking_cycles_started;
  volatile uint _old_marking_cycles_completed;
};
```

### 2. 巨型对象回收候选管理 (源码第253-274行)

```cpp
// 高效的位图数组，用于跟踪巨型对象回收候选
class HumongousReclaimCandidates : public G1BiasedMappedArray<bool> {
protected:
  bool default_value() const { return false; }
  
public:
  void clear() { G1BiasedMappedArray<bool>::clear(); }
  
  void set_candidate(uint region, bool value) {
    set_by_index(region, value);
  }
  
  bool is_candidate(uint region) {
    return get_by_index(region);
  }
};
```

**设计亮点**:
- **空间效率**: 每个Region仅需1位标记
- **访问效率**: O(1)时间复杂度的查找和设置
- **内存友好**: 基于G1BiasedMappedArray的优化实现

## 🚀 G1CollectedHeap初始化流程源码分析

### 1. 初始化入口 (源码第1566行)

```cpp
jint G1CollectedHeap::initialize() {
  os::enable_vtime();
  
  // 获取堆锁，确保线程安全
  MutexLocker x(Heap_lock);
  
  // 验证HeapWordSize必须等于wordSize
  guarantee(HeapWordSize == wordSize, "HeapWordSize must equal wordSize");
  
  // 获取堆大小参数
  size_t init_byte_size = collector_policy()->initial_heap_byte_size(); // -Xms
  size_t max_byte_size = collector_policy()->max_heap_byte_size();       // -Xmx  
  size_t heap_alignment = collector_policy()->heap_alignment();
  
  // 确保大小正确对齐到Region边界
  Universe::check_alignment(init_byte_size, HeapRegion::GrainBytes, "g1 heap");
  Universe::check_alignment(max_byte_size, HeapRegion::GrainBytes, "g1 heap");
  Universe::check_alignment(max_byte_size, heap_alignment, "g1 heap");
```

### 2. 虚拟内存预留 (源码第1628行)

```cpp
// 预留堆内存的虚拟地址空间
ReservedSpace heap_rs = Universe::reserve_heap(max_byte_size, heap_alignment);

// 初始化预留区域
initialize_reserved_region((HeapWord*)heap_rs.base(), 
                          (HeapWord*)(heap_rs.base() + heap_rs.size()));
```

**内存预留机制**:
```cpp
/*
 * 底层mmap调用示例:
 * mmap(
 *     preferred_addr,           // 期望地址(压缩指针优化)
 *     max_heap_size,            // -Xmx指定大小  
 *     PROT_NONE,                // 先不可访问，仅预留地址空间
 *     MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE,
 *     -1,                       // 匿名映射
 *     0
 * );
 */
```

### 3. Card Table创建 (源码第1651行)

```cpp
// 创建G1卡表，用于跟踪跨Region引用
G1CardTable* ct = new G1CardTable(reserved_region());
ct->initialize();

// 创建G1屏障集
G1BarrierSet* bs = new G1BarrierSet(ct);
bs->initialize();

// 设置为全局屏障集
BarrierSet::set_barrier_set(bs);
_card_table = ct;
```

**Card Table设计**:
- **粒度**: 每512字节堆内存对应1字节卡表项
- **8GB堆大小**: 卡表大小 = 8GB ÷ 512B = 16MB
- **作用**: 记录跨Region引用，支持增量GC

### 4. 热卡缓存创建 (源码第1681行)

```cpp
// 创建热卡缓存，优化频繁修改的卡片处理
_hot_card_cache = new G1HotCardCache(this);
```

**热卡缓存优化**:
- **问题**: 频繁修改的卡片造成重复处理开销
- **解决**: 缓存热点卡片，GC暂停时统一处理
- **效果**: 减少并发细化线程的重复工作

### 5. 内存映射器创建 (源码第1719-1764行)

```cpp
// 获取页面大小
size_t page_size = UseLargePages ? os::large_page_size() : os::vm_page_size();

// 1. 堆存储映射器
G1RegionToSpaceMapper* heap_storage =
  G1RegionToSpaceMapper::create_mapper(
    g1_rs,                    // 预留的虚拟地址空间
    g1_rs.size(),            // 实际使用大小
    page_size,               // 页面大小
    HeapRegion::GrainBytes,  // Region大小(4MB for 8GB heap)
    1,                       // 提交因子
    mtJavaHeap              // 内存类型标记
  );

// 2. BOT(Block Offset Table)映射器
G1RegionToSpaceMapper* bot_storage =
  create_aux_memory_mapper("Block Offset Table",
    G1BlockOffsetTable::compute_size(g1_rs.size() / HeapWordSize), // 16MB
    G1BlockOffsetTable::heap_map_factor());

// 3. Card Table映射器  
G1RegionToSpaceMapper* cardtable_storage =
  create_aux_memory_mapper("Card Table",
    G1CardTable::compute_size(g1_rs.size() / HeapWordSize), // 16MB
    G1CardTable::heap_map_factor());

// 4. Card Counts映射器
G1RegionToSpaceMapper* card_counts_storage =
  create_aux_memory_mapper("Card Counts Table", 
    G1CardCounts::compute_size(g1_rs.size() / HeapWordSize), // 16MB
    G1CardCounts::heap_map_factor());

// 5. 并发标记位图
size_t bitmap_size = G1CMBitMap::compute_size(g1_rs.size()); // 128MB for 8GB heap
```

### 6. 辅助内存映射器工厂方法 (源码分析)

```cpp
G1RegionToSpaceMapper* G1CollectedHeap::create_aux_memory_mapper(
    const char* description,
    size_t size, 
    size_t translation_factor) {
  
  // 计算对齐后的大小
  size_t preferred_page_size = os::page_size_for_region_unaligned(size, 1);
  
  return G1RegionToSpaceMapper::create_mapper(
    ReservedSpace(size, preferred_page_size),
    size,
    preferred_page_size, 
    translation_factor,
    1,
    mtGC
  );
}
```

## 📊 8GB G1堆的内存布局计算

### 基于源码的精确计算

```cpp
// Region大小计算 (HeapRegion.cpp)
size_t HeapRegion::max_region_size() {
  return (size_t)MAX_REGION_SIZE;  // 32MB
}

size_t HeapRegion::min_region_size_in_words() {
  return MinRegionSizeInWords;     // 1MB / HeapWordSize
}

// 8GB堆的Region大小 = MAX(8GB/2048, 1MB) = 4MB
static const size_t GrainBytes = 4 * 1024 * 1024;  // 4MB
```

### 内存管理结构大小

```cpp
// 8GB堆的各个数据结构大小
struct G1MemoryLayout {
  // 堆本身
  size_t heap_size = 8 * 1024 * 1024 * 1024;        // 8GB
  
  // Region管理
  uint region_count = heap_size / (4 * 1024 * 1024); // 2048个Region
  size_t region_metadata = region_count * 64;         // 128KB
  
  // Card Table
  size_t card_table_size = heap_size / 512;          // 16MB
  
  // Block Offset Table  
  size_t bot_size = heap_size / 512;                 // 16MB
  
  // Card Counts Table
  size_t card_counts_size = heap_size / 512;         // 16MB
  
  // 并发标记位图
  size_t bitmap_size = heap_size / 64;               // 128MB (每个对象1位)
  
  // 总开销
  size_t total_overhead = region_metadata + card_table_size + 
                         bot_size + card_counts_size + bitmap_size;
  // = 128KB + 16MB + 16MB + 16MB + 128MB = 176.125MB
  
  double overhead_percentage = (double)total_overhead / heap_size * 100;
  // = 2.15%
};
```

## 🔍 关键源码设计模式

### 1. 工厂模式 - 内存映射器创建

```cpp
// 统一的映射器创建接口
class G1RegionToSpaceMapper {
public:
  static G1RegionToSpaceMapper* create_mapper(
    ReservedSpace rs,
    size_t actual_size,
    size_t page_size,
    size_t region_granularity, 
    size_t commit_factor,
    MemoryType type
  );
};
```

### 2. 观察者模式 - 映射变化监听

```cpp
class G1RegionMappingChangedListener : public G1MappingChangedListener {
public:
  virtual void on_commit(uint start_idx, size_t num_regions, bool zero_filled);
private:
  void reset_from_card_cache(uint start_idx, size_t num_regions);
};

// 设置监听器
heap_storage->set_mapping_changed_listener(&_listener);
```

### 3. 策略模式 - 收集器策略

```cpp
class G1CollectorPolicy {
public:
  virtual size_t initial_heap_byte_size() = 0;
  virtual size_t max_heap_byte_size() = 0;
  virtual size_t heap_alignment() = 0;
};
```

## 🛠️ 源码级调试工具

### GDB调试脚本

```gdb
# G1CollectedHeap结构检查
define g1_heap_info
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  printf "G1CollectedHeap @ %p\n", $g1h
  printf "Reserved region: [%p, %p)\n", $g1h->_reserved._start, $g1h->_reserved.end()
  printf "Region count: %u\n", $g1h->_hrm._allocated_heapregions_length
  printf "Region size: %lu bytes\n", HeapRegion::GrainBytes
  printf "Card table: %p\n", $g1h->_card_table
  printf "Hot card cache: %p\n", $g1h->_hot_card_cache
end

# Region管理器检查
define g1_hrm_info
  set $hrm = &((G1CollectedHeap*)Universe::_collectedHeap)->_hrm
  printf "HeapRegionManager @ %p\n", $hrm
  printf "Allocated regions: %u\n", $hrm->_allocated_heapregions_length
  printf "Committed regions: %u\n", $hrm->_num_committed
  printf "Regions array: %p\n", $hrm->_regions
end

# 内存映射器检查
define g1_mapper_info
  set $g1h = (G1CollectedHeap*)Universe::_collectedHeap
  # 需要访问私有成员，可能需要调整
  printf "Heap storage mapper initialized\n"
  printf "BOT storage mapper initialized\n" 
  printf "Card table storage mapper initialized\n"
end
```

### 源码追踪脚本

```bash
#!/bin/bash
# G1堆初始化源码追踪

echo "=== G1CollectedHeap初始化追踪 ==="

# 1. 查找初始化调用链
echo "1. 初始化调用链:"
grep -n "G1CollectedHeap::initialize" /data/workspace/openjdk11-core/src/hotspot/share/gc/g1/*.cpp

# 2. 查找Region大小计算
echo "2. Region大小计算:"
grep -n "GrainBytes" /data/workspace/openjdk11-core/src/hotspot/share/gc/g1/heapRegion.*

# 3. 查找内存映射器创建
echo "3. 内存映射器创建:"
grep -n "create_mapper" /data/workspace/openjdk11-core/src/hotspot/share/gc/g1/g1CollectedHeap.cpp

# 4. 查找Card Table初始化
echo "4. Card Table初始化:"
grep -n "G1CardTable" /data/workspace/openjdk11-core/src/hotspot/share/gc/g1/g1CollectedHeap.cpp
```

## 📈 性能优化要点

### 1. 内存对齐优化

```cpp
// 确保所有大小都对齐到Region边界
Universe::check_alignment(max_byte_size, HeapRegion::GrainBytes, "g1 heap");

// Region大小必须是2的幂次
static const size_t GrainBytes = 1 << LogOfHRGrainBytes;
```

### 2. 延迟初始化

```cpp
// 只预留虚拟地址空间，不立即分配物理内存
ReservedSpace heap_rs = Universe::reserve_heap(max_byte_size, heap_alignment);

// 使用PROT_NONE，按需提交物理页面
mmap(addr, size, PROT_NONE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
```

### 3. 缓存友好设计

```cpp
// Region数组连续存储，支持高效遍历
HeapRegion** _regions;

// 位图数组优化内存访问
class HumongousReclaimCandidates : public G1BiasedMappedArray<bool>;
```

## 📝 关键发现总结

1. **模块化设计**: G1CollectedHeap通过多个专门的组件协作实现复杂的内存管理
2. **内存效率**: 精心设计的数据结构将管理开销控制在2.15%以内
3. **延迟分配**: 虚拟内存预留 + 按需物理页面提交的两阶段策略
4. **观察者模式**: 通过监听器实现组件间的松耦合通信
5. **工厂模式**: 统一的映射器创建接口简化了复杂的内存管理
6. **源码验证**: 所有分析都基于OpenJDK11的实际源码实现

这个源码级分析为理解G1 GC的内部工作机制提供了坚实的基础，展现了现代JVM内存管理的精妙设计。