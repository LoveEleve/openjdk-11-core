# JVM面试专项：GC Root枚举与OopMap

> **面试级别**: JVM技术专家  
> **环境**: Linux, OpenJDK 11, -Xms8g -Xmx8g -XX:+UseG1GC  
> **源码路径**: `/src/hotspot/share/compiler/`, `/src/hotspot/share/gc/`

---

## 问题1：什么是GC Root？JVM中有哪些GC Root？

### 面试官视角
考察对可达性分析基础的理解。

### 参考答案

#### GC Root定义

GC Root是垃圾回收的起点，从这些对象出发进行可达性分析，不可达的对象将被回收。

#### JVM中的GC Root类型

**源码位置**: `gc/shared/strongRootsScope.cpp`

```cpp
// GC Root类型
enum RootType {
  _threads,                    // 线程栈
  _code_cache,                 // 代码缓存
  _universe,                   // 系统类
  _jni_handles,               // JNI全局句柄
  _jfr_leak_profiler,         // JFR
  _object_synchronizer,       // 同步器
  _management,                // 管理
  _system_dictionary,         // 系统字典
  _cld_do_roots,              // 类加载器数据
  _class_loader_data_graph,   // 类加载器图
  _jvmti,                     // JVMTI
  _string_table,              // 字符串常量池
  _vm_weak_handles,           // VM弱引用
};
```

#### 详细分类

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              GC Root 分类                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 虚拟机栈中的引用                                                         │
│     - 局部变量表中的对象引用                                                  │
│     - 操作数栈中的对象引用                                                    │
│                                                                             │
│  2. 本地方法栈中的JNI引用                                                    │
│     - JNI全局引用(Global References)                                        │
│     - JNI本地引用(Local References)                                         │
│                                                                             │
│  3. 方法区中的静态引用                                                       │
│     - 类的静态字段引用的对象                                                  │
│                                                                             │
│  4. 方法区中的常量引用                                                       │
│     - 字符串常量池中的引用                                                    │
│                                                                             │
│  5. 同步锁持有的对象                                                         │
│     - synchronized锁定的对象                                                │
│                                                                             │
│  6. JVM内部引用                                                             │
│     - 基本类型对应的Class对象                                                │
│     - 常驻异常对象(NullPointerException等)                                  │
│     - 系统类加载器                                                          │
│                                                                             │
│  7. JMXBean、JVMTI回调、本地代码缓存等                                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Root枚举入口

**源码位置**: `gc/shared/gcRootsClosure.hpp`

```cpp
// 遍历所有GC Root
void StrongRootsScope::process_roots(OopClosure* oops) {
  // 1. 线程栈
  Threads::oops_do(oops, NULL);
  
  // 2. JNI句柄
  JNIHandles::oops_do(oops);
  
  // 3. 系统字典
  SystemDictionary::oops_do(oops);
  
  // 4. 代码缓存
  CodeCache::blobs_do(oops);
  
  // 5. 同步器(ObjectSynchronizer)
  ObjectSynchronizer::oops_do(oops);
  
  // 6. 字符串常量池
  StringTable::oops_do(oops);
  
  // ... 其他根
}
```

---

## 问题2：OopMap是什么？为什么需要它？

### 面试官视角
考察对准确式GC实现的理解。

### 参考答案

#### OopMap定义

OopMap记录了栈帧和寄存器中哪些位置存储着对象引用(oop)，GC使用它来精确定位Root。

#### 为什么需要OopMap？

```
问题: GC如何知道栈上的值是引用还是普通数据？

栈内存:
┌─────────────────┐
│    0x12345678   │  ← 这是对象地址还是int值？
├─────────────────┤
│    0x00000042   │  ← 这是对象地址还是int值？
├─────────────────┤
│    0xABCDEF00   │  ← 这是对象地址还是int值？
└─────────────────┘

解决方案1: 保守式GC
- 把所有看起来像指针的值都当作引用
- 问题: 可能误判，导致内存泄漏

解决方案2: 准确式GC (HotSpot采用)
- 编译时记录哪些位置是引用
- 这个记录就是OopMap
```

#### OopMap结构

**源码位置**: `compiler/oopMap.hpp`

```cpp
class OopMapValue {
  // 记录一个引用的位置
  short _content_reg;   // 寄存器或栈偏移
  
  enum oop_types {
    unused_value = 0,
    oop_value,           // 普通引用
    narrowoop_value,     // 压缩引用
    callee_saved_value,  // 被调用者保存的寄存器
    derived_oop_value    // 派生指针
  };
};

class OopMap : public ResourceObj {
  // 一个SafePoint的所有引用位置
  CompressedWriteStream* _write_stream;  // 压缩存储
  
  void set_oop(VMReg reg);       // 记录引用位置
  void set_narrowoop(VMReg reg); // 记录压缩引用位置
};

class OopMapSet : public ResourceObj {
  // 一个方法的所有OopMap集合
  // 每个SafePoint一个OopMap
  GrowableArray<OopMap*> _list;
};
```

---

## 问题3：OopMap是如何生成的？

### 面试官视角
考察对编译器生成OopMap过程的理解。

### 参考答案

#### 编译期间生成

**源码位置**: `opto/compile.cpp`

```cpp
// C2编译器在生成代码时同时生成OopMap
void Compile::Compile(...) {
  // ...
  
  // 每个SafePoint生成OopMap
  for (int i = 0; i < _cfg->number_of_blocks(); i++) {
    Block* block = _cfg->get_block(i);
    for (uint j = 0; j < block->number_of_nodes(); j++) {
      Node* n = block->get_node(j);
      if (n->is_MachSafePoint()) {
        // 生成OopMap
        MachSafePointNode* safepoint = n->as_MachSafePoint();
        OopMap* map = new OopMap(_frame_size, _arg_count);
        
        // 记录所有活跃的引用
        for (uint k = 0; k < safepoint->num_opnds(); k++) {
          MachOper* oper = safepoint->in(k);
          if (is_oop(oper)) {
            VMReg reg = oper->reg();
            map->set_oop(reg);
          }
        }
        
        _oop_map_set->add(map);
      }
    }
  }
}
```

#### SafePoint位置

```java
// OopMap只在SafePoint位置生成
void method() {
    Object a = new Object();  // 不是SafePoint
    a.foo();                  // 方法调用后是SafePoint ← 有OopMap
    for (...) {               // 循环回边是SafePoint ← 有OopMap
        // ...
    }
    return;                   // 方法返回前是SafePoint ← 有OopMap
}
```

#### OopMap存储位置

```cpp
// OopMap存储在nmethod中
class nmethod : public CompiledMethod {
  // OopMapSet紧跟在代码后面
  OopMapSet* _oop_maps;
  
  // 通过PC地址查找对应的OopMap
  OopMap* oop_map_for_return_address(address return_address);
};
```

---

## 问题4：GC时如何使用OopMap枚举Root？

### 面试官视角
考察OopMap在GC中的实际应用。

### 参考答案

#### 线程栈扫描流程

**源码位置**: `runtime/thread.cpp`

```cpp
void JavaThread::oops_do(OopClosure* f, CodeBlobClosure* cf) {
  // 确保线程在SafePoint停止
  assert(is_gc_safe(), "should be at safepoint");
  
  // 遍历栈帧
  for (StackFrameStream fst(this); !fst.is_done(); fst.next()) {
    fst.current()->oops_do(f, cf, fst.register_map());
  }
  
  // Handle区域
  _handles.oops_do(f);
}
```

#### 栈帧扫描

**源码位置**: `runtime/frame.cpp`

```cpp
void frame::oops_do(OopClosure* f, CodeBlobClosure* cf, RegisterMap* map) {
  if (is_interpreted_frame()) {
    // 解释器帧: 使用解释器的布局信息
    oops_interpreted_do(f, map);
  } else if (is_compiled_frame()) {
    // 编译帧: 使用OopMap
    oops_compiled_arguments_do(f, map);
    
    // 获取当前PC对应的OopMap
    nmethod* nm = code()->as_nmethod();
    OopMap* map = nm->oop_map_for_return_address(pc());
    
    // 使用OopMap扫描引用
    OopMapStream oms(map);
    while (!oms.is_done()) {
      OopMapValue v = oms.current();
      if (v.is_oop()) {
        // 获取引用的地址
        oop* loc = (oop*)reg_to_loc(v.reg(), map);
        // 处理引用
        f->do_oop(loc);
      }
      oms.next();
    }
  }
}
```

#### 寄存器处理

```cpp
// OopMap中的VMReg可能是:
// 1. 寄存器 (如rax, rbx)
// 2. 栈偏移 (如[rbp-16])

address frame::reg_to_loc(VMReg reg, RegisterMap* map) {
  if (reg->is_stack()) {
    // 栈上位置
    return (address)sp() + reg->reg2stack() * VMRegImpl::stack_slot_size;
  } else {
    // 寄存器位置
    return map->location(reg);
  }
}
```

---

## 问题5：解释器帧如何进行Root枚举？

### 面试官视角
解释器帧没有OopMap，如何处理？

### 参考答案

#### 解释器帧的布局已知

```cpp
// 解释器栈帧布局是固定的，不需要OopMap

// 源码位置: interpreter/abstractInterpreter.hpp
class AbstractInterpreter {
  // 布局信息
  static int local_offset_in_bytes(int index);       // 局部变量偏移
  static int expression_stack_offset_in_bytes(int); // 表达式栈偏移
  static int monitor_offset_in_bytes(int index);    // 监视器偏移
};
```

#### 解释器帧扫描

**源码位置**: `runtime/frame.cpp`

```cpp
void frame::oops_interpreted_do(OopClosure* f, RegisterMap* map) {
  // 1. 局部变量表
  int max_locals = method()->max_locals();
  for (int i = 0; i < max_locals; i++) {
    intptr_t* addr = interpreter_frame_local_at(i);
    // 使用签名信息判断是否是引用
    if (method()->is_local_oop(bci(), i)) {
      f->do_oop((oop*)addr);
    }
  }
  
  // 2. 表达式栈
  int max_stack = method()->max_stack();
  // 使用当前bci的栈映射判断哪些是引用
  StackMapFrame* frame = method()->get_stack_map_frame(bci());
  for (int i = 0; i < frame->stack_size(); i++) {
    if (frame->stack_is_oop(i)) {
      f->do_oop((oop*)interpreter_frame_expression_stack_at(i));
    }
  }
  
  // 3. 监视器(synchronized块)
  BasicObjectLock* current = interpreter_frame_monitor_begin();
  while (current < interpreter_frame_monitor_end()) {
    if (current->obj() != NULL) {
      f->do_oop(&current->obj());
    }
    current++;
  }
}
```

---

## 问题6：Derived Pointer是什么？如何处理？

### 面试官视角
考察对复杂引用类型的理解。

### 参考答案

#### Derived Pointer定义

派生指针是指向对象内部的指针，比如指向数组某个元素的指针。

```java
int[] array = new int[100];
// array是base指针，指向数组对象头
// &array[50]是derived指针，指向数组内部

// GC移动数组时:
// 1. array需要更新
// 2. derived指针也需要更新(保持偏移不变)
```

#### OopMap记录派生指针

```cpp
// oopMap.hpp
class OopMapValue {
  enum oop_types {
    // ...
    derived_oop_value  // 派生指针
  };
  
  // 派生指针需要记录:
  // 1. 派生指针的位置
  // 2. 基址指针的位置
};
```

#### 派生指针表

**源码位置**: `gc/shared/derivedPointerTable.cpp`

```cpp
class DerivedPointerTable : AllStatic {
  // 记录所有派生指针
  static GrowableArray<DerivedPointerEntry*>* _list;
  
  struct DerivedPointerEntry {
    oop* base_loc;        // 基址指针位置
    oop* derived_loc;     // 派生指针位置
    intptr_t offset;      // 派生指针相对基址的偏移
  };
  
  // GC前: 记录派生指针
  static void add(oop* derived_loc, oop* base_loc);
  
  // GC后: 更新派生指针
  static void update_pointers();
};
```

---

## 问题7：8GB堆的Root枚举有多少开销？

### 面试官视角
结合实际场景的性能问题。

### 参考答案

#### Root枚举开销分析

```
Root枚举开销主要取决于:
1. 线程数量
2. 每个线程的栈深度
3. JNI全局引用数量
4. 静态字段数量
5. 代码缓存大小

8GB堆场景估算:
- 假设200个线程
- 平均每个线程50个栈帧
- 每个栈帧平均10个引用

Root数量 ≈ 200 × 50 × 10 = 100,000个引用
扫描时间 ≈ 1-5ms (取决于硬件)
```

#### 优化措施

```cpp
// 1. 并行Root扫描
// G1的Root扫描是并行的
void G1RootProcessor::process_java_roots(G1RootClosures* closures,
                                         G1GCPhaseTimes* phase_times,
                                         uint worker_i) {
  // 每个GC Worker扫描一部分线程
}

// 2. 增量式Root枚举(ZGC/Shenandoah)
// 部分Root在并发阶段处理

// 3. 减少Root数量
// - 避免过多静态字段
// - 减少JNI全局引用
// - 控制线程数量
```

---

## 总结

### 关键源码文件

| 主题 | 源码文件 | 关键类/方法 |
|------|----------|-------------|
| OopMap | `compiler/oopMap.hpp` | `OopMap`, `OopMapSet` |
| Root扫描 | `gc/shared/gcRootsClosure.hpp` | `OopClosure` |
| 线程扫描 | `runtime/thread.cpp` | `JavaThread::oops_do` |
| 栈帧扫描 | `runtime/frame.cpp` | `frame::oops_do` |
| 派生指针 | `gc/shared/derivedPointerTable.cpp` | `DerivedPointerTable` |

### 面试回答要点

1. **GC Root类型**: 栈引用、JNI引用、静态字段、常量、同步锁对象、JVM内部引用
2. **OopMap作用**: 记录SafePoint时哪些位置存储对象引用，实现准确式GC
3. **OopMap生成**: 编译时生成，存储在nmethod中，每个SafePoint一个
4. **Root枚举**: 遍历栈帧，使用OopMap(编译帧)或布局信息(解释器帧)
5. **派生指针**: 指向对象内部的指针，需要记录基址以便GC后更新
6. **优化**: 并行扫描、增量式枚举、减少Root数量
