# JVM堆内存分配策略源码级深度分析

> **环境**: -Xms8g -Xmx8g, 非大页(UseLargePages=false), 非NUMA, 64位Linux, OpenJDK 11
> **重点**: 堆内存预留、压缩指针模式选择、内存分配地址选择策略

## 一、堆内存预留入口

### 1.1 Universe::reserve_heap()

**源码位置**: `src/hotspot/share/memory/universe.cpp`

```cpp
ReservedSpace Universe::reserve_heap(size_t heap_size, size_t alignment) {
  assert(googc_heap_base_min_address != NULL, "HeapBaseMinAddress not yet set.");
  assert(googc_heap_base_min_address < (char *)OopEncodingHeapMax, "Incorrect HeapBaseMinAddress");

  size_t total_reserve_size = align_up(heap_size, alignment);
  
  // 使用AllocateHeapAt指定堆分配位置(通常不使用)
  char* heap_allocation_directory = AllocateHeapAt;
  
  // 创建ReservedHeapSpace对象，触发实际内存预留
  ReservedHeapSpace total_rs(total_reserve_size, alignment, 
                             UseLargePages, heap_allocation_directory);
  
  return total_rs;
}
```

### 1.2 ReservedHeapSpace构造函数

**源码位置**: `src/hotspot/share/memory/virtualspace.cpp:757-799`

```cpp
ReservedHeapSpace::ReservedHeapSpace(size_t size, size_t alignment, 
                                     bool large, const char *heap_allocation_directory)
  : ReservedSpace(), _fd_for_heap(-1) {
    
  if (size == 0) {
    return;
  }
  
  // 文件支持的堆分配(NVMe、持久内存等)
  if (heap_allocation_directory != NULL) {
    _fd_for_heap = os::create_file_for_heap(heap_allocation_directory);
    // ...
  }
  
  // 核心分支：是否使用压缩指针
  if (UseCompressedOops) {
    initialize_compressed_heap(size, alignment, large);  // 压缩指针堆分配策略
    // ...
  } else {
    initialize(size, alignment, large, NULL, false);     // 普通堆分配
  }
}
```

---

## 二、压缩指针堆分配策略详解

### 2.1 initialize_compressed_heap()核心逻辑

**源码位置**: `src/hotspot/share/memory/virtualspace.cpp:540-747`

```cpp
void ReservedHeapSpace::initialize_compressed_heap(const size_t size, size_t alignment, bool large) {
    // 1. 验证堆大小支持压缩指针
    guarantee(size + noaccess_prefix_size(alignment) <= OopEncodingHeapMax,
              "can not allocate compressed oop heap for this size");
    // OopEncodingHeapMax = 32GB，超过则无法使用压缩指针
    
    // 2. 计算堆基址最小地址
    char *aligned_heap_base_min_address = (char *)align_up((void *)HeapBaseMinAddress, alignment);
    // HeapBaseMinAddress = 2GB (Linux x86_64)
    // 堆不会分配在0~2GB区域(留给C堆和其他用途)
    
    // 3. 尝试不同的压缩指针模式（按优先级从高到低）
    // ...
}
```

### 2.2 三种压缩指针模式详解

#### 2.2.1 Unscaled模式 (最优)

**条件**: 堆起始地址为0，堆大小 ≤ 4GB

```cpp
// 源码: virtualspace.cpp:603-656
if (aligned_heap_base_min_address + size <= (char *)UnscaledOopHeapMax) {
    // UnscaledOopHeapMax = 4GB
    // 尝试在[HeapBaseMinAddress, 4GB-size]范围内分配
    
    char *try_end = (char *)UnscaledOopHeapMax - size;
    char *aligned_end = align_down(try_end, attach_point_alignment);
    
    // 从高地址向低地址尝试
    char *attach_point = aligned_end;
    while (attach_point >= aligned_heap_base_min_address && _base == NULL) {
        try_reserve_heap(size, alignment, large, attach_point);
        attach_point -= stepsize;
    }
}
```

**内存布局** (Unscaled成功时):
```
0GB        2GB        4GB
 ├──────────┼──────────┤
 │ C堆/其他 │   Java堆  │
 └──────────┴──────────┘
            ↑
      HeapBaseMinAddress
```

**解码公式**: `oop = (oop32)` (直接使用32位值)

#### 2.2.2 ZeroBased模式 (次优)

**条件**: 堆起始地址为0，堆大小 ≤ 32GB

```cpp
// 源码: virtualspace.cpp:660-706
if (_base == NULL && aligned_heap_base_min_address + size <= (char *)OopEncodingHeapMax) {
    // OopEncodingHeapMax = 32GB
    // 尝试在[HeapBaseMinAddress, 32GB-size]范围内分配
    
    char *zerobased_max = (char *)OopEncodingHeapMax - size;
    char *aligned_zerobased_max = align_down(zerobased_max, attach_point_alignment);
    
    // 从高地址向低地址尝试
    while (attach_point >= aligned_heap_base_min_address && _base == NULL) {
        try_reserve_heap(size, alignment, large, attach_point);
        attach_point -= stepsize;
    }
}
```

**内存布局** (8GB堆，ZeroBased模式):
```
0GB        2GB                  10GB       32GB
 ├──────────┼────────────────────┼──────────┤
 │ C堆/其他 │      Java堆(8GB)    │   空闲   │
 └──────────┴────────────────────┴──────────┘
            ↑                    ↑
       堆起始地址            堆结束地址
```

**解码公式**: `oop = (oop32 << 3)` (左移3位，因为8字节对齐)

#### 2.2.3 HeapBased模式 (兜底)

**条件**: 堆大小 > 32GB 或无法在低地址分配

```cpp
// 源码: virtualspace.cpp:708-746
if (_base == NULL) {
    // Disjoint模式：尝试特定地址
    char **noaccess_addresses = get_attach_addresses_for_disjoint_mode();
    for (int i = 0; noaccess_addresses[i] != NULL; i++) {
        try_reserve_heap(size + noaccess_prefix, alignment, large, attach_point);
        i++;
    }
    
    // 最后尝试：让OS选择地址
    if (_base == NULL) {
        initialize(size + noaccess_prefix, alignment, large, NULL, false);
    }
}
```

**Disjoint模式尝试地址**:
```cpp
static char **get_attach_addresses_for_disjoint_mode() {
    static uint64_t addresses[] = {
        2 * SIZE_32G,       //  64GB
        3 * SIZE_32G,       //  96GB
        4 * SIZE_32G,       // 128GB
        8 * SIZE_32G,       // 256GB
        10 * SIZE_32G,      // 320GB
        1 * SIZE_64K * SIZE_32G,   // 2TB
        2 * SIZE_64K * SIZE_32G,   // 4TB
        0
    };
    return (char**)addresses;
}
```

**解码公式**: `oop = (oop32 << 3) + base`

---

## 三、try_reserve_heap()实际内存预留

### 3.1 源码分析

**源码位置**: `src/hotspot/share/memory/virtualspace.cpp:348-415`

```cpp
void ReservedHeapSpace::try_reserve_heap(size_t size,
                                         size_t alignment,
                                         bool large,
                                         char *requested_address) {
    // 如果之前尝试过但地址不满意，先释放
    if (_base != NULL) {
        release();
    }
    
    // 是否需要特殊分配(大页且OS不支持按需分页)
    bool special = large && !os::can_commit_large_page_memory();
    
    if (!special) {
        // 普通分配
        if (requested_address != NULL) {
            // 指定地址分配
            _base = os::attempt_reserve_memory_at(size, requested_address);
        } else {
            // 让OS选择地址
            _base = os::reserve_memory(size, NULL, alignment);
        }
        if (_base != NULL) {
            _size = size;
            _alignment = alignment;
            _special = false;
            _noaccess_prefix = 0;
        }
    }
    
    // 如果分配成功但地址不是期望的，记录日志
    if (_base != requested_address) {
        log_trace(gc, heap, coops)("Failed to attach at " PTR_FORMAT 
                                   ", got " PTR_FORMAT,
                                   p2i(requested_address), p2i(_base));
    }
}
```

### 3.2 Linux系统调用

**源码位置**: `src/hotspot/os/linux/os_linux.cpp`

```cpp
char* os::attempt_reserve_memory_at(size_t bytes, char* requested_addr) {
  // Linux实现
  char* result = anon_mmap(requested_addr, bytes, false);
  if (result != NULL && requested_addr != NULL && result != requested_addr) {
    // 分配成功但地址不对，释放并返回NULL
    anon_munmap(result, bytes);
    return NULL;
  }
  return result;
}

static char* anon_mmap(char* requested_addr, size_t bytes, bool exec) {
  int prot = exec ? PROT_READ|PROT_WRITE|PROT_EXEC : PROT_NONE;
  int flags = MAP_PRIVATE | MAP_NORESERVE | MAP_ANONYMOUS;
  if (requested_addr != NULL) {
    flags |= MAP_FIXED;  // 强制使用指定地址
  }
  
  char* addr = (char*)::mmap(requested_addr, bytes, prot, flags, -1, 0);
  return addr == MAP_FAILED ? NULL : addr;
}
```

---

## 四、8GB堆分配流程验证

### 4.1 理论分析

```
输入参数:
  heap_size = 8GB = 8,589,934,592 bytes
  alignment = 4MB (Region大小)
  UseCompressedOops = true
  HeapBaseMinAddress = 2GB
  
分配流程:
  1. 检查堆大小: 8GB < 32GB (OopEncodingHeapMax) ✓
  
  2. 尝试Unscaled模式:
     - 条件: 2GB + 8GB ≤ 4GB? → 10GB > 4GB ✗
     - 跳过Unscaled模式
  
  3. 尝试ZeroBased模式:
     - 条件: 2GB + 8GB ≤ 32GB? → 10GB ≤ 32GB ✓
     - 尝试地址: 从 (32GB - 8GB) 向下搜索
     - 成功分配在低于32GB的地址
  
  4. 最终结果: ZeroBased模式
```

### 4.2 JVM日志验证

```bash
$ java -Xms8g -Xmx8g -XX:+UseG1GC -Xlog:gc+heap+coops=debug -version

# 期望输出类似:
[0.003s][debug][gc,heap,coops] Trying to allocate heap at address 0x...
[0.003s][debug][gc,heap,coops] Heap address: 0x..., size: 8589934592, Compressed Oops mode: Zero based
```

### 4.3 NMT验证

```bash
$ java -Xms8g -Xmx8g -XX:+UseG1GC -XX:NativeMemoryTracking=summary -XX:+PrintNMTStatistics -version

# 输出:
-                 Java Heap (reserved=8589934592, committed=8589934592)
                            (mmap: reserved=8589934592, committed=8589934592)
```

---

## 五、压缩指针编解码实现

### 5.1 编码(64位→32位)

**源码位置**: `src/hotspot/share/oops/compressedOops.hpp`

```cpp
inline narrowOop CompressedOops::encode(oop obj) {
  assert(is_in(obj), "object not in heap");
  return (narrowOop)(obj == NULL ? NULL : 
                     (narrowOop)((uintptr_t)obj >> _shift));
}
```

### 5.2 解码(32位→64位)

```cpp
inline oop CompressedOops::decode(narrowOop v) {
  return (oop)((uintptr_t)_base + ((uintptr_t)v << _shift));
}

// ZeroBased模式: _base = 0, _shift = 3
// 解码: oop = 0 + (v << 3) = v << 3

// Unscaled模式: _base = 0, _shift = 0
// 解码: oop = 0 + (v << 0) = v

// HeapBased模式: _base ≠ 0, _shift = 3
// 解码: oop = base + (v << 3)
```

### 5.3 汇编级别实现

**ZeroBased模式解码汇编** (x86_64):
```asm
; narrowOop在r10d中，结果放入rax
shl     r10, 3          ; 左移3位 (乘以8)
mov     rax, r10        ; 结果就是64位地址
```

**HeapBased模式解码汇编**:
```asm
; narrowOop在r10d中，结果放入rax
movabs  r11, <heap_base>  ; 加载堆基址
shl     r10, 3            ; 左移3位
add     rax, r10, r11     ; 加上基址
```

---

## 六、8GB堆地址空间布局图

```
虚拟地址空间 (非大页，ZeroBased压缩指针模式)
┌─────────────────────────────────────────────────────────────────────────────┐
│ 0x0000_0000_0000_0000                                                       │
│     ↓                                                                       │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ NULL区域 (0 - 4KB)                                                    │   │
│ │ 用于空指针检测                                                         │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ 程序代码段 (.text)                                                    │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ 程序数据段 (.data, .bss)                                              │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ C Heap (malloc使用)                                                   │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ 0x0000_0000_8000_0000 (2GB) - HeapBaseMinAddress                            │
│     ↓                                                                       │
│ ╔═══════════════════════════════════════════════════════════════════════╗   │
│ ║                                                                       ║   │
│ ║                    Java Heap (8GB)                                    ║   │
│ ║                                                                       ║   │
│ ║  ┌─────────────────────────────────────────────────────────────────┐  ║   │
│ ║  │ Region 0 (4MB)                                                  │  ║   │
│ ║  ├─────────────────────────────────────────────────────────────────┤  ║   │
│ ║  │ Region 1 (4MB)                                                  │  ║   │
│ ║  ├─────────────────────────────────────────────────────────────────┤  ║   │
│ ║  │ ...                                                             │  ║   │
│ ║  ├─────────────────────────────────────────────────────────────────┤  ║   │
│ ║  │ Region 2047 (4MB)                                               │  ║   │
│ ║  └─────────────────────────────────────────────────────────────────┘  ║   │
│ ║                                                                       ║   │
│ ╚═══════════════════════════════════════════════════════════════════════╝   │
│ 0x0000_0002_8000_0000 (10GB) - Heap End                                     │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ Metaspace (Non-Class)                                                 │   │
│ │ Reserved: ~8MB, Committed: ~6MB                                       │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ Compressed Class Space                                                │   │
│ │ Reserved: 1GB, Committed: ~640KB                                      │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ Code Cache                                                            │   │
│ │ Reserved: ~240MB, Committed: ~9MB                                     │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ ┌───────────────────────────────────────────────────────────────────────┐   │
│ │ Thread Stacks (22 threads × ~1MB)                                     │   │
│ └───────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│ 0x0000_7FFF_FFFF_FFFF (用户空间上限)                                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 七、辅助数据结构映射器

### 7.1 G1RegionToSpaceMapper

**源码位置**: `src/hotspot/share/gc/g1/g1RegionToSpaceMapper.cpp`

```cpp
G1RegionToSpaceMapper* G1RegionToSpaceMapper::create_mapper(ReservedSpace rs,
                                                             size_t actual_size,
                                                             size_t page_size,
                                                             size_t region_granularity,
                                                             size_t commit_factor,
                                                             MemoryType type) {
  // commit_factor决定提交粒度
  // 1: 整个ReservedSpace一次性提交
  // >1: 按Region粒度提交
  
  if (region_granularity >= page_size) {
    return new G1RegionsLargerThanCommitSizeMapper(rs, actual_size, page_size, 
                                                   region_granularity, commit_factor, type);
  } else {
    return new G1RegionsSmallerThanCommitSizeMapper(rs, actual_size, page_size, 
                                                    region_granularity, commit_factor, type);
  }
}
```

### 7.2 辅助数据结构创建

**源码位置**: `src/hotspot/share/gc/g1/g1CollectedHeap.cpp:1719-1782`

```cpp
// 1. 堆内存映射器
G1RegionToSpaceMapper* heap_storage =
  G1RegionToSpaceMapper::create_mapper(g1_rs,              // 预留的虚拟地址空间
                                       g1_rs.size(),       // 实际使用大小(8GB)
                                       page_size,          // 页面大小(4KB)
                                       HeapRegion::GrainBytes,  // Region大小(4MB)
                                       1,                  // commit_factor
                                       mtJavaHeap);        // NMT类型

// 2. BOT映射器
G1RegionToSpaceMapper* bot_storage =
  create_aux_memory_mapper("Block Offset Table",
                           G1BlockOffsetTable::compute_size(g1_rs.size() / HeapWordSize),  // 16MB
                           G1BlockOffsetTable::heap_map_factor());  // 512

// 3. CardTable映射器
G1RegionToSpaceMapper* cardtable_storage =
  create_aux_memory_mapper("Card Table",
                           G1CardTable::compute_size(g1_rs.size() / HeapWordSize),  // 16MB
                           G1CardTable::heap_map_factor());  // 512

// 4. Card Counts映射器
G1RegionToSpaceMapper* card_counts_storage =
  create_aux_memory_mapper("Card Counts Table",
                           G1CardCounts::compute_size(g1_rs.size() / HeapWordSize),  // 16MB
                           G1CardCounts::heap_map_factor());  // 512

// 5&6. CMBitMap映射器
size_t bitmap_size = G1CMBitMap::compute_size(g1_rs.size());  // 16MB
G1RegionToSpaceMapper* prev_bitmap_storage =
  create_aux_memory_mapper("Prev Bitmap", bitmap_size, G1CMBitMap::heap_map_factor());
G1RegionToSpaceMapper* next_bitmap_storage =
  create_aux_memory_mapper("Next Bitmap", bitmap_size, G1CMBitMap::heap_map_factor());
```

---

## 八、内存提交(Commit)流程

### 8.1 expand()方法

**源码位置**: `src/hotspot/share/gc/g1/g1CollectedHeap.cpp:1901-1913`

```cpp
// 初始化时调用expand提交初始堆内存
if (!expand(init_byte_size, _workers)) {
  vm_shutdown_during_initialization("Failed to allocate initial heap.");
  return JNI_ENOMEM;
}
```

### 8.2 HeapRegionManager::expand_by()

```cpp
uint HeapRegionManager::expand_by(uint num_regions, WorkGang* pretouch_workers) {
  // 1. 预留新Region的虚拟地址空间(已在构造时完成)
  // 2. 提交物理内存
  // 3. 创建HeapRegion对象
  // 4. 初始化辅助数据结构
  
  for (uint i = 0; i < num_regions; i++) {
    commit_regions(current_index, 1, pretouch_workers);
    HeapRegion* hr = new_heap_region(current_index, mem_region);
    _regions.set(current_index, hr);
    _available_map.at_put(current_index, true);
  }
}
```

### 8.3 Linux commit_memory

```cpp
bool os::commit_memory(char* addr, size_t size, bool exec) {
  int prot = exec ? PROT_READ|PROT_WRITE|PROT_EXEC : PROT_READ|PROT_WRITE;
  
  // 使用MAP_FIXED覆盖之前PROT_NONE的映射
  uintptr_t res = (uintptr_t)::mmap(addr, size, prot,
                                     MAP_PRIVATE|MAP_FIXED|MAP_ANONYMOUS, -1, 0);
  
  return res != (uintptr_t)MAP_FAILED;
}
```

---

## 九、关键参数源码索引

| 参数 | 值 | 源码位置 |
|------|-----|---------|
| HeapBaseMinAddress | 2GB | arguments.cpp (pd_default) |
| UnscaledOopHeapMax | 4GB | compressedOops.hpp |
| OopEncodingHeapMax | 32GB | compressedOops.hpp |
| TARGET_REGION_NUMBER | 2048 | heapRegionBounds.hpp:46 |
| MIN_REGION_SIZE | 1MB | heapRegionBounds.hpp:35 |
| MAX_REGION_SIZE | 32MB | heapRegionBounds.hpp:42 |
| card_shift | 9 | cardTable.hpp:234 |
| card_size | 512B | cardTable.hpp:235 |
| LogN (BOT) | 9 | blockOffsetTable.hpp:52 |
| mark_distance | 64B | g1ConcurrentMarkBitMap.cpp:43 |

---

## 十、验证脚本

### 10.1 压缩指针模式验证

```java
public class CompressedOopsVerify {
    public static void main(String[] args) {
        // 获取运行时参数
        System.out.println("Heap Size: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 / 1024 + " GB");
        
        // 通过JMX获取更详细信息
        for (java.lang.management.MemoryPoolMXBean pool : 
             java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            System.out.println("Pool: " + pool.getName() + 
                             ", Max: " + pool.getUsage().getMax() / 1024 / 1024 + " MB");
        }
    }
}
```

### 10.2 GDB验证脚本

```gdb
# jvm_heap_verify.gdb
set pagination off

# 断点在堆预留函数
break ReservedHeapSpace::try_reserve_heap
commands
  printf "\n=== 尝试预留堆内存 ===\n"
  printf "请求大小: %lu MB\n", size/1024/1024
  printf "期望地址: %p\n", requested_address
  printf "对齐要求: %lu\n", alignment
  continue
end

# 断点在压缩指针初始化
break ReservedHeapSpace::initialize_compressed_heap
commands
  printf "\n=== 压缩指针堆初始化 ===\n"
  printf "堆大小: %lu GB\n", size/1024/1024/1024
  printf "使用大页: %d\n", large
  continue
end

run -Xms8g -Xmx8g -XX:+UseG1GC -version
quit
```
