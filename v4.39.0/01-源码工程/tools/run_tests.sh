#!/bin/bash
# 【v4.35.0】单元测试运行器：编译并执行 tools 下的 *Test.java
#
# 用法：./tools/run_tests.sh
#
# 编译策略：
#   · app/src 全量源码 + aapt2 生成的 R.java + tools/XxxTest.java 一起编译
#   · android.jar 放在 **classpath**（不是 bootclasspath），
#     这样 JDK 自带的 com.sun.net.httpserver 也能被解析到
#     （ProxyParallelTest 用它起本地假 CDN）
set -u
ASDK=/opt/asdk
AJ=$ASDK/platforms/android-22/android.jar
SRC=app/src/com/bixiaoqu/app
GEN=app/build/gen/com/bixiaoqu/app/R.java
OUT=/tmp/bxq_tests
CP="/tmp/jump3r.jar:/tmp/zxing-core.jar"

cd "$(dirname "$0")/.."
rm -rf $OUT && mkdir -p $OUT

find $SRC -name "*.java" > $OUT/all.txt
[ -f "$GEN" ] && echo "$GEN" >> $OUT/all.txt

javac -encoding UTF-8 -nowarn -source 8 -target 8 -cp "$CP:$AJ" \
  -d $OUT @$OUT/all.txt 2>$OUT/javac_all.log
if [ $? -ne 0 ]; then
  echo "!!! 主源码编译失败"
  grep -v "^注:\|^warning" $OUT/javac_all.log | head -40
  exit 1
fi

F=0
for T in AvSyncTest DiskCacheTest LayoutPolicyTest ParallelPlanTest ProxyParallelTest \
         RowFitTest StartupDiagTest ScheduleProfileTest LoadTunerTest StreamTunerTest \
         DanmakuClockTest; do
  [ -f "tools/$T.java" ] || continue
  javac -encoding UTF-8 -nowarn -source 8 -target 8 -cp "$OUT:$CP:$AJ" \
    -d $OUT "tools/$T.java" 2>$OUT/javac_$T.log
  if [ $? -ne 0 ]; then
    echo "=== $T 编译失败 ==="
    grep -v "^注:\|^warning" $OUT/javac_$T.log | head -30
    F=1
    continue
  fi
  # 测试类可能带 package 声明，也可能没有（默认包）——按实际声明决定类名
  PKG=$(grep -m1 -oE '^package[[:space:]]+[A-Za-z0-9_.]+' "tools/$T.java" | awk '{print $2}')
  [ -z "$PKG" ] && PKG=""
  CLS="$T"
  [ -n "$PKG" ] && CLS="$PKG.$T"
  echo "=== $T ==="
  java -cp "$OUT:$CP:$AJ" "$CLS" || F=1
done
exit $F
