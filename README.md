# WebDAV 备份应用

一个基于 Jetpack Compose 的 Android WebDAV 备份工具。

## 功能
- 支持 `http` / `https` WebDAV 地址
- 支持多选文件
- 支持手动备份
- 支持自动备份
- 支持压缩备份（ZIP）
- 支持用户名 / 密码认证
- 提供美观的深色界面与图标

## 说明
- 选择文件后会缓存到应用沙箱，用于自动备份任务。
- 自动备份通过 `WorkManager` 执行。
- 上传使用系统网络接口，不依赖额外第三方库。

## 云端构建
已配置 GitHub Actions：
- 推送或 PR 时自动执行 `./gradlew :app:assembleDebug`
- 生成的 APK 作为 artifact 上传

工作流文件：`.github/workflows/android.yml`

## 主要文件
- `app/src/main/java/com/java/myapplication/MainActivity.kt`
- `app/src/main/java/com/java/myapplication/BackupEngine.kt`
- `app/src/main/java/com/java/myapplication/AutoBackupWorker.kt`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/values/strings.xml`

## 注意
- 由于当前环境要求“禁止本地构建”，本次仅完成源码与云端构建配置，未在本地执行 Gradle 构建。