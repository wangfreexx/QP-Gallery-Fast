# 疾图 SwiftPic

为速度而生的 Android 本地相册：MediaStore 直读缩略图 + 内存 LRU，几万张照片也能流畅浏览。
Kotlin + View 体系，无广告、无联网、无账号。

## 功能

**浏览**
- 首页「全部照片」+ 相册网格，相册封面支持斜向层叠（右上错层）
- 相册/照片各自独立排序：日期正反序、名称正反序、手动排序
- 相册置顶（后置顶的排最前）、上移/下移、排除路径
- 右侧快滑条，2 万张以上的相册拖动即跳，不卡主线程
- 首页下拉刷新，重新扫描相册与照片

**查看器**
- 左右翻页、双指缩放（最高 64×）、双击缩放、放大后仍可点按翻页
- 放大后支持横向拖拽翻页，拖动带 2× 增益与惯性
- 超长截图/全景图按原生长边解码，放大后依然清晰
- 转场可选「滑动」或「淡入淡出」，动画速度全局限速
- 图片详情（EXIF/尺寸/位置）、分享、删除
- 旋转 / 裁剪 / 镜像编辑，另存副本
- 设为壁纸：裁剪确认后应用

**动图与动态照片**
- GIF / 动态 WebP 自动播放，可逐帧前进后退
- 实况照片（MotionPhoto / MicroVideo）：网格角标识别，查看器自动播放、可静音
- Ultra HDR：增益图解码 + HDR 角标（Android 14+）

**个性化**
- 深浅色主题
- 相册背景可跟随主题、使用系统壁纸（免 root）或自选图片
- 照片标签、相册标签：网格右上角 / 相册封面左上角最多显示两个，竖排
- 密码保护、收藏、搜索

## 环境要求

- Android 8.0（API 28）及以上
- 存储权限：`READ_EXTERNAL_STORAGE`
- `targetSdk = 32`：`WallpaperManager` 读取系统壁纸仍然只认旧存储权限模型，
  升到 33+ 后普通应用无法再读取系统壁纸，因此本项目刻意停留在 32。
  代价是无法上架 Google Play，MediaStore 的其它能力不受影响。

## 安装

从 [Releases](https://github.com/wangfreexx/QP-Gallery-Fast/releases) 下载 APK 安装：

```bash
adb install SwiftPic-v1.8.0-release.apk
```

首次启动授予照片读取权限即可。

## 构建

需要 JDK 17：

```bash
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/SwiftPic-v<版本>-release.apk`。

- Gradle 8.4 / AGP 8.2.2 / Kotlin 1.9
- `compileSdk 34`、`minSdk 28`、`targetSdk 32`
- 仓库默认使用本机 debug keystore 签名，正式分发请替换为自己的签名配置

## 项目结构

```
app/src/main/java/com/qoder/swiftpic/
├── data/      MediaStore 仓库、缩略图/图片加载、GIF、HDR、实况探测、用户数据
├── ui/        首页、相册、查看器、搜索、设置、壁纸裁剪、编辑器
│   ├── viewer/ 分页查看、缩放、动图/实况播放
│   └── widget/ 快滑条、方形网格、层叠封面
└── util/      格式化工具
```

## 已知限制

- 删除相册需要系统二次确认（Android 11+），旧系统仅应用内确认
- 系统壁纸读取依赖 `targetSdk 32` 的旧权限模型
- 实况照片识别基于 XMP 标记 `MotionPhoto="1"` / `MicroVideo="1"`

## License

[MIT](LICENSE) © 2026 wangfreexx
