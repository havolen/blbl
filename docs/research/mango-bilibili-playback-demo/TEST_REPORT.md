# 芒果 TV 与 B 站播放链路 Demo 测试报告

- 日期：2026-10-23（Asia/Singapore）
- 项目：`blbl.cat3399`
- 测试目的：给后续评审提供可复核的 Mango 历史/网页播放链路、B 站 Android 播放链路，以及三星设备 1080p SDR 能力证据。

## 结论摘要

1. **SM-F946U 的硬件解码能力不是本次 B 站低清现象的通用限制。** 设备 MediaCodec 报告硬件 AVC、HEVC 解码器支持 1920×1080@24/30/60；B 站公开测试视频在同一设备上从接口得到 qn=80，并实际输入 1920×1080 视频轨。按项目画质映射，qn=80 是“1080P 高清”，HDR、杜比视界、HDR Vivid 分别使用其他 qn。
2. **Mango 播放路径不同，已完成 1080P 修订。** Edge PC 页面上播放器质量标签显示“1080P SDR”。原版 blbl WebView 使用 Android Mobile UA，进入移动播放器并落在 832×468；本轮改为同 Chromium 版本的 Desktop UA，并在播放器源列表加载后调用 Mango 自身清晰度切换 API。三星实测 HLS definition 从 2 切到 4，最终画面为 1920×1080 且持续播放。
3. 本次未能从 Edge 主用户配置直接取得 DevTools 的运行时 Network 面板数据。因此 PC 的播放 API 参数与选档流程来自页面实际加载的公开播放器脚本；Android Mango WebView 的请求和媒体分片则由本机 CDP 运行时捕获。两类证据在下文分开标记。
4. 对用户先前遇到的“某条 B 站视频只能低清”，本次公共样本不能代替那条具体视频。最可能的分界点是：playurl 接口是否返回 qn=80；若返回，再检查实际解码轨与播放错误。需要同一视频 BVID 才能定位具体原因。
5. 本次新增的真机验证证明：Mango 当前 HLS 流可由 Media3 ExoPlayer 播放并进行暂停、前后 seek、退出；WebView 可以通过 Mango 播放器自身接口控制，也能改善网页全屏与 Back 行为。对多台盒子，NAS 局域网会话/流中转在架构上可行，但仍需要单独设计与验证授权保管、短时签名 URL 更新及并发流处理。

## Demo 代码与运行方式

Debug 专用 Demo：

- `app/src/debug/java/blbl/cat3399/research/PlaybackCapabilityDemoActivity.kt`
- `app/src/debug/java/blbl/cat3399/research/MangoExoProbeActivity.kt`
- `app/src/debug/AndroidManifest.xml`
- `MangoVideoActivity` 的播放控制/流捕获探针由 `BuildConfig.DEBUG` 控制；Demo 和 Exo 探针 Activity 仅注册在 Debug manifest。

Demo 在三星上枚举 `MediaCodecList.REGULAR_CODECS`，通过 `VideoCapabilities.areSizeAndRateSupported(1920, 1080, fps)` 检查 AVC、HEVC、AV1 解码器能力。两个按钮分别启动公开 B 站测试视频和 Mango 网页播放页。它不请求额外接口、不读取或打印 Cookie、不记录带签名的媒体 URL；Demo 与 Exo 探针 Activity 不在 Release manifest 中，WebView 控制与 HLS 捕获只在 Debug 构建启用。

在已授权并连接的设备上启动：

```bash
adb -s <设备序列号> shell am start -n blbl.cat3399/.research.PlaybackCapabilityDemoActivity
```

Mango WebView DevTools 通过 ADB 本地端口转发读取。本次只启用了 CDP `Network` 事件，不读取请求头或请求/响应正文；为解析播放响应时，只保留 URL 路径、查询参数名、状态码、MIME、分片字节数及播放器的宽高/播放状态。临时端口已移除，ADB 设备连接保持在线。

## 测试环境与构建

| 项目 | 结果 |
|---|---|
| 手机 | Samsung SM-F946U，Android 15 / API 35，arm64-v8a |
| 手机 ADB serial | `adb-RFCWB0KSX9N-cOFWkA._adb-tls-connect._tcp` |
| TV AVD | `TCL_75Q10G_API28`，Android 9 / API 28 / x86，`emulator-5554` |
| 构建 | `scripts/tv-build.sh`：单测、`checkThemeTokens`、`assembleDebug` 均通过 |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK SHA-256 | `cd8e44c2cf09032c3f27e223bc3719c70dc693e9d2afdde2b4553c27d24ea12e` |
| AVD 焦点走查 | PASS；8 条焦点记录，方向键后 5/6 次焦点移动 |
| AVD 记录 | `build/dsh-tv-verify/20261023-113307-85306/summary.txt` |

AVD 仅用于启动、焦点和界面冒烟；下列播放与硬件解码结果均来自三星真机。

## 实验一：Edge PC 中餐厅页面与选档

### 观测结果

- 在 Edge 已登录会话的播放记录页打开“中餐厅”记录，浏览器进入 Mango PC 播放页；视频开始播放。
- 播放器控制区显示当前质量“1080P SDR”。这是 Edge 页面可见状态，证明该 PC 页面本次选择了该标签；未保存包含账号信息或历史标题的截图。
- 实际 PC 页面 URL 形态为 `/s/<episodeId>.html`，历史记录链接可附 `start_time=<resumeSeconds>`。报告不保留个人观看进度值。

### PC 播放脚本中的接口与选档逻辑

从 Edge 页面加载的公开播放器脚本 `s1.hitv.com/libs/??vplayer/9.0.8/vplayer.min.js` 静态检查到以下请求构造。这里是**播放器脚本证据，不是 DevTools Network 实时响应记录**：

| 请求 | 参数名（值不记录） | 用途与代码证据 |
|---|---|---|
| `GET https://pcweb.api.mgtv.com/player/vinfo` | `video_id`, `cid`, `pid`, `cxid`, `entranceType`, `_support`, `allowedRC` | 获取视频信息；播放器数据源 `getVideoInfo()` 调用此路由。 |
| `GET https://pcweb.api.mgtv.com/player/getSource` | `_support`, `tk2`, `pm2`, `did`, `vf`, `video_id`, `type`, `cxid`, `entranceType`, `suuid`, `auth_mode`, `src`, `abroad`, `allowedRC`, `definitionType` | 根据视频和客户端上下文取得可播放 source/清晰度流。脚本声明重试域名为 `pcweb.api.mgtv.com`、`pcweb2.api.mgtv.com`、`pcweb3.api.mgtv.com`，最多重试 2 次。 |
| `https://pcweb.api.mgtv.com/vod/config` | 本脚本搜索到配置路由；本次未捕获其运行时参数/响应 | 站点播放器配置。不能据静态字符串断言本次必然调用了它。 |

脚本先取 `videoInfo`，再取 `videoSource`；源对象包含按 `def` 标识的流，播放器检查流的 `playable` 状态，并使用流的 `standardName` 展示名称。`getDefaultDefinition()` 优先采用可播放的配置默认档；配置档无效时回退到最高可播放流。选中档后通过 `getSrcByDef({def})` 获取该档 source，再交给 Mango Player Kernel。Edge 页面本次显示 1080P SDR，但 PC DevTools 的 API response、`def` 具体值和 source 响应正文未能从当前 Edge 用户配置读取，故不推断实际码率或请求参数值。

## 实验二：blbl Mango 历史、数据处理与 Android 网页播放

### 历史列表接口与字段转换

代码入口：`MangoHistoryApi.loadPage()`、`MangoHistoryItem.pageUrl()`、`MangoHistoryFragment`。

1. 如果 Mango 本地会话没有缓存 UID，先请求 `GET https://nuc.api.mgtv.com/user/get_login_user`，增加 `needVipIcon=1`。Cookie 只从 Mango 专属 `MangoSessionStore` 读取。
2. 然后请求 `GET https://playhistory.bz.mgtv.com/playHistory/v2/get`。参数名包括 `uid`, `pageSize`, `from`, `version`, `isFilter`, `_support`, `allowedRC`, `uuid`, `ticket`, `did`, `device`, `osType`, `osVersion`, `appVersion`, `platform`, `seqId`；后续页可增加 `beforeTime`。本次报告不保存这些参数的运行时值。
3. 成功响应从 `data.playList` 读取条目。解析字段：`playId`, `pType`, `pid`, `vid`, `vName`, `watchTime`, `duration`, `updateTime`, `nextVid` 和封面字段候选（如 `vImage`、`vImg`、`vPic`、`image`、`cover`）。封面相对路径规范化到 HTTPS Mango 图片域；时长与观看位置用于进度显示。
4. 页面类型映射：`pType=1 → /s/{vid}.html`；`pType=2 → /l/{pid}/{vid}.html`；`pType=3 → /b/{pid}/{vid}.html`。历史卡片点击时附 `start_time=<watchTime>`，保留当前记录进度。
5. 真机本次接口日志仅记条数和封面数量：加载 7 条记录、7 张封面；没有把任何标题或 Cookie 写入日志。

### Android WebView 运行时观察（改动前基线）

`MangoVideoActivity` 将 Mango CookieJar 的 Cookie 逐个写入 Android `CookieManager`，然后加载 HTTPS 页面；启用 JavaScript/DOM Storage。它**不把 Mango 地址交给 blbl 的 ExoPlayer/IJK 内核**，页面由 Android System WebView/Chromium 播放。

本次 Debug WebView 的运行时网络事件（实际请求值、请求头和正文均未保存）：

| 请求/响应 | 观测 |
|---|---|
| 播放页文档 | `www.mgtv.com` 页面转到 `m.mgtv.com/b/<albumId>/<videoId>.html`，HTTP 200。 |
| 播放器数据 | `m.da.mgtv.com/m/player`，XHR/JSONP 请求，HTTP 200，响应 MIME 为 JSON；查询参数名：`_support`, `_type_object`, `callback`, `p`, `v`。 |
| HLS 播放清单 | `pcvideocmobile-v6.titan.mgtv.com/c1/<assetPath>/<stream>/<playlist>.m3u8`，HTTP 200，MIME `application/vnd.apple.mpegurl`。实际 URL 含用户/播放上下文查询值，未保存。 |
| HLS 媒体分片 | 相同 Mango CDN 下 `.ts` 分片请求，HTTP 206 `video/mp2t`；本轮观察窗口内 6 个分片，合计约 4.88 MB。 |
| 播放进度 | `playhistory.bz.mgtv.com/playHistory/heartbeat`，JSONP；参数名包含 `callback`, `cid`, `from`, `isEnd`, `pid`, `sid`, `ticket`, `uid`, `vid`, `watchTime`。值全部省略。 |

运行时只在内存检查 `<video>` 的播放状态：`832×468`、`paused=false`、`readyState=4`，播放时间约 186 秒。它证明原版移动 WebView 实际在播放，但默认没有使用 1080p。该基线由后续桌面 UA 方案替代。

### 本轮追加：历史视频自动选择 1080P SDR

改动代码：`MangoVideoActivity` 在播放器 WebView 中把 Android Mobile UA 替换为 `Windows NT 10.0` 桌面 UA，同时保留 WebView 当前 Chromium 主/次版本。页面仍由 Mango 官方站点和播放器提供视频流。

页面完成加载后，Activity 向同源 PC 播放器注入一段短脚本：最多每 500ms 检查一次，最多 20 次；等待 `mgtvPlayer.dataSource.videoSource.streams` 可用后，选择 `playable` 且名称为 `1080P SDR`/`1080P` 的流，通过播放器原有 `userActions.changeDefinition(stream)` 切换。HDR、杜比视界、HDR Vivid、1080P+ 不匹配；无可用标准 1080p 时不强行切换，保留 Mango 默认播放。脚本设一次性标记避免同一文档重复切换。

使用桌面 UA 后的 Android WebView **运行时**观测：

| 请求 | 观测 |
|---|---|
| `GET pcweb.api.mgtv.com/player/video` | HTTP 200 / JSON；查询参数名包括 `_support`, `abroad`, `allowedRC`, `auth_mode`, `did`, `entranceType`, `src`, `suuid`, `tk2`, `type`, `video_id`。响应根字段名为 `msg`, `code`, `data`, `err_msg`, `err_code`, `seqid`, `timestamp`；不保留响应值。 |
| Mango CDN HLS 清单 | HTTP 200 / `application/vnd.apple.mpegurl`；同一次重载观察到 HLS `def=2` 初始流请求，自动选档后请求 `def=4`。其他签名参数值不输出。 |

PC VPlayer SDK 的静态脚本还包含 `/player/vinfo` 与 `/player/getSource`；当前页面本次运行时实际可见的是 `/player/video`，因此前两项仅作为播放器 SDK 接口证据，不声称本次运行必然调用。

在三星最终 APK 上完成的闭环测试：

1. 先用 Mango 官方 `changeDefinition` 将当前播放临时切为 definition `2`，读到 `960×540`，确认测试开始于低档。
2. 重新加载历史视频 PC 播放页。CDP 只记录 HLS URL 的 `def` 参数，不记录其余签名/账号查询值；观察到播放器先发 `def=2`，随后自动选档发出 `def=4`。
3. 从页面对象读取安全状态字段：自动选档标记为 true、当前 definition 为 `4`；HTML video 为 `1920×1080`、`paused=false`、`readyState=4`，时间持续增长。反向验证先通过官方播放器 API 降到 def `2`（960×540），重新加载后自动选档又请求 def `4`。

这证明三星上的 Mango **历史视频 WebView 页面现在可自动切换到实际 1080p 轨并播放**。播放器 UI 把该档显示为“1080P SDR”；播放源对象标准名为“1080P”。本次没有读取视频色彩传递元数据，所以“SDR”依据是站点档位标签/标准流选择，不是独立测出的 HDR transfer characteristic。

## 实验三：B 站 Android 播放、画质和本地解码

### API 与处理流程

当前设置没有显式 `api_source` 覆盖，`AppPrefs` 默认 `web`，`VideoApiGateway` 因此选择 `WebVideoApi`。主要流程：

1. `GET https://api.bilibili.com/x/web-interface/view?bvid=<publicBvid>` 解析 aid/cid 和视频详情。
2. UGC 播放请求到 `GET https://api.bilibili.com/x/player/wbi/playurl`。代码参数名为 `bvid`, `cid`, `qn`, `fnver`, `fnval`, `fourk`, `voice_balance`, `web_location`, `gaia_source`, `isGaiaAvoided`；需要时加入 `try_look` 和风控 vtoken。WBI 的 `w_rid`、`wts` 由密钥签名生成。请求值、Cookie、vtoken 和 URL 均未写入报告。
3. 代码请求最高档 `qn=127`、`fnval=4048`、`fourk=1`，由服务端根据该视频可用流和账号权限返回实际 `support_formats`/DASH 视频与音频轨。
4. `WebVideoMapper` 把返回解析成 `VideoPlayStream`、`VideoTrack`、`AudioTrack`。`PlayerActivity.pickPlayable()` 依据可用 qn、用户偏好和 codec 偏好挑选视频/音频；随后 `DashMpdGenerator` 生成 Media3 可读的 DASH representation，`ExoPlayerEngine` 通过 Android MediaCodec 解码。遇到风控/无播放地址时还有 `try_look` fallback。

若切换到项目的 APP API source，UGC 走 Bilibili gRPC `PlayViewUniteReq`，PGC 走 `PlayViewReq`；这不是本次真机样本所走的 WEB source。

### 三星真机实测结果

用 Demo 按钮启动公开测试 BVID `BV1fu411n7aD`（不记录视频标题）。本次播放器日志经字段白名单提取后为：

```text
targetQn=80
rawQns=[16, 32, 64, 80]
selectableQns=[16, 32, 64, 80]
selected actualQn=80
ExoPlayer input format qn=80 size=1920x1080 bitrate=694905
```

项目画质名称映射是 qn 80=`1080P 高清`、qn 125=`HDR 真彩色`、126=`杜比视界`、129=`HDR Vivid`。因此本次请求与实际输入轨为标准 qn80/1080p，属于项目的非 HDR 档位；`dv=false`。项目没有在本次日志记录色彩传递元数据，严格的 SDR 色彩元数据仍需额外采集。

设备能力 Demo 在 SM-F946U / Android 15 枚举结果：

| MIME | 硬件解码器 | 1920×1080@24 | @30 | @60 |
|---|---|---:|---:|---:|
| AVC / H.264 | Qualcomm `c2.qti.avc.decoder` | 支持 | 支持 | 支持 |
| HEVC / H.265 | Qualcomm `c2.qti.hevc.decoder` | 支持 | 支持 | 支持 |
| AV1 | Qualcomm `c2.qti.av1.decoder` | 支持 | 支持 | 支持 |

这是 Codec API 公布的尺寸/帧率能力，不单独证明任意文件、码率或 HDR 元数据都能实时解码；但结合本次 ExoPlayer 实际 1920×1080 输入轨，足以否定“该三星机型普遍不能播 1080p”的判断。

## 问题判断与下一步诊断

- **已验证：** 同一三星手机、本次公共 B 站样本、当前登录/网络环境下，服务端返回 qn80 且 ExoPlayer 实际得到 1920×1080 轨；本机硬件可解码 AVC/HEVC/AV1 1080p。
- **未验证：** 用户此前看到低清的那条具体视频。某些视频本身可能没有 1080p；账号会员/版权限制、地区、API source、`try_look` 风控兜底和接口实际返回的 rawQns 都可能影响可选档位。本次不能断定是哪一个原因。
- 如果该视频的 `rawQns` 不含 80，问题在服务端返回/视频权限一侧；如果含 80 但切换后仍不是 1920×1080，再查 `QualitySwitch` 轨道支持、MediaCodec renderer 错误、当前 `format.colorInfo` 与实际输出分辨率。
- **可用单机路径：** 已验证 blbl 自带的 Media3 ExoPlayer + 系统 MediaCodec 可以播 1080p 标准档；Mango WebView 也能调用官方 PC 播放器选到 1080p，不需要另装播放器。项目 IJK 插件在该手机上未安装，未下载外部 native 插件；第三方播放器 App 未纳入测试。
- Mango PC UA 方案绕过移动页的默认低档选择；在当前三星 Android 15/WebView 环境通过。老版本 Android WebView、其他设备 UA/站点策略和 1080p+ 网络带宽下的实际播放稳定性仍需设备覆盖测试。

## 证据与限制

- 构建：`scripts/tv-build.sh` PASS；`git diff --check` PASS。
- AVD 焦点验证：`build/dsh-tv-verify/20261023-113307-85306/summary.txt`；方向键 5/6 次移动、共 8 条焦点记录，PASS。最终 APK 先经 `adb install -r` 返回 Success，再用已安装的最终版本走完焦点检查；其日志/截图是 AVD 首页焦点冒烟，与播放画质无关。
- 手机 ADB：测试前后 serial 均在线；本地 CDP 转发端口 9227 已移除，没有断开 ADB。
- **PC 网络观察限制：** 当前 Edge 主用户配置未暴露可连接的 CDP browser endpoint。可见的播放页及质量标签已检查，PC 公开播放器脚本中的 endpoint/选档逻辑已静态分析；PC 的实际 Network 面板请求值、响应正文和 `def` 档位值未能捕获。
- 隐私：报告不含账号名、Cookie、ticket/vtoken、签名 URL、私人观看进度或历史列表截图。证据截图仅显示公开播放器画面与测试控件，不显示账号信息。

## 实验四：WebView 控制与 ExoPlayer 接管（最终 Debug APK）

### 测试范围与方法

- 在三星 SM-F946U 安装最终 Debug APK，使用 `adb install -r` 升级，保留原应用数据；未清 Cookie、未登出账号。
- 通过仅 Debug 注册的播放能力 Demo 打开 Mango PC 播放页，未启动 B 站视频。测试从同一个 `MangoVideoActivity` 路径进入；历史卡片也路由到该 Activity，但本轮没有从私人的历史列表实体卡片触发。
- 仅在进程内捕获 WebView 请求到的 HLS 地址和 UA，供 Debug Exo 探针使用；不写入文件、Logcat 或报告。Exo 探针用 Mango 专属 CookieJar、UA、Referer 和 Origin 请求同一播放会话的 HLS 流。
- 触屏动作使用 ADB display 0 坐标注入；网页播放状态通过本机 CDP 读取 `<video>` 的 `currentTime`、`paused`、`videoWidth`/`videoHeight`。CDP 转发端口 `9227` 已移除，ADB 设备连接仍在线。

### WebView 控制结果

最终 Debug APK 上，应用侧控制按钮调用 Mango 播放器公开到页面对象的 `mgtvPlayer.userActions.togglePlay()`、`seek(seconds)` 与 `webFullscreen()`；缺少对应方法时播放/seek 有 HTML `<video>` 回退。实测：

| 操作 | 观测结果 |
|---|---|
| 播放中暂停 | `paused=false → true`，分辨率 1920×1080 保持 |
| 前进 10 秒 | 暂停状态下播放时间增加 10.0 秒 |
| 后退 10 秒 | 播放时间减少 10.0 秒 |
| 恢复播放 | `paused=true → false`，播放时间继续增加 |
| 网页全屏 | `<video>` 边界扩展到整个 WebView viewport |
| 全屏按 Back | 播放页保留，网页从全屏回到内嵌布局；再次 Back 才退出播放页 |

这是对“WebView 控制是否能改善”的肯定实测：WebView 并不要求由网页独占遥控器控制，Android 层可调用页面播放器 API。它依赖 Mango 页面的 JS 对象，站点升级后需做兼容检查。最终版本的网页全屏处理使用 `webFullscreen()`；通用 HTML5 自定义全屏仍由 `WebChromeClient` 回调处理。应用全屏返回优先退出网页全屏，避免第一下 Back 直接关闭播放 Activity。

### ExoPlayer 接管结果

Debug 探针从当前网页捕获该次选中的 HLS 流，并通过 Media3 `HlsMediaSource` 和应用 `MangoSessionStore.cookies` 新建 ExoPlayer 播放实例。最终 APK 的三星运行结果：

| 操作 | 观测结果 |
|---|---|
| 打开 Exo 探针 | `READY`，实际 `videoSize=1920×1080`，播放时间递增 |
| 暂停/恢复 | 状态从“播放中”变为“已暂停”，再恢复播放 |
| 前进/后退 | 暂停时前进 10 秒后退 10 秒，时间与操作相符 |
| 退出播放器 | Exo Activity 结束并返回原 Mango WebView Activity |

因此，当前捕获的 Mango HLS **在这台三星 Android 15 设备上**可以交给 ExoPlayer 控制和播放 1080p。该探针是 Debug 验证工具；尚未将历史页正式改为 ExoPlayer 播放，也未验证其他型号 Android 盒子、不同 HLS 加密/签名策略或长时间播放稳定性。

### 可行方向判断

以下区分本次实测与架构推断：

| 方向 | 判断 | 条件与代价 |
|---|---|---|
| WebView + 应用控制条 | **已实测可行，适合作为低改动路径。** | 继续让 Mango 官方网页负责源解析和播放；应用只增加精简遥控器 OSD，调用播放器 `togglePlay`、`seek`、`webFullscreen`。JS API 属站点实现，需有缺失时的降级与版本回归。通用 HTML5 自定义全屏由 `WebChromeClient` 的 show/hide 回调承接；Android 文档要求两者成对处理。[API 说明](https://developer.android.com/reference/android/webkit/WebChromeClient) |
| 盒子本地 ExoPlayer | **当前 HLS 对三星已实测可行；旧 Android TV 盒子仍待验。** | 需要稳定获取 Mango 选定档的 manifest、带上合法 Cookie/Referer，并能续取短时签名流。Media3 支持 HLS，但容器、媒体编码及加密方式仍决定兼容性。[Media3 HLS 支持范围](https://developer.android.com/media/media3/exoplayer/hls) |
| NAS 登录/历史/流解析中转 | **符合多盒子共享会话目标，架构上可行，尚未实现或负载实测。** | NAS 统一保存 Mango 会话，只向已配对盒子开放局域网 API；为历史条目解析短时播放源，再由盒子 ExoPlayer 直连 CDN，或让 NAS 以不转码方式代理 HLS 清单、分片和续签。代理需要正确处理相对 URI、Range、重定向、签名过期与并发。NAS 仍承担 TLS、API/清单解析和每路流的网卡转发；无云服务流量费不代表零网络或服务器负载。 |
| NAS 转码 | **本轮不建议作为第一步。** | 会增加 NAS CPU/GPU、延迟和画质转换成本；本机 Exo 已能播放当前 1080p HLS，先让盒子硬解更符合低性能 NAS 的约束。 |

**建议的验证次序：** 先把 WebView 原生 OSD 与页面全屏 Back 行为产品化；再做 NAS 会话/历史 API；最后从 NAS 返回短时签名 HLS 地址给盒子 ExoPlayer。只有源地址受设备绑定、续签或 CDN 策略阻挡时，再测试 NAS 透明代理。转码暂不纳入 MVP。NAS 的真实并发负载要用家庭网络、NAS 型号和同时播放盒子数测量，本报告没有相关性能数据。

### 设备冒烟、Logcat 与证据

- 最新 AVD 走查：`TCL_75Q10G_API28`，Android 9 / API 28 / x86，serial `emulator-5554`；最终 APK 升级安装成功，8 条焦点记录、5/6 次方向键移动，脚本 PASS。
- 最终 APK 的 Logcat 检查未发现 `FATAL EXCEPTION`、`ANR in blbl.cat3399` 或 `Force finishing activity blbl.cat3399`。只保留脱敏计数，不保存含会话上下文的原始 Mango 网络日志。
- 三星最终运行截图：[`exoplayer-final.png`](evidence/exoplayer-final.png)（显示 Exo READY、1920×1080、原生暂停/seek/退出控件）；[`webview-fullscreen-final.png`](evidence/webview-fullscreen-final.png)（网页全屏画面与应用控制条）。
- AVD 截图、焦点表和 Logcat：`build/dsh-tv-verify/20261023-113307-85306/`。
- AVD 冒烟只验证导航/焦点/存活；视频播放结果只来自三星，不把 AVD 当作播放能力证据。

## 参考与可复核源码

- Android 解码能力探针使用的官方 API：[`MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported`](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities)、[`MediaCodecInfo.isHardwareAccelerated`](https://developer.android.com/reference/android/media/MediaCodecInfo.html)。能力查询表示 codec 声明支持，不等价于对任意媒体流的实时播放保证。
- Mango PC 播放器脚本（页面当前公开加载的 VPlayer 9.0.8）：[`vplayer.min.js`](https://s1.hitv.com/libs/??vplayer/9.0.8/vplayer.min.js?ver=202209260954)。
- Mango PC 页面播放器 UI bundle：[`vplayer.ae42b29.js`](https://static.hitv.com/pc2/vplayer.ae42b29.js)、[`pcuicore.ae42b29.js`](https://static.hitv.com/pc2/pcuicore.ae42b29.js)。
- 本仓库实现入口：`app/src/main/java/blbl/cat3399/core/api/MangoHistoryApi.kt`、`app/src/main/java/blbl/cat3399/feature/my/MangoHistoryFragment.kt`、`app/src/main/java/blbl/cat3399/feature/player/MangoVideoActivity.kt`、`app/src/main/java/blbl/cat3399/feature/player/PlayerActivity.kt`、`app/src/main/java/blbl/cat3399/core/api/video/web/WebVideoApi.kt`、`app/src/main/java/blbl/cat3399/core/api/video/app/AppVideoApi.kt`。
