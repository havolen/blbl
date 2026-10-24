# 爱奇艺 1080P 播放性能测试报告

- 日期：2026-10-25
- 应用：blbl.cat3399（debug，含临时 `IqiyiPerf` 日志）
- 分支 / 提交：`feature/category-mapping-player-unify` / `b2264d2`
- 设备：`192.168.2.32:5555`，STI6110 / Askey，Android 9（API 28），armv7
- WebView：`66.0.3359.158`
- 测试内容：《我的阿勒泰》第 8 集（从爱奇艺历史记录进入）
- 账号状态：应用内爱奇艺账号为 VIP（`blbl_iqiyi_account.xml` 中 `vip=1`）
- 结论级别：**已定位根因，未完成最终修复**（VIP 流的分段签名是阻塞点）

---

## 1. 结论摘要

1. **卡顿不是 1080P 硬解能力不足。** 设备存在 Amlogic 4K HEVC 硬件解码器，免费集 H.264/H.265 流均可原生播放。
2. **用户看到的卡顿来自 WebView 的 HTML5 播放器，而不是 ExoPlayer。** 会员集通过 `IqiyiApi.resolveStream()` 拿不到流（`A00011`），历史记录页于是回退到 `IqiyiWebPlayerActivity`，由 WebView 66 用 MSE 播 1080P。
3. **不是简单的 Cookie 问题。** 用“全量 Cookie / 去掉 P00001 / 只带 P00001 / 不带 Cookie”四组请求测试会员集，均返回 `A00011`；免费集不带 Cookie 也能拿到 `A00000`。
4. **把网页播放器的 m3u8 直接交给 ExoPlayer 也不行。** 播放列表能加载，但分段 `.ts` 请求返回 `405`，响应体 `{"code":"D2001"}`——爱奇艺网页播放器会在 JS 层给**每个分段**追加动态签名，m3u8 里的 URL 不能直接复用。
5. **共验证 7 种方法/路径**：5 条播放/解析路径、1 条启动期缓解、1 组综合诊断。其中只有“免费集解析可用”和“会员集 WebView 回退”能出画面；会员集原生解析、m3u8 转原生、移动版页面均失败。
6. **建议**：根治需要实现爱奇艺 VIP 播放接口（`authKey` / 分段签名），或引入能执行网页签名逻辑的解析服务；短期可评估 720P/关闭弹幕等降负载方案，但会牺牲画质，且本次未做量化验证。

---

## 2. 测试目标与方法

### 2.1 目标

回答三个问题：

1. 爱奇艺 1080P 起播卡顿，卡在解析、网络、解码，还是渲染？
2. 为什么 B 站/芒果可以原生 ExoPlayer，爱奇艺 VIP 却退回 WebView？
3. 能否把网页播放器已经拿到的 1080P 流交给 ExoPlayer，从而复用硬件解码？

### 2.2 观测手段

| 手段 | 用途 |
|---|---|
| `IqiyiPerf` 日志 | 记录 `prepare` 耗时、`STATE_BUFFERING/READY`、首帧、视频 `codec/width/height/bitrate/fps`、`rendered/dropped` 帧数 |
| `ActivityManager` 日志 | 记录 Activity 启动到 `Displayed` 的耗时 |
| `dumpsys media.codec` / `media_codecs.xml` | 确认设备是否有 HEVC/H.264 硬解 |
| VMS `tmts` API 直连 | 复现应用内解析结果，对比免费集/会员集、Cookie 变体 |
| HLS 播放列表与 TS 分段下载 + `ffprobe` | 确认码流分辨率、编码、码率、分段大小 |
| WebView CDP（`webview_devtools_remote`） | 读取页面真实请求的 m3u8、`<video>` 状态、`performance` 资源、播放器全局对象 |
| 截图 / 录屏 | 确认 WebView 桌面页、移动页、原生接管的实际画面 |

> 说明：`IqiyiPerf` 是本次临时加入的调试日志，不是正式埋点；下文的耗时均为单次采样，不是统计均值。

---

## 3. 关键发现

### 3.1 会员集解析失败，历史记录回退 WebView

会员集《我的阿勒泰》第 8 集请求 `cache.m.iqiyi.com/tmts/...` 返回：

```
code = A00011
vidl = 0
```

`IqiyiApi.resolveStream()` 返回 `null`，`IqiyiHistoryFragment` 走兜底分支：

```kotlin
IqiyiWebPlayerActivity.intent(... desktop = true ...)
```

也就是说，**用户看到的“爱奇艺 1080P 播放器”实际是网页播放器**。这条路径没有走 `IqiyiPlayerActivity`，自然也没有用上 ExoPlayer 的硬解和播放器控制条。

### 3.2 免费集可以原生播放，说明链路本身可用

同一部剧第 1 集（免费）请求 VMS 返回 `A00000`，有 7 个码流候选：

| 分辨率 | vd | 编码 |
|---|---|---|
| 1280x536 | 4 | H.264 |
| 1280x536 | 17 | H.265 |
| 896x376 | 2 / 21 / 75 | H.264 / H.265 |
| 640x264 | 1 | H.264 |

`ffprobe` 对 1280x536 H.265 分段的检查结果：HEVC Main、`yuv420p`、Level 93、AAC-LC 205 kbps；3 秒分段 242,708 字节（约 647 kbps）。这类流原生播放没有压力。

### 3.3 设备硬解能力足够

`/vendor/etc/media_codecs.xml` 中存在：

```
OMX.amlogic.hevc.decoder.awesome   type=video/hevc   max=3840x2160
OMX.amlogic.hevc.decoder.awesome.secure
OMX.google.hevc.decoder（软解兜底）
```

因此“1080P 解不动”不成立。真正的瓶颈在 WebView 的网页播放器，而不是 MediaCodec。

### 3.4 网页播放器确实拿到了 VIP 1080P 流

通过 WebView CDP 读取 `performance.getEntriesByType('resource')`，拿到网页播放器实际请求的播放列表：

```
https://meta-cdn.video.iqiyi.com/20240627/...m3u8
  ?qdv=3&qd_uid=...&qd_vip=1&qd_vipres=2&...
```

播放列表 107,064 字节，`#EXT-X-TARGETDURATION:5`，分段 3–5 秒，大小从 242 KB 到 3.37 MB；网页播放器的分段请求带 `bid=600&fr=25&vcodec=2&br=100` 等参数。该流就是网页端实际播放的 VIP 流（用户称之为 1080P；日志只能确认 `bid=600`/25fps，无法仅凭日志确认精确分辨率）。

### 3.5 网页播放器起播阶段存在码率切换

WebView 控制台日志在起播后出现：

```
Decoder push switch stream by change videoId
  [VODTS, 2143888500841700, 600 -> VODTS, 2143888500841700, 500]
```

即网页播放器在起播后从 600 档切到 500 档，触发 MSE 重新初始化和重新缓冲。这是“开始特别卡”的一个直接可疑点，但本次没有做逐帧录屏来量化它占总卡顿的比例。

---

## 4. 验证过的 7 种方法

### 方法 1：原生 ExoPlayer + VMS 解析（免费集）

- **思路**：确认免费集的解析链路是否可用，以及能拿到什么规格的码流。
- **操作**：用第 1 集的 `tvId/videoId` 调 VMS，检查 `vidl` 候选，并下载 1280x536 H.265 分段做 `ffprobe`。
- **结果**：`A00000`，7 个候选，最高 1280x536；码流可正常下载和解析。
- **结论**：免费集解析链路可用；但本次没有单独播放第 1 集，且免费集最高只有 1280x536，不能代表 VIP 1080P。

### 方法 2：原生 ExoPlayer + VMS 解析（会员集，4 组 Cookie 变体）

- **思路**：判断 `A00011` 是不是 Cookie 没带对。
- **操作**：对第 8 集分别用：
  1. 全量 Cookie；
  2. 去掉 `P00001`；
  3. 只带 `P00001`；
  4. 不带 Cookie。
- **结果**：4 组全部 `A00011`、`vidl=0`。
- **结论**：不是“少带某个 Cookie”能解决；VIP 播放需要 VMS 之外的鉴权/签名参数，或该接口本身不支持会员集。

### 方法 3：WebView 桌面版 HTML5 播放（现状基线）

- **思路**：复现用户实际路径，确认卡顿发生在哪一层。
- **操作**：历史记录点击第 8 集，走 `IqiyiWebPlayerActivity`（`desktop=true`）。
- **结果**：
  - `ActivityManager: Displayed ... +709ms`；
  - 页面起播后出现 `600 -> 500` 码率切换；
  - 播放依赖 WebView 66 的 MSE/HTML5，页面 JS 与渲染负载明显高于原生播放器。
- **结论**：这是当前 VIP 内容唯一的出画面路径，也是卡顿的直接来源。

### 方法 4：WebView 截获 m3u8 → ExoPlayer 原生接管

- **思路**：网页播放器已经完成鉴权并拿到签名播放列表，拦截 `.m3u8` 请求，把 URL 交给 ExoPlayer，绕开 WebView 解码。
- **操作**：在 `IqiyiWebPlayerActivity.shouldInterceptRequest()` 中匹配 `meta-cdn.video.iqiyi.com` 的 `.m3u8`，启动 `IqiyiPlayerActivity` 并 `finish()`。
- **结果**：
  - 接管成功：`IqiyiWebPlayer: handoff native m3u8 host=meta-cdn.video.iqiyi.com`；
  - 原生播放器 `prepare t=1134ms`，但持续 `STATE_BUFFERING`、`buf=0`；
  - 5.6 秒后转为 `STATE_IDLE`；
  - 报错：`HttpDataSource$InvalidResponseCodeException: Response code: 405`，错误码 `ERROR_CODE_IO_BAD_HTTP_STATUS`；
  - 直接 curl 分段返回 `{"code":"D2001"}`。
- **结论**：**失败**。m3u8 能读，但分段 URL 需要网页 JS 动态追加的签名参数（`qd_sc` 等），原生播放器无法复用。该改动已回退，避免 VIP 视频完全播不了。

### 方法 5：移动版页面回退

- **思路**：桌面页太重，改用 `m.iqiyi.com` 移动页降低 JS/DOM 负载。
- **操作**：历史记录兜底改为 `desktop=false`，使用移动 UA。
- **结果**：页面被一个巨大的“iQIYI APP内打开”浮层遮挡，无法播放。
- **结论**：**失败**，已回退到桌面页。

### 方法 6：减少启动期重复布局注入（缓解）

- **思路**：`onPageFinished` 原本在 0ms、1.2s、3s 连续三次注入桌面布局；第三次正好落在起播阶段，可能触发重排/抖动。
- **操作**：删除 3s 那次，只保留 0ms 和 1.2s。
- **结果**：改动保留；但本次没有做 A/B 帧率对比，**不能宣称有量化收益**。
- **结论**：低风险缓解项，不是根因修复。

### 方法 7：码流 / 解码器 / CDP 诊断（排除项）

- **思路**：排除“硬解不行”“网络分段太大”“页面没拿到 1080P”。
- **操作**：
  - 查 `media_codecs.xml`；
  - 下载并 `ffprobe` 免费集分段；
  - CDP 读取网页真实 m3u8、`<video>` 状态和资源列表。
- **结果**：
  - 4K HEVC 硬解存在；
  - 分段 0.24–3.37 MB、3–5 秒，不属于“单分片过大”；
  - 网页确实请求了 VIP m3u8。
- **结论**：排除硬解和“网页只拿到低清”的假设；瓶颈集中在 **WebView 66 的网页播放器 + 起播码率切换 + 分段签名壁垒**。

---

## 5. 根因分析

### 5.1 直接原因

```
VIP 内容
  → IqiyiApi.resolveStream() 返回 A00011 / null
  → IqiyiHistoryFragment 回退 IqiyiWebPlayerActivity
  → WebView 66 用 MSE 播 HTML5 1080P
  → 起播阶段码率 600→500 切换 + 重缓冲
  → 用户感知“开始特别卡”
```

### 5.2 为什么不能简单修

| 层面 | 问题 | 能否用现有代码解决 |
|---|---|---|
| 鉴权 | VMS 对会员集返回 `A00011`，4 组 Cookie 组合都失败 | 需要实现 VIP 播放接口/签名，不能靠补 Cookie |
| 播放列表 | 网页 m3u8 可读，但分段 URL 不带完整签名 | 需要复刻网页 JS 的分段签名，或让网页代理分段 |
| 渲染 | WebView 66 的 MSE 播放器负载高 | 只能降码率/换内核/换设备，不能靠调 ExoPlayer 参数解决 |
| 解码 | 设备硬解足够 | 不是瓶颈 |

### 5.3 哪些不是根因

- **不是 1080P 硬解能力不足**：设备有 4K HEVC 硬解。
- **不是网络分段过大**：VIP 分段 3–5 秒、最大 3.37 MB；免费集 3 秒 242 KB。
- **不是“网页没拿到 1080P”**：CDP 确认网页请求了 `qd_vip=1` 的 m3u8。
- **不是 ExoPlayer 参数**：VIP 内容根本没走到 ExoPlayer；免费集原生路径正常。

---

## 6. 关键数据

### 6.1 原生接管时间线（失败路径）

| 时间点 | 事件 |
|---|---|
| 01:45:06.398 | 启动 `IqiyiWebPlayerActivity` |
| 01:45:07.153 | WebView 页面 `Displayed +709ms` |
| 01:45:09.600 | 截获 m3u8，`handoff native m3u8`（距启动约 3.2s） |
| 01:45:09.633 | 启动 `IqiyiPlayerActivity` |
| 01:45:10.806 | `state=2`（BUFFERING），`prepare t=1124ms` |
| 01:45:11.818–01:45:14.841 | 持续 BUFFERING，`buf=0`，`rendered=-1` |
| 01:45:15.271 | `Response code: 405`，`Source error` |
| 01:45:15.302 | `state=1`（IDLE），未出首帧 |

### 6.2 VMS 解析结果

| 内容 | Cookie | code | vidl 数量 |
|---|---|---|---|
| 第 1 集（免费） | 无 | `A00000` | 7 |
| 第 8 集（会员） | 全量 | `A00011` | 0 |
| 第 8 集（会员） | 去掉 `P00001` | `A00011` | 0 |
| 第 8 集（会员） | 只带 `P00001` | `A00011` | 0 |
| 第 8 集（会员） | 无 | `A00011` | 0 |

### 6.3 码流对比

| 项目 | 免费集（VMS） | VIP 网页流 |
|---|---|---|
| 分辨率 | 1280x536（最高） | 页面 `bid=600`，精确分辨率未从日志确认 |
| 编码 | H.264 / H.265 均有 | `vcodec=2`（H.264） |
| 帧率 | 未记录 | 25fps |
| 分段时长 | 3–5s | 3–5s |
| 分段大小 | 242 KB / 3s | 0.24–3.37 MB |
| 播放路径 | ExoPlayer | WebView MSE |

### 6.4 设备解码器

```
OMX.amlogic.hevc.decoder.awesome
  type=video/hevc
  max=3840x2160
  adaptive-playback
```

---

## 7. 建议

### P0：实现爱奇艺 VIP 播放接口（根治）

目标：让会员集也走 `IqiyiPlayerActivity`，不再回退 WebView。

需要做的事：

1. 复刻网页播放器的 VIP 播放请求（`authKey`、`qd_sc` 等签名参数）；
2. 或者引入一个能执行网页 JS 的解析层，输出**每个分段都带完整签名**的播放列表；
3. 用 ExoPlayer 播放，保留现有硬解、控制条、下一集逻辑。

验收指标（建议）：

- 点击到首帧 ≤ 2.5s（STI6110，1080P，3 次取中位数）；
- 连续播放 60s，`droppedBufferCount / renderedOutputBufferCount` ≤ 1%；
- 全程不出现 `IqiyiWebPlayerActivity`；
- 首帧后 10s 内无 `STATE_BUFFERING`。

> 本次未完成该接口，因此以上指标是建议值，不是已达成值。

### P1：短期降负载（可选，需产品确认）

在 VIP 仍走 WebView 的前提下，可评估：

- 强制 720P 起播（用清晰度换流畅度）；
- 默认关闭弹幕/降低弹幕密度；
- 减少起播阶段的布局注入（本次已删掉一次 3s 注入）。

注意：这些都没有本次的量化数据支撑，必须单独做 A/B 测试；且不能宣称“修复了 1080P 卡顿”。

### P2：设备/内核层面

- 把 WebView 升级到 80+，或换用 WebView 较新的盒子；
- 这是最省开发成本的方案，但需要动设备系统/硬件，应用本身无法完成。

### P3：建立性能回归基线

建议把本次临时日志沉淀为正式埋点：

- `prepare` 耗时、首帧耗时、`codec`、`width/height`、`dropped/rendered`；
- 每次播放器改动跑 3 次，记录中位数；
- 覆盖：免费集、VIP 集、B 站、芒果各 1 条。

---

## 8. 本次验收

- `scripts/tv-build.sh`：BUILD OK（提交 `b2264d2`）
- `scripts/tv-verify.sh --serial 192.168.2.32:5555`：PASS（8 次焦点、6/6 移动、3 截图、无崩溃/ANR）
- 证据目录：`build/dsh-tv-verify/20261025-015954-52826/`

## 9. 局限与未验证项

1. **单设备、单内容、单次采样**：只测了 STI6110 上的《我的阿勒泰》第 8 集，不能代表所有爱奇艺内容/所有设备。
2. **没有 WebView 帧率数据**：本次只记录了原生播放器的 `rendered/dropped`；WebView 路径没有 FPS/掉帧统计，无法量化“卡”的具体程度。
3. **没有 CPU/GPU/内存 profiling**：未用 `systrace`/`perfetto`，无法区分 JS 主线程、合成器、解码器各占多少。
4. **`A00011` 的官方语义未确认**：只能确认“会员集 + 当前 Cookie 组合拿不到 `vidl`”，不能断言它就是“需要 VIP 签名”。
5. **VIP 原生播放未成功**：因此没有“原生 1080P 首帧/掉帧”数据，不能宣称原生方案一定达标。
6. **布局注入削减未做 A/B**：保留改动，但收益未量化。
7. **移动版/原生接管均已回退**：当前代码行为是“免费集原生、VIP 回退 WebView”，与测试前一致。

---

## 10. 附录：关键日志摘录

```
IqiyiWebPlayer: handoff native m3u8 host=meta-cdn.video.iqiyi.com
IqiyiPerf: state=2 t=1124ms pos=0
IqiyiPerf: prepare t=1134ms host=meta-cdn.video.iqiyi.com
IqiyiPerf: t=2136ms pos=0 buf=0 state=2 rate=1.0 rendered=-1 dropped=-1
...
ExoPlayerImplInternal: androidx.media3.exoplayer.ExoPlaybackException: Source error
ExoPlayerImplInternal: Caused by: HttpDataSource$InvalidResponseCodeException: Response code: 405
IqiyiPlayer: playback failed code=ERROR_CODE_IO_BAD_HTTP_STATUS type=ExoPlaybackException
```

```
# 会员集 VMS
code=A00011 vidl=0

# 免费集 VMS
code=A00000 vidl=7   # 最高 1280x536
```

```
# 网页播放器控制台
Decoder push switch stream by change videoId
  [VODTS, 2143888500841700, 600 -> VODTS, 2143888500841700, 500]
```

---

## 附：同期芒果进度条修复（非爱奇艺性能项）

- 问题：按左右键调整进度时，芒果进度条会从 4dp 放大到 12dp。
- 原因：聚焦监听里设置了 `scaleY = 3f`。
- 修复：删除 `scaleY`，只保留颜色高亮；高度保持不变。
- 验证：`build/dsh-tv-verify/20261025-015954-52826/extra/mango-bar-height.png`。
