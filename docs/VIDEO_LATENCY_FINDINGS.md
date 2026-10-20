# 视频花屏修复后持续延迟：本次排查结果

## 已确认的接收端问题

当前播放器会保留启动时形成的媒体时间线积压，缺少把播放位置逐步追到最新接收数据的机制。版本 0.8.3 为降低花屏/反复重连取消了旧的 500 ms 积压重连检查；0.8.4 恢复 UDP 时把积压重连门限设为 1200 ms。实际约 625 ms 的积压因此始终处于不处理区间。这是本次已经确认的软件问题，解释了稳定播放后仍持续落后的现象。

这里的 625 ms 是最新接收样本与播放器当前进度之间的媒体时间差，不是整个摄像头到屏幕的绝对延迟，也不表示每个时间点都存有完整帧。最初积压如何形成仍需发送端管线或 RTP 级时间测量确认。

## 真机证据

2026-10-19 从 MK32 拉取到版本 `0.8.5-diagnostic` 的持久日志。采集前设备实际播放了约 68 秒。原始证据保存在本地忽略目录：

- `artifacts/mk32-video-diagnostics/20261019_205840_player.csv`
- `artifacts/mk32-video-diagnostics/20261019_205840_logcat.txt`

| 检查项 | 实际结果 | 含义 |
| --- | --- | --- |
| 当前 RTP 传输 | `RTP/AVP;unicast;client_port=46086-46087` | UDP，而非当前仍在强制 TCP |
| 首帧到达时 | `after_ms=807 buffered_ms=600` | 首次显示时已有约 600 ms 待播时间跨度 |
| READY 状态采样 | 68 次，596–658 ms，平均 625.44 ms | 积压超过一分钟没有被消除 |
| 稳定播放速度 | 始终 `speed=1.0` | 按正常速度顺序播放，无法主动消化固定积压 |
| 接近播放末尾 | `position_ms=67300 buffered_position_ms=67930` | 最新接收媒体已比当前播放位置超前 630 ms |
| 解码 | `OMX.qcom.video.decoder.avc`，稳定阶段约 30 FPS，`dropped=0` | 当前样本不支持“硬解码吞吐不足导致不断积压”的判断 |
| 直播偏移 | `live_offset_ms=-9223372036854775807` | `C.TIME_UNSET`，内置直播追帧所需的绝对直播偏移不可用 |
| SDP 声明码率 | `b=AS:6144` | 声明约 6.1 Mbit/s；用户确认后续已升至约 6 Mbit/s，并非本次独立发现的配置错误 |

连接初期曾多次 `ECONNREFUSED`；服务启动后建链成功。末尾网络切断后进入 BUFFERING。两者与中间一分钟稳定的约 625 ms 落后是不同现象。

## 花屏修复前后的实际改动

仓库仅有最初一次提交，没有可直接 git bisect 的连续版本。本次依据同一工程本地开发记录中的实际补丁还原以下改动；未执行旧 APK 的同机 A/B 对照。

| 版本 | 视频策略 |
| --- | --- |
| 0.8.2 | 积压超过 500 ms 重连，并增加 PTS 相对运行时间漂移超过 450 ms 重连。该方法不能估计绝对端到端延迟，容易引起反复重建会话。 |
| 0.8.3 | 为缓解花屏，强制 RTP/TCP；LoadControl 配置从 50/300/30/50 改为 100/500/50/100 ms；删除积压和 PTS 漂移重连检查，只处理较长停帧。 |
| 0.8.4 | 恢复 UDP 优先，配置恢复 50/300/30/50 ms；重新加入积压检查，但门限为 1200 ms，未增加安全追帧策略。 |
| 0.8.5-diagnostic | 继承 0.8.4 播放策略，增加诊断日志。 |

所以，0.8.3 的 TCP 改动可能解释该版本的恶化，但不能用它解释当前样本，因为当前已确认使用 UDP。当前持续落后的具体原因是积压被保留且没有追帧；撤回 TCP 设置不能同时解决这一问题。

## 为什么配置“50–300 ms 缓冲”仍能看到 625 ms

Media3 1.1.1 的 RTSP `RtspMediaPeriod.continueLoading()` 不会根据该时间阈值截断正在进行的 RTP 接收，`reevaluateBuffer()` 也不主动清理待播数据。`DefaultLoadControl` 配置不是 RTSP 队列端到端的硬上限，调小数字不会自动丢弃已经形成的积压。

另外，`RtspMediaSource` 生成的时间线设为 `isDynamic=false`；而 `ExoPlayerImplInternal.shouldUseLivePlaybackSpeedControl()` 要求 `isLive && isDynamic && windowStartTimeMs != C.TIME_UNSET`。所以该 RTSP 路径不会自动启用内置直播变速追帧，日志中的 `speed=1.0` 和直播偏移不可用与源码一致。

## 修复方向与本次边界

修复应补充面向 RTSP 的安全追帧策略：依据实际待播时间跨度，用有限的小幅变速消化已完整接收的帧，恢复到目标余量后回到 1.0；保留迟滞和欠缓冲保护。需要同机实测硬解码吞吐、花屏、断流重连后的积压，以及追帧后的端到端秒表延迟。不能直接把重连门限调回 500 ms，因为当前每次启动都可能出现 600 ms 积压，这会再次触发循环断流；也不能随机丢弃 H.264 参考帧。

上述排查阶段没有修改播放策略。随后用户补充了 Orin 端的队列/编码测量结果，下面记录 0.8.6 的实际修复与验证。完整摄像头到屏幕延迟仍需真实链路秒表测量，不能用 App 缓存大小代替。

## 0.8.6 修复与 MK32 同机验证

播放器现在根据待播时间跨度执行有迟滞的有限追帧：持续积压达到 300 ms 后，使用 1.1–1.2 倍速，降到约 180 ms 时恢复 1.0。输入/渲染不再更新或进入 BUFFERING 时取消加速。保留正常 H.264 解码顺序，避免随机丢参考帧。

持续 BUFFERING 达约 400 ms 时释放旧播放器重建会话；每 500 ms 检查 eth0/IP 变化，网卡丢失时结束旧会话，恢复 IPv4 后自动重试。修复 Surface 重建后监控未重新启动的问题。首次连接/等待首帧使用独立超时，不把初始化误当成播放中断。UDP 优先仍为真实 Orin 的默认传输。

实测 APK：`0.8.6`，versionCode 21，已在 MK32 上核对安装。测试使用 Ubuntu 本地 GStreamer 测试球、1280×720/30 FPS、x264 zerolatency、约 6 Mbit/s；通过 USB ADB reverse 走 RTP/TCP，独立于 Orin 相机和无线链路。调试包只允许 loopback 测试地址，关闭追帧和注入暂停的选项在正常 Orin 地址上无效。

| 同机测试 | 结果 |
| --- | --- |
| 暂停播放 600 ms 后恢复，关闭追帧 | 待播积压持续存在，稳定采样平均 635.29 ms |
| 同样条件，开启追帧 | 3068 ms 内降到目标附近；末尾 5 次采样平均 176.6 ms，恢复 1.0 倍速 |
| 追帧阶段 | 未触发重连；稳定阶段 renderer dropped=0 |
| 暂停本地 RTSP 服务进程 3 秒模拟断流 | BUFFERING 后 402 ms 重连，日志确认 RELEASE 和 RTSP TEARDOWN |
| 服务恢复 | 同一个 App 进程自动建新会话并出首帧；随后待播积压约 107–133 ms |
| 单元测试 | 22 项通过，含 6 项追帧状态/流量仿真测试 |

这些结果证明客户端可以消化原来永久保留的固定积压，并能重建断流会话；不能代替真实 UDP/无线链路的画质、丢包和绝对延迟验收。网络已经物理断开时，客户端无法保证 TEARDOWN 到达 Orin，服务端仍需要自身的超时清理策略。

原始验证文件保存在 `artifacts/rtsp-ab-test/`：`baseline.csv`、`catch-up.csv`、`recovery.csv`、`recovery-logcat.txt`、`result.json` 与测试画面截图。退出测试后已恢复默认 Orin 播放地址。

验证结果可重复计算：

```bash
/usr/bin/python3 tools/verify_rtsp_backlog_test.py \
  artifacts/rtsp-ab-test/baseline.csv \
  artifacts/rtsp-ab-test/catch-up.csv \
  artifacts/rtsp-ab-test/recovery.csv
```

## 真实链路用户验收

2026-10-19，用户反馈“问题成功解决”。记录为 0.8.6 本轮视频问题的真实使用验收通过。前述 USB 同机测试用于量化追帧和会话恢复；用户反馈没有附带新的摄像头到屏幕同步计时测量，不将 176.6 ms 待播均值解释成真实端到端延迟。

完整开发、部署、数据流与维护说明见 [开发文档](DEVELOPMENT_GUIDE.md)。

## 对照的官方源码

- [RTSP 时间线的 isDynamic 配置](https://github.com/androidx/media/blob/1.1.1/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtspMediaSource.java)
- [RTSP 持续加载和缓冲行为](https://github.com/androidx/media/blob/1.1.1/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtspMediaPeriod.java)
- [内置直播追帧的启用条件](https://github.com/androidx/media/blob/1.1.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java)
