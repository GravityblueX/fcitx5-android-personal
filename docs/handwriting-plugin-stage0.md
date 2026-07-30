# Fcitx17 多语言手写识别插件：阶段 0 架构调研

本文只记录阶段 0 的仓库调研和推荐方案，不接入 ML Kit，也不实现完整手写功能。

## 一、当前架构

### 1. 插件发现与数据加载

1. 插件 APK 通过一个导出的 Activity 暴露插件清单 Intent。
   - 当前主程序自身动作：`${BuildConfig.APPLICATION_ID}.plugin.MANIFEST`
   - 兼容的上游动作：
     - `org.fcitx.fcitx5.android.plugin.MANIFEST`
     - `org.fcitx.fcitx5.android.debug.plugin.MANIFEST`
2. `DataManager.queryPluginActivities()` 使用 `PackageManager.queryIntentActivities()` 查找插件，再从目标 APK 的 `res/xml/plugin.xml` 读取：
   - `apiVersion`
   - `domain`
   - `description`
   - `hasService`
3. 当前插件 API 版本固定为 `0.1`。运行时只比较版本字符串，没有使用 XSD 做运行时验证。
4. `DataManager.sync()` 读取插件 APK 中的 `assets/descriptor.json`，将插件资源合并进主程序的数据目录。
5. `DataHierarchy` 会阻止插件覆盖已被主程序或其他插件占用的文件、目录和符号链接。
6. 插件的 `nativeLibraryDir` 会被追加到 Fcitx 的动态库搜索路径，所以 Anthy、Rime 等插件可以把原生 Fcitx addon 装入主进程。
7. 安装、更新或卸载插件后，`PluginFragment` 只刷新检测结果；用户点击“重新加载”后，`FcitxDaemon.restartFcitx()` 才会重新同步数据并重建输入法列表。

当前加载流程可概括为：

```text
PackageManager 查询插件 Activity
  → 解析 res/xml/plugin.xml
  → 检查 plugin API 0.1
  → 读取 assets/descriptor.json
  → 合并 usr/share/fcitx5 等资源
  → 汇总插件 nativeLibraryDir
  → 启动 Fcitx
  → 加载 addon 和 inputmethod 配置
```

### 2. `hasService` 的实际用途

`hasService=true` 不代表插件已经获得通用输入能力。它只让 `FcitxPluginServices.connectAll()` 尝试绑定插件中动作名为 `${mainApplicationId}.plugin.SERVICE` 的服务。

绑定后，主程序把服务 Binder 当作 `Messenger` 保存。当前仓库没有定义任何实际使用的 Messenger 消息编号，`FcitxPluginServices.sendMessage()` 也没有调用方。

唯一现有服务插件 `clipboard-filter` 的实际通信方向是：

```text
主程序绑定插件 MainService
  → FcitxPluginService.start()
  → 插件反向绑定主程序 FcitxRemoteService
  → 通过 IFcitxRemoteService 注册 IClipboardEntryTransformer
```

因此 `hasService` 目前的核心作用是启动和维持插件进程，真正的类型化协议是 `lib/common` 中的 AIDL。

### 3. 当前跨 APK 协议

现有 AIDL 只有：

- `IFcitxRemoteService`
- `IClipboardEntryTransformer`

`IFcitxRemoteService` 当前支持：

- 查询主程序版本、PID 和已加载插件；
- 重启 Fcitx；
- 注册/注销剪贴板转换器；
- 重新加载拼音词典和快捷短语。

它不支持：

- 注册识别后端；
- 发送笔迹；
- 异步返回候选；
- 查询或下载识别模型；
- 识别任务取消或 `requestId` 校验；
- 插件向主程序提供输入面板。

主程序的 IPC 和插件服务都受 `signature` 级权限保护。独立 APK 必须与目标主程序使用同一签名，才能使用现有双向服务协议。

### 4. 输入法条目与生命周期

输入法条目由 Fcitx 原生 `InputMethodManager` 管理，不是 Android 插件层动态添加的 Kotlin 对象。

以 Anthy 为例，插件提供：

```text
usr/share/fcitx5/addon/anthy.conf
usr/share/fcitx5/inputmethod/anthy.conf
libanthy.so
```

`InputMethodListFragment` 调用：

- `availableIme()` 获取所有 Fcitx 输入法；
- `enabledIme()` 获取当前组中的输入法；
- `setEnabledIme()` 保存启用状态和排序。

切换输入法后，原生前端发送 `IMChangeEvent`。`FcitxInputMethodService` 和 `InputView` 再把事件分发给键盘、工具栏和其他组件。Android 14 及以上还会由 `SubtypeManager` 为已启用的 Fcitx 输入法生成动态 Android subtype。

### 5. 候选与文字提交

当前候选链路完全以 Fcitx 原生候选列表为源：

```text
Fcitx InputMethodEngine
  → InputContext.inputPanel().candidateList()
  → androidfrontend
  → FcitxEvent.CandidateListEvent
  → InputView / InputBroadcaster
  → HorizontalCandidateComponent
  → 用户点击候选
  → FcitxAPI.select(index)
  → CandidateWord.select()
  → InputContext.commitString()
  → CommitStringEvent
  → FcitxInputMethodService.commitText()
```

`HorizontalCandidateComponent` 的点击和长按行为直接依赖 Fcitx 候选索引与候选动作，因此不能直接接收插件 Binder 返回的候选。

主程序已经有公开的 `FcitxInputMethodService.commitText()`，但退格和回车的语义实现目前是私有方法。新手写面板应在主程序内调用或抽取这些现有编辑动作，不应让插件直接操作目标应用的 `InputConnection`。

### 6. 虚拟键盘面板

`InputView` 在主程序进程内创建：

- `KawaiiBarComponent`
- `InputWindowManager`
- `KeyboardWindow`
- 候选、预编辑、弹窗和浮动键盘组件。

`InputWindowManager` 管理的是编译进主 APK、加入 `DynamicScope` 的普通 Android View。当前没有远程 View、Surface、RemoteViews 或插件 UI 工厂协议。

因此插件 APK 不能独立把普通 View 嵌入现有键盘窗口。手写画布必须在主程序实现为通用组件，插件只提供识别与模型管理能力。

## 二、相关文件和类

### 插件发现与加载

- `plugin/pluginSchema.xsd`
- `app/src/main/java/org/fcitx/fcitx5/android/core/data/PluginDescriptor.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/core/data/DataHierarchy.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/ui/main/PluginFragment.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt`
- `lib/plugin-base/src/main/AndroidManifest.xml`

### 服务与 AIDL

- `app/src/main/java/org/fcitx/fcitx5/android/core/FcitxPluginServices.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/FcitxRemoteService.kt`
- `lib/common/src/main/java/org/fcitx/fcitx5/android/common/FcitxPluginService.kt`
- `lib/common/src/main/java/org/fcitx/fcitx5/android/common/ipc/FcitxRemoteConnection.kt`
- `lib/common/src/main/aidl/org/fcitx/fcitx5/android/common/ipc/IFcitxRemoteService.aidl`
- `lib/common/src/main/aidl/org/fcitx/fcitx5/android/common/ipc/IClipboardEntryTransformer.aidl`
- `plugin/clipboard-filter/src/main/java/org/fcitx/fcitx5/android/plugin/clipboard_filter/MainService.kt`

### 输入法注册与枚举

- `plugin/anthy/src/main/assets/usr/share/fcitx5/inputmethod/anthy.conf`
- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/core/Types.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/core/FcitxEvent.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodListFragment.kt`

### 候选、提交与键盘窗口

- `app/src/main/cpp/androidfrontend/androidfrontend.cpp`
- `app/src/main/cpp/androidfrontend/androidfrontend.h`
- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindow.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateComponent.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateViewAdapter.kt`
- `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/CandidateItemUi.kt`

## 三、现有插件能力

现有插件系统已经能够：

- 发现独立安装的 APK；
- 读取插件版本、说明、翻译域和 `hasService`；
- 合并插件的 Fcitx 配置、输入法配置、词典和其他资源；
- 把插件原生库加入 Fcitx addon 搜索路径；
- 通过静态 `.conf` 注册可启用、禁用和排序的输入法条目；
- 在 Fcitx 启停时绑定和解绑插件服务；
- 通过共享 AIDL 让插件向主程序注册特定能力；
- 通过签名权限限制服务访问；
- 在插件卸载并重新加载 Fcitx 后移除对应数据和输入法条目。

这意味着插件发现、APK 形态、资源加载和输入法列表机制可以复用，不需要另造插件管理系统。

## 四、缺失能力

本项目属于计划中的“情况 B”。现有接口不足以完成手写输入，缺少：

1. 通用手写识别 Provider 协议和明确的协议版本。
2. 笔画、候选、模型状态和错误的跨进程数据结构。
3. 异步识别回调及 `requestId` 过期结果过滤。
4. Provider 注册表、Binder death 处理和连接状态通知。
5. 主程序内的手写画布、控制栏和生命周期控制器。
6. 按输入法条目自动切换 `KeyboardWindow` 与 `HandwritingWindow` 的路由。
7. 与插件候选兼容的候选展示/点击接口。
8. 主程序侧安全获取少量 `preContext` 的接口。
9. 供手写面板复用的退格、空格和回车语义接口。
10. 面向 Fcitx17 包名的插件构建配置。

最后一项是当前分支特有的实际阻塞：`AndroidPluginAppConventionPlugin` 仍把插件的目标主程序写死为官方包名 `org.fcitx.fcitx5.android`，但本项目 Release 包名是 `org.fcitx.fcitx17.android`。静态插件仍可通过兼容 Intent 被发现；需要签名权限的服务插件却不能依靠该默认配置连接 Fcitx17。

## 五、推荐实现路径

### 1. 总体判断

采用“主程序通用 UI + 插件识别 Provider”，ML Kit 只进入插件 APK：

```text
Fcitx17 主程序
├─ 通用 androidhandwriting Fcitx engine
├─ HandwritingWindow / HandwritingCanvas
├─ HandwritingController
├─ HandwritingProviderRegistry
├─ 通用 AIDL DTO 和回调
└─ InputConnection 提交与编辑动作

handwriting-mlkit 插件
├─ 四个 inputmethod .conf
├─ MainService
├─ IHandwritingRecognitionProvider 实现
├─ ML Kit backend（阶段 2 起）
└─ 模型设置 Activity（阶段 2 起）
```

### 2. 输入法条目

主程序新增一个不含 ML Kit 逻辑的通用 Fcitx engine：`androidhandwriting`。主 APK 安装该 engine 的 addon 配置和原生库，但不内置任何手写输入法条目。

插件 APK 通过数据资源提供：

```text
usr/share/fcitx5/inputmethod/handwriting-zh-cn.conf
usr/share/fcitx5/inputmethod/handwriting-en.conf
usr/share/fcitx5/inputmethod/handwriting-ja.conf
usr/share/fcitx5/inputmethod/handwriting-auto.conf
```

这些配置共同引用 `Addon=androidhandwriting`。这样可以保证：

- 未安装插件时，主程序没有手写输入法条目；
- 安装并重新加载后，条目进入现有 Fcitx 列表；
- 四个条目可以独立启用、禁用和排序；
- 卸载并重新加载后，条目自然消失；
- 主程序中的 engine 保持后端无关。

阶段 1 只先提供 `handwriting-zh-cn` 一个条目和固定候选，后续阶段再增加其余三个，避免重复实现。

### 3. 服务协议

沿用 `clipboard-filter` 的反向注册方式：

1. `hasService=true` 启动插件服务；
2. 插件绑定 `FcitxRemoteService`；
3. 插件调用 `registerHandwritingRecognitionProvider()`；
4. 主程序保存 Provider，并监听 Binder death；
5. `HandwritingController` 只面向通用 Provider；
6. 插件停止时注销 Provider。

新增协议至少包含：

```text
IHandwritingRecognitionProvider
IHandwritingRecognitionCallback
HandwritingRecognitionRequest
HandwritingRecognitionResponse
HandwritingCandidate
InkStroke / InkPoint
```

Provider 必须返回独立的手写协议版本，例如 `1`。插件清单 API `0.1` 与手写 Provider API 是两个不同层级，不应混用。

识别使用异步回调；主程序持有最新 `requestId`，任何旧回调都直接丢弃。插件不得获得 `InputConnection`，只能接收笔迹和最小化的前文，并返回候选。

### 4. 手写面板

新增 `HandwritingWindow` 作为 `InputWindowManager` 的 essential window：

- `InputView` 收到 `IMChangeEvent` 后，依据 `uniqueName` 路由：
  - `handwriting-*` → `HandwritingWindow`
  - 其他输入法 → `KeyboardWindow`
- `startInput()` 不能再无条件把窗口切回 `KeyboardWindow`，必须经过统一路由。
- `HandwritingWindow` 实现 `InputBroadcastReceiver`，在输入法切换、输入开始和窗口卸载时清理任务。
- 浮动键盘仍由外层 `InputView` 和 `FloatingKeyboardController` 管理，手写窗口只适配可用尺寸与内容缩放。

阶段 1 的候选条可复用现有 `CandidateItemUi` 和 `HorizontalCandidateViewAdapter` 的视觉实现，但点击回调由 `HandwritingController` 处理。现有 `HorizontalCandidateComponent` 与 Fcitx `select(index)`、候选动作和展开窗口耦合，不能原样用于 Binder 候选。

后续如需完全共用展开候选，可把候选展示层抽成“数据 + 点击回调”的通用组件；第一阶段不应为此重构整个候选系统。

### 5. 提交与编辑

- 候选点击：主程序调用现有 `FcitxInputMethodService.commitText()`。
- 空格：主程序提交普通空格。
- 退格与回车：从 `FcitxInputMethodService` 抽取或增加内部语义方法，复用现有编辑器兼容逻辑。
- `preContext`：主程序在识别前通过当前 `InputConnection` 获取有限长度的光标前文本，再传给 Provider。
- 插件不直接提交文字、不读取完整编辑器内容，也不记录笔迹或前文。

## 六、需要修改的文件

### 阶段 1：最小通信原型

需要修改：

- `settings.gradle.kts`
  - 注册 `:plugin:handwriting-mlkit`。
- `build-logic/convention/src/main/kotlin/AndroidPluginAppConventionPlugin.kt`
  - 让服务插件可以明确以 `org.fcitx.fcitx17.android` / `.debug` 为目标，而不是写死官方包名。
- `lib/common/build.gradle.kts`
  - 为通用 Parcelable/AIDL 数据启用所需构建功能。
- `lib/common/src/main/aidl/org/fcitx/fcitx5/android/common/ipc/IFcitxRemoteService.aidl`
  - 增加 Provider 注册和注销方法。
- `app/src/main/java/org/fcitx/fcitx5/android/FcitxRemoteService.kt`
  - 接收 Provider 注册、处理重复注册和 Binder death。
- `app/src/main/cpp/CMakeLists.txt`
  - 加入通用 `androidhandwriting` addon。
- `app/build.gradle.kts`
  - 把 `androidhandwriting` 加入原生构建目标。
- `app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt`
  - 注册手写窗口并按输入法路由。
- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
  - 提供主程序内部的手写提交、退格、回车和有限前文接口。

建议新增：

```text
lib/common/src/main/aidl/org/fcitx/fcitx5/android/common/ipc/
├─ IHandwritingRecognitionProvider.aidl
└─ IHandwritingRecognitionCallback.aidl

lib/common/src/main/java/org/fcitx/fcitx5/android/common/handwriting/
├─ HandwritingProtocol.kt
├─ HandwritingMode.kt
├─ InkPoint.kt
├─ InkStroke.kt
├─ RecognitionRequest.kt
├─ RecognitionCandidate.kt
└─ RecognitionResponse.kt

app/src/main/cpp/androidhandwriting/
├─ CMakeLists.txt
├─ androidhandwriting.conf.in.in
├─ androidhandwriting.h
└─ androidhandwriting.cpp

app/src/main/java/org/fcitx/fcitx5/android/input/handwriting/
├─ HandwritingProviderRegistry.kt
├─ HandwritingWindow.kt
├─ HandwritingCanvas.kt
├─ HandwritingController.kt
└─ HandwritingCandidateAdapter.kt

plugin/handwriting-mlkit/
├─ build.gradle.kts
├─ proguard-rules.pro
└─ src/main/
   ├─ AndroidManifest.xml
   ├─ java/org/fcitx/fcitx5/android/plugin/handwriting/mlkit/
   │  └─ MainService.kt
   ├─ res/xml/plugin.xml
   ├─ res/xml/plugin_resources_keep.xml
   ├─ res/values/strings.xml
   └─ assets/usr/share/fcitx5/inputmethod/
      └─ handwriting-zh-cn.conf
```

阶段 1 的 `MainService` 只返回固定候选，不添加 ML Kit 依赖。

### 阶段 2 起

到中文 MVP 再修改：

- `gradle/libs.versions.toml`
  - 添加 Digital Ink 依赖坐标。
- `plugin/handwriting-mlkit/build.gradle.kts`
  - 仅插件模块依赖 ML Kit。
- 插件内新增 recognition、download、ranking 和 settings 代码。
- 插件内新增模型设置 Activity 与隐私说明。

主程序 `app` 模块不得出现 `com.google.mlkit` 依赖或 ML Kit 类型。

## 七、第一阶段具体编码任务

建议拆成可独立验证的小步骤：

1. 修正插件目标主程序 ID 的构建配置，确保同签名测试插件能绑定 Fcitx17。
2. 在 `lib/common` 定义手写协议版本、最小 Parcelable DTO、Provider 和异步 Callback。
3. 扩展 `IFcitxRemoteService`，增加 Provider 注册/注销，并在主程序建立线程安全注册表。
4. 新增通用 `androidhandwriting` Fcitx engine；插件只提供一个 `handwriting-zh-cn.conf` 测试条目。
5. 新增 `HandwritingWindow` 的最小占位界面，先显示连接状态和一个测试按钮，不采集真实笔迹。
6. 在 `InputView` 中实现 `handwriting-*` 与普通键盘窗口的双向切换。
7. 插件 `MainService` 注册固定候选 Provider，例如返回“测试”“手写”。
8. 主程序显示固定候选；点击候选由主程序调用 `commitText()`，随后清空候选。
9. 验证插件未安装、未连接、进程被杀、重新连接和卸载后的安全行为。
10. 在 Android 16 模拟器上验证：
    - 插件被发现；
    - 测试输入法可以启用和排序；
    - 切换后显示占位手写面板；
    - 固定候选可以提交；
    - 切回普通输入法恢复 `KeyboardWindow`；
    - 横竖屏和浮动模式不崩溃。

阶段 1 完成并稳定后，才进入 ML Kit 中文模型下载与真实笔迹识别。

## 八、风险和待验证事项

1. **签名和包名**
   - 当前服务插件默认指向官方包名，必须先解决 Fcitx17 目标 ID。
   - 手写插件与 Fcitx17 必须使用同一签名；否则 signature 权限会阻止双向绑定。
   - 若未来要同时支持官方 Fcitx5 Android，需要官方签名、官方认可的新权限方案，或设计不依赖 signature 权限的安全授权协议。

2. **插件信任边界**
   - 当前静态插件发现没有做签名白名单；API 校验只是 `plugin.xml` 中的版本字符串。
   - 数据层会阻止路径冲突，但不会证明 APK 来源可信。
   - 手写 Provider 必须依赖 signature 权限，并校验协议版本，不能因为插件被发现就信任其 Binder。

3. **Binder 数据大小**
   - AIDL 单次事务有大小限制。长时间书写和大量 historical points 不能无限制装入 Parcelable 列表。
   - 阶段 2 需要设置最大笔画/点数、使用紧凑数据结构，并在不破坏笔迹的前提下考虑采样。

4. **候选组件耦合**
   - 现有横向和展开候选都以 Fcitx 原生索引为核心。
   - 阶段 1 先复用候选 Item 和主题，不立即重构分页候选；否则原型范围会过大。

5. **窗口路由**
   - `InputView.startInput()` 当前会把窗口切回 `KeyboardWindow`。
   - 旋转、主题变化和输入视图重建后，也必须根据当前 `InputMethodEntry` 恢复手写窗口，而不是依赖上一次 View 状态。

6. **服务连接竞态**
   - Fcitx 输入法条目可能已经可选，但 Provider 仍在连接。
   - 面板需要明确显示“正在连接”“插件不可用”“版本不兼容”，并监听注册表变化。

7. **进程死亡**
   - Provider 和 Callback 都要处理 Binder death、`RemoteException` 和重复注册。
   - 插件死亡不能让 IME 崩溃；当前请求应失效，笔迹可以保留或按明确策略清空。

8. **模型与 Direct Boot**
   - 现有插件服务可声明 `directBootAware`，但 ML Kit 模型及插件的 credential-protected 数据在解锁前是否可用需要实机验证。
   - 第一版可以在未解锁或模型不可用时显示明确状态，不应静默失败。

9. **自动模式性能**
   - 三模型并行的延迟、内存和功耗必须在真实设备测量。
   - 在阶段 4 前不承诺固定时延，也不提前把自动路由逻辑写进主程序。

10. **当前加载器细节**
    - `pluginSchema.xsd` 不是运行时校验器。
    - `PluginDescriptor` 文档要求包名前缀，但 `DataManager.detectPlugins()` 当前没有显式执行该前缀校验。
    - `PluginLoadFailed` 定义了缺少/损坏数据描述符的错误类型，但当前同步路径在读取失败时主要记录日志并跳过；后续插件错误体验可能需要单独改善，但不应与阶段 1 原型混在同一提交。

## 结论

现有插件系统可以复用 APK 发现、数据装载、输入法 `.conf` 注册和服务拉起能力，但不能独立提供手写 UI，也没有手写识别协议。

最小且可扩展的方案是：

```text
主程序增加后端无关的手写窗口、通用 Fcitx engine 和 AIDL 注册表；
插件提供输入法配置、识别 Provider、模型管理和 ML Kit；
候选提交始终由主程序完成；
ML Kit 不进入主 APK。
```

这条路径满足四个输入法独立管理、插件卸载后条目消失、未来替换其他识别后端以及主程序无插件时正常工作的要求。
