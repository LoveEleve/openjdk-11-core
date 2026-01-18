# G1 Region配置验证脚本
set pagination off
set print pretty on
set confirm off

# 在G1堆初始化完成后检查Region配置
break G1CollectedHeap::initialize
commands
    silent
    printf "\n🔍 === G1 REGION 配置验证 ===\n"
    continue
end

# 在HeapRegionManager初始化后检查
break HeapRegionManager::initialize  
commands
    silent
    printf "\n📊 === HEAP REGION MANAGER 状态 ===\n"
    continue
end

# 在universe_init完成后检查G1配置
break universe.cpp:720
commands
    silent
    printf "\n🌌 === G1堆配置最终验证 ===\n"
    
    # 检查G1相关的全局变量
    printf "G1HeapRegionSize: %lu bytes\n", G1HeapRegionSize
    printf "LogOfHRGrainBytes: %d\n", LogOfHRGrainBytes
    printf "G1HeapRegionSize (MB): %lu MB\n", G1HeapRegionSize/1024/1024
    
    # 检查堆配置
    if Universe::_collectedHeap != 0
        printf "堆类型: %s\n", Universe::_collectedHeap->name()
        printf "堆容量: %lu bytes (%lu MB)\n", Universe::_collectedHeap->capacity(), Universe::_collectedHeap->capacity()/1024/1024
        
        # 如果是G1堆，获取更多信息
        printf "尝试获取G1特定信息...\n"
    end
    
    continue
end

printf "🎯 开始G1 Region配置验证...\n"
run
quit