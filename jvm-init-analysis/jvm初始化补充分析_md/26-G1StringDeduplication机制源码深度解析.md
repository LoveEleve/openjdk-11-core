# G1 StringDeduplication机制源码深度解析

## 概述

String Deduplication是G1 GC的一个重要优化特性，旨在通过去重相同内容的String对象来减少堆内存使用。该机制通过共享相同的字符数组来实现内存节省，对于包含大量重复字符串的应用具有显著的内存优化效果。本文基于OpenJDK11源码深入分析G1 StringDeduplication的实现机制。

## 核心架构设计

### 1. StringDeduplication工作原理

```cpp
// src/hotspot/share/gc/shared/stringdedup/stringDedup.hpp:28-63
//
// String Deduplication
//
// String deduplication aims to reduce the heap live-set by deduplicating identical
// instances of String so that they share the same backing character array.
//
// The deduplication process is divided in two main parts:
// 1) finding the objects to deduplicate
// 2) deduplicating those objects
//
// The first part is done as part of a normal GC cycle when objects are marked or 
// evacuated. At this time a check is applied on each object to check if it is a 
// candidate for deduplication. If so, the object is placed on the deduplication 
// queue for later processing.
//
// The second part, processing the objects on the deduplication queue, is a 
// concurrent phase which starts right after the stop-the-world marking/evacuation 
// phase. This phase is executed by the deduplication thread.
```

**核心思想**：
- **两阶段处理**：候选对象识别 + 并发去重处理
- **共享字符数组**：多个String对象共享相同的char[]
- **哈希表管理**：使用去重哈希表跟踪唯一字符数组
- **并发执行**：去重处理在GC后并发执行

### 2. StringDedup基类设计

```cpp
// src/hotspot/share/gc/shared/stringdedup/stringDedup.hpp:77
class StringDedup : public AllStatic {
private:
  static bool _enabled;  // 去重功能是否启用

public:
  static bool is_enabled() { return _enabled; }
  
  // 立即去重指定的String对象（绕过去重队列）
  static void deduplicate(oop java_string);
  
  // 并行unlink操作
  static void parallel_unlink(StringDedupUnlinkOrOopsDoClosure* unlink, uint worker_id);
  
  // GC支持
  static void gc_prologue(bool resize_and_rehash_table);
  static void gc_epilogue();
  
protected:
  // 初始化字符串去重
  template <typename Q, typename S>
  static void initialize_impl();
};
```

### 3. G1StringDedup实现

```cpp
// src/hotspot/share/gc/g1/g1StringDedup.hpp:63
class G1StringDedup : public StringDedup {
private:
  // 候选对象选择策略
  static bool is_candidate_from_mark(oop obj);
  static bool is_candidate_from_evacuation(bool from_young, bool to_young, oop obj);

public:
  // 从标记阶段入队去重候选对象
  static void enqueue_from_mark(oop java_string, uint worker_id);
  
  // 从疏散阶段入队去重候选对象
  static void enqueue_from_evacuation(bool from_young, bool to_young,
                                      unsigned int queue, oop java_string);
  
  // 并行unlink和oops_do操作
  static void parallel_unlink(G1StringDedupUnlinkOrOopsDoClosure* unlink, uint worker_id);
  static void unlink_or_oops_do(BoolObjectClosure* is_alive, OopClosure* keep_alive,
                                bool allow_resize_and_rehash, G1GCPhaseTimes* phase_times = NULL);
};
```

## G1候选对象选择策略

### 1. 候选对象选择标准

```cpp
// src/hotspot/share/gc/g1/g1StringDedup.hpp:31-48
//
// G1 string deduplication candidate selection
//
// An object is considered a deduplication candidate if all of the following
// statements are true:
//
// - The object is an instance of java.lang.String
//
// - The object is being evacuated from a young heap region
//
// - The object is being evacuated to a young/survivor heap region and the
//   object's age is equal to the deduplication age threshold
//
//   or
//
//   The object is being evacuated to an old heap region and the object's age is
//   less than the deduplication age threshold
//
// Once an string object has been promoted to an old region, or its age is higher
// than the deduplication age threshold, is will never become a candidate again.
```

**选择条件**：
1. **类型检查**：必须是`java.lang.String`实例
2. **疏散源**：必须从年轻代Region疏散
3. **年龄条件**：
   - 疏散到年轻代/Survivor：年龄等于去重年龄阈值
   - 疏散到老年代：年龄小于去重年龄阈值

### 2. 候选对象入队机制

```cpp
// G1StringDedup候选对象入队实现
static void G1StringDedup::enqueue_from_evacuation(bool from_young, bool to_young,
                                                   unsigned int queue, oop java_string) {
  assert(is_enabled(), "String deduplication not enabled");
  
  if (is_candidate_from_evacuation(from_young, to_young, java_string)) {
    // 将候选对象加入指定队列
    G1StringDedupQueue::push(queue, java_string);
  }
}

static bool G1StringDedup::is_candidate_from_evacuation(bool from_young, bool to_young, oop obj) {
  if (java_lang_String::is_instance(obj)) {
    bool from_young_satisfied = from_young;
    bool age_satisfied = false;
    
    if (to_young) {
      // 疏散到年轻代：年龄必须等于阈值
      age_satisfied = (obj->age() == StringDeduplicationAgeThreshold);
    } else {
      // 疏散到老年代：年龄必须小于阈值
      age_satisfied = (obj->age() < StringDeduplicationAgeThreshold);
    }
    
    return from_young_satisfied && age_satisfied;
  }
  
  return false;
}
```

## StringDedupTable哈希表设计

### 1. 表项结构

```cpp
// src/hotspot/share/gc/shared/stringdedup/stringDedupTable.hpp:39
class StringDedupEntry : public CHeapObj<mtGC> {
private:
  StringDedupEntry* _next;      // 链表指针（处理哈希冲突）
  unsigned int      _hash;      // 哈希值
  bool              _latin1;    // 是否为Latin1编码
  typeArrayOop      _obj;       // 弱引用字符数组

public:
  unsigned int hash() { return _hash; }
  bool latin1() { return _latin1; }
  typeArrayOop obj() { return _obj; }
  
  // 链表操作
  StringDedupEntry* next() { return _next; }
  void set_next(StringDedupEntry* next) { _next = next; }
};
```

### 2. 哈希表核心结构

```cpp
// src/hotspot/share/gc/shared/stringdedup/stringDedupTable.hpp:114
class StringDedupTable : public CHeapObj<mtGC> {
private:
  static StringDedupTable*        _table;           // 当前活跃的哈希表实例
  static StringDedupEntryCache*   _entry_cache;     // 表项缓存

  StringDedupEntry**              _buckets;         // 哈希桶数组
  size_t                          _size;            // 表大小
  uintx                           _entries;         // 表项数量
  uintx                           _shrink_threshold; // 收缩阈值
  uintx                           _grow_threshold;   // 扩展阈值
  bool                            _rehash_needed;   // 是否需要重哈希
  uint64_t                        _hash_seed;       // 哈希种子

  // 常量配置
  static const size_t             _min_size;        // 最小表大小
  static const size_t             _max_size;        // 最大表大小
  static const double             _grow_load_factor;   // 扩展负载因子
  static const double             _shrink_load_factor; // 收缩负载因子
  static const uintx              _rehash_multiple;    // 重哈希倍数
  static const uintx              _rehash_threshold;   // 重哈希阈值
};
```

### 3. 查找或插入操作

```cpp
// 线程安全的查找或添加操作
static typeArrayOop lookup_or_add(typeArrayOop value, bool latin1, unsigned int hash) {
  // 保护表免受并发访问，同时作为_table的内存屏障
  MutexLockerEx ml(StringDedupTable_lock, Mutex::_no_safepoint_check_flag);
  return _table->lookup_or_add_inner(value, latin1, hash);
}

typeArrayOop StringDedupTable::lookup_or_add_inner(typeArrayOop value, bool latin1, unsigned int hash) {
  size_t index = hash_to_index(hash);
  StringDedupEntry** list = bucket(index);
  uintx count = 0;
  
  // 首先尝试查找现有条目
  typeArrayOop existing_value = lookup(value, latin1, hash, list, count);
  if (existing_value != NULL) {
    return existing_value;  // 找到现有的，返回共享数组
  }
  
  // 没找到，添加新条目
  add(value, latin1, hash, list);
  return value;  // 返回原数组
}
```

## 去重队列管理

### 1. StringDedupQueue设计

```cpp
// src/hotspot/share/gc/shared/stringdedup/stringDedupQueue.hpp:53
class StringDedupQueue : public CHeapObj<mtGC> {
protected:
  StringDedupQueue();

public:
  // 将String对象推入队列
  virtual void push(uint worker_id, oop java_string) = 0;
  
  // 从队列弹出String对象
  virtual oop pop() = 0;
  
  // 队列大小
  virtual size_t size() = 0;
  
  // 并行unlink操作
  virtual void parallel_unlink(StringDedupUnlinkOrOopsDoClosure* unlink, uint worker_id) = 0;
};
```

### 2. G1StringDedupQueue实现

```cpp
// G1特定的去重队列实现
class G1StringDedupQueue : public StringDedupQueue {
private:
  // 多个队列支持并行入队
  static const size_t      _num_queues = 1;
  static G1StringDedupQueue* _queues[_num_queues];
  
  // 队列状态
  oop*                     _buffer;
  size_t                   _cursor;
  size_t                   _capacity;
  
public:
  static void push(uint queue, oop java_string) {
    assert(queue < _num_queues, "Invalid queue");
    _queues[queue]->push_impl(java_string);
  }
  
  static oop pop() {
    // 轮询所有队列
    for (size_t i = 0; i < _num_queues; i++) {
      oop obj = _queues[i]->pop_impl();
      if (obj != NULL) {
        return obj;
      }
    }
    return NULL;
  }
};
```

## 并发去重线程

### 1. StringDedupThread设计

```cpp
// src/hotspot/share/gc/shared/stringdedup/stringDedupThread.hpp:39
class StringDedupThread: public ConcurrentGCThread {
protected:
  StringDedupThread();
  
public:
  virtual void run_service() = 0;
  virtual void stop_service() = 0;
  
  // 去重统计
  static void print_statistics();
};

class StringDedupThreadImpl : public StringDedupThread {
public:
  virtual void run_service();
  virtual void stop_service();
  
private:
  void deduplicate_shared_strings(StringDedupStat* stat);
};
```

### 2. 去重处理主循环

```cpp
void StringDedupThreadImpl::run_service() {
  StringDedupStat total_stat;
  
  while (!should_terminate()) {
    StringDedupStat stat;
    
    {
      // 等待GC完成
      MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
      while (StringDedupQueue::size() == 0 && !should_terminate()) {
        ml.wait(Mutex::_no_safepoint_check_flag);
      }
    }
    
    if (should_terminate()) {
      break;
    }
    
    // 处理队列中的String对象
    stat.mark_exec();
    
    for (;;) {
      oop java_string = StringDedupQueue::pop();
      if (java_string == NULL) {
        break;
      }
      
      stat.inc_inspected();
      
      // 执行实际的去重操作
      typeArrayOop value = java_lang_String::value(java_string);
      if (value != NULL) {
        bool latin1 = java_lang_String::is_latin1(java_string);
        unsigned int hash = java_lang_String::hash_code(java_string);
        
        typeArrayOop existing_value = StringDedupTable::lookup_or_add(value, latin1, hash);
        if (existing_value != value) {
          // 找到了重复的字符数组，进行去重
          java_lang_String::set_value(java_string, existing_value);
          stat.inc_deduped();
        } else {
          stat.inc_new();
        }
      }
    }
    
    stat.mark_done();
    total_stat.add(stat);
    
    // 定期打印统计信息
    if (stat.elapsed_time() > 0) {
      stat.print_statistics(false);
    }
  }
  
  total_stat.print_statistics(true);
}
```

## 8GB堆环境下的StringDeduplication行为分析

### 1. 默认配置参数

```bash
# G1 StringDeduplication默认配置
-XX:+UseG1GC
-XX:+UseStringDeduplication          # 启用字符串去重
-XX:StringDeduplicationAgeThreshold=3 # 去重年龄阈值
-XX:+PrintStringDeduplicationStatistics # 打印去重统计

# 相关内存配置
-Xms8g -Xmx8g                       # 8GB堆
-XX:G1HeapRegionSize=4m             # 4MB Region
```

### 2. 实际运行时行为

```
8GB G1堆StringDeduplication行为：
├── 候选对象识别: Young GC期间
├── 队列容量: ~1000个String对象
├── 去重线程: 1个并发线程
├── 哈希表大小: 动态调整(1K-1M条目)
├── 处理延迟: <10ms
└── 内存节省: 10-40%（取决于应用）
```

### 3. 性能特征分析

```
StringDeduplication性能数据：
├── 候选对象识别开销: <1% GC时间
├── 去重处理吞吐量: ~10000 strings/s
├── 哈希表查找时间: O(1) 平均
├── 内存开销: <1% 堆大小
├── 并发处理延迟: <50ms
└── 总体性能影响: <2%
```

## 调试和验证工具

### 1. StringDeduplication监控脚本

```bash
#!/bin/bash
# monitor_string_dedup.sh

echo "=== G1 StringDeduplication监控 ==="

# 启用字符串去重和详细日志
JAVA_OPTS="-XX:+UseG1GC -Xms8g -Xmx8g"
JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication"
JAVA_OPTS="$JAVA_OPTS -XX:+PrintStringDeduplicationStatistics"
JAVA_OPTS="$JAVA_OPTS -Xlog:stringdedup:string-dedup.log:time"

# 运行测试程序
java $JAVA_OPTS StringDedupTest &
PID=$!

# 实时监控去重统计
while kill -0 $PID 2>/dev/null; do
  echo "--- $(date) ---"
  
  # 解析去重统计信息
  tail -n 20 string-dedup.log | grep -E "(Inspected|Deduplicated|New)" | tail -3
  
  # 显示内存使用情况
  jstat -gc $PID | tail -1 | awk '{printf "堆使用: %.1fMB, Young: %.1fMB, Old: %.1fMB\n", ($3+$4+$6+$8)/1024, ($1+$2)/1024, ($7+$8)/1024}'
  
  echo ""
  sleep 5
done

echo "StringDeduplication监控完成"
```

### 2. 字符串去重测试程序

```java
// StringDedupTest.java
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class StringDedupTest {
    private static final String[] TEMPLATES = {
        "Hello World", "Java String", "Deduplication Test",
        "OpenJDK G1 GC", "Memory Optimization", "Performance Test"
    };
    
    private static List<String> strings = new ArrayList<>();
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== G1 StringDeduplication验证测试 ===");
        
        // 测试不同的字符串模式
        testDuplicateStrings();
        testUniqueStrings();
        testMixedStrings();
        
        System.out.println("测试完成");
    }
    
    private static void testDuplicateStrings() throws InterruptedException {
        System.out.println("\n--- 重复字符串测试 ---");
        
        long startTime = System.currentTimeMillis();
        
        // 创建大量重复的字符串
        for (int i = 0; i < 100000; i++) {
            String template = TEMPLATES[i % TEMPLATES.length];
            // 通过字符串连接创建新的String对象
            String duplicateString = new String(template + "");
            strings.add(duplicateString);
            
            if (i % 10000 == 0) {
                System.out.printf("创建了 %d 个字符串\n", i);
                // 触发GC以促进去重
                if (i % 20000 == 0) {
                    System.gc();
                    Thread.sleep(100);
                }
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.printf("重复字符串创建耗时: %dms\n", duration);
        
        // 等待去重处理完成
        Thread.sleep(2000);
        printMemoryUsage();
    }
    
    private static void testUniqueStrings() throws InterruptedException {
        System.out.println("\n--- 唯一字符串测试 ---");
        
        strings.clear();
        System.gc();
        Thread.sleep(1000);
        
        // 创建唯一的字符串
        for (int i = 0; i < 50000; i++) {
            String uniqueString = "Unique_String_" + i + "_" + 
                ThreadLocalRandom.current().nextInt(1000000);
            strings.add(uniqueString);
            
            if (i % 10000 == 0) {
                System.out.printf("创建了 %d 个唯一字符串\n", i);
            }
        }
        
        Thread.sleep(2000);
        printMemoryUsage();
    }
    
    private static void testMixedStrings() throws InterruptedException {
        System.out.println("\n--- 混合字符串测试 ---");
        
        strings.clear();
        System.gc();
        Thread.sleep(1000);
        
        // 混合创建重复和唯一字符串
        for (int i = 0; i < 80000; i++) {
            String str;
            if (i % 3 == 0) {
                // 33%重复字符串
                str = new String(TEMPLATES[i % TEMPLATES.length]);
            } else {
                // 67%唯一字符串
                str = "Mixed_" + i + "_" + (i % 1000);
            }
            strings.add(str);
            
            if (i % 15000 == 0) {
                System.gc();
                Thread.sleep(50);
            }
        }
        
        Thread.sleep(3000);
        printMemoryUsage();
    }
    
    private static void printMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        System.out.printf("内存使用: %dMB / %dMB, 字符串数量: %d\n",
            usedMemory / 1024 / 1024,
            totalMemory / 1024 / 1024,
            strings.size());
    }
}
```

### 3. 去重效果分析脚本

```python
#!/usr/bin/env python3
# analyze_string_dedup.py

import re
import sys
from datetime import datetime

def parse_dedup_log(log_file):
    """解析字符串去重日志"""
    
    dedup_events = []
    
    with open(log_file, 'r') as f:
        for line in f:
            # 解析去重统计信息
            if 'Concurrent String Deduplication' in line:
                event = parse_dedup_event(line)
                if event:
                    dedup_events.append(event)
    
    return dedup_events

def parse_dedup_event(line):
    """解析单个去重事件"""
    
    # 提取关键统计数据
    inspected_match = re.search(r'Inspected:\s*(\d+)', line)
    deduped_match = re.search(r'Deduplicated:\s*(\d+)', line)
    new_match = re.search(r'New:\s*(\d+)', line)
    time_match = re.search(r'(\d+\.\d+)ms', line)
    
    if inspected_match and deduped_match and new_match and time_match:
        return {
            'inspected': int(inspected_match.group(1)),
            'deduplicated': int(deduped_match.group(1)),
            'new': int(new_match.group(1)),
            'time_ms': float(time_match.group(1))
        }
    
    return None

def analyze_dedup_effectiveness(events):
    """分析去重效果"""
    
    if not events:
        print("没有找到去重事件")
        return
    
    print("=== G1 StringDeduplication效果分析报告 ===\n")
    
    # 累计统计
    total_inspected = sum(e['inspected'] for e in events)
    total_deduplicated = sum(e['deduplicated'] for e in events)
    total_new = sum(e['new'] for e in events)
    total_time = sum(e['time_ms'] for e in events)
    
    print(f"去重事件总数: {len(events)}")
    print(f"检查字符串总数: {total_inspected:,}")
    print(f"成功去重总数: {total_deduplicated:,}")
    print(f"新增字符串总数: {total_new:,}")
    print(f"总处理时间: {total_time:.2f}ms")
    
    # 效果分析
    if total_inspected > 0:
        dedup_rate = (total_deduplicated / total_inspected) * 100
        print(f"\n去重效果:")
        print(f"  去重成功率: {dedup_rate:.1f}%")
        print(f"  新字符串率: {(total_new / total_inspected) * 100:.1f}%")
        
        if total_time > 0:
            throughput = total_inspected / (total_time / 1000)  # strings/second
            print(f"  处理吞吐量: {throughput:,.0f} strings/s")
    
    # 内存节省估算
    if total_deduplicated > 0:
        # 假设平均字符串长度为20字符，每字符2字节
        avg_string_size = 40  # bytes
        memory_saved = total_deduplicated * avg_string_size
        print(f"\n估算内存节省:")
        print(f"  节省字符数组: {total_deduplicated:,} 个")
        print(f"  节省内存大小: {memory_saved / 1024 / 1024:.1f} MB")
    
    # 性能分析
    if events:
        avg_time_per_event = total_time / len(events)
        max_time = max(e['time_ms'] for e in events)
        min_time = min(e['time_ms'] for e in events)
        
        print(f"\n性能分析:")
        print(f"  平均处理时间: {avg_time_per_event:.2f}ms")
        print(f"  最长处理时间: {max_time:.2f}ms")
        print(f"  最短处理时间: {min_time:.2f}ms")
    
    # 显示前10个事件的详细信息
    print(f"\n--- 前10个去重事件详情 ---")
    for i, event in enumerate(events[:10]):
        dedup_rate = (event['deduplicated'] / event['inspected'] * 100) if event['inspected'] > 0 else 0
        print(f"事件#{i+1:2d}: 检查={event['inspected']:5d}, "
              f"去重={event['deduplicated']:4d} ({dedup_rate:4.1f}%), "
              f"新增={event['new']:4d}, "
              f"时间={event['time_ms']:6.2f}ms")

def main():
    if len(sys.argv) != 2:
        print("用法: python3 analyze_string_dedup.py <dedup-log-file>")
        sys.exit(1)
    
    log_file = sys.argv[1]
    
    try:
        events = parse_dedup_log(log_file)
        analyze_dedup_effectiveness(events)
    except FileNotFoundError:
        print(f"错误: 找不到日志文件 {log_file}")
    except Exception as e:
        print(f"分析过程中出错: {e}")

if __name__ == "__main__":
    main()
```

## 关键技术洞察

### 1. StringDeduplication优势

- **内存节省**：显著减少重复字符串的内存占用
- **并发处理**：去重操作与应用线程并发执行
- **自适应优化**：哈希表动态调整大小和重哈希
- **低开销**：候选对象识别开销很小

### 2. 适用场景

- **大量重复字符串**：配置文件、日志消息、JSON数据
- **长期运行应用**：Web服务器、应用服务器
- **内存敏感应用**：大数据处理、缓存系统
- **微服务架构**：大量相似的配置和消息

### 3. 性能调优建议

```bash
# 8GB堆StringDeduplication调优
-XX:+UseG1GC
-XX:+UseStringDeduplication              # 启用去重
-XX:StringDeduplicationAgeThreshold=3    # 年龄阈值3
-XX:+PrintStringDeduplicationStatistics  # 打印统计

# 监控和调试
-Xlog:stringdedup:string-dedup.log:time
-XX:+UnlockDiagnosticVMOptions
-XX:+LogVMOutput
```

## 总结

G1的StringDeduplication机制通过智能的候选对象选择和高效的并发去重处理，为Java应用提供了显著的内存优化能力。其核心特点包括：

1. **智能选择**：基于对象年龄和疏散路径的候选对象选择策略
2. **并发处理**：独立的去重线程并发处理，不影响应用性能
3. **高效存储**：动态调整的哈希表管理唯一字符数组
4. **低开销**：最小化对GC性能的影响

在8GB堆环境下，StringDeduplication能够为包含大量重复字符串的应用节省10-40%的内存，同时保持很低的性能开销。理解这一机制对于优化Java应用的内存使用具有重要价值。