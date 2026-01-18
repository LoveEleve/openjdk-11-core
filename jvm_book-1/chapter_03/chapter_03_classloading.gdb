# 第03章：类加载机制 - GDB调试脚本
# 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 配置

# 设置输出日志
set logging file logs/chapter_03_classloading.log
set logging on

# 基础设置
set confirm off
set pagination off
set print pretty on
set print array on

echo ========================================\n
echo 第03章：类加载机制 GDB调试验证\n
echo 配置: -Xms=Xmx=8GB, G1GC\n
echo ========================================\n

# ============================================
# 1. 类加载器层次结构验证
# ============================================

# 系统字典相关断点
break SystemDictionary::resolve_or_fail
commands
  silent
  printf "\n=== 类加载请求 ===\n"
  printf "类名: %s\n", class_name->as_C_string()
  printf "类加载器: %p\n", class_loader()
  printf "保护域: %p\n", protection_domain()
  printf "线程: %p\n", Thread::current()
  continue
end

# Bootstrap类加载器
break ClassLoader::load_class
commands
  silent
  printf "\n=== Bootstrap类加载器 ===\n"
  printf "加载类: %s\n", name->as_C_string()
  printf "搜索路径: %s\n", _first_entry ? _first_entry->name() : "NULL"
  continue
end

# 类字典查找
break SystemDictionary::find_class
commands
  silent
  printf "\n=== 类字典查找 ===\n"
  printf "查找类: %s\n", class_name->as_C_string()
  printf "类加载器: %p\n", class_loader()
  continue
end

# ============================================
# 2. 类加载五阶段验证
# ============================================

# 2.1 加载阶段 - 字节码解析
break ClassFileParser::parse_stream
commands
  silent
  printf "\n=== 加载阶段：字节码解析 ===\n"
  printf "类名: %s\n", _class_name->as_C_string()
  printf "类加载器数据: %p\n", _loader_data
  printf "字节码流: %p\n", _stream
  printf "字节码长度: %d\n", _stream->length()
  continue
end

# Klass对象创建
break InstanceKlass::allocate_instance_klass
commands
  silent
  printf "\n=== Klass对象创建 ===\n"
  printf "vtable大小: %d\n", parser.vtable_size()
  printf "itable大小: %d\n", parser.itable_size()
  printf "实例大小: %d\n", size
  continue
end

# 2.2 验证阶段
break Verifier::verify
commands
  silent
  printf "\n=== 验证阶段 ===\n"
  printf "验证类: %s\n", klass->name()->as_C_string()
  printf "验证模式: %d\n", mode
  continue
end

# 字节码验证
break StackMapTable::match_stackmap
commands
  silent
  printf "\n=== 字节码验证：栈映射表 ===\n"
  printf "目标偏移: %d\n", target
  printf "更新模式: %s\n", update ? "true" : "false"
  continue
end

# 2.3 准备阶段 - 静态字段初始化
break InstanceKlass::initialize_static_field
commands
  silent
  printf "\n=== 准备阶段：静态字段初始化 ===\n"
  printf "字段名: %s\n", fd->name()->as_C_string()
  printf "字段类型: %d\n", fd->field_type()
  printf "字段偏移: %d\n", fd->offset()
  continue
end

# 2.4 解析阶段 - 符号引用解析
break LinkResolver::resolve_method
commands
  silent
  printf "\n=== 解析阶段：方法解析 ===\n"
  printf "方法名: %s\n", method_name->as_C_string()
  printf "方法签名: %s\n", method_signature->as_C_string()
  printf "当前类: %s\n", current_klass->name()->as_C_string()
  continue
end

# 2.5 初始化阶段
break InstanceKlass::initialize_impl
commands
  silent
  printf "\n=== 初始化阶段 ===\n"
  printf "初始化类: %s\n", this->name()->as_C_string()
  printf "初始化状态: %d\n", this->_init_state
  printf "线程: %p\n", Thread::current()
  continue
end

# <clinit>方法执行
break Method::invoke
commands
  silent
  if $_streq(this->name()->as_C_string(), "<clinit>")
    printf "\n=== 执行类初始化方法 <clinit> ===\n"
    printf "类: %s\n", this->method_holder()->name()->as_C_string()
    printf "方法: %s\n", this->name()->as_C_string()
  end
  continue
end

# ============================================
# 3. Metaspace内存管理验证
# ============================================

# ClassLoaderData创建
break ClassLoaderData::ClassLoaderData
commands
  silent
  printf "\n=== ClassLoaderData创建 ===\n"
  printf "ClassLoaderData: %p\n", this
  printf "类加载器: %p\n", h_class_loader()
  printf "是否匿名: %s\n", is_anonymous ? "true" : "false"
  continue
end

# Metaspace分配
break Metaspace::allocate
commands
  silent
  printf "\n=== Metaspace内存分配 ===\n"
  printf "分配大小: %ld words\n", word_size
  printf "对象类型: %d\n", type
  printf "ClassLoaderData: %p\n", loader_data
  continue
end

# 类卸载检查
break ClassLoaderData::is_alive
commands
  silent
  printf "\n=== 类卸载检查 ===\n"
  printf "ClassLoaderData: %p\n", this
  printf "keep_alive: %s\n", keep_alive() ? "true" : "false"
  printf "是否null类加载器: %s\n", is_the_null_class_loader_data() ? "true" : "false"
  continue
end

# ============================================
# 4. 自定义GDB命令
# ============================================

# 显示类加载器层次结构
define show_classloader_hierarchy
  printf "\n=== 类加载器层次结构 ===\n"
  
  # Bootstrap ClassLoader
  printf "Bootstrap ClassLoader (C++): %p\n", &ClassLoader::_first_entry
  
  # Platform ClassLoader
  if SystemDictionary::_java_platform_loader != 0
    printf "Platform ClassLoader: %p\n", SystemDictionary::_java_platform_loader
  end
  
  # System ClassLoader  
  if SystemDictionary::_java_system_loader != 0
    printf "System ClassLoader: %p\n", SystemDictionary::_java_system_loader
  end
  
  printf "===========================\n"
end

# 显示Metaspace使用情况
define show_metaspace_usage
  printf "\n=== Metaspace使用情况 ===\n"
  printf "已提交内存: %ld bytes\n", MetaspaceAux::committed_bytes()
  printf "已使用内存: %ld bytes\n", MetaspaceAux::used_bytes()
  printf "空闲内存: %ld bytes\n", MetaspaceAux::free_bytes()
  printf "保留内存: %ld bytes\n", MetaspaceAux::reserved_bytes()
  printf "========================\n"
end

# 显示类字典统计
define show_class_dictionary_stats
  printf "\n=== 类字典统计 ===\n"
  printf "已加载类数量: %d\n", SystemDictionary::number_of_classes()
  printf "字典大小: %d\n", SystemDictionary::_dictionary->table_size()
  printf "占位符数量: %d\n", SystemDictionary::_placeholders->number_of_entries()
  printf "==================\n"
end

# 显示类加载性能统计
define show_classloading_stats
  printf "\n=== 类加载性能统计 ===\n"
  printf "类加载时间: %ld ms\n", ClassLoader::class_init_time()
  printf "类验证时间: %ld ms\n", ClassLoader::class_verify_time()
  printf "类链接时间: %ld ms\n", ClassLoader::class_link_time()
  printf "======================\n"
end

# 监控类加载过程
define monitor_class_loading
  printf "\n开始监控类加载过程...\n"
  
  # 设置计数器
  set $class_load_count = 0
  set $metaspace_before = MetaspaceAux::used_bytes()
  
  # 显示初始状态
  show_metaspace_usage
  show_class_dictionary_stats
  
  printf "监控已启动，使用 'continue' 继续执行\n"
end

# ============================================
# 5. 启动监控
# ============================================

echo \n开始类加载机制验证...\n
echo 使用以下命令进行交互式调试:\n
echo - show_classloader_hierarchy: 显示类加载器层次\n
echo - show_metaspace_usage: 显示Metaspace使用情况\n
echo - show_class_dictionary_stats: 显示类字典统计\n
echo - monitor_class_loading: 开始监控类加载\n
echo \n

# 运行程序
run

# 程序结束后显示最终统计
echo \n========================================\n
echo 类加载验证完成\n
echo ========================================\n

show_classloader_hierarchy
show_metaspace_usage
show_class_dictionary_stats

# 关闭日志
set logging off

quit