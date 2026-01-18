# 18-内存分配器与Arena

## 面试官：JVM中有哪些内存分配方式？它们有什么区别？

### 答案

#### 1. JVM内存分配体系

```
                    ┌──────────────────────────────────────────┐
                    │           JVM内存分配体系                 │
                    └──────────────────────────────────────────┘
                                       │
         ┌─────────────────────────────┼─────────────────────────────┐
         ▼                             ▼                             ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│   Java堆分配    │          │   Native内存    │          │   Arena分配     │
│  (GC管理)       │          │   (C Heap)      │          │   (快速临时)    │
├─────────────────┤          ├─────────────────┤          ├─────────────────┤
│ TLAB            │          │ os::malloc      │          │ ResourceArea    │
│ G1 Region       │          │ AllocateHeap    │          │ HandleArea      │
│ Humongous       │          │ ArrayAllocator  │          │ 编译器Arena     │
└─────────────────┘          └─────────────────┘          └─────────────────┘
```

#### 2. 三种分配方式对比

| 分配方式 | 用途 | 生命周期 | 释放方式 |
|----------|------|----------|----------|
| Java堆 | 对象实例 | GC管理 | 自动回收 |
| Native/CHeap | 长期数据结构 | 手动管理 | 显式free |
| Arena | 临时数据 | 作用域内 | 批量释放 |

---

## 面试官：Arena是什么？它解决什么问题？

### 答案

#### 1. Arena概念

Arena是一种**区域内存分配器**（Region-based Memory Allocator）：
- 预先分配大块内存（Chunk）
- 从Chunk中快速分配小块内存
- 离开作用域时批量释放整个Arena

#### 2. 解决的问题

```
传统malloc/free的问题:
1. 频繁的系统调用开销
2. 内存碎片化
3. 需要追踪每个分配的指针

Arena的优势:
1. 批量分配减少系统调用
2. 顺序分配无碎片
3. 批量释放无需追踪
```

#### 3. 源码定义

```cpp:91:108:openjdk11-core/src/hotspot/share/memory/arena.hpp
//------------------------------Arena------------------------------------------
// Fast allocation of memory
class Arena : public CHeapObj<mtNone> {
protected:
  friend class ResourceMark;
  friend class HandleMark;
  friend class NoHandleMark;
  friend class VMStructs;

  MEMFLAGS    _flags;           // Memory tracking flags

  Chunk *_first;                // First chunk
  Chunk *_chunk;                // current chunk
  char *_hwm, *_max;            // High water mark and max in current chunk
  // Get a new Chunk of at least size x
  void* grow(size_t x, AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM);
  size_t _size_in_bytes;        // Size of arena (used for native memory tracking)
```

---

## 面试官：Chunk和ChunkPool机制是怎么工作的？

### 答案

#### 1. Chunk结构

```cpp
class Chunk {
    Chunk* _next;     // 链表指针
    size_t _len;      // Chunk数据区大小
    
    char* bottom();   // 数据区起始
    char* top();      // 数据区结束
};

// 布局:
┌──────────────┐
│ Chunk Header │  sizeof(Chunk)
│ (_next, _len)│
├──────────────┤ ← bottom()
│              │
│  数据区      │  _len bytes
│              │
└──────────────┘ ← top()
```

#### 2. ChunkPool - 四级缓存池

```cpp:43:53:openjdk11-core/src/hotspot/share/memory/arena.cpp
class ChunkPool: public CHeapObj<mtInternal> {
  Chunk*       _first;        // first cached Chunk; its first word points to next chunk
  size_t       _num_chunks;   // number of unused chunks in pool
  size_t       _num_used;     // number of chunks currently checked out
  const size_t _size;         // size of each chunk (must be uniform)

  // Our four static pools
  static ChunkPool* _large_pool;
  static ChunkPool* _medium_pool;
  static ChunkPool* _small_pool;
  static ChunkPool* _tiny_pool;
```

#### 3. 四种Chunk大小

```cpp
// Chunk大小常量
enum ChunkSize {
    tiny_size   = 256 - sizeof(Chunk),     // ~240 bytes
    init_size   = 1*K - sizeof(Chunk),     // ~1000 bytes
    medium_size = 10*K - sizeof(Chunk),    // ~10KB
    size        = 32*K - sizeof(Chunk)     // ~32KB (large)
};
```

#### 4. ChunkPool分配流程

```cpp:70:84:openjdk11-core/src/hotspot/share/memory/arena.cpp
  // Allocate a new chunk from the pool (might expand the pool)
  NOINLINE void* allocate(size_t bytes, AllocFailType alloc_failmode) {
    assert(bytes == _size, "bad size");
    void* p = NULL;
    // No VM lock can be taken inside ThreadCritical lock, so os::malloc
    // should be done outside ThreadCritical lock due to NMT
    { ThreadCritical tc;
      _num_used++;
      p = get_first();
    }
    if (p == NULL) p = os::malloc(bytes, mtChunk, CURRENT_PC);
    if (p == NULL && alloc_failmode == AllocFailStrategy::EXIT_OOM) {
      vm_exit_out_of_memory(bytes, OOM_MALLOC_ERROR, "ChunkPool::allocate");
    }
    return p;
  }
```

#### 5. Chunk选择逻辑

```cpp:182:200:openjdk11-core/src/hotspot/share/memory/arena.cpp
void* Chunk::operator new (size_t requested_size, AllocFailType alloc_failmode, size_t length) throw() {
  size_t bytes = ARENA_ALIGN(requested_size) + length;
  switch (length) {
   case Chunk::size:        return ChunkPool::large_pool()->allocate(bytes, alloc_failmode);
   case Chunk::medium_size: return ChunkPool::medium_pool()->allocate(bytes, alloc_failmode);
   case Chunk::init_size:   return ChunkPool::small_pool()->allocate(bytes, alloc_failmode);
   case Chunk::tiny_size:   return ChunkPool::tiny_pool()->allocate(bytes, alloc_failmode);
   default: {
     void* p = os::malloc(bytes, mtChunk, CALLER_PC);
     if (p == NULL && alloc_failmode == AllocFailStrategy::EXIT_OOM) {
       vm_exit_out_of_memory(bytes, OOM_MALLOC_ERROR, "Chunk::new");
     }
     return p;
   }
  }
}
```

---

## 面试官：Arena的分配和扩展机制是怎样的？

### 答案

#### 1. Arena构造

```cpp:249:265:openjdk11-core/src/hotspot/share/memory/arena.cpp
Arena::Arena(MEMFLAGS flag, size_t init_size) : _flags(flag), _size_in_bytes(0)  {
  size_t round_size = (sizeof (char *)) - 1;
  init_size = (init_size+round_size) & ~round_size;
  _first = _chunk = new (AllocFailStrategy::EXIT_OOM, init_size) Chunk(init_size);
  _hwm = _chunk->bottom();      // Save the cached hwm, max
  _max = _chunk->top();
  MemTracker::record_new_arena(flag);
  set_size_in_bytes(init_size);
}

Arena::Arena(MEMFLAGS flag) : _flags(flag), _size_in_bytes(0) {
  _first = _chunk = new (AllocFailStrategy::EXIT_OOM, Chunk::init_size) Chunk(Chunk::init_size);
  _hwm = _chunk->bottom();      // Save the cached hwm, max
  _max = _chunk->top();
  MemTracker::record_new_arena(flag);
  set_size_in_bytes(Chunk::init_size);
}
```

#### 2. 快速路径分配 (Amalloc)

```cpp
// 内联的快速分配
inline void* Arena::Amalloc(size_t x) {
    x = ARENA_ALIGN(x);          // 对齐到8字节
    char* old_hwm = _hwm;
    _hwm += x;
    if (_hwm <= _max) {          // 有足够空间
        return old_hwm;          // 直接bump pointer
    }
    return grow(x);              // 慢路径：扩展Chunk
}
```

#### 3. 慢路径扩展 (grow)

```cpp:352:372:openjdk11-core/src/hotspot/share/memory/arena.cpp
// Grow a new Chunk
void* Arena::grow(size_t x, AllocFailType alloc_failmode) {
  // Get minimal required size.  Either real big, or even bigger for giant objs
  size_t len = MAX2(x, (size_t) Chunk::size);

  Chunk *k = _chunk;            // Get filled-up chunk address
  _chunk = new (alloc_failmode, len) Chunk(len);

  if (_chunk == NULL) {
    _chunk = k;                 // restore the previous value of _chunk
    return NULL;
  }
  if (k) k->set_next(_chunk);   // Append new chunk to end of linked list
  else _first = _chunk;
  _hwm  = _chunk->bottom();     // Save the cached hwm, max
  _max =  _chunk->top();
  set_size_in_bytes(size_in_bytes() + len);
  void* result = _hwm;
  _hwm += x;
  return result;
}
```

#### 4. Arena内存布局

```
Arena结构:
                   _first              _chunk (当前)
                     │                    │
                     ▼                    ▼
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Chunk 1   │───→│   Chunk 2   │───→│   Chunk 3   │───→ NULL
├─────────────┤    ├─────────────┤    ├─────────────┤
│ [已分配数据]│    │ [已分配数据]│    │ [已分配]    │ ← _hwm
│             │    │             │    │ [空闲空间]  │
│             │    │             │    │             │ ← _max
└─────────────┘    └─────────────┘    └─────────────┘
```

---

## 面试官：ResourceArea是什么？它和ResourceMark如何配合？

### 答案

#### 1. ResourceArea定义

```cpp:42:58:openjdk11-core/src/hotspot/share/memory/resourceArea.hpp
//------------------------------ResourceArea-----------------------------------
// A ResourceArea is an Arena that supports safe usage of ResourceMark.
class ResourceArea: public Arena {
  friend class ResourceMark;
  friend class DeoptResourceMark;
  friend class VMStructs;
  debug_only(int _nesting;)             // current # of nested ResourceMarks
  debug_only(static int _warned;)       // to suppress multiple warnings

public:
  ResourceArea(MEMFLAGS flags = mtThread) : Arena(flags) {
    debug_only(_nesting = 0;)
  }

  ResourceArea(size_t init_size, MEMFLAGS flags = mtThread) : Arena(flags, init_size) {
    debug_only(_nesting = 0;);
  }
```

#### 2. 每线程ResourceArea

```cpp
// Thread构造函数中
Thread::Thread() {
    ...
    set_resource_area(new(mtThread) ResourceArea());  // 每个线程一个
    ...
}
```

#### 3. ResourceMark工作原理

```cpp
class ResourceMark {
    Thread* _thread;
    char*   _hwm;      // 高水位线（保存进入时的位置）
    char*   _max;
    Chunk*  _chunk;
    
public:
    ResourceMark(Thread* thread) {
        _thread = thread;
        ResourceArea* ra = thread->resource_area();
        // 保存当前状态
        _hwm = ra->_hwm;
        _max = ra->_max;
        _chunk = ra->_chunk;
    }
    
    ~ResourceMark() {
        ResourceArea* ra = _thread->resource_area();
        // 回滚到保存的状态
        ra->_hwm = _hwm;
        ra->_max = _max;
        // 释放额外的Chunks
        if (ra->_chunk->next() != NULL) {
            ra->_chunk->next_chop();
        }
        ra->_chunk = _chunk;
    }
};
```

#### 4. 使用模式

```cpp
void some_function() {
    ResourceMark rm;  // 构造函数保存状态
    
    // 在此作用域内的所有分配
    char* buf = NEW_RESOURCE_ARRAY(char, 1024);
    int* arr = NEW_RESOURCE_ARRAY(int, 100);
    
    // 使用分配的内存...
    
}  // rm析构函数自动回滚，释放所有分配

// 宏定义
#define NEW_RESOURCE_ARRAY(type, size) \
    (type*) resource_allocate_bytes((size) * sizeof(type))
```

---

## 面试官：HandleArea和HandleMark是做什么的？

### 答案

#### 1. Handle的作用

Handle是对oop的**安全包装**，防止GC移动对象时指针失效：

```cpp
class Handle {
    oop* _handle;  // 指向handleArea中存储oop的位置
    
    oop operator()() const { return *_handle; }  // 解引用获取oop
};
```

#### 2. HandleArea

```cpp
class HandleArea: public Arena {
    HandleArea* _prev;  // 链表结构，支持嵌套
    
    // 分配一个Handle slot
    Handle allocate_handle(oop obj) {
        // 在arena中分配空间存储oop
        oop* slot = (oop*) Amalloc(sizeof(oop));
        *slot = obj;
        return Handle(slot);
    }
};
```

#### 3. 为什么需要Handle？

```cpp
// 错误示例：直接使用oop
void dangerous_function(oop obj) {
    // ... 一些操作可能触发GC ...
    call_some_method();  // 可能触发GC
    
    // GC后obj可能已被移动，原指针无效！
    obj->print();  // CRASH!
}

// 正确示例：使用Handle
void safe_function(oop obj) {
    Handle h(obj);  // 将oop注册到handleArea
    
    // GC发生时，会更新handleArea中的指针
    call_some_method();
    
    // 通过handle访问始终安全
    h()->print();  // OK!
}
```

#### 4. HandleMark

```cpp
class HandleMark {
    HandleArea* _area;
    Handle*     _hwm;
    
    HandleMark(Thread* thread) {
        _area = thread->handle_area();
        _hwm = _area->_hwm;  // 保存状态
    }
    
    ~HandleMark() {
        _area->_hwm = _hwm;  // 回滚状态
        // 所有在此范围内创建的Handle自动释放
    }
};
```

---

## 面试官：编译器Arena有什么特别之处？

### 答案

#### 1. 编译器使用的Arena

```cpp
// C2编译器中
class Compile {
    Arena _arena;           // 主Arena
    Arena _node_arena;      // Node专用
    Arena _type_arena;      // Type专用
    
    // 分配Node
    void* node_arena_alloc(size_t size) {
        return _node_arena.Amalloc(size);
    }
};
```

#### 2. 为什么编译器需要单独Arena？

```
1. 隔离性：编译期间分配的数据不影响其他线程
2. 批量释放：编译完成后一次性释放所有临时数据
3. 局部性：相关数据连续分配，提高缓存命中
```

#### 3. 编译器Arena使用示例

```cpp
// 在Dict中使用Arena
class Dict {
    Arena* _arena;  // Where to draw storage from
    
    void* operator new(size_t size, Arena* arena) {
        return arena->Amalloc(size);
    }
};

// 在BitMap中使用Arena
class ArenaBitMap : public BitMap {
public:
    ArenaBitMap(Arena* arena, idx_t size_in_bits);
};
```

---

## 面试官：ChunkPoolCleaner是做什么的？

### 答案

#### 1. 定义

```cpp:169:177:openjdk11-core/src/hotspot/share/memory/arena.cpp
class ChunkPoolCleaner : public PeriodicTask {
  enum { CleaningInterval = 5000 };      // cleaning interval in ms

 public:
   ChunkPoolCleaner() : PeriodicTask(CleaningInterval) {}
   void task() {
     ChunkPool::clean();
   }
};
```

#### 2. 作用

定期清理ChunkPool中缓存的空闲Chunk，防止内存持续增长：

```cpp:141:147:openjdk11-core/src/hotspot/share/memory/arena.cpp
  static void clean() {
    enum { BlocksToKeep = 5 };
     _tiny_pool->free_all_but(BlocksToKeep);
     _small_pool->free_all_but(BlocksToKeep);
     _medium_pool->free_all_but(BlocksToKeep);
     _large_pool->free_all_but(BlocksToKeep);
  }
```

#### 3. 清理逻辑

```cpp:99:126:openjdk11-core/src/hotspot/share/memory/arena.cpp
  // Prune the pool
  void free_all_but(size_t n) {
    Chunk* cur = NULL;
    Chunk* next;
    {
      // if we have more than n chunks, free all of them
      ThreadCritical tc;
      if (_num_chunks > n) {
        // free chunks at end of queue, for better locality
        cur = _first;
        for (size_t i = 0; i < (n - 1) && cur != NULL; i++) cur = cur->next();

        if (cur != NULL) {
          next = cur->next();
          cur->set_next(NULL);
          cur = next;

          // Free all remaining chunks while in ThreadCritical lock
          // so NMT adjustment is stable.
          while(cur != NULL) {
            next = cur->next();
            os::free(cur);
            _num_chunks--;
            cur = next;
          }
        }
      }
    }
  }
```

---

## 面试官：MEMFLAGS在内存分配中起什么作用？

### 答案

#### 1. MEMFLAGS定义

```cpp
// 内存类型标记，用于Native Memory Tracking
enum MEMFLAGS {
    mtNone             = 0,
    mtJavaHeap         = 1,
    mtClass            = 2,
    mtThread           = 3,
    mtThreadStack      = 4,
    mtCode             = 5,    // CodeCache
    mtGC               = 6,    // GC内部数据结构
    mtCompiler         = 7,    // 编译器
    mtInternal         = 8,    // 内部使用
    mtOther            = 9,
    mtSymbol           = 10,
    mtNMT              = 11,   // NMT自身
    mtClassShared      = 12,   // CDS
    mtChunk            = 13,   // Arena Chunk
    mtTest             = 14,
    mtTracing          = 15,   // JFR
    mtLogging          = 16,
    mtSafepoint        = 17,
    mt_number_of_types = 18
};
```

#### 2. 使用方式

```cpp
// 带类型的分配
void* p = AllocateHeap(size, mtThread, CALLER_PC);

// Arena使用
Arena arena(mtCompiler);
void* buf = arena.Amalloc(1024);  // 标记为编译器内存

// NMT会追踪每种类型的使用量
```

#### 3. NMT报告示例

```
Native Memory Tracking:

Total: reserved=XXX, committed=XXX
-                 Java Heap (reserved=8GB, committed=8GB)
-                     Class (reserved=1GB, committed=XXX)
-                    Thread (reserved=XXX, committed=XXX)
                            (thread #123)
                            (stack: reserved=XXX, committed=XXX)
-                      Code (reserved=XXX, committed=XXX)
-                        GC (reserved=XXX, committed=XXX)
-                  Compiler (reserved=XXX, committed=XXX)
-                  Internal (reserved=XXX, committed=XXX)
```

---

## 知识图谱

```
                    ┌─────────────────────────────────────────────────┐
                    │             JVM内存分配器体系                    │
                    └─────────────────────────────────────────────────┘
                                          │
         ┌────────────────────────────────┼────────────────────────────────┐
         ▼                                ▼                                ▼
┌─────────────────┐              ┌─────────────────┐              ┌─────────────────┐
│   Arena分配器    │              │    ChunkPool    │              │  MEMFLAGS追踪   │
├─────────────────┤              ├─────────────────┤              ├─────────────────┤
│ Chunk链表       │              │ tiny ~240B      │              │ mtJavaHeap      │
│ _hwm指针bump    │              │ small ~1KB      │              │ mtThread        │
│ 批量释放        │              │ medium ~10KB    │              │ mtCompiler      │
│ 零碎片          │              │ large ~32KB     │              │ mtGC            │
└─────────────────┘              └─────────────────┘              └─────────────────┘
         │                                │
         ├────────────────────────────────┤
         ▼                                ▼
┌─────────────────┐              ┌─────────────────┐
│  ResourceArea   │              │   HandleArea    │
├─────────────────┤              ├─────────────────┤
│ 线程私有        │              │ oop安全包装     │
│ ResourceMark    │              │ HandleMark      │
│ 临时数据分配    │              │ GC安全引用      │
└─────────────────┘              └─────────────────┘

分配流程:
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Amalloc(x)  │────→│ _hwm+x≤_max?│─Yes─→│ bump _hwm   │
└─────────────┘     └─────────────┘     └─────────────┘
                           │No
                           ▼
                    ┌─────────────┐     ┌─────────────┐
                    │ grow(x)     │────→│ new Chunk   │
                    └─────────────┘     └─────────────┘
```
