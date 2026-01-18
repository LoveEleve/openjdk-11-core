# JVM底层调试脚本 - 从java.c的main到Java main()方法
# 设置调试环境
set confirm off
set pagination off
set print pretty on
set print elements 0

# 设置断点在关键函数
break JavaMain
break InitializeJVM
break JNI_CreateJavaVM
break LoadJavaVM

# 启动程序
run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseBiasedLocking -cp /data/workspace/demo/out com.wjcoder.jvm.ObjectLayoutTest

# 开始调试会话
echo ========================================\n
echo JVM底层调试开始！\n
echo ========================================\n