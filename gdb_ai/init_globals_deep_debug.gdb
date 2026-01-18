# init_globals() 深度调试脚本 - 第二轮
# 获取更多对象详细信息

set pagination off
set print pretty on
set print object on
set confirm off

file /data/workspace/openjdk11-core/build/linux-x86_64-normal-server-slowdebug/images/jdk/bin/java

# 在init_globals返回前获取完整状态
break init.cpp:167
commands
    silent
    printf "\n========================================\n"
    printf "=== init_globals() 完成后的完整状态 ===\n"
    printf "========================================\n"
    
    printf "\n=== 1. Universe 核心静态变量 ===\n"
    printf "Universe::_collectedHeap: %p\n", Universe::_collectedHeap
    printf "Universe::_main_thread_group: %p\n", Universe::_main_thread_group
    printf "Universe::_system_thread_group: %p\n", Universe::_system_thread_group
    printf "Universe::_the_null_sentinel: %p\n", Universe::_the_null_sentinel
    printf "Universe::_the_empty_class_klass_array: %p\n", Universe::_the_empty_class_klass_array
    printf "Universe::_the_empty_int_array: %p\n", Universe::_the_empty_int_array
    printf "Universe::_the_empty_short_array: %p\n", Universe::_the_empty_short_array
    printf "Universe::_the_empty_method_array: %p\n", Universe::_the_empty_method_array
    printf "Universe::_the_empty_klass_array: %p\n", Universe::_the_empty_klass_array
    printf "Universe::_the_array_interfaces_array: %p\n", Universe::_the_array_interfaces_array
    
    printf "\n=== 2. 基本类型数组Klass对象 ===\n"
    printf "Universe::_boolArrayKlassObj: %p\n", Universe::_boolArrayKlassObj
    printf "Universe::_byteArrayKlassObj: %p\n", Universe::_byteArrayKlassObj
    printf "Universe::_charArrayKlassObj: %p\n", Universe::_charArrayKlassObj
    printf "Universe::_intArrayKlassObj: %p\n", Universe::_intArrayKlassObj
    printf "Universe::_longArrayKlassObj: %p\n", Universe::_longArrayKlassObj
    printf "Universe::_shortArrayKlassObj: %p\n", Universe::_shortArrayKlassObj
    
    printf "\n=== 3. 类型数组Klass ===\n"
    printf "Universe::_typeArrayKlassObjs[0]: %p\n", Universe::_typeArrayKlassObjs[0]
    printf "Universe::_typeArrayKlassObjs[1]: %p\n", Universe::_typeArrayKlassObjs[1]
    printf "Universe::_typeArrayKlassObjs[2]: %p\n", Universe::_typeArrayKlassObjs[2]
    printf "Universe::_typeArrayKlassObjs[3]: %p\n", Universe::_typeArrayKlassObjs[3]
    printf "Universe::_typeArrayKlassObjs[4]: %p\n", Universe::_typeArrayKlassObjs[4]
    printf "Universe::_typeArrayKlassObjs[5]: %p\n", Universe::_typeArrayKlassObjs[5]
    printf "Universe::_typeArrayKlassObjs[6]: %p\n", Universe::_typeArrayKlassObjs[6]
    printf "Universe::_typeArrayKlassObjs[7]: %p\n", Universe::_typeArrayKlassObjs[7]
    printf "Universe::_typeArrayKlassObjs[8]: %p\n", Universe::_typeArrayKlassObjs[8]
    
    printf "\n=== 4. 压缩指针配置 ===\n"
    printf "Universe::_narrow_oop._base: %p\n", Universe::_narrow_oop._base
    printf "Universe::_narrow_oop._shift: %d\n", Universe::_narrow_oop._shift
    printf "Universe::_narrow_oop._use_implicit_null_checks: %d\n", Universe::_narrow_oop._use_implicit_null_checks
    printf "Universe::_narrow_klass._base: %p\n", Universe::_narrow_klass._base
    printf "Universe::_narrow_klass._shift: %d\n", Universe::_narrow_klass._shift
    
    printf "\n=== 5. 堆内存详情 ===\n"
    printf "Universe::_collectedHeap地址: %p\n", Universe::_collectedHeap
    printf "Universe::heap()->max_capacity(): %lu\n", Universe::_collectedHeap->_max_capacity
    printf "Universe::heap()->reserved_region()._start: %p\n", Universe::_collectedHeap->_reserved._start
    printf "Universe::heap()->reserved_region()._end: %p\n", Universe::_collectedHeap->_reserved._end
    
    printf "\n=== 6. 异常对象 ===\n"
    printf "Universe::_out_of_memory_error_java_heap: %p\n", Universe::_out_of_memory_error_java_heap
    printf "Universe::_out_of_memory_error_metaspace: %p\n", Universe::_out_of_memory_error_metaspace
    printf "Universe::_out_of_memory_error_class_metaspace: %p\n", Universe::_out_of_memory_error_class_metaspace
    printf "Universe::_out_of_memory_error_array_size: %p\n", Universe::_out_of_memory_error_array_size
    printf "Universe::_out_of_memory_error_gc_overhead_limit: %p\n", Universe::_out_of_memory_error_gc_overhead_limit
    printf "Universe::_out_of_memory_error_realloc_objects: %p\n", Universe::_out_of_memory_error_realloc_objects
    
    printf "\n=== 7. Metaspace详情 ===\n"
    printf "Metaspace::_first_chunk_word_size: %lu\n", Metaspace::_first_chunk_word_size
    printf "Metaspace::_first_class_chunk_word_size: %lu\n", Metaspace::_first_class_chunk_word_size
    printf "Metaspace::_commit_alignment: %lu\n", Metaspace::_commit_alignment
    printf "Metaspace::_reserve_alignment: %lu\n", Metaspace::_reserve_alignment
    printf "Metaspace::_class_space_list: %p\n", Metaspace::_class_space_list
    printf "Metaspace::_space_list: %p\n", Metaspace::_space_list
    printf "Metaspace::_class_vsm: %p\n", Metaspace::_class_vsm
    printf "Metaspace::_vsm: %p\n", Metaspace::_vsm
    
    printf "\n=== 8. G1堆详细信息 ===\n"
    # 强制转换为G1CollectedHeap
    set $g1heap = (G1CollectedHeap*)Universe::_collectedHeap
    printf "G1CollectedHeap指针: %p\n", $g1heap
    printf "G1CollectedHeap._hrm: %p\n", &($g1heap->_hrm)
    printf "G1CollectedHeap._g1_policy: %p\n", $g1heap->_g1_policy
    printf "G1CollectedHeap._allocator: %p\n", $g1heap->_allocator
    printf "G1CollectedHeap._humongous_object_threshold_in_words: %lu\n", $g1heap->_humongous_object_threshold_in_words
    printf "G1CollectedHeap._num_humongous_objects: %lu\n", $g1heap->_num_humongous_objects
    printf "G1CollectedHeap._old_marking_cycles_started: %u\n", $g1heap->_old_marking_cycles_started
    printf "G1CollectedHeap._old_marking_cycles_completed: %u\n", $g1heap->_old_marking_cycles_completed
    printf "G1CollectedHeap._workers: %p\n", $g1heap->_workers
    printf "G1CollectedHeap._card_table: %p\n", $g1heap->_card_table
    printf "G1CollectedHeap._g1_rem_set: %p\n", $g1heap->_g1_rem_set
    printf "G1CollectedHeap._cm: %p\n", $g1heap->_cm
    printf "G1CollectedHeap._ref_processor_stw: %p\n", $g1heap->_ref_processor_stw
    printf "G1CollectedHeap._ref_processor_cm: %p\n", $g1heap->_ref_processor_cm
    printf "G1CollectedHeap._bot: %p\n", $g1heap->_bot
    printf "G1CollectedHeap._hot_card_cache: %p\n", $g1heap->_hot_card_cache
    
    printf "\n=== 9. HeapRegionManager详情 ===\n"
    printf "HeapRegionManager._regions: %p\n", $g1heap->_hrm._regions
    printf "HeapRegionManager._num_committed: %u\n", $g1heap->_hrm._num_committed
    printf "HeapRegionManager._allocated_heapregions_length: %u\n", $g1heap->_hrm._allocated_heapregions_length
    printf "HeapRegionManager._heap_mapper: %p\n", $g1heap->_hrm._heap_mapper
    printf "HeapRegionManager._prev_bitmap_mapper: %p\n", $g1heap->_hrm._prev_bitmap_mapper
    printf "HeapRegionManager._next_bitmap_mapper: %p\n", $g1heap->_hrm._next_bitmap_mapper
    printf "HeapRegionManager._bot_mapper: %p\n", $g1heap->_hrm._bot_mapper
    printf "HeapRegionManager._cardtable_mapper: %p\n", $g1heap->_hrm._cardtable_mapper
    printf "HeapRegionManager._card_counts_mapper: %p\n", $g1heap->_hrm._card_counts_mapper
    
    printf "\n=== 10. CodeCache详情 ===\n"
    printf "CodeCache::_heaps._data: %p\n", CodeCache::_heaps._data
    printf "CodeCache::_heaps._len: %d\n", CodeCache::_heaps._len
    printf "CodeCache::_low_bound: %p\n", CodeCache::_low_bound
    printf "CodeCache::_high_bound: %p\n", CodeCache::_high_bound
    printf "CodeCache::_number_of_nmethods_with_dependencies: %d\n", CodeCache::_number_of_nmethods_with_dependencies
    printf "CodeCache::_amount_full_count: %d\n", CodeCache::_amount_full_count
    
    printf "\n=== 11. SymbolTable详情 ===\n"
    printf "SymbolTable::_the_table: %p\n", SymbolTable::_the_table
    printf "SymbolTable::_local_table: %p\n", SymbolTable::_local_table
    printf "SymbolTable::_current_size: %lu\n", SymbolTable::_current_size
    printf "SymbolTable::_symbols_removed: %lu\n", SymbolTable::_symbols_removed
    printf "SymbolTable::_symbols_counted: %lu\n", SymbolTable::_symbols_counted
    
    printf "\n=== 12. StringTable详情 ===\n"
    printf "StringTable::_the_table: %p\n", StringTable::_the_table
    printf "StringTable::_local_table: %p\n", StringTable::_local_table
    printf "StringTable::_current_size: %lu\n", StringTable::_current_size
    
    printf "\n=== 13. Universe初始化状态 ===\n"
    printf "Universe::_fully_initialized: %d\n", Universe::_fully_initialized
    printf "Universe::_verify_count: %d\n", Universe::_verify_count
    printf "Universe::_non_oop_bits: %d\n", Universe::_non_oop_bits
    printf "Universe::_heap_base_min_address_aligned: %p\n", Universe::_heap_base_min_address_aligned
    
    printf "\n========================================\n"
    printf "=== 调试完成 ===\n"
    printf "========================================\n"
    
    quit
end

run -version
