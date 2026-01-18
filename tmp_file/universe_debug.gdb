# Universe系统深度调试
set pagination off
set print pretty on
set confirm off

# 在universe_init完成后检查Universe状态
break universe.cpp:720
commands
    silent
    printf "\n🌌 === UNIVERSE 初始化完成状态检查 ===\n"
    
    printf "\n=== 1. 堆内存信息 ===\n"
    printf "Universe::_collectedHeap: %p\n", Universe::_collectedHeap
    if Universe::_collectedHeap != 0
        printf "堆名称: %s\n", Universe::_collectedHeap->name()
        printf "堆容量: %lu bytes\n", Universe::_collectedHeap->capacity()
        printf "已使用: %lu bytes\n", Universe::_collectedHeap->used()
    end
    
    printf "\n=== 2. 基本类型Klass对象 ===\n"
    printf "_boolArrayKlassObj: %p\n", Universe::_boolArrayKlassObj
    printf "_byteArrayKlassObj: %p\n", Universe::_byteArrayKlassObj
    printf "_charArrayKlassObj: %p\n", Universe::_charArrayKlassObj
    printf "_intArrayKlassObj: %p\n", Universe::_intArrayKlassObj
    printf "_longArrayKlassObj: %p\n", Universe::_longArrayKlassObj
    
    printf "\n=== 3. 压缩指针配置 ===\n"
    printf "UseCompressedOops: %d\n", UseCompressedOops
    printf "UseCompressedClassPointers: %d\n", UseCompressedClassPointers
    if UseCompressedOops
        printf "OopEncodingHeapMax: %lu\n", OopEncodingHeapMax
        printf "CompressedOops::base(): %p\n", CompressedOops::base()
        printf "CompressedOops::shift(): %d\n", CompressedOops::shift()
    end
    
    printf "\n=== 4. 元空间信息 ===\n"
    printf "Metaspace已初始化\n"
    
    continue
end

# G1堆详细信息
break G1CollectedHeap::initialize
commands
    silent
    printf "\n🔥 === G1 HEAP 初始化详情 ===\n"
    printf "G1堆初始化开始...\n"
    continue
end

# G1 Region信息
break HeapRegionManager::initialize
commands
    silent
    printf "\n📊 === G1 REGION MANAGER 初始化 ===\n"
    continue
end

run
quit