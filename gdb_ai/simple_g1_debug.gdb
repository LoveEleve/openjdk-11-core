# 简化的G1调试脚本
set confirm off
set pagination off

# 在main函数后设置断点
break main
commands 1
  echo ========== 进入main函数 ==========\n
  continue
end

# 启动程序
run -Xms8g -Xmx8g -XX:+UseG1GC -Xint -XX:+PrintGC -XX:+UnlockDiagnosticVMOptions -XX:+LogVMOutput -cp /data/workspace/demo/out HelloWorld

quit