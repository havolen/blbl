# AGENTS.md — blbl-android（Android TV / 遥控器优先）

本文件是仓库级常驻指令，控制在 10KB 内。**先读本文件，再按需加载 skill**；不要每次从零搜索工具链。

- 固定流程 skill：`.dsh/skills/tv-build-verify/SKILL.md`
- 本机覆盖：`AGENTS.local.md`（gitignored，DSH 自动加载；模板 `AGENTS.local.md.example`）

## 1. 项目使命

第三方哔哩哔哩 Android 客户端，覆盖电视、盒子、车机与平板。**遥控器优先、老设备优先、流畅优先。**

- 目标不是“功能最多”，而是在 1–4GB RAM 的 Android 5.1–9 设备上稳定、可操作地看视频。
- 任何改动都要回答：老设备是否更卡/更耗内存？遥控器是否还能走通？1080p UI 是否仍然完整？
- 性能是不可交易项；不要用新增功能、动画或视觉包装掩盖卡顿、掉帧、OOM。

## 2. 支持矩阵（必须保持）

| 维度 | 必须支持 | 验收含义 |
|---|---|---|
| 系统 | Android 5.1–9（API 22–28） | `minSdk 21` 只是构建下限；验收基线是 5.1 与 9，不要默认使用 API 29+ 行为 |
| ABI | `armeabi-v7a`、`arm64-v8a` | 电视真机常见 32 位；x86 模拟器只验 UI/焦点，native/播放/DRM 必须回真机 |
| 内存 | 1–4 GB | TV heap 常为 512MB；避免一次性大图/大列表；OOM 按 P0 处理 |
| 分辨率 | 1080p UI、4K 面板/视频 | 不硬编码 1920×1080；用 dp/约束布局；4K 走视频通路，UI 仍按 1080p 验证 |
| 输入 | 遥控器优先 | 主路径全部可用方向键、OK、BACK 完成；焦点可见、可预测、不丢失、不困在子视图 |
| 设备 | TCL 75Q10G（Android 9 / armv7 / 4GB / 4K）+ API 28 ATV AVD | 见 §3；模拟器不能替代真机播放验证 |

## 3. 工具链（绝对路径，直接用）

| 用途 | 绝对路径 / 值 |
|---|---|
| JDK 17 | `/Users/hayzax/.local/share/jdks/temurin-17.0.20.1/Contents/Home` |
| Android SDK | `/Volumes/Data/AndroidStudio/AndroidSDK` |
| adb | `/Volumes/Data/AndroidStudio/AndroidSDK/platform-tools/adb` |
| TV AVD | `TCL_75Q10G_API28`（`system-images;android-28;android-tv;x86`，1080p/320dpi/2GB） |
| TV 模拟器 serial | `emulator-5554` |
| TCL 真机 | `192.168.2.67:5555`（Android 9，armeabi-v7a，4GB） |

注意：

- PATH 里的 `java` 是 JBR 21，**不要用**；所有 Gradle 命令显式设置 `JAVA_HOME`。
- SDK 不在默认 `~/Library/Android/sdk`；不要重新 `sdkmanager` 猜路径。
- 本机还有手机设备（`SM_F946U`），**禁止**把 TV 包安装到手机；脚本只选 Leanback/TV 设备。
- 多设备/序列号变化时，用 `--serial` 或 `TV_SERIAL` 覆盖。

## 4. 必跑命令

```bash
# 一条命令构建：单测 + 主题 token 检查 + debug 包
scripts/tv-build.sh

# 一条命令设备冒烟：安装 + Leanback 启动 + 方向键 + 焦点断言 + 截图/logcat
scripts/tv-verify.sh
```

`tv-build.sh` 内部固定执行（**不要只跑 assembleDebug**）：

```bash
./gradlew :app:testDebugUnitTest :app:checkThemeTokens :app:assembleDebug
```

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 脚本已设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME=$PWD/.gradle-dsh`，workspace-write 下无需升权。
- 手动执行上面的 Gradle 命令前，也必须先 export 同样的 `JAVA_HOME` / `GRADLE_USER_HOME`。
- `.gradle-dsh/` 是本地 Gradle 缓存，已 gitignore；不要提交。
- 只有 SDK/AVD 写操作（安装镜像、创建 AVD）才需要临时申请更宽权限。

## 5. 设备验收协议

每次改 UI、焦点、导航、播放、性能相关代码，都必须执行；只跑构建不算验收。

1. **安装**：`adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
   - 保留应用数据；签名冲突先问人，**不要自动卸载/清数据**。
2. **Leanback 启动**：先用 `cmd package resolve-activity` 按 `LEANBACK_LAUNCHER` 解析组件，再 `am start -W -n <component>`。
   - 不要用 `am start -p`：API 28 上会因 launcher activity 没有 `CATEGORY_DEFAULT` 而解析失败。
   - 必须进入 `MainActivity`；停在 Splash、桌面或崩溃都算失败。
3. **启动弹窗**：先处理《使用协议》/更新弹窗。
   - 首次协议需操作者手动接受一次，或显式使用 `scripts/tv-verify.sh --accept-disclaimer`；脚本不代替用户接受。
   - 更新弹窗请选择“此版本不再提醒”；脚本不代替用户升级。
4. **方向键路径**：`RIGHT` → `DOWN` → `UP` → `LEFT` → `DOWN` → `UP`；每一步后重新 dump。
   - 焦点必须始终存在且可见；6 次中至少 2 次发生移动（否则导航失效）。
   - 默认不按 OK/CENTER，避免误触视频播放；需要激活时单独人工验证。
5. **uiautomator dump**：`uiautomator dump --compressed /sdcard/window.xml` 后 pull。
   - 断言：恰好一个 `focused="true"`，bounds 非空，package 为 `blbl.cat3399`。
6. **logcat**：启动前 `logcat -c`，结束后 `logcat -d -v threadtime`。
   - 不得出现 `FATAL EXCEPTION` + `Process: blbl.cat3399`、`ANR in blbl.cat3399`、`Force finishing activity blbl.cat3399`。
7. **截图**：启动后、方向键走查后、结束前至少 3 张 PNG；用 `screencap -p`，不要用缩略图代替。
8. **产物**：`build/dsh-tv-verify/<timestamp>-<pid>/`（`summary.txt`、`focus.tsv`、`ui/`、`screenshots/`、`logcat.txt`）。失败也必须保留证据。

通过标准：脚本退出码 0；8 条焦点记录（启动 + 6 次方向键 + 结束）、至少 2 次移动；3 张 PNG；进程存活；logcat 无上述崩溃/ANR。

## 6. 禁止项（硬性）

- **不改** `local.properties`、`keystore/`、签名密码或任何密钥文件；不把本机路径/密钥写进 tracked 文件。
- **不覆盖**用户未提交改动：开工先 `git status --short`；已有 `M`/`??` 视为用户工作，不 `checkout`/`reset`/`stash`/覆盖；只 stage 本次新增/修改的文件；按 §8 在打包前提交。
- **新颜色必须用主题属性**（`?attr/...`、`?android:attr/...`）；不得在 layout 里新增固定 `@color/blbl_*` 调色板引用；`checkThemeTokens` 必须通过。
- **不以新增功能/动画/视觉包装掩盖性能问题**；1GB 设备卡顿/OOM 先修性能，不靠加开关或删测试“通过”。
- 不删除/跳过测试，不关闭 `checkThemeTokens`，不修改 `minSdk`/`targetSdk`/ABI 过滤来绕过兼容问题（除非明确要求）。
- 不自动卸载应用、不清除用户数据、不把 TV 包装到手机、不把模拟器 UI 结论当成真机播放/DRM 结论。

## 6.1 播放器跨站点一致性（硬性）

修改播放器相关代码时，必须保证 B站、芒果 TV、爱奇艺等站点的播放器样式一致：

1. 进度条在屏幕上的位置一致；按钮的图标、尺寸、圆形样式一致。
2. 各站点按钮的排列位置一致（播放/暂停、下一集、列表、弹幕、站点独有按钮、关闭）。
3. 站点独有按钮必须复用 `PlayerControlBar`/`PlayerIconButton` 的样式，不得单独实现不同外观。
4. 验收时至少截取两个站点的播放界面做对比；只跑构建不算通过。

## 7. 工作流

1. `git status --short`，识别并避开用户未提交改动。
2. 定位改动范围；需要细节时读 `.dsh/skills/tv-build-verify/SKILL.md`，不要重复搜索 JDK/SDK/AVD。
3. 改完先按 §8 提交本地代码，再跑 `scripts/tv-build.sh`；UI/焦点改动再跑 `scripts/tv-verify.sh`。
4. 汇报时给出：改了什么、提交号、构建结果、设备 serial、焦点走查结果、截图/产物路径、未验证项（尤其是真机播放/4K/DRM）。
5. 验收通过后按 §8 推送远端；只 stage 本次文件，提交信息用中文简述“改了什么 + 为什么”。

## 8. 提交与推送（打包前必做）

**不要用未提交的工作区直接跑打包/验收。** 每次运行 `scripts/tv-build.sh` 或 `scripts/tv-verify.sh` 前，先把当前代码提交到本地 Git；验收通过后再推送远端。

1. `git status --short`，确认改动范围；排除 `local.properties`、`keystore/`、`.gradle-dsh/`、`build/`、`.DS_Store` 等本机/构建文件。
2. 只 `git add` 本次相关文件；用 `git diff --cached --stat`、`git diff --cached` 复核，避免把无关改动、Cookie/Token、密钥或私人路径带进提交。
3. 提交信息用中文说明“改了什么 + 为什么”，例如：`搜索: 二维码与软键盘切换`。打包前至少有一个本地提交。
4. 再运行 `scripts/tv-build.sh`；UI/焦点/播放改动继续跑 `scripts/tv-verify.sh` 或对应真机验证。
5. 验收通过后 `git push origin <当前分支>`；失败就修复后追加提交，**不要**用 `--force` 覆盖远端历史。
6. 临时 WIP 可以先用 `git commit` 存快照，验收通过后再整理成正式提交；不要长期把可运行代码留在未提交状态。
