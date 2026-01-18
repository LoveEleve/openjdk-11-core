# JVM内存分配与句柄管理系统详解

## 📋 **文档概述**

本文档详细分析JVM内存分配和句柄管理相关的核心对象，包括Arena、ResourceArea、HandleArea、JNIHandles、OopStorage、TLAB等。这些是之前文档中遗漏或介绍不够详细的重要子系统。

### **🎯 分析环境**
- **操作系统**: Linux x86_64
- **JVM版本**: OpenJDK 11
- **堆大小**: 8GB (-Xms8g -Xmx8g)

---

## 🏗️ **1. Arena - 快速内存分配器**

### **1.1 概述**

`Arena`是JVM内部的快速内存分配器，通过Chunk链表管理内存，支持批量分配和释放。

**源文件**: `src/hotspot/share/memory/arena.hpp`

### **1.2 Chunk结构**

```cpp
class Chunk: CHeapObj<mtChunk> {
private:
  Chunk*       _next;      // 链表中的下一个Chunk
  const size_t _len;       // Chunk大小(字节)
  
public:
  // Chunk大小常量 (slack = 2*sizeof(void*) 用于malloc对齐)
  enum {
    slack      = 2 * sizeof(void*),  // 对齐开销
    tiny_size  = 256 - slack,        // ~240字节 (第一个tiny chunk)
    init_size  = 1*K - slack,        // ~1KB (第一个normal chunk)
    medium_size= 10*K - slack,       // ~10KB (中等大小chunk)
    size       = 32*K - slack,       // ~32KB (默认Arena chunk大小)
    non_pool_size = init_size + 32   // 非池化chunk大小
  };
  
  void* operator new(size_t size, AllocFailType alloc_failmode, size_t length);
  void  operator delete(void* p);
  
  // 返回chunk的数据起始地址
  char* bottom() const { return ((char*) this) + sizeof(Chunk); }
  char* top()    const { return bottom() + _len; }
};
```

### **1.3 Arena结构**

```cpp
class Arena : public CHeapObj<mtNone> {
protected:
  MEMFLAGS  _flags;         // 内存追踪标志(NMT)
  Chunk*    _first;         // 第一个Chunk
  Chunk*    _chunk;         // 当前Chunk
  char*     _hwm;           // 当前chunk的高水位标记(High Water Mark)
  char*     _max;           // 当前chunk的最大位置
  size_t    _size_in_bytes; // Arena总大小(用于NMT)
  
  NOT_PRODUCT(static julong _bytes_allocated;)  // 启动以来分配的总字节数
  
public:
  // 构造函数
  Arena(MEMFLAGS memflag);
  Arena(MEMFLAGS memflag, size_t init_size);
  
  // 快速分配 - 内联实现
  void* Amalloc(size_t x, AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM) {
    x = ARENA_ALIGN(x);  // 对齐到AmallowWord大小
    if (_hwm + x > _max) {
      return grow(x, alloc_failmode);  // 需要新chunk
    }
    char* old = _hwm;
    _hwm += x;
    return old;
  }
  
  // 假设大小已对齐到字
  void* Amalloc_4(size_t x, AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM) {
    assert((x & (sizeof(char*)-1)) == 0, "misaligned size");
    if (_hwm + x > _max) {
      return grow(x, alloc_failmode);
    }
    char* old = _hwm;
    _hwm += x;
    return old;
  }
  
  // double对齐分配
  void* Amalloc_D(size_t x, AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM);
  
  // 快速释放 - 只在释放最后分配的块时有效
  void Afree(void* ptr, size_t size) {
    if (((char*)ptr) + size == _hwm) {
      _hwm = (char*)ptr;
    }
  }
  
  // 重新分配
  void* Arealloc(void* old_ptr, size_t old_size, size_t new_size,
                 AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM);
  
  // 重置Arena (释放除第一个chunk外的所有chunk)
  void set_size_in_bytes(size_t size);
  
  // 统计
  size_t size_in_bytes() const { return _size_in_bytes; }
  size_t used() const;
};
```

### **1.4 Arena分配流程**

```
Arena分配流程:
┌─────────────────────────────────────────────────────────────┐
│ Amalloc(size)                                               │
│       │                                                     │
│       ▼                                                     │
│ 对齐大小: x = ARENA_ALIGN(size)                             │
│       │                                                     │
│       ▼                                                     │
│ 检查当前chunk是否有足够空间                                  │
│       │                                                     │
│       ├── _hwm + x <= _max?                                 │
│       │         │                                           │
│       │         ├── 是 → 快速分配                           │
│       │         │         │                                 │
│       │         │         ├── old = _hwm                    │
│       │         │         ├── _hwm += x                     │
│       │         │         └── return old                    │
│       │         │                                           │
│       │         └── 否 → grow(x)                            │
│       │                   │                                 │
│       │                   ├── 分配新Chunk                   │
│       │                   │                                 │
│       │                   ├── 链接到chunk链表               │
│       │                   │                                 │
│       │                   └── 从新chunk分配                 │
└─────────────────────────────────────────────────────────────┘
```

### **1.5 内存布局**

```
Arena内存布局:
┌─────────────────────────────────────────────────────────────┐
│                         Arena                               │
│  _first ──► Chunk1 ──► Chunk2 ──► Chunk3 ──► NULL          │
│  _chunk ─────────────────────────────┘                      │
│                                                             │
│  Chunk结构:                                                  │
│  ┌──────────────────────────────────────────┐               │
│  │ Chunk Header │      Data Area            │               │
│  │  _next       │ ◄── _hwm (高水位)         │               │
│  │  _len        │                     _max ►│               │
│  └──────────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────────┘
```

---

## 📦 **2. ResourceArea - 资源区域**

### **2.1 概述**

`ResourceArea`是线程本地的临时数据结构存储区域，基于Arena实现，支持ResourceMark进行批量释放。

**源文件**: `src/hotspot/share/memory/resourceArea.hpp`

### **2.2 ResourceArea结构**

```cpp
class ResourceArea: public Arena {
  debug_only(int _nesting;)  // 当前嵌套的ResourceMark数量
  
public:
  ResourceArea(MEMFLAGS flags = mtThread) : Arena(flags) {
    debug_only(_nesting = 0;)
  }
  
  ResourceArea(size_t init_size, MEMFLAGS flags = mtThread)
    : Arena(flags, init_size) {
    debug_only(_nesting = 0;)
  }
  
  // 通过Thread访问
  // Thread::resource_area() 返回线程的ResourceArea
};
```

### **2.3 ResourceMark结构**

```cpp
class ResourceMark: public StackObj {
protected:
  ResourceArea* _area;        // 关联的资源区域
  Chunk*        _chunk;       // 保存的arena chunk
  char*         _hwm;         // 保存的高水位标记
  char*         _max;         // 保存的最大位置
  size_t        _size_in_bytes;  // 字节大小
  
  DEBUG_ONLY(Thread* _thread;)
  DEBUG_ONLY(ResourceMark* _previous_resource_mark;)
  
public:
  ResourceMark()              { initialize(Thread::current()); }
  ResourceMark(Thread* thread) { initialize(thread); }
  
  void initialize(Thread* thread) {
    _area = thread->resource_area();
    _chunk = _area->_chunk;
    _hwm = _area->_hwm;
    _max = _area->_max;
    _size_in_bytes = _area->size_in_bytes();
    DEBUG_ONLY(_area->_nesting++;)
  }
  
  ~ResourceMark() {
    // 恢复到标记时的状态
    _area->_chunk = _chunk;
    _area->_hwm = _hwm;
    _area->_max = _max;
    _area->set_size_in_bytes(_size_in_bytes);
    DEBUG_ONLY(_area->_nesting--;)
  }
  
  void reset_to_mark() {
    // 重置到标记点，但不销毁ResourceMark
    _area->_chunk = _chunk;
    _area->_hwm = _hwm;
    _area->_max = _max;
  }
};
```

### **2.4 ResourceMark使用模式**

```cpp
// 典型使用模式:
void some_function() {
  ResourceMark rm;  // 记录当前位置
  
  // 在ResourceArea中分配临时对象
  char* buffer = NEW_RESOURCE_ARRAY(char, 1024);
  Symbol* sym = NEW_RESOURCE_OBJ(Symbol);
  
  // ... 使用这些对象 ...
  
}  // rm析构，自动释放所有分配的内存
```

### **2.5 ResourceArea与线程关系**

```
线程与ResourceArea:
┌─────────────────────────────────────────────────────────────┐
│ Thread                                                      │
│       │                                                     │
│       ├── _resource_area ──► ResourceArea                   │
│       │                           │                         │
│       │                           └── Arena (继承)          │
│       │                                   │                 │
│       │                                   └── Chunk链表     │
│       │                                                     │
│       └── 调用栈                                             │
│             │                                               │
│             ├── function1()                                 │
│             │     └── ResourceMark rm1                      │
│             │           │                                   │
│             │           └── 分配A, B, C                     │
│             │                                               │
│             └── function2()                                 │
│                   └── ResourceMark rm2                      │
│                         │                                   │
│                         └── 分配D, E                        │
│                                                             │
│ rm2析构 → 释放D, E                                          │
│ rm1析构 → 释放A, B, C                                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 🤝 **3. HandleArea - 句柄区域**

### **3.1 概述**

`HandleArea`是线程本地的句柄分配区域，用于在GC期间保护oop引用。

**源文件**: `src/hotspot/share/runtime/handles.hpp`

### **3.2 HandleArea结构**

```cpp
class HandleArea: public Arena {
  friend class HandleMark;
  friend class NoHandleMark;
  friend class ResetNoHandleMark;
  
private:
  HandleArea* _prev;          // 链接到外层(旧)区域
  
  // 调试支持
  DEBUG_ONLY(int _handle_mark_nesting;)     // HandleMark嵌套深度
  DEBUG_ONLY(int _no_handle_mark_nesting;)  // NoHandleMark嵌套深度
  
public:
  HandleArea(HandleArea* prev) : Arena(mtThread, Chunk::tiny_size) {
    _prev = prev;
    DEBUG_ONLY(_handle_mark_nesting = 0;)
    DEBUG_ONLY(_no_handle_mark_nesting = 0;)
  }
  
  // 分配句柄
  oop* allocate_handle(oop obj) {
    oop* handle = (oop*)Amalloc_4(sizeof(oop));
    *handle = obj;
    return handle;
  }
};
```

### **3.3 Handle类**

```cpp
class Handle {
private:
  oop* _handle;  // 指向HandleArea中的槽位
  
public:
  // 构造函数 - 在当前线程的HandleArea中分配
  Handle(Thread* thread, oop obj) {
    _handle = thread->handle_area()->allocate_handle(obj);
  }
  
  // 解引用
  oop operator()() const { return *_handle; }
  oop operator->() const { return *_handle; }
  
  // 检查是否为空
  bool is_null() const { return _handle == NULL || *_handle == NULL; }
  bool not_null() const { return !is_null(); }
  
  // 原始句柄访问
  oop* raw_value() const { return _handle; }
};

// 类型化句柄
class instanceHandle : public Handle {
public:
  instanceHandle(Thread* thread, instanceOop obj) : Handle(thread, obj) {}
  instanceOop operator()() const { return (instanceOop)Handle::operator()(); }
};
```

### **3.4 HandleMark结构**

```cpp
class HandleMark : public StackObj {
private:
  Thread*      _thread;              // 拥有此标记的线程
  HandleArea*  _area;                // 保存的句柄区域
  Chunk*       _chunk;               // 保存的Arena chunk
  char*        _hwm;                 // 保存的高水位标记
  char*        _max;                 // 保存的最大位置
  size_t       _size_in_bytes;       // 句柄区域大小
  HandleMark*  _previous_handle_mark;  // 链接到前一个活动标记
  
public:
  HandleMark();                      // 使用当前线程
  HandleMark(Thread* thread);        // 指定线程
  ~HandleMark();
  
  void push();   // 保存当前状态
  void pop();    // 恢复到保存的状态
};
```

### **3.5 句柄的GC安全性**

```
句柄保护oop引用:
┌─────────────────────────────────────────────────────────────┐
│ 问题: 直接持有oop指针在GC时可能失效                          │
│                                                             │
│ 不安全:                                                      │
│   oop obj = some_object;  // 直接持有oop                    │
│   // ... 可能发生GC ...                                     │
│   obj->method();  // 危险! obj可能已被移动                  │
│                                                             │
│ 安全:                                                        │
│   Handle h(THREAD, some_object);  // 通过句柄持有           │
│   // ... 发生GC ...                                         │
│   // GC会更新HandleArea中的oop指针                          │
│   h()->method();  // 安全! h()返回更新后的oop               │
└─────────────────────────────────────────────────────────────┘

GC更新句柄:
┌─────────────────────────────────────────────────────────────┐
│ HandleArea                                                  │
│ ┌─────┬─────┬─────┬─────┐                                  │
│ │ oop1│ oop2│ oop3│ ... │  ← GC扫描并更新这些指针          │
│ └─────┴─────┴─────┴─────┘                                  │
│    ↑                                                        │
│    └── Handle._handle 指向这里                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔗 **4. JNIHandles - JNI句柄管理**

### **4.1 概述**

`JNIHandles`创建和解析JNI局部/全局句柄，是JNI与Java对象交互的桥梁。

**源文件**: `src/hotspot/share/runtime/jniHandles.hpp`

### **4.2 JNIHandles结构**

```cpp
class JNIHandles : AllStatic {
private:
  // ==================== 全局句柄存储 ====================
  static OopStorage* _global_handles;       // 全局句柄存储
  static OopStorage* _weak_global_handles;  // 弱全局句柄存储
  
  // ==================== 标记位 ====================
  static const uintptr_t weak_tag_size = 1;
  static const uintptr_t weak_tag_value = 1;
  // 弱引用句柄的最低位设为1
  
public:
  // 初始化
  static void initialize();
  
  // 局部句柄操作
  static jobject make_local(oop obj);
  static jobject make_local(Thread* thread, oop obj);
  static jobject make_local(JNIEnv* env, oop obj);
  
  // 全局句柄操作
  static jobject make_global(Handle obj, AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM);
  static jobject make_weak_global(Handle obj, AllocFailType alloc_failmode = AllocFailStrategy::EXIT_OOM);
  
  // 销毁句柄
  static void destroy_local(jobject handle);
  static void destroy_global(jobject handle);
  static void destroy_weak_global(jobject handle);
  
  // 解析句柄
  static oop resolve(jobject handle);
  static oop resolve_non_null(jobject handle);
  static oop resolve_external_guard(jobject handle);
  
  // 检查句柄类型
  static bool is_local_handle(Thread* thread, jobject handle);
  static bool is_global_handle(jobject handle);
  static bool is_weak_global_handle(jobject handle);
};
```

### **4.3 JNIHandleBlock结构**

```cpp
class JNIHandleBlock : public CHeapObj<mtInternal> {
private:
  // ==================== 句柄数组 ====================
  enum { block_size_in_oops = 32 };  // 每块32个句柄
  oop _handles[block_size_in_oops];  // 句柄数组
  
  // ==================== 块管理 ====================
  int              _top;              // 下一个未使用句柄的索引
  JNIHandleBlock*  _next;             // 链接到下一个块
  JNIHandleBlock*  _last;             // 链中最后一个使用的块
  JNIHandleBlock*  _pop_frame_link;   // PopLocalFrame恢复点
  
  // ==================== 空闲列表 ====================
  oop*             _free_list;        // 句柄空闲列表
  int              _allocate_before_rebuild;  // 重建空闲列表前的分配数
  size_t           _planned_capacity; // 当前帧的计划容量
  
  // ==================== 全局空闲块列表 ====================
  static JNIHandleBlock* _block_free_list;  // 全局空闲块列表
  static int             _blocks_allocated; // 已分配块数(调试)
  
public:
  // 分配局部句柄
  jobject allocate_handle(oop obj);
  
  // 块操作
  static JNIHandleBlock* allocate_block(Thread* thread = NULL);
  static void release_block(JNIHandleBlock* block, Thread* thread = NULL);
  
  // 帧管理
  JNIHandleBlock* pop_frame_link() const { return _pop_frame_link; }
  void set_pop_frame_link(JNIHandleBlock* block) { _pop_frame_link = block; }
};
```

### **4.4 JNI句柄类型对比**

```
JNI句柄类型:
┌─────────────────────────────────────────────────────────────┐
│ 类型           │ 生命周期          │ GC行为      │ 存储位置  │
├─────────────────────────────────────────────────────────────┤
│ Local Handle   │ 方法返回前        │ 强引用      │ JNIHandleBlock │
│                │ 或手动删除        │             │ (线程本地)     │
├─────────────────────────────────────────────────────────────┤
│ Global Handle  │ 手动删除前        │ 强引用      │ OopStorage     │
│                │ 一直有效          │ 阻止GC回收  │ (_global_handles) │
├─────────────────────────────────────────────────────────────┤
│ Weak Global    │ 手动删除前        │ 弱引用      │ OopStorage     │
│ Handle         │ 对象可能被GC回收  │ 不阻止回收  │ (_weak_global) │
└─────────────────────────────────────────────────────────────┘
```

### **4.5 JNI局部句柄分配流程**

```
JNI局部句柄分配:
┌─────────────────────────────────────────────────────────────┐
│ JNIHandles::make_local(thread, obj)                         │
│       │                                                     │
│       ▼                                                     │
│ 获取线程的active_handles (JNIHandleBlock)                   │
│       │                                                     │
│       ▼                                                     │
│ JNIHandleBlock::allocate_handle(obj)                        │
│       │                                                     │
│       ├── 检查空闲列表                                       │
│       │         │                                           │
│       │         ├── 有空闲槽 → 使用空闲槽                   │
│       │         │                                           │
│       │         └── 无空闲槽 → 继续                         │
│       │                                                     │
│       ├── 检查当前块是否有空间                               │
│       │         │                                           │
│       │         ├── _top < block_size_in_oops               │
│       │         │         │                                 │
│       │         │         └── 使用_handles[_top++]          │
│       │         │                                           │
│       │         └── 块已满 → 分配新块                       │
│       │                                                     │
│       └── 返回jobject (句柄指针)                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 🗄️ **5. OopStorage - 堆外对象引用存储**

### **5.1 概述**

`OopStorage`管理堆外对象引用的存储系统，支持GC对这些引用的迭代和处理。

**源文件**: `src/hotspot/share/gc/shared/oopStorage.hpp`

### **5.2 OopStorage结构**

```cpp
class OopStorage : public CHeapObj<mtGC> {
private:
  // ==================== 标识 ====================
  const char* _name;                  // 存储名称标识
  
  // ==================== Block管理 ====================
  ActiveArray* _active_array;         // 活跃Block数组
  AllocationList _allocation_list;    // 可分配Block的双向链表
  Block* volatile _deferred_updates;  // 延迟更新的Block链表
  
  // ==================== 同步 ====================
  Mutex* _allocation_mutex;           // 分配操作的互斥锁
  Mutex* _active_mutex;               // 活跃数组操作的互斥锁
  
  // ==================== 统计 ====================
  volatile size_t _allocation_count;  // 已分配entry数量
  
  // ==================== 并发支持 ====================
  SingleWriterSynchronizer _protect_active;  // 保护活跃数组的同步器
  mutable bool _concurrent_iteration_active; // 并发迭代是否活跃
  
public:
  // 构造函数
  OopStorage(const char* name, Mutex* allocation_mutex, Mutex* active_mutex);
  
  // 分配和释放
  oop* allocate();
  void release(const oop* ptr);
  
  // 迭代
  template<typename Closure>
  void oops_do(Closure* cl);
  
  // 弱引用处理
  template<typename IsAliveClosure, typename Closure>
  void weak_oops_do(IsAliveClosure* is_alive, Closure* cl);
  
  // 统计
  size_t allocation_count() const { return _allocation_count; }
};
```

### **5.3 OopStorage Block结构**

```cpp
class OopStorage::Block {
private:
  // 每个Block包含固定数量的oop槽位
  // 使用位图跟踪哪些槽位被使用
  
  oop _data[BitsPerWord];  // oop数组 (64个槽位)
  
  volatile uintx _allocated_bitmask;  // 已分配位图
  const OopStorage* _owner;           // 所属OopStorage
  void* _memory;                      // 原始内存指针
  
  Block* _active_next;                // 活跃链表下一个
  Block* _allocation_next;            // 分配链表下一个
  Block* _allocation_prev;            // 分配链表上一个
  Block* volatile* _deferred_updates_next;  // 延迟更新链表
};
```

### **5.4 OopStorage使用场景**

```
OopStorage使用场景:
┌─────────────────────────────────────────────────────────────┐
│ 1. JNI全局句柄                                               │
│    JNIHandles::_global_handles                              │
│    JNIHandles::_weak_global_handles                         │
│                                                             │
│ 2. 字符串表                                                  │
│    StringTable::_weak_handles                               │
│                                                             │
│ 3. 符号表                                                    │
│    (符号不是oop，但相关的引用使用OopStorage)                 │
│                                                             │
│ 4. 类加载器数据                                              │
│    ClassLoaderData的弱引用                                   │
│                                                             │
│ 5. 解析方法表                                                │
│    ResolvedMethodTable的弱引用                               │
└─────────────────────────────────────────────────────────────┘
```

### **5.5 GC与OopStorage交互**

```
GC处理OopStorage:
┌─────────────────────────────────────────────────────────────┐
│ GC标记阶段                                                   │
│       │                                                     │
│       ▼                                                     │
│ 遍历所有OopStorage                                           │
│       │                                                     │
│       ├── JNIHandles::_global_handles                       │
│       │         │                                           │
│       │         └── 强引用 → 标记所有引用的对象              │
│       │                                                     │
│       ├── JNIHandles::_weak_global_handles                  │
│       │         │                                           │
│       │         └── 弱引用 → 检查对象是否存活               │
│       │                   │                                 │
│       │                   ├── 存活 → 更新引用               │
│       │                   │                                 │
│       │                   └── 死亡 → 清除引用               │
│       │                                                     │
│       └── StringTable::_weak_handles                        │
│                 │                                           │
│                 └── 弱引用 → 清理死亡字符串                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 **6. ThreadLocalAllocBuffer (TLAB) - 线程本地分配缓冲区**

### **6.1 概述**

`TLAB`为每个线程提供独立的堆内存分配区域，避免多线程分配时的锁竞争。

**源文件**: `src/hotspot/share/gc/shared/threadLocalAllocBuffer.hpp`

### **6.2 TLAB结构**

```cpp
class ThreadLocalAllocBuffer: public CHeapObj<mtThread> {
private:
  // ==================== 边界指针 ====================
  HeapWord* _start;           // TLAB起始地址
  HeapWord* _top;             // 最后一次分配后的地址
  HeapWord* _pf_top;          // 预取水位线
  HeapWord* _end;             // 分配结束点(可能是采样点)
  HeapWord* _allocation_end;  // 实际TLAB结束点
  
  // ==================== 大小配置 ====================
  size_t _desired_size;       // 期望大小(含对齐保留)
  size_t _refill_waste_limit; // 重填浪费限制
  
  // ==================== 统计 ====================
  size_t _allocated_before_last_gc;   // 上次GC前分配的总字节数
  size_t _bytes_since_last_sample_point;  // 上次采样点后的字节数
  
  unsigned _number_of_refills;    // 重填次数
  unsigned _fast_refill_waste;    // 快速重填浪费
  unsigned _slow_refill_waste;    // 慢速重填浪费
  unsigned _gc_waste;             // GC浪费
  unsigned _slow_allocations;     // 慢速分配次数
  
  // ==================== 自适应调整 ====================
  AdaptiveWeightedAverage _allocation_fraction;  // Eden中TLAB分配比例
  
  // ==================== 静态成员 ====================
  static size_t   _max_size;                      // 任何TLAB的最大大小
  static int      _reserve_for_allocation_prefetch;  // TLAB末尾预留空间
  static unsigned _target_refills;                // GC间预期重填次数
  static GlobalTLABStats* _global_stats;          // 全局TLAB统计
  
public:
  // 分配对象
  HeapWord* allocate(size_t size) {
    HeapWord* obj = top();
    if (pointer_delta(_end, obj) >= size) {
      set_top(obj + size);
      return obj;
    }
    return NULL;  // TLAB空间不足
  }
  
  // 重填TLAB
  void fill(HeapWord* start, HeapWord* top, size_t new_size);
  
  // 统计
  size_t used() const { return pointer_delta(top(), start()); }
  size_t free() const { return pointer_delta(end(), top()); }
};
```

### **6.3 TLAB分配流程**

```
TLAB分配流程:
┌─────────────────────────────────────────────────────────────┐
│ 对象分配请求 (size字节)                                      │
│       │                                                     │
│       ▼                                                     │
│ 检查TLAB是否有足够空间                                       │
│       │                                                     │
│       ├── _top + size <= _end?                              │
│       │         │                                           │
│       │         ├── 是 → 快速分配 (无锁)                    │
│       │         │         │                                 │
│       │         │         ├── obj = _top                    │
│       │         │         ├── _top += size                  │
│       │         │         └── return obj                    │
│       │         │                                           │
│       │         └── 否 → TLAB空间不足                       │
│       │                   │                                 │
│       │                   ▼                                 │
│       │             检查剩余空间是否值得保留                 │
│       │                   │                                 │
│       │                   ├── 剩余 < refill_waste_limit     │
│       │                   │         │                       │
│       │                   │         └── 丢弃并重填TLAB      │
│       │                   │                                 │
│       │                   └── 剩余 >= refill_waste_limit    │
│       │                             │                       │
│       │                             └── 慢速分配(堆直接分配)│
│       │                                                     │
│       └── 重填TLAB                                          │
│                 │                                           │
│                 ├── 从Eden区分配新TLAB空间                  │
│                 │                                           │
│                 ├── 更新TLAB边界指针                        │
│                 │                                           │
│                 └── 在新TLAB中分配对象                      │
└─────────────────────────────────────────────────────────────┘
```

### **6.4 TLAB内存布局**

```
TLAB内存布局:
┌─────────────────────────────────────────────────────────────┐
│ Eden区                                                       │
│ ┌───────────────────────────────────────────────────────┐   │
│ │ Thread1 TLAB │ Thread2 TLAB │ Thread3 TLAB │ 空闲空间 │   │
│ └───────────────────────────────────────────────────────┘   │
│                                                             │
│ 单个TLAB:                                                    │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ _start                                       _allocation_end│
│ │    │                                              │       │
│ │    ▼                                              ▼       │
│ │ ┌──────────────────────────────────────────────────────┐ │
│ │ │ 已分配对象 │ 已分配对象 │ ... │ 空闲空间 │ 预留空间 │ │
│ │ └──────────────────────────────────────────────────────┘ │
│ │                           ▲           ▲                   │
│ │                           │           │                   │
│ │                         _top        _end                  │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### **6.5 TLAB大小配置**

```cpp
// TLAB相关JVM参数:
// -XX:+UseTLAB              启用TLAB (默认开启)
// -XX:TLABSize=0            初始TLAB大小 (0表示自动)
// -XX:MinTLABSize=2048      最小TLAB大小
// -XX:TLABRefillWasteFraction=64  重填浪费比例
// -XX:TLABWasteTargetPercent=1    目标浪费百分比
// -XX:TLABWasteIncrement=4        浪费增量

// 8GB堆环境下的典型TLAB配置:
// - 初始大小: ~256KB
// - 最大大小: ~4MB
// - 重填浪费限制: TLAB大小 / 64
```

---

## 📊 **7. PerfData - 性能数据系统**

### **7.1 概述**

`PerfData`是JVM性能数据收集系统，支持JVM性能监控(jvmstat)，数据可通过共享内存暴露给外部工具。

**源文件**: `src/hotspot/share/runtime/perfData.hpp`

### **7.2 PerfData结构**

```cpp
class PerfData : public CHeapObj<mtInternal> {
public:
  // ==================== 变化性枚举 ====================
  enum Variability {
    V_Constant = 1,    // 常量，初始化后不变
    V_Monotonic = 2,   // 单调递增
    V_Variable = 3     // 可变
  };
  
  // ==================== 单位枚举 ====================
  enum Units {
    U_None = 1,        // 无单位
    U_Bytes = 2,       // 字节
    U_Ticks = 3,       // 时钟周期
    U_Events = 4,      // 事件数
    U_String = 5,      // 字符串
    U_Hertz = 6        // 赫兹
  };
  
  // ==================== 标志枚举 ====================
  enum Flags {
    F_None = 0x0,
    F_Supported = 0x1  // 支持的计数器
  };
  
private:
  char* _name;              // 计数器名称
  Variability _v;           // 变化性
  Units _u;                 // 单位
  bool _on_c_heap;          // 是否在C堆上分配
  Flags _flags;             // 标志位
  PerfDataEntry* _pdep;     // PerfData内存区域中的entry
  
protected:
  void* _valuep;            // 数据值指针
};

// 具体类型
class PerfLong : public PerfData {
protected:
  jlong* _valuep;
};

class PerfLongCounter : public PerfLong {
  // 单调递增的long计数器
};

class PerfLongVariable : public PerfLong {
  // 可变的long值
};

class PerfString : public PerfData {
protected:
  char* _valuep;
};
```

### **7.3 PerfDataManager结构**

```cpp
class PerfDataManager : AllStatic {
private:
  // ==================== PerfData列表 ====================
  static PerfDataList* _all;        // 所有PerfData项列表
  static PerfDataList* _sampled;    // 需要采样的PerfData列表
  static PerfDataList* _constants;  // 常量PerfData列表
  
  // ==================== 状态 ====================
  static volatile bool _has_PerfData;  // 是否有PerfData
  
public:
  // 创建计数器
  static PerfLongCounter* create_long_counter(CounterNS ns, const char* name,
                                               PerfData::Units u, jlong* sp);
  
  static PerfLongVariable* create_long_variable(CounterNS ns, const char* name,
                                                 PerfData::Units u, jlong* sp);
  
  static PerfStringVariable* create_string_variable(CounterNS ns, const char* name,
                                                     const char* s);
  
  // 查找计数器
  static PerfData* find_by_name(const char* name);
  
  // 采样
  static void sample();
};
```

### **7.4 常见性能计数器**

```
JVM性能计数器示例:
┌─────────────────────────────────────────────────────────────┐
│ 命名空间          │ 计数器名称              │ 说明          │
├─────────────────────────────────────────────────────────────┤
│ java.cls          │ loadedClasses           │ 已加载类数    │
│                   │ unloadedClasses         │ 已卸载类数    │
├─────────────────────────────────────────────────────────────┤
│ java.gc           │ collector.0.invocations │ GC调用次数    │
│                   │ collector.0.time        │ GC时间        │
├─────────────────────────────────────────────────────────────┤
│ java.threads      │ live                    │ 活动线程数    │
│                   │ daemon                  │ 守护线程数    │
│                   │ peak                    │ 峰值线程数    │
├─────────────────────────────────────────────────────────────┤
│ sun.gc.generation │ 0.space.0.used          │ Eden使用量    │
│                   │ 0.space.1.used          │ S0使用量      │
│                   │ 1.space.0.used          │ Old使用量     │
├─────────────────────────────────────────────────────────────┤
│ sun.rt            │ createVmBeginTime       │ VM创建开始时间│
│                   │ createVmEndTime         │ VM创建结束时间│
│                   │ vmInitDoneTime          │ VM初始化完成时间│
└─────────────────────────────────────────────────────────────┘
```

### **7.5 PerfMemory共享内存**

```
PerfMemory结构:
┌─────────────────────────────────────────────────────────────┐
│ 共享内存文件: /tmp/hsperfdata_<user>/<pid>                   │
│                                                             │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ PerfDataPrologue (头部)                                 │ │
│ │   magic, byte_order, major_version, minor_version       │ │
│ │   accessible, used, overflow, mod_time_stamp            │ │
│ │   entry_offset, num_entries                             │ │
│ ├─────────────────────────────────────────────────────────┤ │
│ │ PerfDataEntry 1                                         │ │
│ │   entry_length, name_offset, vector_length              │ │
│ │   data_type, flags, data_units, data_variability        │ │
│ │   data_offset, name, data                               │ │
│ ├─────────────────────────────────────────────────────────┤ │
│ │ PerfDataEntry 2                                         │ │
│ │   ...                                                   │ │
│ ├─────────────────────────────────────────────────────────┤ │
│ │ ...                                                     │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                             │
│ 外部工具(jstat, jps等)可以mmap这个文件读取性能数据          │
└─────────────────────────────────────────────────────────────┘
```

---

## 📈 **内存占用汇总**

| 子系统 | 组件 | 典型大小 | 说明 |
|--------|------|----------|------|
| Arena | 每个Arena | 1KB-32KB | 取决于使用情况 |
| ResourceArea | 每线程 | 1KB-64KB | 临时数据 |
| HandleArea | 每线程 | 256B-4KB | 句柄存储 |
| JNIHandleBlock | 每块 | ~256B | 32个句柄 |
| OopStorage | 全局句柄 | ~64KB | 取决于全局引用数 |
| OopStorage | 弱全局句柄 | ~32KB | 取决于弱引用数 |
| TLAB | 每线程 | 256KB-4MB | 自适应调整 |
| PerfMemory | 共享内存 | ~32KB | 性能计数器 |

### **8GB堆环境下的典型配置**

```
内存分配系统配置:
┌─────────────────────────────────────────────────────────────┐
│ TLAB配置:                                                    │
│   - 初始大小: ~256KB                                         │
│   - 最大大小: ~4MB                                           │
│   - 每线程: 1个TLAB                                          │
│   - 10个Java线程 ≈ 2.5MB-40MB TLAB空间                      │
├─────────────────────────────────────────────────────────────┤
│ ResourceArea配置:                                            │
│   - 每线程: 1个ResourceArea                                  │
│   - 初始chunk: 256B (tiny)                                   │
│   - 扩展chunk: 32KB                                          │
├─────────────────────────────────────────────────────────────┤
│ HandleArea配置:                                              │
│   - 每线程: 1个HandleArea                                    │
│   - 初始chunk: 256B (tiny)                                   │
├─────────────────────────────────────────────────────────────┤
│ JNIHandleBlock配置:                                          │
│   - 每块: 32个句柄                                           │
│   - 按需分配新块                                             │
├─────────────────────────────────────────────────────────────┤
│ OopStorage配置:                                              │
│   - 全局句柄: 按需增长                                       │
│   - 每Block: 64个槽位                                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 🎯 **总结**

本文档补充了之前遗漏的JVM内存分配和句柄管理系统核心对象：

1. **Arena** - 快速内存分配器，使用Chunk链表管理
2. **ResourceArea** - 线程本地临时数据存储，支持ResourceMark批量释放
3. **HandleArea** - GC安全的句柄分配区域
4. **Handle/HandleMark** - RAII风格的oop引用保护
5. **JNIHandles** - JNI句柄管理的静态接口
6. **JNIHandleBlock** - JNI局部句柄的块存储
7. **OopStorage** - 堆外对象引用的通用存储系统
8. **TLAB** - 线程本地分配缓冲区，无锁快速分配
9. **PerfData/PerfDataManager** - 性能数据收集和共享内存暴露

这些子系统共同实现了JVM高效的内存分配和对象引用管理，是理解JVM内部工作原理的关键组件。
