# compilationPolicy_init() - 编译策略初始化

## 调试环境

| 配置项 | 值 |
|--------|-----|
| **JVM参数** | `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages` |
| **测试程序** | HelloWorld.java |

## 源码位置

- 文件：`src/hotspot/share/runtime/compilationPolicy.cpp`
- 函数：`compilationPolicy_init()`

## 调用链

```
init_globals()
  └── compilationPolicy_init()  ← 第3个初始化函数
        └── CompilationPolicy::set_in_vm_startup(true)
        └── 创建编译策略对象
```

## GDB调试结果

### 断点信息
```gdb
Thread 2 "java" hit Breakpoint 3, compilationPolicy_init () 
at /data/workspace/openjdk11-core/src/hotspot/share/runtime/compilationPolicy.cpp:63
63	  CompilationPolicy::set_in_vm_startup(DelayCompilationDuringStartup); // true
```

### 调用栈
```
#0  compilationPolicy_init () at compilationPolicy.cpp:63
#1  init_globals () at init.cpp:114
#2  Threads::create_vm (args=0x7ffff780add0, canTryAgain=0x7ffff780acc3) at thread.cpp:4060
```

### 分层编译参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `TieredCompilation` | true | 启用分层编译 |
| `TieredStopAtLevel` | 4 | 最高编译层级 |
| `Tier3InvocationThreshold` | 200 | Tier3调用阈值 |
| `Tier3CompileThreshold` | 2000 | Tier3编译阈值 |
| `Tier4InvocationThreshold` | 5000 | Tier4调用阈值 |
| `Tier4CompileThreshold` | 15000 | Tier4编译阈值 |
| `CICompilerCount` | 12 | 编译器线程数 |

### CompilationPolicy最终状态

```gdb
$27 = (TieredThresholdPolicy *) 0x7ffff002f0b0
$28 = (TieredThresholdPolicy) {
  <CompilationPolicy> = {
    static _in_vm_startup = false
  }, 
  members of TieredThresholdPolicy:
  _start_time = 1768398044164,
  _c1_count = 4,          // C1编译器线程数
  _c2_count = 8,          // C2编译器线程数
  _increase_threshold_at_ratio = 2
}
```

## 编译层级说明

HotSpot采用分层编译策略，共5个层级：

| 层级 | 编译器 | 说明 |
|------|--------|------|
| **Tier 0** | 解释器 | 纯解释执行，收集profiling数据 |
| **Tier 1** | C1 | 简单编译，不收集profiling |
| **Tier 2** | C1 | 有限profiling |
| **Tier 3** | C1 | 完整profiling |
| **Tier 4** | C2 | 完全优化编译 |

## 编译触发条件

### 热点检测

```
方法调用次数 >= Tier3InvocationThreshold (200)
     或
回边次数 >= Tier3BackEdgeThreshold
     ↓
触发C1编译 (Tier 3)
     ↓
继续profiling
     ↓
方法调用次数 >= Tier4InvocationThreshold (5000)
     或
回边次数 >= Tier4BackEdgeThreshold
     ↓
触发C2编译 (Tier 4)
```

## TieredThresholdPolicy类结构

```cpp
// src/hotspot/share/runtime/tieredThreshold.hpp
class TieredThresholdPolicy : public CompilationPolicy {
private:
  jlong _start_time;           // 策略启动时间
  int _c1_count;               // C1编译器线程数
  int _c2_count;               // C2编译器线程数
  double _increase_threshold_at_ratio; // 阈值增加比率

public:
  virtual void initialize();
  virtual bool should_compile_at_level(nmethod* nm, int level);
  virtual nmethod* event(methodHandle method, TRAPS);
};
```

## 编译器线程分配

基于 `CICompilerCount = 12`：

| 线程类型 | 数量 | 说明 |
|----------|------|------|
| C1编译线程 | 4 | 快速编译，用于Tier 1-3 |
| C2编译线程 | 8 | 优化编译，用于Tier 4 |

计算公式：
- C1线程 = min(CICompilerCount/3, 默认值)
- C2线程 = CICompilerCount - C1线程

## 关键JVM参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-XX:+TieredCompilation` | true | 启用分层编译 |
| `-XX:TieredStopAtLevel=N` | 4 | 最高编译层级 |
| `-XX:Tier0InvokeNotifyFreqLog` | 7 | Tier0调用通知频率 |
| `-XX:Tier0BackedgeNotifyFreqLog` | 7 | Tier0回边通知频率 |
| `-XX:Tier3InvocationThreshold` | 200 | Tier3调用阈值 |
| `-XX:Tier3CompileThreshold` | 2000 | Tier3编译阈值 |
| `-XX:Tier4InvocationThreshold` | 5000 | Tier4调用阈值 |
| `-XX:Tier4CompileThreshold` | 15000 | Tier4编译阈值 |

## 编译流程图

```
方法首次调用
      │
      ▼
  Tier 0 (解释执行)
      │ 收集profiling
      ▼
  计数器检查
      │
      ▼ 达到Tier3阈值
  ┌───────────────┐
  │ C1编译队列    │
  │ (Tier 3)      │
  └───────────────┘
      │
      ▼
  C1编译完成
      │ 继续profiling
      ▼
  计数器检查
      │
      ▼ 达到Tier4阈值
  ┌───────────────┐
  │ C2编译队列    │
  │ (Tier 4)      │
  └───────────────┘
      │
      ▼
  C2编译完成
      │
      ▼
  最优代码执行
```

## 与其他组件的关系

- **CompileBroker**：管理编译任务队列和编译器线程
- **InvocationCounter**：跟踪方法调用次数
- **CodeCache**：存储编译后的代码
- **C1/C2 Compiler**：实际的编译器实现
