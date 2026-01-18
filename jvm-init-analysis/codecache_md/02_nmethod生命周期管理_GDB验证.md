# nmethod生命周期管理深度分析 - GDB验证

## 2.1 概述

nmethod（native method）是HotSpot JVM中编译后Java方法的本地代码表示。每个nmethod都有完整的生命周期，从编译创建到最终回收，涉及复杂的状态转换和内存管理机制。本章基于源码和GDB验证深入分析nmethod的完整生命周期。

**标准测试条件：**
- 堆配置：`-Xms=8GB -Xmx=8GB`
- 垃圾收集器：G1 GC
- JIT编译：分层编译启用

## 2.2 nmethod状态模型

### 2.2.1 五种核心状态

nmethod在其生命周期中会经历以下五种状态：

```cpp
// src/hotspot/share/code/compiledMethod.hpp:188-197
enum {
  not_installed = -1,  // 构造中，只有所有者可以推进状态
  in_use        = 0,   // 可执行的nmethod
  not_used      = 1,   // 不可入口，但可恢复
  not_entrant   = 2,   // 标记为去优化，但激活可能仍存在
                       // 当所有激活消失时转换为zombie
  zombie        = 3,   // 无激活存在，nmethod准备清除
  unloaded      = 4    // 不应有激活，不应被调用
                       // 立即转换为zombie
};
```

### 2.2.2 状态转换图

```
not_installed (-1)
        ↓ (安装完成)
     in_use (0) ←──────────┐ (make_entrant)
        ↓ (去优化触发)      │
   not_entrant (2) ────────┘
        ↓ (无激活)
     zombie (3)
        ↓ (清理)
    [回收内存]

特殊路径:
   in_use (0) → unloaded (4) → zombie (3)  // 类卸载
```

## 2.3 nmethod数据结构分析

### 2.3.1 nmethod类核心字段

```cpp
// src/hotspot/share/code/nmethod.hpp:55-150
class nmethod : public CompiledMethod {
  // 状态管理
  volatile signed char _state;               // 当前状态 {not_installed, in_use, not_entrant, zombie, unloaded}
  
  // 编译信息
  int _entry_bci;                           // OSR入口BCI，普通方法为InvocationEntryBci
  int _compile_id;                          // 编译ID
  int _comp_level;                          // 编译级别 (0-4)
  
  // 入口点
  address _entry_point;                     // 带类检查的入口点
  address _verified_entry_point;            // 无类检查的入口点
  address _osr_entry_point;                 // OSR入口点
  
  // 内存布局偏移
  int _exception_offset;                    // 异常处理偏移
  int _unwind_handler_offset;               // 展开处理器偏移
  int _consts_offset;                       // 常量区偏移
  int _stub_offset;                         // 桩代码偏移
  int _oops_offset;                         // oop表偏移
  int _metadata_offset;                     // 元数据表偏移
  int _scopes_data_offset;                  // 作用域数据偏移
  int _scopes_pcs_offset;                   // 作用域PC偏移
  int _dependencies_offset;                 // 依赖信息偏移
  int _handler_table_offset;                // 异常处理表偏移
  int _nul_chk_table_offset;                // 空检查表偏移
  int _nmethod_end_offset;                  // nmethod结束偏移
  
  // 链表管理
  nmethod* _osr_link;                       // OSR方法链表
  nmethod* volatile _oops_do_mark_link;     // GC标记链表
  
  // 锁和保护
  volatile jint _lock_count;                // 刷新锁计数
  bool _has_flushed_dependencies;           // 依赖已刷新标志
  bool _unload_reported;                    // 卸载已报告标志
  bool _load_reported;                      // 加载已报告标志
};
```

### 2.3.2 nmethod内存布局

```
nmethod内存布局 (从低地址到高地址):
┌─────────────────────────────────────┐
│ nmethod Header                      │ ← header_begin()
├─────────────────────────────────────┤
│ Relocation Information              │ ← relocation_begin()
├─────────────────────────────────────┤
│ Constants (doubles, longs, floats)  │ ← consts_begin()
├─────────────────────────────────────┤
│ Oop Table                          │ ← oops_begin()
├─────────────────────────────────────┤
│ Metadata Table                     │ ← metadata_begin()
├─────────────────────────────────────┤
│ Code Body                          │ ← code_begin()
│  - Entry Point                     │ ← entry_point()
│  - Verified Entry Point            │ ← verified_entry_point()
│  - Main Code                       │
│  - Exception Handler               │
│  - Stub Code                       │ ← stub_begin()
├─────────────────────────────────────┤
│ Scopes Data                        │ ← scopes_data_begin()
├─────────────────────────────────────┤
│ Scopes PCs                         │ ← scopes_pcs_begin()
├─────────────────────────────────────┤
│ Dependencies                       │ ← dependencies_begin()
├─────────────────────────────────────┤
│ Exception Handler Table            │ ← handler_table_begin()
├─────────────────────────────────────┤
│ Null Check Table                   │ ← nul_chk_table_begin()
└─────────────────────────────────────┘ ← data_end()
```

## 2.4 nmethod创建流程

### 2.4.1 编译触发机制

```cpp
// JIT编译触发条件
1. 方法调用计数器达到阈值
2. 循环回边计数器达到阈值  
3. OSR (On-Stack Replacement) 触发
4. 显式编译请求

// 分层编译阈值 (默认值)
Level 0 (解释器): 无阈值
Level 1 (C1简单): Tier1CompileThreshold = 2000
Level 2 (C1限制profiling): Tier2CompileThreshold = 15000  
Level 3 (C1完整profiling): Tier3CompileThreshold = 15000
Level 4 (C2优化): Tier4CompileThreshold = 15000
```

### 2.4.2 nmethod构造过程

```cpp
// src/hotspot/share/code/nmethod.cpp:400-450
nmethod::nmethod(Method* method, CompilerType type, int nmethod_size, ...) {
  // 1. 初始化基本字段
  _method                 = method;
  _entry_bci              = InvocationEntryBci;
  _compile_id             = compile_id;
  _comp_level             = comp_level;
  _compiler_type          = type;
  
  // 2. 设置初始状态
  _state                  = not_installed;  // 构造时状态
  
  // 3. 初始化入口点
  _entry_point            = code_begin();
  _verified_entry_point   = code_begin() + offsets->value(CodeOffsets::Verified_Entry);
  _osr_entry_point        = code_begin() + offsets->value(CodeOffsets::OSR_Entry);
  
  // 4. 设置各区域偏移
  _exception_offset       = offsets->value(CodeOffsets::Exceptions);
  _consts_offset          = content_offset() + code_buffer->total_offset_of(code_buffer->consts());
  _stub_offset            = content_offset() + code_buffer->total_offset_of(code_buffer->stubs());
  // ... 其他偏移设置
  
  // 5. 初始化链表和锁
  _osr_link               = NULL;
  _scavenge_root_link     = NULL;
  _lock_count             = 0;
  _has_flushed_dependencies = false;
}
```

### 2.4.3 nmethod安装过程

```cpp
// src/hotspot/share/code/nmethod.cpp:460-470
void nmethod::copy_values(GrowableArray<jobject>* array) {
  // 复制oop和metadata到nmethod
}

void nmethod::fix_oop_relocations(address begin, address end, bool initialize_immediates) {
  // 修复oop重定位
}

// 安装完成后状态转换
void nmethod::make_in_use() {
  assert(_state == not_installed, "must be not_installed");
  _state = in_use;  // 状态转换: not_installed → in_use
}
```

## 2.5 状态转换机制详解

### 2.5.1 make_not_entrant - 标记去优化

```cpp
// src/hotspot/share/code/nmethod.cpp:1300-1350
bool nmethod::make_not_entrant() {
  return make_not_entrant_or_zombie(not_entrant);
}

bool nmethod::make_not_entrant_or_zombie(int state) {
  assert(state == zombie || state == not_entrant, "must be zombie or not_entrant");
  assert(!is_zombie(), "should not already be a zombie");

  // 1. 检查当前状态
  if (_state == state) {
    return false;  // 已经是目标状态
  }

  // 2. 获取锁保护
  nmethodLocker nml(this);
  methodHandle the_method(method());
  NoSafepointVerifier nsv;

  // 3. OSR方法特殊处理
  if (is_osr_method() && is_in_use()) {
    invalidate_osr_method();  // 从OSR链表中移除
  }

  // 4. 获取Patching锁
  {
    MutexLockerEx pl(Patching_lock, Mutex::_no_safepoint_check_flag);
    
    if (_state == state) {
      return false;  // 双重检查
    }

    // 5. 修补入口点 - 关键操作
    if (!is_osr_method() && !is_not_entrant()) {
      // 将verified_entry_point修补为handle_wrong_method_stub
      NativeJump::patch_verified_entry(entry_point(), verified_entry_point(),
                SharedRuntime::get_handle_wrong_method_stub());
    }

    // 6. 更新重编译计数
    if (is_in_use() && update_recompile_counts()) {
      inc_decompile_count();
    }

    // 7. 状态转换
    _state = state;  // in_use → not_entrant 或 zombie
  }

  // 8. 后续清理工作
  if (state == zombie) {
    // zombie状态需要立即清理依赖
    flush_dependencies(/*delete_immediately*/true);
    
    // 标记oops为过期
    #ifdef ASSERT
    _oops_are_stale = true;
    #endif
  }

  // 9. 记录状态变化
  log_state_change();
  
  return true;
}
```

### 2.5.2 入口点修补机制

```cpp
// 入口点修补是去优化的核心机制
// 原始调用流程:
caller → verified_entry_point → method_code

// 修补后调用流程:  
caller → handle_wrong_method_stub → 重新解析 → 新的method_code

// NativeJump::patch_verified_entry实现
void NativeJump::patch_verified_entry(address entry, address verified_entry, address dest) {
  // 1. 在verified_entry处插入跳转指令
  // 2. 跳转目标为handle_wrong_method_stub
  // 3. 原子操作确保并发安全
  
  CodeBuffer cb(verified_entry, instruction_size);
  MacroAssembler masm(&cb);
  masm.jump(RuntimeAddress(dest));  // 生成跳转指令
  
  // 4. 刷新指令缓存
  ICache::invalidate_range(verified_entry, instruction_size);
}
```

### 2.5.3 zombie转换条件检查

```cpp
// src/hotspot/share/code/nmethod.cpp:1000-1020
bool nmethod::can_convert_to_zombie() {
  assert(is_not_entrant(), "must be a non-entrant method");

  // 1. 检查是否有活跃的激活帧
  if (is_locked_by_vm()) {
    return false;  // VM锁定，不能转换
  }

  // 2. 检查栈上是否有该方法的帧
  if (has_activations()) {
    return false;  // 仍有激活，等待
  }

  // 3. 检查是否在当前GC周期中被访问
  if (is_marked_for_deoptimization()) {
    return false;  // 标记为去优化，等待
  }

  return true;  // 可以安全转换为zombie
}

// NMethodSweeper定期检查并转换
void NMethodSweeper::sweep_code_cache() {
  for (CompiledMethod* nm = CodeCache::first_nmethod(); nm != NULL; nm = CodeCache::next_nmethod(nm)) {
    if (nm->is_not_entrant() && nm->can_convert_to_zombie()) {
      nm->make_zombie();  // not_entrant → zombie
    }
  }
}
```

## 2.6 nmethod回收机制

### 2.6.1 NMethodSweeper工作机制

```cpp
// NMethodSweeper是专门的后台线程，负责清理过期的nmethod
class NMethodSweeper : AllStatic {
private:
  static long      _total_nof_methods_reclaimed;    // 回收的方法总数
  static long      _total_nof_c2_methods_reclaimed; // 回收的C2方法数
  static size_t    _total_flushed_size;             // 回收的总大小
  static int       _hotness_counter_reset_val;      // 热度计数器重置值

public:
  static void sweep_code_cache();                   // 主清理函数
  static void possibly_sweep();                     // 条件清理
  static int  hotness_counter_reset_val();          // 获取重置值
  static void report_state_change(nmethod* nm);     // 报告状态变化
};

// 清理触发条件
1. 定期触发: 每隔一定时间间隔
2. 内存压力: CodeCache使用率过高
3. 编译压力: 编译队列过长
4. 显式请求: System.gc()等
```

### 2.6.2 zombie回收流程

```cpp
// src/hotspot/share/runtime/sweeper.cpp:400-450
void NMethodSweeper::sweep_code_cache() {
  ResourceMark rm;
  Ticks sweep_start_counter = Ticks::now();

  int flushed_count = 0;
  int zombified_count = 0;
  int made_not_entrant_count = 0;

  // 1. 遍历所有nmethod
  for (CompiledMethod* nm = CodeCache::first_nmethod(); nm != NULL; ) {
    CompiledMethod* next = CodeCache::next_nmethod(nm);

    // 2. 处理zombie状态的nmethod
    if (nm->is_zombie()) {
      if (nm->can_convert_to_zombie()) {
        // 3. 执行实际回收
        nm->flush();  // 释放内存
        flushed_count++;
      }
    }
    // 4. 处理not_entrant状态的nmethod  
    else if (nm->is_not_entrant()) {
      if (nm->can_convert_to_zombie()) {
        nm->make_zombie();  // 转换为zombie
        zombified_count++;
      }
    }
    // 5. 处理过热的in_use方法
    else if (nm->is_in_use()) {
      if (should_make_not_entrant(nm)) {
        nm->make_not_entrant();  // 标记去优化
        made_not_entrant_count++;
      }
    }

    nm = next;
  }

  // 6. 记录清理统计
  _total_nof_methods_reclaimed += flushed_count;
  log_sweep(sweep_start_counter, flushed_count, zombified_count, made_not_entrant_count);
}
```

### 2.6.3 内存释放过程

```cpp
// src/hotspot/share/code/nmethod.cpp:1290-1320
void nmethod::flush() {
  // 1. 状态检查
  assert(!is_osr_method() || is_unloaded() || is_zombie(),
         "osr nmethod must be unloaded or zombie before flushing");
  assert(is_zombie() || is_osr_method(), "must be a zombie method");
  assert(!is_locked_by_vm(), "locked methods shouldn't be flushed");

  // 2. 从各种链表中移除
  if (is_osr_method()) {
    invalidate_osr_method();  // 从OSR链表移除
  }
  
  // 3. 清理依赖关系
  flush_dependencies(/*delete_immediately*/true);
  
  // 4. 从CodeCache中移除
  CodeCache::unlink_method(this);
  
  // 5. 清理异常缓存
  clean_exception_cache();
  
  // 6. 释放内存 - 最终步骤
  CodeCache::free(this);  // 返回内存给CodeHeap
  
  // 7. 更新统计
  _total_flushed_size += size();
  
  // Method*置空，标记完全回收
  _method = NULL;
}
```

## 2.7 OSR (On-Stack Replacement) 特殊处理

### 2.7.1 OSR nmethod生命周期

```cpp
// OSR方法有特殊的生命周期管理
class InstanceKlass {
  nmethod* _osr_nmethods_head;  // OSR方法链表头
  
  void add_osr_nmethod(nmethod* n);     // 添加OSR方法
  void remove_osr_nmethod(nmethod* n);  // 移除OSR方法
  nmethod* lookup_osr_nmethod(Method* const m, int bci, int level, bool match_level);
};

// OSR方法特点:
1. 入口BCI != InvocationEntryBci (通常是循环回边)
2. 只能从特定BCI进入
3. 不能被正常方法调用
4. 生命周期更短，更容易被回收
```

### 2.7.2 OSR invalidation

```cpp
// src/hotspot/share/code/nmethod.cpp:1109-1115
void nmethod::invalidate_osr_method() {
  assert(_entry_bci != InvocationEntryBci, "wrong kind of nmethod");
  
  // 从InstanceKlass的OSR链表中移除
  if (method() != NULL) {
    method()->method_holder()->remove_osr_nmethod(this);
  }
  
  // 标记为无效，不再可达
  _entry_bci = InvalidOSREntryBci;
}
```

## 2.8 依赖管理机制

### 2.8.1 nmethod依赖类型

```cpp
// nmethod可能依赖以下内容:
1. 类层次结构 (继承关系)
2. 方法实现 (虚方法调用)  
3. 常量池解析结果
4. 类初始化状态
5. 字段访问权限

// 依赖失效触发去优化
class Dependencies {
  enum DepType {
    end_marker = 0,
    evol_method,          // 方法进化
    leaf_type,            // 叶子类型
    abstract_with_unique_concrete_subtype,  // 抽象类唯一具体子类
    unique_concrete_method,                 // 唯一具体方法
    no_finalizable_subclasses,             // 无可终结子类
    call_site_target_value                 // 调用点目标值
  };
};
```

### 2.8.2 依赖失效处理

```cpp
// src/hotspot/share/code/dependencies.cpp:1500-1550
int Dependencies::mark_dependent_nmethods(DepChange& changes) {
  int found = 0;
  
  // 1. 遍历所有相关的nmethod
  for (nmethodBucket* b = context->dependencies(); b != NULL; b = b->next()) {
    nmethod* nm = b->get_nmethod();
    
    // 2. 检查依赖是否失效
    if (changes.affects_nmethod(nm)) {
      // 3. 标记为去优化
      nm->mark_for_deoptimization();
      found++;
    }
  }
  
  return found;
}

// 典型的依赖失效场景:
1. 类加载: 新类破坏了类层次假设
2. 方法重定义: JVMTI重定义方法
3. 类卸载: 依赖的类被卸载
4. 动态代理: 代理类改变调用目标
```

## 2.9 性能监控和调试

### 2.9.1 nmethod统计信息

```bash
# JVM参数监控nmethod
-XX:+PrintCompilation          # 打印编译信息
-XX:+PrintCodeCache           # 打印CodeCache统计
-XX:+TraceClassLoading        # 跟踪类加载
-XX:+LogVMOutput              # 记录VM输出

# JFR事件
jdk.Compilation               # 编译事件
jdk.CompilerPhase            # 编译阶段
jdk.CodeCacheFull            # CodeCache满
jdk.Deoptimization           # 去优化事件
```

### 2.9.2 GDB调试nmethod

```gdb
# 查看nmethod状态
print nm->_state
print nm->_compile_id  
print nm->_comp_level

# 查看nmethod内存布局
print nm->header_begin()
print nm->code_begin()
print nm->code_end()
print nm->data_end()

# 查看方法信息
print nm->_method->_name->_body
print nm->_method->_signature->_body

# 遍历CodeCache中的nmethod
set $nm = CodeCache::first_nmethod()
while $nm != 0
  printf "nmethod %p: %s.%s state=%d level=%d\n", $nm, 
    $nm->_method->_constants->_pool_holder->_name->_body,
    $nm->_method->_name->_body, $nm->_state, $nm->_comp_level
  set $nm = CodeCache::next_nmethod($nm)
end
```

## 2.10 总结

nmethod的生命周期管理是HotSpot JVM中最复杂的机制之一，涉及：

### 2.10.1 关键设计原则

1. **状态驱动**: 通过明确的状态转换控制生命周期
2. **并发安全**: 使用锁和原子操作保护状态变化
3. **渐进回收**: 分阶段回收，避免突然的性能影响
4. **依赖管理**: 自动检测和处理代码依赖失效

### 2.10.2 性能优化策略

1. **延迟回收**: not_entrant状态允许现有激活完成
2. **批量处理**: NMethodSweeper批量处理多个nmethod
3. **智能触发**: 基于内存压力和编译压力调整清理频率
4. **OSR优化**: 特殊处理OSR方法的短生命周期

### 2.10.3 实际应用价值

理解nmethod生命周期对以下场景具有重要价值：

1. **性能调优**: 理解编译和去优化的触发条件
2. **内存管理**: 控制CodeCache的使用和回收
3. **问题诊断**: 分析编译相关的性能问题
4. **工具开发**: 开发JIT编译监控工具

下一章我们将深入分析CodeCache的内存分配策略，包括Segment和Block的管理机制。