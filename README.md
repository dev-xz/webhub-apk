# WebHub

把多个网页应用变成安卓上的独立"子应用"的管理端。一次安装，即可为任意网页创建带独立图标、独立数据、独立通知渠道的桌面级应用入口。

## 功能

- **子应用管理**：新增 / 编辑 / 删除子应用（名称、图标、URL），网格列表一屏总览
- **图标三来源**：自动抓取站点 favicon（支持 ICO 容器解析）、手动上传图片、内置预设图标；站点无图标时自动分配内置图标
- **数据隔离**：每个子应用独立的 WebView Profile，cookie / localStorage / IndexedDB / 缓存互不干扰；支持按子应用单独清空数据
- **桌面入口**：为每个子应用固定桌面快捷方式（pinned shortcut），像原生应用一样从桌面启动；支持随时重新固定
- **网页通知**：网页内 `Notification` API 的通知桥接为系统通知，按子应用独立渠道，可在系统设置中按站点静音，点击回到对应子应用
- **访问模式**：每个子应用可选 系统默认 / 移动端 / 平板 / 电脑端 User-Agent
- **固定缩放**：可按子应用设定固定缩放比例（100% = 系统默认渲染比例）
- **沉浸式体验**：全屏浏览、视频全屏、同站链接应用内打开、外链交系统浏览器、可选不校验证书（自签名站点）

> 通知仅在对应子应用的 WebView 存活时投递（WebView 不支持 Service Worker push）；首次启动会引导开启通知权限与电池优化白名单以降低回收概率。

## 使用

1. 安装后打开 WebHub，完成首次引导（通知权限 + 电池白名单）
2. 点右上角 "+" 添加子应用：输入名称和完整网址（如 `https://example.com/`），选择图标来源，按需配置访问模式 / 缩放 / 证书选项
3. 创建后桌面会出现子应用图标；也可从 WebHub 列表进入
4. 长按列表项：编辑 / 固定到桌面 / 清空数据 / 删除

若创建桌面快捷方式没有反应，多为 ROM 默认禁止了"桌面快捷方式"权限：长按子应用 → 固定到桌面，按引导跳转系统设置开启。

## 构建

```bash
# 环境：JDK 17+，Android SDK（android-36）
export ANDROID_HOME=~/Android/Sdk

./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

```bash
# 单元测试
./gradlew testDebugUnitTest
```

## 项目信息

- **包名**: `net.marscore.webhub`
- **最低系统**: Android 8.0 (API 26)
- **目标系统**: Android 16 (API 36)
- **技术栈**: Kotlin + WebView（Views，无 Compose）；Room 持久化；androidx WebKit Profile 数据隔离；ShortcutManager 桌面快捷方式

## 开源协议

本项目基于 [MIT License](LICENSE) 开源，可自由使用、修改、分发（含商用），仅需保留版权声明。
