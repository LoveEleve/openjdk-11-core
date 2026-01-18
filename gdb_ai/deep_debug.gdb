# 深度JVM调试 - 跟踪关键函数调用
set confirm off
set pagination off
set print pretty on

# 设置源码目录
directory /data/workspace/openjdk11-core/src

# 关键断点
break main
break JavaMain  
break InitializeJVM
break JNI_CreateJavaVM

# 启动并开始调试
run -Xms1g -Xmx1g -XX:+UseG1GC -XX:-UseBiasedLocking -cp /data/workspace/demo/out com.wjcoder.jvm.ObjectLayoutTest

# 在main函数处
echo ========== 进入main函数 ==========\n
info args
continue

# 在JavaMain处  
echo ========== 进入JavaMain函数 ==========\n
print "参数argc:"
print argc
print "参数argv[0]:"
print argv[0]
continue

# 在InitializeJVM处
echo ========== 进入InitializeJVM函数 ==========\n
print "即将初始化JVM..."
continue

# 在JNI_CreateJavaVM处
echo ========== 进入JNI_CreateJavaVM函数 ==========\n
print "创建JavaVM实例..."
continue

quit