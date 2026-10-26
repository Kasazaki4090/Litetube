# Litube 架构现代化与内核融合迁移计划

> [!NOTE]
> **目标**：以稳定高效的成熟内核（**PipePipeExtractor** `ce289b45503e06ae5e578ea70c92bc87d21b3a73`）为核心，吸收上游 Litube 3.0.x 的现代 Android 架构（Kotlin + Jetpack Compose + Media3 + Room + WorkManager），打造兼具「高可用免维护内核」与「现代轻量 UI 体验」的播放器。

---

## 一、 方案选型与技术路线对比

当前仓库原处于 Java + XML 架构（分支 `PipepipeExtractor`），而上游完成了一次彻底的 Compose 纯 Kotlin 重写（3.0.3）。我们采用 **路线 B（基于 v3.0 基线换装 PipePipeExtractor）**：

| 维度 | 路线 A：在现有 Java 代码上反向移植功能 | 路线 B（已采用）：基于 v3.0 基线换装 PipePipeExtractor |
| :--- | :--- | :--- |
| **工作模式** | 将上游的 Compose、Media3、Room 等逐个翻译/反向嫁接到旧 Java/XML 架构 | 以 `upstream/master` (3.0.3) 为基线，替换其脆弱的自研 Extractor，接入成熟的 PipePipeExtractor 并移植本地修复项 |
| **架构现代化** | 极低（受旧生命周期、XML 布局束缚） | 极高（纯 Kotlin、Jetpack Compose、Koin、Coroutines/Flow、Room） |
| **开发工作量** | 巨大且冗余 | 聚焦核心（适配 `Extractor` 接口层与迁移本地 Web 脚本/导航修复） |
| **后续上游同步** | 难以同步上游 UI 与新特性 | 架构完全对齐，后续上游的 UI 优化与小特性可轻松 cherry-pick |
| **内核稳定性** | 极佳（继续使用 PipePipeExtractor） | 极佳（剥离上游自研 Solver，换上成熟内核 `visionos` 客户端） |

---

## 二、 目标融合架构设计

```mermaid
graph TD
    subgraph UI_Layer [现代 UI 表现层 - Jetpack Compose]
        BrowserScreen["网页浏览 (BrowserScreen + core.js + subscribe_button.js)"]
        PlayerSurface["播放器界面 (PlayerSurface + 迷你窗/PiP)"]
        DownloaderUI["下载管理器 (Room + Compose)"]
        ExtensionUI["扩展与设置 (Settings & SponsorBlock)"]
    end

    subgraph Core_Engine [引擎与调度层]
        PlaybackEngine["Media3 播放引擎 (HLS / DASH / 纯音频)"]
        PlaybackRecovery["播放自愈与重试器 (403/过期链接捕获)"]
        DownloadCoordinator["下载协调器 (WorkManager 调度)"]
        CastController["开源 Cast 投屏 (NanoHTTPD 本地代理)"]
    end

    subgraph Kernel_Facade [内核门面接口层]
        ExtractionFacade["Extractor + Resolve + YoutubeStreamsCompat"]
    end

    subgraph Mature_Kernel [稳定解析内核]
        PipepipeExtractor["PipePipeExtractor (ce289b45... + visionos)"]
        NewPipeBase["NewPipeExtractor 规范 (流/音频/字幕数据源)"]
    end

    UI_Layer --> Core_Engine
    Core_Engine --> Kernel_Facade
    Kernel_Facade --> Mature_Kernel
    Mature_Kernel --> PipepipeExtractor
    Mature_Kernel --> NewPipeBase
```

---

## 三、 五步迁移执行记录（全部完成 ✅）

- [x] **Step 1：依赖配置升级 (`libs.versions.toml`)**
  - 将 `newpipe-extractor` 替换为 `com.github.InfinityLoop1308:PipePipeExtractor:ce289b45503e06ae5e578ea70c92bc87d21b3a73`。
- [x] **Step 2：Web 脚本增强移植 (`subscribe_button.js` & `WebViewFactory.kt`)**
  - 新增 `app/src/main/assets/script/subscribe_button.js`（清理冗余频道徽章、调整订阅按钮展示、修复纯数字显示为「已订阅」）。
  - 在 `WebViewFactory.kt` 注册注入顺序。
- [x] **Step 3：核心解析层换装与冗余清理 (`extractor/` & `di/`)**
  - 重构 `Extractor.kt`、`Resolve.kt`、`HttpDownloader.kt`、`YoutubeMediaRequests.kt`，通过 `NewPipe.init(downloader)` 与 `NewPipe.setYoutubePlayerClient("visionos")` 驱动提取。
  - 彻底移除上游自研的 `AndroidChallengeSolver`、`RemoteEjsRuntime`、`JavascriptRuntime`、`PoTokenProvider`、`AdaptiveClientOrder`、`BrowserPlayerResponses`、`YoutubeExtractionHost`、`YoutubeSessionProvider`、`MediaCookieIdentity` 以及 `assets/ejs`、`assets/potoken`。
- [x] **Step 4：页面跳转与迷你窗状态同步优化 (`BrowserViewModel` & `PlayerViewModel`)**
  - 区分非 Watch 页跳转与同页刷新，跨页导航时自动收起全屏播放为迷你窗（`minimize()`），点击相同视频时直接展开已有播放会话（`expand()`），并补充单元测试 `BrowserViewModelTest.kt`。
- [x] **Step 5：兼容层补齐与全量符号检查 (`YoutubeStreamsCompat.kt` 等)**
  - 新增 `org.schabi.newpipe.extractor.services.youtube.streams.YoutubeStreamsCompat.kt`，提供轻量兼容模型（`ClientProfile`、`FormatKey`、`StreamDemand`、`YoutubeSession`、`RequestPlan`、`ExtractionContext`、`StreamHttpException`），使 Media3 播放、缓存、下载、诊断模块与 `PipePipeExtractor` 无缝衔接。
  - 在 `Models.kt` 补齐 `ByteArray.hexString()` 扩展，在 `YoutubePlaybackCacheKey.kt` 完善无 `resourceIdentity` 时的 `ofQuery` 缓存键回退，在 `androidTest` 补齐 `ExtractionTestActivity.kt`。
- [x] **Step 6：旧分支 (`PipepipeExtractor`) 细节优化全量合入**
  - **全屏刘海屏/挖孔屏铺满优化 (`e21fbc6`)**：在 `PlayerWindowHost.refreshSystemBars()` 中为 Android P (API 28+) 全屏模式设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，消除横屏全屏两侧黑边（配合 v3 已有的 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 与窗口内 Compose 弹层 `FullscreenPlayerSheet`，彻底解决全屏状态栏闪烁与黑边问题）。
  - **原生播放器 → 网页 `#movie_player` 观看进度实时回写 (`9d8aeb1` / `e21fbc6`)**：在 `player-hook.js` 中暴露 `window.__syncPageProgress(targetSec)`（带 `window.__liteSyncing` 防递归锁），并在 `PlayerViewModel.maybeSyncWebProgress()` 中于每 5 秒、暂停瞬间及播放结束时自动将进度同步至网页端播放器，确保 YouTube 账号跨端观看历史与红条进度实时更新。
  - **记住字幕语言开关与中文字幕跨变体智能匹配 (`e390cb0`)**：新增 `PreferenceKeys.REMEMBER_SUBTITLE_LANGUAGE` 设置项（默认开启）及多语言文案，并在 `SubtitleSelection.select()` 中支持简繁及地区代码（`zh-Hans`、`zh-CN`、`zh-Hant`、`zh-TW`、`zh-HK`、`zh-SG`、`zh-MO`、`zh`）跨变体回退匹配。
  - **WebView 深浅色主题实时同步 (`dc75ae6`)**：在 `WebViewFactory.syncTheme()` 与 `BrowserScreen` 中联动系统深浅色模式（配置 `WebSettingsCompat` 暗色策略、更新 `.youtube.com` 的 `PREF` Cookie `f6=400/10000` 并实时注入 DOM/CSS 变量与 `ytm-app.setTheme`），无需重启即可无缝切换网页深浅色外观。
  - **Gradle 工具链与第三方依赖版本同步 (`bc792ec` / `0cde6a1`)**：
    - `Gradle Wrapper` 升级至 `9.7.0`（同步 `gradle-wrapper.properties`、`gradle-wrapper.jar`、`gradlew`、`gradlew.bat`）。
    - `AGP` 升级至 `9.4.0`（并在 `gradle.properties` 中同步 `android.builtInKotlin=false` 等 AGP 9 兼容配置）。
    - 同步更新 `mmkv = "2.4.1"`（支持 16KB 页大小对齐）、`okhttp = "5.3.2"`、`okio = "3.16.4"`、`webkit = "1.15.0"`、`swiperefreshlayout = "1.2.0"`、`activityCompose = "1.13.0"`、`lifecycle = "2.10.0"`、`androidxTestExtJunit = "1.3.0"`，并清理已废弃的 `androidx-javascriptengine` 依赖。
