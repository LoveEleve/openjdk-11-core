# synchronized锁膨胀机制 - GDB调试验证

> **基于GDB调试验证的synchronized锁膨胀完整分析**
> 
> 实验环境: OpenJDK 11 slowdebug, `-XX:-UseBiasedLocking`

---

## 📚 文档列表

| 文档 | 内容 |
|------|------|
| [01_锁膨胀完整流程_GDB验证.md](./01_锁膨胀完整流程_GDB验证.md) | 锁膨胀完整流程、mark word编码 |
| [02_轻量级锁详解_GDB验证.md](./02_轻量级锁详解_GDB验证.md) | BasicLock、CAS获取/释放 |
| [03_重量级锁与ObjectMonitor_GDB验证.md](./03_重量级锁与ObjectMonitor_GDB验证.md) | ObjectMonitor结构与队列 |

---

## ⭐ 核心发现

### 锁状态编码 (关闭偏向锁)

| mark word末两位 | 状态 | 说明 |
|-----------------|------|------|
| `01` | 无锁 | 初始状态 |
| `00` | 轻量级锁 | 指向栈上Lock Record |
| `10` | 重量级锁 | 指向ObjectMonitor |
| `11` | GC标记 | GC专用 |

### 锁膨胀触发条件

```
轻量级锁膨胀为重量级锁的条件:
1. 锁竞争: 线程B尝试获取线程A持有的轻量级锁
2. wait(): 调用Object.wait()
3. hashCode(): 调用hashCode()且锁已被持有
```

---

## 🔍 GDB验证数据汇总

### 锁膨胀过程

| 阶段 | mark word | 状态 |
|------|-----------|------|
| 初始 | `0x1` | 无锁 |
| 轻量级锁 | `0x7fffdd0f42f8` | 指向Lock Record |
| 膨胀后 | `0x7fffc8003082` | 指向ObjectMonitor |

### ObjectMonitor GDB验证

| 字段 | 值 | 说明 |
|------|-----|------|
| ObjectMonitor | `0x7fffc8003080` | 堆外内存 |
| _header | `0x1` | 原始mark |
| _object | `0xfff019d0` | 锁对象 |
| _owner | `NULL`/Thread* | 持有者 |
| _recursions | 0/1/... | 重入计数 |

### InflateCause枚举

| 值 | 名称 | 说明 |
|----|------|------|
| 0 | VM_INTERNAL | JVM内部 |
| 1 | MONITOR_ENTER | synchronized |
| 2 | WAIT | Object.wait() |
| 3 | NOTIFY | Object.notify() |
| 4 | HASH_CODE | hashCode() |

---

## 📊 锁膨胀流程图

```
synchronized(obj) {
        │
        ▼
   fast_enter
        │ (偏向锁关闭)
        ▼
   slow_enter
        │
        ├─ CAS成功 → 轻量级锁 → 执行代码
        │
        └─ CAS失败 → 锁竞争
               │
               ▼
           inflate()
               │
               ▼
         ObjectMonitor::enter
               │
               ├─ CAS _owner成功 → 获得锁
               │
               └─ CAS失败 → park()阻塞
}
```

---

## 🛠️ GDB调试命令

### 设置断点

```bash
break ObjectSynchronizer::slow_enter
break ObjectSynchronizer::inflate
break ObjectMonitor::enter
break ObjectMonitor::exit
```

### 运行程序

```bash
gdb ./java
run -Xms256m -Xmx256m -XX:+UseG1GC -XX:-UseLargePages \
    -XX:-UseBiasedLocking -Xint -cp /path/to SyncTest
```

### 查看锁状态

```bash
# 查看mark word
set $obj = <对象地址>
p/x *(unsigned long*)$obj
p/x (*(unsigned long*)$obj) & 0x3  # 锁状态位

# 查看ObjectMonitor
set $mon = <Monitor地址>
p *(ObjectMonitor*)$mon
```

---

## 📈 性能对比

| 锁类型 | 获取开销 | 适用场景 |
|--------|----------|----------|
| 轻量级锁 | ~10ns (CAS) | 无竞争/低竞争 |
| 重量级锁 | ~微秒 (park/unpark) | 高竞争 |

---

## 🔗 相关源码

| 文件 | 内容 |
|------|------|
| `synchronizer.cpp` | ObjectSynchronizer (slow_enter, inflate) |
| `objectMonitor.cpp` | ObjectMonitor (enter, exit) |
| `basicLock.hpp` | BasicLock, BasicObjectLock |
| `markOop.hpp` | mark word编码定义 |
