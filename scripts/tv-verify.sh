#!/usr/bin/env bash
# 固定设备冒烟入口：安装 -> Leanback 启动 -> 方向键走查 -> 焦点断言 -> 截图/logcat。
# 只做设备验收，不改应用代码，不卸载应用，不清除用户数据。
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_JAVA_HOME="$HOME/.local/share/jdks/temurin-17.0.20.1/Contents/Home"
DEFAULT_SDK="/Volumes/Data/AndroidStudio/AndroidSDK"
PKG="blbl.cat3399"
DEFAULT_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

SERIAL="${TV_SERIAL:-}"
APK="$DEFAULT_APK"
OUT=""
RUN_BUILD=0
SKIP_INSTALL=0
ACCEPT_DISCLAIMER=0
WAIT_SECONDS="${TV_LAUNCH_TIMEOUT:-90}"
KEY_DELAY="${TV_KEY_DELAY:-1.0}"
TV_AVD="${TV_AVD:-TCL_75Q10G_API28}"
INSTALL_TIMEOUT="${TV_INSTALL_TIMEOUT:-120}"

# Android keycodes（数字形式兼容 5.1–9）
KEY_LEFT=21
KEY_RIGHT=22
KEY_UP=19
KEY_DOWN=20
KEY_CENTER=23

log() { printf '[tv-verify] %s\n' "$*"; }
warn() { printf '[tv-verify] WARN: %s\n' "$*" >&2; }
die() {
    printf '[tv-verify] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
用法: scripts/tv-verify.sh [选项]

安装最新 debug APK，按 LEANBACK_LAUNCHER 启动，走方向键并断言焦点，
最后保存 uiautomator dump、截图和 logcat。

选项:
  --serial <serial>   adb 序列号（默认 $TV_SERIAL，否则自动选唯一的 Leanback/TV 设备）
  --apk <path>        APK 路径（默认 app/build/outputs/apk/debug/app-debug.apk）
  --out <dir>         产物目录（默认 build/dsh-tv-verify/<timestamp>-<pid>）
  --build             先运行 scripts/tv-build.sh
  --skip-install      不安装，直接验证已安装版本
  --accept-disclaimer 显式接受首次启动的《使用协议》（默认不代替用户接受）
  --wait <seconds>    等待 MainActivity 的超时（默认 90）
  -h, --help          显示帮助

环境变量:
  TV_SERIAL           adb 序列号
  TV_LAUNCH_TIMEOUT   MainActivity 超时秒数
  TV_INSTALL_TIMEOUT  adb install 超时秒数（默认 120）
  TV_KEY_DELAY        每次方向键后的等待秒数（默认 1.0）
  TV_JAVA_HOME        覆盖 JDK 17 路径

示例:
  scripts/tv-verify.sh
  scripts/tv-verify.sh --serial emulator-5554 --build
  scripts/tv-verify.sh --skip-install
  scripts/tv-verify.sh --accept-disclaimer
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial)
            [ "$#" -ge 2 ] || die "--serial 需要参数"
            SERIAL="$2"
            shift 2
            ;;
        --apk)
            [ "$#" -ge 2 ] || die "--apk 需要参数"
            APK="$2"
            shift 2
            ;;
        --out)
            [ "$#" -ge 2 ] || die "--out 需要参数"
            OUT="$2"
            shift 2
            ;;
        --build)
            RUN_BUILD=1
            shift
            ;;
        --skip-install)
            SKIP_INSTALL=1
            shift
            ;;
        --accept-disclaimer)
            ACCEPT_DISCLAIMER=1
            shift
            ;;
        --wait)
            [ "$#" -ge 2 ] || die "--wait 需要参数"
            WAIT_SECONDS="$2"
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            die "未知参数: $1（--help 查看用法）"
            ;;
    esac
done

case "$WAIT_SECONDS" in
    '' | *[!0-9]*) die "--wait / TV_LAUNCH_TIMEOUT 必须是整数秒" ;;
esac
[ "$WAIT_SECONDS" -ge 5 ] || die "等待时间至少 5 秒"
case "$INSTALL_TIMEOUT" in
    '' | *[!0-9]*) die "TV_INSTALL_TIMEOUT 必须是整数秒" ;;
esac
[ "$INSTALL_TIMEOUT" -ge 10 ] || die "TV_INSTALL_TIMEOUT 至少 10 秒"

case "$KEY_DELAY" in
    '' | *[!0-9.]*) die "TV_KEY_DELAY 必须是数字（秒）" ;;
esac

JAVA_HOME="${TV_JAVA_HOME:-$DEFAULT_JAVA_HOME}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$DEFAULT_SDK}}"
ADB="${TV_ADB:-$SDK_ROOT/platform-tools/adb}"
GRADLE_USER_HOME="${TV_GRADLE_USER_HOME:-$ROOT/.gradle-dsh}"

export JAVA_HOME
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export ADB
export GRADLE_USER_HOME
export PATH="$JAVA_HOME/bin:$SDK_ROOT/platform-tools:$PATH"

command -v python3 >/dev/null 2>&1 || die "需要 python3 解析 uiautomator dump"
[ -x "$ADB" ] || die "adb 不存在: $ADB（可用 TV_ADB 覆盖）"

case "$APK" in
    /*) ;;
    *) APK="$ROOT/$APK" ;;
esac

if [ -z "$OUT" ]; then
    OUT="$ROOT/build/dsh-tv-verify/$(date +%Y%m%d-%H%M%S)-$$"
else
    case "$OUT" in
        /*) ;;
        *) OUT="$ROOT/$OUT" ;;
    esac
fi
mkdir -p "$OUT/ui" "$OUT/screenshots"

prev_sig=""
focus_moves=0
ANDROID_RELEASE=""
ANDROID_API=""
DEVICE_ABI=""

# ---------------------------------------------------------------- utilities

online_devices() {
    "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

device_is_tv() {
    local s="$1"
    local props features packages
    props="$("$ADB" -s "$s" shell 'getprop ro.build.characteristics; getprop ro.product.model; getprop ro.product.name; getprop ro.product.device' 2>/dev/null | tr -d '\r' | tr '\n' ' ' || true)"
    case "$props" in
        *[Tt][Vv]* | *[Aa][Tt][Vv]* | *TCL* | *BRAVIA* | *SHIELD*) return 0 ;;
    esac
    features="$("$ADB" -s "$s" shell pm list features 2>/dev/null | tr -d '\r' || true)"
    case "$features" in
        *android.software.leanback*) ;;
        *) return 1 ;;
    esac
    packages="$("$ADB" -s "$s" shell pm list packages 2>/dev/null | tr -d '\r' || true)"
    case "$packages" in
        *leanbacklauncher* | *android.tv.*) return 0 ;;
    esac
    return 1
}

print_no_device() {
    local avd="${TV_AVD:-TCL_75Q10G_API28}"
    {
        printf '在线设备:\n'
        online_devices | sed 's/^/  - /'
        printf '\n启动 TV AVD:\n'
        printf '  %s/emulator/emulator -avd %s -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim\n' "$SDK_ROOT" "$avd"
        printf '  # 若 HVF 报错，追加 -accel off（软件渲染，开机约 6 分钟）\n'
        printf '\n连接真机:\n'
        printf '  %s connect <tv-ip>:5555\n' "$ADB"
        printf '\n指定设备:\n'
        printf '  scripts/tv-verify.sh --serial <serial>\n'
    } >&2
}

pick_serial() {
    if [ -n "$SERIAL" ]; then
        local state
        state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
        [ "$state" = "device" ] || die "指定设备不可用: $SERIAL（state=${state:-unknown}）"
        return 0
    fi

    local devices d found=""
    devices="$(online_devices)"
    if [ -z "$devices" ]; then
        print_no_device
        die "没有在线 adb 设备"
    fi
    for d in $devices; do
        if device_is_tv "$d"; then
            found="${found}${d}"$'\n'
        fi
    done

    local count
    count="$(printf '%s' "$found" | sed '/^$/d' | wc -l | tr -d ' ')"
    if [ "$count" -eq 1 ]; then
        SERIAL="$(printf '%s' "$found" | sed -n '1p')"
        return 0
    fi
    if [ "$count" -eq 0 ]; then
        print_no_device
        die "未找到 Leanback/TV 设备；不会把 TV 包装到手机"
    fi
    printf '[tv-verify] 找到多个 TV 设备:\n%s' "$found" >&2
    die "请用 --serial 或 TV_SERIAL 指定，避免装错设备"
}

wait_for_boot() {
    local i=0 boot=""
    while [ "$i" -lt 90 ]; do
        boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
        if [ "$boot" = "1" ]; then
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    warn "sys.boot_completed 未就绪，继续尝试连接"
}

record_device() {
    local model release api abi avd
    model="$("$ADB" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
    release="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
    api="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
    abi="$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
    avd="${TV_AVD:-unknown}"
    ANDROID_RELEASE="$release"
    ANDROID_API="$api"
    DEVICE_ABI="$abi"
    {
        printf 'serial=%s\n' "$SERIAL"
        printf 'model=%s\n' "$model"
        printf 'android=%s\n' "$release"
        printf 'sdk=%s\n' "$api"
        printf 'abi=%s\n' "$abi"
        printf 'avd=%s\n' "$avd"
    } >"$OUT/device.txt"
    log "设备: $SERIAL  Android $release (API $api)  $abi  $model"
}

current_focus() {
    local win act
    win="$("$ADB" -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
    if [ -z "$win" ]; then
        act="$("$ADB" -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | grep -m1 'mResumedActivity' || true)"
        if [ -n "$act" ]; then
            printf '%s\n' "$act"
            return 0
        fi
    fi
    printf '%s\n' "$win"
}

check_foreground() {
    local win
    win="$(current_focus)"
    case "$win" in
        *"$PKG"/*) return 0 ;;
        *) return 1 ;;
    esac
}

wait_for_main() {
    local i=0 win=""
    while [ "$i" -lt "$WAIT_SECONDS" ]; do
        win="$(current_focus)"
        case "$win" in
            *"$PKG"*MainActivity*) return 0 ;;
        esac
        i=$((i + 1))
        sleep 1
    done
    return 1
}

run_with_timeout() {
    local seconds="$1"
    shift
    local out="$OUT/.cmd-timeout.out"
    "$@" >"$out" 2>&1 &
    local cmd_pid=$!
    (
        sleep "$seconds"
        if kill -0 "$cmd_pid" 2>/dev/null; then
            kill -TERM "$cmd_pid" 2>/dev/null || true
            sleep 2
            kill -KILL "$cmd_pid" 2>/dev/null || true
        fi
    ) &
    local watchdog_pid=$!
    local rc=0
    wait "$cmd_pid" || rc=$?
    kill "$watchdog_pid" 2>/dev/null || true
    cat "$out"
    return "$rc"
}

dump_ui() {
    local name="$1"
    local remote="/sdcard/tv-verify-ui.xml"
    local local_file="$OUT/ui/${name}.xml"
    local i=0
    while [ "$i" -lt 5 ]; do
        if "$ADB" -s "$SERIAL" shell uiautomator dump --compressed "$remote" >/dev/null 2>&1 &&
            "$ADB" -s "$SERIAL" pull "$remote" "$local_file" >/dev/null 2>&1 &&
            [ -s "$local_file" ]; then
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    warn "uiautomator dump 失败: $name"
    return 1
}

parse_focus() {
    local xml="$1"
    python3 - "$xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception as exc:  # noqa: BLE001 - report a bounded diagnostic
    print(f"parse error: {exc}", file=sys.stderr)
    sys.exit(3)

nodes = [n for n in root.iter() if n.get("focused") == "true"]
if len(nodes) != 1:
    print(f"expected exactly 1 focused node, got {len(nodes)}", file=sys.stderr)
    for n in nodes[:5]:
        print(
            f"  class={n.get('class', '')} id={n.get('resource-id', '')} "
            f"text={n.get('text', '')!r} bounds={n.get('bounds', '')}",
            file=sys.stderr,
        )
    sys.exit(2)

node = nodes[0]
bounds = node.get("bounds", "")
match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
if not match:
    print(f"bad bounds: {bounds!r}", file=sys.stderr)
    sys.exit(2)
x1, y1, x2, y2 = map(int, match.groups())
if x2 <= x1 or y2 <= y1:
    print(f"empty bounds: {bounds}", file=sys.stderr)
    sys.exit(2)

package = node.get("package", "")
if package and package != "blbl.cat3399":
    print(f"focused package is {package}, not blbl.cat3399", file=sys.stderr)
    sys.exit(2)

def clean(value):
    return re.sub(r"\s+", " ", value or "").strip()

label = clean(node.get("text", "")) or clean(node.get("content-desc", ""))
if not label:
    fallback = []
    for child in node.iter():
        if child is node:
            continue
        value = clean(child.get("text", "")) or clean(child.get("content-desc", ""))
        if not value:
            continue
        if child.get("resource-id", "").endswith("/tv_title"):
            label = value
            break
        if len(fallback) < 2:
            fallback.append(value)
    if not label:
        label = " / ".join(fallback)

print(
    "\x1f".join(
        [
            clean(node.get("class", "")),
            clean(node.get("resource-id", "")),
            label,
            clean(node.get("content-desc", "")),
            bounds,
        ]
    )
)
PY
}

focused_text() {
    local xml="$1"
    python3 - "$xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter():
    if node.get("focused") == "true":
        parts = []
        for child in node.iter():
            value = child.get("text") or child.get("content-desc") or ""
            if value:
                parts.append(value)
        print(" ".join(parts).strip())
        break
PY
}

record_focus() {
    local name="$1"
    local info cls rid text desc bounds sig moved=0
    if ! dump_ui "$name"; then
        return 1
    fi
    if ! info="$(parse_focus "$OUT/ui/${name}.xml")"; then
        return 1
    fi
    IFS=$'\x1f' read -r cls rid text desc bounds <<<"$info"
    sig="${cls}|${rid}|${bounds}"
    if [ -n "$prev_sig" ] && [ "$sig" != "$prev_sig" ]; then
        moved=1
        case "$name" in
            0[2-7]-*) focus_moves=$((focus_moves + 1)) ;;
        esac
    fi
    prev_sig="$sig"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$cls" "$rid" "$text" "$desc" "$bounds" "$moved" >>"$OUT/focus.tsv"
    if [ "$moved" = "1" ]; then
        printf '[tv-verify] focus %-18s moved  %s | %s | %s\n' "$name" "$cls" "$bounds" "$text"
    else
        printf '[tv-verify] focus %-18s same   %s | %s | %s\n' "$name" "$cls" "$bounds" "$text"
    fi
}

screenshot() {
    local name="$1"
    local file="$OUT/screenshots/${name}.png"
    local i=0 magic=""
    while [ "$i" -lt 3 ]; do
        if "$ADB" -s "$SERIAL" exec-out screencap -p >"$file" 2>/dev/null && [ -s "$file" ]; then
            magic="$(od -An -tx1 -N4 "$file" | tr -d ' \n')"
            if [ "$magic" = "89504e47" ]; then
                log "截图: $file"
                return 0
            fi
        fi
        i=$((i + 1))
        sleep 1
    done
    warn "截图失败: $name"
    return 1
}

press_key() {
    local key="$1"
    "$ADB" -s "$SERIAL" shell input keyevent "$key" >/dev/null 2>&1 || return 1
    sleep "$KEY_DELAY"
}

check_startup_overlays() {
    local i=0 xml ftext
    while [ "$i" -lt 3 ]; do
        xml="$OUT/ui/00-startup-${i}.xml"
        dump_ui "00-startup-${i}" || return 1
        if ! grep -q 'resource-id="blbl.cat3399:id/overlay"' "$xml"; then
            return 0
        fi
        if grep -q '使用协议' "$xml"; then
            if [ "$ACCEPT_DISCLAIMER" -ne 1 ]; then
                die "检测到首次《使用协议》弹窗：请手动接受一次，或用 --accept-disclaimer 显式授权脚本接受"
            fi
            ftext="$(focused_text "$xml")"
            case "$ftext" in
                *"接受并继续"*) ;;
                *) die "协议弹窗焦点不在‘接受并继续’（当前: ${ftext:-空}），未自动接受" ;;
            esac
            log "按 --accept-disclaimer 接受《使用协议》"
            press_key "$KEY_CENTER" || die "接受协议时按键失败"
            sleep 2
            i=$((i + 1))
            continue
        fi
        if grep -q '发现新版本' "$xml"; then
            die "检测到更新弹窗：请手动选择‘此版本不再提醒’（推荐）或‘本次关闭’后重跑；脚本不代替用户升级"
        fi
        die "启动时有未知应用弹窗；请手动处理后重跑（dump: $xml）"
    done
    die "启动弹窗处理 3 次后仍未消失（dump: $OUT/ui/00-startup-*.xml）"
}

collect_logcat() {
    [ -n "$SERIAL" ] || return 0
    if ! "$ADB" -s "$SERIAL" logcat -d -v threadtime -t 20000 >"$OUT/logcat.txt" 2>/dev/null; then
        "$ADB" -s "$SERIAL" logcat -d -v threadtime >"$OUT/logcat.txt" 2>/dev/null || true
    fi
}

check_logcat() {
    local log="$OUT/logcat.txt"
    if [ ! -s "$log" ]; then
        warn "logcat 为空，无法确认无崩溃"
        return 1
    fi
    if grep -q 'FATAL EXCEPTION' "$log" && grep -q 'Process: blbl\.cat3399' "$log"; then
        printf '[tv-verify] logcat 发现 FATAL EXCEPTION:\n' >&2
        grep -n -A 20 'FATAL EXCEPTION' "$log" >&2 || true
        return 1
    fi
    if grep -q 'ANR in blbl\.cat3399' "$log"; then
        printf '[tv-verify] logcat 发现 ANR:\n' >&2
        grep -n -A 20 'ANR in blbl\.cat3399' "$log" >&2 || true
        return 1
    fi
    if grep -q 'Force finishing activity blbl\.cat3399' "$log"; then
        printf '[tv-verify] logcat 发现应用被强制结束:\n' >&2
        grep -n 'Force finishing activity blbl\.cat3399' "$log" >&2 || true
        return 1
    fi
    return 0
}

current_pid() {
    local pid=""
    pid="$("$ADB" -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{ print $1; exit }' || true)"
    if [ -z "$pid" ]; then
        pid="$("$ADB" -s "$SERIAL" shell ps -A 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '$NF == p { print $2; exit }' || true)"
    fi
    if [ -z "$pid" ]; then
        pid="$("$ADB" -s "$SERIAL" shell ps 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '$NF == p { print $2; exit }' || true)"
    fi
    printf '%s' "$pid"
}

resolve_leanback_component() {
    local out component
    out="$("$ADB" -s "$SERIAL" shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LEANBACK_LAUNCHER \
        "$PKG" 2>/dev/null | tr -d '\r' || true)"
    component="$(printf '%s\n' "$out" | awk '/^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/ { print; exit }')"
    printf '%s' "$component"
}

write_pass_summary() {
    local activity="$1"
    local pid="$2"
    local sha=""
    if command -v shasum >/dev/null 2>&1; then
        sha="$(shasum -a 256 "$APK" | awk '{ print $1 }' || true)"
    elif command -v sha256sum >/dev/null 2>&1; then
        sha="$(sha256sum "$APK" | awk '{ print $1 }' || true)"
    fi
    {
        printf 'result=PASS\n'
        printf 'serial=%s\n' "$SERIAL"
        printf 'avd=%s\n' "${TV_AVD:-unknown}"
        printf 'android=%s\n' "$ANDROID_RELEASE"
        printf 'sdk=%s\n' "$ANDROID_API"
        printf 'abi=%s\n' "$DEVICE_ABI"
        printf 'apk=%s\n' "$APK"
        printf 'apk_sha256=%s\n' "$sha"
        printf 'launch=%s\n' "$activity"
        printf 'pid=%s\n' "$pid"
        printf 'focus_steps=%s\n' "$(wc -l <"$OUT/focus.tsv" | tr -d ' ')"
        printf 'focus_moves=%s\n' "$focus_moves"
        printf 'screenshots=%s\n' "$(find "$OUT/screenshots" -type f -name '*.png' | wc -l | tr -d ' ')"
        printf 'logcat=%s\n' "$OUT/logcat.txt"
        printf 'artifacts=%s\n' "$OUT"
    } >"$OUT/summary.txt"
}

finalize() {
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        if [ -n "${OUT:-}" ] && [ -d "${OUT:-}" ]; then
            collect_logcat >/dev/null 2>&1 || true
            screenshot "failure" >/dev/null 2>&1 || true
            {
                printf 'result=FAIL\n'
                printf 'exit_code=%s\n' "$rc"
                printf 'serial=%s\n' "${SERIAL:-}"
                printf 'apk=%s\n' "${APK:-}"
                printf 'artifacts=%s\n' "$OUT"
            } >"$OUT/summary.txt" || true
            printf '[tv-verify] FAILED (exit %s). 证据: %s\n' "$rc" "$OUT" >&2
        fi
    fi
}
trap finalize EXIT

# ---------------------------------------------------------------- main flow

pick_serial
wait_for_boot
record_device

if [ "$RUN_BUILD" -eq 1 ] || { [ "$SKIP_INSTALL" -eq 0 ] && [ ! -f "$APK" ]; }; then
    log "运行固定构建..."
    "$ROOT/scripts/tv-build.sh" || die "构建失败"
fi

if [ "$SKIP_INSTALL" -eq 0 ]; then
    [ -f "$APK" ] || die "APK 不存在: $APK（先跑 scripts/tv-build.sh）"
    log "安装: $APK"
    install_out=""
    if ! install_out="$(run_with_timeout "$INSTALL_TIMEOUT" "$ADB" -s "$SERIAL" install --no-streaming -r "$APK")"; then
        printf '%s\n' "$install_out" >&2
        die "adb install 失败或超时（${INSTALL_TIMEOUT}s）；签名冲突请人工确认，脚本不会自动卸载"
    fi
    printf '%s\n' "$install_out"
    if ! printf '%s\n' "$install_out" | grep -q 'Success'; then
        die "adb install 未返回 Success；脚本不会自动卸载应用"
    fi
    "$ADB" -s "$SERIAL" shell pm path "$PKG" >"$OUT/installed-path.txt" 2>&1 || true
else
    log "跳过安装（--skip-install）"
fi

"$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1 || warn "logcat -c 失败"
sleep 0.5

log "Leanback 启动..."
COMPONENT="$(resolve_leanback_component)"
launch_out=""
if [ -n "$COMPONENT" ]; then
    log "Leanback 组件: $COMPONENT"
    if ! launch_out="$("$ADB" -s "$SERIAL" shell am start -W -n "$COMPONENT" 2>&1)"; then
        printf '%s\n' "$launch_out" >&2
        die "Leanback am start 失败"
    fi
    launch_out="$(printf '%s' "$launch_out" | tr -d '\r')"
    printf '%s\n' "$launch_out"
    printf '%s\n' "$launch_out" >"$OUT/launch.txt"
    case "$launch_out" in
        *"Status: ok"*) ;;
        *) die "Leanback 启动未返回 Status: ok" ;;
    esac
    ACTIVITY_LINE="$COMPONENT"
else
    warn "未解析到 Leanback 组件，回退 monkey -c LEANBACK_LAUNCHER"
    if ! launch_out="$("$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1)"; then
        printf '%s\n' "$launch_out" >&2
        die "Leanback monkey 启动失败"
    fi
    launch_out="$(printf '%s' "$launch_out" | tr -d '\r')"
    printf '%s\n' "$launch_out"
    printf '%s\n' "$launch_out" >"$OUT/launch.txt"
    case "$launch_out" in
        *"Events injected"*) ;;
        *) die "monkey 未能启动 Leanback 入口" ;;
    esac
    ACTIVITY_LINE="unknown (monkey LEANBACK_LAUNCHER)"
fi

if ! wait_for_main; then
    die "等待 MainActivity 超时（${WAIT_SECONDS}s）；可能是 Splash 卡住、弹窗或崩溃"
fi
sleep 2

check_startup_overlays || die "启动弹窗检查失败"

: >"$OUT/focus.tsv"
log "焦点断言 + 方向键走查..."

record_focus "01-launch" || die "启动后未找到唯一焦点"
screenshot "01-launch" || die "启动截图失败"

press_key "$KEY_RIGHT" || die "发送 RIGHT 失败"
record_focus "02-right" || die "RIGHT 后焦点丢失"
screenshot "02-after-dpad" || die "方向键走查截图失败"
press_key "$KEY_DOWN" || die "发送 DOWN 失败"
record_focus "03-down" || die "DOWN 后焦点丢失"
press_key "$KEY_UP" || die "发送 UP 失败"
record_focus "04-up" || die "UP 后焦点丢失"
press_key "$KEY_LEFT" || die "发送 LEFT 失败"
record_focus "05-left" || die "LEFT 后焦点丢失"
press_key "$KEY_DOWN" || die "发送 DOWN 失败"
record_focus "06-down" || die "DOWN 后焦点丢失"
press_key "$KEY_UP" || die "发送 UP 失败"
record_focus "07-up" || die "UP 后焦点丢失"

[ "$focus_moves" -ge 2 ] || die "方向键走查期间焦点只移动了 ${focus_moves} 次（至少 2 次），导航可能失效"

sleep 1
check_foreground || die "方向键走查后应用不在前台"
record_focus "08-final" || die "走查结束焦点丢失"
screenshot "03-final" || die "最终截图失败"

collect_logcat
check_logcat || die "logcat 发现崩溃/ANR"
PID="$(current_pid)"
[ -n "$PID" ] || die "应用进程不存在（可能已崩溃）"
check_foreground || die "走查后应用不在前台"

write_pass_summary "$ACTIVITY_LINE" "$PID"
log "RESULT: PASS"
log "serial:  $SERIAL"
log "pid:     $PID"
log "focus:   $(wc -l <"$OUT/focus.tsv" | tr -d ' ') steps, direction moves ${focus_moves}/6"
log "证据:    $OUT"
