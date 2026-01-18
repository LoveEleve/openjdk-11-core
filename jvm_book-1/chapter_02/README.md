# 第02章：内存模型与对象创建 - 配套文件说明

## 📁 文件结构

```
chapter_02/
├── 02_内存模型与对象创建.md          # 主要章节内容
├── chapter_02_memory_layout.gdb      # 内存布局验证脚本
├── chapter_02_object_creation.gdb    # 对象创建追踪脚本
├── ObjectCreationTest.java           # 对象创建测试程序
├── README.md                         # 本文件
└── logs/                            # 运行日志目录
    ├── memory_layout.log            # 内存布局验证日志
    ├── object_creation_trace.log    # 对象创建追踪日志
    └── performance_data.txt         # 性能分析数据
```

## 🚀 快速开始

### 1. 环境准备

确保已完成第01章的环境配置，并验证8GB堆配置：

```bash
# 检查可用内存 (至少需要12GB)
free -h

# 验证OpenJDK slowdebug版本
/data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java -version
```

### 2. 编译测试程序

```bash
cd /data/workspace/openjdk11-core/jvm_book-1/chapter_02

# 编译对象创建测试程序
/data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/javac ObjectCreationTest.java

# 创建日志目录
mkdir -p logs
```

### 3. 运行内存布局验证

```bash
# 验证8GB G1堆的完整内存布局
gdb --batch --command=chapter_02_memory_layout.gdb \
    --args /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java \
    -Xms8g -Xmx8g -XX:+UseG1GC HelloWorld > logs/memory_layout.log 2>&1

# 查看内存布局验证结果
cat logs/memory_layout.log
```

### 4. 运行对象创建追踪

```bash
# 追踪完整对象创建流程
gdb --batch --command=chapter_02_object_creation.gdb \
    --args /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin/java \
    -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest > logs/object_creation_trace.log 2>&1

# 查看对象创建追踪结果
cat logs/object_creation_trace.log
```

## 📊 预期输出

### 内存布局验证结果

运行内存布局验证脚本后，你应该看到：

```
=== G1堆基本配置验证 ===
G1堆对象地址: 0x7ffff0031e20
堆内存配置:
  堆起始地址: 0x600000000 (24.0 GB虚拟地址)
  堆结束地址: 0x800000000 (32.0 GB虚拟地址)
  堆总大小: 8589934592 bytes (8.00 GB)

Region配置:
  Region大小: 4194304 bytes (4.0 MB)
  Region数量: 2048个
  总Region容量: 8.00 GB

年轻代配置:
  当前Eden Region数: 51个
  Eden区大小: 204.0 MB

G1策略配置:
  最大暂停时间目标: 200 ms
  年轻代最小Region数: 51个
  年轻代最大Region数: 1024个

=== 压缩指针配置验证 ===
压缩对象指针:
  启用状态: 启用 ✅
  基址: 0x600000000
  偏移位数: 3 bits
  可寻址范围: 32.0 GB

压缩类指针:
  启用状态: 启用 ✅
  基址: 0x800000000
  偏移位数: 3 bits
  类空间大小: 1073741824 bytes (1.00 GB)

=== CodeCache内存布局验证 ===
Non-nmethod区域 (解释器):
  起始地址: 0x7fffed000000
  结束地址: 0x7fffed5d0000
  区域大小: 5.81 MB
  已使用: 2.34 MB

Profiled区域 (C1编译器):
  起始地址: 0x7fffec000000
  结束地址: 0x7fffec750000
  区域大小: 117.00 MB
  已使用: 0.12 MB

Non-profiled区域 (C2编译器):
  起始地址: 0x7fffeb000000
  结束地址: 0x7fffeb750000
  区域大小: 117.00 MB
  已使用: 0.08 MB

CodeCache总大小: 239.81 MB

=== 完整内存布局图 ===
虚拟地址空间布局 (64位Linux):
┌─────────────────────────────────────────────────────────────┐
│ 0x000000000 (0GB)    ── 进程空间起始                        │
│     │                                                       │
│ 0x600000000 (24.0GB) ── G1堆起始地址 ⭐               │
│     │ ├─ Eden区域        (~204MB)                         │
│     │ ├─ 可分配区域      (~7.8GB)                         │
│     │                                                       │
│ 0x800000000 (32.0GB) ── G1堆结束/压缩类空间起始 ⭐     │
│     │ ├─ 压缩类空间      (1.00GB)                          │
│     │                                                       │
│ 0x840000000 (33.0GB) ── 压缩类空间结束                 │
│     │                                                       │
│ 0x????????????? ── CodeCache区域 (~240MB)              │
│ 0x????????????? ── Metaspace区域                         │
└─────────────────────────────────────────────────────────────┘
```

### 对象创建追踪结果

运行对象创建追踪脚本后，你应该看到：

```
=== 对象分配 #1 ===
InterpreterRuntime::_new() - 解释器对象分配
  常量池: 0x7fffcefa6068
  常量池索引: 2
  分配类: java/lang/Object

InstanceKlass::allocate_instance() - 类实例化
  InstanceKlass地址: 0x800000028
  类名: java/lang/Object
  实例大小: 16 bytes
  字段数量: 0

CollectedHeap::obj_allocate() - 堆分配请求
  请求大小: 2 words (16 bytes)
  分配类: 0x800000028

MemAllocator::allocate() - 内存分配器
  分配器地址: 0x7fffdd0f4280
  分配大小: 2 words (16 bytes)
  分配类: 0x800000028

TLAB分配 (快速路径):
  TLAB起始: 0x600000000
  TLAB当前: 0x600000000
  TLAB结束: 0x600200000
  请求大小: 2 words (16 bytes)
  分配后位置: 0x600000010
  TLAB空间: 充足 ✅
  剩余空间: 2097136 bytes (2047.00 KB)

=== 对象分配统计 ===
分配统计:
  总分配次数: 15
  总分配字节: 1248 bytes (1.22 KB)
  平均对象大小: 83.2 bytes

当前TLAB状态:
  TLAB起始: 0x600000000
  TLAB当前: 0x6000004e0
  TLAB结束: 0x600200000
  TLAB大小: 2097152 bytes (2.00 MB)
  TLAB已用: 1248 bytes (0.06%)
  TLAB剩余: 2095904 bytes (2046.00 KB)
```

## 🔧 实验扩展

### 1. TLAB大小调优实验

测试不同TLAB大小对分配性能的影响：

```bash
# 小TLAB测试
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:TLABSize=512k ObjectCreationTest

# 大TLAB测试  
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:TLABSize=4m ObjectCreationTest

# 默认TLAB测试
java -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest
```

### 2. 压缩指针开关实验

对比启用/禁用压缩指针的内存使用：

```bash
# 启用压缩指针 (默认)
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+UseCompressedOops ObjectCreationTest

# 禁用压缩指针
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseCompressedOops ObjectCreationTest
```

### 3. 对象对齐实验

测试不同对象对齐设置：

```bash
# 8字节对齐 (默认)
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:ObjectAlignmentInBytes=8 ObjectCreationTest

# 16字节对齐
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:ObjectAlignmentInBytes=16 ObjectCreationTest
```

## 🔍 深度分析技巧

### 1. 自定义GDB分析

在GDB脚本中添加自定义分析函数：

```gdb
# 分析特定对象的内存布局
define analyze_object_at_address
    set $addr = $arg0
    printf "对象地址: %p\n", $addr
    
    # 分析对象头
    set $mark_word = *(uint64_t*)$addr
    printf "Mark Word: 0x%016lx\n", $mark_word
    
    # 分析Klass指针
    set $klass_ptr = *(uint32_t*)($addr + 8)
    printf "压缩Klass指针: 0x%08x\n", $klass_ptr
end

# 使用方法
# (gdb) analyze_object_at_address 0x600000000
```

### 2. 内存使用统计

添加详细的内存使用统计：

```java
// 在ObjectCreationTest.java中添加
private static void analyzeMemoryUsage() {
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
    
    System.out.printf("堆内存使用详情:\n");
    System.out.printf("  初始大小: %.2f GB\n", heapUsage.getInit() / 1024.0 / 1024.0 / 1024.0);
    System.out.printf("  已使用: %.2f MB\n", heapUsage.getUsed() / 1024.0 / 1024.0);
    System.out.printf("  已提交: %.2f GB\n", heapUsage.getCommitted() / 1024.0 / 1024.0 / 1024.0);
    System.out.printf("  最大大小: %.2f GB\n", heapUsage.getMax() / 1024.0 / 1024.0 / 1024.0);
}
```

## 🎯 性能基准

### 标准配置下的预期性能

| 指标 | 预期值 | 说明 |
|------|--------|------|
| TLAB分配速度 | 2-5ns/对象 | bump-the-pointer算法 |
| TLAB命中率 | >95% | 大部分分配在TLAB内 |
| 压缩指针开销 | <1ns | 简单位运算 |
| 对象创建总开销 | 10-20ns | 包含初始化 |
| TLAB重填频率 | <1% | 取决于分配模式 |

### 性能影响因素

1. **TLAB大小**: 影响重填频率和内存局部性
2. **对象大小分布**: 影响TLAB利用率
3. **分配频率**: 影响TLAB压力
4. **线程数量**: 影响TLAB总内存开销

## 🚨 故障排除

### 常见问题

1. **内存不足错误**
   ```bash
   # 检查系统内存
   free -h
   # 需要至少12GB可用内存
   ```

2. **GDB符号未找到**
   ```bash
   # 确保使用slowdebug版本
   file /path/to/java
   # 应显示 "not stripped"
   ```

3. **TLAB分配失败**
   ```bash
   # 检查TLAB配置
   java -XX:+PrintFlagsFinal -version | grep TLAB
   ```

### 调试技巧

1. **单步调试对象分配**
   ```bash
   gdb --args java -Xms8g -Xmx8g -XX:+UseG1GC ObjectCreationTest
   (gdb) break InterpreterRuntime::_new
   (gdb) run
   (gdb) step
   ```

2. **查看详细TLAB状态**
   ```bash
   java -Xms8g -Xmx8g -XX:+UseG1GC -XX:+PrintTLAB ObjectCreationTest
   ```

## 📚 相关资源

- [G1垃圾收集器详解](https://docs.oracle.com/en/java/javase/11/gctuning/garbage-first-garbage-collector.html)
- [TLAB机制原理](https://shipilev.net/jvm/anatomy-quarks/4-tlab-allocation/)
- [压缩指针实现](https://wiki.openjdk.java.net/display/HotSpot/CompressedOops)
- [对象内存布局](https://shipilev.net/jvm/objects-inside-out/)

---

**下一步**: 完成本章学习后，继续学习 [第03章：类加载机制](../chapter_03/) - 深入分析类加载的完整生命周期 🚀