# codeCache_init() - 代码缓存初始化

## 调试环境

| 配置项 | 值 |
|--------|-----|
| **JVM参数** | `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages` |
| **测试程序** | HelloWorld.java |

## 源码位置

- 文件：`src/hotspot/share/code/codeCache.cpp`
- 函数：`codeCache_init()`

## 调用链

```
init_globals()
  └── codeCache_init()  ← 第4个初始化函数
        └── CodeCache::initialize()
```

## GDB调试结果

### 断点信息
```gdb
Thread 2 "java" hit Breakpoint 4, codeCache_init () 
at /data/workspace/openjdk11-core/src/hotspot/share/code/codeCache.cpp:1129
1129	  CodeCache::initialize(); // forcus
```

### 调用栈
```
#0  codeCache_init () at codeCache.cpp:1129
#1  init_globals () at init.cpp:115
#2  Threads::create_vm (args=0x7ffff780add0, canTryAgain=0x7ffff780acc3) at thread.cpp:4060
```

### CodeCache配置参数

| 参数 | 值 | 换算 | 说明 |
|------|-----|------|------|
| `ReservedCodeCacheSize` | 251658240 | **240MB** | 预留代码缓存大小 |
| `InitialCodeCacheSize` | 2555904 | ~2.4MB | 初始代码缓存大小 |
| `CodeCacheExpansionSize` | 65536 | 64KB | 扩展单位 |
| `SegmentedCodeCache` | true | - | 启用分段代码缓存 |

### CodeCache结构

| 属性 | 值 | 说明 |
|------|-----|------|
| `_heaps` | 0x555555571b40 | CodeHeap数组指针 |
| `_heaps->_len` | 3 | 3个分段堆 |
| `_low_bound` | 0x7fffe1000000 | 代码缓存低地址 |
| `_high_bound` | 0x7ffff0000000 | 代码缓存高地址 |

## 分段代码缓存 (Segmented CodeCache)

### 三个CodeHeap分段

| 分段 | 名称 | 类型 | 说明 |
|------|------|------|------|
| **Heap 0** | non-profiled nmethods | blob_type=0 | 无profiling的nmethod |
| **Heap 1** | profiled nmethods | blob_type=1 | 有profiling的nmethod |
| **Heap 2** | non-nmethods | blob_type=2 | 非nmethod代码（桩、适配器等） |

### CodeHeap 0: non-profiled nmethods

```gdb
$1 = (CodeHeap) {
  _memory = {
    _low_boundary = 0x7fffe8b9f000,
    _high_boundary = 0x7ffff0000000,    // 约120MB预留
    _high = 0x7fffe8e0f000,             // 当前使用约2.5MB
  },
  _number_of_committed_segments = 19968,
  _number_of_reserved_segments = 953376,
  _segment_size = 128,
  _name = "CodeHeap 'non-profiled nmethods'",
  _code_blob_type = 0,
  _blob_count = 0,
  _nmethod_count = 0,
  _adapter_count = 0,
}
```

### CodeHeap 1: profiled nmethods

```gdb
$2 = (CodeHeap) {
  _memory = {
    _low_boundary = 0x7fffe173f000,
    _high_boundary = 0x7fffe8b9f000,    // 约120MB预留
    _high = 0x7fffe19af000,
  },
  _number_of_committed_segments = 19968,
  _number_of_reserved_segments = 953344,
  _segment_size = 128,
  _name = "CodeHeap 'profiled nmethods'",
  _code_blob_type = 1,
  _blob_count = 0,
  _nmethod_count = 0,
  _adapter_count = 0,
}
```

### CodeHeap 2: non-nmethods

```gdb
$3 = (CodeHeap) {
  _memory = {
    _low_boundary = 0x7fffe1000000,
    _high_boundary = 0x7fffe173f000,    // 约7MB预留
    _high = 0x7fffe1270000,             // 当前使用约2.4MB
  },
  _number_of_committed_segments = 19968,
  _number_of_reserved_segments = 59360,
  _segment_size = 128,
  _name = "CodeHeap 'non-nmethods'",
  _code_blob_type = 2,
  _blob_count = 700,           // 700个代码块
  _nmethod_count = 0,
  _adapter_count = 674,        // 674个适配器
  _max_allocated_capacity = 1132672,  // ~1.1MB
}
```

## 内存布局

```
CodeCache 总地址空间 (240MB)
┌──────────────────────────────────────────────────────────────┐
│ 0x7fffe1000000                                 0x7ffff0000000│
├────────────┬─────────────────────┬───────────────────────────┤
│ non-nmethod│  profiled nmethods  │    non-profiled nmethods  │
│   (~7MB)   │      (~120MB)       │        (~120MB)           │
│ blob_type=2│     blob_type=1     │       blob_type=0         │
└────────────┴─────────────────────┴───────────────────────────┘
```

## 存储内容

### non-nmethods (Heap 2)

- **StubRoutines**：JVM内部桩代码
- **Adapter Blobs**：调用适配器
- **Buffer Blobs**：临时代码缓冲
- **RuntimeStubs**：运行时桩代码
- **SafepointBlobs**：安全点处理代码

### profiled nmethods (Heap 1)

- C1编译的带profiling信息的代码
- Tier 2/3 编译结果

### non-profiled nmethods (Heap 0)

- C2编译的优化代码（Tier 4）
- C1编译的无profiling代码（Tier 1）

## CodeCache类结构

```cpp
// src/hotspot/share/code/codeCache.hpp
class CodeCache : AllStatic {
private:
  static GrowableArray<CodeHeap*>* _heaps;  // CodeHeap数组
  static address _low_bound;                 // 最低地址
  static address _high_bound;                // 最高地址

public:
  static void initialize();
  static CodeBlob* allocate(int size, int code_blob_type, int orig_code_blob_type);
  static void free(CodeBlob* cb);
};
```

## 关键JVM参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-XX:ReservedCodeCacheSize=N` | 240MB | 预留代码缓存大小 |
| `-XX:InitialCodeCacheSize=N` | ~2.4MB | 初始代码缓存大小 |
| `-XX:CodeCacheExpansionSize=N` | 64KB | 扩展单位 |
| `-XX:+SegmentedCodeCache` | true | 启用分段 |
| `-XX:NonProfiledCodeHeapSize=N` | 自动 | 非profiled区大小 |
| `-XX:ProfiledCodeHeapSize=N` | 自动 | profiled区大小 |
| `-XX:NonNMethodCodeHeapSize=N` | 自动 | 非nmethod区大小 |

## 与其他组件的关系

- **CompileBroker**：编译完成后将代码放入CodeCache
- **StubRoutines**：桩代码存储在non-nmethods区
- **Interpreter**：解释器代码存储在non-nmethods区
- **NMethod**：JIT编译代码存储在对应分段
- **GC**：在GC时扫描CodeCache中的引用
