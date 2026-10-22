---
name: tv-build-verify
description: 用固定脚本完成 blbl Android TV 的构建（单测 / checkThemeTokens / assembleDebug）、安装、Leanback 启动、方向键走查、焦点断言、截图与 logcat 验收。
whenToUse: 当需要构建、安装或设备验收 blbl（TV AVD 或真机），或用户要求冒烟测试、焦点/方向键检查、截图/logcat 证据时使用。不要用它做功能开发。
---

# TV 构建与设备验收（固定脚本）

## 这个 skill 只做一件事

把 **构建 → 安装 → Leanback 启动 → 方向键走查 → 断言焦点 → 截图/logcat** 固定成两条命令。不要每次重新找 JDK/SDK/AVD，也不要在对话里临时手写 adb 流程。

## 路径与前置

- JDK 17、SDK、adb、AVD 见 `AGENTS.md` §3；本机覆盖见 `AGENTS.local.md`。
- 在仓库根执行；脚本自己 `cd` 并设置 `JAVA_HOME`、`GRADLE_USER_HOME=<repo>/.gradle-dsh`。
- 设备需已启动：TV AVD `TCL_75Q10G_API28` 或 TCL 真机。
- host 需要 `python3`（仅用于解析 uiautomator dump）。

```bash
# 启动 TV AVD（HVF 报错时追加 -accel off）
/Volumes/Data/AndroidStudio/AndroidSDK/emulator/emulator \
  -avd TCL_75Q10G_API28 -no-window -gpu swiftshader_indirect \
  -no-audio -no-boot-anim

# 连接真机
/Volumes/Data/AndroidStudio/AndroidSDK/platform-tools/adb connect 192.168.2.67:5555
```

只有 SDK/AVD 写操作（装镜像、建 AVD）才需要更宽权限；构建和 install 不需要。

## 1. 构建

```bash
scripts/tv-build.sh
```

内部固定执行（**不要只跑 assembleDebug**）：

```bash
:app:testDebugUnitTest :app:checkThemeTokens :app:assembleDebug
```

通过：退出码 0，输出 `BUILD OK`，APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

失败处理：

- 读第一条 Gradle error；不要删测试、不要关闭 `checkThemeTokens`、不要改 `minSdk`/`targetSdk`/ABI 绕过。
- `checkThemeTokens` 失败 = layout 使用了固定调色板；改成主题属性，不要加白名单。

## 2. 设备冒烟

```bash
scripts/tv-verify.sh

# 指定设备 / 强制重建 / 只验已安装版本
scripts/tv-verify.sh --serial emulator-5554 --build
scripts/tv-verify.sh --skip-install

# 仅限测试设备：显式授权脚本接受首次《使用协议》
scripts/tv-verify.sh --accept-disclaimer
```

默认流程：

1. 自动选唯一的 Leanback/TV 设备（`TV_SERIAL` / `--serial` 优先；不会选手机）。
2. `adb install -r`，保留应用数据；**不会自动卸载**。
3. `am force-stop` + `logcat -c`；用 `cmd package resolve-activity` 解析 `LEANBACK_LAUNCHER` 组件，再 `am start -W -n <component>`。
4. 等 `MainActivity`（默认 90s；`--wait` 或 `TV_LAUNCH_TIMEOUT`）。
5. 启动弹窗检查：拒绝未知 overlay；协议弹窗默认失败，`--accept-disclaimer` 时才显式接受；更新弹窗要求人工“此版本不再提醒”。
6. 首帧 `uiautomator dump` + 截图。
7. 方向键路径：`RIGHT` → `DOWN` → `UP` → `LEFT` → `DOWN` → `UP`，每步重新 dump；不按 OK，避免误触播放。
8. 断言：唯一 `focused="true"`、bounds 非空、package=`blbl.cat3399`；焦点不能丢失；6 次方向键至少 2 次移动。
9. 启动/走查/结束 3 张截图；logcat 扫描 FATAL/ANR/Force finishing；检查进程存活。
10. 写 `summary.txt`（含 `focus_steps`、`focus_moves`）。

产物：`build/dsh-tv-verify/<timestamp>-<pid>/`

| 文件 | 内容 |
|---|---|
| `summary.txt` | PASS/FAIL、serial、APK、sha256、launch component、pid、`focus_steps`、`focus_moves` |
| `focus.tsv` | 每步焦点：class / resource-id / 焦点标签 / desc / bounds / moved |
| `ui/*.xml` | 每步 uiautomator dump（含 `00-startup-*`） |
| `screenshots/*.png` | 启动、走查、结束；失败时 `failure.png` |
| `logcat.txt` | 本次启动到验收结束的日志 |
| `device.txt` / `installed-path.txt` | 设备与安装信息 |

通过标准：退出码 0 + `RESULT: PASS` + 8 行焦点记录（启动 + 6 次方向键 + 结束）+ 至少 2 次移动 + 3 张 PNG + logcat 无崩溃/ANR + 进程存活。

## 3. 失败排查

- **没有 TV 设备**：脚本会列在线设备并给启动命令；不要用手机顶替。
- **install 失败**：签名冲突先问用户；不要 `adb uninstall`、不要清数据。
- **install 卡住/超时**：脚本默认 120s（`TV_INSTALL_TIMEOUT` 可调）；先检查 adb/模拟器状态，不要自动卸载。
- **等不到 MainActivity**：看 `screenshots/failure.png` 和 `logcat.txt`；常见原因是首次《使用协议》、更新弹窗、崩溃或 Splash 卡住。
  - 首次协议弹窗需要操作者手动接受一次；只有测试设备且明确授权时才用 `--accept-disclaimer`。
  - 更新弹窗请手动选择“此版本不再提醒”；不要代替用户升级。
- **uiautomator dump 失败**：等 1–2s 重试；确认没有系统权限弹窗/ANR。
- **焦点丢失**（dump 中 0 个或多个 `focused=true`）：视为真实焦点 bug；不要放宽断言。
- **单次方向键没移动**：记录在 `focus.tsv` 的 `moved` 列；只有总移动次数 < 2 才判失败，避免把边界/刷新误报成导航失效。
- **logcat 有 FATAL/ANR**：保存证据并定位堆栈；不要用“模拟器慢”解释崩溃。
- **截图 0 字节**：重试；播放器 DRM 页面可能 secure，但主界面不应失败。

## 4. 汇报格式

```text
构建: PASS (tv-build.sh)
设备: emulator-5554 / Android 9 / x86
启动: blbl.cat3399/.ui.MainActivity
方向键: 6 次中 N 次移动焦点（至少 2 次），结束仍在前台
证据: build/dsh-tv-verify/.../summary.txt
未验证: 真机播放/4K/DRM（模拟器 x86 无 native so）
```

## 5. 边界

- 本 skill 只验收，不改业务代码；不要为了 pass 修改断言或跳过测试。
- 不修改 `local.properties`、keystore、签名；不覆盖用户未提交改动。
- 不自动卸载、不清数据、不装到手机。
- 模拟器只证明 UI/焦点；播放、4K、DRM、armv7 必须回真机复验。
