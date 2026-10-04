# ClearAll for Trebuchet（LSPosed Modern API 102 模块）

为 **Trebuchet (LineageOS launcher3)** 最近任务底部操作栏添加"清除全部"按钮，
对齐系统桌面 (Oplus/ColorOS) 始终可见的交互习惯。

## 背景

Trebuchet 源自 AOSP，其"清除全部"功能 (`ClearAllButton`) 虽然存在且已启用
(`OverviewState.getVisibleElements()` 返回 24，含 `CLEAR_ALL_BUTTON` flag 16)，
但它是 **滚动到任务列表末尾才出现的按钮**（alpha 随滚动位置渐显），不在底部操作栏里，
不够直观。本模块把"清除全部"按钮添加到底部操作栏 (`OverviewActionsView`)，始终可见。

## 结构
```
app/
├─ src/main/java/top/gtian/clearall/
│  ├─ Main.kt                # 模块入口（XposedModule），分发 hook
│  └─ ClearAllHook.kt        # Hook 实现
├─ src/main/resources/META-INF/xposed/
│  ├─ java_init.list          # 入口类全名
│  ├─ module.prop            # minApiVersion=102
│  └─ scope.list             # 注入目标包
```

## Hook 原理

| Hook 点 | 作用 |
|---|---|
| `RecentsView.init(OverviewActionsView, ...)` | 建立 actionsView → recentsView 弱引用映射，解析 `dismissAllTasks` 方法 |
| `OverviewActionsView.onFinishInflate()` | 在底部操作栏 `action_buttons` 容器追加"清除全部"按钮 |
| 按钮点击 | 反射调用 `RecentsView.dismissAllTasks(View)` 清除所有最近任务 |

**安全措施**：
- `ExceptionMode.PROTECTIVE` 防止 hook 异常崩溃宿主
- `WeakReference` / `WeakHashMap` 避免内存泄漏
- 全链路 `runCatching` 降级，失败不影响宿主正常使用
- `processedViews` 防止重复添加按钮

## 编译

需 JDK 17 + Android SDK (platform 35, build-tools 35) + Gradle 8.9。
项目自带 gradle wrapper。

```powershell
$env:JAVA_HOME = "D:\DevTools\Java\jdk-17"
.\gradlew.bat assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`。

### 签名（zipalign + apksigner）

`minSdk 29` 下 Gradle 自动签名只输出 v3，需手动 zipalign + apksigner 补全：

```powershell
$bt       = "$env:ANDROID_HOME\build-tools\35.0.0"
$apk      = "app\build\outputs\apk\release\app-release.apk"
$aligned  = "app\build\outputs\apk\release\app-release-aligned.apk"

& "$bt\zipalign.exe" -v 4 $apk $aligned
& "$bt\apksigner.bat" sign `
  --ks keystore\hiderecent-release.jks --ks-key-alias hiderecent `
  --ks-pass "pass:<密码>" --key-pass "pass:<密码>" `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --out $apk $aligned
```

> 用 apksigner（而非 jarsigner）保持 ZIP LFH 结构完整，避免 LSPosed 索引服务
> Range 请求读 `module.prop` 时解压失败。

## 安装与启用

1. 安装 APK。
2. LSPosed 管理器 → 启用模块。
3. 作用域已锁定 `com.android.launcher3`（Trebuchet）。
4. 重启桌面。

## 注意

- 现代 API 不需要 `assets/xposed_init`，只写 `java_init.list`。
- 混淆/开 R8：保留 `app/proguard-rules.pro` 中 XposedModule 条目，勿删。
- 排查不生效时看 logcat：
  ```bat
  adb logcat -s ClearAllTrebuchet
  ```
