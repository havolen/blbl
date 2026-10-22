#!/usr/bin/env bash
# 固定构建入口：单测 + 主题 token 检查 + debug 包。
# 所有工具链路径固定，避免每个 DSH 会话重新找 JDK/SDK，也避免 workspace-write 下反复升权。
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_JAVA_HOME="$HOME/.local/share/jdks/temurin-17.0.20.1/Contents/Home"
DEFAULT_SDK="/Volumes/Data/AndroidStudio/AndroidSDK"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

log() { printf '[tv-build] %s\n' "$*"; }
warn() { printf '[tv-build] WARN: %s\n' "$*" >&2; }
die() {
    printf '[tv-build] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法: scripts/tv-build.sh [额外的 Gradle 参数...]

固定执行:
  :app:testDebugUnitTest  :app:checkThemeTokens  :app:assembleDebug

环境变量（一般不用改）:
  TV_JAVA_HOME       覆盖 JDK 17 路径
  TV_GRADLE_USER_HOME 覆盖 Gradle 缓存（默认 <repo>/.gradle-dsh）
  ANDROID_SDK_ROOT / ANDROID_HOME 覆盖 SDK 路径

示例:
  scripts/tv-build.sh
  scripts/tv-build.sh --stacktrace
EOF
}

case "${1:-}" in
    -h | --help)
        usage
        exit 0
        ;;
esac

JAVA_HOME="${TV_JAVA_HOME:-$DEFAULT_JAVA_HOME}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$DEFAULT_SDK}}"
GRADLE_USER_HOME="${TV_GRADLE_USER_HOME:-$ROOT/.gradle-dsh}"

export JAVA_HOME
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export GRADLE_USER_HOME
export PATH="$JAVA_HOME/bin:$SDK_ROOT/platform-tools:$PATH"

[ -x "$JAVA_HOME/bin/java" ] || die "JDK 17 不存在: $JAVA_HOME（可用 TV_JAVA_HOME 覆盖）"
[ -d "$SDK_ROOT" ] || die "Android SDK 不存在: $SDK_ROOT（可用 ANDROID_SDK_ROOT 覆盖）"
[ -d "$SDK_ROOT/platforms/android-36" ] ||
    die "缺少 compileSdk 36: $SDK_ROOT/platforms/android-36（不要改 compileSdk 绕过）"
[ -x "$SDK_ROOT/platform-tools/adb" ] || warn "adb 不存在: $SDK_ROOT/platform-tools/adb"

JAVA_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')"
case "$JAVA_VERSION" in
    17.*) ;;
    *) die "需要 JDK 17，当前为 ${JAVA_VERSION:-unknown}: $JAVA_HOME；可用 TV_JAVA_HOME 覆盖" ;;
esac

mkdir -p "$GRADLE_USER_HOME"
cd "$ROOT"

TASKS=(:app:testDebugUnitTest :app:checkThemeTokens :app:assembleDebug)
if [ "$#" -gt 0 ]; then
    TASKS+=("$@")
fi

log "JDK:              $JAVA_HOME"
log "SDK:              $SDK_ROOT"
log "GRADLE_USER_HOME: $GRADLE_USER_HOME"
log "tasks:            ${TASKS[*]}"

./gradlew --no-daemon --console=plain "${TASKS[@]}"

[ -f "$APK" ] || die "构建结束但 APK 不存在: $APK"

APK_SIZE="$(du -h "$APK" | awk '{ print $1 }')"
log "BUILD OK"
log "APK: $APK ($APK_SIZE)"
