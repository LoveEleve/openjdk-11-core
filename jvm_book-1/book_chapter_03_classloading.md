# 第三章：类加载系统源码深度分析

## 3.1 概述

类加载系统是JVM的核心组件之一，负责将Java字节码转换为运行时的类对象。本章将基于OpenJDK 11源码深入分析类加载的完整流程，重点关注SystemDictionary、ClassLoader和ClassFileParser的实现机制。

**标准测试条件回顾：**
- 堆配置：`-Xms=8GB -Xmx=8GB`
- 模块系统：Java 9+模块化
- 类加载器：Bootstrap、Platform、Application三层结构
- 元空间：存储类元数据

## 3.2 类加载系统架构总览

### 3.2.1 核心组件关系

```
应用程序类
    ↓
ClassLoader (类加载器)
    ↓
SystemDictionary (系统字典)
    ↓
ClassFileParser (类文件解析器)
    ↓
InstanceKlass (运行时类表示)
    ↓
Metaspace (元空间存储)
```

### 3.2.2 类加载器层次结构

```cpp
// Java 9+ 类加载器层次结构
Bootstrap ClassLoader (C++实现)
    ↓
Platform ClassLoader (Java实现)
    ↓  
Application ClassLoader (Java实现)
    ↓
Custom ClassLoader (用户自定义)
```

## 3.3 SystemDictionary：类注册表的核心实现

### 3.3.1 SystemDictionary类结构分析

SystemDictionary是JVM中所有已加载类的注册表，维护着类名到Klass对象的映射关系：

```cpp
// src/hotspot/share/classfile/systemDictionary.hpp:227
class SystemDictionary : AllStatic {
  friend class VMStructs;
  friend class SystemDictionaryHandles;

public:
  // 知名类枚举ID
  enum WKID {
    NO_WKID = 0,
    
    // 通过宏定义生成所有知名类的枚举值
    #define WK_KLASS_ENUM(name, symbol, ignore_o) \
        WK_KLASS_ENUM_NAME(name), \
        WK_KLASS_ENUM_NAME(symbol) = WK_KLASS_ENUM_NAME(name),
    WK_KLASSES_DO(WK_KLASS_ENUM)
    #undef WK_KLASS_ENUM
    
    WKID_LIMIT,
    FIRST_WKID = NO_WKID + 1
  };

private:
  // 知名类数组 - 存储所有预加载的核心类
  static InstanceKlass* _well_known_klasses[];
  
  // 占位符表 - 用于检测循环依赖
  static PlaceholderTable* _placeholders;
  
  // 加载器约束表 - 维护类加载器之间的约束关系
  static LoaderConstraintTable* _loader_constraints;
  
  // 解析错误表 - 记录类解析过程中的错误
  static ResolutionErrorTable* _resolution_errors;
  
  // 调用计数器表 - 用于JIT编译决策
  static SymbolPropertyTable* _invoke_method_table;
  
  // 保护域缓存表 - 安全管理相关
  static ProtectionDomainCacheTable* _pd_cache_table;

public:
  // 类解析接口
  static Klass* resolve_or_fail(Symbol* class_name, 
                                Handle class_loader, 
                                Handle protection_domain, 
                                bool throw_error, TRAPS);
  
  // 类查找接口
  static Klass* find(Symbol* class_name,
                     Handle class_loader,
                     Handle protection_domain, TRAPS);
  
  // 类定义接口
  static InstanceKlass* define_instance_class(Symbol* class_name,
                                              Handle class_loader,
                                              instanceKlassHandle k, TRAPS);
};
```

### 3.3.2 知名类（Well-Known Classes）系统

JVM预定义了一系列核心类，这些类在JVM启动时就被加载：

```cpp
// src/hotspot/share/classfile/systemDictionary.hpp:106-224
#define WK_KLASSES_DO(do_klass) \
  /* 核心基础类 */ \
  do_klass(Object_klass,                java_lang_Object,           Pre) \
  do_klass(String_klass,                java_lang_String,           Pre) \
  do_klass(Class_klass,                 java_lang_Class,            Pre) \
  do_klass(Cloneable_klass,             java_lang_Cloneable,        Pre) \
  do_klass(ClassLoader_klass,           java_lang_ClassLoader,      Pre) \
  do_klass(Serializable_klass,          java_io_Serializable,       Pre) \
  do_klass(System_klass,                java_lang_System,           Pre) \
  \
  /* 异常处理类 */ \
  do_klass(Throwable_klass,             java_lang_Throwable,        Pre) \
  do_klass(Error_klass,                 java_lang_Error,            Pre) \
  do_klass(Exception_klass,             java_lang_Exception,        Pre) \
  do_klass(RuntimeException_klass,      java_lang_RuntimeException, Pre) \
  do_klass(ClassNotFoundException_klass, java_lang_ClassNotFoundException, Pre) \
  do_klass(NoClassDefFoundError_klass,  java_lang_NoClassDefFoundError, Pre) \
  \
  /* 引用类型 */ \
  do_klass(Reference_klass,             java_lang_ref_Reference,    Pre) \
  do_klass(SoftReference_klass,         java_lang_ref_SoftReference, Pre) \
  do_klass(WeakReference_klass,         java_lang_ref_WeakReference, Pre) \
  do_klass(FinalReference_klass,        java_lang_ref_FinalReference, Pre) \
  do_klass(PhantomReference_klass,      java_lang_ref_PhantomReference, Pre) \
  \
  /* 线程相关类 */ \
  do_klass(Thread_klass,                java_lang_Thread,           Pre) \
  do_klass(ThreadGroup_klass,           java_lang_ThreadGroup,      Pre) \
  \
  /* 反射相关类 */ \
  do_klass(reflect_Field_klass,         java_lang_reflect_Field,    Pre) \
  do_klass(reflect_Method_klass,        java_lang_reflect_Method,   Pre) \
  do_klass(reflect_Constructor_klass,   java_lang_reflect_Constructor, Pre) \
  \
  /* 方法句柄相关类 */ \
  do_klass(MethodHandle_klass,          java_lang_invoke_MethodHandle, Pre) \
  do_klass(VarHandle_klass,             java_lang_invoke_VarHandle, Pre) \
  do_klass(MemberName_klass,            java_lang_invoke_MemberName, Pre) \
  do_klass(MethodType_klass,            java_lang_invoke_MethodType, Pre) \
  \
  /* 装箱类型 */ \
  do_klass(Boolean_klass,               java_lang_Boolean,          Pre) \
  do_klass(Character_klass,             java_lang_Character,        Pre) \
  do_klass(Float_klass,                 java_lang_Float,            Pre) \
  do_klass(Double_klass,                java_lang_Double,           Pre) \
  do_klass(Byte_klass,                  java_lang_Byte,             Pre) \
  do_klass(Short_klass,                 java_lang_Short,            Pre) \
  do_klass(Integer_klass,               java_lang_Integer,          Pre) \
  do_klass(Long_klass,                  java_lang_Long,             Pre) \
  /*end*/
```

### 3.3.3 知名类访问接口

SystemDictionary为每个知名类提供了类型安全的访问接口：

```cpp
// 宏定义生成知名类访问方法
#define WK_KLASS_DECLARE(name, symbol, option) \
  static InstanceKlass* name() { \
    return check_klass_##option(_well_known_klasses[WK_KLASS_ENUM_NAME(name)]); \
  } \
  static InstanceKlass** name##_addr() { \
    return &SystemDictionary::_well_known_klasses[SystemDictionary::WK_KLASS_ENUM_NAME(name)]; \
  }

WK_KLASSES_DO(WK_KLASS_DECLARE);
#undef WK_KLASS_DECLARE

// 使用示例
InstanceKlass* object_klass = SystemDictionary::Object_klass();
InstanceKlass* string_klass = SystemDictionary::String_klass();
```

## 3.4 类加载流程深度分析

### 3.4.1 类解析主流程

```cpp
// src/hotspot/share/classfile/systemDictionary.cpp
Klass* SystemDictionary::resolve_or_fail(Symbol* class_name,
                                          Handle class_loader,
                                          Handle protection_domain,
                                          bool throw_error, TRAPS) {
  // 1. 检查是否已经加载
  Klass* klass = find(class_name, class_loader, protection_domain, THREAD);
  if (klass != NULL) {
    return klass;
  }
  
  // 2. 检查是否为数组类
  if (FieldType::is_array(class_name)) {
    return resolve_array_class_or_fail(class_name, class_loader, protection_domain, throw_error, THREAD);
  }
  
  // 3. 检查加载器约束
  check_loader_constraints(class_name, class_loader, THREAD);
  
  // 4. 执行实际的类加载
  HandleMark hm(THREAD);
  Handle h_loader(THREAD, class_loader());
  Handle h_prot(THREAD, protection_domain());
  
  klass = resolve_instance_class_or_null(class_name, h_loader, h_prot, THREAD);
  
  if (klass != NULL) {
    return klass;
  }
  
  // 5. 处理加载失败
  if (throw_error) {
    THROW_MSG_NULL(vmSymbols::java_lang_ClassNotFoundException(), class_name->as_C_string());
  }
  
  return NULL;
}
```

### 3.4.2 实例类加载详细流程

```cpp
// 实例类加载的核心逻辑
InstanceKlass* SystemDictionary::resolve_instance_class_or_null(Symbol* name,
                                                                Handle class_loader,
                                                                Handle protection_domain, TRAPS) {
  HandleMark hm(THREAD);
  
  // 1. 双重检查锁定模式
  {
    MutexLocker mu(SystemDictionary_lock, THREAD);
    InstanceKlass* check = find_class(name, class_loader);
    if (check != NULL) {
      return check;
    }
  }
  
  // 2. 创建占位符，防止循环加载
  PlaceholderEntry* placeholder;
  {
    MutexLocker mu(SystemDictionary_lock, THREAD);
    placeholder = placeholders()->find_and_add(p_index, p_hash, name, loader_data, PlaceholderTable::LOAD_INSTANCE, NULL, THREAD);
  }
  
  // 3. 调用类加载器加载类
  Handle s = java_lang_String::create_from_symbol(name, CHECK_NULL);
  JavaValue result(T_OBJECT);
  JavaCalls::call_virtual(&result,
                          class_loader,
                          KlassHandle(THREAD, SystemDictionary::ClassLoader_klass()),
                          vmSymbols::loadClass_name(),
                          vmSymbols::string_class_signature(),
                          s,
                          CHECK_NULL);
  
  // 4. 获取加载结果
  Handle h_obj(THREAD, (oop)result.get_jobject());
  if (h_obj.not_null()) {
    InstanceKlass* k = InstanceKlass::cast(java_lang_Class::as_Klass(h_obj()));
    
    // 5. 链接和初始化
    if (k->class_loader() == class_loader()) {
      // 验证、准备、解析
      k->link_class(CHECK_NULL);
      
      // 注册到SystemDictionary
      {
        MutexLocker mu(SystemDictionary_lock, THREAD);
        define_instance_class(k, CHECK_NULL);
      }
      
      return k;
    }
  }
  
  // 6. 清理占位符
  {
    MutexLocker mu(SystemDictionary_lock, THREAD);
    placeholders()->find_and_remove(p_index, p_hash, name, loader_data, PlaceholderTable::LOAD_INSTANCE, THREAD);
  }
  
  return NULL;
}
```

### 3.4.3 占位符机制

占位符机制用于防止类加载过程中的循环依赖：

```cpp
// src/hotspot/share/classfile/placeholderTable.hpp
class PlaceholderEntry : public HashtableEntry<Symbol*, mtClass> {
private:
  ClassLoaderData* _loader_data;   // 类加载器数据
  bool _havesupername;             // 是否有超类名
  bool _definer;                   // 是否为定义者
  Symbol* _supername;              // 超类名
  Thread* _definer_thread;         // 定义线程
  Klass* _instanceKlass;           // 实例类
  
public:
  void add_seen_thread(Thread* thread, PlaceholderTable::classloadAction action);
  void remove_seen_thread(Thread* thread, PlaceholderTable::classloadAction action);
  bool check_seen_thread(Thread* thread, PlaceholderTable::classloadAction action);
};
```

## 3.5 ClassFileParser：字节码解析引擎

### 3.5.1 ClassFileParser类结构

ClassFileParser负责将Java字节码解析为JVM内部的类表示：

```cpp
// src/hotspot/share/classfile/classFileParser.hpp
class ClassFileParser VALUE_OBJ_CLASS_SPEC {
private:
  const ClassFileStream* _stream;           // 字节码流
  const Symbol* _requested_name;            // 请求的类名
  ClassLoaderData* _loader_data;            // 类加载器数据
  const bool _is_hidden;                    // 是否为隐藏类
  const bool _can_access_vm_annotations;    // 是否可访问VM注解
  
  // 解析状态
  int _major_version;                       // 主版本号
  int _minor_version;                       // 次版本号
  const ConstantPool* _cp;                  // 常量池
  Array<u2>* _fields;                       // 字段数组
  Array<Method*>* _methods;                 // 方法数组
  Array<u2>* _inner_classes;                // 内部类
  
public:
  // 主解析方法
  InstanceKlass* create_instance_klass(bool changed_by_loadhook, TRAPS);
  
private:
  // 各个解析阶段
  void parse_constant_pool(TRAPS);
  void parse_interfaces(TRAPS);
  void parse_fields(TRAPS);
  void parse_methods(TRAPS);
  void parse_classfile_attributes(TRAPS);
  
  // 验证方法
  void verify_constantvalue(TRAPS);
  void verify_legal_class_modifiers(jint flags, TRAPS);
  void verify_legal_field_modifiers(jint flags, bool is_interface, TRAPS);
  void verify_legal_method_modifiers(jint flags, bool is_interface, Symbol* name, TRAPS);
};
```

### 3.5.2 常量池解析

常量池是类文件的核心数据结构，包含了类的所有符号引用：

```cpp
// 常量池解析主流程
void ClassFileParser::parse_constant_pool(TRAPS) {
  ClassFileStream* cfs = stream();
  
  // 读取常量池大小
  u2 length = cfs->get_u2_fast();
  guarantee(length >= 1, "Illegal constant pool size %u in class file %s", length, CHECK);
  
  // 创建常量池对象
  ConstantPool* cp = ConstantPool::allocate(_loader_data, length, CHECK);
  _cp = cp;
  
  // 解析每个常量池项
  for (int index = 1; index < length; index++) {
    u1 tag = cfs->get_u1_fast();
    
    switch (tag) {
      case JVM_CONSTANT_Class: {
        u2 name_index = cfs->get_u2_fast();
        cp->klass_index_at_put(index, name_index);
        break;
      }
      
      case JVM_CONSTANT_Fieldref: {
        u2 class_index = cfs->get_u2_fast();
        u2 name_and_type_index = cfs->get_u2_fast();
        cp->field_at_put(index, class_index, name_and_type_index);
        break;
      }
      
      case JVM_CONSTANT_Methodref: {
        u2 class_index = cfs->get_u2_fast();
        u2 name_and_type_index = cfs->get_u2_fast();
        cp->method_at_put(index, class_index, name_and_type_index);
        break;
      }
      
      case JVM_CONSTANT_String: {
        u2 string_index = cfs->get_u2_fast();
        cp->string_index_at_put(index, string_index);
        break;
      }
      
      case JVM_CONSTANT_Utf8: {
        u2 utf8_length = cfs->get_u2_fast();
        u1* utf8_buffer = cfs->get_u1_buffer();
        assert(utf8_buffer != NULL, "null utf8 buffer");
        
        // 验证UTF-8编码
        verify_legal_utf8((unsigned char*)utf8_buffer, utf8_length, CHECK);
        
        // 创建Symbol
        Symbol* sym = SymbolTable::new_symbol((char*)utf8_buffer, utf8_length, CHECK);
        cp->symbol_at_put(index, sym);
        break;
      }
      
      // ... 其他常量类型
    }
  }
}
```

### 3.5.3 方法解析

方法解析是类文件解析中最复杂的部分：

```cpp
// 方法解析主流程
void ClassFileParser::parse_methods(TRAPS) {
  ClassFileStream* cfs = stream();
  
  // 读取方法数量
  u2 length = cfs->get_u2_fast();
  
  // 创建方法数组
  Array<Method*>* methods = MetadataFactory::new_array<Method*>(_loader_data, length, NULL, CHECK);
  _methods = methods;
  
  // 解析每个方法
  for (int index = 0; index < length; index++) {
    Method* method = parse_method(CHECK);
    methods->at_put(index, method);
    
    // 检查特殊方法
    if (method->name() == vmSymbols::object_initializer_name()) {
      _has_vanilla_constructor = true;
    }
    
    if (method->name() == vmSymbols::class_initializer_name()) {
      _has_static_initializer = true;
    }
  }
}

// 单个方法解析
Method* ClassFileParser::parse_method(TRAPS) {
  ClassFileStream* cfs = stream();
  
  // 读取方法基本信息
  u2 access_flags = cfs->get_u2_fast();
  u2 name_index = cfs->get_u2_fast();
  u2 signature_index = cfs->get_u2_fast();
  
  // 验证方法修饰符
  verify_legal_method_modifiers(access_flags, _access_flags.is_interface(), 
                                _cp->symbol_at(name_index), CHECK_NULL);
  
  // 读取属性数量
  u2 attributes_count = cfs->get_u2_fast();
  
  // 解析方法属性
  u2 code_length = 0;
  u1* code_start = 0;
  
  for (int i = 0; i < attributes_count; i++) {
    u2 attribute_name_index = cfs->get_u2_fast();
    u4 attribute_length = cfs->get_u4_fast();
    
    Symbol* attribute_name = _cp->symbol_at(attribute_name_index);
    
    if (attribute_name == vmSymbols::tag_code()) {
      // 解析Code属性
      parse_code_attribute(cfs, attribute_length, &code_start, &code_length, CHECK_NULL);
    } else if (attribute_name == vmSymbols::tag_exceptions()) {
      // 解析Exceptions属性
      parse_exceptions_attribute(cfs, attribute_length, CHECK_NULL);
    } else if (attribute_name == vmSymbols::tag_signature()) {
      // 解析泛型签名
      parse_signature_attribute(cfs, attribute_length, CHECK_NULL);
    }
    // ... 其他属性
  }
  
  // 创建Method对象
  Method* method = Method::allocate(_loader_data,
                                    code_length,
                                    access_flags,
                                    _linenumber_table,
                                    _localvariable_table,
                                    _localvariable_type_table,
                                    _exception_table,
                                    _generic_signature_index,
                                    _method_parameters,
                                    _method_annotations,
                                    _method_parameter_annotations,
                                    _method_default_annotations,
                                    _method_type_annotations,
                                    CHECK_NULL);
  
  // 设置方法信息
  method->set_constants(_cp);
  method->set_name_index(name_index);
  method->set_signature_index(signature_index);
  
  if (code_start != 0) {
    memcpy(method->code_base(), code_start, code_length);
  }
  
  return method;
}
```

## 3.6 InstanceKlass：运行时类表示

### 3.6.1 InstanceKlass类结构

InstanceKlass是JVM中类的运行时表示，包含了类的所有元数据：

```cpp
// src/hotspot/share/oops/instanceKlass.hpp
class InstanceKlass: public Klass {
private:
  // 类的基本信息
  u2 _minor_version;                    // 次版本号
  u2 _major_version;                    // 主版本号
  Symbol* _source_file_name;            // 源文件名
  Symbol* _source_debug_extension;      // 调试扩展信息
  
  // 类的结构信息
  Array<u2>* _inner_classes;            // 内部类
  Array<jushort>* _fields;              // 字段数组
  Array<Method*>* _methods;             // 方法数组
  Array<Method*>* _default_methods;     // 默认方法
  Array<Klass*>* _local_interfaces;     // 本地接口
  Array<Klass*>* _transitive_interfaces; // 传递接口
  
  // 常量池和注解
  ConstantPool* _constants;             // 常量池
  AnnotationArray* _class_annotations;  // 类注解
  AnnotationArray* _class_type_annotations; // 类型注解
  Array<AnnotationArray*>* _fields_annotations; // 字段注解
  Array<AnnotationArray*>* _fields_type_annotations; // 字段类型注解
  
  // 虚表和接口表
  klassVtable _vtable;                  // 虚方法表
  klassItable _itable;                  // 接口方法表
  
  // 类状态
  u1 _init_state;                       // 初始化状态
  u1 _reference_type;                   // 引用类型
  
  // JIT编译相关
  nmethod* _osr_nmethods_head;          // OSR编译方法链表头
  BreakpointInfo* _breakpoints;         // 断点信息
  
  // 类加载器数据
  ClassLoaderData* _class_loader_data;  // 类加载器数据
  
public:
  // 类状态枚举
  enum ClassState {
    allocated,                          // 已分配
    loaded,                             // 已加载
    linked,                             // 已链接
    being_initialized,                  // 正在初始化
    fully_initialized,                  // 完全初始化
    initialization_error                // 初始化错误
  };
  
  // 创建实例
  instanceOop allocate_instance(TRAPS);
  
  // 类链接
  void link_class(TRAPS);
  void link_class_impl(bool throw_verifyerror, TRAPS);
  
  // 类初始化
  void initialize(TRAPS);
  void initialize_impl(TRAPS);
  
  // 方法查找
  Method* find_method(Symbol* name, Symbol* signature) const;
  Method* find_instance_method(Symbol* name, Symbol* signature) const;
  Method* find_static_method(Symbol* name, Symbol* signature) const;
};
```

### 3.6.2 类链接过程

类链接包括验证、准备和解析三个阶段：

```cpp
// 类链接主流程
void InstanceKlass::link_class_impl(bool throw_verifyerror, TRAPS) {
  // 1. 验证阶段
  if (!is_linked()) {
    if (!is_rewritten()) {
      {
        bool verify_ok = verify_code(this, throw_verifyerror, THREAD);
        if (!verify_ok) {
          return; // 验证失败
        }
      }
      
      // 重写字节码
      rewrite_class(CHECK);
    }
    
    // 2. 准备阶段 - 分配静态变量内存并设置默认值
    if (super() != NULL && !super()->is_linked()) {
      super()->link_class(CHECK);
    }
    
    // 链接接口
    for (int i = 0; i < local_interfaces()->length(); i++) {
      local_interfaces()->at(i)->link_class(CHECK);
    }
    
    // 3. 解析阶段 - 构建虚表和接口表
    vtable().initialize_vtable(true, CHECK);
    itable().initialize_itable(true, CHECK);
    
    // 设置链接状态
    set_init_state(linked);
  }
}
```

### 3.6.3 类初始化过程

类初始化是执行类的静态初始化代码：

```cpp
// 类初始化主流程
void InstanceKlass::initialize_impl(TRAPS) {
  // 检查初始化状态
  if (is_initialized()) return;
  
  // 获取初始化锁
  Handle h_init_lock(THREAD, init_lock());
  ObjectLocker ol(h_init_lock, THREAD, h_init_lock.not_null());
  
  // 双重检查
  if (is_initialized()) return;
  
  // 检查是否正在被其他线程初始化
  if (is_being_initialized() && _init_thread == THREAD) {
    THROW_MSG(vmSymbols::java_lang_ClassCircularityError(), external_name());
  }
  
  // 等待其他线程完成初始化
  while (is_being_initialized() && _init_thread != THREAD) {
    ol.waitUninterruptibly(CHECK);
  }
  
  if (is_initialized()) return;
  
  // 开始初始化
  set_init_state(being_initialized);
  set_init_thread(THREAD);
  
  // 初始化超类
  if (super() != NULL && !super()->is_initialized()) {
    super()->initialize(CHECK);
  }
  
  // 执行类初始化方法 <clinit>
  Method* clinit = find_method(vmSymbols::class_initializer_name(),
                               vmSymbols::void_method_signature());
  if (clinit != NULL) {
    JavaCallArguments args;
    JavaCalls::call_static(&result, this, clinit, &args, CHECK);
  }
  
  // 设置初始化完成状态
  set_init_state(fully_initialized);
  set_init_thread(NULL);
  
  // 通知等待的线程
  ol.notify_all(CHECK);
}
```

## 3.7 模块系统集成

### 3.7.1 Java 9+模块系统

Java 9引入了模块系统，对类加载产生了重大影响：

```cpp
// src/hotspot/share/classfile/modules.hpp
class Modules : AllStatic {
public:
  // 定义模块
  static void define_module(jobject module, jboolean is_open, jstring version,
                           jstring location, jobjectArray packages, TRAPS);
  
  // 添加导出
  static void add_module_exports(jobject from_module, jstring package,
                                jobject to_module, TRAPS);
  
  // 添加读取关系
  static void add_reads_module(jobject from_module, jobject to_module, TRAPS);
  
  // 检查包访问权限
  static jboolean can_read_module(jobject asking_module, jobject target_module);
  
  // 检查包导出权限
  static jboolean is_exported_to_module(jobject from_module, jstring package,
                                       jobject to_module);
};
```

### 3.7.2 模块化类加载器

```cpp
// 模块化环境下的类加载
class ModuleEntry : public HashtableEntry<Symbol*, mtModule> {
private:
  OopHandle _module;                    // 对应的java.lang.Module对象
  Symbol* _version;                     // 模块版本
  Symbol* _location;                    // 模块位置
  bool _can_read_all_unnamed;           // 是否可读取所有未命名模块
  bool _has_default_read_edges;         // 是否有默认读取边
  
public:
  // 检查是否可以读取指定模块
  bool can_read(ModuleEntry* m) const;
  
  // 添加读取关系
  void add_read(ModuleEntry* m);
  
  // 检查包是否导出
  bool is_exported(Symbol* package, ModuleEntry* to_module) const;
};
```

## 3.8 基于标准配置的类加载性能分析

### 3.8.1 类加载时序分析

在标准测试条件下，类加载的典型时序：

```cpp
// 类加载性能统计（基于8GB堆配置）
class ClassLoadingStats {
public:
  // 启动时类加载统计
  static void print_startup_stats() {
    log_info(class, load)("Bootstrap classes loaded: %d", bootstrap_classes_loaded);
    log_info(class, load)("Platform classes loaded: %d", platform_classes_loaded);
    log_info(class, load)("Application classes loaded: %d", application_classes_loaded);
    log_info(class, load)("Total loading time: %d ms", total_loading_time_ms);
  }
  
private:
  static int bootstrap_classes_loaded;    // ~1500个核心类
  static int platform_classes_loaded;     // ~500个平台类
  static int application_classes_loaded;  // 应用相关
  static int total_loading_time_ms;       // 总加载时间：~200ms
};
```

### 3.8.2 元空间使用分析

类元数据存储在元空间中，在标准配置下的使用情况：

```cpp
// 元空间使用统计
class MetaspaceUsage {
public:
  // 类元数据大小估算
  static size_t estimate_class_metadata_size(InstanceKlass* ik) {
    size_t size = 0;
    
    // InstanceKlass对象本身
    size += sizeof(InstanceKlass);
    
    // 常量池
    size += ik->constants()->size() * sizeof(ConstantPool);
    
    // 方法数组
    size += ik->methods()->length() * sizeof(Method*);
    for (int i = 0; i < ik->methods()->length(); i++) {
      size += ik->methods()->at(i)->size();
    }
    
    // 字段数组
    size += ik->fields()->length() * sizeof(u2);
    
    // 虚表和接口表
    size += ik->vtable_length() * sizeof(void*);
    size += ik->itable_length() * sizeof(void*);
    
    return size;
  }
};

// 典型类的元空间使用量：
// - java.lang.Object: ~2KB
// - java.lang.String: ~8KB  
// - 普通应用类: ~5-20KB
// - 总元空间使用: ~50-100MB（启动后）
```

### 3.8.3 类加载器性能优化

```cpp
// 类加载器缓存优化
class ClassLoaderCache {
private:
  // 类名到Klass的映射缓存
  static Dictionary* _system_dictionary_cache;
  
  // 包名到模块的映射缓存
  static PackageToModuleHashtable* _package_to_module_cache;
  
public:
  // 快速查找已加载的类
  static Klass* find_loaded_class(Symbol* class_name, Handle class_loader) {
    // 首先检查SystemDictionary缓存
    Klass* k = SystemDictionary::find(class_name, class_loader, Handle(), THREAD);
    if (k != NULL) {
      return k;
    }
    
    // 检查类加载器本地缓存
    return check_loader_cache(class_name, class_loader);
  }
};
```

## 3.9 类加载安全机制

### 3.9.1 双亲委派模型

```cpp
// 双亲委派模型的实现
class ParentDelegationModel {
public:
  static Klass* load_class_with_delegation(Symbol* class_name, 
                                           Handle class_loader, TRAPS) {
    // 1. 检查是否已经加载
    Klass* loaded_class = find_loaded_class(class_name, class_loader);
    if (loaded_class != NULL) {
      return loaded_class;
    }
    
    // 2. 委派给父类加载器
    Handle parent_loader = get_parent_class_loader(class_loader);
    if (parent_loader.not_null()) {
      Klass* parent_result = load_class_with_delegation(class_name, parent_loader, THREAD);
      if (parent_result != NULL) {
        return parent_result;
      }
    }
    
    // 3. 父类加载器无法加载，由当前加载器尝试加载
    return load_class_locally(class_name, class_loader, THREAD);
  }
};
```

### 3.9.2 类加载器约束

```cpp
// 类加载器约束表
class LoaderConstraintTable : public Hashtable<Klass*, mtClass> {
private:
  enum Constants {
    _loader_constraint_size = 107,      // 哈希表大小
    _nof_buckets            = 1009      // 桶数量
  };
  
public:
  // 添加约束
  void add_entry(Symbol* name, Klass* klass1, Handle class_loader1,
                 Klass* klass2, Handle class_loader2);
  
  // 检查约束
  void check_signature_loaders(Symbol* signature, Handle loader1, 
                              Handle loader2, bool is_method, TRAPS);
  
  // 验证约束一致性
  void verify(Dictionary* dictionary, PlaceholderTable* placeholders);
};
```

## 3.10 总结

类加载系统是JVM的核心基础设施，通过精密的设计实现了Java的动态性和安全性。在标准测试条件下：

1. **SystemDictionary设计**：提供了高效的类注册表和知名类管理机制
2. **ClassFileParser实现**：完整解析Java字节码为运行时类表示
3. **InstanceKlass结构**：包含了类的完整元数据和运行时状态
4. **模块系统集成**：支持Java 9+的模块化特性
5. **性能优化**：通过缓存、约束检查等机制提升加载效率
6. **安全保障**：双亲委派模型和约束机制确保类加载安全

类加载系统的源码实现体现了JVM设计的复杂性和精妙性，为Java程序的动态特性提供了坚实的基础。在8GB堆配置下，类加载系统能够高效地管理数千个类的元数据，为应用程序提供快速的类访问和方法调用能力。

下一章我们将深入分析JIT编译器的源码实现，了解Java代码如何从解释执行转换为高度优化的本地代码。