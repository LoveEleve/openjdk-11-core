# 第6章：JIT编译系统 - 从字节码到机器码

> **本章导读**：解释器执行虽然灵活，但性能有限。JIT（Just-In-Time）编译器将热点代码编译为高效的机器码。本章基于HotSpot源码，深入讲解C1/C2编译器、分层编译策略、核心优化技术，以及与G1 GC的协作机制。

---

## 6.1 编译系统概述

### 6.1.1 为什么需要JIT编译？

| 执行方式 | 优点 | 缺点 |
|---------|------|------|
| **解释执行** | 启动快、内存省 | 执行慢 |
| **AOT编译** | 执行快 | 无运行时优化 |
| **JIT编译** | 执行快 + 运行时优化 | 编译开销 |

HotSpot采用**混合模式**：先解释执行，识别热点后JIT编译。

### 6.1.2 HotSpot的两个编译器

```cpp
// src/hotspot/share/compiler/compileBroker.hpp
class CompileBroker: AllStatic {
private:
  // The installed compiler(s)
  static AbstractCompiler* _compilers[2];  // [0]=C1, [1]=C2
  
  // The maximum numbers of compiler threads
  static int _c1_count, _c2_count;
  
  // Compile queues
  static CompileQueue* _c2_compile_queue;
  static CompileQueue* _c1_compile_queue;
};
```

| 编译器 | 别名 | 优化级别 | 编译速度 | 代码质量 |
|-------|------|---------|---------|---------|
| **C1** | Client Compiler | 轻量优化 | 快 | 中等 |
| **C2** | Server Compiler | 激进优化 | 慢 | 高 |

**关键源码目录**：
```
src/hotspot/share/
├── compiler/              # 编译框架
│   ├── compileBroker.hpp  # 编译代理（调度）
│   ├── compileTask.hpp    # 编译任务
│   └── abstractCompiler.hpp # 编译器接口
├── c1/                    # C1编译器
│   ├── c1_Compilation.hpp # C1编译流程
│   ├── c1_IR.hpp          # 高级IR
│   └── c1_LIR.hpp         # 低级IR
└── opto/                  # C2编译器（Sea-of-Nodes）
    ├── compile.hpp        # C2编译入口
    ├── node.hpp           # IR节点
    └── escape.hpp         # 逃逸分析
```

---

## 6.2 分层编译（Tiered Compilation）

### 6.2.1 编译层级

JDK 11默认开启分层编译，定义了5个层级：

```cpp
// src/hotspot/share/compiler/compilerDefinitions.hpp
enum CompLevel {
  CompLevel_none              = 0,  // 解释执行
  CompLevel_simple            = 1,  // C1编译，无Profiling
  CompLevel_limited_profile   = 2,  // C1编译，有限Profiling
  CompLevel_full_profile      = 3,  // C1编译，完整Profiling
  CompLevel_full_optimization = 4   // C2编译，完整优化
};
```

### 6.2.2 层级转换路径

```
                    ┌─────────────────────────────────────┐
                    │                                     ▼
Level 0 ──────────► Level 3 ──────────────────────────► Level 4
(解释器)            (C1+Profiling)                       (C2)
    │                   │
    │                   ▼
    │              Level 2
    │           (C1+有限Profiling)
    │                   │
    └──────────────────►│
                        ▼
                    Level 1
                 (C1，无Profiling)
```

**典型路径**：
- **常规路径**：Level 0 → Level 3 → Level 4（解释 → C1+Profile → C2）
- **快速路径**：Level 0 → Level 4（直接C2，用于简单方法）
- **回退路径**：Level 4 → Level 0（去优化后回到解释）

### 6.2.3 编译策略实现

```cpp
// src/hotspot/share/runtime/compilationPolicy.hpp
class CompilationPolicy : public CHeapObj<mtCompiler> {
  static CompilationPolicy* _policy;
  
public:
  // 核心事件处理：决定是否编译、编译到哪个层级
  virtual nmethod* event(const methodHandle& method, 
                         const methodHandle& inlinee,
                         int branch_bci, int bci, 
                         CompLevel comp_level, 
                         CompiledMethod* nm, 
                         JavaThread* thread) = 0;
  
  // 选择编译任务
  virtual CompileTask* select_task(CompileQueue* compile_queue) = 0;
  
  // 判断方法是否成熟（Profile数据足够）
  virtual bool is_mature(Method* method) = 0;
};
```

**触发编译的条件**：
```cpp
// 方法调用计数器 + 回边计数器
bool should_compile(Method* m) {
  int count = m->invocation_count() + m->backedge_count();
  return count >= CompileThreshold;  // 默认10000
}
```

---

## 6.3 编译代理（CompileBroker）

### 6.3.1 编译队列

```cpp
// src/hotspot/share/compiler/compileBroker.hpp
class CompileQueue : public CHeapObj<mtCompiler> {
private:
  const char* _name;
  CompileTask* _first;
  CompileTask* _last;
  int _size;
  
public:
  void add(CompileTask* task);
  void remove(CompileTask* task);
  CompileTask* get();  // 获取下一个任务
  
  bool is_empty() const { return _first == NULL; }
  int  size() const     { return _size; }
};
```

**两个队列**：
- `_c1_compile_queue`：C1编译任务
- `_c2_compile_queue`：C2编译任务

### 6.3.2 编译任务

```cpp
// src/hotspot/share/compiler/compileTask.hpp
class CompileTask : public CHeapObj<mtCompiler> {
private:
  int          _compile_id;      // 编译ID
  Method*      _method;          // 待编译方法
  int          _osr_bci;         // OSR入口（-1表示普通编译）
  int          _comp_level;      // 目标编译层级
  CompileTask* _next;            // 队列链表
  
  volatile bool _is_complete;    // 编译完成标志
  volatile bool _is_success;     // 编译成功标志
  
public:
  enum CompileReason {
    Reason_InvocationCount,      // 调用计数达到阈值
    Reason_BackedgeCount,        // 回边计数达到阈值（OSR）
    Reason_Tiered,               // 分层编译升级
    Reason_Whitebox,             // WhiteBox API触发
    Reason_MustBeCompiled,       // -Xcomp强制编译
    Reason_Bootstrap             // 启动时编译
  };
};
```

### 6.3.3 编译线程循环

```cpp
// src/hotspot/share/compiler/compileBroker.cpp
void CompileBroker::compiler_thread_loop() {
  CompilerThread* thread = CompilerThread::current();
  CompileQueue* queue = thread->queue();
  
  while (true) {
    // 1. 从队列获取任务
    CompileTask* task = queue->get();
    if (task == NULL) {
      // 队列空，等待
      queue->wait();
      continue;
    }
    
    // 2. 执行编译
    invoke_compiler_on_method(task);
    
    // 3. 处理编译结果
    if (task->is_success()) {
      // 安装nmethod到CodeCache
    }
  }
}
```

### 6.3.4 提交编译请求

```cpp
// src/hotspot/share/compiler/compileBroker.cpp
nmethod* CompileBroker::compile_method(const methodHandle& method,
                                       int osr_bci,
                                       int comp_level,
                                       const methodHandle& hot_method,
                                       int hot_count,
                                       CompileTask::CompileReason compile_reason,
                                       Thread* thread) {
  // 1. 检查是否已在编译
  if (compilation_is_in_queue(method)) {
    return NULL;
  }
  
  // 2. 创建编译任务
  CompileTask* task = create_compile_task(queue, compile_id, 
                                          method, osr_bci, comp_level, ...);
  
  // 3. 加入队列
  CompileQueue* queue = compile_queue(comp_level);
  queue->add(task);
  
  // 4. 阻塞等待（如果需要）
  if (blocking) {
    wait_for_completion(task);
  }
  
  return task->code();
}
```

---

## 6.4 C1编译器

### 6.4.1 C1编译流程

```cpp
// src/hotspot/share/c1/c1_Compilation.hpp
class Compilation: public StackObj {
private:
  ciMethod*          _method;        // 待编译方法
  IR*                _hir;           // 高级IR（HIR）
  FrameMap*          _frame_map;     // 栈帧映射
  C1_MacroAssembler* _masm;          // 汇编器
  LinearScan*        _allocator;     // 寄存器分配器
  
  // 编译阶段
  void build_hir();   // 构建HIR
  void emit_lir();    // 生成LIR
  int  emit_code_body();  // 生成机器码
};
```

**C1编译流程**：
```
字节码 → HIR(SSA) → LIR → 寄存器分配 → 机器码
```

### 6.4.2 HIR（High-level IR）

```cpp
// src/hotspot/share/c1/c1_Instruction.hpp
class Instruction: public CompilationResourceObj {
protected:
  int          _id;          // 指令ID
  int          _bci;         // 对应的字节码位置
  ValueType*   _type;        // 值类型
  Instruction* _next;        // 下一条指令
  ValueStack*  _state_before; // 执行前的值栈状态
  
public:
  // 子类：Constant, Local, LoadField, StoreField, Invoke, ...
};
```

### 6.4.3 LIR（Low-level IR）

```cpp
// src/hotspot/share/c1/c1_LIR.hpp
class LIR_Op: public CompilationResourceObj {
protected:
  LIR_Opr     _result;       // 结果操作数
  LIR_Code    _code;         // 操作码
  CodeEmitInfo* _info;       // 调试信息
  
public:
  enum LIR_Code {
    lir_none,
    lir_move,
    lir_add, lir_sub, lir_mul, lir_div,
    lir_branch, lir_cond_branch,
    lir_call, lir_return,
    // ...
  };
};
```

### 6.4.4 C1的优化

C1的优化相对保守，主要包括：

1. **方法内联**（有限制）
2. **常量折叠**
3. **死代码消除**
4. **空值检查消除**
5. **范围检查消除**

```cpp
// src/hotspot/share/c1/c1_Optimizer.cpp
void Optimizer::eliminate_null_checks() {
  // 分析控制流，消除冗余的空指针检查
  for (int i = 0; i < _hir->number_of_blocks(); i++) {
    BlockBegin* block = _hir->block_at(i);
    for (Instruction* instr = block; instr != NULL; instr = instr->next()) {
      if (instr->as_NullCheck() != NULL) {
        NullCheck* nc = instr->as_NullCheck();
        if (is_proven_non_null(nc->obj())) {
          // 可以证明非空，消除检查
          nc->unpin(Instruction::PinNullCheck);
        }
      }
    }
  }
}
```

---

## 6.5 C2编译器（Opto）

### 6.5.1 C2编译入口

```cpp
// src/hotspot/share/opto/compile.hpp
class Compile : public Phase {
private:
  ciEnv*            _env;           // 编译环境
  ciMethod*         _method;        // 待编译方法
  int               _entry_bci;     // 入口BCI（OSR时使用）
  
  // IR图
  RootNode*         _root;          // 根节点
  StartNode*        _start;         // 起始节点
  
  // 优化阶段
  PhaseGVN*         _initial_gvn;   // 初始GVN
  ConnectionGraph*  _congraph;      // 逃逸分析图
  
public:
  Compile(ciEnv* ci_env, C2Compiler* compiler, ciMethod* target, ...);
  
  // 编译主流程
  void Compile::Optimize();
  void Code_Gen();
};
```

### 6.5.2 Sea-of-Nodes IR

C2使用**Sea-of-Nodes**表示法，节点之间通过边连接：

```cpp
// src/hotspot/share/opto/node.hpp
class Node {
protected:
  uint _idx;              // 节点索引
  uint _cnt;              // 输入边数量
  Node** _in;             // 输入边数组
  Node_List _out;         // 输出边列表
  
public:
  // 节点类型
  virtual uint hash() const;
  virtual uint cmp(const Node &n) const;
  
  // 输入/输出
  Node* in(uint i) const { return _in[i]; }
  void set_req(uint i, Node* n);
  void add_prec(Node* n);  // 添加控制依赖
};
```

**节点类型**：
```cpp
// 控制流节点
class RegionNode : public Node { ... };
class IfNode : public MultiBranchNode { ... };
class LoopNode : public RegionNode { ... };

// 数据节点
class AddNode : public Node { ... };
class MulNode : public Node { ... };
class LoadNode : public MemNode { ... };
class StoreNode : public MemNode { ... };

// 内存节点
class MemNode : public Node { ... };
class MergeMemNode : public Node { ... };
```

### 6.5.3 C2编译阶段

```cpp
// src/hotspot/share/opto/compile.cpp
void Compile::Optimize() {
  // Phase 1: 解析字节码，构建IR图
  Parse parse(this, method(), ...);
  
  // Phase 2: 迭代GVN（Global Value Numbering）
  PhaseIterGVN igvn(initial_gvn());
  igvn.optimize();
  
  // Phase 3: 逃逸分析
  if (DoEscapeAnalysis) {
    ConnectionGraph* congraph = new ConnectionGraph(this, &igvn);
    if (congraph->compute_escape()) {
      // 标量替换、栈上分配
      congraph->optimize_ideal_graph(&igvn);
    }
  }
  
  // Phase 4: 循环优化
  PhaseIdealLoop ideal_loop(igvn, LoopOptsDefault);
  
  // Phase 5: 条件常量传播（CCP）
  PhaseCCP ccp(&igvn);
  ccp.do_transform();
  
  // Phase 6: 最终GVN
  igvn.optimize();
  
  // Phase 7: 宏扩展
  PhaseMacroExpand mex(igvn);
  mex.expand_macro_nodes();
}
```

---

## 6.6 核心优化技术

### 6.6.1 方法内联（Inlining）

```cpp
// src/hotspot/share/opto/callGenerator.cpp
bool InlineTree::should_inline(ciMethod* callee, ...) {
  // 1. 检查方法大小
  if (callee->code_size() > MaxInlineSize) {
    return false;  // 太大，不内联
  }
  
  // 2. 检查内联深度
  if (inline_depth() > MaxInlineLevel) {
    return false;  // 太深，不内联
  }
  
  // 3. 检查热度
  if (callee->interpreter_invocation_count() < MinInliningThreshold) {
    return false;  // 不够热，不内联
  }
  
  // 4. 特殊方法强制内联
  if (callee->should_inline()) {
    return true;  // @ForceInline
  }
  
  return true;
}
```

**内联参数**：
```bash
-XX:MaxInlineSize=35        # 内联方法的最大字节码大小
-XX:MaxInlineLevel=9        # 最大内联深度
-XX:InlineSmallCode=1000    # 小方法强制内联阈值
```

### 6.6.2 逃逸分析（Escape Analysis）

```cpp
// src/hotspot/share/opto/escape.hpp
// 基于论文 [Choi99] 的实现

// 逃逸状态
enum EscapeState {
  UnknownEscape = 0,
  NoEscape      = 1,  // 不逃逸（可栈上分配）
  ArgEscape     = 2,  // 作为参数逃逸
  GlobalEscape  = 3   // 全局逃逸
};

class ConnectionGraph {
private:
  // 连接图节点类型
  // - JavaObject (JO): new表达式
  // - LocalVar (LV): 局部变量
  // - Field (OF): 对象字段
  
  // 边类型
  // - PointsTo (-P>): LV/OF → JO
  // - Deferred (-D>): LV/OF → LV/OF
  // - Field (-F>): JO → OF
  
public:
  bool compute_escape();
  void optimize_ideal_graph(PhaseIterGVN* igvn);
};
```

**逃逸分析的优化**：

1. **栈上分配**：不逃逸的对象直接在栈上分配
2. **标量替换**：将对象拆解为独立的标量变量
3. **锁消除**：消除不逃逸对象的锁操作

```java
// 示例：逃逸分析优化
public int sum() {
    Point p = new Point(1, 2);  // 不逃逸
    return p.x + p.y;
}

// 优化后（标量替换）
public int sum() {
    int p_x = 1;  // 直接使用标量
    int p_y = 2;
    return p_x + p_y;
}
```

### 6.6.3 锁优化

```cpp
// src/hotspot/share/opto/macro.cpp
void PhaseMacroExpand::eliminate_locking_node(AbstractLockNode* alock) {
  // 1. 锁消除（Lock Elision）
  // 如果对象不逃逸，消除锁操作
  if (alock->is_non_esc_obj()) {
    // 移除锁节点
    _igvn.replace_node(alock, alock->obj_node());
    return;
  }
  
  // 2. 锁粗化（Lock Coarsening）
  // 合并相邻的锁操作
  if (can_coarsen_lock(alock)) {
    coarsen_lock(alock);
  }
}
```

**锁优化示例**：
```java
// 原始代码
for (int i = 0; i < 100; i++) {
    synchronized (lock) {
        count++;
    }
}

// 锁粗化后
synchronized (lock) {
    for (int i = 0; i < 100; i++) {
        count++;
    }
}
```

### 6.6.4 循环优化

```cpp
// src/hotspot/share/opto/loopnode.cpp
class PhaseIdealLoop : public PhaseTransform {
public:
  // 循环展开
  void do_unroll(IdealLoopTree* loop, Node_List& old_new, bool adjust_min_trip);
  
  // 循环向量化
  void do_vectorization(IdealLoopTree* loop);
  
  // 循环剥离
  void do_peeling(IdealLoopTree* loop, Node_List& old_new);
  
  // 范围检查消除
  void do_range_check(IdealLoopTree* loop);
};
```

---

## 6.7 代码缓存（CodeCache）

### 6.7.1 CodeCache结构

```cpp
// src/hotspot/share/code/codeCache.hpp
class CodeCache : AllStatic {
private:
  // 代码堆（分段存储）
  static GrowableArray<CodeHeap*>* _heaps;
  static GrowableArray<CodeHeap*>* _compiled_heaps;
  static GrowableArray<CodeHeap*>* _nmethod_heaps;
  
  static address _low_bound;   // 地址下界
  static address _high_bound;  // 地址上界
  
public:
  static CodeBlob* allocate(int size, int code_blob_type);
  static void free(CodeBlob* cb);
  static void flush_dependents_on(InstanceKlass* dependee);
};
```

**代码堆分段**（开启分层编译时）：
```cpp
// src/hotspot/share/code/codeCache.hpp
// 三种代码堆类型：
// - Non-nmethods: 运行时Stub、适配器
// - Profiled nmethods: Level 2/3编译的代码
// - Non-Profiled nmethods: Level 1/4编译的代码
```

### 6.7.2 nmethod结构

```cpp
// src/hotspot/share/code/nmethod.hpp
class nmethod : public CompiledMethod {
private:
  Method*       _method;           // 对应的Java方法
  int           _entry_bci;        // OSR入口
  
  // 代码区域
  address       _entry_point;      // 普通入口
  address       _verified_entry_point;  // 验证后入口
  address       _osr_entry_point;  // OSR入口
  
  // 元数据
  PcDescContainer _pc_descs;       // PC描述表
  ScopeDescRecorder* _scopes_data; // 作用域数据
  
  // 依赖关系
  Dependencies* _dependencies;     // 编译假设
  
public:
  // 安装到Method
  void install_on_method(Method* method);
  
  // 使nmethod失效
  void make_not_entrant();
  void make_zombie();
};
```

### 6.7.3 代码缓存大小

```bash
# 8GB堆内存时的默认配置
-XX:ReservedCodeCacheSize=240m    # 总代码缓存大小
-XX:InitialCodeCacheSize=2496k    # 初始大小
-XX:CodeCacheExpansionSize=64k    # 扩展步长

# 分段大小（分层编译时）
-XX:NonNMethodCodeHeapSize=5m     # 非nmethod堆
-XX:ProfiledCodeHeapSize=117m     # Profiled代码堆
-XX:NonProfiledCodeHeapSize=117m  # Non-Profiled代码堆
```

---

## 6.8 OSR编译（On-Stack Replacement）

### 6.8.1 OSR触发

```cpp
// src/hotspot/share/runtime/compilationPolicy.cpp
nmethod* NonTieredCompPolicy::event(const methodHandle& method, ...) {
  // 回边计数器溢出时触发OSR
  if (bci != InvocationEntryBci) {
    // 这是一个回边事件
    nmethod* osr_nm = method->lookup_osr_nmethod_for(bci, comp_level);
    if (osr_nm != NULL) {
      return osr_nm;  // 已有OSR编译版本
    }
    
    // 提交OSR编译请求
    CompileBroker::compile_method(method, bci, comp_level, ...);
  }
  return NULL;
}
```

### 6.8.2 OSR入口

```cpp
// src/hotspot/share/opto/parse.cpp
void Parse::do_osr_entry() {
  // 1. 从解释器栈帧恢复状态
  for (int i = 0; i < num_locals; i++) {
    Node* local = osr_buf->local_at(i);
    set_local(i, local);
  }
  
  // 2. 恢复锁状态
  for (int i = 0; i < num_locks; i++) {
    Node* lock = osr_buf->lock_at(i);
    push_lock(lock);
  }
  
  // 3. 跳转到循环入口
  jump_to(osr_bci);
}
```

---

## 6.9 去优化（Deoptimization）

### 6.9.1 去优化原因

```cpp
// src/hotspot/share/runtime/deoptimization.hpp
class Deoptimization : AllStatic {
public:
  enum DeoptReason {
    Reason_none,
    Reason_null_check,           // 空指针检查失败
    Reason_div0_check,           // 除零检查
    Reason_range_check,          // 数组越界
    Reason_class_check,          // 类型检查失败
    Reason_array_check,          // 数组类型检查
    Reason_intrinsic,            // 内置方法假设失败
    Reason_bimorphic,            // 双态调用变多态
    Reason_unloaded,             // 类卸载
    Reason_uninitialized,        // 类未初始化
    Reason_unstable_if,          // 条件分支不稳定
    Reason_unstable_fused_if,    // 融合条件不稳定
    Reason_constraint,           // 约束违反
    Reason_speculate_null_check, // 推测性空检查失败
    // ...
  };
};
```

### 6.9.2 去优化流程

```cpp
// src/hotspot/share/runtime/deoptimization.cpp
void Deoptimization::uncommon_trap(JavaThread* thread, ...) {
  // 1. 收集当前编译帧信息
  frame stub_frame = thread->last_frame();
  CompiledMethod* cm = stub_frame.cb()->as_compiled_method();
  
  // 2. 记录去优化原因
  MethodData* mdo = cm->method()->method_data();
  mdo->inc_decompile_count();
  
  // 3. 使nmethod失效
  if (should_make_not_entrant(reason)) {
    cm->make_not_entrant();
  }
  
  // 4. 重建解释器栈帧
  Deoptimization::UnrollBlock* info = fetch_unroll_info(thread);
  
  // 5. 恢复到解释执行
  thread->set_pending_deoptimization(info);
}
```

### 6.9.3 栈帧重建

```cpp
// 编译帧 → 解释器帧
void Deoptimization::unpack_frames(JavaThread* thread, ...) {
  // 遍历编译帧中的所有逻辑帧（可能有内联）
  for (int i = 0; i < info->number_of_frames(); i++) {
    // 1. 创建解释器栈帧
    frame* iframe = create_interpreter_frame(thread, method, bci);
    
    // 2. 恢复局部变量
    for (int j = 0; j < num_locals; j++) {
      iframe->set_local(j, values[j]);
    }
    
    // 3. 恢复操作数栈
    for (int j = 0; j < stack_size; j++) {
      iframe->push(stack[j]);
    }
    
    // 4. 恢复锁
    for (int j = 0; j < num_locks; j++) {
      iframe->lock(locks[j]);
    }
  }
}
```

---

## 6.10 与G1 GC的协作

### 6.10.1 编译代码中的写屏障

```cpp
// src/hotspot/share/gc/g1/g1BarrierSetC2.cpp
void G1BarrierSetC2::post_barrier(GraphKit* kit, Node* store, ...) {
  // 在Store节点后插入G1写屏障
  
  // 1. SATB Pre-Barrier
  Node* pre_val = kit->memory(Compile::AliasIdxRaw);
  kit->g1_write_barrier_pre(store, pre_val, ...);
  
  // 2. Post-Barrier（更新RSet）
  kit->g1_write_barrier_post(store, new_val, ...);
}
```

### 6.10.2 安全点轮询

```cpp
// src/hotspot/share/opto/output.cpp
void Compile::fill_buffer(CodeBuffer* cb, ...) {
  // 在方法返回前插入安全点检查
  if (need_polling_safepoint()) {
    // 读取轮询页
    __ testl(Address(r15_thread, JavaThread::polling_page_offset()));
  }
}
```

### 6.10.3 编译代码的GC支持

```cpp
// src/hotspot/share/code/nmethod.cpp
void nmethod::oops_do(OopClosure* f) {
  // GC时遍历nmethod中的所有oop引用
  
  // 1. 遍历常量池引用
  for (int i = 0; i < _metadata_size; i++) {
    f->do_oop(&_metadata[i]);
  }
  
  // 2. 遍历内联缓存
  for (RelocIterator iter(this); iter.next(); ) {
    if (iter.type() == relocInfo::oop_type) {
      f->do_oop(iter.oop_addr());
    }
  }
}
```

---

## 6.11 编译日志与调试

### 6.11.1 打印编译日志

```bash
# 打印编译事件
java -XX:+PrintCompilation YourApp

# 输出示例：
#   88   1       3       java.lang.String::hashCode (55 bytes)
#   编译ID  层级  编译类型  方法名               (字节码大小)
```

**编译类型标记**：
- `%`：OSR编译
- `s`：同步方法
- `!`：有异常处理
- `b`：阻塞编译
- `n`：native方法包装

### 6.11.2 详细编译日志

```bash
# 输出XML格式的详细日志
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+LogCompilation \
     -XX:LogFile=compilation.log \
     YourApp
```

### 6.11.3 查看生成的汇编

```bash
# 需要hsdis库
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+PrintAssembly \
     -XX:PrintAssemblyOptions=intel \
     YourApp
```

---

## 6.12 实战案例：追踪一次编译

### 6.12.1 Java代码

```java
public class HotMethod {
    public static int compute(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }
    
    public static void main(String[] args) {
        for (int i = 0; i < 100000; i++) {
            compute(100);
        }
    }
}
```

### 6.12.2 编译过程

```
1. 解释执行（Level 0）
   - 每次调用增加invocation_count
   - 每次循环增加backedge_count

2. 达到C1阈值（约2000次）
   - 提交到C1编译队列
   - C1编译到Level 3（带Profiling）
   
3. 收集Profile数据
   - 分支概率
   - 类型信息
   - 调用频率

4. 达到C2阈值（约15000次）
   - 提交到C2编译队列
   - C2编译到Level 4（完整优化）

5. C2优化
   - 循环展开
   - 强度削减（sum += i → sum = n*(n-1)/2）
   - 寄存器分配

6. 安装nmethod
   - 替换Method的入口点
   - 后续调用直接执行机器码
```

### 6.12.3 编译日志

```
    88    1     3     HotMethod::compute (22 bytes)
   125    2     4     HotMethod::compute (22 bytes)
   125    1     3     HotMethod::compute (22 bytes)   made not entrant
```

**解读**：
- 88ms时C1编译到Level 3
- 125ms时C2编译到Level 4
- Level 3版本被标记为not entrant（不再使用）

---

## 6.13 关键数据结构总结

```cpp
// 编译框架
class CompileBroker {
  static AbstractCompiler* _compilers[2];  // C1, C2
  static CompileQueue* _c1_compile_queue;
  static CompileQueue* _c2_compile_queue;
};

// 编译任务
class CompileTask {
  Method* _method;
  int _comp_level;
  int _osr_bci;
};

// C1编译
class Compilation {
  IR* _hir;           // HIR
  LinearScan* _allocator;
};

// C2编译
class Compile {
  RootNode* _root;    // IR图根节点
  ConnectionGraph* _congraph;  // 逃逸分析
};

// 编译结果
class nmethod {
  Method* _method;
  address _entry_point;
  Dependencies* _dependencies;
};
```

---

## 6.14 本章小结

### 核心要点
1. **分层编译**：Level 0→3→4是典型路径，平衡启动速度和峰值性能
2. **C1编译器**：快速编译，轻量优化，收集Profile
3. **C2编译器**：激进优化，Sea-of-Nodes IR，逃逸分析
4. **方法内联**：最重要的优化，消除调用开销
5. **逃逸分析**：支持栈上分配、标量替换、锁消除
6. **代码缓存**：分段存储，支持不同类型的代码
7. **去优化**：编译假设失败时回退到解释执行

### 性能调优参数
```bash
# 分层编译（默认开启）
-XX:+TieredCompilation
-XX:TieredStopAtLevel=4

# 编译阈值
-XX:CompileThreshold=10000
-XX:Tier3InvocationThreshold=200
-XX:Tier4InvocationThreshold=5000

# 内联控制
-XX:MaxInlineSize=35
-XX:MaxInlineLevel=9
-XX:InlineSmallCode=1000

# 逃逸分析
-XX:+DoEscapeAnalysis

# 代码缓存
-XX:ReservedCodeCacheSize=240m
```

### 与后续章节的联系
- **第7章（C1编译器）**：C1的详细编译流程
- **第8章（C2编译器）**：C2的优化技术深入
- **第12章（G1）**：编译代码中的写屏障实现

---

## 6.15 进阶阅读

**源码文件**：
```
src/hotspot/share/compiler/
├── compileBroker.cpp          [编译调度]
├── compileTask.cpp            [编译任务]
└── compilationPolicy.cpp      [编译策略]

src/hotspot/share/c1/
├── c1_Compilation.cpp         [C1编译流程]
├── c1_IR.cpp                  [HIR构建]
└── c1_LIRGenerator.cpp        [LIR生成]

src/hotspot/share/opto/
├── compile.cpp                [C2编译入口]
├── parse.cpp                  [字节码解析]
├── escape.cpp                 [逃逸分析]
└── loopnode.cpp               [循环优化]
```

**推荐实验**：
1. 使用`-XX:+PrintCompilation`观察编译过程
2. 使用`-XX:+PrintInlining`查看内联决策
3. 使用`-XX:+PrintEscapeAnalysis`查看逃逸分析结果
4. 使用JFR记录编译事件

---

**下一章预告**：  
《第7章：C1编译器详解》将深入C1的编译流程，从HIR构建到LIR生成，再到寄存器分配和代码生成，让你完全理解C1的工作原理。

兄弟，准备好深入编译器内部了吗？🚀
