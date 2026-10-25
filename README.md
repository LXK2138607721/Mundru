<div align="center">

# Mundru

**极简纯粹 · 手感至上 · 100% 离线隐私的本地相册与实况照片整理工具**

[![Platform](https://img.shields.io/badge/Platform-Android%209.0~16%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/Compose-BOM%202024.12.01-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](#)
[![Privacy](https://img.shields.io/badge/Internet%20Permission-0%20(Pure%20Offline)-00C853?style=flat-square)](#)
[![Package Size](https://img.shields.io/badge/APK%20Size-~3.5%20MB%20(R8%20Minified)-blue?style=flat-square)](#)
[![Developer](https://img.shields.io/badge/Developer-Muran-orange?style=flat-square)](#)

</div>

---

## ✦ 最新版本：v1.1.1

[下载安装包 Mundru.apk](https://github.com/LXK2138607721/Mundru/releases/download/v1.1.1/Mundru.apk) · [本次修复事项](CHANGELOG.md)

修复相片冷却与抽样、Activity 重建时的操作保存，以及双击指令丢失和焦点偏移；减少重复解析与未播放实况照片的资源占用。现有界面和功能保持不变。本版完成代码审查、26 项 JVM 回归与签名 Release 检查，未进行真机实测。

---

## ✦ 品牌寓意

> **Mundru** 源自格陵兰语（Greenlandic），意为「**擦掉 / 擦除**」。

在数字相机的快门泛滥成灾的时代，相册里堆满了连拍残次、临时截图与模糊废片。  
Mundru 摒弃了一切社交干扰、商业云推销与臃肿算法，回归相册整理的本初——**用最极简的手势流与极致的物理手感，赋能每一帧回忆的沉淀与擦除**。

---

## ✦ 核心特性

### 1. 🛡️ 0 联网权限 · 绝对离线隐私
- `AndroidManifest.xml` 中 **完全不声明 `INTERNET` 权限**，从操作系统底层切断任何网络传输可能；
- 0 遥测打点、0 第三方推送、0 商业广告 SDK；相册与实况照片 100% 驻留设备本机，杜绝任何隐私泄漏之虞。

### 2. 🖐️ 单手手势流 · 极致操控触感
- **左滑保留（Keep）**：轻滑翻页，伴随微弱的触觉轻触反馈，顺畅滑入下一张；
- **上滑暂存（Trash）**：向上推动卡片，触发阻尼阶梯顿挫，精准移入应用内回收站暂存；
- **右滑回溯（Undo）**：右滑瞬时撤销上一操作，找回刚刚滑走的相片，绝无后悔药焦虑；
- **双击动力学展开（Zoom）**：以点击位置为中心，通过 280ms 物理阻尼曲线平滑放大至 2.5x；
- **全屏双指捏合（Inspect）**：1.0x ~ 5.0x 自由平移与边界回弹约束，微观对比锐度与对焦；
- **批次化整理**：支持 5 / 10 / 15 / 20 / 30 张批次自定，每批完成触发双脉冲振动与轻量彩带结算。

### 3. 🎬 原生零依赖实况照片（Motion Photo）播放
- 零第三方重量级音视频解码依赖，基于原生 `MediaDataSource` + `MediaPlayer` + `TextureView`；
- 毫秒级流式定位并提取三星/小米/OPPO/vivo 等主流 Android 机型的 XMP / MP4 局部微视频切片；
- 长按卡片原位即刻唤醒动态画面并同步输出原声音轨，松手平滑复原。

### 4. 🎞️ GIF 动图全链路原生解码
- 深度装配 Coil 原生 `ImageDecoderDecoder` 与 `GifDecoder`；
- 整理卡片堆叠流与大图查看器中全自动循环播放，并附带专属极简紫色 `GIF` 状态徽标。

### 5. 🧭 四维智能抽样与专清引擎
- **随机漫游（Random Walk）**：均衡时间分层抽样 + 24 小时严格冷却，保护期内不重复推送已审阅照片；
- **时光胶囊（Time Capsule）**：优先半年前的老照片与“那年今日”；没有半年老照片时，沿用至少 30 天前照片的降级规则；
- **图集专清（Album Cleanup）**：按照片数量降序自动聚类系统图集，支持对膨胀相册开展靶向专清；
- **月份专清（Month Cleanup）**：按历史年月时间轴无缝穿梭，专治某年某月的历史堆积。

### 6. 🗑️ 真实物理删除 · 拒绝幽灵残留
- 深度适配 Android 11+ Scoped Storage 存储安全规范；
- 设有独立回收站，精准统计暂存占用空间（MB / GB），支持单张/多选即时恢复至相册；
- 物理销毁接入系统级单次弹窗确认授权，通过 `PhotoId` + `URI` 双重联合主键同步擦除，彻底根绝伪删除与幽灵条目残留。

### 7. 📳 原生硬件触觉反馈引擎
- 提供 4 档触感微调（关闭 / 轻柔 / 适中 / 强劲），点击即时触发真实手感预览；
- 兼容 Android 9 ~ 16+（真机实测在 Android 16 上运行良好），Android 12+ 采用 `VibratorManager` 与 `VibrationAttributes.USAGE_TOUCH`；
- 贯穿卡片拖拽阈值微顿挫、丢进回收站阻尼确认、保留轻击与结算双脉冲。

### 8. 🕶️ 后台多任务防窥双保险
- **底层窗口防御**：开启后由系统级 WindowManager 长效维持 `FLAG_SECURE`，阻断多任务界面截屏与录屏；
- **瞬时焦点捕获**：重写 `onWindowFocusChanged`，在用户手势上滑呼出多任务的一瞬间（焦点丢失）立即激活 Compose 顶层纯净遮罩（居中展示小幽灵 Logo 与“Mundru 隐私保护中”），彻底封堵 Android 手势导航提前截屏泄露相册的漏洞。

### 9. 🌗 三模式外观与沉浸深黑转场
- 自由切换「跟随系统」、「白天模式」与「夜间模式」，状态栏与导航栏时间/图标毫秒级自动反色联动；
- **白天模式**：纯净纸质白基底（`#F9F9FB`）+ 瑞士排版深炭黑；
- **夜间模式**：深度空灵炭黑（`#0E0E12`）+ 高对比度交互层（`#2C2F38`），图标与副文本高保真呈现；
- **全链路四重防闪白**：Window 预着色 + Theme 强绑定 + Surface 纯黑地基 + 转场层级控温，彻底根绝夜间切页边缘白边与刺眼闪白。

---

## ✦ 手势交互指南

| 动作手势 | 触发行为 | 触感反馈 | 适用场景 |
| :--- | :--- | :--- | :--- |
| **卡片向左滑出** | **保留相片**，自动切至下一张 | 轻柔轻击（Light Click） | 确认保留的美好回忆 |
| **卡片向上滑出** | **暂存回收站**，待后续彻底销毁 | 阻尼重击（Heavy Click） | 模糊、连拍废片、过期截图 |
| **卡片向右滑出** | **撤回回溯**，找回上一张相片 | 回弹微震（Tick） | 误滑抢救、二次比对 |
| **单击卡片右下** | 翻转查看详细 EXIF 参数 | 极轻点触 | 查看拍摄参数、分辨率与体积 |
| **长按卡片画面** | 播放实况照片（Motion Photo） | 柔和长振 | 静态照片中重温动态原声实况 |
| **双击卡片画面** | 平滑展开原图，对焦放大至 2.5x | 弹性轻击 | 细看眼神、焦点清晰度 |
| **大图双指捏合** | 1.0x ~ 5.0x 自由无级缩放平移 | 边界微阻尼 | 局部放大微观细节 |

---

## ✦ 技术底座与架构

```
app/src/main/java/com/lumina/cleaner/
├── core/
│   ├── database/          # Room SQLite 存储层 (WAL 模式, PhotoEntity, LocalTrashEntity)
│   ├── model/             # 核心数据模型 (PhotoItem, AlbumInfo, MonthBucket)
│   └── util/              # 原生硬件触觉引擎 (HapticFeedback, 兼容 Android 9~16+)
├── domain/
│   ├── album/             # 图集聚类与管理引擎 (AlbumManager)
│   ├── motion/            # 原生零依赖实况解析器 (MotionPhotoParser, XMP/MP4 偏移量提取)
│   └── sampling/          # 四维智能抽样引擎 (SamplingEngine, 24h 严格冷却)
├── ui/
│   ├── album/             # 图集专清与月份专清抽屉 (AlbumBottomSheet, MonthBottomSheet)
│   ├── deck/              # 动力学卡片堆叠流 (CardStack, DeckCard, ModePill)
│   ├── motion/            # 原生低延迟实况播放器 (MotionPhotoPlayer, TextureView)
│   ├── onboarding/        # 首启极简引导中心 (OnboardingScreen, 权限前置引导)
│   ├── settings/          # 偏好设置、赞赏支持与外观主题 (SettingsScreen)
│   ├── theme/             # 瑞士排版色板、双模中性色与状态栏自适应 (Color, Theme)
│   ├── trash/             # 真实回收站看板与批量销毁 (RecycleBinScreen)
│   └── viewer/            # 沉浸式高保真原图查看器 (PhotoViewerOverlay, 280ms 动力学缩放)
└── widget/                # Android 桌面小组件 (MundruWidgetProvider)
```

| 维度 | 规格 / 选型 | 说明 |
| :--- | :--- | :--- |
| **开发语言** | Kotlin 2.1.0 | 严格空安全，协程异步流式处理 |
| **UI 架构** | Jetpack Compose (BOM 2024.12.01) | 全声明式响应式渲染，单向数据流 |
| **系统兼容** | minSdk 28 (Android 9.0 ~ Android 16+） | compileSdk 35，覆盖绝大多数主流机型与前沿系统 |
| **本地数据库** | Room SQLite 2.6.1 (WAL 模式) | 毫秒级全库索引与状态管理 |
| **图片加载与动图** | Coil 2.7.0 + ImageDecoderDecoder | 原生硬件位图渲染与 GIF 动图原生支持 |
| **代码混淆与优化** | R8 Full Mode (Shrink Resources + Minify) | 编译输出体积仅约 3.5 MB |
| **权限清单** | 0 个 `INTERNET` 权限 | 仅声明系统必要相册读取与振动硬件权限 |

---

## ✦ 编译与构建

### 1. 准备环境
- **JDK**：OpenJDK 17 或 Microsoft JDK 17+
- **Android SDK**：API Level 35 (Android 15), Build-Tools 35.0.0+
- **Gradle**：8.11.1（工程已内置 `gradlew` 包装器）

### 2. 命令行编译

```bash
# 1. 克隆代码仓库并进入工程目录
git clone https://github.com/LXK2138607721/Mundru.git
cd Mundru

# 2. 编译调试版 (Debug)
# Linux / macOS
./gradlew assembleDebug

# Windows (PowerShell / CMD)
.\gradlew.bat assembleDebug

# 3. 编译正式优化版 (Release，自动执行 R8 裁剪压缩与混淆)
# Linux / macOS
./gradlew assembleRelease

# Windows (PowerShell / CMD)
.\gradlew.bat assembleRelease
```

构建完成后的安装包输出路径：
- **Debug 调试包**：`app/build/outputs/apk/debug/app-debug.apk`
- **Release 正式包**：`app/build/outputs/apk/release/app-release.apk`

---

## ✦ 签名与发布

- **开发者**：沐然 (Muran)
- **证书指纹**：
  - `SHA1: 96:98:7C:24:8B:46:C0:11:DF:5E:AC:30:FA:E5:0E:15:26:0D:43:32`
  - `SHA256: 19:7D:9E:DB:F5:0C:A3:23:CE:A8:C4:FE:C6:F0:E7:CD:96:25:23:5E:22:27:1A:51:06:36:B7:CA:10:12:8D:72`
- **有效期**：100 年（至 2126 年）
- **发布原则**：杜绝冗余存档，根目录始终保持单一纯净的 `Mundru.apk`。

---

## ✦ 开源与致谢

- **图标设计**：圆润大眼黑墨小幽灵（纯白底座 / 自适应多端图标 / Material You 单色兼容）；
- **赞赏支持**：应用内设置页提供纯净方形微信与支付宝打赏通道，感谢所有支持独立开发的朋友。

---

<div align="center">

Crafted with dedication by **Muran** · 2026

</div>



