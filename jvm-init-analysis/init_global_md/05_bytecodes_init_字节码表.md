# bytecodes_init() - 字节码表初始化

## 调试环境

| 配置项 | 值 |
|--------|-----|
| **JVM参数** | `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages` |
| **测试程序** | HelloWorld.java |

## 源码位置

- 文件：`src/hotspot/share/interpreter/bytecodes.cpp`
- 函数：`bytecodes_init()`

## 调用链

```
init_globals()
  └── bytecodes_init()  ← 第2个初始化函数
        └── Bytecodes::initialize()
```

## GDB调试结果

### 断点信息
```gdb
Thread 2 "java" hit Breakpoint 2, bytecodes_init () 
at /data/workspace/openjdk11-core/src/hotspot/share/interpreter/bytecodes.cpp:562
562	  Bytecodes::initialize();
```

### 调用栈
```
#0  bytecodes_init () at bytecodes.cpp:562
#1  init_globals () at init.cpp:112
#2  Threads::create_vm (args=0x7ffff780add0, canTryAgain=0x7ffff780acc3) at thread.cpp:4060
```

### Bytecodes初始化状态

| 属性 | 值 | 说明 |
|------|-----|------|
| `_is_initialized` | true | 初始化完成标志 |
| `number_of_codes` | 枚举值 | 字节码总数 |

### 常用字节码枚举值

| 字节码 | 枚举值 | 十进制 | 说明 |
|--------|--------|--------|------|
| `_nop` | 0 | 0 | 空操作 |
| `_aconst_null` | 1 | 1 | 推送null |
| `_iconst_0` | 3 | 3 | 推送int 0 |
| `_iload` | 21 | 21 | 加载int局部变量 |
| `_aload` | 25 | 25 | 加载引用局部变量 |
| `_istore` | 54 | 54 | 存储int局部变量 |
| `_iadd` | 96 | 96 | int加法 |
| `_return` | 177 | 177 | void方法返回 |
| `_invokevirtual` | 182 | 182 | 调用虚方法 |
| `_invokespecial` | 183 | 183 | 调用特殊方法 |
| `_invokestatic` | 184 | 184 | 调用静态方法 |
| `_invokeinterface` | 185 | 185 | 调用接口方法 |
| `_invokedynamic` | 186 | 186 | 动态调用 |
| `_new` | 187 | 187 | 创建对象 |
| `_athrow` | 191 | 191 | 抛出异常 |
| `_monitorenter` | 194 | 194 | 进入同步块 |
| `_monitorexit` | 195 | 195 | 退出同步块 |

## 字节码分类

### 1. 常量操作 (0x00 - 0x14)

| 范围 | 字节码 | 说明 |
|------|--------|------|
| 0x00 | nop | 空操作 |
| 0x01 | aconst_null | null常量 |
| 0x02-0x08 | iconst_m1 - iconst_5 | int常量-1到5 |
| 0x09-0x0a | lconst_0, lconst_1 | long常量0,1 |
| 0x0b-0x0d | fconst_0 - fconst_2 | float常量0,1,2 |
| 0x0e-0x0f | dconst_0, dconst_1 | double常量0,1 |
| 0x10 | bipush | 推送byte |
| 0x11 | sipush | 推送short |
| 0x12-0x14 | ldc, ldc_w, ldc2_w | 从常量池加载 |

### 2. 加载操作 (0x15 - 0x35)

| 范围 | 字节码 | 说明 |
|------|--------|------|
| 0x15-0x19 | iload - aload | 加载局部变量 |
| 0x1a-0x2d | iload_0 - aload_3 | 快速加载 |
| 0x2e-0x35 | iaload - saload | 数组元素加载 |

### 3. 存储操作 (0x36 - 0x56)

| 范围 | 字节码 | 说明 |
|------|--------|------|
| 0x36-0x3a | istore - astore | 存储到局部变量 |
| 0x3b-0x4e | istore_0 - astore_3 | 快速存储 |
| 0x4f-0x56 | iastore - sastore | 数组元素存储 |

### 4. 栈操作 (0x57 - 0x5f)

| 字节码 | 说明 |
|--------|------|
| pop, pop2 | 弹出栈顶 |
| dup, dup_x1, dup_x2 | 复制栈顶 |
| dup2, dup2_x1, dup2_x2 | 复制2个slot |
| swap | 交换栈顶两个值 |

### 5. 算术运算 (0x60 - 0x84)

| 范围 | 操作 | 类型 |
|------|------|------|
| 0x60-0x63 | add | i/l/f/d |
| 0x64-0x67 | sub | i/l/f/d |
| 0x68-0x6b | mul | i/l/f/d |
| 0x6c-0x6f | div | i/l/f/d |
| 0x70-0x73 | rem | i/l/f/d |
| 0x74-0x77 | neg | i/l/f/d |
| 0x78-0x7d | shl/shr/ushr | i/l |
| 0x7e-0x83 | and/or/xor | i/l |
| 0x84 | iinc | int自增 |

### 6. 方法调用 (0xb6 - 0xba)

| 字节码 | 值 | 说明 |
|--------|-----|------|
| invokevirtual | 182 | 虚方法调用 |
| invokespecial | 183 | 特殊方法（构造器、私有、super） |
| invokestatic | 184 | 静态方法调用 |
| invokeinterface | 185 | 接口方法调用 |
| invokedynamic | 186 | 动态调用（Lambda等） |

## Bytecodes类结构

```cpp
// src/hotspot/share/interpreter/bytecodes.hpp
class Bytecodes : AllStatic {
public:
  enum Code {
    _nop             = 0,
    _aconst_null     = 1,
    _iconst_m1       = 2,
    _iconst_0        = 3,
    // ... 共256个字节码
    number_of_codes  // 枚举结束标记
  };

private:
  static bool        _is_initialized;
  static const char* _name          [number_of_codes]; // 字节码名称
  static BasicType   _result_type   [number_of_codes]; // 结果类型
  static s_char      _depth         [number_of_codes]; // 栈深度变化
  static u_char      _lengths       [number_of_codes]; // 指令长度
  static Code        _java_code     [number_of_codes]; // Java标准码
  static jchar       _flags         [(1<<BitsPerByte)*2]; // 标志位
};
```

## 初始化过程

```cpp
void Bytecodes::initialize() {
  if (_is_initialized) return;
  
  // 为每个字节码定义属性
  def(_nop,        "nop",         "b", NULL, T_VOID, 0, false);
  def(_aconst_null,"aconst_null", "b", NULL, T_OBJECT, 1, false);
  def(_iconst_m1,  "iconst_m1",   "b", NULL, T_INT, 1, false);
  // ... 定义所有字节码
  
  _is_initialized = true;
}
```

## 与其他组件的关系

- **TemplateTable**：为每个字节码生成模板解释器代码
- **AbstractInterpreter**：使用字节码定义执行Java方法
- **C1/C2编译器**：编译字节码为本地代码
