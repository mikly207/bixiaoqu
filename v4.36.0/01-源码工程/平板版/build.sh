#!/bin/bash
set -e
cd "$(dirname "$0")"
# 【v4.34.0】用法：./build.sh [base|ios]
#   base = 原版界面（默认）  ios = iOS 皮肤版
# 两版共用同一份源码，只在打包前把 manifest 里的 bxq.ios_ui 改成 true，
# 产出的 APK 名字不同，可以同时装在一起对比。
SKIN="${1:-base}"
ASDK=/opt/asdk
AJ=$ASDK/platforms/android-22/android.jar
AJ34=$ASDK/android-34/android.jar
OUT="$(pwd)/build"
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex $OUT/gen

echo "== javac =="
find src -name "*.java" > $OUT/srcs.txt
set -o pipefail
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $AJ \
  -cp /tmp/jump3r.jar:/tmp/zxing-core.jar \
  -d $OUT/classes @$OUT/srcs.txt 2>&1 | grep -v "^注:" | grep -v "^warning"
ls $OUT/classes/com/bixiaoqu/app/*.class | wc -l

echo "== d8 =="
find $OUT/classes -name "*.class" > $OUT/classlist.txt
$ASDK/android-14/d8 --release --min-api 22 --lib $AJ34 --output $OUT/dex @$OUT/classlist.txt

echo "== manifest ($SKIN) =="
cp AndroidManifest.xml $OUT/AndroidManifest.xml
VNAME="4.36.0"
APK="/workspace/哔小趣.apk"
if [ "$SKIN" = "ios" ]; then
  sed -i 's|android:name="bxq.ios_ui" android:value="false"|android:name="bxq.ios_ui" android:value="true"|' $OUT/AndroidManifest.xml
  # 版本名加 -ios 后缀，安装后在系统里能一眼区分两个包
  sed -i 's|android:versionName="\([^"]*\)"|android:versionName="\1-ios"|' $OUT/AndroidManifest.xml
  VNAME="4.36.0-ios"
  APK="/workspace/哔小趣-iOS.apk"
fi

echo "== aapt2 =="
$ASDK/android-14/aapt2 compile --dir res -o $OUT/res.zip
$ASDK/android-14/aapt2 link -o $OUT/base.apk -I $AJ34 \
  --manifest $OUT/AndroidManifest.xml -R $OUT/res.zip \
  --java $OUT/gen --auto-add-overlay --min-sdk-version 22 --target-sdk-version 34 \
  --version-code 136 --version-name "$VNAME"

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
cd $OUT && zip -q -j unsigned.apk dex/classes.dex
$ASDK/android-14/zipalign -f 4 unsigned.apk aligned.apk
$ASDK/android-14/apksigner sign --ks /workspace/哔小趣签名密钥.jks --ks-pass pass:bxq202501 \
  --ks-key-alias bxq --key-pass pass:bxq202501 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" aligned.apk
$ASDK/android-14/apksigner verify "$APK" && echo "sign OK: $APK"
ls -la "$APK"
