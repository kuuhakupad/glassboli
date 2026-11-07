# 小米动态照片 · 液态玻璃水印 App (v2.0 静态+动态视频逐帧水印版)

本项目是专为 **小米澎湃 OS (Xiaomi HyperOS)** 打造的动态照片（Live Photo / Motion Photo）底部**液态玻璃胶囊水印**原生 Android 客户端源码工程。

## 本版更新重点 (v2.0)
1. **默认光学参数已锁定为你指定的黄金预设**：
   - 边缘液态透镜折射曲率 (`Lens Refraction`): **20 px**
   - 内部磨砂高斯模糊度 (`Frosted Blur`): **2 px**
   - 高光玻璃立体边缘强度 (`Specular Rim`): **20%**
   - 水印宽度占比 (`Width Ratio`): **98%**
   - 底部距离微调 (`Bottom Offset`): **1.0%**
2. **动态照片长按播放全程带液态玻璃水印 (`VideoFrameWatermarker.kt`)**：
   - 新增基于 `MediaExtractor` + `MediaMetadataRetriever` + `MediaCodec (video/avc H.264)` + `MediaMuxer` 的**硬件视频流逐帧液态玻璃渲染管线**。
   - 不仅给静态封面 JPEG 加上液态玻璃水印，还会对动态照片内嵌的 MP4 视频**每一帧底部实时计算背景折射与磨砂模糊**（并无损复制原视频的 AAC 环境音轨），最后重新计算新视频字节长度写入 `GCamera:MicroVideoOffset` 与 `Container:Directory` XMP 标签。在小米系统相册中长按播放动态照片时，底部液态玻璃水印全程保留且内部背景随画面实时流动折射！
3. **默认展示图已更换为樱花树实拍图 (`res/drawable-nodpi/sample_showcase.jpg`)**：
   - 打开 App 即默认加载樱花树展示图与上述默认参数预览。

---

## 如何获取安装 APK？

### 方法一：GitHub Actions 云端一键自动打包 APK（无需电脑配环境，2 分钟直接下载 `.apk`）
1. 打开 GitHub 新建一个仓库（Public 或 Private 均可）。
2. 将本压缩包解压后的全部文件上传到该仓库。
3. 点击仓库顶部的 **Actions** 选项卡，系统会自动触发 `Build Xiaomi Liquid Watermark APK` 编译任务。
4. 编译完成后，在任务页面底部 **Artifacts** 处点击下载 `XiaomiLiquidWatermark-v2-apk`，解压即可得到编译并签名好的 `.apk` 安装包，发到小米手机直接安装即可。

### 方法二：Android Studio 本地一键编译
1. 使用 Android Studio 打开解压后的工程文件夹。
2. 点击顶部菜单 **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** 即可生成安装包。
