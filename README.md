# 屏幕翻译 (ScreenTranslate)

一个 Android 悬浮窗屏幕翻译工具，支持对屏幕内容进行实时 OCR 识别并翻译为中文，保持原文位置与字号。内置《边狱巴士》《脑叶公司》《废墟图书馆》（Project Moon）多语言术语库，确保译名统一。

## 功能特性

- **悬浮窗操作**：可拖动悬浮按钮，点击展开三模式菜单
  - 单次翻译：截屏一次，OCR + AI 翻译后覆盖显示
  - 持续翻译：周期性自动截屏翻译，再次点击关闭
  - 设置：打开主界面配置 AI 服务商
- **多语言 OCR**：并行运行拉丁、韩语、日语识别器，按文字脚本智能择优合并
- **倾斜文字对齐**：识别文字旋转角度，覆盖层以相同角度旋转并校正字号
- **术语注入**：自动检测屏幕文本中出现的 Project Moon 术语，注入提示词确保译名统一（如 Yi Sang → 李箱）
- **可配置 AI 服务商**：支持任意 OpenAI 兼容接口，可自定义 API 地址、Key、模型、提示词

## 项目结构

```
ScreenTranslate/
├── app/
│   └── src/main/
│       ├── java/com/screentranslate/
│       │   ├── FloatingWindowService.kt   # 悬浮窗服务与翻译流程编排
│       │   ├── MainActivity.kt            # 权限申请与 AI 配置界面
│       │   ├── OcrManager.kt              # 多语言 OCR（拉丁/韩/日）
│       │   ├── OverlayManager.kt          # 翻译结果覆盖层
│       │   ├── ScreenCaptureManager.kt    # MediaProjection 截屏
│       │   ├── TranslationManager.kt      # OpenAI 兼容翻译客户端
│       │   ├── TermInjector.kt            # Project Moon 术语注入
│       │   ├── model/                     # AppConfig、TextBlock 数据类
│       │   └── util/PrefsManager.kt       # 配置持久化
│       ├── assets/project_moon_terms.json # 术语库（英/韩/日 → 简中）
│       └── res/                           # 布局、字符串、图标
├── build.gradle.kts
└── settings.gradle.kts
```

## 环境要求

- Android 7.0 (API 24) 及以上
- Android Studio Hedgehog (2023.1) 或更高（或 Gradle 8.x + JDK 17）
- ML Kit 文字识别模型（拉丁、中文、韩语、日语）

## 构建与运行

```bash
cd ScreenTranslate
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 首次使用步骤

1. 打开应用，依次申请**悬浮窗权限**与**截屏权限**
2. 在「AI 服务商配置」中填入 API 地址、Key、模型名称，保存
3. 点击「启动悬浮窗」，授权后退到目标应用
4. 点击悬浮按钮选择翻译模式

## 术语库

`app/src/main/assets/project_moon_terms.json` 包含 Project Moon 系列游戏的多语言术语对照表：

```json
{
  "languages": {
    "en": { "Yi Sang": "李箱", "Lobotomy Corporation": "脑叶公司" },
    "kr": { "이상": "李箱" },
    "jp": { "イサン": "李箱" }
  }
}
```

翻译前会扫描屏幕文本，命中的术语会注入到 AI 提示词中。可在设置中关闭此功能。如需扩充术语，直接编辑该 JSON 文件即可。

## 分支策略

- `main`：发布分支，保持稳定
- `trae/agent-cRiMSO`：当前开发分支，包含完整可运行的 ScreenTranslate 项目

开发完成后通过 Pull Request 合并回 `main`。

## 许可证

[Apache License 2.0](./LICENSE)
