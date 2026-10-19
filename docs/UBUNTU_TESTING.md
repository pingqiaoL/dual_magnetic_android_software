# Ubuntu Android 测试环境

当前虚拟机已经安装：

- Android SDK Platform 33
- Android Build Tools 30.0.3 / 33.0.2
- Android Platform Tools / adb
- Android Emulator 37.1.11
- Android 9 / API 28 AOSP x86_64 系统镜像
- `MK32_AOSP_API28` 测试设备：1920×1080、横屏、320 dpi、2 GB RAM

## 一条命令启动并测试

```bash
cd /home/lpq/climb_wheel_software
./scripts/install_and_run_emulator.sh
```

该脚本现在会自动完成：启动模拟器、等待 Android 服务、执行单元测试和 Lint、构建 APK、安装、打开应用并保存截图。第一次启动因无 KVM 可能需要 3–8 分钟。如果终端显示应用启动成功但没有看到窗口，按 `Alt+Tab`，选择 `Android Emulator - MK32_AOSP_API28`。

## 只启动模拟器

显示模拟器窗口：

```bash
cd /home/lpq/climb_wheel_software
./scripts/start_android_emulator.sh
```

后台无窗口运行：

```bash
./scripts/start_android_emulator.sh --headless
```

当前 VMware 没有透传 VT-x/AMD-V，也没有 `/dev/kvm`，所以模拟器使用软件 CPU 和 SwiftShader 图形渲染。首次启动可能需要 3–8 分钟，期间 Ubuntu 负载较高。偶发的 `System UI isn't responding` 是无硬件加速导致的系统 UI 超时，选择 `Wait` 即可；首次全屏提示选择 `GOT IT`。脚本会关闭后续系统错误弹窗和动画，并自动处理已经出现的这两个提示。本应用进程和真机性能不受该模拟器速度代表。

## 单独执行构建、安装并运行

模拟器已经运行时也可以执行：

```bash
./scripts/install_and_run_emulator.sh
```

脚本会依次执行单元测试、Lint、APK 构建、安装、启动，并把截图保存到：

```text
artifacts/climbwheel-latest.png
```

## 停止模拟器

```bash
./scripts/stop_android_emulator.sh
```

## 提升速度

关闭 Ubuntu 虚拟机，在 VMware 虚拟机设置的“处理器”页面启用“虚拟化 Intel VT-x/EPT 或 AMD-V/RVI”。重新启动后检查：

```bash
ls -l /dev/kvm
```

若 `/dev/kvm` 存在，可以把启动脚本中的 `-accel off` 改为 `-accel on`，模拟器启动和操作速度会提高很多。

## 能测试和不能测试的内容

Ubuntu 模拟器可以测试：横屏 UI、演示波形、按钮交互、页面切换、MAVLink 解析单元测试和异常帧处理。

模拟器不能真实提供 MK32 的 `/dev/ttyHS0`、SBUS、思翼无线数传和硬件视频解码。上述部分仍需要 MK32 真机验证。
