# 精确验证G1 Region大小
set pagination off
set print pretty on
set confirm off

# 在JVM完全启动后检查Region大小
break JavaMain
commands
    silent
    printf "\n🔍 === G1 REGION 大小精确验证 ===\n"
    
    # 检查G1HeapRegionSize全局变量
    printf "G1HeapRegionSize (bytes): %lu\n", G1HeapRegionSize
    printf "G1HeapRegionSize (KB): %lu KB\n", G1HeapRegionSize/1024
    printf "G1HeapRegionSize (MB): %lu MB\n", G1HeapRegionSize/1024/1024
    
    # 检查相关的计算
    printf "\n=== Region大小相关计算 ===\n"
    printf "1MB = %d bytes\n", 1024*1024
    printf "2MB = %d bytes\n", 2*1024*1024  
    printf "4MB = %d bytes\n", 4*1024*1024
    printf "8MB = %d bytes\n", 8*1024*1024
    
    printf "\n=== 对比验证 ===\n"
    if G1HeapRegionSize == 2097152
        printf "✅ Region大小 = 2MB\n"
    end
    if G1HeapRegionSize == 4194304
        printf "✅ Region大小 = 4MB\n"
    end
    if G1HeapRegionSize == 8388608
        printf "✅ Region大小 = 8MB\n"
    end
    
    continue
end

printf "🎯 开始精确验证G1 Region大小...\n"
run
quit