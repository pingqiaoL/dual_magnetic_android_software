# MK32 视频延迟分层诊断

更新：已获取约 68 秒真实播放日志，确认约 625 ms 的待播积压始终保留。0.8.6 已加入安全追帧并在 MK32 USB 同机测试中把积压从约 635 ms 降至约 177 ms，断流自动重连也已验证；版本回溯、测试条件和完整证据见 [本次排查结果](VIDEO_LATENCY_FINDINGS.md)。以下方法用于继续验收真实无线链路与完整端到端延迟。

## 为什么不能继续只调播放器缓存

端到端延迟由摄像头采集、图像队列、编码、RTSP 服务、RTP 接收、解码和显示共同组成。播放器只能看到进入设备后的时间戳，无法从普通 RTP PTS 推导摄像头曝光时刻。要区分稳定的约 1 秒延迟位于哪一层，必须同时使用播放器内部统计和画面中的发送端时间标记。

当前诊断版把数据保存到应用私有目录 `files/video_diagnostics.csv`，每秒记录。后台写入队列有上限，日志滚动保留两份各约 2 MiB 的文件，不在解码回调中写磁盘。

- `buffered_ms`：Media3 已接收但尚未播放的时长；
- `release_lead_us`：Media3 计划显示这一帧的时刻距离当前时刻有多远；
- `processing_offset_avg_us`：视频帧处理时间偏移；
- `rendered/skipped/dropped`：硬解码器累计输出、跳过和丢弃帧数；
- `pts_us/pts_step_us`：RTP 解包后的视频 PTS 及相邻帧间隔；
- `READY/FIRST_FRAME`：建链、首帧和硬解码器初始化耗时。

## MK32 实测步骤

MK32 的 ADB 数据口与内置 USB 以太网共用 OTG 控制器，插 USB 数据线时 `eth0` 会消失。本次读到固件已经配置 `persist.adb.tcp.port=5555`，尚未验证无线 ADB 能否跨当前虚拟机网络访问。按以下顺序测试：

1. 安装诊断 APK，并确认 `persist.adb.tcp.port=5555`。
2. 拔掉 USB 数据线，重启 MK32，使内置 USB 网卡和 `eth0` 恢复。
3. 保持视频播放 30 秒，快速左右移动镜头前的物体，至少人为制造一次明显运动。
4. 优先从虚拟机执行 `adb connect 192.168.144.20:5555`；如果宿主机网络没有把该网段桥接给虚拟机，则播放完成后重新插 USB。
5. 执行：

   ```bash
   tools/collect_mk32_video_diagnostics.sh
   ```

## 判断规则

- `release_lead_us` 较大：说明已解码帧还在等待计划输出时间。但该回调通常临近输出才触发，数值较小也不能排除帧此前在播放器、解码器中等待；不能用它独立定位整秒延迟。
- `buffered_ms` 较小但实景仍慢：需要进一步检查播放器/解码器内部等待、MK32 无线传输、Orin 图像队列和采集端。此值不包含所有隐藏缓冲，也不是端到端延迟。
- `buffered_ms` 持续增大：存在待播积压。用丢旧帧或追帧前，需要确定 H.264 参考帧完整性，避免任意丢压缩帧造成花屏。
- `dropped` 快速增加：表示解码器/渲染器未按时显示的帧增加，并非 RTP 丢包计数；马赛克还需结合 RTP 序号缺口、解包警告和源端图像检查。提高码率可能加剧无线拥塞，不能直接作为修复。
- 只有首帧约 1 秒而稳定播放延迟很小：先检查关键帧等待和握手/解码初始化。GOP 30 在 30 FPS 下正常最多等约 1 秒关键帧（不计丢包），这与持续落后是两个指标。

## QGroundControl 的处理方式

QGC 使用自建 GStreamer 实时管线，不使用 Android 通用媒体播放器。它的低延迟模式对 RTSP 使用零毫秒抖动缓冲、关闭 RTP 重传并启用 `drop-on-latency`；接收测试管线使用 `sync=false`，目标是尽快显示最新帧，而不是保持连续播放所有旧帧。代价是在网络差时主动丢帧并可能出现不流畅。

本 App 当前使用 Media3 1.1.1。该版本 RTP 重排窗口等待约 30 ms，没有固定 1 秒重排缓冲；它顺序包直接出队，只在缺包时等待。`DefaultLoadControl` 控制待播媒体阈值，不是整个 RTSP 接收/解码链路的硬上限，RTSP 读取也有其自身加载行为。Media3 没有暴露与 GStreamer 完全相同的 `latency/drop-on-latency/sync` 控制。是否迁移到 GStreamer Android，应以同一 MK32、同一视频流的对比结果决定。

Ubuntu 已安装 GStreamer 时，可以用下列脚本测试参考 QGC 策略的简化接收管线。它用 Ubuntu 软件解码/桌面显示，并不是 QGC Android 的完整实现：

```bash
tools/test_rtsp_qgc_low_latency.sh
```

该测试强制 RTP/UDP，必须确保 NAT/防火墙允许 UDP 返回。两台设备的无线/有线网络路径和解码器不同，Ubuntu 更快不能单独证明 App 有错，Ubuntu 也慢同样不能直接排除接收端等待。最终对照应在同一 MK32 上交替运行两种播放器。本次 QGC APK 官方旧下载地址返回 403，未完成 QGC 实机安装与比较。

## 发送端的确定性测量

最直接的方法：让摄像头拍摄一个毫秒秒表，用另一部手机同时拍下原始秒表和 MK32 上显示的秒表，暂停比较两者，得到完整端到端延迟；该方法不要求 MK32 与 Orin 时钟一致。本次 MK32 系统时间显示在 2023 年，主机在 2026 年，不能直接相减计算延迟。

也可在 Orin 视频中临时烧录捕获帧时刻，经过时钟同步后比较接收端时间。编码前临时叠加的当前时间只能测量叠加之后的耗时，不能代表摄像头曝光时刻；递增帧号本身也不能换算绝对延迟。RTCP NTP 映射还需要确认源端时钟和时间戳语义，普通 RTP PTS 不足以完成这一判断。

## 首轮检查证据与边界（后续结果见上方更新）

- ADB 曾确认安装版本 `0.8.4`、Android 9、H.264 解码器 `OMX.qcom.video.decoder.avc`。
- 首次安装 `0.8.5-diagnostic` 返回 `Success`，`dumpsys package` 核对到 versionCode 20。随后补充的日志/生命周期修订已编译，但再次覆盖安装时 ADB 传输挂起，连接转为离线；尚未确认最新 APK 在设备中。
- 当前 USB ADB 连接时仅有 loopback、没有 `eth0`，不能现场抓取正在播放的真实 RTP 会话。
- 虚拟机经 NAT ping `192.168.144.129` 有响应；8554 TCP 可建链但 RTSP OPTIONS 无返回，SSH 也停在协议 banner 之前。此结果不能区分服务异常与宿主机/NAT 路径问题，须在真实设备网口路径复测。
- 尚未得到稳定播放时的诊断 CSV 或端到端秒表测量，不能声明 1 秒延迟的根因已确定或修复。

## 官方参考

- [QGC RTSP 源和抖动缓冲配置](https://github.com/mavlink/qgroundcontrol/blob/master/src/VideoManager/VideoReceiver/GStreamer/GstSourceFactory.cc)
- [QGC GStreamer 架构与测试管线](https://github.com/mavlink/qgroundcontrol/blob/master/src/VideoManager/VideoReceiver/GStreamer/README.md)
- [QGC 低延迟模式说明](https://github.com/mavlink/qgroundcontrol/blob/master/docs/en/qgc-user-guide/settings_view/general.md)
- [Media3 1.1.1 RTP 提取器的 30 ms 重排等待](https://github.com/androidx/media/blob/1.1.1/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtpExtractor.java)
- [Media3 1.1.1 RTP 重排队列](https://github.com/androidx/media/blob/1.1.1/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtpPacketReorderingQueue.java)

