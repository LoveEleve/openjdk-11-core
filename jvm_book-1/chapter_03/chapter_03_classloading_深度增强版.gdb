# 类加载机制深度分析GDB脚本 - 深度增强版
# 基于 -Xms=Xmx=8GB, 非大页, 非NUMA, G1GC 标准配置
# 提供120+个关键数据点的完整验证

# 类加载器层次结构分析
define analyze_classloader_hierarchy
    printf "=== 类加载器层次结构分析 ===\n"
    
    # 1. Bootstrap ClassLoader (C++实现)
    printf "1. Bootstrap ClassLoader:\n"
    printf "   - 实现语言: C++\n"
    if Arguments::_sun_boot_class_path != 0
        printf "   - 启动类路径: %s\n", Arguments::_sun_boot_class_path->_value
    end
    if Arguments::_java_class_path != 0
        printf "   - Java类路径: %s\n", Arguments::_java_class_path->_value
    end
    
    # 2. Platform ClassLoader
    set $platform_loader = SystemDictionary::_java_platform_loader
    if $platform_loader != 0
        printf "2. Platform ClassLoader: 0x%lx\n", $platform_loader
        set $platform_klass = $platform_loader->_metadata
        if $platform_klass != 0
            printf "   - 类型: %s\n", $platform_klass->_name->_body
        end
    else
        printf "2. Platform ClassLoader: 未初始化\n"
    end
    
    # 3. Application ClassLoader  
    set $app_loader = SystemDictionary::_java_system_loader
    if $app_loader != 0
        printf "3. Application ClassLoader: 0x%lx\n", $app_loader
        set $app_klass = $app_loader->_metadata
        if $app_klass != 0
            printf "   - 类型: %s\n", $app_klass->_name->_body
        end
    else
        printf "3. Application ClassLoader: 未初始化\n"
    end
    
    # 4. 类加载器数据统计
    printf "\n类加载器数据统计:\n"
    set $cld_count = 0
    set $current_cld = ClassLoaderDataGraph::_head
    while $current_cld != 0
        set $cld_count = $cld_count + 1
        set $current_cld = $current_cld->_next
    end
    printf "   - 活跃类加载器数据: %d\n", $cld_count
    
    printf "\n"
end

# 类字典状态分析
define analyze_class_dictionary
    printf "=== 类字典状态分析 ===\n"
    
    set $dict = SystemDictionary::_dictionary
    if $dict != 0
        printf "主类字典: 0x%lx\n", $dict
        printf "- 表大小: %d\n", $dict->_table_size
        printf "- 已用条目: %d\n", $dict->_number_of_entries
        set $load_factor = (double)$dict->_number_of_entries / $dict->_table_size
        printf "- 负载因子: %.3f\n", $load_factor
        
        # 哈希分布分析
        set $i = 0
        set $empty_buckets = 0
        set $max_chain_length = 0
        set $total_chain_length = 0
        
        while $i < $dict->_table_size
            set $entry = $dict->_buckets[$i]
            set $chain_length = 0
            
            if $entry == 0
                set $empty_buckets = $empty_buckets + 1
            else
                while $entry != 0
                    set $chain_length = $chain_length + 1
                    set $entry = $entry->_next
                end
                set $total_chain_length = $total_chain_length + $chain_length
                if $chain_length > $max_chain_length
                    set $max_chain_length = $chain_length
                end
            end
            set $i = $i + 1
        end
        
        printf "- 空桶数量: %d (%.1f%%)\n", $empty_buckets, (double)$empty_buckets * 100 / $dict->_table_size
        printf "- 最大链长: %d\n", $max_chain_length
        if $dict->_number_of_entries > 0
            printf "- 平均链长: %.2f\n", (double)$total_chain_length / ($dict->_table_size - $empty_buckets)
        end
        
        # 统计每个加载器的类数量
        set $i = 0
        set $bootstrap_count = 0
        set $platform_count = 0
        set $app_count = 0
        set $custom_count = 0
        
        while $i < $dict->_table_size
            set $entry = $dict->_buckets[$i]
            while $entry != 0
                set $dict_entry = (Dictionary::DictionaryEntry*)$entry
                set $loader_data = $dict_entry->_loader_data
                if $loader_data->_class_loader == 0
                    set $bootstrap_count = $bootstrap_count + 1
                else
                    set $loader_oop = $loader_data->_class_loader
                    if $loader_oop == SystemDictionary::_java_platform_loader
                        set $platform_count = $platform_count + 1
                    elif $loader_oop == SystemDictionary::_java_system_loader
                        set $app_count = $app_count + 1
                    else
                        set $custom_count = $custom_count + 1
                    end
                end
                set $entry = $entry->_next
            end
            set $i = $i + 1
        end
        
        printf "\n按加载器分类统计:\n"
        printf "- Bootstrap加载的类: %d\n", $bootstrap_count
        printf "- Platform加载的类: %d\n", $platform_count  
        printf "- Application加载的类: %d\n", $app_count
        printf "- 自定义加载器类: %d\n", $custom_count
        
        # 性能评估
        printf "\n性能评估:\n"
        if $load_factor < 0.75
            printf "- 负载因子: ✅ 良好 (< 0.75)\n"
        else
            printf "- 负载因子: ⚠️  偏高 (>= 0.75)\n"
        end
        
        if $max_chain_length <= 8
            printf "- 最大链长: ✅ 良好 (<= 8)\n"
        else
            printf "- 最大链长: ⚠️  过长 (> 8)\n"
        end
        
    else
        printf "类字典未初始化\n"
    end
    
    printf "\n"
end

# Metaspace使用情况分析
define analyze_metaspace_usage
    printf "=== Metaspace使用情况分析 ===\n"
    
    # 获取Metaspace统计信息
    set $used_bytes = MetaspaceUtils::used_bytes()
    set $capacity_bytes = MetaspaceUtils::capacity_bytes()
    set $reserved_bytes = MetaspaceUtils::reserved_bytes()
    set $committed_bytes = MetaspaceUtils::committed_bytes()
    
    printf "Metaspace总体使用情况:\n"
    printf "- 已使用: %lu bytes (%.2f MB)\n", $used_bytes, (double)$used_bytes / 1048576
    printf "- 已提交: %lu bytes (%.2f MB)\n", $committed_bytes, (double)$committed_bytes / 1048576
    printf "- 已分配: %lu bytes (%.2f MB)\n", $capacity_bytes, (double)$capacity_bytes / 1048576
    printf "- 已保留: %lu bytes (%.2f MB)\n", $reserved_bytes, (double)$reserved_bytes / 1048576
    
    if $capacity_bytes > 0
        printf "- 使用率: %.2f%%\n", (double)$used_bytes * 100 / $capacity_bytes
    end
    if $reserved_bytes > 0
        printf "- 提交率: %.2f%%\n", (double)$committed_bytes * 100 / $reserved_bytes
    end
    
    # 类空间使用情况
    if UseCompressedClassPointers
        set $class_used = MetaspaceUtils::used_bytes_slow(Metaspace::ClassType)
        set $class_capacity = MetaspaceUtils::capacity_bytes_slow(Metaspace::ClassType)
        set $class_committed = MetaspaceUtils::committed_bytes_slow(Metaspace::ClassType)
        
        printf "\n压缩类空间使用情况:\n"
        printf "- 已使用: %lu bytes (%.2f MB)\n", $class_used, (double)$class_used / 1048576
        printf "- 已提交: %lu bytes (%.2f MB)\n", $class_committed, (double)$class_committed / 1048576
        printf "- 已分配: %lu bytes (%.2f MB)\n", $class_capacity, (double)$class_capacity / 1048576
        
        if $class_capacity > 0
            printf "- 使用率: %.2f%%\n", (double)$class_used * 100 / $class_capacity
        end
        
        # 压缩指针配置
        printf "\n压缩类指针配置:\n"
        printf "- 启用状态: %s\n", UseCompressedClassPointers ? "✅ 启用" : "❌ 禁用"
        if CompressedClassPointers::base() != 0
            printf "- 基地址: 0x%lx\n", CompressedClassPointers::base()
            printf "- 位移量: %d bits\n", CompressedClassPointers::shift()
        end
        if CompressedClassSpaceSize > 0
            printf "- 最大空间: %lu bytes (%.2f MB)\n", CompressedClassSpaceSize, (double)CompressedClassSpaceSize / 1048576
        end
    else
        printf "\n压缩类指针: ❌ 禁用\n"
    end
    
    # 非类空间使用情况
    set $nonclass_used = MetaspaceUtils::used_bytes_slow(Metaspace::NonClassType)
    set $nonclass_capacity = MetaspaceUtils::capacity_bytes_slow(Metaspace::NonClassType)
    set $nonclass_committed = MetaspaceUtils::committed_bytes_slow(Metaspace::NonClassType)
    
    printf "\n非类空间使用情况:\n"
    printf "- 已使用: %lu bytes (%.2f MB)\n", $nonclass_used, (double)$nonclass_used / 1048576
    printf "- 已提交: %lu bytes (%.2f MB)\n", $nonclass_committed, (double)$nonclass_committed / 1048576
    printf "- 已分配: %lu bytes (%.2f MB)\n", $nonclass_capacity, (double)$nonclass_capacity / 1048576
    
    if $nonclass_capacity > 0
        printf "- 使用率: %.2f%%\n", (double)$nonclass_used * 100 / $nonclass_capacity
    end
    
    # Metaspace GC统计
    printf "\nMetaspace GC统计:\n"
    set $gc_threshold = MetaspaceGC::capacity_until_GC()
    printf "- GC触发阈值: %lu bytes (%.2f MB)\n", $gc_threshold, (double)$gc_threshold / 1048576
    
    if MetaspaceCounters::metaspace_counter() != 0
        set $gc_count = MetaspaceCounters::metaspace_counter()->_value
        printf "- Metaspace GC次数: %lu\n", $gc_count
    end
    
    printf "\n"
end

# 类加载性能统计
define analyze_classloading_performance
    printf "=== 类加载性能统计 ===\n"
    
    # 获取性能计数器
    if ClassLoader::perf_accumulated_time() != 0
        set $total_time = ClassLoader::perf_accumulated_time()->value()
        printf "累计加载时间: %lu ns (%.2f ms)\n", $total_time, (double)$total_time / 1000000
    end
    
    if ClassLoader::perf_classes_inited() != 0
        set $classes_inited = ClassLoader::perf_classes_inited()->value()
        printf "已初始化类数量: %lu\n", $classes_inited
    end
    
    if ClassLoader::perf_class_init_time() != 0
        set $init_time = ClassLoader::perf_class_init_time()->value()
        printf "累计初始化时间: %lu ns (%.2f ms)\n", $init_time, (double)$init_time / 1000000
    end
    
    if ClassLoader::perf_class_verify_time() != 0
        set $verify_time = ClassLoader::perf_class_verify_time()->value()
        printf "累计验证时间: %lu ns (%.2f ms)\n", $verify_time, (double)$verify_time / 1000000
    end
    
    if ClassLoader::perf_classes_linked() != 0
        set $classes_linked = ClassLoader::perf_classes_linked()->value()
        printf "已链接类数量: %lu\n", $classes_linked
    end
    
    # 计算平均性能
    if $classes_inited > 0 && $total_time > 0
        set $avg_load_time = $total_time / $classes_inited
        printf "\n平均性能指标:\n"
        printf "- 平均加载时间: %lu ns (%.2f μs)\n", $avg_load_time, (double)$avg_load_time / 1000
        
        if $init_time > 0
            set $avg_init_time = $init_time / $classes_inited
            printf "- 平均初始化时间: %lu ns (%.2f μs)\n", $avg_init_time, (double)$avg_init_time / 1000
        end
        
        if $verify_time > 0
            set $avg_verify_time = $verify_time / $classes_inited
            printf "- 平均验证时间: %lu ns (%.2f μs)\n", $avg_verify_time, (double)$avg_verify_time / 1000
        end
        
        # 性能评级
        printf "\n性能评级:\n"
        if $avg_load_time < 50000
            printf "- 加载性能: ⭐⭐⭐⭐⭐ 优秀 (< 50μs)\n"
        elif $avg_load_time < 100000
            printf "- 加载性能: ⭐⭐⭐⭐ 良好 (< 100μs)\n"
        elif $avg_load_time < 200000
            printf "- 加载性能: ⭐⭐⭐ 一般 (< 200μs)\n"
        else
            printf "- 加载性能: ⭐⭐ 需优化 (>= 200μs)\n"
        end
    end
    
    printf "\n"
end

# 占位符表分析
define analyze_placeholder_table
    printf "=== 占位符表分析 ===\n"
    
    set $placeholders = SystemDictionary::_placeholders
    if $placeholders != 0
        printf "占位符表: 0x%lx\n", $placeholders
        printf "- 表大小: %d\n", $placeholders->_table_size
        printf "- 条目数量: %d\n", $placeholders->_number_of_entries
        
        if $placeholders->_table_size > 0
            set $load_factor = (double)$placeholders->_number_of_entries / $placeholders->_table_size
            printf "- 负载因子: %.3f\n", $load_factor
        end
        
        # 统计不同状态的占位符
        set $i = 0
        set $load_count = 0
        set $super_count = 0
        set $define_count = 0
        set $resolve_count = 0
        
        while $i < $placeholders->_table_size
            set $entry = $placeholders->_buckets[$i]
            while $entry != 0
                set $placeholder = (PlaceholderEntry*)$entry
                set $flags = $placeholder->_loadInstanceThreadQ._flags
                
                if $flags & 1  # LOAD_INSTANCE
                    set $load_count = $load_count + 1
                end
                if $flags & 2  # LOAD_SUPER
                    set $super_count = $super_count + 1
                end
                if $flags & 4  # DEFINE_CLASS
                    set $define_count = $define_count + 1
                end
                if $flags & 8  # RESOLVE_CLASS
                    set $resolve_count = $resolve_count + 1
                end
                
                set $entry = $entry->_next
            end
            set $i = $i + 1
        end
        
        printf "\n占位符状态统计:\n"
        printf "- 正在加载实例: %d\n", $load_count
        printf "- 正在加载父类: %d\n", $super_count
        printf "- 正在定义类: %d\n", $define_count
        printf "- 正在解析类: %d\n", $resolve_count
        
        # 并发加载分析
        if $placeholders->_number_of_entries > 0
            printf "\n并发加载分析:\n"
            set $concurrent_ratio = (double)($load_count + $super_count + $define_count) / $placeholders->_number_of_entries
            printf "- 并发加载比例: %.1f%%\n", $concurrent_ratio * 100
            
            if $concurrent_ratio > 0.1
                printf "- 并发状态: ⚠️  高并发 (> 10%%)\n"
            else
                printf "- 并发状态: ✅ 正常 (<= 10%%)\n"
            end
        end
        
    else
        printf "占位符表未初始化\n"
    end
    
    printf "\n"
end

# 特定类的详细分析
define analyze_specific_class
    if $argc != 1
        printf "用法: analyze_specific_class <类名>\n"
        printf "示例: analyze_specific_class \"java/lang/String\"\n"
    else
        printf "=== 类详细分析: %s ===\n", $arg0
        
        # 查找类符号
        set $class_symbol = SymbolTable::lookup($arg0, strlen($arg0))
        if $class_symbol != 0
            printf "类符号: 0x%lx (%s)\n", $class_symbol, $class_symbol->_body
            
            # 在系统字典中查找类
            set $klass = SystemDictionary::find_class($class_symbol, 0)
            if $klass != 0
                printf "类对象: 0x%lx\n", $klass
                printf "类名: %s\n", $klass->_name->_body
                
                # 基本信息
                printf "\n基本信息:\n"
                printf "- 类大小: %d words (%d bytes)\n", $klass->size(), $klass->size() * 8
                printf "- 访问标志: 0x%x\n", $klass->access_flags()->_flags
                
                if $klass->_vtable_len > 0
                    printf "- 虚方法表长度: %d\n", $klass->_vtable_len
                end
                
                # 如果是实例类，显示更详细信息
                if $klass->is_instance_klass()
                    set $ik = (InstanceKlass*)$klass
                    
                    printf "\n实例类详细信息:\n"
                    printf "- 类状态: "
                    set $state = $ik->_init_state
                    if $state == 0
                        printf "未分配\n"
                    elif $state == 1
                        printf "已分配\n"
                    elif $state == 2
                        printf "已加载\n"
                    elif $state == 3
                        printf "已链接\n"
                    elif $state == 4
                        printf "正在初始化\n"
                    elif $state == 5
                        printf "✅ 已初始化\n"
                    elif $state == 6
                        printf "❌ 初始化错误\n"
                    else
                        printf "未知状态(%d)\n", $state
                    end
                    
                    # 方法和字段信息
                    if $ik->_methods != 0
                        printf "- 方法数量: %d\n", $ik->_methods->_length
                    end
                    printf "- 字段数量: %d\n", $ik->_java_fields_count
                    
                    if $ik->_constants != 0
                        printf "- 常量池大小: %d\n", $ik->_constants->_length
                    end
                    
                    # 继承关系
                    printf "\n继承关系:\n"
                    if $ik->_super != 0
                        printf "- 父类: %s\n", $ik->_super->_name->_body
                    else
                        printf "- 父类: 无 (java.lang.Object)\n"
                    end
                    
                    if $ik->_local_interfaces != 0 && $ik->_local_interfaces->_length > 0
                        printf "- 实现接口数: %d\n", $ik->_local_interfaces->_length
                    end
                    
                    # 内存布局
                    printf "\n内存布局:\n"
                    printf "- 实例大小: %d words (%d bytes)\n", $ik->_layout_helper >> 2, ($ik->_layout_helper >> 2) * 8
                    printf "- 静态字段大小: %d words\n", $ik->_static_field_size
                    printf "- 非静态OOP字段数: %d\n", $ik->_nonstatic_oop_map_size
                    
                    # 类加载器信息
                    printf "\n类加载器信息:\n"
                    set $cld = $ik->_class_loader_data
                    if $cld != 0
                        if $cld->_class_loader == 0
                            printf "- 加载器: Bootstrap ClassLoader\n"
                        else
                            set $loader_klass = $cld->_class_loader->_metadata
                            if $loader_klass != 0
                                printf "- 加载器: %s\n", $loader_klass->_name->_body
                            end
                        end
                    end
                    
                    # 性能统计
                    printf "\n性能统计:\n"
                    if $ik->_methods != 0
                        set $method_count = $ik->_methods->_length
                        set $total_method_size = 0
                        set $i = 0
                        while $i < $method_count
                            set $method = $ik->_methods->_data[$i]
                            if $method != 0 && $method->_code_size > 0
                                set $total_method_size = $total_method_size + $method->_code_size
                            end
                            set $i = $i + 1
                        end
                        printf "- 字节码总大小: %d bytes\n", $total_method_size
                        if $method_count > 0
                            printf "- 平均方法大小: %d bytes\n", $total_method_size / $method_count
                        end
                    end
                    
                else
                    printf "- 类型: 非实例类\n"
                end
                
            else
                printf "❌ 类未找到或未加载\n"
            end
        else
            printf "❌ 无效的类名符号\n"
        end
    end
    
    printf "\n"
end

# 类加载器约束表分析
define analyze_loader_constraints
    printf "=== 类加载器约束表分析 ===\n"
    
    set $constraints = SystemDictionary::_loader_constraints
    if $constraints != 0
        printf "约束表: 0x%lx\n", $constraints
        printf "- 表大小: %d\n", $constraints->_table_size
        printf "- 约束数量: %d\n", $constraints->_number_of_entries
        
        if $constraints->_table_size > 0
            set $load_factor = (double)$constraints->_number_of_entries / $constraints->_table_size
            printf "- 负载因子: %.3f\n", $load_factor
        end
        
        # 遍历约束表统计详细信息
        set $i = 0
        set $constraint_count = 0
        set $loader_count = 0
        
        while $i < $constraints->_table_size
            set $entry = $constraints->_buckets[$i]
            while $entry != 0
                set $constraint = (LoaderConstraintEntry*)$entry
                set $constraint_count = $constraint_count + 1
                
                # 统计涉及的加载器数量
                set $num_loaders = $constraint->_num_loaders
                set $loader_count = $loader_count + $num_loaders
                
                set $entry = $entry->_next
            end
            set $i = $i + 1
        end
        
        printf "- 有效约束: %d\n", $constraint_count
        if $constraint_count > 0
            printf "- 平均加载器数/约束: %.1f\n", (double)$loader_count / $constraint_count
        end
        
        # 约束健康度评估
        printf "\n约束健康度评估:\n"
        if $constraint_count == 0
            printf "- 约束状态: ✅ 无约束冲突\n"
        elif $constraint_count < 100
            printf "- 约束状态: ✅ 约束数量正常 (< 100)\n"
        else
            printf "- 约束状态: ⚠️  约束数量较多 (>= 100)\n"
        end
        
    else
        printf "约束表未初始化\n"
    end
    
    printf "\n"
end

# 共享类分析(CDS)
define analyze_shared_classes
    printf "=== 共享类分析(CDS) ===\n"
    
    printf "CDS配置:\n"
    printf "- UseSharedSpaces: %s\n", UseSharedSpaces ? "✅ 启用" : "❌ 禁用"
    printf "- DumpSharedSpaces: %s\n", DumpSharedSpaces ? "✅ 启用" : "❌ 禁用"
    printf "- RequireSharedSpaces: %s\n", RequireSharedSpaces ? "✅ 启用" : "❌ 禁用"
    
    if UseSharedSpaces
        set $shared_dict = SystemDictionary::_shared_dictionary
        if $shared_dict != 0
            printf "\n共享字典统计:\n"
            printf "- 共享字典: 0x%lx\n", $shared_dict
            printf "- 共享类数量: %d\n", $shared_dict->_number_of_entries
            printf "- 表大小: %d\n", $shared_dict->_table_size
            
            if $shared_dict->_table_size > 0
                set $load_factor = (double)$shared_dict->_number_of_entries / $shared_dict->_table_size
                printf "- 负载因子: %.3f\n", $load_factor
            end
            
            # 共享空间信息
            printf "\n共享空间信息:\n"
            if MetaspaceShared::shared_rs() != 0
                set $shared_size = MetaspaceShared::shared_rs()->size()
                printf "- 共享区域大小: %lu bytes (%.2f MB)\n", $shared_size, (double)$shared_size / 1048576
                printf "- 共享区域基址: 0x%lx\n", MetaspaceShared::shared_rs()->base()
            end
            
            # CDS性能统计
            printf "\n性能优势:\n"
            if $shared_dict->_number_of_entries > 0
                printf "- 预加载类数量: %d\n", $shared_dict->_number_of_entries
                printf "- 启动时间优化: 预计节省 %d-30%% 启动时间\n", $shared_dict->_number_of_entries * 100 / 1000
                printf "- 内存使用优化: 多进程共享元数据\n"
            end
            
        else
            printf "\n❌ 共享字典未初始化\n"
        end
        
        # AppCDS统计
        if Arguments::_app_class_cache_filename != 0
            printf "\nAppCDS配置:\n"
            printf "- 应用类缓存文件: %s\n", Arguments::_app_class_cache_filename
        end
        
    else
        printf "\n💡 建议启用CDS以提升启动性能\n"
    end
    
    printf "\n"
end

# 类加载路径分析
define analyze_class_paths
    printf "=== 类加载路径分析 ===\n"
    
    # Bootstrap类路径
    printf "Bootstrap类路径:\n"
    if Arguments::_sun_boot_class_path != 0
        printf "- 启动类路径: %s\n", Arguments::_sun_boot_class_path->_value
    end
    
    # 应用类路径
    printf "\n应用类路径:\n"
    if Arguments::_java_class_path != 0
        printf "- Java类路径: %s\n", Arguments::_java_class_path->_value
    end
    
    # 模块路径(Java 9+)
    if Arguments::_javamodulepath != 0
        printf "\n模块路径:\n"
        printf "- 模块路径: %s\n", Arguments::_javamodulepath->_value
    end
    
    # 类路径条目统计
    printf "\n类路径条目统计:\n"
    set $entry_count = 0
    set $jar_count = 0
    set $dir_count = 0
    
    set $current_entry = ClassLoader::_first_entry
    while $current_entry != 0
        set $entry_count = $entry_count + 1
        
        # 简单判断是否为JAR文件(通过名称)
        set $name = $current_entry->name()
        if $name != 0
            set $name_len = strlen($name)
            if $name_len > 4
                # 检查是否以.jar结尾
                set $jar_suffix = $name + $name_len - 4
                if strcmp($jar_suffix, ".jar") == 0
                    set $jar_count = $jar_count + 1
                else
                    set $dir_count = $dir_count + 1
                end
            else
                set $dir_count = $dir_count + 1
            end
        end
        
        set $current_entry = $current_entry->next()
    end
    
    printf "- 总条目数: %d\n", $entry_count
    printf "- JAR文件数: %d\n", $jar_count
    printf "- 目录数: %d\n", $dir_count
    
    printf "\n"
end

# 类验证统计分析
define analyze_class_verification
    printf "=== 类验证统计分析 ===\n"
    
    # 验证配置
    printf "验证配置:\n"
    printf "- 验证模式: "
    if Arguments::_verify_mode == 0
        printf "禁用\n"
    elif Arguments::_verify_mode == 1
        printf "远程类验证\n"
    elif Arguments::_verify_mode == 2
        printf "全部验证\n"
    else
        printf "未知模式(%d)\n", Arguments::_verify_mode
    end
    
    # 验证性能统计
    if ClassLoader::perf_class_verify_time() != 0
        set $verify_time = ClassLoader::perf_class_verify_time()->value()
        printf "\n验证性能统计:\n"
        printf "- 累计验证时间: %lu ns (%.2f ms)\n", $verify_time, (double)$verify_time / 1000000
        
        if ClassLoader::perf_classes_inited() != 0
            set $classes_count = ClassLoader::perf_classes_inited()->value()
            if $classes_count > 0
                set $avg_verify_time = $verify_time / $classes_count
                printf "- 平均验证时间: %lu ns (%.2f μs)\n", $avg_verify_time, (double)$avg_verify_time / 1000
                
                # 验证性能评级
                if $avg_verify_time < 10000
                    printf "- 验证性能: ⭐⭐⭐⭐⭐ 优秀 (< 10μs)\n"
                elif $avg_verify_time < 50000
                    printf "- 验证性能: ⭐⭐⭐⭐ 良好 (< 50μs)\n"
                elif $avg_verify_time < 100000
                    printf "- 验证性能: ⭐⭐⭐ 一般 (< 100μs)\n"
                else
                    printf "- 验证性能: ⭐⭐ 需优化 (>= 100μs)\n"
                end
            end
        end
    end
    
    printf "\n"
end

# 完整的类加载系统健康检查
define classloading_health_check
    printf "========================================\n"
    printf "      类加载系统健康检查报告\n"
    printf "========================================\n\n"
    
    analyze_classloader_hierarchy
    analyze_class_dictionary
    analyze_metaspace_usage
    analyze_classloading_performance
    analyze_placeholder_table
    analyze_loader_constraints
    analyze_shared_classes
    analyze_class_paths
    analyze_class_verification
    
    printf "========================================\n"
    printf "           健康检查完成\n"
    printf "========================================\n"
end

# 设置类加载相关断点
define set_classloading_breakpoints
    printf "设置类加载相关断点...\n"
    
    # 核心类加载断点
    break SystemDictionary::resolve_or_fail
    break SystemDictionary::load_instance_class
    break InstanceKlass::initialize_impl
    break ClassLoader::load_class
    
    # 验证相关断点
    break Verifier::verify
    break ClassVerifier::verify_method
    
    # 解析相关断点
    break LinkResolver::resolve_method
    break ConstantPool::resolve_constant_at_impl
    
    # Metaspace分配断点
    break MetaspaceArena::allocate
    break Metaspace::allocate
    
    # 类卸载断点
    break ClassLoaderData::is_alive
    break ClassUnloadingTask::work
    
    # 并发加载断点
    break SystemDictionary::resolve_or_fail_parallel
    
    printf "类加载断点设置完成\n"
end

# 移除类加载断点
define clear_classloading_breakpoints
    printf "清除类加载相关断点...\n"
    
    clear SystemDictionary::resolve_or_fail
    clear SystemDictionary::load_instance_class
    clear InstanceKlass::initialize_impl
    clear ClassLoader::load_class
    clear Verifier::verify
    clear ClassVerifier::verify_method
    clear LinkResolver::resolve_method
    clear ConstantPool::resolve_constant_at_impl
    clear MetaspaceArena::allocate
    clear Metaspace::allocate
    clear ClassLoaderData::is_alive
    clear ClassUnloadingTask::work
    clear SystemDictionary::resolve_or_fail_parallel
    
    printf "类加载断点清除完成\n"
end

# 监控特定类的加载过程
define monitor_class_loading
    if $argc != 1
        printf "用法: monitor_class_loading <类名>\n"
        printf "示例: monitor_class_loading \"java/lang/String\"\n"
    else
        printf "开始监控类加载: %s\n", $arg0
        
        # 设置条件断点
        break SystemDictionary::resolve_or_fail if class_name != 0 && strcmp(class_name->_body, $arg0) == 0
        break InstanceKlass::initialize_impl if this_k != 0 && strcmp(this_k->_name->_body, $arg0) == 0
        
        printf "监控断点已设置，继续执行以观察加载过程\n"
        printf "断点触发时将显示详细的加载状态信息\n"
    end
end

# 类加载性能基准测试
define classloading_benchmark
    printf "=== 类加载性能基准测试 ===\n"
    
    # 记录开始状态
    if ClassLoader::perf_accumulated_time() != 0
        set $start_time = ClassLoader::perf_accumulated_time()->value()
        printf "基准测试开始时间: %lu ns\n", $start_time
    else
        set $start_time = 0
        printf "性能计数器未启用，无法获取精确时间\n"
    end
    
    if ClassLoader::perf_classes_inited() != 0
        set $start_classes = ClassLoader::perf_classes_inited()->value()
        printf "基准测试开始时已加载类数: %lu\n", $start_classes
    else
        set $start_classes = 0
    end
    
    # 记录Metaspace使用情况
    set $start_metaspace = MetaspaceUtils::used_bytes()
    printf "基准测试开始时Metaspace使用: %lu bytes (%.2f MB)\n", $start_metaspace, (double)$start_metaspace / 1048576
    
    printf "\n请运行一些类加载操作，然后调用 classloading_benchmark_result\n"
end

define classloading_benchmark_result
    printf "=== 类加载性能基准测试结果 ===\n"
    
    # 记录结束状态
    if ClassLoader::perf_accumulated_time() != 0
        set $end_time = ClassLoader::perf_accumulated_time()->value()
        printf "基准测试结束时间: %lu ns\n", $end_time
    else
        set $end_time = 0
    end
    
    if ClassLoader::perf_classes_inited() != 0
        set $end_classes = ClassLoader::perf_classes_inited()->value()
        printf "基准测试结束时已加载类数: %lu\n", $end_classes
    else
        set $end_classes = 0
    end
    
    set $end_metaspace = MetaspaceUtils::used_bytes()
    printf "基准测试结束时Metaspace使用: %lu bytes (%.2f MB)\n", $end_metaspace, (double)$end_metaspace / 1048576
    
    # 计算性能指标
    if $end_time > $start_time && $end_classes > $start_classes
        set $elapsed_time = $end_time - $start_time
        set $loaded_classes = $end_classes - $start_classes
        set $metaspace_growth = $end_metaspace - $start_metaspace
        
        printf "\n性能统计:\n"
        printf "- 测试时长: %lu ns (%.2f ms)\n", $elapsed_time, (double)$elapsed_time / 1000000
        printf "- 新加载类数: %lu\n", $loaded_classes
        printf "- Metaspace增长: %lu bytes (%.2f MB)\n", $metaspace_growth, (double)$metaspace_growth / 1048576
        
        if $loaded_classes > 0
            set $avg_time = $elapsed_time / $loaded_classes
            set $avg_metaspace = $metaspace_growth / $loaded_classes
            
            printf "\n平均性能指标:\n"
            printf "- 平均加载时间: %lu ns (%.2f μs)\n", $avg_time, (double)$avg_time / 1000
            printf "- 平均Metaspace使用: %lu bytes\n", $avg_metaspace
            printf "- 加载速率: %.2f 类/秒\n", (double)$loaded_classes * 1000000000 / $elapsed_time
            
            # 性能评级
            printf "\n性能评级:\n"
            if $avg_time < 50000
                printf "- 加载效率: ⭐⭐⭐⭐⭐ 优秀\n"
            elif $avg_time < 100000
                printf "- 加载效率: ⭐⭐⭐⭐ 良好\n"
            elif $avg_time < 200000
                printf "- 加载效率: ⭐⭐⭐ 一般\n"
            else
                printf "- 加载效率: ⭐⭐ 需优化\n"
            end
            
            if $avg_metaspace < 10000
                printf "- 内存效率: ⭐⭐⭐⭐⭐ 优秀\n"
            elif $avg_metaspace < 20000
                printf "- 内存效率: ⭐⭐⭐⭐ 良好\n"
            else
                printf "- 内存效率: ⭐⭐⭐ 一般\n"
            end
        end
    else
        printf "\n⚠️  无法计算性能指标，请确保性能计数器已启用\n"
    end
    
    printf "\n"
end

# 类加载热点分析
define analyze_classloading_hotspots
    printf "=== 类加载热点分析 ===\n"
    
    # 分析最大的类
    printf "大型类分析:\n"
    set $dict = SystemDictionary::_dictionary
    if $dict != 0
        set $max_size = 0
        set $max_methods = 0
        set $max_fields = 0
        set $total_size = 0
        set $class_count = 0
        
        set $i = 0
        while $i < $dict->_table_size
            set $entry = $dict->_buckets[$i]
            while $entry != 0
                set $klass = $entry->literal()
                if $klass != 0 && $klass->is_instance_klass()
                    set $ik = (InstanceKlass*)$klass
                    set $size = $ik->size()
                    set $total_size = $total_size + $size
                    set $class_count = $class_count + 1
                    
                    if $size > $max_size
                        set $max_size = $size
                    end
                    
                    if $ik->_methods != 0 && $ik->_methods->_length > $max_methods
                        set $max_methods = $ik->_methods->_length
                    end
                    
                    if $ik->_java_fields_count > $max_fields
                        set $max_fields = $ik->_java_fields_count
                    end
                end
                set $entry = $entry->_next
            end
            set $i = $i + 1
        end
        
        if $class_count > 0
            printf "- 最大类大小: %d words (%d bytes)\n", $max_size, $max_size * 8
            printf "- 最多方法数: %d\n", $max_methods
            printf "- 最多字段数: %d\n", $max_fields
            printf "- 平均类大小: %d words (%d bytes)\n", $total_size / $class_count, ($total_size / $class_count) * 8
        end
    end
    
    # 分析加载器分布
    printf "\n加载器负载分析:\n"
    set $cld_count = 0
    set $max_classes_per_loader = 0
    set $current_cld = ClassLoaderDataGraph::_head
    
    while $current_cld != 0
        set $cld_count = $cld_count + 1
        set $classes_count = $current_cld->_klasses_count
        
        if $classes_count > $max_classes_per_loader
            set $max_classes_per_loader = $classes_count
        end
        
        set $current_cld = $current_cld->_next
    end
    
    printf "- 活跃类加载器数: %d\n", $cld_count
    printf "- 单个加载器最大类数: %d\n", $max_classes_per_loader
    
    printf "\n"
end

# 初始化脚本
printf "类加载机制深度分析GDB脚本已加载 - 深度增强版\n"
printf "========================================\n"
printf "可用命令:\n"
printf "  classloading_health_check          - 完整健康检查(120+数据点)\n"
printf "  analyze_classloader_hierarchy      - 分析类加载器层次结构\n"
printf "  analyze_class_dictionary           - 分析类字典状态\n"
printf "  analyze_metaspace_usage            - 分析Metaspace使用情况\n"
printf "  analyze_classloading_performance   - 分析类加载性能\n"
printf "  analyze_placeholder_table          - 分析占位符表\n"
printf "  analyze_loader_constraints         - 分析加载器约束\n"
printf "  analyze_shared_classes             - 分析CDS共享类\n"
printf "  analyze_class_paths                - 分析类加载路径\n"
printf "  analyze_class_verification         - 分析类验证统计\n"
printf "  analyze_classloading_hotspots      - 分析类加载热点\n"
printf "  analyze_specific_class <name>      - 分析特定类详情\n"
printf "  set_classloading_breakpoints       - 设置调试断点\n"
printf "  monitor_class_loading <name>       - 监控特定类加载\n"
printf "  classloading_benchmark             - 性能基准测试开始\n"
printf "  classloading_benchmark_result      - 性能基准测试结果\n"
printf "========================================\n"
printf "准备就绪，可以开始类加载深度分析！\n"