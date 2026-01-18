# G1 GC 8GB堆精确参数再验证分析

## 验证环境

- **堆大小**: -Xms=8GB -Xmx=8GB
- **特性**: 非大页，非NUMA
- **JVM版本**: OpenJDK 11
- **验证日期**: 2026-01-16

---

## 一、Region大小计算精确验证

### 1.1 源码位置与算法

**文件**: `src/hotspot/share/gc/g1/heapRegion.cpp:63-110`

```cpp
void HeapRegion::setup_heap_region_size(size_t initial_heap_size, size_t max_heap_size) {
  size_t region_size = G1HeapRegionSize;
  if (FLAG_IS_DEFAULT(G1HeapRegionSize)) {
    // 计算平均堆大小
    size_t average_heap_size = (initial_heap_size + max_heap_size) / 2;
    // Region大小 = MAX(平均堆大小/2048, 1MB)
    region_size = MAX2(average_heap_size / HeapRegionBounds::target_number(),
                       HeapRegionBounds::min_size());
  }

  // 向下取整到2的幂次
  int region_size_log = log2_long((jlong) region_size);
  region_size = ((size_t)1 << region_size_log);

  // 边界检查
  if (region_size < HeapRegionBounds::min_size()) {
    region_size = HeapRegionBounds::min_size();
  } else if (region_size > HeapRegionBounds::max_size()) {
    region_size = HeapRegionBounds::max_size();
  }
  
  // 设置全局变量
  GrainBytes = region_size;
  GrainWords = GrainBytes >> LogHeapWordSize;
  CardsPerRegion = GrainBytes >> G1CardTable::card_shift;
}
```

### 1.2 8GB堆下的精确计算

```
输入参数:
  initial_heap_size = 8GB = 8,589,934,592 bytes
  max_heap_size     = 8GB = 8,589,934,592 bytes

计算过程:
  1. average_heap_size = (8GB + 8GB) / 2 = 8GB = 8,589,934,592 bytes
  
  2. region_size = MAX(8GB / 2048, 1MB)
                 = MAX(4,194,304 bytes, 1,048,576 bytes)
                 = 4,194,304 bytes = 4MB
  
  3. log2_long(4,194,304) = 22 (因为 2^22 = 4,194,304)
  
  4. region_size = 1 << 22 = 4,194,304 bytes = 4MB
  
  5. 边界检查: 1MB ≤ 4MB ≤ 32MB ✓

最终结果:
  GrainBytes      = 4,194,304 bytes (4MB)
  GrainWords      = 4,194,304 >> 3 = 524,288 words (8字节/word)
  CardsPerRegion  = 4,194,304 >> 9 = 8,192 cards
  Region数量      = 8GB / 4MB = 2,048 个
```

### 1.3 关键常量源码验证

**文件**: `src/hotspot/share/gc/g1/heapRegionBounds.hpp:31-52`

```cpp
class HeapRegionBounds : public AllStatic {
private:
  static const size_t MIN_REGION_SIZE = 1024 * 1024;        // 1MB
  static const size_t MAX_REGION_SIZE = 32 * 1024 * 1024;   // 32MB
  static const size_t TARGET_REGION_NUMBER = 2048;          // 目标2048个Region
};
```

---

## 二、辅助数据结构精确内存开销验证

### 2.1 CardTable精确计算

**源码位置**: `src/hotspot/share/gc/shared/cardTable.hpp:232-237`

```cpp
enum SomePublicConstants {
  card_shift         = 9,                          // 地址右移9位
  card_size          = 1 << card_shift,            // 512 bytes
  card_size_in_words = card_size / sizeof(HeapWord) // 64 words
};
```

**精确计算**:
```
CardTable大小计算公式 (g1CardTable.hpp:108-111):
  number_of_slots = (heap_size_in_words / card_size_in_words)
                  = (8GB / 8 bytes) / 64
                  = 1,073,741,824 / 64
                  = 16,777,216 bytes = 16MB

验证:
  heap_map_factor = card_size = 512 bytes
  意味着: 每512字节堆内存 → 1字节CardTable
  8GB / 512 = 16MB ✓
```

### 2.2 Block Offset Table (BOT) 精确计算

**源码位置**: `src/hotspot/share/gc/shared/blockOffsetTable.hpp:50-56`

```cpp
class BOTConstants : public AllStatic {
public:
  static const uint LogN = 9;                        // 512字节
  static const uint LogN_words = LogN - LogHeapWordSize; // 9 - 3 = 6
  static const uint N_bytes = 1 << LogN;             // 512 bytes
  static const uint N_words = 1 << LogN_words;       // 64 words
};
```

**精确计算** (g1BlockOffsetTable.hpp:82-85):
```cpp
static size_t compute_size(size_t mem_region_words) {
  size_t number_of_slots = (mem_region_words / BOTConstants::N_words);
  return ReservedSpace::allocation_align_size_up(number_of_slots);
}
```

```
BOT大小计算:
  mem_region_words = 8GB / 8 bytes = 1,073,741,824 words
  number_of_slots  = 1,073,741,824 / 64 = 16,777,216 bytes = 16MB
  
  heap_map_factor = N_bytes = 512 bytes
  意味着: 每512字节堆内存 → 1字节BOT条目
  8GB / 512 = 16MB ✓
```

### 2.3 Concurrent Mark Bitmap 精确计算

**源码位置**: `src/hotspot/share/gc/g1/g1ConcurrentMarkBitMap.cpp:38-44`

```cpp
size_t G1CMBitMap::compute_size(size_t heap_size) {
  return ReservedSpace::allocation_align_size_up(heap_size / mark_distance());
}

size_t G1CMBitMap::mark_distance() {
  return MinObjAlignmentInBytes * BitsPerByte;
  // = 8 * 8 = 64 bytes
}
```

**关键参数验证** (arguments.cpp:1613-1625):
```cpp
// MinObjAlignmentInBytes 默认值 = ObjectAlignmentInBytes = 8 bytes
MinObjAlignmentInBytes = ObjectAlignmentInBytes; // 8
LogMinObjAlignmentInBytes = exact_log2(ObjectAlignmentInBytes); // 3
```

**精确计算**:
```
mark_distance = 8 bytes × 8 bits = 64 bytes

单个CMBitMap大小:
  bitmap_size = 8GB / 64 = 134,217,728 bytes = 128MB
  
G1使用双缓冲机制 (prev_bitmap + next_bitmap):
  总CMBitMap内存 = 128MB × 2 = 256MB

等价理解:
  每64字节堆内存 → 1位标记
  每字节位图 → 64 × 8 = 512字节堆内存
  heap_map_factor = mark_distance = 64 bytes
```

### 2.4 Card Counts Table 精确计算

**源码位置**: `src/hotspot/share/gc/g1/g1CardCounts.cpp:40-49`

```cpp
size_t G1CardCounts::compute_size(size_t mem_region_size_in_words) {
  // 与CardTable大小完全相同
  return G1CardTable::compute_size(mem_region_size_in_words);
}

size_t G1CardCounts::heap_map_factor() {
  return G1CardTable::heap_map_factor(); // 512 bytes
}
```

```
Card Counts大小 = CardTable大小 = 16MB
```

---

## 三、Humongous对象阈值精确验证

### 3.1 阈值计算源码

**文件**: `src/hotspot/share/gc/g1/g1CollectedHeap.hpp:1219-1231`

```cpp
// 判断对象是否为Humongous
static bool is_humongous(size_t word_size) {
  // ⚠️ 关键：使用严格大于(>)，不是大于等于(>=)
  return word_size > _humongous_object_threshold_in_words;
}

// 阈值计算
static size_t humongous_threshold_for(size_t region_size) {
  return (region_size / 2);  // Region大小的一半
}
```

**初始化位置** (g1CollectedHeap.cpp:1498):
```cpp
_humongous_object_threshold_in_words = humongous_threshold_for(HeapRegion::GrainWords);
```

### 3.2 8GB堆下的精确阈值

```
阈值计算:
  GrainWords = 4MB / 8 bytes = 524,288 words
  _humongous_object_threshold_in_words = 524,288 / 2 = 262,144 words
  
字节表示:
  阈值 = 262,144 × 8 bytes = 2,097,152 bytes = 2MB

判断逻辑 (关键细节!):
  is_humongous(word_size) = (word_size > 262,144)
  
  - 对象大小 = 2MB (262,144 words): 不是Humongous ❌
  - 对象大小 > 2MB (> 262,144 words): 是Humongous ✓

⚠️ 重要结论:
  - 阈值是 Region大小/2 = 2MB
  - 判断条件是"严格大于"(>)，不是"大于等于"(>=)
  - 因此: 刚好2MB的对象 不是 Humongous
         超过2MB的对象 才是 Humongous
```

---

## 四、每个Region的内存映射精确计算

### 4.1 单Region关联的辅助数据

| 数据结构 | 计算公式 | 4MB Region的值 |
|---------|---------|---------------|
| CardTable | 4MB / 512B | 8,192 bytes (8KB) |
| BOT | 4MB / 512B | 8,192 bytes (8KB) |
| CMBitMap (单个) | 4MB / 64B | 65,536 bytes (64KB) |
| CMBitMap (双缓冲) | 64KB × 2 | 131,072 bytes (128KB) |
| Card Counts | 4MB / 512B | 8,192 bytes (8KB) |

### 4.2 每Region辅助数据总计

```
单个Region辅助数据 = 8KB + 8KB + 128KB + 8KB = 152KB
比例 = 152KB / 4MB = 3.71%
```

---

## 五、8GB堆总内存布局精确汇总

### 5.1 固定辅助数据结构

| 结构 | 大小 | 占堆比例 | 源码验证 |
|------|------|---------|---------|
| CardTable | 16 MB | 0.195% | card_size=512 ✓ |
| BOT | 16 MB | 0.195% | N_bytes=512 ✓ |
| Card Counts | 16 MB | 0.195% | 同CardTable ✓ |
| Prev CMBitMap | 128 MB | 1.56% | mark_distance=64 ✓ |
| Next CMBitMap | 128 MB | 1.56% | mark_distance=64 ✓ |
| **固定总计** | **304 MB** | **3.71%** | |

### 5.2 HeapRegion对象开销

```cpp
// HeapRegion类关键成员变量 (heapRegion.hpp:191-285)
class HeapRegion: public G1ContiguousSpace {
  HeapRegionRemSet* _rem_set;           // 8 bytes (指针)
  uint _hrm_index;                       // 4 bytes
  HeapRegionType _type;                  // 1 byte (enum)
  HeapRegion* _humongous_start_region;   // 8 bytes
  bool _evacuation_failed;               // 1 byte
  HeapRegion* _next;                     // 8 bytes
  HeapRegion* _prev;                     // 8 bytes
  size_t _prev_marked_bytes;             // 8 bytes
  size_t _next_marked_bytes;             // 8 bytes
  double _gc_efficiency;                 // 8 bytes
  int _young_index_in_cset;              // 4 bytes
  SurvRateGroup* _surv_rate_group;       // 8 bytes
  int _age_index;                        // 4 bytes
  HeapWord* _prev_top_at_mark_start;     // 8 bytes
  HeapWord* _next_top_at_mark_start;     // 8 bytes
  size_t _recorded_rs_length;            // 8 bytes
  double _predicted_elapsed_time_ms;     // 8 bytes
  // 继承自G1ContiguousSpace和父类的成员...
};
```

```
估算单个HeapRegion对象大小:
  显式成员: ~104 bytes
  继承成员 (CompactibleSpace等): ~64 bytes
  对齐和填充: ~32 bytes
  估算总计: ~200 bytes

2048个HeapRegion对象:
  2048 × 200 bytes = 409,600 bytes ≈ 400 KB
```

### 5.3 RemSet (Remembered Set) 开销

RemSet是动态增长的，初始很小，根据跨区域引用增长：

```
初始状态 (每Region):
  - SparsePRT: 很小的稀疏表
  - 估算初始: ~256 bytes/Region
  
2048个Region初始RemSet:
  2048 × 256 = 524,288 bytes ≈ 512 KB

运行时可能增长到:
  - 理论上限: 约为堆的1-2%
  - 典型值: 约为堆的0.1-0.5%
```

### 5.4 总内存开销汇总

```
固定开销:
├── CardTable:        16 MB
├── BOT:              16 MB
├── Card Counts:      16 MB
├── Prev CMBitMap:   128 MB
├── Next CMBitMap:   128 MB
└── HeapRegion对象:    ~0.4 MB
    ────────────────────────
    固定总计:         ~304.4 MB (3.72%)

可变开销 (运行时):
├── RemSet:           ~0.5 - 80 MB (取决于引用模式)
└── 其他管理结构:     ~1 MB
    ────────────────────────
    总开销范围:       ~305 - 386 MB (3.7% - 4.7%)

实际可用堆内存:       ~7,900 - 7,780 MB (95.3% - 96.3%)
```

---

## 六、关键参数精确值速查表

### 6.1 核心参数

| 参数名 | 精确值 | 源码位置 |
|-------|-------|---------|
| HeapRegion::GrainBytes | 4,194,304 (4MB) | heapRegion.cpp:97 |
| HeapRegion::GrainWords | 524,288 | heapRegion.cpp:101 |
| HeapRegion::CardsPerRegion | 8,192 | heapRegion.cpp:105 |
| HeapRegion::LogOfHRGrainBytes | 22 | heapRegion.cpp:89 |
| Region总数量 | 2,048 | 8GB / 4MB |

### 6.2 辅助数据结构映射因子

| 数据结构 | heap_map_factor | 含义 |
|---------|----------------|------|
| CardTable | 512 bytes | 每字节覆盖512字节堆 |
| BOT | 512 bytes | 每字节覆盖512字节堆 |
| CMBitMap | 64 bytes | 每位覆盖64字节堆 |
| Card Counts | 512 bytes | 每字节覆盖512字节堆 |

### 6.3 Humongous阈值

| 参数 | 值 | 备注 |
|-----|---|-----|
| 阈值 (words) | 262,144 | GrainWords / 2 |
| 阈值 (bytes) | 2,097,152 (2MB) | GrainBytes / 2 |
| 判断条件 | > (严格大于) | **不是** >= |
| 最小Humongous | 2MB + 8bytes | 刚超过阈值 |

---

## 七、验证脚本

### 7.1 Java验证代码

```java
public class G1ParameterVerification {
    public static void main(String[] args) {
        // 获取运行时参数
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        
        System.out.println("=== G1 8GB Heap Parameter Verification ===");
        System.out.println("Max Heap: " + (maxMemory / (1024*1024)) + " MB");
        
        // 计算Region大小 (假设8GB堆)
        long heapSize = 8L * 1024 * 1024 * 1024; // 8GB
        long regionSize = Math.max(heapSize / 2048, 1024 * 1024);
        
        // 向下取整到2的幂次
        int log = (int)(Math.log(regionSize) / Math.log(2));
        regionSize = 1L << log;
        
        // 边界检查
        regionSize = Math.max(regionSize, 1024 * 1024);    // min 1MB
        regionSize = Math.min(regionSize, 32 * 1024 * 1024); // max 32MB
        
        System.out.println("Region Size: " + (regionSize / (1024*1024)) + " MB");
        System.out.println("Region Count: " + (heapSize / regionSize));
        System.out.println("Cards Per Region: " + (regionSize / 512));
        
        // 辅助数据结构大小
        long cardTableSize = heapSize / 512;
        long botSize = heapSize / 512;
        long bitmapSize = heapSize / 64;
        
        System.out.println("\n=== Auxiliary Data Structures ===");
        System.out.println("CardTable: " + (cardTableSize / (1024*1024)) + " MB");
        System.out.println("BOT: " + (botSize / (1024*1024)) + " MB");
        System.out.println("CMBitMap (single): " + (bitmapSize / (1024*1024)) + " MB");
        System.out.println("CMBitMap (both): " + (2*bitmapSize / (1024*1024)) + " MB");
        
        // Humongous阈值
        long humongousThreshold = regionSize / 2;
        System.out.println("\n=== Humongous Threshold ===");
        System.out.println("Threshold: " + (humongousThreshold / (1024*1024)) + " MB");
        System.out.println("Judgment: word_size > threshold (strictly greater than)");
        
        // 总开销
        long totalFixed = cardTableSize + botSize + cardTableSize + 2*bitmapSize;
        System.out.println("\n=== Total Fixed Overhead ===");
        System.out.println("Fixed: " + (totalFixed / (1024*1024)) + " MB");
        System.out.println("Percentage: " + String.format("%.2f", 100.0 * totalFixed / heapSize) + "%");
    }
}
```

### 7.2 命令行验证

```bash
# 启动JVM并查看G1参数
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintGCDetails -Xlog:gc+heap=debug -version

# 期望输出包含:
# Heap region size: 4M
```

---

## 八、与之前分析的误差修正

### 8.1 已确认正确的参数

| 参数 | 之前分析 | 本次验证 | 状态 |
|-----|---------|---------|------|
| Region大小 | 4MB | 4MB | ✓ 正确 |
| CardsPerRegion | 8,192 | 8,192 | ✓ 正确 |
| card_size | 512B | 512B | ✓ 正确 |
| BOT N_bytes | 512B | 512B | ✓ 正确 |
| mark_distance | 64B | 64B | ✓ 正确 |

### 8.2 需要注意的细节

1. **Humongous判断**: 使用`>`(严格大于)，**不是**`>=`(大于等于)
   - 2MB对象: **不是**Humongous
   - 2MB+1byte对象: **是**Humongous

2. **CMBitMap总大小**: 
   - 之前可能记为16MB是错误的
   - 正确值: 128MB × 2 = 256MB (双缓冲)

3. **辅助数据总开销**:
   - 之前分析: ~71MB (0.87%)
   - 修正后: ~304MB (3.71%) - 主要是CMBitMap计算错误

---

## 九、结论

本次再验证通过直接源码追踪，确认了8GB G1堆的所有关键参数：

1. **Region大小**: 4MB (2048个Region)
2. **CardTable**: 16MB (512字节/卡)
3. **BOT**: 16MB (512字节/条目)
4. **CMBitMap**: 256MB (64字节/位，双缓冲)
5. **Card Counts**: 16MB
6. **Humongous阈值**: >2MB (严格大于)
7. **固定开销**: ~304MB (3.71%)

所有参数均通过源码直接验证，误差已最小化。
