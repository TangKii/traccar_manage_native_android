#!/usr/bin/env bash
# 构建 + 打包发布产物（发布流程唯一入口）。
#
# 约定（2026-11-14 起）：
#  1) 每次构建成功都要把产物上传到「项目资产」；
#  2) 上传文件名必须带日期时间（北京时间 YYYYMMDD-HHmmss），防止同名冲突；
#  3) 构建脚本只负责产出带时间戳的 APK / ZIP 归档，上传到项目资产由会话侧
#     在构建成功后调用项目资产接口完成（上传凭证需动态获取，脚本无法直连）；
#  4) 发布签名库 traccar-release.keystore 属于工程必需资产，放在工程根目录，
#     清理工作区时必须保留；构建前会校验其证书指纹，不匹配则中止，防止产出
#     不同签名、导致已安装版本无法覆盖安装的 APK。
#
# 用法: bash build-release.sh
# 产物: /workspace/releases/traccar-native-release_<TS>.apk / .zip
#       并同步 /workspace/traccar-native-release.apk / .zip 作为「最新版」固定名
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE="$(cd "$ROOT/.." && pwd)"
OUT_DIR="$WORKSPACE/releases"
# 优先使用工程内 wrapper（可复现）；没有 wrapper 时才回退到作者环境的固定路径
if [ -x "$ROOT/gradlew" ]; then
    GRADLE="$ROOT/gradlew"
else
    GRADLE="/opt/gradle/gradle-8.11.1/bin/gradle"
fi
if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER="$(command -v apksigner)"
else
    APKSIGNER="/opt/android-sdk/build-tools/36.0.0/apksigner"
fi
KEYSTORE="$ROOT/traccar-release.keystore"
if command -v keytool >/dev/null 2>&1; then
    KEYTOOL="$(command -v keytool)"
else
    KEYTOOL="/opt/jdk21/bin/keytool"
fi
# 原始发布证书 SHA-256（与已交付 APK 一致，勿改）
EXPECT_CERT_SHA256="8bf1ae5406a7aac3c58c5a10bb2ef5ea6d7e8e470aee350280b20562222ab1db"

echo "==> 1/5 校验发布签名库"
if [ ! -f "$KEYSTORE" ]; then
    echo "错误：发布签名库缺失：$KEYSTORE" >&2
    echo "请放回原始 traccar-release.keystore，否则新 APK 签名会变、已安装版本无法覆盖安装。" >&2
    exit 1
fi
if [ -x "$KEYTOOL" ]; then
    ACTUAL_CERT_SHA256="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" \
        -storepass traccar2026 -alias traccar 2>/dev/null \
        | awk '/SHA256:/ {sub(/^.*SHA256:/, ""); gsub(/[ \t:]/, ""); print tolower($0); exit}')"
    if [ -n "$ACTUAL_CERT_SHA256" ] && [ "$ACTUAL_CERT_SHA256" != "$EXPECT_CERT_SHA256" ]; then
        echo "错误：签名库证书指纹不匹配！" >&2
        echo "  期望: $EXPECT_CERT_SHA256" >&2
        echo "  实际: $ACTUAL_CERT_SHA256" >&2
        echo "请确认使用原始 keystore；不同签名会导致已安装版本无法覆盖安装。已中止构建。" >&2
        exit 1
    fi
    echo "    签名库指纹 OK: $ACTUAL_CERT_SHA256"
else
    echo "    警告：未找到 keytool，跳过指纹校验"
fi

echo "==> 2/5 编译 release"
cd "$ROOT"
# 版本号默认由构建时间生成（yyMMddHH，单调递增），也可外部传入覆盖
VERSION_CODE="$(TZ=Asia/Shanghai date +%y%m%d%H)"
VERSION_NAME="1.1.${VERSION_CODE}"
GRADLE_ARGS=(assembleRelease -PversionCode="$VERSION_CODE" -PversionName="$VERSION_NAME")
if [ "${GRADLE_OFFLINE:-0}" = "1" ]; then
    GRADLE_ARGS+=(--offline)
fi
"$GRADLE" "${GRADLE_ARGS[@]}"

APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SRC" ]; then
    echo "构建产物不存在: $APK_SRC" >&2
    exit 1
fi

echo "==> 3/5 校验签名"
"$APKSIGNER" verify --print-certs "$APK_SRC" | grep -i "SHA-256 digest" || true

TS="$(TZ=Asia/Shanghai date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
NAME="traccar-native-release_${TS}"
APK_DST="$OUT_DIR/${NAME}.apk"
ZIP_DST="$OUT_DIR/${NAME}.zip"

echo "==> 4/5 生成带时间戳归档 ($TS)"
cp "$APK_SRC" "$APK_DST"
( cd "$OUT_DIR" && zip -j "${NAME}.zip" "${NAME}.apk" >/dev/null )
unzip -t "$ZIP_DST" | tail -1

echo "==> 5/5 同步最新版固定名"
cp "$APK_DST" "$WORKSPACE/traccar-native-release.apk"
cp "$ZIP_DST" "$WORKSPACE/traccar-native-release.zip"

echo
echo "构建成功。产物清单："
echo "  归档 APK : $APK_DST"
echo "  归档 ZIP : $ZIP_DST"
echo "  最新版   : $WORKSPACE/traccar-native-release.apk"
echo "            $WORKSPACE/traccar-native-release.zip"
echo
echo "下一步（流程约定）：上传 $APK_DST 到项目资产"
