# TLAB内存分配机制 - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -Xint`  
> **目标**: HelloWorld对象TLAB分配过程

---

## 1. TLAB概述

### 1.1 什么是TLAB

```
TLAB = Thread Local Allocation Buffer (线程本地分配缓冲)

核心思想:
  每个线程在Eden区预先申请一块私有内存
  对象分配在TLAB中进行，无需加锁
  极大提升多线程分配效率

分配方式: bump-the-pointer (指针碰撞)
  1. 检查TLAB剩余空间是否足够
  2. 如果足够: new_top = old_top + size
  3. 返回old_top作为对象地址
```

### 1.2 TLAB工作流程

```
┌─────────────────────────────────────────────────────────────────────┐
│ TLAB分配流程                                                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  分配请求 (16 bytes)                                                │
│        │                                                            │
│        ▼                                                            │
│  ┌─────────────────────┐                                            │
│  │ TLAB空间足够?        │                                            │
│  └─────────┬───────────┘                                            │
│            │                                                        │
│     ┌──────┴──────┐                                                 │
│     │             │                                                 │
│     ▼ Yes         ▼ No                                              │
│  ┌───────────┐  ┌─────────────────┐                                 │
│  │ TLAB分配  │  │ 新TLAB/Eden分配 │                                  │
│  │ (无锁)    │  │ (需要加锁)      │                                  │
│  └───────────┘  └─────────────────┘                                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. TLAB数据结构

### 2.1 ThreadLocalAllocBuffer类

```cpp
// threadLocalAllocBuffer.hpp
class ThreadLocalAllocBuffer {
  HeapWord* _start;           // TLAB起始地址
  HeapWord* _top;             // 下次分配位置
  HeapWord* _end;             // TLAB结束地址
  HeapWord* _allocation_end;  // 实际分配限制
  
  size_t _desired_size;       // 期望大小
  size_t _refill_waste_limit; // 浪费上限
  
  // 统计信息
  unsigned _number_of_refills;
  size_t _allocated_size;
  size_t _wasted_size;
};
```

### 2.2 GDB验证TLAB结构

```
ThreadLocalAllocBuffer地址: 0x7ffff001f128
sizeof(ThreadLocalAllocBuffer): 约64 bytes

成员偏移:
  _start:           +0
  _top:             +8
  _end:             +16
  _allocation_end:  +24
```

---

## 3. HelloWorld对象TLAB分配 (GDB验证)

### 3.1 分配前状态

```
=== TLAB状态 (分配前) ===
TLAB地址: 0x7ffff001f128
_start: 0x7ff400800 (TLAB起始)
_top: 0x7ff41f2b0 (下次分配位置)
_end: 0x7ff6005c0 (TLAB结束)

计算:
  TLAB大小: 0x7ff6005c0 - 0x7ff400800 = 2,096,576 bytes (~2MB)
  已使用: 0x7ff41f2b0 - 0x7ff400800 = 125,616 bytes
  剩余: 0x7ff6005c0 - 0x7ff41f2b0 = 1,970,960 bytes

需要分配: 16 bytes
判断: 1,970,960 >= 16 ✓ 可以TLAB分配
```

### 3.2 分配过程

```
1. 检查空间
   remaining = _end - _top = 1,970,960 bytes
   required = 16 bytes
   remaining >= required → 使用TLAB快速分配

2. bump-the-pointer
   result = _top = 0x7ff41f2b0
   _top = _top + 16 = 0x7ff41f2c0

3. 返回result作为对象地址
```

### 3.3 分配后状态

```
=== TLAB状态 (分配后) ===
_start: 0x7ff400800 (不变)
_top: 0x7ff41f2c0 (增加16字节)
_end: 0x7ff6005c0 (不变)

对象地址: 0x7ff41f2b0 (原_top位置)
增量: 16 bytes
```

### 3.4 内存变化图示

```
分配前:
  _start                              _top                    _end
    │                                  │                        │
    ▼                                  ▼                        ▼
    ┌──────────────────────────────────┬────────────────────────┐
    │     已分配对象 (125,616 B)        │   空闲 (1,970,960 B)   │
    └──────────────────────────────────┴────────────────────────┘
                                       │
                                 0x7ff41f2b0

分配后:
  _start                              旧_top  新_top          _end
    │                                  │       │               │
    ▼                                  ▼       ▼               ▼
    ┌──────────────────────────────────┬──────┬────────────────┐
    │     已分配对象 (125,616 B)        │ 16B  │ 空闲           │
    └──────────────────────────────────┴──────┴────────────────┘
                                       │
                                 HelloWorld对象
                                 0x7ff41f2b0
```

---

## 4. TLAB分配源码分析

### 4.1 入口函数

```cpp
// memAllocator.cpp:363-372
HeapWord* MemAllocator::mem_allocate(Allocation& allocation) const {
  if (UseTLAB) {
    // 优先尝试TLAB分配
    HeapWord* result = allocate_inside_tlab(allocation);
    if (result != NULL) {
      return result;
    }
  }
  // TLAB分配失败，走慢速路径
  return allocate_outside_tlab(allocation);
}
```

### 4.2 TLAB内部分配

```cpp
// memAllocator.cpp:350-360
HeapWord* MemAllocator::allocate_inside_tlab(Allocation& allocation) const {
  assert(UseTLAB, "should use UseTLAB");

  // 直接在TLAB中分配 (bump-the-pointer)
  HeapWord* mem = _thread->tlab().allocate(_word_size);
  if (mem != NULL) {
    return mem;
  }

  // TLAB空间不足，尝试获取新TLAB
  return allocate_inside_tlab_slow(allocation);
}
```

### 4.3 ThreadLocalAllocBuffer::allocate

```cpp
// threadLocalAllocBuffer.hpp
inline HeapWord* ThreadLocalAllocBuffer::allocate(size_t size) {
  HeapWord* obj = top();
  if (pointer_delta(end(), obj) >= size) {
    // 空间足够，bump-the-pointer
    set_top(obj + size);
    return obj;
  }
  return NULL;  // 空间不足
}
```

### 4.4 关键操作

```cpp
// 核心操作就是简单的指针移动
inline void set_top(HeapWord* top) {
  _top = top;
}

// 无锁、无CAS、极快
// 这就是TLAB的威力
```

---

## 5. TLAB生命周期

### 5.1 TLAB创建

```
线程首次分配对象时:
1. 计算TLAB大小 (根据分配历史)
2. 从Eden区申请一块内存
3. 初始化_start, _top, _end
4. 后续分配使用bump-the-pointer
```

### 5.2 TLAB填满

```
当TLAB空间不足时:
1. 计算剩余空间的浪费比例
2. 如果浪费可接受:
   - 申请新TLAB
   - 旧TLAB剩余空间填充dummy对象
3. 如果对象太大:
   - 直接在Eden分配 (需要加锁)
```

### 5.3 TLAB配置参数

```
-XX:+UseTLAB              # 启用TLAB (默认开启)
-XX:TLABSize=<size>       # TLAB初始大小
-XX:MinTLABSize=<size>    # TLAB最小大小
-XX:TLABRefillWasteFraction=<n>  # 浪费比例阈值
-XX:+ResizeTLAB           # 动态调整TLAB大小
```

---

## 6. G1与TLAB

### 6.1 G1堆布局中的TLAB

```
┌─────────────────────────────────────────────────────────────────────┐
│ G1堆内存布局                                                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ ┌───────────────────────────────────────────────────────────────┐   │
│ │ Region 0 (Eden)                                               │   │
│ │ ┌───────────┐ ┌───────────┐ ┌───────────┐                     │   │
│ │ │ Thread1   │ │ Thread2   │ │ Thread3   │  ...                │   │
│ │ │  TLAB     │ │  TLAB     │ │  TLAB     │                     │   │
│ │ └───────────┘ └───────────┘ └───────────┘                     │   │
│ └───────────────────────────────────────────────────────────────┘   │
│                                                                     │
│ ┌───────────────────────────────────────────────────────────────┐   │
│ │ Region 1 (Eden)                                               │   │
│ │ ┌─────────────────────────────────────────────────────────┐   │   │
│ │ │ 普通分配区域                                             │   │   │
│ │ └─────────────────────────────────────────────────────────┘   │   │
│ └───────────────────────────────────────────────────────────────┘   │
│                                                                     │
│ ┌───────────────────────────────────────────────────────────────┐   │
│ │ Region N (Survivor / Old / Humongous)                         │   │
│ └───────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 GDB验证G1堆配置

```
G1CollectedHeap: 0x7ffff0031cd0
HeapRegionManager: 0x7ffff0031f78

堆配置 (8GB):
  总大小: 8,192 MB
  Region大小: 4 MB
  Region数量: 2,048个

TLAB验证:
  TLAB大小: ~2MB
  位于Eden Region中
  地址范围: 0x7ff400800 - 0x7ff6005c0
```

---

## 7. TLAB vs 直接分配

### 7.1 性能对比

```
┌─────────────────────────────────────────────────────────────────────┐
│ 分配方式对比                                                         │
├────────────────┬────────────────────────────────────────────────────┤
│ 特性           │ TLAB分配          │ 直接Eden分配                   │
├────────────────┼───────────────────┼────────────────────────────────┤
│ 需要加锁       │ 否                │ 是 (CAS或锁)                   │
├────────────────┼───────────────────┼────────────────────────────────┤
│ 分配速度       │ 极快 (~10ns)      │ 较慢 (~100ns+)                 │
├────────────────┼───────────────────┼────────────────────────────────┤
│ 线程竞争       │ 无                │ 高并发时严重                   │
├────────────────┼───────────────────┼────────────────────────────────┤
│ 适用场景       │ 小对象 (<TLAB)    │ 大对象/TLAB满                  │
├────────────────┼───────────────────┼────────────────────────────────┤
│ 内存浪费       │ TLAB尾部碎片      │ 无                             │
└────────────────┴───────────────────┴────────────────────────────────┘
```

### 7.2 分配路径选择

```
对象分配决策树:

                    分配请求
                       │
                       ▼
              ┌───────────────┐
              │ 对象大小判断  │
              └───────┬───────┘
                      │
        ┌─────────────┴─────────────┐
        │                           │
        ▼ 小对象                    ▼ 大对象 (> TLAB)
    ┌─────────┐                 ┌─────────┐
    │ 使用TLAB │                 │ Eden区  │
    └────┬────┘                 └────┬────┘
         │                           │
    ┌────┴────┐                      ▼
    │         │                  ┌─────────┐
    ▼ 空间够  ▼ 空间不够         │ Humongous?│
┌─────────┐ ┌─────────┐         └────┬────┘
│bump-ptr │ │新TLAB/  │              │
│  分配   │ │Eden分配 │       ┌──────┴──────┐
└─────────┘ └─────────┘       │             │
                              ▼ 是          ▼ 否
                         Humongous       普通Eden
                           Region          分配
```

---

## 8. GDB调试命令

```bash
# 查看线程的TLAB
set $thread = (Thread*)0x7ffff001f000
set $tlab = &($thread->_tlab)

# TLAB状态
print $tlab->_start
print $tlab->_top
print $tlab->_end

# 计算剩余空间
print (unsigned long)$tlab->_end - (unsigned long)$tlab->_top

# 计算已使用
print (unsigned long)$tlab->_top - (unsigned long)$tlab->_start

# 断点设置
break ThreadLocalAllocBuffer::allocate
break MemAllocator::allocate_inside_tlab
```

---

## 9. 关键数据汇总

| 项目 | GDB值 | 说明 |
|------|-------|------|
| TLAB地址 | 0x7ffff001f128 | Thread._tlab偏移 |
| TLAB._start | 0x7ff400800 | TLAB起始 |
| TLAB._top (分配前) | 0x7ff41f2b0 | 下次分配位置 |
| TLAB._top (分配后) | 0x7ff41f2c0 | +16字节 |
| TLAB._end | 0x7ff6005c0 | TLAB结束 |
| TLAB大小 | ~2MB | 2,096,576 bytes |
| 对象大小 | 16 bytes | 2 words |
| 分配方式 | bump-the-pointer | 无锁快速分配 |
