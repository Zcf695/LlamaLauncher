# Llama 启动器 - Android APP

一键启动 llama.cpp 模型服务的 Android 应用，无需打开 Termux 敲命令。

## 功能特性

- **一键启动/停止** llama-server 服务
- **图形化参数配置**：线程数、上下文窗口、最大输出、端口
- **模型自动扫描**：自动查找 Download 等目录中的 GGUF 文件
- **唤醒锁**：防止手机休眠降频
- **内存监控**：实时显示模型内存占用
- **前台服务**：服务在后台持续运行，带通知栏状态
- **内置聊天**：直接在 APP 内与模型对话
- **局域网开放**：电脑可通过 IP 连接手机模型
- **mlock 内存锁定**：充分利用手机内存，防止模型被换出

## 项目结构

```
LlamaLauncher/
├── app/
│   ├── build.gradle.kts          # APP 级 Gradle 配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/llama/launcher/
│       │   ├── MainActivity.kt       # 主界面：参数配置、启动/停止
│       │   ├── ChatActivity.kt       # 聊天界面
│       │   ├── ChatAdapter.kt        # 聊天消息适配器
│       │   ├── LlamaServerService.kt # 前台服务：进程管理、唤醒锁、监控
│       │   ├── ModelScanner.kt       # 模型扫描器
│       │   └── SettingsManager.kt    # 配置持久化
│       ├── res/
│       │   ├── layout/               # 界面布局
│       │   ├── values/               # 字符串、颜色、主题
│       │   └── drawable/             # 图标、气泡
│       └── jniLibs/arm64-v8a/        # llama-server 二进制（需自行编译放入）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

## 编译步骤

### 前置要求

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34
- Android NDK r26+（用于编译 llama.cpp）

### 第一步：编译 llama.cpp for Android

在 Linux 或 WSL 中执行（Windows 原生编译 NDK 较麻烦，推荐 WSL/Ubuntu）：

```bash
# 1. 安装 NDK（如果还没有）
# 下载 https://developer.android.com/ndk/downloads
# 解压到 ~/android-ndk

# 2. 设置环境变量
export NDK_PATH=~/android-ndk
export ANDROID_NDK_HOME=$NDK_PATH

# 3. 克隆 llama.cpp
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp

# 4. 交叉编译 llama-server for arm64-v8a
mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK_PATH/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_SERVER=ON

cmake --build . --config Release -j$(nproc)

# 5. 编译产物在 bin/ 目录下
ls bin/llama-server
```

**编译成功后**，将 `llama-server` 二进制文件重命名为 `libllama_server.so`（Android 要求 .so 后缀才能从 nativeLibraryDir 加载），放入：

```
app/src/main/jniLibs/arm64-v8a/libllama_server.so
```

> **为什么命名为 libllama_server.so？**
> Android 的 `applicationInfo.nativeLibraryDir` 只会加载 `lib*.so` 格式的文件。
> APP 运行时通过 `File(nativeLibraryDir, "libllama_server.so")` 获取路径并执行。

### 第二步：用 Android Studio 编译 APK

1. 打开 Android Studio
2. `File → Open` 选择 `LlamaLauncher` 项目目录
3. 等待 Gradle 同步完成
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
5. APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 第三步：安装到手机

```bash
# 通过 adb 安装
adb install app/build/outputs/apk/debug/app-debug.apk
```

或直接将 APK 传到手机安装。

## 使用方法

### 1. 准备模型文件

将 GGUF 模型文件放入手机的 **Download** 文件夹，例如：
```
/sdcard/Download/Qwen3.5-2B-Instruct.Q4_K_M.gguf
```

### 2. 启动 APP

- 打开「Llama 启动器」
- 点击「扫描模型」，选择要加载的模型
- 调整参数（线程数建议 4-6，上下文根据手机内存选择）
- 点击「启动服务」

### 3. 电脑连接（可选）

APP 启动后会显示手机 IP 和 API 地址，例如：
```
IP: 192.168.1.100
API: http://192.168.1.100:8080/v1
```

在电脑的 local-ai-ide 或其他 OpenAI 兼容客户端中配置：
- API Base: `http://192.168.1.100:8080/v1`
- Model: 任意（llama-server 忽略此字段）
- API Key: 任意

### 4. 内置聊天

点击「打开聊天」直接在手机上与模型对话。

## 参数说明

| 参数 | 说明 | 推荐值（骁龙8+ Gen1） |
|------|------|----------------------|
| 线程数 | CPU 推理线程 | 4-6 |
| 上下文窗口 | 对话历史容量 | 8192（12GB内存可试32768） |
| 最大输出 | 单次回复最大 token | 512-1024 |
| 端口 | API 服务端口 | 8080 |
| Jinja 模板 | 启用模型内置对话模板 | **必须开启** |
| 滚动 KV 缓存 | 上下文满时自动淘汰旧缓存 | 长对话建议开启 |
| mlock | 锁定模型内存，防止被换出 | **建议开启** |
| 唤醒锁 | 防止手机休眠 | **建议开启** |

## 内存优化建议

iQOO Neo8（骁龙8+ Gen1，12GB RAM）：

| 模型 | 量化 | 模型大小 | 推荐上下文 | 预计内存占用 |
|------|------|----------|------------|-------------|
| Qwen3.5-2B | Q4_K_M | ~1.5GB | 32768 | ~3-4GB |
| Qwen2.5-7B | Q4_K_M | ~4.7GB | 8192 | ~6-7GB |
| Qwen2.5-7B | Q4_K_M | ~4.7GB | 4096 | ~5-6GB |

**充分利用内存的技巧**：
1. 开启 `mlock` 锁定模型内存
2. 关闭其他后台应用
3. 手机设置中允许 APP 后台运行
4. 开启唤醒锁防止系统降频
5. 7B 模型建议上下文不超过 8192

## 常见问题

### Q: 启动后服务立即退出
A: 检查模型文件路径是否正确，二进制文件是否为 arm64-v8a 架构。

### Q: 电脑连不上手机 API
A: 确保手机和电脑在同一 WiFi，检查手机防火墙设置，确认端口未被占用。

### Q: 模型加载很慢
A: 首次加载需要将模型读入内存，后续启动会利用 mlock 缓存。确保模型在 UFS 存储中，不在 SD 卡。

### Q: 聊天返回乱码
A: 必须开启 `--jinja` 参数，否则对话模板不正确。

### Q: 如何更新 llama-server 版本
A: 重新编译新版本的 `libllama_server.so`，替换 `jniLibs/arm64-v8a/` 中的文件，重新编译 APK。

## 技术细节

### 为什么用前台服务？
Android 后台进程容易被系统杀死。前台服务带常驻通知，优先级更高，能保证模型服务持续运行。

### 为什么用 mlock？
Android 的 lowmemorykiller 会在内存紧张时杀进程。mlock 将模型内存锁定在 RAM 中，降低被回收的概率。

### 唤醒锁的作用
手机屏幕关闭后，CPU 会进入休眠降频。唤醒锁保持 CPU 全速运行，保证推理速度。

## License

MIT
