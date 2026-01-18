# stubRoutines_init1/2() - 汇编桩代码初始化

## 调试环境

| 配置项 | 值 |
|--------|-----|
| **JVM参数** | `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages` |
| **测试程序** | HelloWorld.java |

## 源码位置

- 文件：`src/hotspot/share/runtime/stubRoutines.cpp`
- CPU特定实现：`src/hotspot/cpu/x86/stubGenerator_x86_64.cpp`
- 函数：`stubRoutines_init1()`, `stubRoutines_init2()`

## 两阶段初始化

StubRoutines采用两阶段初始化，因为某些桩代码依赖于其他组件：

| 阶段 | 函数 | 调用位置 | 依赖 |
|------|------|----------|------|
| Phase 1 | `stubRoutines_init1()` | `init_globals()` 第6个 | VM_Version |
| Phase 2 | `stubRoutines_init2()` | `init_globals()` 最后 | Universe, Interpreter |

## GDB调试结果

### Phase 1 断点信息
```gdb
Thread 2 "java" hit Breakpoint 6, stubRoutines_init1 () 
at /data/workspace/openjdk11-core/src/hotspot/share/runtime/stubRoutines.cpp:410
410	void stubRoutines_init1() { StubRoutines::initialize1(); }
```

### StubRoutines Phase 1 最终状态

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_call_stub_entry` | 0x7fffe1000c9e | Java调用入口 |
| `_call_stub_return_address` | 0x7fffe1000d4a | 调用返回地址 |
| `_catch_exception_entry` | 0x7fffe1000e50 | 异常捕获入口 |
| `_forward_exception_entry` | 0x7fffe1000c20 | 异常转发入口 |
| `_throw_AbstractMethodError_entry` | 0x7fffe1092b20 | 抽象方法错误 |
| `_throw_IncompatibleClassChangeError_entry` | 0x7fffe1092820 | 类变更不兼容错误 |
| `_throw_NullPointerException_at_call_entry` | 0x7fffe1092520 | 空指针异常 |
| `_throw_StackOverflowError_entry` | 0x7fffe1008620 | 栈溢出错误 |

### 原子操作桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_atomic_xchg_entry` | 0x7fffe1000f08 | 原子交换 |
| `_atomic_xchg_long_entry` | 0x7fffe1000f0d | 原子交换(long) |
| `_atomic_cmpxchg_entry` | 0x7fffe1000f14 | 原子比较交换 |
| `_atomic_cmpxchg_byte_entry` | 0x7fffe1000f1b | 原子比较交换(byte) |
| `_atomic_cmpxchg_long_entry` | 0x7fffe1000f25 | 原子比较交换(long) |
| `_atomic_add_entry` | 0x7fffe1000f2e | 原子加 |
| `_atomic_add_long_entry` | 0x7fffe1000f37 | 原子加(long) |
| `_fence_entry` | 0x7fffe1000f43 | 内存屏障 |

### Code1 代码块

```gdb
$30 = (BufferBlob) {
  _name = "StubRoutines (1)",
  _size = 30144,              // ~29KB
  _code_begin = 0x7fffe1000c20,
  _code_end = 0x7fffe1008150,
}
```

## StubRoutines Phase 2 最终状态

### 数组操作桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_jbyte_arraycopy` | 0x7fffe1093800 | byte数组拷贝 |
| `_jshort_arraycopy` | 0x7fffe1093a20 | short数组拷贝 |
| `_jint_arraycopy` | 0x7fffe1093c00 | int数组拷贝 |
| `_jlong_arraycopy` | 0x7fffe1093dc0 | long数组拷贝 |
| `_oop_arraycopy` | 0x7fffe1094100 | 对象数组拷贝 |
| `_oop_arraycopy_uninit` | 0x7fffe1094560 | 未初始化对象拷贝 |
| `_jbyte_disjoint_arraycopy` | 0x7fffe1093700 | 不重叠byte拷贝 |
| `_arrayof_jbyte_arraycopy` | 0x7fffe1093800 | 对齐byte数组拷贝 |
| `_checkcast_arraycopy` | 0x7fffe1094740 | 带类型检查拷贝 |
| `_unsafe_arraycopy` | 0x7fffe1094d20 | Unsafe拷贝 |

### CRC/加密桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_updateBytesCRC32` | 0x7fffe1000f60 | CRC32计算 |
| `_updateBytesCRC32C` | 0x7fffe10011c0 | CRC32C计算 |
| `_aescrypt_encryptBlock` | 0x7fffe1095420 | AES加密块 |
| `_aescrypt_decryptBlock` | 0x7fffe1095540 | AES解密块 |
| `_cipherBlockChaining_encryptAESCrypt` | 0x7fffe1095660 | CBC加密 |
| `_cipherBlockChaining_decryptAESCrypt` | 0x7fffe10958a0 | CBC解密 |
| `_ghash_processBlocks` | 0x7fffe1099c20 | GHASH处理 |

### SHA桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_sha1_implCompress` | 0x7fffe1097300 | SHA1压缩 |
| `_sha256_implCompress` | 0x7fffe1097840 | SHA256压缩 |
| `_sha512_implCompress` | 0x7fffe1097f20 | SHA512压缩 |

### 数学运算桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_dexp` | 0x7fffe1001419 | double exp |
| `_dlog` | 0x7fffe1001746 | double log |
| `_dlog10` | 0x7fffe10019c2 | double log10 |
| `_dpow` | 0x7fffe1001c71 | double pow |
| `_dsin` | 0x7fffe1002d85 | double sin |
| `_dcos` | 0x7fffe100341c | double cos |
| `_dtan` | 0x7fffe1003a95 | double tan |

### BigInteger桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_multiplyToLen` | 0x7fffe109a0c0 | 大数乘法 |
| `_squareToLen` | 0x7fffe109a300 | 大数平方 |
| `_mulAdd` | 0x7fffe109a440 | 乘加 |

### 安全点桩

| 桩名称 | 地址 | 说明 |
|--------|------|------|
| `_safefetch32_entry` | 0x7fffe109a0aa | 安全读32位 |
| `_safefetchN_entry` | 0x7fffe109a0b0 | 安全读N位 |

### Code2 代码块

```gdb
$42 = (BufferBlob) {
  _name = "StubRoutines (2)",
  _size = 46448,              // ~45KB
  _code_begin = 0x7fffe1093220,
  _code_end = 0x7fffe109e700,
}
```

## StubRoutines类结构

```cpp
// src/hotspot/share/runtime/stubRoutines.hpp
class StubRoutines : AllStatic {
private:
  // Phase 1 桩
  static address _call_stub_entry;
  static address _catch_exception_entry;
  static address _forward_exception_entry;
  static address _throw_AbstractMethodError_entry;
  // ...

  // 原子操作桩
  static address _atomic_xchg_entry;
  static address _atomic_cmpxchg_entry;
  static address _atomic_add_entry;
  static address _fence_entry;

  // Phase 2 桩
  static address _jbyte_arraycopy;
  static address _jshort_arraycopy;
  // ...

  // 代码块
  static BufferBlob* _code1;  // Phase 1 代码
  static BufferBlob* _code2;  // Phase 2 代码

public:
  static void initialize1();
  static void initialize2();
};
```

## 桩代码内存占用

| 阶段 | 代码块 | 大小 |
|------|--------|------|
| Phase 1 | StubRoutines (1) | ~29KB |
| Phase 2 | StubRoutines (2) | ~45KB |
| **总计** | | **~74KB** |

## 与其他组件的关系

- **VM_Version**：根据CPU特性选择最优实现
- **Interpreter**：解释器调用桩代码执行特定操作
- **C1/C2 Compiler**：编译器内联这些桩代码
- **Unsafe**：`_unsafe_arraycopy`支持Unsafe操作
- **java.util.zip**：CRC32桩支持压缩操作
- **javax.crypto**：AES/SHA桩支持加密操作
- **java.lang.Math**：数学桩支持Math类方法
