# 对象创建流程文档 - GDB验证

> **验证环境**: OpenJDK 11 slowdebug  
> **JVM参数**: `-Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -Xint`  
> **验证日期**: 2026-01-15

---

## 文档列表

| 文件 | 内容 |
|------|------|
| [01_对象创建完整流程_GDB验证.md](./01_对象创建完整流程_GDB验证.md) | 对象创建5阶段、完整调用栈、源码分析 |
| [02_对象内存布局_GDB验证.md](./02_对象内存布局_GDB验证.md) | 对象头、Mark Word、压缩类指针 |
| [03_TLAB内存分配_GDB验证.md](./03_TLAB内存分配_GDB验证.md) | TLAB机制、bump-the-pointer分配 |

---

## 测试代码

```java
public class HelloWorld {
    public static void main(String[] args) {
        HelloWorld obj = new HelloWorld();
    }
}
```

**字节码**:
```
0: new           #2    // class HelloWorld
3: dup
4: invokespecial #3    // Method "<init>":()V
7: astore_1
8: return
```

---

## ⭐ 核心GDB验证数据

### 对象创建流程

```
new HelloWorld() 执行路径:

字节码 bb 00 02 (new #2)
    │
    ▼
InterpreterRuntime::_new
    │ pool=0x7fffcefa6058, index=2
    │
    ├──► InstanceKlass::allocate_instance
    │    │ this=0x800092840, size=16 bytes
    │    │
    │    ├──► CollectedHeap::obj_allocate
    │    │    │
    │    │    └──► MemAllocator::allocate
    │    │         │
    │    │         └──► TLAB bump-the-pointer
    │    │              └──► 返回 0x7ff41f2b0
    │    │
    │    └──► 初始化对象头
    │
    └──► 返回oop到解释器栈
```

### 对象内存布局

```
HelloWorld对象 @ 0x7ff41f2b0 (16 bytes)

┌───────┬──────────────────────────────┐
│ 偏移  │ 内容                          │
├───────┼──────────────────────────────┤
│ +0    │ mark word: 0x0000000000000005│
│       │ (偏向锁可用)                  │
├───────┼──────────────────────────────┤
│ +8    │ compressed klass: 0x00092840 │
│       │ → 0x800092840 (HelloWorld)   │
├───────┼──────────────────────────────┤
│ +12   │ padding: 4 bytes             │
└───────┴──────────────────────────────┘
```

### TLAB分配

```
TLAB状态:
  _start: 0x7ff400800
  _top:   0x7ff41f2b0 → 0x7ff41f2c0 (+16)
  _end:   0x7ff6005c0
  大小:   ~2MB

分配方式: bump-the-pointer (无锁)
```

---

## ⭐ 关键地址汇总

| 对象/结构 | 地址 | 说明 |
|-----------|------|------|
| HelloWorld对象 | 0x7ff41f2b0 | Java堆 (Eden TLAB) |
| InstanceKlass | 0x800092840 | Compressed Class Space |
| ConstantPool | 0x7fffcefa6058 | Metaspace Non-class |
| CPCache | 0x7fffcefa62c0 | Metaspace Non-class |
| G1CollectedHeap | 0x7ffff0031cd0 | Native内存 |
| TLAB | 0x7ffff001f128 | Thread结构内 |

---

## ⭐ 关键数值汇总

| 项目 | 值 | 说明 |
|------|-----|------|
| 对象大小 | 16 bytes | header(12) + padding(4) |
| mark word | 0x5 | 偏向锁可用 |
| compressed klass | 0x92840 | 压缩后4字节 |
| TLAB大小 | ~2MB | 线程本地缓冲 |
| _layout_helper | 16 | InstanceKlass中存储 |
| _vtable_len | 5 | 继承Object的5个虚方法 |

---

## GDB调试命令

```bash
# 进入调试
cd /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/jdk/bin
gdb ./java

# 关键断点
break InterpreterRuntime::_new
break InstanceKlass::allocate_instance
break MemAllocator::mem_allocate

# 运行
run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseLargePages -Xint \
    -cp /data/workspace HelloWorld

# 查看TLAB
set $thread = (Thread*)thread
set $tlab = &($thread->_tlab)
print $tlab->_top

# 查看对象头
set $obj = 0x7ff41f2b0
x/1gx $obj      # mark word
x/1wx $obj+8    # compressed klass

# 解压类指针
set $cklass = *(unsigned int*)($obj+8)
print/x $cklass + 0x800000000
```

---

## 对象创建5阶段

| 阶段 | 函数 | 作用 |
|------|------|------|
| 1 | InterpreterRuntime::_new | 解释器入口，解析常量池 |
| 2 | InstanceKlass::allocate_instance | 计算大小，调用堆分配 |
| 3 | CollectedHeap::obj_allocate | 创建分配器 |
| 4 | MemAllocator::allocate | TLAB/Eden分配 |
| 5 | ObjAllocator::initialize | 初始化对象头 |
