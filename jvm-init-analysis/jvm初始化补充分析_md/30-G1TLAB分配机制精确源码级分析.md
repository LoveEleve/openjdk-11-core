# G1 TLAB分配机制精确源码级分析

> **目标**: 精确分析TLAB(Thread Local Allocation Buffer)的分配机制和参数计算
> **环境**: -Xms8g -Xmx8g -XX:+UseG1GC, 64位Linux

## 一、TLAB核心数据结构

### 1.1 源码定义

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp`

```cpp:50:72:src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp
HeapWord* _start;                    // TLAB起始地址
HeapWord* _top;                      // 当前分配指针(下一个对象分配位置)
HeapWord* _pf_top;                   // 预取水位线
HeapWord* _end;                      // 分配终点(可能是采样点)
HeapWord* _allocation_end;           // 实际TLAB终点

size_t    _desired_size;             // 期望大小(含alignment_reserve)
size_t    _refill_waste_limit;       // refill浪费阈值

static size_t   _max_size;           // 最大TLAB大小
static int      _reserve_for_allocation_prefetch;  // 预取预留空间
static unsigned _target_refills;     // GC间期目标refill次数
```

### 1.2 内存布局图解

```
TLAB内存布局 (从低地址到高地址):

_start                                      _allocation_end  hard_end
   |                                              |              |
   v                                              v              v
   +------------------+----------------+----------+--------------+
   |   已分配对象     |   当前对象     |  剩余空间 | align_reserve |
   +------------------+----------------+----------+--------------+
                      ^                ^
                      |                |
                    _top             _end (可能等于_allocation_end或采样点)

hard_end = _allocation_end + alignment_reserve()
实际可用空间 = [_start, _allocation_end)
alignment_reserve用于TLAB结束时填充dummy对象
```

---

## 二、TLAB参数精确定义

### 2.1 JVM参数默认值

**文件**: `src/hotspot/share/gc/shared/gc_globals.hpp`

```cpp:753:788:src/hotspot/share/gc/shared/gc_globals.hpp
product(size_t, MinTLABSize, 2*K,                        // 最小TLAB: 2KB
        "Minimum allowed TLAB size (in bytes)")

product(size_t, TLABSize, 0,                             // 起始大小: 0表示自动
        "Starting TLAB size (in bytes)")

product(uintx, TLABAllocationWeight, 35,                 // 分配权重
        "Allocation averaging weight")

product(uintx, TLABWasteTargetPercent, 1,                // 浪费目标: 1%
        "Percentage of Eden that can be wasted")

product(uintx, TLABRefillWasteFraction, 64,              // refill浪费比例
        "Maximum TLAB waste at a refill")

product(uintx, TLABWasteIncrement, 4,                    // 慢分配增量
        "Increment allowed waste at slow allocation")
```

### 2.2 目标Refill次数计算

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp`

```cpp:226:234:src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp
void ThreadLocalAllocBuffer::startup_initialization() {
  // 目标refill次数 = 100 / (2 * TLABWasteTargetPercent)
  _target_refills = 100 / (2 * TLABWasteTargetPercent);
  // 默认: 100 / (2 * 1) = 50
  _target_refills = MAX2(_target_refills, 2U);  // 最少2次
}
```

**精确计算**:
```
TLABWasteTargetPercent = 1 (默认)
_target_refills = 100 / (2 * 1) = 50 次/GC周期
```

---

## 三、TLAB大小精确计算

### 3.1 初始期望大小

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp`

```cpp:270:285:src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp
size_t ThreadLocalAllocBuffer::initial_desired_size() {
  size_t init_sz = 0;
  
  if (TLABSize > 0) {
    // 用户指定了TLABSize
    init_sz = TLABSize / HeapWordSize;
  } else if (global_stats() != NULL) {
    // 自动计算
    unsigned nof_threads = global_stats()->allocating_threads_avg();
    // init_sz = Eden容量 / (线程数 * 目标refill次数)
    init_sz = (Universe::heap()->tlab_capacity(myThread()) / HeapWordSize) /
              (nof_threads * target_refills());
    init_sz = align_object_size(init_sz);
  }
  
  // 边界检查
  init_sz = MIN2(MAX2(init_sz, min_size()), max_size());
  return init_sz;
}
```

### 3.2 8GB G1堆下的TLAB大小计算

```
假设条件:
- Eden比例: 默认约60% of Young Gen
- Young Gen: 默认约2048MB (8GB * 25%)  
- Eden: 约1800MB
- 线程数: 假设8个分配线程
- target_refills: 50

计算:
init_sz = 1800MB / (8 * 50)
        = 1800MB / 400
        = 4.5MB
        = 4,718,592 bytes
        = 589,824 words

由于max_size限制(通常是Region/8 = 512KB):
实际init_sz = min(4.5MB, 512KB) = 512KB = 65,536 words
```

### 3.3 最小大小计算

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp`

```cpp:123:src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp
static size_t min_size() { 
  return align_object_size(MinTLABSize / HeapWordSize) + alignment_reserve(); 
}
// MinTLABSize = 2KB = 2048 bytes
// MinTLABSize / HeapWordSize = 2048 / 8 = 256 words
// alignment_reserve() ≈ 2-4 words (取决于平台)
// min_size ≈ 258-260 words = 2064-2080 bytes
```

### 3.4 alignment_reserve计算

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp`

```cpp:145:150:src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp
static size_t end_reserve() {
  int reserve_size = typeArrayOopDesc::header_size(T_INT);  // int数组头
  return MAX2(reserve_size, _reserve_for_allocation_prefetch);
}
static size_t alignment_reserve() { 
  return align_object_size(end_reserve()); 
}
```

**精确计算**:
```
int数组头大小(64位):
  - Mark Word: 8 bytes
  - Klass Pointer: 4 bytes (压缩) 或 8 bytes
  - Array Length: 4 bytes
  - 总计: 16 bytes = 2 words (压缩) 或 24 bytes = 3 words

_reserve_for_allocation_prefetch (C2编译器):
  = (AllocatePrefetchDistance + AllocatePrefetchStepSize * lines) / HeapWordSize
  典型值: 约 8-16 words

alignment_reserve = align_object_size(max(2, ~12)) ≈ 2-4 words
```

---

## 四、TLAB分配快速路径

### 4.1 内联分配实现

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.inline.hpp`

```cpp:34:54:src/hotspot/share/gc/shared/threadLocalAllocBuffer.inline.hpp
inline HeapWord* ThreadLocalAllocBuffer::allocate(size_t size) {
  invariants();
  HeapWord* obj = top();
  if (pointer_delta(end(), obj) >= size) {
    // 快速路径成功! 无锁分配
    #ifdef ASSERT
    // 调试模式: 填充badHeapWordVal
    size_t hdr_size = oopDesc::header_size();
    Copy::fill_to_words(obj + hdr_size, size - hdr_size, badHeapWordVal);
    #endif
    
    // 原子操作: 移动top指针
    set_top(obj + size);
    
    invariants();
    return obj;  // 返回分配的对象地址
  }
  return NULL;  // TLAB空间不足，需要慢路径
}
```

### 4.2 分配性能特征

```
快速路径(TLAB分配):
  操作: 1次指针比较 + 1次指针加法
  同步: 无需任何锁
  耗时: ~20 纳秒
  
慢路径(需要新TLAB或直接分配):
  操作: 可能需要加锁分配新TLAB
  同步: 可能需要堆锁
  耗时: ~200-2000 纳秒
```

---

## 五、TLAB动态调整

### 5.1 动态大小调整

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp`

```cpp:150:168:src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp
void ThreadLocalAllocBuffer::resize() {
  assert(ResizeTLAB, "Should not call this otherwise");
  
  // 基于历史分配比例计算新大小
  size_t alloc = (size_t)(_allocation_fraction.average() *
                          (Universe::heap()->tlab_capacity(myThread()) / HeapWordSize));
  size_t new_size = alloc / _target_refills;
  
  // 边界检查
  new_size = MIN2(MAX2(new_size, min_size()), max_size());
  
  // 对齐
  size_t aligned_new_size = align_object_size(new_size);
  
  log_trace(gc, tlab)("TLAB new size: ... desired_size: " SIZE_FORMAT " -> " SIZE_FORMAT,
                      desired_size(), aligned_new_size);
  
  set_desired_size(aligned_new_size);
  set_refill_waste_limit(initial_refill_waste_limit());
}
```

### 5.2 refill_waste_limit机制

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp`

```cpp:85:src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp
size_t initial_refill_waste_limit() { 
  return desired_size() / TLABRefillWasteFraction; 
}
// 默认: desired_size / 64
// 例如: 512KB / 64 = 8KB
```

**动态增加机制**:
```cpp:87:src/hotspot/share/gc/shared/threadLocalAllocBuffer.inline.hpp
void ThreadLocalAllocBuffer::record_slow_allocation(size_t obj_size) {
  // 每次慢分配后增加waste limit
  set_refill_waste_limit(refill_waste_limit() + refill_waste_limit_increment());
  // TLABWasteIncrement = 4 words = 32 bytes
}
```

---

## 六、G1特有的TLAB处理

### 6.1 G1 TLAB最大大小限制

**文件**: `src/hotspot/share/gc/g1/g1Allocator.cpp`

```cpp:154:src/hotspot/share/gc/g1/g1Allocator.cpp
return MIN2(MAX2(hr->free(), (size_t) MinTLABSize), max_tlab);
// max_tlab通常 = Region大小 / 8 = 4MB / 8 = 512KB
```

### 6.2 Region剩余空间检查

**文件**: `src/hotspot/share/gc/g1/g1AllocRegion.cpp`

```cpp:277:src/hotspot/share/gc/g1/g1AllocRegion.cpp
if (free_bytes < MinTLABSize) {
  // Region剩余空间太小，无法分配新TLAB
  // 需要切换到新Region
}
```

---

## 七、TLAB浪费统计

### 7.1 浪费类型

**文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp`

```cpp:66:69:src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp
unsigned  _fast_refill_waste;   // 快速refill浪费
unsigned  _slow_refill_waste;   // 慢refill浪费  
unsigned  _gc_waste;            // GC时的浪费
unsigned  _slow_allocations;    // 慢分配次数
```

### 7.2 浪费统计计算

```cpp:71:76:src/hotspot/share/gc/shared/threadLocalAllocBuffer.cpp
void ThreadLocalAllocBuffer::accumulate_statistics() {
  _gc_waste += (unsigned)remaining();  // GC时TLAB剩余空间
  size_t total_allocated = thread->allocated_bytes();
  size_t allocated_since_last_gc = total_allocated - _allocated_before_last_gc;
}
```

---

## 八、8GB G1堆TLAB参数精确汇总

### 8.1 默认参数值

| 参数 | 默认值 | 含义 |
|------|--------|------|
| MinTLABSize | 2 KB | 最小TLAB大小 |
| TLABSize | 0 (自动) | 起始TLAB大小 |
| TLABWasteTargetPercent | 1% | Eden浪费目标 |
| TLABRefillWasteFraction | 64 | Refill浪费分数 |
| TLABWasteIncrement | 4 words | 慢分配增量 |
| TLABAllocationWeight | 35 | 分配权重 |

### 8.2 8GB堆计算值

| 参数 | 计算值 | 计算公式 |
|------|--------|----------|
| target_refills | 50 | 100 / (2 * 1) |
| min_size | ~2080 bytes | 256 words + reserve |
| max_size | 512 KB | Region / 8 |
| initial_desired_size | ~512 KB | min(Eden/threads/refills, max) |
| initial_waste_limit | 8 KB | desired / 64 |

### 8.3 典型TLAB生命周期

```
1. 线程创建时初始化:
   desired_size = initial_desired_size() ≈ 512KB
   waste_limit = 512KB / 64 = 8KB

2. 分配循环:
   if (TLAB剩余空间 >= 对象大小) {
     快速分配 (无锁)
   } else if (TLAB剩余空间 <= waste_limit) {
     丢弃TLAB，分配新TLAB (加锁)
   } else {
     直接在堆上分配 (加锁)
     waste_limit += 32 bytes
   }

3. GC时:
   累计统计信息
   调整desired_size
   重置waste_limit
```

---

## 九、性能优化建议

### 9.1 TLAB调优参数

```bash
# 固定TLAB大小(避免调整开销)
-XX:TLABSize=512k -XX:-ResizeTLAB

# 减少TLAB浪费
-XX:TLABWasteTargetPercent=2

# 允许更大TLAB(减少refill)
-XX:TLABRefillWasteFraction=32
```

### 9.2 监控TLAB使用

```bash
# 启用TLAB跟踪日志
-Xlog:gc+tlab=trace

# 输出示例:
# TLAB: fill thread: 0x00007f... desired_size: 512KB slow allocs: 2
# TLAB totals: thrds: 8 refills: 42 max: 12 waste: 1.2%
```

---

## 十、源码文件索引

| 功能 | 源码文件 |
|------|----------|
| TLAB类定义 | threadLocalAllocBuffer.hpp |
| TLAB实现 | threadLocalAllocBuffer.cpp |
| 内联分配 | threadLocalAllocBuffer.inline.hpp |
| 参数定义 | gc_globals.hpp |
| G1 TLAB | g1Allocator.cpp, g1AllocRegion.cpp |

---

**文档版本**: v1.0
**基于源码**: OpenJDK 11
**最后更新**: 2026-01-16
