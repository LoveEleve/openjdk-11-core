# synchronized锁膨胀完整流程 - GDB验证

> **实验环境**
> - OpenJDK 11 slowdebug
> - `-Xms256m -Xmx256m -XX:+UseG1GC -XX:-UseLargePages -XX:-UseBiasedLocking`
> - **关闭偏向锁**，专注于轻量级锁 → 重量级锁膨胀过程

---

## 1. 测试程序

```java
/**
 * 锁竞争测试 - 触发锁膨胀
 */
public class SyncTestContention {
    static final Object lock = new Object();
    static volatile boolean ready = false;
    
    public static void main(String[] args) throws Exception {
        // 创建竞争线程
        Thread competitor = new Thread(() -> {
            while (!ready) Thread.yield();
            synchronized (lock) {
                System.out.println("竞争线程: 获得锁!");
            }
        }, "CompetitorThread");
        
        competitor.start();
        
        // 主线程持有锁
        synchronized (lock) {
            ready = true;  // 让竞争线程开始竞争
            Thread.sleep(100);  // 持有锁，让竞争线程阻塞
        }
        
        competitor.join();
    }
}
```

---

## 2. 锁状态编码（关闭偏向锁）

```
┌─────────────────────────────────────────────────────────────────────┐
│ Mark Word 锁状态编码 (64-bit, 关闭偏向锁)                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│ 无锁状态 (01):                                                      │
│ ┌────────────────────────────────────────────────┬────┬────┐       │
│ │              unused / hashcode                  │ age│ 01 │       │
│ └────────────────────────────────────────────────┴────┴────┘       │
│                                                                     │
│ 轻量级锁 (00):                                                      │
│ ┌────────────────────────────────────────────────────────┬────┐    │
│ │          ptr to Lock Record (栈上)                      │ 00 │    │
│ └────────────────────────────────────────────────────────┴────┘    │
│                                                                     │
│ 重量级锁 (10):                                                      │
│ ┌────────────────────────────────────────────────────────┬────┐    │
│ │          ptr to ObjectMonitor (堆外)                    │ 10 │    │
│ └────────────────────────────────────────────────────────┴────┘    │
│                                                                     │
│ GC标记 (11):                                                        │
│ ┌────────────────────────────────────────────────────────┬────┐    │
│ │                    GC相关信息                           │ 11 │    │
│ └────────────────────────────────────────────────────────┴────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

锁状态位判断:
  mark & 0x3 == 0x1 → 无锁
  mark & 0x3 == 0x0 → 轻量级锁
  mark & 0x3 == 0x2 → 重量级锁
  mark & 0x3 == 0x3 → GC标记
```

---

## 3. 锁膨胀完整流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    synchronized 锁膨胀流程 (GDB验证)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  synchronized(lock) {                                                       │
│          │                                                                  │
│          ▼                                                                  │
│  ┌─────────────────────────────────────────┐                               │
│  │ ObjectSynchronizer::fast_enter          │                               │
│  │ - 偏向锁已关闭，直接进入slow_enter      │                               │
│  └────────────┬────────────────────────────┘                               │
│               ▼                                                             │
│  ┌─────────────────────────────────────────┐                               │
│  │ ObjectSynchronizer::slow_enter          │                               │
│  │ - 检查mark word状态                      │                               │
│  │ - GDB: mark = 0x1 (无锁)                │                               │
│  └────────────┬────────────────────────────┘                               │
│               ▼                                                             │
│  ┌─────────────────────────────────────────┐                               │
│  │ 尝试CAS获取轻量级锁                      │                               │
│  │ CAS(mark, Lock Record地址)              │                               │
│  │                                          │                               │
│  │ mark = 0x1 → 0x7fffXXXXXXX0              │                               │
│  │        无锁    轻量级锁(00)              │                               │
│  └───────┬───────────────┬─────────────────┘                               │
│          │               │                                                  │
│      CAS成功          CAS失败                                               │
│          │               │                                                  │
│          ▼               ▼                                                  │
│  ┌──────────────┐ ┌────────────────────────────┐                           │
│  │ 获得轻量级锁 │ │ 检测到锁竞争               │                           │
│  │ 执行代码...  │ │ mark已是轻量级锁(00)       │                           │
│  └──────────────┘ │ 指向其他线程的Lock Record  │                           │
│                   └───────────┬────────────────┘                           │
│                               ▼                                             │
│                   ┌────────────────────────────┐                           │
│                   │ ObjectSynchronizer::inflate │ ⭐ 锁膨胀               │
│                   │ cause = MONITOR_ENTER       │                           │
│                   │                              │                           │
│                   │ 创建/获取ObjectMonitor      │                           │
│                   │ mark = ObjectMonitor | 0x2  │                           │
│                   └───────────┬────────────────┘                           │
│                               ▼                                             │
│                   ┌────────────────────────────┐                           │
│                   │ ObjectMonitor::enter        │                           │
│                   │ - 尝试CAS获取_owner         │                           │
│                   │ - 失败则park等待            │                           │
│                   └────────────────────────────┘                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. GDB验证数据

### 4.1 slow_enter入口验证

```
=== ObjectSynchronizer::slow_enter ===

(gdb) break ObjectSynchronizer::slow_enter

锁对象: 0xfff00970
mark word: 0x1
锁状态: 无锁 (01)
  → 尝试CAS设置为轻量级锁

BasicLock: 0x7ffff780a338
```

**关键数据**:
- `mark word = 0x1`: 无锁状态，末两位为01
- `BasicLock`: 栈上的Lock Record，用于存储displaced mark word

### 4.2 锁膨胀触发验证

```
========================================
=== ObjectSynchronizer::inflate #1 ===
========================================
object: 0xfff019d0
cause: 2 - WAIT

--- Mark Word分析 ---
mark word: 0x7fffdd0f42f8
锁状态位: 0
状态: 轻量级锁 (00)
  Lock Record: 0x7fffdd0f42f8
  → 竞争导致膨胀为重量级锁!

调用栈:
#0  ObjectSynchronizer::inflate
#1  ObjectSynchronizer::waitUninterruptibly
#2  ObjectLocker::waitUninterruptibly
#3  InstanceKlass::initialize_impl
```

**关键发现**:
- `mark word = 0x7fffdd0f42f8`: 末两位为00，表示轻量级锁
- `Lock Record = 0x7fffdd0f42f8`: 指向持有锁线程栈上的BasicLock
- `cause = WAIT`: 因为wait()调用触发膨胀

### 4.3 重量级锁状态验证

```
========================================
=== ObjectSynchronizer::inflate #2 ===
========================================
object: 0xfff019d0
cause: 1 - MONITOR_ENTER ⭐

--- Mark Word分析 ---
mark word: 0x7fffc8003082
锁状态位: 2
状态: 重量级锁 (10)
  ObjectMonitor: 0x7fffc8003080
  → 已膨胀，直接返回Monitor

调用栈:
#0  ObjectSynchronizer::inflate
#1  ObjectSynchronizer::slow_enter
#2  ObjectSynchronizer::fast_enter
#3  ObjectLocker::ObjectLocker
```

**关键发现**:
- `mark word = 0x7fffc8003082`: 末两位为10，表示重量级锁
- `ObjectMonitor = 0x7fffc8003080`: 去掉末两位得到Monitor地址
- 后续MONITOR_ENTER直接使用已膨胀的Monitor

### 4.4 InflateCause枚举

```cpp
// src/hotspot/share/runtime/synchronizer.hpp
typedef enum {
  inflate_cause_vm_internal = 0,   // JVM内部使用
  inflate_cause_monitor_enter = 1, // monitorenter指令 ⭐
  inflate_cause_wait = 2,          // Object.wait()
  inflate_cause_notify = 3,        // Object.notify()
  inflate_cause_hash_code = 4,     // hashCode()调用
  inflate_cause_jni_enter = 5,     // JNI MonitorEnter
  inflate_cause_jni_exit = 6,      // JNI MonitorExit
} InflateCause;
```

---

## 5. ObjectMonitor详解

### 5.1 ObjectMonitor结构

```cpp
// src/hotspot/share/runtime/objectMonitor.hpp
class ObjectMonitor {
private:
  volatile markOop   _header;       // displaced mark word (原始mark)
  void*     volatile _object;       // 关联的锁对象
  
protected:
  void*     volatile _owner;        // 持有锁的线程 (Thread*)
  volatile jlong _previous_owner_tid;
  volatile intptr_t  _recursions;   // 重入计数
  ObjectWaiter* volatile _EntryList; // 阻塞队列 (竞争锁的线程)
  ObjectWaiter* volatile _cxq;       // 竞争队列
  ObjectWaiter* volatile _WaitSet;   // 等待队列 (调用wait的线程)
  
  volatile int _Spinner;            // 自旋相关
  volatile int _SpinDuration;
};
```

### 5.2 ObjectMonitor GDB验证

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

**字段解析**:
| 字段 | 值 | 说明 |
|------|-----|------|
| `_header` | `0x1` | 原始mark word（无锁状态） |
| `_object` | `0xfff019d0` | 关联的锁对象地址 |
| `_owner` | `NULL` | 当前无持有者 |
| `_recursions` | 1 | 重入深度（1表示第一次进入） |
| `_EntryList` | `NULL` | 无阻塞线程 |

---

## 6. 锁膨胀核心源码

### 6.1 slow_enter

```cpp
// src/hotspot/share/runtime/synchronizer.cpp:340-370
void ObjectSynchronizer::slow_enter(Handle obj, BasicLock* lock, TRAPS) {
  markOop mark = obj->mark();
  
  if (mark->is_neutral()) {
    // 无锁状态，尝试CAS获取轻量级锁
    lock->set_displaced_header(mark);
    if (mark == obj()->cas_set_mark((markOop)lock, mark)) {
      return;  // CAS成功，获得轻量级锁
    }
  } else if (mark->has_locker() && 
             THREAD->is_lock_owned((address)mark->locker())) {
    // 锁重入
    lock->set_displaced_header(NULL);
    return;
  }
  
  // 锁竞争 → 膨胀为重量级锁
  lock->set_displaced_header(markOopDesc::unused_mark());
  ObjectSynchronizer::inflate(THREAD,
                              obj(),
                              inflate_cause_monitor_enter)->enter(THREAD);
}
```

### 6.2 inflate函数

```cpp
// src/hotspot/share/runtime/synchronizer.cpp:1387-1500
ObjectMonitor* ObjectSynchronizer::inflate(Thread* Self,
                                           oop object,
                                           const InflateCause cause) {
  for (;;) {
    const markOop mark = object->mark();
    
    // CASE 1: 已经是重量级锁
    if (mark->has_monitor()) {
      ObjectMonitor* inf = mark->monitor();
      return inf;
    }
    
    // CASE 2: 正在膨胀中
    if (mark == markOopDesc::INFLATING()) {
      continue;  // 自旋等待
    }
    
    // CASE 3: 轻量级锁 → 膨胀
    if (mark->has_locker()) {
      ObjectMonitor* m = omAlloc(Self);  // 分配ObjectMonitor
      m->set_header(mark->displaced_mark_helper());
      m->set_owner(mark->locker());      // 设置owner为Lock Record
      
      // CAS将mark替换为指向Monitor的指针
      if (object->cas_set_mark(markOopDesc::encode(m), mark) == mark) {
        return m;
      }
      omRelease(Self, m);  // CAS失败，释放Monitor
      continue;
    }
    
    // CASE 4: 无锁状态 → 膨胀
    ObjectMonitor* m = omAlloc(Self);
    m->set_header(mark);
    m->set_object(object);
    
    if (object->cas_set_mark(markOopDesc::encode(m), mark) == mark) {
      return m;
    }
    omRelease(Self, m);
  }
}
```

---

## 7. 关键数据汇总

| 阶段 | mark word | 状态 | 说明 |
|------|-----------|------|------|
| 初始 | `0x1` | 无锁(01) | 对象刚创建 |
| 轻量级锁 | `0x7fffdd0f42f8` | 轻量级(00) | 指向Lock Record |
| 膨胀后 | `0x7fffc8003082` | 重量级(10) | 指向ObjectMonitor |

| 对象 | GDB地址 | 说明 |
|------|---------|------|
| 锁对象 | `0xfff019d0` | Java堆中的Object |
| Lock Record | `0x7fffdd0f42f8` | 线程栈上 |
| ObjectMonitor | `0x7fffc8003080` | 堆外native内存 |

---

## 8. 关键函数调用链

```
synchronized(lock) 字节码: monitorenter
    │
    ▼
InterpreterRuntime::monitorenter
    │
    ▼
ObjectSynchronizer::fast_enter
    │  (偏向锁关闭)
    ▼
ObjectSynchronizer::slow_enter
    │
    ├─ CAS成功 → 获得轻量级锁 → 返回
    │
    └─ CAS失败 → 锁竞争
           │
           ▼
       ObjectSynchronizer::inflate
           │
           ▼
       ObjectMonitor::enter
           │
           ├─ CAS _owner成功 → 获得重量级锁
           │
           └─ CAS失败 → park()阻塞等待
```

---

## 9. GDB调试命令

```bash
# 进入调试
gdb ./java

# 设置断点
break ObjectSynchronizer::slow_enter
break ObjectSynchronizer::inflate
break ObjectMonitor::enter

# 运行
run -Xms256m -Xmx256m -XX:+UseG1GC -XX:-UseLargePages \
    -XX:-UseBiasedLocking -Xint -cp /path/to SyncTestContention

# 查看mark word
set $oop = <对象地址>
x/1gx $oop
p/x *(unsigned long*)$oop & 0x3  # 锁状态位

# 查看ObjectMonitor
set $mon = <monitor地址>
p *(ObjectMonitor*)$mon
```
