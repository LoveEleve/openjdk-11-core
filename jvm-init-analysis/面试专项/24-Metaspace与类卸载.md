# JVM技术专家面试 - Metaspace与类卸载

## 面试题1：Metaspace的内存结构是什么样的？

**难度**：⭐⭐⭐⭐⭐

### 面试官问：

JDK 8用Metaspace替代了PermGen，Metaspace的内存是如何组织的？与PermGen有什么本质区别？

### 答题要点：

#### 1. PermGen vs Metaspace

| 特性 | PermGen (JDK7-) | Metaspace (JDK8+) |
|------|----------------|-------------------|
| 位置 | Java堆内 | Native内存 |
| 默认大小 | 固定（如64MB） | 无上限（受物理内存限制） |
| GC | Full GC时回收 | 类卸载时回收 |
| OOM信息 | PermGen space | Metaspace |
| 碎片化 | 容易 | Chunk机制减少 |

#### 2. Metaspace层次结构

从源码可以看到分层设计：

```
┌─────────────────────────────────────────────────────────────┐
│                     Metaspace Overview                       │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ ClassLoader1 │  │ ClassLoader2 │  │ ClassLoader3 │      │
│  │ Metaspace    │  │ Metaspace    │  │ Metaspace    │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                 │                 │               │
│         ▼                 ▼                 ▼               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              SpaceManager (per CLD)                  │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐                │   │
│  │  │ Chunk1  │ │ Chunk2  │ │ Chunk3  │                │   │
│  │  └─────────┘ └─────────┘ └─────────┘                │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              ChunkManager (global)                   │   │
│  │   管理空闲Chunk列表，按大小分级                        │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │            VirtualSpaceList (global)                 │   │
│  │   管理从OS申请的大块虚拟内存                          │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

#### 3. 核心组件

**VirtualSpaceList**：管理从OS申请的虚拟空间

```cpp
// metaspace/virtualSpaceList.hpp
class VirtualSpaceList : public CHeapObj<mtClass> {
  VirtualSpaceNode* _virtual_space_list;  // 链表
  VirtualSpaceNode* _current_virtual_space;
  
  // 申请新的虚拟空间
  VirtualSpaceNode* create_new_virtual_space(size_t vs_word_size);
};
```

**ChunkManager**：管理空闲Chunk

```cpp
// Chunk大小分级
enum ChunkIndex {
  SpecializedIndex = 0,  // 128 words
  SmallIndex = 1,        // 512 words  
  MediumIndex = 2,       // 8K words
  HumongousIndex = 3     // > 8K words
};
```

**SpaceManager**：每个ClassLoader的元空间管理器

```cpp
class SpaceManager : public CHeapObj<mtClass> {
  Metachunk* _chunk_list;      // 已分配的chunk列表
  Metachunk* _current_chunk;   // 当前正在使用的chunk
  
  // 分配元数据
  MetaWord* allocate(size_t word_size);
};
```

#### 4. Metaspace类型

```cpp
// metaspace.cpp
enum MetaspaceType {
  StandardMetaspaceType,    // 普通类加载器
  BootMetaspaceType,        // Bootstrap类加载器
  AnonymousMetaspaceType,   // 匿名类（Lambda等）
  ReflectionMetaspaceType   // 反射生成的类
};
```

每种类型有不同的默认Chunk大小策略：

```cpp
// Boot类加载器：初始chunk更大（减少分配次数）
// Anonymous类：chunk更小（类数量多但每个小）
```

---

## 面试题2：什么时候会触发Metaspace的GC？

**难度**：⭐⭐⭐⭐

### 面试官问：

Metaspace不在Java堆中，它的GC是如何触发的？

### 答题要点：

#### 1. 触发条件

从源码可以看到，Metaspace有一个动态的GC阈值：

```cpp
// metaspace.cpp
volatile size_t MetaspaceGC::_capacity_until_GC = 0;

// 当已提交的字节数接近阈值时，触发GC
bool MetaspaceGC::can_expand(size_t word_size, bool is_class) {
  size_t committed_bytes = MetaspaceUtils::committed_bytes();
  
  // 检查是否超过MaxMetaspaceSize
  if (committed_bytes + word_size * BytesPerWord > MaxMetaspaceSize) {
    return false;  // 不能扩展，触发GC
  }
  
  return true;
}
```

#### 2. GC阈值的动态调整

```cpp
// 类似于Java堆的自适应调整
void MetaspaceGC::compute_new_size() {
  const size_t used_after_gc = MetaspaceUtils::committed_bytes();
  const size_t capacity_until_GC = MetaspaceGC::capacity_until_GC();
  
  // 基于MinMetaspaceFreeRatio计算最小需求
  const double minimum_free_percentage = MinMetaspaceFreeRatio / 100.0;
  size_t minimum_desired_capacity = used_after_gc / (1.0 - minimum_free_percentage);
  
  if (capacity_until_GC < minimum_desired_capacity) {
    // 需要扩展阈值
    size_t expand_bytes = minimum_desired_capacity - capacity_until_GC;
    MetaspaceGC::inc_capacity_until_GC(expand_bytes, ...);
  } else if (capacity_until_GC > maximum_desired_capacity) {
    // 可以收缩阈值（渐进式）
    // 0% → 10% → 40% → 100%
    shrink_bytes = shrink_bytes / 100 * current_shrink_factor;
  }
}
```

#### 3. GC触发流程

```
元数据分配请求
     │
     ▼
检查SpaceManager中是否有空间
     │
     ├── 有空间 → 直接分配
     │
     └── 无空间 → 尝试从ChunkManager获取新Chunk
                      │
                      ├── 获取成功 → 分配
                      │
                      └── 获取失败 → 尝试扩展VirtualSpace
                                          │
                                          ├── 可以扩展 → 扩展后分配
                                          │
                                          └── 达到capacity_until_GC
                                                  │
                                                  ▼
                                              触发GC
                                         VM_CollectForMetadataAllocation
```

#### 4. 相关参数

```bash
# 初始Metaspace大小（影响首次GC时机）
-XX:MetaspaceSize=256m

# 最大Metaspace大小
-XX:MaxMetaspaceSize=512m

# 扩展步长
-XX:MinMetaspaceExpansion=256k
-XX:MaxMetaspaceExpansion=4m

# 空闲比例（影响收缩）
-XX:MinMetaspaceFreeRatio=40
-XX:MaxMetaspaceFreeRatio=70

# Compressed Class Space（64位且UseCompressedClassPointers）
-XX:CompressedClassSpaceSize=1g
```

---

## 面试题3：类卸载的条件和过程是什么？

**难度**：⭐⭐⭐⭐⭐

### 面试官问：

什么情况下类会被卸载？卸载过程中发生了什么？

### 答题要点：

#### 1. 类卸载的条件

**三个条件必须同时满足**：

1. **该类的所有实例都已被GC**
2. **该类的ClassLoader已经被GC**
3. **该类的java.lang.Class对象没有任何引用**

从源码角度，关键在于ClassLoader：

```cpp
// classLoaderData.hpp
class ClassLoaderData {
  WeakHandle<vm_class_loader_data> _holder;  // 弱引用到ClassLoader
  bool _unloading;  // 标记是否正在卸载
  
  // 当ClassLoader不可达时，CLD变为unloading
};
```

#### 2. ClassLoaderDataGraph的作用

```cpp
// classLoaderData.hpp
class ClassLoaderDataGraph : public AllStatic {
  static ClassLoaderData* _head;       // 所有活跃的CLD
  static ClassLoaderData* _unloading;  // 正在卸载的CLD
  
  // 标记所有不可达的CLD
  static void clean_metaspaces();
  
  // 回收内存
  static void purge(bool at_safepoint);
};
```

#### 3. 卸载过程

```cpp
// classLoaderData.cpp
void ClassLoaderData::unload() {
  _unloading = true;
  
  // 1. 释放deallocate_list中的C heap内存
  unload_deallocate_list();
  
  // 2. 通知服务工具（JVMTI等）
  classes_do(InstanceKlass::notify_unload_class);
  
  // 3. 清理编译器状态
  static_klass_iterator.adjust_saved_class(this);
}

void ClassLoaderData::~ClassLoaderData() {
  // 释放Metaspace
  delete _metaspace;
  _metaspace = NULL;
  
  // 释放其他资源：Dictionary, ModuleEntryTable等
}
```

#### 4. Metaspace回收

```cpp
// 当CLD被卸载时，其Metaspace的所有Chunk返回给ChunkManager
ClassLoaderMetaspace::~ClassLoaderMetaspace() {
  // 将所有chunk返还到全局空闲列表
  _vsm->deallocate_chunks();     // 非class空间
  _class_vsm->deallocate_chunks(); // class空间
}
```

#### 5. 类卸载时序图

```
GC Marking Phase:
┌────────────────────────────────────────────────────────────┐
│ 1. 标记所有活跃对象                                         │
│ 2. 遍历ClassLoaderDataGraph                                │
│    - 如果CLD的holder不可达，标记CLD为unloading              │
└────────────────────────────────────────────────────────────┘
                           │
                           ▼
Unloading Phase (at safepoint):
┌────────────────────────────────────────────────────────────┐
│ 3. ClassLoaderData::unload()                               │
│    - 设置_unloading = true                                 │
│    - 通知类卸载事件                                        │
│    - 清理内部数据结构                                      │
└────────────────────────────────────────────────────────────┘
                           │
                           ▼
Purge Phase:
┌────────────────────────────────────────────────────────────┐
│ 4. ClassLoaderDataGraph::purge()                           │
│    - 从链表中移除unloading的CLD                            │
│    - 删除CLD对象，释放Metaspace                            │
│    - Chunk返还给ChunkManager                               │
└────────────────────────────────────────────────────────────┘
                           │
                           ▼
Memory Release:
┌────────────────────────────────────────────────────────────┐
│ 5. 如果VirtualSpace完全空闲                                │
│    - 可能将内存归还给OS（取决于配置）                       │
└────────────────────────────────────────────────────────────┘
```

---

## 面试题4：为什么Bootstrap类加载器加载的类不会被卸载？

**难度**：⭐⭐⭐

### 面试官问：

Bootstrap ClassLoader加载的类（如java.lang.String）永远不会被卸载，为什么？

### 答题要点：

#### 1. Bootstrap CLD的特殊性

```cpp
// classLoaderData.cpp
ClassLoaderData* ClassLoaderData::_the_null_class_loader_data = NULL;

// Bootstrap ClassLoader的CLD
void ClassLoaderData::init_null_class_loader_data() {
  _the_null_class_loader_data = new ClassLoaderData(Handle(), false);
  // 注意：第一个参数是空Handle，表示没有java.lang.ClassLoader对象
}
```

#### 2. 为什么不会被卸载

**原因1**：Bootstrap CLD没有对应的Java ClassLoader对象

```cpp
// GC不会标记null ClassLoader为不可达
// 因为它从一开始就不是一个普通对象
```

**原因2**：VM内部持有强引用

```cpp
// universe.cpp
class Universe : AllStatic {
  static Klass* _typeArrayKlassObjs[T_LONG+1];  // 基本类型数组
  static Klass* _objectArrayKlassObj;
  // 这些Klass存在于Bootstrap Metaspace
};
```

**原因3**：安全和正确性

```
如果java.lang.Object被卸载：
- 所有对象的父类没了
- instanceof检查失败
- 整个类型系统崩溃
```

#### 3. 实际影响

```java
// 这些类永远不会被卸载：
java.lang.Object
java.lang.String
java.lang.Class
java.lang.Thread
// ... 所有rt.jar/java.base模块中的类
```

---

## 面试题5：Metaspace碎片化问题如何解决？

**难度**：⭐⭐⭐⭐

### 面试官问：

Metaspace使用Chunk机制，但仍可能产生碎片。JVM如何处理？

### 答题要点：

#### 1. 碎片化的来源

```
场景：大量短命的ClassLoader（如每次请求创建ClassLoader）

ClassLoader1 → CLD1 → Chunk[A][B][C]
ClassLoader2 → CLD2 → Chunk[D][E]
ClassLoader3 → CLD3 → Chunk[F]

ClassLoader1 unload → Chunk[A][B][C]返还
ClassLoader3 unload → Chunk[F]返还

ChunkManager: FreeList = [A, B, C, F]  （不连续）

新的大分配请求可能无法满足，即使总空闲空间足够
```

#### 2. JVM的应对策略

**策略1：Chunk大小分级**

```cpp
// 不同大小的Chunk有独立的空闲列表
enum ChunkIndex {
  SpecializedIndex = 0,  // 128 words  (~1KB)
  SmallIndex = 1,        // 512 words  (~4KB)
  MediumIndex = 2,       // 8K words   (~64KB)
  HumongousIndex = 3     // 自定义大小
};

// 优先使用合适大小的Chunk，减少内部碎片
```

**策略2：Chunk合并**

```cpp
// 相邻的空闲Chunk可以合并成更大的Chunk
// 但实现较复杂，JDK 11中有限支持
```

**策略3：VirtualSpace回收**

```cpp
// 当整个VirtualSpace都空闲时，可以归还给OS
// 减少长期的内存占用
```

#### 3. 监控和调优

```bash
# 查看Metaspace碎片状况
jcmd <pid> VM.metaspace

# 输出示例：
# Total Usage:
#   reserved:  1073741824  committed:   268435456  used:   215645872
# Virtual space list:
#   node count: 5
#   [...]
# Chunk manager statistics:
#   In use chunks: 1234  total word size: 12345678
#   Free chunks:   567   total word size:  1234567  ← 关注碎片
```

#### 4. 最佳实践

```
1. 避免创建过多临时ClassLoader
2. 合理设置-XX:MaxMetaspaceSize防止无限增长
3. 使用-XX:MinMetaspaceFreeRatio/-XX:MaxMetaspaceFreeRatio控制空间
4. 监控Metaspace使用，及时发现泄漏
```

---

## 面试题6：Compressed Class Space的作用是什么？

**难度**：⭐⭐⭐⭐

### 面试官问：

64位JVM启用压缩指针时，有一个独立的Compressed Class Space。它是什么？为什么需要？

### 答题要点：

#### 1. 背景：压缩类指针

```cpp
// 对象头中的klass指针
class oopDesc {
  markOop _mark;
  union {
    Klass* _klass;           // 64位：8字节
    narrowKlass _compressed_klass;  // 压缩：4字节
  };
};
```

#### 2. 为什么需要独立空间

```
压缩类指针的原理：
  实际地址 = base + (压缩值 << shift)
  
为了让压缩有效，所有Klass必须在一个连续的、有限大小的区域内：
  - 默认1GB（CompressedClassSpaceSize）
  - 32位压缩指针最大寻址4GB（shift=0时）
```

#### 3. 内存布局

```
┌────────────────────────────────────────────────────────────┐
│                    Native Memory                            │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         Compressed Class Space (1GB)                │   │
│  │  ┌─────────────────────────────────────────────┐   │   │
│  │  │  Klass*  │  Klass*  │  Klass*  │  ...       │   │   │
│  │  └─────────────────────────────────────────────┘   │   │
│  │  base address ──────────────────────────────────   │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │         Non-Class Metaspace (无限制)                │   │
│  │  ┌─────────────────────────────────────────────┐   │   │
│  │  │ Method* │ ConstantPool* │ Annotations │ ... │   │   │
│  │  └─────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

#### 4. 存储内容

**Compressed Class Space存储**：
- Klass结构（类的VM表示）
- vtable
- itable

**Non-Class Metaspace存储**：
- Method结构
- ConstantPool
- MethodData
- Annotations
- 其他元数据

#### 5. 参数配置

```bash
# 压缩类空间大小（默认1GB）
-XX:CompressedClassSpaceSize=256m

# 禁用压缩类指针（不建议）
-XX:-UseCompressedClassPointers

# 压缩类空间满会导致OOM
# java.lang.OutOfMemoryError: Compressed class space
```

#### 6. 常见问题

**问题**：类很多时Compressed Class Space不够

```
症状：
java.lang.OutOfMemoryError: Compressed class space

解决：
1. 增大-XX:CompressedClassSpaceSize（最大3GB左右）
2. 检查是否有类加载泄漏
3. 极端情况：禁用-XX:-UseCompressedClassPointers
```

---

## 总结：Metaspace关键知识点

### 内存层次

```
应用层    ClassLoaderData → 每个ClassLoader有独立的CLD
            ↓
管理层    SpaceManager → 管理该CLD的Chunk分配
            ↓
缓存层    ChunkManager → 全局空闲Chunk管理
            ↓
系统层    VirtualSpaceList → 向OS申请/释放内存
```

### 关键参数速查

| 参数 | 作用 | 默认值 |
|------|------|--------|
| -XX:MetaspaceSize | 初始阈值 | ~21MB |
| -XX:MaxMetaspaceSize | 最大限制 | 无限制 |
| -XX:CompressedClassSpaceSize | 压缩类空间 | 1GB |
| -XX:MinMetaspaceFreeRatio | 最小空闲比 | 40% |
| -XX:MaxMetaspaceFreeRatio | 最大空闲比 | 70% |

### 类卸载条件

```
可卸载 = 
    ClassLoader不可达 
    && 所有实例被GC 
    && Class对象无引用
    && ClassLoader不是Bootstrap
```

### 监控命令

```bash
# 实时状态
jstat -gc <pid>
jcmd <pid> VM.metaspace

# 详细dump
jcmd <pid> GC.class_stats
```
