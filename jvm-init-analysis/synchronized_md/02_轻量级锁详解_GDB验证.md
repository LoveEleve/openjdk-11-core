# 轻量级锁详解 - GDB验证

> **实验环境**
> - OpenJDK 11 slowdebug
> - `-XX:-UseBiasedLocking` 关闭偏向锁
> - 专注于轻量级锁的获取、重入和释放过程

---

## 1. 轻量级锁核心概念

轻量级锁是JVM对synchronized的优化，适用于**无竞争或低竞争**场景：

```
┌─────────────────────────────────────────────────────────────────────┐
│                    轻量级锁 vs 重量级锁                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ 轻量级锁 (Lock Record):                                             │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ • 数据结构: 栈上的BasicLock (8字节)                              │ │
│ │ • 获取方式: CAS操作 (~10ns)                                      │ │
│ │ • 适用场景: 无竞争/低竞争                                        │ │
│ │ • 阻塞方式: 不阻塞(CAS失败则膨胀)                                │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│ 重量级锁 (ObjectMonitor):                                           │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ • 数据结构: 堆外ObjectMonitor (~200字节)                        │ │
│ │ • 获取方式: CAS + park/unpark (~微秒级)                         │ │
│ │ • 适用场景: 高竞争                                               │ │
│ │ • 阻塞方式: 内核阻塞(park)                                       │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. BasicLock结构

```cpp
// src/hotspot/share/runtime/basicLock.hpp
class BasicLock {
private:
  volatile markOop _displaced_header;  // 保存原始mark word
  
public:
  markOop displaced_header() const { return _displaced_header; }
  void set_displaced_header(markOop header) { _displaced_header = header; }
};

// 在栈帧中的位置
class BasicObjectLock {
  BasicLock _lock;  // Lock Record
  oop _obj;         // 锁对象引用
};
```

**内存布局**:
```
线程栈:
┌─────────────────────────────────────┐ 高地址
│  ...                                │
├─────────────────────────────────────┤
│ BasicObjectLock                     │
│ ├── _lock (BasicLock)               │ ← Lock Record
│ │   └── _displaced_header (8字节)   │ ← 保存原始mark
│ └── _obj (oop)                      │ ← 锁对象指针
├─────────────────────────────────────┤
│  ...                                │
└─────────────────────────────────────┘ 低地址
```

---

## 3. 轻量级锁获取过程

### 3.1 流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    轻量级锁获取流程                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  synchronized(obj) {                                                │
│          │                                                          │
│          ▼                                                          │
│  ┌─────────────────────────────────────────┐                       │
│  │ 1. 在栈帧中分配BasicObjectLock          │                       │
│  │    - Lock Record空间                     │                       │
│  │    - 对象引用_obj                        │                       │
│  └────────────┬────────────────────────────┘                       │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                       │
│  │ 2. 读取对象mark word                     │                       │
│  │    mark = obj->mark()                    │                       │
│  │    GDB: mark = 0x1 (无锁)               │                       │
│  └────────────┬────────────────────────────┘                       │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                       │
│  │ 3. 检查是否无锁状态                      │                       │
│  │    mark->is_neutral()                    │                       │
│  │    (mark & 0x3) == 0x1                   │                       │
│  └────────────┬────────────────────────────┘                       │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                       │
│  │ 4. 保存原始mark到Lock Record             │                       │
│  │    lock->set_displaced_header(mark)      │                       │
│  │    GDB: _displaced_header = 0x1          │                       │
│  └────────────┬────────────────────────────┘                       │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                       │
│  │ 5. CAS替换对象mark word                  │                       │
│  │    obj->cas_set_mark(lock_addr, mark)    │                       │
│  │                                          │                       │
│  │    原值: 0x0000000000000001              │                       │
│  │    新值: 0x00007fffXXXXXXX0              │                       │
│  │          └─ Lock Record地址 | 00         │                       │
│  └───────┬──────────────────────┬──────────┘                       │
│          │                      │                                   │
│      CAS成功                CAS失败                                 │
│          │                      │                                   │
│          ▼                      ▼                                   │
│  ┌──────────────┐      ┌────────────────┐                          │
│  │ 获得轻量级锁 │      │ 检查是否重入   │                          │
│  │ 执行临界区   │      │ 或竞争导致膨胀 │                          │
│  └──────────────┘      └────────────────┘                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 GDB验证数据

```
=== ObjectSynchronizer::slow_enter ===

锁对象: 0xfff00970
mark word: 0x1
锁状态: 无锁 (01)
  → 尝试CAS设置为轻量级锁

BasicLock: 0x7ffff780a338
BasicLock._displaced_header: 0x1   ← 保存原始mark
```

**CAS操作**:
```
CAS前: mark word = 0x0000000000000001 (无锁)
CAS后: mark word = 0x00007ffff780a338 (轻量级锁)
                   ↑
                   Lock Record地址，末两位00
```

---

## 4. 轻量级锁重入

同一线程再次获取同一把锁时的处理：

```cpp
// src/hotspot/share/runtime/synchronizer.cpp
void ObjectSynchronizer::slow_enter(Handle obj, BasicLock* lock, TRAPS) {
  markOop mark = obj->mark();
  
  // ...
  
  // 检查是否是锁重入
  if (mark->has_locker() && 
      THREAD->is_lock_owned((address)mark->locker())) {
    // 是重入，设置displaced_header为NULL标识
    lock->set_displaced_header(NULL);
    return;
  }
  
  // ...
}
```

**重入机制**:
```
第1次进入:
┌─────────────────┐
│ BasicObjectLock │
│ _displaced = 0x1│ ← 保存原始mark
│ _obj = lock_obj │
└─────────────────┘

第2次进入（重入）:
┌─────────────────┐
│ BasicObjectLock │
│ _displaced = NULL│ ← NULL标识重入
│ _obj = lock_obj │
└─────────────────┘

第3次进入（重入）:
┌─────────────────┐
│ BasicObjectLock │
│ _displaced = NULL│ ← NULL标识重入
│ _obj = lock_obj │
└─────────────────┘
```

**重入计数**: 通过栈上`_displaced_header = NULL`的Lock Record数量

---

## 5. 轻量级锁释放

```
┌─────────────────────────────────────────────────────────────────────┐
│                    轻量级锁释放流程                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  } // synchronized结束                                              │
│          │                                                          │
│          ▼                                                          │
│  ┌─────────────────────────────────────────┐                       │
│  │ 1. 检查displaced_header                 │                       │
│  │    header = lock->displaced_header()     │                       │
│  └────────────┬────────────────────────────┘                       │
│               │                                                     │
│       ┌───────┴───────┐                                            │
│       │               │                                             │
│   header==NULL    header!=NULL                                      │
│       │               │                                             │
│       ▼               ▼                                             │
│  ┌──────────┐ ┌─────────────────────────────────┐                  │
│  │ 重入返回 │ │ 2. CAS恢复原始mark word          │                  │
│  │ 直接退出 │ │    obj->cas_set_mark(header, lock)│                  │
│  └──────────┘ └──────────┬──────────────────────┘                  │
│                          │                                          │
│                  ┌───────┴───────┐                                  │
│                  │               │                                  │
│              CAS成功         CAS失败                                │
│                  │               │                                  │
│                  ▼               ▼                                  │
│          ┌──────────┐    ┌────────────────┐                        │
│          │ 释放成功 │    │ 锁已膨胀       │                        │
│          │ 返回     │    │ 走重量级锁释放 │                        │
│          └──────────┘    └────────────────┘                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. 核心源码分析

### 6.1 slow_enter完整代码

```cpp
// src/hotspot/share/runtime/synchronizer.cpp:340-380
void ObjectSynchronizer::slow_enter(Handle obj, BasicLock* lock, TRAPS) {
  markOop mark = obj->mark();
  assert(!mark->has_bias_pattern(), "should not see bias pattern here");

  // CASE 1: 无锁状态
  if (mark->is_neutral()) {
    // 保存原始mark到Lock Record
    lock->set_displaced_header(mark);
    
    // CAS将对象mark替换为指向Lock Record的指针
    if (mark == obj()->cas_set_mark((markOop)lock, mark)) {
      return;  // 成功获得轻量级锁
    }
    // CAS失败，继续往下走
  } 
  // CASE 2: 锁重入
  else if (mark->has_locker() && 
           THREAD->is_lock_owned((address)mark->locker())) {
    assert(lock != mark->locker(), "must not re-lock the same lock");
    assert(lock != (BasicLock*)obj->mark(), "don't relock with same BasicLock");
    lock->set_displaced_header(NULL);  // NULL表示重入
    return;
  }

  // CASE 3: 锁竞争或其他情况 → 膨胀
  lock->set_displaced_header(markOopDesc::unused_mark());
  ObjectSynchronizer::inflate(THREAD,
                              obj(),
                              inflate_cause_monitor_enter)->enter(THREAD);
}
```

### 6.2 fast_exit释放代码

```cpp
// src/hotspot/share/runtime/synchronizer.cpp:300-340
void ObjectSynchronizer::fast_exit(oop object, BasicLock* lock, TRAPS) {
  markOop dhw = lock->displaced_header();
  
  if (dhw == NULL) {
    // 重入情况，直接返回
    return;
  }

  markOop mark = object->mark();
  
  // 尝试CAS恢复原始mark word
  if (mark == (markOop)lock) {
    if (object->cas_set_mark(dhw, mark) == mark) {
      return;  // 成功释放轻量级锁
    }
  }
  
  // CAS失败说明锁已膨胀，走重量级锁释放
  ObjectSynchronizer::inflate(THREAD,
                              object,
                              inflate_cause_vm_internal)->exit(true, THREAD);
}
```

---

## 7. GDB调试命令

```bash
# 设置断点
break ObjectSynchronizer::slow_enter
break ObjectSynchronizer::fast_exit

# 查看BasicLock
set $lock = <BasicLock地址>
p/x $lock->_displaced_header

# 查看对象mark word
set $obj = <对象地址>
p/x *(unsigned long*)$obj

# 判断锁状态
p/x (*(unsigned long*)$obj) & 0x3

# 查看Lock Record地址
p/x (*(unsigned long*)$obj) & ~0x3
```

---

## 8. 关键数据总结

| 阶段 | mark word | _displaced_header | 说明 |
|------|-----------|-------------------|------|
| 无锁 | `0x1` | - | 初始状态 |
| 获取锁 | `lock_addr \| 00` | `0x1` | CAS成功 |
| 重入 | 不变 | `NULL` | 标识重入 |
| 释放 | `0x1` | - | CAS恢复 |

| 操作 | 时间复杂度 | 说明 |
|------|------------|------|
| CAS获取 | O(1) ~10ns | 单条原子指令 |
| 重入检查 | O(1) | 检查栈帧 |
| CAS释放 | O(1) ~10ns | 单条原子指令 |
