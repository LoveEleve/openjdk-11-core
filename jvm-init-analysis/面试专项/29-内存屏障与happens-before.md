# 29. 内存屏障与happens-before

## 一、概述

Java内存模型(JMM)通过happens-before关系定义了多线程程序的可见性和有序性。HotSpot JVM通过内存屏障(Memory Barrier)来实现这些语义。

### 1.1 核心概念

```
┌─────────────────────────────────────────────────────────────┐
│                    JMM与内存屏障的关系                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Java语言层面          JMM定义           HotSpot实现        │
│        ↓                  ↓                  ↓              │
│   volatile/synchronized → happens-before → Memory Barrier   │
│   final/Lock             规则              OrderAccess      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、内存屏障原理

### 2.1 四种基本屏障

**源码定义**（`orderAccess.hpp:42-68`）：
```cpp
// 我们定义四种基本的内存屏障操作
//
// LoadLoad:   Load1(s); LoadLoad; Load2
// 确保Load1在Load2之前完成，防止Load1浮到Load2之后
//
// StoreStore: Store1(s); StoreStore; Store2
// 确保Store1在Store2之前可见，防止Store1浮到Store2之后
//
// LoadStore:  Load1(s); LoadStore; Store2
// 确保Load1在Store2之前完成
//
// StoreLoad:  Store1(s); StoreLoad; Load2
// 确保Store1在Load2之前完成（最昂贵的屏障）
```

### 2.2 OrderAccess类

**核心接口**（`orderAccess.hpp:257-293`）：
```cpp
class OrderAccess : private Atomic {
 public:
  // 基本屏障
  static void     loadload();    // 读-读屏障
  static void     storestore();  // 写-写屏障
  static void     loadstore();   // 读-写屏障
  static void     storeload();   // 写-读屏障

  // 语义屏障
  static void     acquire();     // 获取屏障（LoadLoad + LoadStore）
  static void     release();     // 释放屏障（LoadStore + StoreStore）
  static void     fence();       // 全屏障（所有四种屏障）

  // 带屏障的原子操作
  template <typename T>
  static T        load_acquire(const volatile T* p);

  template <typename T, typename D>
  static void     release_store(volatile D* p, T v);

  template <typename T, typename D>
  static void     release_store_fence(volatile D* p, T v);
};
```

### 2.3 平台实现差异

**不同架构的屏障实现**（`orderAccess.hpp:122-147`）：
```cpp
//                       Constraint     x86          sparc TSO          ppc
// ---------------------------------------------------------------------------
// fence                 LoadStore  |   lock         membar #StoreLoad  sync
//                       StoreStore |   addl 0,(sp)
//                       LoadLoad   |
//                       StoreLoad
//
// release               LoadStore  |                                   lwsync
//                       StoreStore
//
// acquire               LoadLoad   |                                   lwsync
//                       LoadStore
//
// release_store                        <store>      <store>            lwsync
//                                                                      <store>
//
// load_acquire                         <load>       <load>             <load>
//                                                                      lwsync
```

**x86平台特点**：
- x86是TSO(Total Store Order)架构
- 硬件保证大部分有序性
- 只有StoreLoad需要显式屏障

---

## 三、Acquire-Release语义

### 3.1 设计原理

**源码注释**（`orderAccess.hpp:69-106`）：
```cpp
// acquire/release语义形成单向异步屏障
// 配合同步的load(X)和store(X)使用
//
// T1: access_shared_data
// T1: ]release
// T1: (...)
// T1: store(X)
//
// T2: load(X)
// T2: (...)
// T2: acquire[
// T2: access_shared_data
//
// 保证：如果T2: load(X)观察到T1: store(X)的值，
// 那么T1: ]release之前的内存访问发生在T2: acquire[之后的访问之前
```

### 3.2 使用示例

**实际应用**（`instanceKlass.inline.hpp:37-51`）：
```cpp
// 获取数组类（带acquire语义）
inline Klass* InstanceKlass::array_klasses_acquire() const {
  return OrderAccess::load_acquire(&_array_klasses);
}

// 设置数组类（带release语义）
inline void InstanceKlass::release_set_array_klasses(Klass* k) {
  OrderAccess::release_store(&_array_klasses, k);
}

// 获取jmethodID数组
inline jmethodID* InstanceKlass::methods_jmethod_ids_acquire() const {
  return OrderAccess::load_acquire(&_methods_jmethod_ids);
}

// 设置jmethodID数组
inline void InstanceKlass::release_set_methods_jmethod_ids(jmethodID* jmeths) {
  OrderAccess::release_store(&_methods_jmethod_ids, jmeths);
}
```

---

## 四、happens-before规则实现

### 4.1 程序顺序规则

**实现**：编译器保证单线程内的依赖关系
```java
// 程序顺序：同一线程中，前面的操作happens-before后面的操作
int a = 1;    // 操作A
int b = a;    // 操作B, A happens-before B
```

### 4.2 volatile变量规则

**JVM实现**：
```cpp
// volatile写 - 插入StoreStore + StoreLoad屏障
void oopDesc::release_set_mark(markOop m) {
  OrderAccess::release_store(&_mark, m);
}

// volatile读 - 插入LoadLoad + LoadStore屏障
markOop oopDesc::mark_acquire() const {
  return OrderAccess::load_acquire(&_mark);
}
```

**volatile语义实现**（`accessBackend.inline.hpp`）：
```cpp
// MO_SEQ_CST表示顺序一致性
template <DecoratorSet ds, typename T>
inline typename EnableIf<
  HasDecorator<ds, MO_SEQ_CST>::value, T>::type
RawAccessBarrier<decorators>::load_internal(void* addr) {
  if (support_IRIW_for_not_multiple_copy_atomic_cpu) {
    OrderAccess::fence();  // 全屏障
  }
  return OrderAccess::load_acquire(reinterpret_cast<const volatile T*>(addr));
}
```

### 4.3 监视器锁规则

**synchronized实现**：
```cpp
// 锁获取 - acquire语义
void ObjectMonitor::enter(TRAPS) {
  // ...获取锁...
  // 隐含acquire屏障
}

// 锁释放 - release语义  
void ObjectMonitor::exit(bool not_suspended, TRAPS) {
  // release屏障确保临界区写入可见
  OrderAccess::release_store(&_recursions, (intptr_t)0);
  // ...释放锁...
}
```

### 4.4 线程启动规则

```cpp
// Thread.start() happens-before 线程中的第一个操作
void JavaThread::thread_main_inner() {
  // 等待创建线程完成初始化
  // 隐含acquire语义
  while (!is_running()) {
    os::naked_yield();
  }
  // 现在可以安全访问共享数据
}
```

### 4.5 线程终止规则

```cpp
// 线程的最后一个操作 happens-before join()返回
void JavaThread::exit(bool destroy_vm, ExitType exit_type) {
  // 设置线程状态（release语义）
  set_terminated_value();
  OrderAccess::fence();  // 确保所有写入可见
  
  // 通知等待的线程
  lock.notify_all(thread);
}
```

---

## 五、GC中的内存屏障

### 5.1 写屏障与内存屏障

**G1写屏障**：
```cpp
// G1后置写屏障
void G1BarrierSet::write_ref_field_post(oop obj, oop* field, oop new_val) {
  // 更新Remembered Set需要保证可见性
  OrderAccess::storestore();  // 确保字段写入完成
  
  // 记录卡表标记
  volatile CardValue* byte = _card_table->byte_for(field);
  *byte = G1CardTable::dirty_card_val();
}
```

### 5.2 并发标记屏障

**SATB屏障**：
```cpp
// SATB写前屏障
void SATBMarkQueue::enqueue(oop pre_val) {
  if (pre_val == NULL) return;
  
  // 需要确保原值被正确记录
  void** buf = _buf;
  if (_index == 0) {
    handle_zero_index();
    return;
  }
  _index -= sizeof(void*);
  buf[_index] = pre_val;
  // 隐含release语义，确保队列更新可见
}
```

---

## 六、常见场景分析

### 6.1 双重检查锁定

```java
public class Singleton {
    private static volatile Singleton instance;  // 必须volatile
    
    public static Singleton getInstance() {
        if (instance == null) {               // 第一次检查
            synchronized (Singleton.class) {
                if (instance == null) {       // 第二次检查
                    instance = new Singleton(); // volatile写
                }
            }
        }
        return instance;
    }
}
```

**JVM层面**：
```cpp
// new Singleton()展开：
// 1. allocate memory
// 2. 调用构造函数（初始化字段）
// 3. instance = obj（volatile写）

// 如果没有volatile，2和3可能重排序：
// 1. allocate memory
// 3. instance = obj（非null！）
// 2. 构造函数（可能被其他线程看到未初始化的对象）
```

### 6.2 工作窃取队列

**源码**（`taskqueue.inline.hpp:163-215`）：
```cpp
// 多线程任务队列操作
template<class E, MEMFLAGS F, unsigned int N>
bool GenericTaskQueue<E, F, N>::pop_global(volatile E& t) {
  Age oldAge = _age.get();
  
  // 弱内存模型架构需要屏障
#if !(defined SPARC || defined IA32 || defined AMD64)
  OrderAccess::fence();
#endif

  uint localBot = OrderAccess::load_acquire(&_bottom);
  uint n_elems = size(localBot, oldAge.top());
  
  if (n_elems == 0) {
    return false;
  }
  // ...
}

template<class E, MEMFLAGS F, unsigned int N>
bool GenericTaskQueue<E, F, N>::pop_local_slow(uint localBot, Age oldAge) {
  localBot = decrement_index(localBot);
  _bottom = localBot;
  
  // 防止后续读取被重排序到上面的写入之前
  OrderAccess::fence();
  // ...
}
```

### 6.3 常量池解析

**源码**（`constantPool.cpp:247-256`）：
```cpp
void ConstantPool::klass_at_put(int class_index, Klass* k) {
  assert(k != NULL, "must be valid klass");
  CPKlassSlot kslot = klass_slot_at(class_index);
  int resolved_klass_index = kslot.resolved_klass_index();
  Klass** adr = resolved_klasses()->adr_at(resolved_klass_index);
  
  // release语义确保Klass完全初始化后才可见
  OrderAccess::release_store(adr, k);
  
  // 解释器假设：当tag存储后，klass已解析且非NULL
  // 需要硬件存储顺序保证
  release_tag_at_put(class_index, JVM_CONSTANT_Class);
}
```

---

## 七、面试要点

### 7.1 常见问题

**Q1: 什么是happens-before？**
```
A: happens-before是JMM定义的偏序关系：
1. 如果A happens-before B，则A的执行结果对B可见
2. 不代表A一定在B之前执行（允许重排序，只要结果正确）
3. JMM通过happens-before保证多线程程序的可见性和有序性
```

**Q2: volatile如何保证可见性和有序性？**
```
A: 通过内存屏障：
1. 可见性：
   - volatile写后插入StoreLoad屏障，强制刷新到主内存
   - volatile读前插入LoadLoad屏障，从主内存读取
   
2. 有序性：
   - 写前：StoreStore屏障，防止前面的写重排序到volatile写之后
   - 写后：StoreLoad屏障，防止后面的读写重排序到volatile写之前
   - 读后：LoadLoad + LoadStore屏障，防止后面的读写重排序到volatile读之前
```

**Q3: synchronized如何保证happens-before？**
```
A: 通过监视器锁的acquire-release语义：
1. 锁获取时执行acquire：
   - 清空本地缓存，从主内存读取
   - 后续读写不能重排序到锁获取之前
   
2. 锁释放时执行release：
   - 将修改刷新到主内存
   - 前面的读写不能重排序到锁释放之后
```

**Q4: 为什么x86不需要显式LoadLoad和StoreStore屏障？**
```
A: x86是TSO(Total Store Order)架构：
1. 硬件保证Load-Load有序
2. 硬件保证Store-Store有序
3. 只有Store-Load可能重排序，需要显式屏障
4. 因此x86上很多屏障是空操作（no-op）
```

### 7.2 屏障开销对比

| 屏障类型 | x86 | ARM/PowerPC | 说明 |
|---------|-----|-------------|------|
| LoadLoad | 空 | 有开销 | x86硬件保证 |
| StoreStore | 空 | 有开销 | x86硬件保证 |
| LoadStore | 空 | 有开销 | x86硬件保证 |
| StoreLoad | mfence | 有开销 | 最昂贵的屏障 |

### 7.3 调试技巧

```bash
# 查看JIT生成的汇编代码，验证屏障插入
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+PrintAssembly \
     -XX:CompileCommand=print,*YourClass.yourMethod \
     YourClass
```

---

## 八、总结

### 8.1 屏障层次

```
Java层面                    JVM层面                 硬件层面
   ↓                          ↓                       ↓
volatile/synchronized  →  OrderAccess  →  mfence/dmb/sync
final/Lock             →  memory barriers →  CPU指令
```

### 8.2 关键原则

1. **acquire-release配对使用**：发布数据用release，访问数据用acquire
2. **最小化屏障使用**：屏障有开销，只在必要时使用
3. **平台差异**：x86是强内存模型，ARM/PPC是弱内存模型
4. **优先使用高级原语**：volatile、synchronized比手动屏障更安全

### 8.3 happens-before规则汇总

| 规则 | 描述 |
|-----|------|
| 程序顺序 | 同一线程内的操作按程序顺序 |
| 监视器锁 | unlock happens-before 后续lock |
| volatile | 写 happens-before 后续读 |
| 线程启动 | start() happens-before 线程第一个操作 |
| 线程终止 | 线程最后操作 happens-before join()返回 |
| 传递性 | A hb B, B hb C → A hb C |
