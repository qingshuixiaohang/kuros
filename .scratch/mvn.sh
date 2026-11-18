#!/bin/sh
# kuros-web 后端构建入口。绕开 MSYS 路径转换：MSYS 下 mvn 脚本的
# $JAVA_HOME/bin/java 会被转成 java.exe 找不到类（classworlds jar 路径
# 被吞），所以直接以 native 路径调 Launcher。
JAVA_HOME='C:\Program Files\Java\jdk-21.0.2'
MVN_HOME='E:\apache-maven-3.9.11'
exec "$JAVA_HOME\\bin\\java" \
  --enable-native-access=ALL-UNNAMED \
  -classpath "$MVN_HOME\\boot\\plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=$MVN_HOME\\bin\\m2.conf" \
  "-Dmaven.home=$MVN_HOME" \
  "-Dlibrary.jansi.path=$MVN_HOME\\lib\\jansi-native" \
  "-Dmaven.multiModuleProjectDirectory=$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
