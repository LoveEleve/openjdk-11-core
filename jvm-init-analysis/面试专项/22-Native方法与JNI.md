# JVM技术专家面试 - Native方法与JNI

## 面试题1：JNI Handle的三种类型及其生命周期是怎样的？

**难度**：⭐⭐⭐⭐

### 面试官问：

Native代码需要访问Java对象，但GC可能随时移动对象。JVM如何保证Native代码持有的对象引用在GC后仍然有效？

### 答题要点：

JNI通过**间接引用（Handle）机制**解决这个问题。Native代码不直接持有对象指针，而是持有Handle，JVM负责在GC时更新Handle指向的地址。

#### 1. 三种Handle类型

从源码 `jniHandles.hpp` 可以看到：

```cpp
class JNIHandles : AllStatic {
 private:
  static OopStorage* _global_handles;      // 全局Handle存储
  static OopStorage* _weak_global_handles; // 弱全局Handle存储

 public:
  // Local handles
  static jobject make_local(oop obj);
  
  // Global handles
  static jobject make_global(Handle obj, AllocFailType alloc_failmode);
  
  // Weak global handles
  static jobject make_weak_global(Handle obj, AllocFailType alloc_failmode);
};
```

#### 2. Local Handle

**特点**：
- 生命周期绑定到Native方法调用
- 方法返回时自动释放
- 存储在线程的 `JNIHandleBlock` 链表中

```cpp
// jniHandles.hpp
class JNIHandleBlock : public CHeapObj<mtInternal> {
  enum SomeConstants {
    block_size_in_oops = 32   // 每个Block存32个Handle
  };

  oop _handles[block_size_in_oops];  // Handle数组
  int _top;                          // 下一个未使用的位置
  JNIHandleBlock* _next;             // 链表下一个Block
};
```

Local Handle的创建（简化）：

```cpp
jobject JNIHandles::make_local(oop obj) {
    // 1. 获取当前线程的JNIHandleBlock
    JNIHandleBlock* block = thread->active_handles();
    
    // 2. 在block中分配槽位
    if (block->_top < block_size_in_oops) {
        block->_handles[block->_top++] = obj;
        return (jobject)&block->_handles[block->_top - 1];
    } else {
        // 当前block满了，分配新block
        // ...
    }
}
```

#### 3. Global Handle

**特点**：
- 跨Native方法调用存活
- 必须手动调用 `DeleteGlobalRef` 释放
- 存储在全局 `OopStorage` 中

```cpp
jobject JNIHandles::make_global(Handle obj, AllocFailType alloc_failmode) {
    assert(!Universe::heap()->is_gc_active(), "GC期间不能创建");
    jobject res = NULL;
    if (!obj.is_null()) {
        oop *ptr = global_handles()->allocate();  // 从OopStorage分配
        if (ptr != NULL) {
            *ptr = obj();  // 存储对象引用
            res = reinterpret_cast<jobject>(ptr);
        }
    }
    return res;
}
```

#### 4. Weak Global Handle

**特点**：
- 不阻止对象被GC回收
- 引用的对象被回收后，Handle变为NULL
- 常用于缓存

```cpp
// 检测弱引用是否已被清除
static bool is_global_weak_cleared(jweak handle);
```

#### 5. Handle的标识机制

JNI使用低位标记区分Handle类型：

```cpp
// jniHandles.hpp
static const uintptr_t weak_tag_size = 1;
static const uintptr_t weak_tag_alignment = (1u << weak_tag_size);  // 2
static const uintptr_t weak_tag_mask = weak_tag_alignment - 1;       // 1
static const int weak_tag_value = 1;

inline static bool is_jweak(jobject handle) {
    return (reinterpret_cast<uintptr_t>(handle) & weak_tag_mask) != 0;
}
```

#### 内存布局对比

```
Local Handle:
+------------------+
| Thread           |
|  +---------------+
|  | active_handles|---> JNIHandleBlock
|  +---------------+     +------------------+
+------------------+     | _handles[0..31]  | <-- jobject指向这里
                         | _top             |
                         | _next            |---> 下一个Block
                         +------------------+

Global Handle:
+------------------+
| OopStorage       |
|  +---------------+
|  | Block Array   |---> [Block0][Block1]...
|  +---------------+       |
+------------------+       v
                    +------------------+
                    | slot[0] = oop    | <-- jobject指向这里
                    | slot[1] = oop    |
                    +------------------+
```

---

## 面试题2：JNI调用是如何在Native代码和Java代码之间切换线程状态的？

**难度**：⭐⭐⭐⭐⭐

### 面试官问：

当Java调用Native方法，或Native调用Java方法时，线程状态是如何变化的？为什么这个状态很重要？

### 答题要点：

#### 1. 线程状态与Safepoint的关系

JVM的GC需要在Safepoint停止所有Java线程。但执行Native代码的线程不需要停止，因为Native代码不会直接访问Java堆。

关键状态定义：

```cpp
// thread.hpp
enum JavaThreadState {
  _thread_in_vm       = 2,   // 执行VM代码（C++代码操作Java对象）
  _thread_in_vm_trans = 3,   // 从VM状态转换
  _thread_in_Java     = 4,   // 执行Java字节码
  _thread_in_Java_trans = 5, // 从Java状态转换
  _thread_in_native   = 6,   // 执行Native代码
  _thread_in_native_trans = 7 // 从Native状态转换
};
```

#### 2. Java调用Native（JNI下行调用）

状态转换：`_thread_in_Java` → `_thread_in_native`

```cpp
// 简化的状态转换宏
#define TRANSITION_FROM_JAVA_TO_NATIVE \
    ThreadStateTransition::transition_from_java(thread, _thread_in_native);

// 实际转换
class ThreadStateTransition : public StackObj {
 public:
  static inline void transition_from_java(JavaThread *thread, JavaThreadState to) {
    // Java → Native 不需要检查safepoint
    // 因为Native代码不会持有Java堆引用
    thread->set_thread_state(to);
    
    // 但如果有pending的safepoint，需要在这里响应
    if (SafepointSynchronize::do_call_back()) {
      SafepointSynchronize::block(thread);
    }
  }
};
```

#### 3. Native调用Java（JNI上行调用）

状态转换：`_thread_in_native` → `_thread_in_vm` → `_thread_in_Java`

这个过程更复杂，因为要从不阻塞GC的状态回到可能持有Java引用的状态。

```cpp
// prims/jni.cpp 中典型的JNI函数入口
JNI_ENTRY(jclass, jni_FindClass(JNIEnv *env, const char *name))
  // JNI_ENTRY宏展开后包含ThreadInVMfromNative
  
  // ThreadInVMfromNative 完成 native → vm 转换
  // 包括：
  // 1. 设置状态为 _thread_in_native_trans（中间态）
  // 2. 检查safepoint，必要时阻塞
  // 3. 设置状态为 _thread_in_vm
  
  // ... 执行实际逻辑
JNI_END
```

```cpp
// 状态转换的详细实现
class ThreadInVMfromNative : public ThreadStateTransition {
 public:
  ThreadInVMfromNative(JavaThread* thread) : ThreadStateTransition(thread) {
    // Step 1: 设置为转换中状态
    thread->set_thread_state(_thread_in_native_trans);
    
    // Step 2: 内存屏障，确保状态对其他线程可见
    OrderAccess::fence();
    
    // Step 3: 检查是否需要在safepoint阻塞
    if (SafepointSynchronize::do_call_back() ||
        thread->is_suspend_after_native()) {
      JavaThread::check_safepoint_and_suspend_for_native_trans(thread);
    }
    
    // Step 4: 正式进入VM状态
    thread->set_thread_state(_thread_in_vm);
  }
  
  ~ThreadInVMfromNative() {
    // 析构时回到native状态
    thread()->set_thread_state(_thread_in_native);
  }
};
```

#### 4. 为什么状态转换如此重要？

```
GC Safepoint 视角：

时刻T1: GC请求Safepoint
        |
        v
线程A: [Java代码] ---> 必须停在safepoint
线程B: [Native代码] ---> 不需要停，但不能访问Java堆
线程C: [Native→VM转换中] ---> 必须等待，完成转换后再执行

如果没有正确的状态管理：
- 线程B可能在GC移动对象时访问Java堆 → 野指针
- 线程C可能在对象地址改变后使用旧地址 → 数据损坏
```

#### 5. 实际示例：FindClass的完整流程

```cpp
JNI_ENTRY(jclass, jni_FindClass(JNIEnv *env, const char *name))
  // 此时已经完成 native → vm 转换
  
  // 分配Handle来安全持有Java对象
  Handle loader(THREAD, SystemDictionary::java_system_loader());
  
  // 调用类加载机制
  TempNewSymbol sym = SymbolTable::new_symbol(name, CHECK_NULL);
  result = find_class_from_class_loader(env, sym, true, loader,
                                        Handle(), true, thread);
  
  // 函数返回时，JNI_END 会：
  // 1. 清理本次调用分配的local handles
  // 2. 将状态从 _thread_in_vm 转回 _thread_in_native
  
  return result;
JNI_END
```

---

## 面试题3：jfieldID和jmethodID的编码机制是什么？

**难度**：⭐⭐⭐⭐

### 面试官问：

Native代码通过 `GetFieldID/GetMethodID` 获取的ID实际是什么？为什么不直接返回字段偏移量或方法指针？

### 答题要点：

#### 1. jfieldID的编码

从源码 `jni.cpp` 可以看到，jfieldID对实例字段进行了特殊编码：

```cpp
// jni.cpp
intptr_t jfieldIDWorkaround::encode_klass_hash(Klass* k, intptr_t offset) {
  if (offset <= small_offset_mask) {
    // 对于小偏移量，编码包含klass哈希用于验证
    Klass* field_klass = k;
    Klass* super_klass = field_klass->super();
    
    // 找到真正定义该字段的类
    while (InstanceKlass::cast(super_klass)->has_nonstatic_fields() &&
           InstanceKlass::cast(super_klass)->contains_field_offset(offset)) {
      field_klass = super_klass;
      super_klass = field_klass->super();
    }
    
    uintptr_t klass_hash = field_klass->identity_hash();
    return ((klass_hash & klass_mask) << klass_shift) | checked_mask_in_place;
  } else {
    return 0;  // 大偏移量不做哈希检查
  }
}
```

jfieldID的结构（32位环境）：

```
对于实例字段（小偏移量）：
+-----+-----+------------+---+
|klass|     |   offset   | 1 |  checked bit
|hash |     |            |   |
+-----+-----+------------+---+
 高位                      低位

对于静态字段：
直接返回 JNIid* 指针（指向JNIid结构）
```

#### 2. jfieldID验证

为什么需要编码klass哈希？防止使用错误的类访问字段：

```cpp
bool jfieldIDWorkaround::klass_hash_ok(Klass* k, jfieldID id) {
  uintptr_t as_uint = (uintptr_t) id;
  intptr_t klass_hash = (as_uint >> klass_shift) & klass_mask;
  do {
    // 沿继承链检查哈希是否匹配
    if ((k->identity_hash() & klass_mask) == klass_hash)
      return true;
    k = k->super();
  } while (k != NULL);
  return false;  // 哈希不匹配，说明使用了错误的类
}

void jfieldIDWorkaround::verify_instance_jfieldID(Klass* k, jfieldID id) {
  guarantee(is_instance_jfieldID(k, id), "must be an instance field");
  if (VerifyJNIFields) {
    if (is_checked_jfieldID(id)) {
      guarantee(klass_hash_ok(k, id),
        "Bug in native code: jfieldID class must match object");
    }
  }
  guarantee(InstanceKlass::cast(k)->contains_field_offset(offset),
      "Bug in native code: jfieldID offset must address interior of object");
}
```

#### 3. jmethodID的实现

jmethodID直接指向内部的Method元数据：

```cpp
// method.cpp
jmethodID Method::jmethod_id() {
  methodHandle mh(Thread::current(), this);
  return method_holder()->get_jmethod_id(mh);
}

// instanceKlass.cpp
jmethodID InstanceKlass::get_jmethod_id(const methodHandle& method_h) {
  // 使用JNIid数组管理，保证ID的稳定性
  // 即使类被重定义(JVMTI)，ID仍然有效
  
  // JNIid包含：
  // - Method* 指向当前方法
  // - next指针形成链表
  // - 所属Klass*
}
```

#### 4. 为什么不直接用偏移量/指针？

| 问题 | 直接方案的缺陷 | ID方案的优势 |
|------|---------------|-------------|
| 类型安全 | 无法验证对象类型 | 可通过klass哈希验证 |
| 类重定义 | 方法地址会变化 | JNIid保持稳定 |
| 内存安全 | 野指针风险 | VM可以验证ID有效性 |
| 调试 | 难以追踪问题 | 可以添加检查 |

---

## 面试题4：JNI Critical Section的工作原理？

**难度**：⭐⭐⭐⭐⭐

### 面试官问：

`GetPrimitiveArrayCritical/ReleasePrimitiveArrayCritical` 和普通的 `Get*ArrayElements` 有什么区别？为什么叫"Critical"？

### 答题要点：

#### 1. 普通访问 vs Critical访问

**普通访问（GetIntArrayElements等）**：

```cpp
JNI_ENTRY(jint*, jni_GetIntArrayElements(JNIEnv *env, jintArray array, jboolean *isCopy))
  // 1. 分配C堆内存
  // 2. 复制数组内容到C堆
  // 3. 返回C堆指针
  // 优点：安全，GC可以自由移动原数组
  // 缺点：有复制开销
JNI_END
```

**Critical访问**：

```cpp
JNI_ENTRY(void*, jni_GetPrimitiveArrayCritical(JNIEnv *env, jarray array, jboolean *isCopy))
  // 直接返回Java堆中数组的内部指针！
  // 没有复制，零开销
  // 但是：在Release之前GC被禁止移动对象
  
  // 进入GC Critical Section
  GCLocker::lock_critical(thread);
  
  // 直接返回数组数据的指针
  typeArrayOop a = typeArrayOop(JNIHandles::resolve_non_null(array));
  void* ret = a->base(element_type);
  
  return ret;
JNI_END
```

#### 2. GC Locker机制

```cpp
// gcLocker.cpp
class GCLocker : public AllStatic {
  static volatile jint _jni_lock_count;  // Critical区域的计数
  static volatile bool _needs_gc;         // 是否有pending的GC

 public:
  static void lock_critical(JavaThread* thread) {
    // 原子增加锁计数
    Atomic::inc(&_jni_lock_count);
    // 标记线程在critical区域
    thread->enter_critical_region();
  }
  
  static void unlock_critical(JavaThread* thread) {
    thread->exit_critical_region();
    Atomic::dec(&_jni_lock_count);
    
    // 如果有pending的GC且没有其他线程在critical区域
    if (needs_gc() && !is_active()) {
      // 触发之前被阻塞的GC
      JvmtiGCMarker jgcm;
      GCLocker::stall_until_clear();
    }
  }
  
  // GC调用：是否可以开始GC？
  static bool check_active_before_gc() {
    if (_jni_lock_count > 0) {
      _needs_gc = true;  // 标记需要GC
      return false;       // 不能开始GC
    }
    return true;          // 可以GC
  }
};
```

#### 3. Critical Section的限制

在Critical Section内，Native代码必须遵守严格限制：

```
禁止的操作：
┌─────────────────────────────────────────────────────────┐
│ 1. 调用任何可能分配Java对象的JNI函数                      │
│    - NewObject, NewString, New*Array                     │
│    - CallXxxMethod (可能触发异常创建)                     │
│                                                          │
│ 2. 调用任何可能阻塞的操作                                 │
│    - MonitorEnter (可能死锁)                              │
│    - CallXxxMethod (可能等待锁)                           │
│                                                          │
│ 3. 长时间持有（会饿死GC）                                 │
│    - 应该尽快Release                                      │
└─────────────────────────────────────────────────────────┘

原因：
- GC被阻塞，内存无法回收
- 其他需要GC的线程会阻塞
- 可能导致整个JVM响应变慢或OOM
```

#### 4. 死锁场景

```
线程A:                          线程B:
GetCritical(array1)             synchronized(lock) {
  |                               allocate() → 触发GC
  |                               GC等待A退出critical
  v                               |
synchronized(lock) → 等待B释放   v
                                GC等待中...

结果：死锁！
A等B释放锁，B等GC完成，GC等A退出Critical
```

#### 5. 正确使用模式

```c
// 正确：短暂持有，不做其他JNI调用
void processArray(JNIEnv *env, jintArray arr) {
    jboolean isCopy;
    jint *elements = (*env)->GetPrimitiveArrayCritical(env, arr, &isCopy);
    if (elements == NULL) return;  // OOM
    
    // 仅做简单的内存操作
    for (int i = 0; i < len; i++) {
        elements[i] *= 2;  // OK
    }
    
    // 立即释放
    (*env)->ReleasePrimitiveArrayCritical(env, arr, elements, 0);
    
    // 在Release之后才能做其他JNI调用
    jclass cls = (*env)->FindClass(env, "...");
}
```

---

## 面试题5：RegisterNatives和动态链接的区别是什么？

**难度**：⭐⭐⭐⭐

### 面试官问：

Native方法可以通过 `RegisterNatives` 手动注册，也可以让JVM自动链接。这两种方式各有什么优缺点？底层如何实现？

### 答题要点：

#### 1. 动态链接（默认方式）

JVM根据命名规则在共享库中查找Native方法：

```cpp
// nativeLookup.cpp
address NativeLookup::lookup_base(const methodHandle& method, TRAPS) {
  // 构造Native方法名：Java_包名_类名_方法名
  // 例如：Java_com_example_MyClass_nativeMethod
  
  char* pure_name = pure_jni_name(method);
  char* long_name = long_jni_name(method);  // 包含参数签名
  
  // 1. 先尝试短名称
  address entry = lookup_style(method, pure_name, "", THREAD);
  if (entry != NULL) return entry;
  
  // 2. 再尝试长名称（处理重载）
  entry = lookup_style(method, pure_name, long_name, THREAD);
  if (entry != NULL) return entry;
  
  // 3. 查找失败，抛出UnsatisfiedLinkError
  THROW_MSG_0(vmSymbols::java_lang_UnsatisfiedLinkError(), method->name());
}
```

命名规则：

```
Java方法: package com.example; class MyClass { native void foo(int x, String s); }

短名称: Java_com_example_MyClass_foo
长名称: Java_com_example_MyClass_foo__ILjava_lang_String_2

特殊字符转义：
  _ → _1
  ; → _2
  [ → _3
  / → _
```

#### 2. RegisterNatives（手动注册）

```cpp
// jni.cpp
JNI_ENTRY(jint, jni_RegisterNatives(JNIEnv *env, jclass clazz,
                                    const JNINativeMethod *methods,
                                    jint nMethods))
  Klass* k = java_lang_Class::as_Klass(JNIHandles::resolve_non_null(clazz));
  
  for (int i = 0; i < nMethods; i++) {
    // 查找Java方法
    TempNewSymbol name = SymbolTable::new_symbol(methods[i].name);
    TempNewSymbol sig = SymbolTable::new_symbol(methods[i].signature);
    
    Method* m = InstanceKlass::cast(k)->find_method(name, sig);
    if (m == NULL || !m->is_native()) {
      // 方法不存在或不是native
      THROW_MSG_0(...);
    }
    
    // 设置native函数指针
    m->set_native_function((address)methods[i].fnPtr, Method::native_bind_event_is_interesting);
  }
  
  return 0;
JNI_END
```

#### 3. 方法入口点的设置

```cpp
// method.cpp
void Method::set_native_function(address function, bool post_event) {
  // 设置本地函数指针
  *native_function_addr() = function;
  
  // 生成或更新adapter stub
  // adapter负责：
  // 1. 参数传递转换
  // 2. Handle/oop转换
  // 3. 状态转换
  // 4. 异常检查
  
  // 通知JVMTI
  if (post_event && JvmtiExport::should_post_native_method_bind()) {
    JvmtiExport::post_native_method_bind(this, &function);
  }
}
```

#### 4. 优缺点对比

| 特性 | 动态链接 | RegisterNatives |
|------|---------|-----------------|
| 灵活性 | 固定命名规则 | 可以任意命名 |
| 首次调用 | 需要查找，稍慢 | 已注册，直接调用 |
| 库依赖 | 必须能加载共享库 | 可以在任何地方注册 |
| 热替换 | 困难 | 可以重新注册 |
| 混淆 | 函数名暴露 | 可以隐藏函数名 |
| 类型安全 | 签名必须匹配 | 手动保证签名正确 |

#### 5. 典型使用场景

**Android使用RegisterNatives**：

```cpp
// Android系统的做法
static JNINativeMethod gMethods[] = {
    {"nativeCreate", "(II)J", (void*)android_view_Surface_nativeCreate},
    {"nativeDestroy", "(J)V", (void*)android_view_Surface_nativeDestroy},
};

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    
    jclass cls = env->FindClass("android/view/Surface");
    env->RegisterNatives(cls, gMethods, sizeof(gMethods)/sizeof(gMethods[0]));
    
    return JNI_VERSION_1_6;
}
```

**优势**：
1. 启动时一次性注册，避免运行时查找
2. 函数名可以用有意义的C命名
3. 便于代码混淆

---

## 面试题6：JNI调用中的异常处理机制是怎样的？

**难度**：⭐⭐⭐⭐

### 面试官问：

Native方法中如何处理Java异常？JVM如何检测和传播Native代码中发生的异常？

### 答题要点：

#### 1. 异常的检测与获取

```cpp
// jni.cpp
JNI_ENTRY(jboolean, jni_ExceptionCheck(JNIEnv *env))
  // 检查当前线程是否有pending异常
  return thread->has_pending_exception() ? JNI_TRUE : JNI_FALSE;
JNI_END

JNI_ENTRY(jthrowable, jni_ExceptionOccurred(JNIEnv *env))
  // 获取pending异常（作为local ref返回）
  oop exception = thread->pending_exception();
  jthrowable ret = (jthrowable) JNIHandles::make_local(env, exception);
  return ret;
JNI_END
```

#### 2. 异常的抛出

```cpp
// jni.cpp
JNI_ENTRY(jint, jni_ThrowNew(JNIEnv *env, jclass clazz, const char *message))
  // 1. 解析异常类
  Klass* k = java_lang_Class::as_Klass(JNIHandles::resolve_non_null(clazz));
  
  // 2. 创建异常对象（调用构造函数）
  Handle exception = Exceptions::new_exception(
      thread, 
      k->name(),
      message
  );
  
  // 3. 设置为pending异常
  thread->set_pending_exception(exception(), __FILE__, __LINE__);
  
  return 0;
JNI_END
```

#### 3. 异常传播机制

```
Native方法返回时的异常检查：

JNI调用返回:
    ↓
检查pending_exception
    ↓
┌─────────────────────────────────┐
│ if (thread->has_pending_exception()) │
│   → 不执行后续Java代码            │
│   → 传播异常到Java调用者          │
│   → 触发异常处理器查找            │
└─────────────────────────────────┘
```

JNI函数调用返回后，如果有pending异常，任何后续JNI调用（除了异常处理相关函数）的结果都是未定义的。

#### 4. CHECK宏的作用

在JVM源码中，CHECK宏用于异常检查：

```cpp
// exceptions.hpp
#define CHECK          THREAD); if (HAS_PENDING_EXCEPTION) return; (void)(0
#define CHECK_NULL     THREAD); if (HAS_PENDING_EXCEPTION) return NULL; (void)(0
#define CHECK_0        THREAD); if (HAS_PENDING_EXCEPTION) return 0; (void)(0

// 使用示例
TempNewSymbol sym = SymbolTable::new_symbol(name, CHECK_NULL);
// 如果new_symbol抛出异常，直接返回NULL

result = find_class_from_class_loader(env, sym, true, loader,
                                      protection_domain, true, thread);
if (HAS_PENDING_EXCEPTION) {
    // 清理资源
    // ...
    return NULL;
}
```

#### 5. Native代码的正确异常处理模式

```c
// 错误示例：不检查异常
JNIEXPORT void JNICALL Java_Example_badMethod(JNIEnv *env, jobject obj) {
    jclass cls = (*env)->FindClass(env, "NonExistentClass");
    // cls可能是NULL，但异常被设置了
    
    jmethodID mid = (*env)->GetMethodID(env, cls, "method", "()V");
    // 崩溃！cls是NULL
}

// 正确示例：检查每个可能失败的调用
JNIEXPORT void JNICALL Java_Example_goodMethod(JNIEnv *env, jobject obj) {
    jclass cls = (*env)->FindClass(env, "com/example/MyClass");
    if (cls == NULL) {
        // 异常已设置，直接返回
        return;
    }
    
    jmethodID mid = (*env)->GetMethodID(env, cls, "method", "()V");
    if (mid == NULL) {
        return;  // NoSuchMethodError已设置
    }
    
    (*env)->CallVoidMethod(env, obj, mid);
    if ((*env)->ExceptionCheck(env)) {
        // 可以选择：
        // 1. 直接返回，让Java处理
        // 2. ExceptionClear + 做清理 + 重新抛出
        // 3. 捕获并处理
        return;
    }
}
```

#### 6. 异常安全的资源管理

```c
JNIEXPORT void JNICALL Java_Example_withCleanup(JNIEnv *env, jobject obj) {
    char *buffer = malloc(1024);
    
    jclass cls = (*env)->FindClass(env, "com/example/MyClass");
    if (cls == NULL) {
        free(buffer);  // 清理！
        return;
    }
    
    // ... 更多操作
    
    free(buffer);
}
```

---

## 面试题7：JNI全局引用泄漏如何检测和诊断？

**难度**：⭐⭐⭐⭐

### 面试官问：

如果Native代码创建了全局引用但忘记删除，会导致内存泄漏。如何诊断这类问题？

### 答题要点：

#### 1. 全局引用泄漏的特征

```
症状：
- 堆内存持续增长，即使触发Full GC
- 某些类型的对象数量持续增加
- OOM错误显示堆满，但dump显示大量"应该被回收"的对象

根因：
- Global ref持有对象，阻止GC回收
- 常见于：
  1. FindClass结果未释放
  2. NewGlobalRef后忘记DeleteGlobalRef
  3. 在循环中创建全局引用
```

#### 2. JVM诊断参数

```bash
# 打印JNI全局引用统计
-XX:+TraceJNIGlobalRefCreation

# 限制全局引用数量（调试用）
-XX:MaxJNILocalCapacity=65536

# 启用CheckJNI模式
-Xcheck:jni
```

#### 3. 从源码看诊断接口

```cpp
// jniHandles.hpp
class JNIHandles {
  // 内存使用统计
  static size_t global_handle_memory_usage();
  static size_t weak_global_handle_memory_usage();
  
  // 诊断打印
  static void print_on(outputStream* st);
};
```

#### 4. 检测方法

**方法1：JNI函数拦截**

```c
// 包装NewGlobalRef，记录调用位置
jobject tracked_NewGlobalRef(JNIEnv *env, jobject obj) {
    jobject ref = (*env)->NewGlobalRef(env, obj);
    if (ref != NULL) {
        log_global_ref_creation(ref, __FILE__, __LINE__);
    }
    return ref;
}
```

**方法2：定期dump统计**

```java
// 通过Management Bean获取
import sun.management.ManagementFactoryHelper;

// 或者通过Native接口
public native void dumpJNIGlobalRefs();
```

**方法3：Heap Dump分析**

使用MAT或VisualVM分析heap dump：
1. 查找所有被JNI Global Reference引用的对象
2. 检查这些对象的引用链
3. 识别不应该存活的对象

#### 5. 预防措施

```cpp
// 使用RAII风格的包装类
class GlobalRef {
    JNIEnv* env;
    jobject ref;
public:
    GlobalRef(JNIEnv* e, jobject obj) : env(e) {
        ref = env->NewGlobalRef(obj);
    }
    ~GlobalRef() {
        if (ref) env->DeleteGlobalRef(ref);
    }
    operator jobject() const { return ref; }
};

// 使用示例
void example(JNIEnv* env, jobject obj) {
    GlobalRef global(env, obj);  // 自动管理
    // ... 使用global
}  // 自动释放
```

---

## 总结：JNI核心机制

### Handle机制三层次

```
┌─────────────────────────────────────────────────────┐
│                    Native Code                       │
│  jobject/jclass/jstring... (Handle引用)              │
└─────────────────────────────────────────────────────┘
                         │
                         ↓
┌─────────────────────────────────────────────────────┐
│                  JNI Handle Layer                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ Local    │  │ Global   │  │ Weak     │          │
│  │ Handles  │  │ Handles  │  │ Global   │          │
│  │ (Thread) │  │ (OopStor)│  │ Handles  │          │
│  └──────────┘  └──────────┘  └──────────┘          │
└─────────────────────────────────────────────────────┘
                         │
                         ↓
┌─────────────────────────────────────────────────────┐
│                    Java Heap                         │
│              Actual oop (对象指针)                   │
│         GC可以移动，Handle层自动更新                  │
└─────────────────────────────────────────────────────┘
```

### 状态转换与安全点

```
状态机：

    _thread_in_Java
         │
    ┌────┴────┐
    ↓         ↓
 字节码     Native调用
 执行      (需要响应safepoint)
            │
            ↓
    _thread_in_native
    (不阻塞GC，不访问堆)
            │
    ┌───────┴───────┐
    ↓               ↓
 纯Native       JNI回调
 计算          (需要重新进入VM)
                  │
                  ↓
           _thread_in_vm
           (可以访问堆)
```

### JNI最佳实践

1. **最小化JNI调用次数**：每次跨界调用都有开销
2. **批量数据传输**：使用GetPrimitiveArrayCritical处理大数组
3. **缓存ID**：jfieldID/jmethodID应该缓存
4. **及时释放**：Local ref在函数返回自动释放，但长循环中应手动释放
5. **异常检查**：每个JNI调用后检查异常
6. **避免死锁**：Critical Section内不做阻塞操作
