# HelloWorld对象创建完整流程 - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -Xint`  
> **源代码**: `HelloWorld obj = new HelloWorld();`

---

## 1. 测试代码

```java
public class HelloWorld {
    public static void main(String[] args) {
        HelloWorld obj = new HelloWorld();
    }
}
```

**编译后字节码**:
```
  public static void main(java.lang.String[]);
    Code:
      stack=2, locals=2, args_size=1
         0: new           #2    // class HelloWorld
         3: dup
         4: invokespecial #3    // Method "<init>":()V
         7: astore_1
         8: return
```

---

## 2. 对象创建阶段概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                    new HelloWorld() 执行流程                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  字节码: bb 00 02 (new #2)                                          │
│          │                                                          │
│          ▼                                                          │
│  ┌─────────────────────────────────────────┐                        │
│  │ Stage 1: InterpreterRuntime::_new       │ 解释器入口              │
│  │ - 解析常量池索引 #2 → HelloWorld类       │                        │
│  │ - 检查类是否已初始化                     │                        │
│  └────────────┬────────────────────────────┘                        │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                        │
│  │ Stage 2: InstanceKlass::allocate_instance│ 分配入口               │
│  │ - 计算对象大小 (16 bytes)               │                        │
│  │ - 检查是否有finalizer                   │                        │
│  └────────────┬────────────────────────────┘                        │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                        │
│  │ Stage 3: CollectedHeap::obj_allocate    │ 堆分配                  │
│  │ - 调用G1CollectedHeap分配内存           │                        │
│  └────────────┬────────────────────────────┘                        │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                        │
│  │ Stage 4: MemAllocator::allocate         │ 内存分配器              │
│  │ - TLAB快速分配 (bump-the-pointer)       │                        │
│  │ - 初始化对象头                          │                        │
│  └────────────┬────────────────────────────┘                        │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                        │
│  │ Stage 5: 返回oop                        │ 对象引用                │
│  │ - 对象头已初始化                        │                        │
│  │ - 字段清零完成                          │                        │
│  └────────────┬────────────────────────────┘                        │
│               ▼                                                     │
│  字节码: b7 00 03 (invokespecial #3)                                │
│  调用 <init> 构造函数                                               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Stage 1: InterpreterRuntime::_new (GDB验证)

```cpp
// interpreterRuntime.cpp:217
IRT_ENTRY(void, InterpreterRuntime::_new(JavaThread* thread, ConstantPool* pool, int index))
```

### GDB验证数据

```
pool (ConstantPool): 0x7fffcefa6058
index: 2 (常量池索引)
thread: 0x7ffff001f000

常量池解析:
  CP#2 = Class HelloWorld
  → 解析为 InstanceKlass* 0x800092840
```

### 调用栈

```
#0  InterpreterRuntime::_new (thread=0x7ffff001f000, pool=0x7fffcefa6058, index=2)
    at interpreterRuntime.cpp:217
#1  0x00007fffed020909 in ?? ()  ← 解释器模板代码
#2  0x00007fffed02087d in ?? ()
...
```

### 源码分析

```cpp
// interpreterRuntime.cpp:217-243
IRT_ENTRY(void, InterpreterRuntime::_new(JavaThread* thread, ConstantPool* pool, int index))
  // 1. 从常量池获取类
  Klass* k = pool->klass_at(index, CHECK);
  InstanceKlass* klass = InstanceKlass::cast(k);

  // 2. 确保类已初始化
  klass->check_valid_for_instantiation(true, CHECK);
  klass->initialize(CHECK);

  // 3. 分配对象
  oop obj = klass->allocate_instance(CHECK);

  // 4. 设置返回值到解释器栈
  thread->set_vm_result(obj);
IRT_END
```

---

## 4. Stage 2: InstanceKlass::allocate_instance (GDB验证)

### GDB验证数据

```
InstanceKlass: 0x800092840
类名: HelloWorld
_layout_helper: 16 bytes (对象实例大小)
_vtable_len: 5
_itable_len: 2
_access_flags: 0x20000021 (ACC_PUBLIC | ACC_SUPER)
_super: 0x800001040 (java/lang/Object)
_nonstatic_field_size: 0 words
_static_field_size: 0 words
_init_state: 4 (fully_initialized)
```

### 对象大小计算

```
┌──────────────────────────────────────────────────────────┐
│ HelloWorld对象内存布局 (16 bytes)                        │
├──────────────────────────────────────────────────────────┤
│ [0-7]   mark word          8 bytes                       │
│ [8-11]  compressed klass   4 bytes                       │
│ [12-15] padding            4 bytes (对齐到8字节)         │
├──────────────────────────────────────────────────────────┤
│ 总计: 16 bytes = 2 words                                 │
└──────────────────────────────────────────────────────────┘

计算公式:
_layout_helper = header_size + field_size + padding
              = 12 + 0 + 4 = 16 bytes
```

### 源码分析

```cpp
// instanceKlass.cpp:1241-1261
instanceOop InstanceKlass::allocate_instance(TRAPS) {
  bool has_finalizer_flag = has_finalizer();
  int size = size_helper();  // 从_layout_helper获取

  instanceOop i;
  i = (instanceOop)Universe::heap()->obj_allocate(this, size, CHECK_NULL);
  
  if (has_finalizer_flag && !RegisterFinalizersAtInit) {
    i = register_finalizer(i, CHECK_NULL);
  }
  return i;
}
```

---

## 5. Stage 3: CollectedHeap::obj_allocate (GDB验证)

### GDB验证数据

```
klass: 0x800092840 (HelloWorld)
size: 2 words (16 bytes)
this (G1CollectedHeap): 0x7ffff0031cd0
```

### 调用栈

```
#0  CollectedHeap::obj_allocate (this=0x7ffff0031cd0, klass=0x800092840, size=2)
    at collectedHeap.cpp:453
#1  InstanceKlass::allocate_instance (this=0x800092840)
    at instanceKlass.cpp:1246
#2  InterpreterRuntime::_new (thread=0x7ffff001f000, pool=0x7fffcefa6058, index=2)
    at interpreterRuntime.cpp:241
```

### 源码分析

```cpp
// collectedHeap.cpp:453-455
oop CollectedHeap::obj_allocate(Klass* klass, int size, TRAPS) {
  ObjAllocator allocator(klass, size, THREAD);
  return allocator.allocate();
}
```

---

## 6. Stage 4: MemAllocator::allocate (GDB验证)

### GDB验证数据

```
_klass: 0x800092840 (HelloWorld)
_word_size: 2 (16 bytes)
_thread: 0x7ffff001f000
```

### TLAB分配验证

```
=== TLAB状态 (分配前) ===
ThreadLocalAllocBuffer: 0x7ffff001f128
_start: 0x7ff400800 (TLAB起始)
_top: 0x7ff41f2b0 (下次分配位置)
_end: 0x7ff6005c0 (TLAB结束)
TLAB大小: 2,096,576 bytes (~2MB)
已使用: 125,616 bytes
剩余: 1,970,960 bytes

需要分配: 16 bytes
分配策略: TLAB快速分配 (bump-the-pointer)

=== TLAB状态 (分配后) ===
_top: 0x7ff41f2c0 (增加16字节)
对象地址: 0x7ff41f2b0 (原_top位置)
```

### TLAB分配算法

```
TLAB快速分配 (bump-the-pointer):

分配前:
  _start                    _top                    _end
    │                        │                        │
    ▼                        ▼                        ▼
    ┌────────────────────────┬────────────────────────┐
    │   已分配对象            │       空闲空间         │
    └────────────────────────┴────────────────────────┘

分配16字节:
  _start                    原_top  新_top          _end
    │                        │       │               │
    ▼                        ▼       ▼               ▼
    ┌────────────────────────┬──────┬───────────────┐
    │   已分配对象            │ 新obj │   空闲空间    │
    └────────────────────────┴──────┴───────────────┘
                             └─ 0x7ff41f2b0 (返回地址)

分配操作:
1. result = _top;           // 0x7ff41f2b0
2. _top = _top + size;      // 0x7ff41f2c0
3. return result;
```

### 源码分析

```cpp
// memAllocator.cpp:363-372
HeapWord* MemAllocator::mem_allocate(Allocation& allocation) const {
  if (UseTLAB) {
    HeapWord* result = allocate_inside_tlab(allocation);  // TLAB分配
    if (result != NULL) {
      return result;
    }
  }
  return allocate_outside_tlab(allocation);  // Eden分配
}

// memAllocator.cpp:350-360
HeapWord* MemAllocator::allocate_inside_tlab(Allocation& allocation) const {
  HeapWord* mem = _thread->tlab().allocate(_word_size);  // bump-the-pointer
  return mem;
}
```

---

## 7. Stage 5: 对象初始化 (GDB验证)

### GDB验证数据

```
对象地址: 0x7ff41f2b0

=== 对象头 (Object Header) ===
mark word (8字节): 0x0000000000000005
  锁状态位 (bit 0-2): 0x5 (偏向锁可用)

compressed klass ptr (4字节): 0x00092840
  压缩类指针: 0x92840
  解压后: 0x800092840 (HelloWorld InstanceKlass)

对象总大小: 16 bytes
  - header: 12 bytes (mark 8 + klass 4)
  - fields: 0 bytes
  - padding: 4 bytes
```

### 对象头详解

```
┌─────────────────────────────────────────────────────────────────────┐
│ mark word (8 bytes) = 0x0000000000000005                            │
├─────────────────────────────────────────────────────────────────────┤
│ bit 63-3: unused/hashcode/age                                       │
│ bit 2: 0 (unused)                                                   │
│ bit 1: 0 (biased_lock)                                              │
│ bit 0: 1 (lock)                                                     │
│                                                                     │
│ 低3位 = 101 = 5 表示偏向锁可用状态                                   │
├─────────────────────────────────────────────────────────────────────┤
│ lock状态:                                                           │
│   001 = 无锁                                                        │
│   000 = 轻量级锁                                                    │
│   010 = 重量级锁                                                    │
│   101 = 偏向锁可用                                                  │
│   111 = GC标记                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ compressed klass pointer (4 bytes) = 0x00092840                     │
├─────────────────────────────────────────────────────────────────────┤
│ 压缩类指针计算:                                                      │
│   base = 0x800000000 (Compressed Class Space起始)                   │
│   解压 = base + (compressed << shift)                               │
│        = 0x800000000 + 0x92840                                      │
│        = 0x800092840                                                │
│                                                                     │
│ 0x800092840 = HelloWorld InstanceKlass地址                          │
└─────────────────────────────────────────────────────────────────────┘
```

### 对象初始化源码

```cpp
// memAllocator.cpp:413-420
oop ObjAllocator::initialize(HeapWord* mem) const {
  mem_clear(mem);                           // 清零字段
  return finish(mem);                       // 设置对象头
}

// memAllocator.cpp:180-195
oop MemAllocator::finish(HeapWord* mem) const {
  oopDesc::set_mark_raw(mem, markOopDesc::prototype());  // 设置mark word
  oopDesc::release_set_klass(mem, _klass);               // 设置klass指针
  return oop(mem);
}
```

---

## 8. 完整调用栈

```
#0  InterpreterRuntime::_new
    │  解释器new字节码入口
    │  pool=0x7fffcefa6058, index=2
    │
    ├──► InstanceKlass::allocate_instance
    │    │  this=0x800092840 (HelloWorld)
    │    │
    │    ├──► CollectedHeap::obj_allocate
    │    │    │  klass=0x800092840, size=2 words
    │    │    │
    │    │    ├──► MemAllocator::allocate
    │    │    │    │  _klass=0x800092840, _word_size=2
    │    │    │    │
    │    │    │    ├──► MemAllocator::mem_allocate
    │    │    │    │    │
    │    │    │    │    └──► allocate_inside_tlab
    │    │    │    │         │  TLAB bump-the-pointer
    │    │    │    │         └──► 返回 HeapWord* 0x7ff41f2b0
    │    │    │    │
    │    │    │    └──► ObjAllocator::initialize
    │    │    │         │  mem=0x7ff41f2b0
    │    │    │         ├──► mem_clear (清零字段)
    │    │    │         └──► finish (设置对象头)
    │    │    │              └──► 返回 oop 0x7ff41f2b0
    │    │    │
    │    │    └──► 返回 oop 0x7ff41f2b0
    │    │
    │    └──► 返回 instanceOop 0x7ff41f2b0
    │
    └──► thread->set_vm_result(obj)
         将对象引用放入解释器栈顶
```

---

## 9. G1堆内存布局

### GDB验证数据

```
G1CollectedHeap: 0x7ffff0031cd0
HeapRegionManager: 0x7ffff0031f78

堆配置:
  总大小: 8,192 MB (8GB)
  Region大小: 4 MB
  Region数量: 2,048个
```

### 内存地址分布

```
┌─────────────────────────────────────────────────────────────────────┐
│ 地址空间分布 (8GB堆)                                                 │
├─────────────────────────────────────────────────────────────────────┤
│ 0x600000000 ─────── Java堆起始                                      │
│      │                                                              │
│      │ Eden Region (TLAB所在)                                       │
│      │   ├── 0x7ff400800 TLAB._start                                │
│      │   ├── 0x7ff41f2b0 HelloWorld对象                             │
│      │   └── 0x7ff6005c0 TLAB._end                                  │
│      │                                                              │
│ 0x800000000 ─────── Java堆结束 / Compressed Class Space起始         │
│      │                                                              │
│      │ InstanceKlass                                                │
│      │   └── 0x800092840 HelloWorld InstanceKlass                   │
│      │                                                              │
│ 0x840000000 ─────── Compressed Class Space结束                      │
├─────────────────────────────────────────────────────────────────────┤
│ 0x7fffcxxxxxxx ─── Metaspace Non-class                              │
│      │                                                              │
│      │   ├── 0x7fffcefa6058 ConstantPool                            │
│      │   └── 0x7fffcefa62c0 CPCache                                 │
├─────────────────────────────────────────────────────────────────────┤
│ 0x7fffedxxxxxx ─── CodeCache                                        │
│      │                                                              │
│      │   └── 解释器模板代码                                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 10. GDB调试命令

```bash
# 进入调试
cd /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin
gdb ./java

# 设置断点
break InterpreterRuntime::_new
break InstanceKlass::allocate_instance
break MemAllocator::mem_allocate

# 运行
run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -Xint -cp /data/workspace HelloWorld

# 查看TLAB
set $thread = (Thread*)thread
set $tlab = &($thread->_tlab)
print $tlab->_start
print $tlab->_top
print $tlab->_end

# 查看对象头
set $obj = 0x7ff41f2b0
x/1gx $obj        # mark word
x/1wx $obj+8      # compressed klass

# 验证类指针
set $cklass = *(unsigned int*)($obj+8)
print/x $cklass + 0x800000000  # 解压
```

---

## 11. 关键数据汇总

| 项目 | GDB验证值 | 说明 |
|------|-----------|------|
| 对象地址 | 0x7ff41f2b0 | Java堆 (Eden TLAB) |
| 对象大小 | 16 bytes | 2 words |
| mark word | 0x5 | 偏向锁可用状态 |
| compressed klass | 0x92840 | 解压后0x800092840 |
| InstanceKlass | 0x800092840 | Compressed Class Space |
| ConstantPool | 0x7fffcefa6058 | Metaspace |
| TLAB大小 | ~2MB | 线程本地分配缓冲 |
| 分配方式 | bump-the-pointer | TLAB快速分配 |
