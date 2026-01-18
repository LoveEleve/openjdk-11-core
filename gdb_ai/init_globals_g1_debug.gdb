# init_globals() G1堆和其他核心对象深度调试

set pagination off
set print pretty on
set print object on
set confirm off

file /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/images/jdk/bin/java

# 在init_globals返回前获取G1堆详细状态
break init.cpp:167
commands
    silent
    printf "\n========================================\n"
    printf "=== G1堆和核心对象深度调试 ===\n"
    printf "========================================\n"
    
    # G1堆基本信息
    set $g1heap = (G1CollectedHeap*)Universe::_collectedHeap
    printf "\n=== 1. G1CollectedHeap 基本信息 ===\n"
    printf "G1CollectedHeap地址: %p\n", $g1heap
    printf "sizeof(G1CollectedHeap): %lu bytes\n", sizeof(G1CollectedHeap)
    
    # G1堆核心组件
    printf "\n=== 2. G1CollectedHeap 核心组件 ===\n"
    printf "_g1_policy: %p\n", $g1heap->_g1_policy
    printf "_allocator: %p\n", $g1heap->_allocator
    printf "_workers: %p\n", $g1heap->_workers
    printf "_card_table: %p\n", $g1heap->_card_table
    printf "_g1_rem_set: %p\n", $g1heap->_g1_rem_set
    printf "_cm: %p (G1ConcurrentMark)\n", $g1heap->_cm
    printf "_ref_processor_stw: %p\n", $g1heap->_ref_processor_stw
    printf "_ref_processor_cm: %p\n", $g1heap->_ref_processor_cm
    printf "_bot: %p (G1BlockOffsetTable)\n", $g1heap->_bot
    printf "_hot_card_cache: %p\n", $g1heap->_hot_card_cache
    
    # G1堆配置
    printf "\n=== 3. G1CollectedHeap 配置参数 ===\n"
    printf "_humongous_object_threshold_in_words: %lu\n", $g1heap->_humongous_object_threshold_in_words
    printf "_num_humongous_objects: %lu\n", $g1heap->_num_humongous_objects
    printf "_old_marking_cycles_started: %u\n", $g1heap->_old_marking_cycles_started
    printf "_old_marking_cycles_completed: %u\n", $g1heap->_old_marking_cycles_completed
    
    # HeapRegionManager
    printf "\n=== 4. HeapRegionManager 详情 ===\n"
    printf "_hrm地址: %p\n", &($g1heap->_hrm)
    printf "_hrm._regions: %p\n", $g1heap->_hrm._regions
    printf "_hrm._num_committed: %u\n", $g1heap->_hrm._num_committed
    printf "_hrm._allocated_heapregions_length: %u\n", $g1heap->_hrm._allocated_heapregions_length
    printf "_hrm._heap_mapper: %p\n", $g1heap->_hrm._heap_mapper
    printf "_hrm._prev_bitmap_mapper: %p\n", $g1heap->_hrm._prev_bitmap_mapper
    printf "_hrm._next_bitmap_mapper: %p\n", $g1heap->_hrm._next_bitmap_mapper
    printf "_hrm._bot_mapper: %p\n", $g1heap->_hrm._bot_mapper
    printf "_hrm._cardtable_mapper: %p\n", $g1heap->_hrm._cardtable_mapper
    printf "_hrm._card_counts_mapper: %p\n", $g1heap->_hrm._card_counts_mapper
    
    # Survivor区域
    printf "\n=== 5. G1 Survivor区域 ===\n"
    printf "_survivor地址: %p\n", &($g1heap->_survivor)
    printf "_survivor._length: %u\n", $g1heap->_survivor._length
    
    # CodeCache详情
    printf "\n=== 6. CodeCache 详情 ===\n"
    printf "CodeCache::_heaps._data: %p\n", CodeCache::_heaps._data
    printf "CodeCache::_heaps._len: %d\n", CodeCache::_heaps._len
    printf "CodeCache::_low_bound: %p\n", CodeCache::_low_bound
    printf "CodeCache::_high_bound: %p\n", CodeCache::_high_bound
    printf "CodeCache::_number_of_nmethods_with_dependencies: %d\n", CodeCache::_number_of_nmethods_with_dependencies
    printf "CodeCache::_amount_full_count: %d\n", CodeCache::_amount_full_count
    
    # SymbolTable详情
    printf "\n=== 7. SymbolTable 详情 ===\n"
    printf "SymbolTable::_the_table: %p\n", SymbolTable::_the_table
    printf "SymbolTable::_local_table: %p\n", SymbolTable::_local_table
    printf "SymbolTable::_current_size: %lu\n", SymbolTable::_current_size
    printf "SymbolTable::_symbols_removed: %lu\n", SymbolTable::_symbols_removed
    printf "SymbolTable::_symbols_counted: %lu\n", SymbolTable::_symbols_counted
    
    # StringTable详情
    printf "\n=== 8. StringTable 详情 ===\n"
    printf "StringTable::_the_table: %p\n", StringTable::_the_table
    printf "StringTable::_local_table: %p\n", StringTable::_local_table
    printf "StringTable::_current_size: %lu\n", StringTable::_current_size
    
    # Metaspace详情
    printf "\n=== 9. Metaspace 详情 ===\n"
    printf "Metaspace::_first_chunk_word_size: %lu\n", Metaspace::_first_chunk_word_size
    printf "Metaspace::_first_class_chunk_word_size: %lu\n", Metaspace::_first_class_chunk_word_size
    printf "Metaspace::_commit_alignment: %lu\n", Metaspace::_commit_alignment
    printf "Metaspace::_reserve_alignment: %lu\n", Metaspace::_reserve_alignment
    printf "Metaspace::_class_space_list: %p\n", Metaspace::_class_space_list
    printf "Metaspace::_space_list: %p\n", Metaspace::_space_list
    
    # Universe状态
    printf "\n=== 10. Universe 状态 ===\n"
    printf "Universe::_fully_initialized: %d\n", Universe::_fully_initialized
    printf "Universe::_verify_count: %d\n", Universe::_verify_count
    printf "Universe::_heap_base_min_address_aligned: %p\n", Universe::_heap_base_min_address_aligned
    
    # 预分配异常对象
    printf "\n=== 11. 预分配异常对象 ===\n"
    printf "_out_of_memory_error_java_heap: %p\n", Universe::_out_of_memory_error_java_heap
    printf "_out_of_memory_error_metaspace: %p\n", Universe::_out_of_memory_error_metaspace
    printf "_out_of_memory_error_class_metaspace: %p\n", Universe::_out_of_memory_error_class_metaspace
    printf "_out_of_memory_error_array_size: %p\n", Universe::_out_of_memory_error_array_size
    printf "_out_of_memory_error_gc_overhead_limit: %p\n", Universe::_out_of_memory_error_gc_overhead_limit
    printf "_out_of_memory_error_realloc_objects: %p\n", Universe::_out_of_memory_error_realloc_objects
    
    # 虚拟机异常
    printf "\n=== 12. 虚拟机预分配异常 ===\n"
    printf "_virtual_machine_error_instance: %p\n", Universe::_virtual_machine_error_instance
    
    # 方法缓存
    printf "\n=== 13. 方法缓存 ===\n"
    printf "_loader_addClass_cache地址: %p\n", &Universe::_loader_addClass_cache
    printf "_pd_implies_cache地址: %p\n", &Universe::_pd_implies_cache
    printf "_throw_illegal_access_error_cache地址: %p\n", &Universe::_throw_illegal_access_error_cache
    printf "_throw_no_such_method_error_cache地址: %p\n", &Universe::_throw_no_such_method_error_cache
    printf "_do_stack_walk_cache地址: %p\n", &Universe::_do_stack_walk_cache
    
    # Mirror对象
    printf "\n=== 14. 基本类型Mirror对象 ===\n"
    printf "_int_mirror: %p\n", Universe::_int_mirror
    printf "_float_mirror: %p\n", Universe::_float_mirror
    printf "_double_mirror: %p\n", Universe::_double_mirror
    printf "_byte_mirror: %p\n", Universe::_byte_mirror
    printf "_bool_mirror: %p\n", Universe::_bool_mirror
    printf "_char_mirror: %p\n", Universe::_char_mirror
    printf "_long_mirror: %p\n", Universe::_long_mirror
    printf "_short_mirror: %p\n", Universe::_short_mirror
    printf "_void_mirror: %p\n", Universe::_void_mirror
    
    # 核心类
    printf "\n=== 15. 系统核心类镜像 ===\n"
    printf "_main_thread_group: %p\n", Universe::_main_thread_group
    printf "_system_thread_group: %p\n", Universe::_system_thread_group
    printf "_the_null_sentinel: %p\n", Universe::_the_null_sentinel
    
    printf "\n========================================\n"
    printf "=== G1堆和核心对象调试完成 ===\n"
    printf "========================================\n"
    
    quit
end

run -version
