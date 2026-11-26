#!/bin/bash
set -e
cd "$(dirname "$0")"
ASDK=/opt/asdk
AJ=$ASDK/platforms/android-22/android.jar   # 【v4.11.0】用 API22 平台编译，强制全量 API 兼容检查
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

echo "== aapt2 =="
$ASDK/android-14/aapt2 compile --dir res -o $OUT/res.zip
$ASDK/android-14/aapt2 link -o $OUT/base.apk -I $AJ34 \
  --manifest AndroidManifest.xml -R $OUT/res.zip \
  --java $OUT/gen --auto-add-overlay --min-sdk-version 22 --target-sdk-version 34 \
  --version-code 130 --version-name 4.30.0-mobile

echo "== package =="
cp $OUT/base.apk $OUT/unsigned.apk
cd $OUT && zip -q -j unsigned.apk dex/classes.dex
# 已移除 VLC 引擎（libvlc.so 41MB / 压缩后约 19MB），APK 内不再包含任何 native 库
$ASDK/android-14/zipalign -f 4 unsigned.apk aligned.apk
$ASDK/android-14/apksigner sign --ks /workspace/哔小趣签名密钥.jks --ks-pass pass:bxq202501 \
  --ks-key-alias bxq --key-pass pass:bxq202501 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out /workspace/哔小趣-手机版.apk aligned.apk
$ASDK/android-14/apksigner verify --print-certs /workspace/哔小趣-手机版.apk | head -5
ls -la /workspace/哔小趣-手机版.apk
