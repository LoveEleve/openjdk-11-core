# 第13章：ASM字节码增强技术深度解析 - Arthas核心技术

## 📋 **章节概述**

ASM是Java字节码操作和分析的核心框架，也是Arthas实现动态监控、方法追踪、热更新等功能的技术基础。本章将从字节码结构开始，深度解析ASM框架的使用方法和高级技巧，为深度理解Arthas源码做好准备。

**学习目标**:
- 🎯 深度理解Java字节码结构和指令集
- 🎯 掌握ASM Core API和Tree API的使用
- 🎯 实现复杂的字节码增强和AOP功能
- 🎯 理解Arthas字节码增强的实现原理
- 🎯 具备开发字节码分析和修改工具的能力

---

## 🏗️ **Java字节码结构深度分析**

### **1.1 字节码文件格式**

Java字节码文件(.class)遵循严格的二进制格式规范：

```
ClassFile {
    u4             magic;                    // 魔数 0xCAFEBABE
    u2             minor_version;            // 次版本号
    u2             major_version;            // 主版本号
    u2             constant_pool_count;      // 常量池计数
    cp_info        constant_pool[constant_pool_count-1];  // 常量池
    u2             access_flags;             // 访问标志
    u2             this_class;               // 当前类索引
    u2             super_class;              // 父类索引
    u2             interfaces_count;         // 接口计数
    u2             interfaces[interfaces_count];          // 接口索引表
    u2             fields_count;             // 字段计数
    field_info     fields[fields_count];     // 字段表
    u2             methods_count;            // 方法计数
    method_info    methods[methods_count];   // 方法表
    u2             attributes_count;         // 属性计数
    attribute_info attributes[attributes_count];          // 属性表
}
```

### **1.2 常量池结构分析**

常量池是字节码文件的核心，存储了类中使用的所有常量信息：

```java
// 常量池项类型
public static final int CONSTANT_Class = 7;
public static final int CONSTANT_Fieldref = 9;
public static final int CONSTANT_Methodref = 10;
public static final int CONSTANT_InterfaceMethodref = 11;
public static final int CONSTANT_String = 8;
public static final int CONSTANT_Integer = 3;
public static final int CONSTANT_Float = 4;
public static final int CONSTANT_Long = 5;
public static final int CONSTANT_Double = 6;
public static final int CONSTANT_NameAndType = 12;
public static final int CONSTANT_Utf8 = 1;
public static final int CONSTANT_MethodHandle = 15;
public static final int CONSTANT_MethodType = 16;
public static final int CONSTANT_InvokeDynamic = 18;
```

### **1.3 方法字节码指令集**

JVM指令集按功能分类：

#### **加载和存储指令**:
```java
// 局部变量加载到操作数栈
ILOAD, LLOAD, FLOAD, DLOAD, ALOAD
ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3  // 快速加载指令

// 操作数栈存储到局部变量
ISTORE, LSTORE, FSTORE, DSTORE, ASTORE
ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3  // 快速存储指令

// 常量加载
ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5
LCONST_0, LCONST_1
FCONST_0, FCONST_1, FCONST_2
DCONST_0, DCONST_1
ACONST_NULL
```

#### **运算指令**:
```java
// 算术运算
IADD, LADD, FADD, DADD    // 加法
ISUB, LSUB, FSUB, DSUB    // 减法
IMUL, LMUL, FMUL, DMUL    // 乘法
IDIV, LDIV, FDIV, DDIV    // 除法
IREM, LREM, FREM, DREM    // 求余

// 位运算
ISHL, LSHL    // 左移
ISHR, LSHR    // 算术右移
IUSHR, LUSHR  // 逻辑右移
IAND, LAND    // 按位与
IOR, LOR      // 按位或
IXOR, LXOR    // 按位异或
```

#### **方法调用指令**:
```java
INVOKEVIRTUAL     // 调用实例方法
INVOKESPECIAL     // 调用构造方法、私有方法、父类方法
INVOKESTATIC      // 调用静态方法
INVOKEINTERFACE   // 调用接口方法
INVOKEDYNAMIC     // 调用动态方法(Lambda、方法句柄)
```

---

## 🔧 **ASM框架核心API详解**

### **2.1 ASM Core API架构**

ASM Core API基于访问者模式设计，提供了高效的字节码读写能力：

```java
// 核心类关系
ClassReader  →  ClassVisitor  →  ClassWriter
                     ↓
                MethodVisitor
                     ↓
                FieldVisitor
                     ↓
                AnnotationVisitor
```

#### **ClassVisitor核心方法**:

```java
public abstract class ClassVisitor {
    
    /**
     * 访问类头信息
     */
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {}
    
    /**
     * 访问源文件信息
     */
    public void visitSource(String source, String debug) {}
    
    /**
     * 访问外部类信息
     */
    public void visitOuterClass(String owner, String name, String descriptor) {}
    
    /**
     * 访问注解
     */
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return null;
    }
    
    /**
     * 访问字段
     */
    public FieldVisitor visitField(int access, String name, String descriptor,
                                  String signature, Object value) {
        return null;
    }
    
    /**
     * 访问方法
     */
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                    String signature, String[] exceptions) {
        return null;
    }
    
    /**
     * 访问内部类
     */
    public void visitInnerClass(String name, String outerName, String innerName, int access) {}
    
    /**
     * 访问结束
     */
    public void visitEnd() {}
}
```

### **2.2 MethodVisitor详解**

MethodVisitor是字节码增强的核心，用于访问和修改方法字节码：

```java
public abstract class MethodVisitor {
    
    /**
     * 访问方法参数
     */
    public void visitParameter(String name, int access) {}
    
    /**
     * 访问注解默认值
     */
    public AnnotationVisitor visitAnnotationDefault() { return null; }
    
    /**
     * 访问方法注解
     */
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return null;
    }
    
    /**
     * 访问参数注解
     */
    public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
        return null;
    }
    
    /**
     * 访问方法代码开始
     */
    public void visitCode() {}
    
    /**
     * 访问栈帧信息
     */
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {}
    
    /**
     * 访问零操作数指令
     */
    public void visitInsn(int opcode) {}
    
    /**
     * 访问单操作数指令
     */
    public void visitIntInsn(int opcode, int operand) {}
    
    /**
     * 访问局部变量指令
     */
    public void visitVarInsn(int opcode, int var) {}
    
    /**
     * 访问类型指令
     */
    public void visitTypeInsn(int opcode, String type) {}
    
    /**
     * 访问字段指令
     */
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}
    
    /**
     * 访问方法指令
     */
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}
    
    /**
     * 访问动态调用指令
     */
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {}
    
    /**
     * 访问跳转指令
     */
    public void visitJumpInsn(int opcode, Label label) {}
    
    /**
     * 访问标签
     */
    public void visitLabel(Label label) {}
    
    /**
     * 访问LDC指令
     */
    public void visitLdcInsn(Object value) {}
    
    /**
     * 访问IINC指令
     */
    public void visitIincInsn(int var, int increment) {}
    
    /**
     * 访问TABLESWITCH指令
     */
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {}
    
    /**
     * 访问LOOKUPSWITCH指令
     */
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {}
    
    /**
     * 访问多维数组指令
     */
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}
    
    /**
     * 访问try-catch块
     */
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {}
    
    /**
     * 访问局部变量信息
     */
    public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {}
    
    /**
     * 访问行号信息
     */
    public void visitLineNumber(int line, Label start) {}
    
    /**
     * 访问最大栈和局部变量
     */
    public void visitMaxs(int maxStack, int maxLocals) {}
    
    /**
     * 访问方法结束
     */
    public void visitEnd() {}
}
```

---

## 💻 **ASM实战开发 - 方法监控增强**

### **3.1 基础方法执行时间监控**

```java
package com.example.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 方法执行时间监控的ClassVisitor
 */
public class MethodTimingClassVisitor extends ClassVisitor {
    
    private String className;
    private boolean isInterface;
    
    public MethodTimingClassVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {
        this.className = name;
        this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        // 跳过接口、抽象方法、构造方法
        if (mv == null || isInterface || (access & Opcodes.ACC_ABSTRACT) != 0 || 
            "<init>".equals(name) || "<clinit>".equals(name)) {
            return mv;
        }
        
        return new MethodTimingAdapter(mv, access, name, descriptor, className);
    }
    
    /**
     * 方法时间监控适配器
     */
    private static class MethodTimingAdapter extends AdviceAdapter {
        
        private final String methodName;
        private final String className;
        private final String methodDescriptor;
        private int timeVarIndex;
        
        protected MethodTimingAdapter(MethodVisitor methodVisitor, int access,
                                    String name, String descriptor, String className) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.methodName = name;
            this.className = className;
            this.methodDescriptor = descriptor;
        }
        
        @Override
        protected void onMethodEnter() {
            // 分配局部变量存储开始时间
            timeVarIndex = newLocal(Type.LONG_TYPE);
            
            // 获取当前时间: long startTime = System.nanoTime();
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, timeVarIndex);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // 计算执行时间并记录
            // long duration = System.nanoTime() - startTime;
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, timeVarIndex);
            mv.visitInsn(LSUB);
            
            // 调用记录方法: MethodMonitor.recordExecution(className, methodName, duration);
            mv.visitLdcInsn(className.replace('/', '.'));
            mv.visitLdcInsn(methodName);
            mv.visitInsn(DUP2_X1); // 复制并重排栈: duration, className, methodName, duration
            mv.visitInsn(POP2);    // 弹出多余的duration
            
            mv.visitMethodInsn(INVOKESTATIC, 
                             "com/example/monitor/MethodMonitor", 
                             "recordExecution", 
                             "(Ljava/lang/String;Ljava/lang/String;J)V", 
                             false);
        }
    }
}
```

### **3.2 方法参数和返回值监控**

```java
package com.example.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 方法参数和返回值监控
 */
public class MethodWatchClassVisitor extends ClassVisitor {
    
    private String className;
    
    public MethodWatchClassVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        if (mv == null || (access & Opcodes.ACC_ABSTRACT) != 0 || 
            "<init>".equals(name) || "<clinit>".equals(name)) {
            return mv;
        }
        
        return new MethodWatchAdapter(mv, access, name, descriptor, className);
    }
    
    /**
     * 方法监控适配器
     */
    private static class MethodWatchAdapter extends AdviceAdapter {
        
        private final String methodName;
        private final String className;
        private final String methodDescriptor;
        private final Type[] argumentTypes;
        private final Type returnType;
        private int argsArrayIndex;
        
        protected MethodWatchAdapter(MethodVisitor methodVisitor, int access,
                                   String name, String descriptor, String className) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.methodName = name;
            this.className = className;
            this.methodDescriptor = descriptor;
            this.argumentTypes = Type.getArgumentTypes(descriptor);
            this.returnType = Type.getReturnType(descriptor);
        }
        
        @Override
        protected void onMethodEnter() {
            // 创建参数数组
            argsArrayIndex = newLocal(Type.getType(Object[].class));
            
            // Object[] args = new Object[argumentTypes.length];
            push(argumentTypes.length);
            newArray(Type.getType(Object.class));
            storeLocal(argsArrayIndex);
            
            // 将参数装箱并存入数组
            int argIndex = (methodAccess & ACC_STATIC) == 0 ? 1 : 0; // 跳过this
            for (int i = 0; i < argumentTypes.length; i++) {
                loadLocal(argsArrayIndex);
                push(i);
                loadArg(i);
                box(argumentTypes[i]);
                arrayStore(Type.getType(Object.class));
            }
            
            // 调用监控方法: MethodWatcher.onMethodEnter(className, methodName, args);
            mv.visitLdcInsn(className.replace('/', '.'));
            mv.visitLdcInsn(methodName);
            loadLocal(argsArrayIndex);
            mv.visitMethodInsn(INVOKESTATIC, 
                             "com/example/monitor/MethodWatcher", 
                             "onMethodEnter", 
                             "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V", 
                             false);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // 处理返回值
            if (opcode == RETURN) {
                // void方法
                mv.visitLdcInsn(className.replace('/', '.'));
                mv.visitLdcInsn(methodName);
                mv.visitInsn(ACONST_NULL);
            } else if (opcode == ARETURN) {
                // 引用类型返回值
                dup();
                mv.visitLdcInsn(className.replace('/', '.'));
                mv.visitLdcInsn(methodName);
                dupX2();
                pop();
            } else {
                // 基本类型返回值，需要装箱
                if (opcode == LRETURN || opcode == DRETURN) {
                    dup2();
                } else {
                    dup();
                }
                box(returnType);
                mv.visitLdcInsn(className.replace('/', '.'));
                mv.visitLdcInsn(methodName);
                dupX2();
                pop();
            }
            
            // 调用监控方法: MethodWatcher.onMethodExit(className, methodName, returnValue);
            mv.visitMethodInsn(INVOKESTATIC, 
                             "com/example/monitor/MethodWatcher", 
                             "onMethodExit", 
                             "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", 
                             false);
        }
        
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // 增加栈深度以容纳额外的操作
            super.visitMaxs(maxStack + 8, maxLocals);
        }
    }
}
```

### **3.3 异常监控增强**

```java
package com.example.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 异常监控增强
 */
public class ExceptionMonitorClassVisitor extends ClassVisitor {
    
    private String className;
    
    public ExceptionMonitorClassVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }
    
    @Override
    public void visit(int version, int access, String name, String signature,
                     String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        if (mv == null || (access & Opcodes.ACC_ABSTRACT) != 0) {
            return mv;
        }
        
        return new ExceptionMonitorAdapter(mv, access, name, descriptor, className);
    }
    
    /**
     * 异常监控适配器
     */
    private static class ExceptionMonitorAdapter extends AdviceAdapter {
        
        private final String methodName;
        private final String className;
        private Label startLabel;
        private Label endLabel;
        private Label handlerLabel;
        
        protected ExceptionMonitorAdapter(MethodVisitor methodVisitor, int access,
                                        String name, String descriptor, String className) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.methodName = name;
            this.className = className;
        }
        
        @Override
        public void visitCode() {
            super.visitCode();
            
            // 创建标签
            startLabel = new Label();
            endLabel = new Label();
            handlerLabel = new Label();
            
            // 添加异常处理器
            visitTryCatchBlock(startLabel, endLabel, handlerLabel, "java/lang/Throwable");
            
            // 标记try块开始
            visitLabel(startLabel);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // 标记try块结束
            visitLabel(endLabel);
        }
        
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // 异常处理器
            visitLabel(handlerLabel);
            
            // 复制异常对象用于重新抛出
            dup();
            
            // 调用异常监控: ExceptionMonitor.onException(className, methodName, exception);
            mv.visitLdcInsn(className.replace('/', '.'));
            mv.visitLdcInsn(methodName);
            dupX2();
            pop();
            mv.visitMethodInsn(INVOKESTATIC, 
                             "com/example/monitor/ExceptionMonitor", 
                             "onException", 
                             "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V", 
                             false);
            
            // 重新抛出异常
            athrow();
            
            super.visitMaxs(maxStack + 4, maxLocals);
        }
    }
}
```

---

## 🔍 **ASM Tree API深度应用**

### **4.1 Tree API基础结构**

ASM Tree API提供了更高级的抽象，允许以树形结构操作字节码：

```java
package com.example.asm.tree;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * 基于Tree API的字节码分析器
 */
public class BytecodeAnalyzer {
    
    /**
     * 分析类的详细信息
     */
    public ClassAnalysisResult analyzeClass(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        
        return analyzeClassNode(classNode);
    }
    
    /**
     * 分析ClassNode
     */
    private ClassAnalysisResult analyzeClassNode(ClassNode classNode) {
        ClassAnalysisResult result = new ClassAnalysisResult();
        result.className = classNode.name.replace('/', '.');
        result.superClassName = classNode.superName != null ? 
                               classNode.superName.replace('/', '.') : null;
        result.isInterface = (classNode.access & Opcodes.ACC_INTERFACE) != 0;
        result.isAbstract = (classNode.access & Opcodes.ACC_ABSTRACT) != 0;
        result.isFinal = (classNode.access & Opcodes.ACC_FINAL) != 0;
        
        // 分析接口
        if (classNode.interfaces != null) {
            result.interfaces = classNode.interfaces.stream()
                    .map(name -> name.replace('/', '.'))
                    .toArray(String[]::new);
        }
        
        // 分析字段
        result.fields = classNode.fields.stream()
                .map(this::analyzeField)
                .toArray(FieldAnalysisResult[]::new);
        
        // 分析方法
        result.methods = classNode.methods.stream()
                .map(this::analyzeMethod)
                .toArray(MethodAnalysisResult[]::new);
        
        return result;
    }
    
    /**
     * 分析字段
     */
    private FieldAnalysisResult analyzeField(FieldNode fieldNode) {
        FieldAnalysisResult result = new FieldAnalysisResult();
        result.name = fieldNode.name;
        result.descriptor = fieldNode.desc;
        result.type = Type.getType(fieldNode.desc).getClassName();
        result.isStatic = (fieldNode.access & Opcodes.ACC_STATIC) != 0;
        result.isFinal = (fieldNode.access & Opcodes.ACC_FINAL) != 0;
        result.isPrivate = (fieldNode.access & Opcodes.ACC_PRIVATE) != 0;
        result.isPublic = (fieldNode.access & Opcodes.ACC_PUBLIC) != 0;
        result.isProtected = (fieldNode.access & Opcodes.ACC_PROTECTED) != 0;
        
        return result;
    }
    
    /**
     * 分析方法
     */
    private MethodAnalysisResult analyzeMethod(MethodNode methodNode) {
        MethodAnalysisResult result = new MethodAnalysisResult();
        result.name = methodNode.name;
        result.descriptor = methodNode.desc;
        result.isStatic = (methodNode.access & Opcodes.ACC_STATIC) != 0;
        result.isAbstract = (methodNode.access & Opcodes.ACC_ABSTRACT) != 0;
        result.isFinal = (methodNode.access & Opcodes.ACC_FINAL) != 0;
        result.isPrivate = (methodNode.access & Opcodes.ACC_PRIVATE) != 0;
        result.isPublic = (methodNode.access & Opcodes.ACC_PUBLIC) != 0;
        result.isProtected = (methodNode.access & Opcodes.ACC_PROTECTED) != 0;
        
        // 分析参数类型
        Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
        result.parameterTypes = new String[argumentTypes.length];
        for (int i = 0; i < argumentTypes.length; i++) {
            result.parameterTypes[i] = argumentTypes[i].getClassName();
        }
        
        // 分析返回类型
        result.returnType = Type.getReturnType(methodNode.desc).getClassName();
        
        // 分析字节码指令
        if (methodNode.instructions != null) {
            result.instructionCount = methodNode.instructions.size();
            result.instructions = analyzeInstructions(methodNode.instructions);
        }
        
        // 分析异常处理
        if (methodNode.tryCatchBlocks != null) {
            result.tryCatchBlocks = methodNode.tryCatchBlocks.stream()
                    .map(this::analyzeTryCatchBlock)
                    .toArray(TryCatchBlockAnalysis[]::new);
        }
        
        return result;
    }
    
    /**
     * 分析指令序列
     */
    private InstructionAnalysis[] analyzeInstructions(InsnList instructions) {
        InstructionAnalysis[] result = new InstructionAnalysis[instructions.size()];
        
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            result[i] = analyzeInstruction(insn);
        }
        
        return result;
    }
    
    /**
     * 分析单个指令
     */
    private InstructionAnalysis analyzeInstruction(AbstractInsnNode insn) {
        InstructionAnalysis analysis = new InstructionAnalysis();
        analysis.opcode = insn.getOpcode();
        analysis.type = insn.getType();
        
        switch (insn.getType()) {
            case AbstractInsnNode.INSN:
                analysis.description = getOpcodeDescription(insn.getOpcode());
                break;
                
            case AbstractInsnNode.INT_INSN:
                IntInsnNode intInsn = (IntInsnNode) insn;
                analysis.description = getOpcodeDescription(insn.getOpcode()) + " " + intInsn.operand;
                break;
                
            case AbstractInsnNode.VAR_INSN:
                VarInsnNode varInsn = (VarInsnNode) insn;
                analysis.description = getOpcodeDescription(insn.getOpcode()) + " " + varInsn.var;
                break;
                
            case AbstractInsnNode.TYPE_INSN:
                TypeInsnNode typeInsn = (TypeInsnNode) insn;
                analysis.description = getOpcodeDescription(insn.getOpcode()) + " " + typeInsn.desc;
                break;
                
            case AbstractInsnNode.FIELD_INSN:
                FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                analysis.description = getOpcodeDescription(insn.getOpcode()) + " " + 
                                     fieldInsn.owner + "." + fieldInsn.name + " " + fieldInsn.desc;
                break;
                
            case AbstractInsnNode.METHOD_INSN:
                MethodInsnNode methodInsn = (MethodInsnNode) insn;
                analysis.description = getOpcodeDescription(insn.getOpcode()) + " " + 
                                     methodInsn.owner + "." + methodInsn.name + methodInsn.desc;
                break;
                
            case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
                InvokeDynamicInsnNode invokeDynamicInsn = (InvokeDynamicInsnNode) insn;
                analysis.description = "INVOKEDYNAMIC " + invokeDynamicInsn.name + invokeDynamicInsn.desc;
                break;
                
            case AbstractInsnNode.JUMP_INSN:
                analysis.description = getOpcodeDescription(insn.getOpcode()) + " (jump)";
                break;
                
            case AbstractInsnNode.LABEL:
                analysis.description = "LABEL";
                break;
                
            case AbstractInsnNode.LDC_INSN:
                LdcInsnNode ldcInsn = (LdcInsnNode) insn;
                analysis.description = "LDC " + ldcInsn.cst;
                break;
                
            case AbstractInsnNode.IINC_INSN:
                IincInsnNode iincInsn = (IincInsnNode) insn;
                analysis.description = "IINC " + iincInsn.var + " " + iincInsn.incr;
                break;
                
            case AbstractInsnNode.TABLESWITCH_INSN:
                analysis.description = "TABLESWITCH";
                break;
                
            case AbstractInsnNode.LOOKUPSWITCH_INSN:
                analysis.description = "LOOKUPSWITCH";
                break;
                
            case AbstractInsnNode.MULTIANEWARRAY_INSN:
                MultiANewArrayInsnNode multiArrayInsn = (MultiANewArrayInsnNode) insn;
                analysis.description = "MULTIANEWARRAY " + multiArrayInsn.desc + " " + multiArrayInsn.dims;
                break;
                
            case AbstractInsnNode.FRAME:
                analysis.description = "FRAME";
                break;
                
            case AbstractInsnNode.LINE:
                LineNumberNode lineInsn = (LineNumberNode) insn;
                analysis.description = "LINENUMBER " + lineInsn.line;
                break;
                
            default:
                analysis.description = "UNKNOWN";
        }
        
        return analysis;
    }
    
    /**
     * 分析try-catch块
     */
    private TryCatchBlockAnalysis analyzeTryCatchBlock(TryCatchBlockNode tryCatchBlock) {
        TryCatchBlockAnalysis analysis = new TryCatchBlockAnalysis();
        analysis.exceptionType = tryCatchBlock.type;
        return analysis;
    }
    
    /**
     * 获取操作码描述
     */
    private String getOpcodeDescription(int opcode) {
        switch (opcode) {
            case Opcodes.NOP: return "NOP";
            case Opcodes.ACONST_NULL: return "ACONST_NULL";
            case Opcodes.ICONST_M1: return "ICONST_M1";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.ICONST_2: return "ICONST_2";
            case Opcodes.ICONST_3: return "ICONST_3";
            case Opcodes.ICONST_4: return "ICONST_4";
            case Opcodes.ICONST_5: return "ICONST_5";
            case Opcodes.LCONST_0: return "LCONST_0";
            case Opcodes.LCONST_1: return "LCONST_1";
            case Opcodes.FCONST_0: return "FCONST_0";
            case Opcodes.FCONST_1: return "FCONST_1";
            case Opcodes.FCONST_2: return "FCONST_2";
            case Opcodes.DCONST_0: return "DCONST_0";
            case Opcodes.DCONST_1: return "DCONST_1";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.SIPUSH: return "SIPUSH";
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.FLOAD: return "FLOAD";
            case Opcodes.DLOAD: return "DLOAD";
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.FSTORE: return "FSTORE";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.ASTORE: return "ASTORE";
            case Opcodes.IADD: return "IADD";
            case Opcodes.LADD: return "LADD";
            case Opcodes.FADD: return "FADD";
            case Opcodes.DADD: return "DADD";
            case Opcodes.ISUB: return "ISUB";
            case Opcodes.LSUB: return "LSUB";
            case Opcodes.FSUB: return "FSUB";
            case Opcodes.DSUB: return "DSUB";
            case Opcodes.IMUL: return "IMUL";
            case Opcodes.LMUL: return "LMUL";
            case Opcodes.FMUL: return "FMUL";
            case Opcodes.DMUL: return "DMUL";
            case Opcodes.IDIV: return "IDIV";
            case Opcodes.LDIV: return "LDIV";
            case Opcodes.FDIV: return "FDIV";
            case Opcodes.DDIV: return "DDIV";
            case Opcodes.IREM: return "IREM";
            case Opcodes.LREM: return "LREM";
            case Opcodes.FREM: return "FREM";
            case Opcodes.DREM: return "DREM";
            case Opcodes.INEG: return "INEG";
            case Opcodes.LNEG: return "LNEG";
            case Opcodes.FNEG: return "FNEG";
            case Opcodes.DNEG: return "DNEG";
            case Opcodes.ISHL: return "ISHL";
            case Opcodes.LSHL: return "LSHL";
            case Opcodes.ISHR: return "ISHR";
            case Opcodes.LSHR: return "LSHR";
            case Opcodes.IUSHR: return "IUSHR";
            case Opcodes.LUSHR: return "LUSHR";
            case Opcodes.IAND: return "IAND";
            case Opcodes.LAND: return "LAND";
            case Opcodes.IOR: return "IOR";
            case Opcodes.LOR: return "LOR";
            case Opcodes.IXOR: return "IXOR";
            case Opcodes.LXOR: return "LXOR";
            case Opcodes.IINC: return "IINC";
            case Opcodes.I2L: return "I2L";
            case Opcodes.I2F: return "I2F";
            case Opcodes.I2D: return "I2D";
            case Opcodes.L2I: return "L2I";
            case Opcodes.L2F: return "L2F";
            case Opcodes.L2D: return "L2D";
            case Opcodes.F2I: return "F2I";
            case Opcodes.F2L: return "F2L";
            case Opcodes.F2D: return "F2D";
            case Opcodes.D2I: return "D2I";
            case Opcodes.D2L: return "D2L";
            case Opcodes.D2F: return "D2F";
            case Opcodes.I2B: return "I2B";
            case Opcodes.I2C: return "I2C";
            case Opcodes.I2S: return "I2S";
            case Opcodes.LCMP: return "LCMP";
            case Opcodes.FCMPL: return "FCMPL";
            case Opcodes.FCMPG: return "FCMPG";
            case Opcodes.DCMPL: return "DCMPL";
            case Opcodes.DCMPG: return "DCMPG";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLT: return "IFLT";
            case Opcodes.IFGE: return "IFGE";
            case Opcodes.IFGT: return "IFGT";
            case Opcodes.IFLE: return "IFLE";
            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ";
            case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
            case Opcodes.IF_ICMPLT: return "IF_ICMPLT";
            case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IF_ACMPEQ: return "IF_ACMPEQ";
            case Opcodes.IF_ACMPNE: return "IF_ACMPNE";
            case Opcodes.GOTO: return "GOTO";
            case Opcodes.JSR: return "JSR";
            case Opcodes.RET: return "RET";
            case Opcodes.TABLESWITCH: return "TABLESWITCH";
            case Opcodes.LOOKUPSWITCH: return "LOOKUPSWITCH";
            case Opcodes.IRETURN: return "IRETURN";
            case Opcodes.LRETURN: return "LRETURN";
            case Opcodes.FRETURN: return "FRETURN";
            case Opcodes.DRETURN: return "DRETURN";
            case Opcodes.ARETURN: return "ARETURN";
            case Opcodes.RETURN: return "RETURN";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.INVOKEDYNAMIC: return "INVOKEDYNAMIC";
            case Opcodes.NEW: return "NEW";
            case Opcodes.NEWARRAY: return "NEWARRAY";
            case Opcodes.ANEWARRAY: return "ANEWARRAY";
            case Opcodes.ARRAYLENGTH: return "ARRAYLENGTH";
            case Opcodes.ATHROW: return "ATHROW";
            case Opcodes.CHECKCAST: return "CHECKCAST";
            case Opcodes.INSTANCEOF: return "INSTANCEOF";
            case Opcodes.MONITORENTER: return "MONITORENTER";
            case Opcodes.MONITOREXIT: return "MONITOREXIT";
            case Opcodes.MULTIANEWARRAY: return "MULTIANEWARRAY";
            case Opcodes.IFNULL: return "IFNULL";
            case Opcodes.IFNONNULL: return "IFNONNULL";
            default: return "UNKNOWN(" + opcode + ")";
        }
    }
    
    // 分析结果类定义
    public static class ClassAnalysisResult {
        public String className;
        public String superClassName;
        public String[] interfaces;
        public boolean isInterface;
        public boolean isAbstract;
        public boolean isFinal;
        public FieldAnalysisResult[] fields;
        public MethodAnalysisResult[] methods;
    }
    
    public static class FieldAnalysisResult {
        public String name;
        public String descriptor;
        public String type;
        public boolean isStatic;
        public boolean isFinal;
        public boolean isPrivate;
        public boolean isPublic;
        public boolean isProtected;
    }
    
    public static class MethodAnalysisResult {
        public String name;
        public String descriptor;
        public String[] parameterTypes;
        public String returnType;
        public boolean isStatic;
        public boolean isAbstract;
        public boolean isFinal;
        public boolean isPrivate;
        public boolean isPublic;
        public boolean isProtected;
        public int instructionCount;
        public InstructionAnalysis[] instructions;
        public TryCatchBlockAnalysis[] tryCatchBlocks;
    }
    
    public static class InstructionAnalysis {
        public int opcode;
        public int type;
        public String description;
    }
    
    public static class TryCatchBlockAnalysis {
        public String exceptionType;
    }
}
```

### **4.2 复杂字节码转换 - 方法调用链追踪**

```java
package com.example.asm.tree;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * 方法调用链追踪增强器
 */
public class MethodTraceEnhancer {
    
    private final Set<String> targetMethods;
    private final boolean traceAllMethods;
    
    public MethodTraceEnhancer(Set<String> targetMethods) {
        this.targetMethods = targetMethods != null ? targetMethods : new HashSet<>();
        this.traceAllMethods = this.targetMethods.isEmpty();
    }
    
    /**
     * 增强类字节码
     */
    public byte[] enhance(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        
        // 增强方法
        for (MethodNode method : classNode.methods) {
            if (shouldEnhanceMethod(classNode.name, method)) {
                enhanceMethod(classNode.name, method);
            }
        }
        
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }
    
    /**
     * 判断是否需要增强方法
     */
    private boolean shouldEnhanceMethod(String className, MethodNode method) {
        if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
            return false;
        }
        
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }
        
        if (traceAllMethods) {
            return true;
        }
        
        String methodSignature = className + "." + method.name + method.desc;
        return targetMethods.contains(methodSignature);
    }
    
    /**
     * 增强方法
     */
    private void enhanceMethod(String className, MethodNode method) {
        InsnList instructions = method.instructions;
        if (instructions == null || instructions.size() == 0) {
            return;
        }
        
        // 在方法开始插入追踪代码
        InsnList enterInstructions = createMethodEnterInstructions(className, method);
        instructions.insert(enterInstructions);
        
        // 在所有返回指令前插入追踪代码
        AbstractInsnNode[] insnArray = instructions.toArray();
        for (AbstractInsnNode insn : insnArray) {
            if (isReturnInstruction(insn.getOpcode())) {
                InsnList exitInstructions = createMethodExitInstructions(className, method);
                instructions.insertBefore(insn, exitInstructions);
            }
        }
        
        // 更新最大栈深度
        method.maxStack += 10;
    }
    
    /**
     * 创建方法进入追踪指令
     */
    private InsnList createMethodEnterInstructions(String className, MethodNode method) {
        InsnList instructions = new InsnList();
        
        // MethodTracer.enter(className, methodName, args);
        instructions.add(new LdcInsnNode(className.replace('/', '.')));
        instructions.add(new LdcInsnNode(method.name));
        
        // 创建参数数组
        Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        instructions.add(new LdcInsnNode(argumentTypes.length));
        instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        
        // 填充参数数组
        int argIndex = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < argumentTypes.length; i++) {
            instructions.add(new InsnNode(Opcodes.DUP));
            instructions.add(new LdcInsnNode(i));
            
            Type argType = argumentTypes[i];
            instructions.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), argIndex));
            
            // 装箱基本类型
            if (argType.getSort() != Type.OBJECT && argType.getSort() != Type.ARRAY) {
                boxPrimitiveType(instructions, argType);
            }
            
            instructions.add(new InsnNode(Opcodes.AASTORE));
            argIndex += argType.getSize();
        }
        
        // 调用追踪方法
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/trace/MethodTracer",
                "enter",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V",
                false));
        
        return instructions;
    }
    
    /**
     * 创建方法退出追踪指令
     */
    private InsnList createMethodExitInstructions(String className, MethodNode method) {
        InsnList instructions = new InsnList();
        
        // MethodTracer.exit(className, methodName);
        instructions.add(new LdcInsnNode(className.replace('/', '.')));
        instructions.add(new LdcInsnNode(method.name));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/trace/MethodTracer",
                "exit",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                false));
        
        return instructions;
    }
    
    /**
     * 装箱基本类型
     */
    private void boxPrimitiveType(InsnList instructions, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                break;
            case Type.BYTE:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
                break;
            case Type.CHAR:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
                break;
            case Type.SHORT:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
                break;
            case Type.INT:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                break;
            case Type.LONG:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                break;
            case Type.FLOAT:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
                break;
            case Type.DOUBLE:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
                break;
        }
    }
    
    /**
     * 判断是否为返回指令
     */
    private boolean isReturnInstruction(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }
}
```

---

## 🧪 **监控组件实现**

### **5.1 方法监控器**

```java
package com.example.monitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 方法执行监控器
 */
public class MethodMonitor {
    
    private static final ConcurrentHashMap<String, MethodStats> methodStats = new ConcurrentHashMap<>();
    private static volatile boolean enabled = true;
    
    /**
     * 记录方法执行
     */
    public static void recordExecution(String className, String methodName, long duration) {
        if (!enabled) {
            return;
        }
        
        String key = className + "." + methodName;
        methodStats.computeIfAbsent(key, k -> new MethodStats()).addExecution(duration);
    }
    
    /**
     * 获取方法统计信息
     */
    public static MethodStats getMethodStats(String className, String methodName) {
        String key = className + "." + methodName;
        return methodStats.get(key);
    }
    
    /**
     * 获取所有方法统计信息
     */
    public static ConcurrentHashMap<String, MethodStats> getAllStats() {
        return new ConcurrentHashMap<>(methodStats);
    }
    
    /**
     * 清除统计信息
     */
    public static void clearStats() {
        methodStats.clear();
    }
    
    /**
     * 启用/禁用监控
     */
    public static void setEnabled(boolean enabled) {
        MethodMonitor.enabled = enabled;
    }
    
    /**
     * 打印统计报告
     */
    public static void printReport() {
        System.out.println("=== Method Execution Report ===");
        methodStats.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().getTotalTime(), e1.getValue().getTotalTime()))
                .limit(20)
                .forEach(entry -> {
                    String method = entry.getKey();
                    MethodStats stats = entry.getValue();
                    System.out.printf("%-50s | Count: %8d | Total: %8.2fms | Avg: %6.2fms | Max: %6.2fms%n",
                            method,
                            stats.getCount(),
                            stats.getTotalTime() / 1_000_000.0,
                            stats.getAverageTime() / 1_000_000.0,
                            stats.getMaxTime() / 1_000_000.0);
                });
    }
    
    /**
     * 方法统计信息
     */
    public static class MethodStats {
        private final AtomicLong count = new AtomicLong(0);
        private final LongAdder totalTime = new LongAdder();
        private final AtomicLong maxTime = new AtomicLong(0);
        private final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        
        void addExecution(long duration) {
            count.incrementAndGet();
            totalTime.add(duration);
            
            // 更新最大时间
            long currentMax = maxTime.get();
            while (duration > currentMax && !maxTime.compareAndSet(currentMax, duration)) {
                currentMax = maxTime.get();
            }
            
            // 更新最小时间
            long currentMin = minTime.get();
            while (duration < currentMin && !minTime.compareAndSet(currentMin, duration)) {
                currentMin = minTime.get();
            }
        }
        
        public long getCount() { return count.get(); }
        public long getTotalTime() { return totalTime.sum(); }
        public long getMaxTime() { return maxTime.get(); }
        public long getMinTime() { 
            long min = minTime.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }
        public double getAverageTime() {
            long c = count.get();
            return c > 0 ? (double) totalTime.sum() / c : 0.0;
        }
    }
}
```

### **5.2 方法观察器**

```java
package com.example.monitor;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 方法观察器 - 类似Arthas的watch命令
 */
public class MethodWatcher {
    
    private static final ConcurrentLinkedQueue<WatchEvent> events = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean enabled = new AtomicBoolean(true);
    private static volatile int maxEvents = 1000;
    
    /**
     * 方法进入事件
     */
    public static void onMethodEnter(String className, String methodName, Object[] args) {
        if (!enabled.get()) {
            return;
        }
        
        WatchEvent event = new WatchEvent();
        event.className = className;
        event.methodName = methodName;
        event.eventType = WatchEvent.EventType.ENTER;
        event.timestamp = System.currentTimeMillis();
        event.threadId = Thread.currentThread().getId();
        event.threadName = Thread.currentThread().getName();
        event.args = args != null ? Arrays.copyOf(args, args.length) : null;
        
        addEvent(event);
    }
    
    /**
     * 方法退出事件
     */
    public static void onMethodExit(String className, String methodName, Object returnValue) {
        if (!enabled.get()) {
            return;
        }
        
        WatchEvent event = new WatchEvent();
        event.className = className;
        event.methodName = methodName;
        event.eventType = WatchEvent.EventType.EXIT;
        event.timestamp = System.currentTimeMillis();
        event.threadId = Thread.currentThread().getId();
        event.threadName = Thread.currentThread().getName();
        event.returnValue = returnValue;
        
        addEvent(event);
    }
    
    /**
     * 添加事件
     */
    private static void addEvent(WatchEvent event) {
        events.offer(event);
        
        // 限制事件数量
        while (events.size() > maxEvents) {
            events.poll();
        }
    }
    
    /**
     * 获取最新事件
     */
    public static WatchEvent[] getLatestEvents(int count) {
        return events.stream()
                .skip(Math.max(0, events.size() - count))
                .toArray(WatchEvent[]::new);
    }
    
    /**
     * 清除事件
     */
    public static void clearEvents() {
        events.clear();
    }
    
    /**
     * 启用/禁用观察
     */
    public static void setEnabled(boolean enabled) {
        MethodWatcher.enabled.set(enabled);
    }
    
    /**
     * 设置最大事件数
     */
    public static void setMaxEvents(int maxEvents) {
        MethodWatcher.maxEvents = maxEvents;
    }
    
    /**
     * 打印最新事件
     */
    public static void printLatestEvents(int count) {
        WatchEvent[] latestEvents = getLatestEvents(count);
        
        System.out.println("=== Latest Watch Events ===");
        for (WatchEvent event : latestEvents) {
            System.out.println(event);
        }
    }
    
    /**
     * 观察事件
     */
    public static class WatchEvent {
        public enum EventType { ENTER, EXIT }
        
        public String className;
        public String methodName;
        public EventType eventType;
        public long timestamp;
        public long threadId;
        public String threadName;
        public Object[] args;
        public Object returnValue;
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%tT] Thread-%d(%s) %s.%s ",
                    timestamp, threadId, threadName, className, methodName));
            
            if (eventType == EventType.ENTER) {
                sb.append("ENTER");
                if (args != null && args.length > 0) {
                    sb.append(" args=").append(Arrays.toString(args));
                }
            } else {
                sb.append("EXIT");
                if (returnValue != null) {
                    sb.append(" return=").append(returnValue);
                }
            }
            
            return sb.toString();
        }
    }
}
```

### **5.3 异常监控器**

```java
package com.example.monitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异常监控器
 */
public class ExceptionMonitor {
    
    private static final ConcurrentHashMap<String, ExceptionStats> exceptionStats = new ConcurrentHashMap<>();
    private static volatile boolean enabled = true;
    
    /**
     * 记录异常
     */
    public static void onException(String className, String methodName, Throwable exception) {
        if (!enabled) {
            return;
        }
        
        String key = className + "." + methodName;
        String exceptionType = exception.getClass().getName();
        
        exceptionStats.computeIfAbsent(key, k -> new ExceptionStats()).addException(exceptionType, exception);
    }
    
    /**
     * 获取异常统计
     */
    public static ExceptionStats getExceptionStats(String className, String methodName) {
        String key = className + "." + methodName;
        return exceptionStats.get(key);
    }
    
    /**
     * 获取所有异常统计
     */
    public static ConcurrentHashMap<String, ExceptionStats> getAllStats() {
        return new ConcurrentHashMap<>(exceptionStats);
    }
    
    /**
     * 清除统计信息
     */
    public static void clearStats() {
        exceptionStats.clear();
    }
    
    /**
     * 启用/禁用监控
     */
    public static void setEnabled(boolean enabled) {
        ExceptionMonitor.enabled = enabled;
    }
    
    /**
     * 打印异常报告
     */
    public static void printReport() {
        System.out.println("=== Exception Report ===");
        exceptionStats.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().getTotalCount(), e1.getValue().getTotalCount()))
                .forEach(entry -> {
                    String method = entry.getKey();
                    ExceptionStats stats = entry.getValue();
                    System.out.printf("%-50s | Total: %d%n", method, stats.getTotalCount());
                    
                    stats.getExceptionCounts().entrySet().stream()
                            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                            .forEach(exEntry -> {
                                System.out.printf("  %-40s | Count: %d%n", 
                                        exEntry.getKey(), exEntry.getValue());
                            });
                });
    }
    
    /**
     * 异常统计信息
     */
    public static class ExceptionStats {
        private final AtomicLong totalCount = new AtomicLong(0);
        private final ConcurrentHashMap<String, AtomicLong> exceptionCounts = new ConcurrentHashMap<>();
        
        void addException(String exceptionType, Throwable exception) {
            totalCount.incrementAndGet();
            exceptionCounts.computeIfAbsent(exceptionType, k -> new AtomicLong(0)).incrementAndGet();
        }
        
        public long getTotalCount() { return totalCount.get(); }
        
        public ConcurrentHashMap<String, Long> getExceptionCounts() {
            ConcurrentHashMap<String, Long> result = new ConcurrentHashMap<>();
            exceptionCounts.forEach((key, value) -> result.put(key, value.get()));
            return result;
        }
    }
}
```

---

## 🔧 **综合测试和验证**

### **6.1 ASM增强测试程序**

```java
package com.example.test;

import com.example.asm.*;
import com.example.monitor.*;
import org.objectweb.asm.*;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * ASM增强功能综合测试
 */
public class ASMEnhancementTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== ASM Enhancement Test ===");
        
        // 测试1: 方法执行时间监控
        testMethodTimingEnhancement();
        
        // 测试2: 方法参数和返回值监控
        testMethodWatchEnhancement();
        
        // 测试3: 异常监控
        testExceptionMonitorEnhancement();
        
        // 测试4: 字节码分析
        testBytecodeAnalysis();
        
        System.out.println("=== Test Completed ===");
    }
    
    /**
     * 测试方法执行时间监控
     */
    private static void testMethodTimingEnhancement() throws Exception {
        System.out.println("\n--- Testing Method Timing Enhancement ---");
        
        // 加载原始类
        byte[] originalBytes = loadClassBytes(TestTarget.class);
        
        // 应用时间监控增强
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        MethodTimingClassVisitor enhancer = new MethodTimingClassVisitor(writer);
        reader.accept(enhancer, 0);
        
        byte[] enhancedBytes = writer.toByteArray();
        
        // 保存增强后的类文件
        saveClassBytes("TestTarget_Enhanced.class", enhancedBytes);
        
        System.out.println("Method timing enhancement completed");
        System.out.println("Enhanced class saved as TestTarget_Enhanced.class");
    }
    
    /**
     * 测试方法观察增强
     */
    private static void testMethodWatchEnhancement() throws Exception {
        System.out.println("\n--- Testing Method Watch Enhancement ---");
        
        // 加载原始类
        byte[] originalBytes = loadClassBytes(TestTarget.class);
        
        // 应用观察增强
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        MethodWatchClassVisitor enhancer = new MethodWatchClassVisitor(writer);
        reader.accept(enhancer, 0);
        
        byte[] enhancedBytes = writer.toByteArray();
        
        // 保存增强后的类文件
        saveClassBytes("TestTarget_Watch.class", enhancedBytes);
        
        System.out.println("Method watch enhancement completed");
        System.out.println("Enhanced class saved as TestTarget_Watch.class");
    }
    
    /**
     * 测试异常监控增强
     */
    private static void testExceptionMonitorEnhancement() throws Exception {
        System.out.println("\n--- Testing Exception Monitor Enhancement ---");
        
        // 加载原始类
        byte[] originalBytes = loadClassBytes(TestTarget.class);
        
        // 应用异常监控增强
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ExceptionMonitorClassVisitor enhancer = new ExceptionMonitorClassVisitor(writer);
        reader.accept(enhancer, 0);
        
        byte[] enhancedBytes = writer.toByteArray();
        
        // 保存增强后的类文件
        saveClassBytes("TestTarget_Exception.class", enhancedBytes);
        
        System.out.println("Exception monitor enhancement completed");
        System.out.println("Enhanced class saved as TestTarget_Exception.class");
    }
    
    /**
     * 测试字节码分析
     */
    private static void testBytecodeAnalysis() throws Exception {
        System.out.println("\n--- Testing Bytecode Analysis ---");
        
        // 加载类字节码
        byte[] classBytes = loadClassBytes(TestTarget.class);
        
        // 分析字节码
        BytecodeAnalyzer analyzer = new BytecodeAnalyzer();
        BytecodeAnalyzer.ClassAnalysisResult result = analyzer.analyzeClass(classBytes);
        
        // 打印分析结果
        printAnalysisResult(result);
    }
    
    /**
     * 加载类字节码
     */
    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        String className = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(className)) {
            if (is == null) {
                throw new IOException("Class not found: " + className);
            }
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }
    
    /**
     * 保存类字节码
     */
    private static void saveClassBytes(String fileName, byte[] classBytes) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write(classBytes);
        }
    }
    
    /**
     * 打印分析结果
     */
    private static void printAnalysisResult(BytecodeAnalyzer.ClassAnalysisResult result) {
        System.out.println("Class Analysis Result:");
        System.out.println("  Class Name: " + result.className);
        System.out.println("  Super Class: " + result.superClassName);
        System.out.println("  Is Interface: " + result.isInterface);
        System.out.println("  Is Abstract: " + result.isAbstract);
        System.out.println("  Is Final: " + result.isFinal);
        
        if (result.interfaces != null && result.interfaces.length > 0) {
            System.out.println("  Interfaces: " + String.join(", ", result.interfaces));
        }
        
        System.out.println("  Fields (" + result.fields.length + "):");
        for (BytecodeAnalyzer.FieldAnalysisResult field : result.fields) {
            System.out.printf("    %s %s %s%n", 
                    getAccessString(field.isPublic, field.isPrivate, field.isProtected, field.isStatic, field.isFinal),
                    field.type, field.name);
        }
        
        System.out.println("  Methods (" + result.methods.length + "):");
        for (BytecodeAnalyzer.MethodAnalysisResult method : result.methods) {
            System.out.printf("    %s %s %s(%s) - %d instructions%n",
                    getAccessString(method.isPublic, method.isPrivate, method.isProtected, method.isStatic, method.isFinal),
                    method.returnType, method.name,
                    method.parameterTypes != null ? String.join(", ", method.parameterTypes) : "",
                    method.instructionCount);
        }
    }
    
    /**
     * 获取访问修饰符字符串
     */
    private static String getAccessString(boolean isPublic, boolean isPrivate, boolean isProtected, 
                                        boolean isStatic, boolean isFinal) {
        StringBuilder sb = new StringBuilder();
        if (isPublic) sb.append("public ");
        if (isPrivate) sb.append("private ");
        if (isProtected) sb.append("protected ");
        if (isStatic) sb.append("static ");
        if (isFinal) sb.append("final ");
        return sb.toString().trim();
    }
    
    /**
     * 测试目标类
     */
    public static class TestTarget {
        private String name;
        private int value;
        
        public TestTarget(String name, int value) {
            this.name = name;
            this.value = value;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public int getValue() {
            return value;
        }
        
        public void setValue(int value) {
            this.value = value;
        }
        
        public String processData(String input) {
            if (input == null) {
                throw new IllegalArgumentException("Input cannot be null");
            }
            
            try {
                Thread.sleep(10); // 模拟处理时间
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return name + ":" + input.toUpperCase();
        }
        
        public int calculate(int a, int b) {
            if (b == 0) {
                throw new ArithmeticException("Division by zero");
            }
            
            return (a + value) / b;
        }
    }
}
```

<function_calls>
<invoke name="todo_write">
<parameter name="merge">true