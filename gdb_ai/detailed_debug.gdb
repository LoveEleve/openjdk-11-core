# 详细的JVM底层调试脚本
set confirm off
set pagination off
set print pretty on

# 在LoadJavaVM处停下来，查看详细信息
break LoadJavaVM
commands 1
  echo ========== LoadJavaVM 被调用 ==========\n
  print "JVM路径:"
  print jvmpath
  echo 继续执行...\n
  continue
end

# 在JNI_CreateJavaVM处停下
break JNI_CreateJavaVM  
commands 2
  echo ========== JNI_CreateJavaVM 被调用 ==========\n
  print "创建JavaVM实例..."
  echo 查看JVM参数:\n
  print *args
  continue
end

# 启动调试
run -Xms8g -Xmx8g -XX:+UseG1GC -XX:-UseBiasedLocking -cp /data/workspace/demo/out com.wjcoder.jvm.ObjectLayoutTest

quit