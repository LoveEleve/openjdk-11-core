# 重量级锁与ObjectMonitor - GDB验证

> **实验环境**
> - OpenJDK 11 slowdebug
> - `-XX:-UseBiasedLocking` 关闭偏向锁
> - 多线程竞争场景触发锁膨胀

---

## 1. ObjectMonitor概述

当轻量级锁发生竞争时，锁会膨胀为重量级锁，底层使用`ObjectMonitor`实现。

```
┌─────────────────────────────────────────────────────────────────────┐
│                    ObjectMonitor 核心特性                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ • 位置: 堆外native内存 (通过omAlloc分配)                            │
│ • 大小: ~200+ 字节 (包含padding)                                    │
│ • 功能: 管理锁竞争、wait/notify                                     │
│ • 阻塞: 基于操作系统futex/park机制                                  │
│                                                                     │
│ 核心队列:                                                           │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ _cxq (Contention Queue)                                         │ │
│ │   新来的竞争线程先入此队列                                       │ │
│ ├─────────────────────────────────────────────────────────────────┤ │
│ │ _EntryList                                                       │ │
│ │   准备获取锁的线程队列                                           │ │
│ ├─────────────────────────────────────────────────────────────────┤ │
│ │ _WaitSet                                                         │ │
│ │   调用wait()的线程队列                                           │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. ObjectMonitor结构

### 2.1 源码定义

```cpp
// src/hotspot/share/runtime/objectMonitor.hpp:128-180
class ObjectMonitor {
private:
  // ===== 锁对象关联 =====
  volatile markOop   _header;       // displaced mark word (原始mark)
  void*     volatile _object;       // 指向锁对象

public:
  ObjectMonitor*     FreeNext;      // 空闲链表

private:
  DEFINE_PAD_MINUS_SIZE(0, 128, ...);  // 128字节对齐padding

protected:
  // ===== 锁持有者 =====
  void*     volatile _owner;        // 当前持有锁的线程
  volatile jlong _previous_owner_tid;
  volatile intptr_t  _recursions;   // 重入计数

  // ===== 阻塞队列 =====
  ObjectWaiter* volatile _EntryList; // 等待获取锁的线程
  
private:
  ObjectWaiter* volatile _cxq;       // 竞争队列 (新到达的线程)
  Thread*       volatile _succ;      // 假定继承者
  Thread*       volatile _Responsible;
  
  // ===== 等待队列 =====
  ObjectWaiter* volatile _WaitSet;   // 调用wait()的线程
  volatile int  _WaitSetLock;
  
  // ===== 自旋优化 =====
  volatile int  _Spinner;
  volatile int  _SpinDuration;
};
```

### 2.2 内存布局

```
ObjectMonitor (GDB: 0x7fffc8003080)
┌────────────────────────────────────────────────────────────────┐
│ Offset 0:   _header         (8 bytes)  = 0x1 (原始mark)        │
│ Offset 8:   _object         (8 bytes)  = 0xfff019d0 (锁对象)   │
│ Offset 16:  FreeNext        (8 bytes)                          │
├────────────────────────────────────────────────────────────────┤
│ Offset 24-191: Padding (128字节缓存行对齐)                      │
├────────────────────────────────────────────────────────────────┤
│ Offset 192: _owner          (8 bytes)  = Thread* 或 NULL       │
│ Offset 200: _previous_owner_tid (8 bytes)                       │
│ Offset 208: _recursions     (8 bytes)  = 重入计数               │
│ Offset 216: _EntryList      (8 bytes)  = 阻塞队列               │
├────────────────────────────────────────────────────────────────┤
│ Offset 224: _cxq            (8 bytes)  = 竞争队列               │
│ Offset 232: _succ           (8 bytes)  = 假定继承者             │
│ ...                                                             │
│ Offset XXX: _WaitSet        (8 bytes)  = 等待队列               │
└────────────────────────────────────────────────────────────────┘
```

---

## 3. GDB验证数据

### 3.1 ObjectMonitor::enter验证

```
=== ObjectMonitor::enter ===
ObjectMonitor: 0x7fffc8003080
Thread: 0x7ffff001eed0

--- ObjectMonitor状态 ---
_header (displaced mark): 0x1
_object: 0xfff019d0
_owner: (nil)
_recursions: 1
_EntryList: (nil)

锁状态: 空闲 (无持有者)

调用栈:
#0  ObjectMonitor::enter
#1  ObjectSynchronizer::slow_enter
#2  ObjectSynchronizer::fast_enter
#3  ObjectLocker::ObjectLocker
```

### 3.2 mark word变化验证

```
膨胀前 (轻量级锁):
mark word: 0x7fffdd0f42f8
  末两位: 00 (轻量级锁)
  Lock Record: 0x7fffdd0f42f8

膨胀后 (重量级锁):
mark word: 0x7fffc8003082
  末两位: 10 (重量级锁)
  ObjectMonitor: 0x7fffc8003080
```

---

## 4. ObjectMonitor::enter流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                    ObjectMonitor::enter 流程                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ObjectMonitor::enter(Thread* Self)                                 │
│          │                                                          │
│          ▼                                                          │
│  ┌─────────────────────────────────────────┐                       │
│  │ 1. 快速路径: CAS尝试获取                 │                       │
│  │    CAS(&_owner, NULL, Self)             │                       │
│  └────────────┬────────────────────────────┘                       │
│               │                                                     │
│       ┌───────┴───────┐                                            │
│       │               │                                             │
│   CAS成功         CAS失败                                           │
│       │               │                                             │
│       ▼               ▼                                             │
│  ┌──────────┐ ┌─────────────────────────────────┐                  │
│  │ 获得锁   │ │ 2. 检查是否重入                  │                  │
│  │ _recursions++│ │    _owner == Self?            │                  │
│  │ return   │ └──────────┬──────────────────────┘                  │
│  └──────────┘            │                                          │
│                  ┌───────┴───────┐                                  │
│                  │               │                                  │
│               是重入          不是重入                               │
│                  │               │                                  │
│                  ▼               ▼                                  │
│          ┌──────────┐    ┌────────────────────────┐                │
│          │ _recursions++│    │ 3. 自旋尝试            │                │
│          │ return   │    │    TrySpin()            │                │
│          └──────────┘    └──────────┬─────────────┘                │
│                                     │                               │
│                             ┌───────┴───────┐                       │
│                             │               │                       │
│                         自旋成功        自旋失败                    │
│                             │               │                       │
│                             ▼               ▼                       │
│                     ┌──────────┐    ┌────────────────────────┐     │
│                     │ 获得锁   │    │ 4. 入队 + 阻塞           │     │
│                     │ return   │    │    EnterI(Self)         │     │
│                     └──────────┘    │    - 加入_cxq队列        │     │
│                                     │    - park()阻塞          │     │
│                                     └────────────────────────┘     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. 核心源码分析

### 5.1 ObjectMonitor::enter

```cpp
// src/hotspot/share/runtime/objectMonitor.cpp:268-350
void ObjectMonitor::enter(TRAPS) {
  Thread* const Self = THREAD;
  void* cur = Atomic::cmpxchg(Self, &_owner, (void*)NULL);
  
  // 快速路径: CAS成功
  if (cur == NULL) {
    return;
  }
  
  // 重入检查
  if (cur == Self) {
    _recursions++;
    return;
  }
  
  // 检查是否是Lock Record持有(轻量级锁膨胀后)
  if (Self->is_lock_owned((address)cur)) {
    _recursions = 1;
    _owner = Self;
    return;
  }
  
  // 自旋尝试
  if (TrySpin(Self) > 0) {
    return;
  }
  
  // 入队阻塞
  EnterI(Self);
}
```

### 5.2 EnterI（入队阻塞）

```cpp
// src/hotspot/share/runtime/objectMonitor.cpp:400-500
void ObjectMonitor::EnterI(TRAPS) {
  Thread* const Self = THREAD;
  
  // 创建ObjectWaiter节点
  ObjectWaiter node(Self);
  node.TState = ObjectWaiter::TS_CXQ;
  
  // 加入_cxq队列头部
  for (;;) {
    node._next = _cxq;
    if (Atomic::cmpxchg(&node, &_cxq, node._next) == node._next) {
      break;
    }
  }
  
  // 自旋重试
  for (int i = 0; i < _SpinCount; i++) {
    if (TryLock(Self) > 0) {
      return;  // 自旋成功
    }
  }
  
  // 阻塞
  for (;;) {
    // 再次尝试获取
    if (TryLock(Self) > 0) break;
    
    // park阻塞
    Self->_ParkEvent->park();
    
    // 被唤醒后再次尝试
    if (TryLock(Self) > 0) break;
  }
  
  // 从队列中移除
  UnlinkAfterAcquire(Self, &node);
}
```

---

## 6. ObjectMonitor::exit流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                    ObjectMonitor::exit 流程                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ObjectMonitor::exit(bool not_suspended, Thread* Self)              │
│          │                                                          │
│          ▼                                                          │
│  ┌─────────────────────────────────────────┐                       │
│  │ 1. 检查重入                              │                       │
│  │    if (_recursions > 0) {                │                       │
│  │      _recursions--;                      │                       │
│  │      return;                             │                       │
│  │    }                                     │                       │
│  └────────────┬────────────────────────────┘                       │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                       │
│  │ 2. 释放锁                                │                       │
│  │    _owner = NULL;                        │                       │
│  └────────────┬────────────────────────────┘                       │
│               ▼                                                     │
│  ┌─────────────────────────────────────────┐                       │
│  │ 3. 检查是否有等待线程                    │                       │
│  │    if (_EntryList || _cxq) {             │                       │
│  │      唤醒一个等待线程                    │                       │
│  │    }                                     │                       │
│  └─────────────────────────────────────────┘                       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 7. 线程状态与队列

### 7.1 ObjectWaiter

```cpp
// src/hotspot/share/runtime/objectMonitor.hpp:42-60
class ObjectWaiter : public StackObj {
public:
  enum TStates { 
    TS_UNDEF,   // 未定义
    TS_READY,   // 准备就绪
    TS_RUN,     // 正在运行
    TS_WAIT,    // 在_WaitSet中
    TS_ENTER,   // 在_EntryList中
    TS_CXQ      // 在_cxq中
  };
  
  ObjectWaiter* volatile _next;
  ObjectWaiter* volatile _prev;
  Thread*       _thread;
  ParkEvent*    _event;      // park/unpark事件
  volatile TStates TState;
};
```

### 7.2 队列关系

```
新竞争线程到达:
                              ┌─────────────────────┐
                              │ _cxq (竞争队列)      │
新线程 ──────────────────────►│ node1 → node2 → ... │
                              └─────────────────────┘
                                        │
                                        │ 锁释放时转移
                                        ▼
                              ┌─────────────────────┐
                              │ _EntryList          │
                              │ node1 → node2 → ... │
                              └─────────────────────┘
                                        │
                                        │ 获得锁
                                        ▼
                              ┌─────────────────────┐
                              │ _owner              │
                              │ Thread*             │
                              └─────────────────────┘

wait()调用:
                              ┌─────────────────────┐
                              │ _WaitSet            │
_owner ─── wait() ──────────►│ waiter1 → waiter2   │
                              └─────────────────────┘
                                        │
                                        │ notify()
                                        ▼
                              ┌─────────────────────┐
                              │ _EntryList 或 _cxq  │
                              └─────────────────────┘
```

---

## 8. GDB调试命令

```bash
# 设置断点
break ObjectMonitor::enter
break ObjectMonitor::exit

# 查看ObjectMonitor状态
set $mon = <Monitor地址>
p/x *(unsigned long*)($mon)         # _header
p *(void**)($mon + 8)               # _object
p *(void**)($mon + 192)             # _owner
p *(long*)($mon + 208)              # _recursions
p *(void**)($mon + 216)             # _EntryList

# 查看等待队列
set $entry = *(void**)($mon + 216)
p *(ObjectWaiter*)$entry

# 查看mark word
set $obj = <锁对象地址>
p/x *(unsigned long*)$obj
p/x (*(unsigned long*)$obj) & 0x3    # 锁状态位
p/x (*(unsigned long*)$obj) & ~0x3   # Monitor地址
```

---

## 9. 关键数据总结

| 字段 | GDB值 | 说明 |
|------|-------|------|
| ObjectMonitor | `0x7fffc8003080` | 堆外native内存 |
| _header | `0x1` | 原始mark word |
| _object | `0xfff019d0` | 锁对象 |
| _owner | `NULL` / Thread* | 持有者 |
| _recursions | 0/1/2... | 重入计数 |
| _EntryList | `NULL` / ObjectWaiter* | 阻塞队列 |

| 操作 | 机制 | 开销 |
|------|------|------|
| 获取锁 | CAS + 自旋 + park | 微秒级 |
| 释放锁 | 原子操作 + unpark | 微秒级 |
| 等待 | _WaitSet + park | 依赖唤醒 |
| 唤醒 | unpark | 内核调用 |
