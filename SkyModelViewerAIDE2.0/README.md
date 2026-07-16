# Sky Model Viewer (Android 版)

## 📖 项目简介

**Sky Model Viewer** 是一款运行在 **Android 平台** 上的 3D 模型查看工具。它是原 Windows 桌面版（基于 WPF/.NET 9）的社区移植版本，专为移动设备优化。

本应用旨在为《光·遇》的爱好者与内容创作者提供一个便捷的移动端解决方案，用于**安全、只读地**浏览本地游戏网格文件，并支持多格式导出。

**⚠️ 重要提示**：本项目是一个**查看器（Viewer）**，**不是**提取器（Extractor）或编辑器（Editor）。它不包含任何游戏代码、资源文件，也无法导出或修改游戏资产。

## 📸 功能预览



## ✨ 主要功能

- **本地文件浏览**：扫描并索引你设备上合法安装的《光·遇》本地文件目录（或解压的资产树）。
- **3D 模型预览**：在简洁的移动端 3D 视口中，预览受支持的静态网格模型。
- **元数据查看**：便捷地查看模型顶点数、面数、UV 数据、骨架数量、材质名称及推断的比例尺等信息。
- **材质与纹理解析**：解析常见的材质定义，并支持显示特定格式（如 BC4/BC7 KTX）的纹理。
- **移动端适配**：针对 Android 触摸操作进行了界面与交互优化。
- **全版本解析**：支持所有版本的 `.mesh`（模型）和 `.meshes`（地形）文件解析。
- **多格式导出**：
  - 模型：OBJ / GLB / FBX
  - 贴图：PNG / JPEG
  - 地图：完整关卡导出（地形 + mesh 实例，含位置变换）→ OBJ / GLB / FBX

## 📦 支持的文件格式与版本

### .mesh 模型文件

支持 **0x17 - 0x20** 共 10 个版本：

| 版本 | 压缩方式 | 顶点偏移 | 索引类型 | UV 格式 | 说明 |
|------|---------|---------|---------|---------|------|
| 0x17 | 无压缩 | 0x9D | u32 | half-float@0 | 最早期版本 |
| 0x18 | 无压缩 | 0x9D | u32 | half-float@0 | 最早期版本 |
| 0x19 | 无压缩 | 0x92 | u32 | half-float@0 | 早期版本 |
| 0x1A | 无压缩 | 0x92 | u32 | half-float@0 | 早期版本 |
| 0x1B | 无压缩 | 0x92 | u32 | half-float@0 | 早期版本 |
| 0x1C | LZ4@0x56 | 0x60 | u32 | half-float@0 | 中期版本 |
| 0x1D | LZ4@0x56 | 0x60 | u32 | half-float@0 | 中期版本 |
| 0x1E | LZ4@0x56 | 0xB3 | u16 | half-float@4 | 过渡版本 |
| 0x1F | LZ4 | 动态 | u16 | half-float | 现代版本，含骨骼权重 |
| 0x20 | LZ4 | 动态 | u16 | half-float | 最新版本，含骨骼权重 |

### .meshes 地形文件

支持 **0x34 - 0x3D** 共 10 个版本，以 0x39 (v57) 为分界线：

| 版本范围 | 解析路径 | 顶点来源 | 索引类型 |
|---------|---------|---------|---------|
| 0x34-0x38 | LOD0 → LZ4 解压 → Terrain.bin | 原始 36 字节顶点 | u16 |
| 0x39-0x3D | GEO0 独立 TOC 段 | meshopt 压缩顶点 | u8 局部索引 |

### 36 字节顶点结构

| 偏移 | 大小 | 字段 | 类型 |
|------|------|------|------|
| 0 | 12 | position | 3×f32 |
| 12 | 3 | normal | 3×snorm (byte/127) |
| 15 | 1 | (padding) | u8 |
| 16 | 4 | material_ids | 4×u8 |
| 20 | 4 | material_weights | 4×u8 |
| 24 | 4 | v_color | 4×u8 |
| 28 | 4 | gi_light | 4×u8 |
| 32 | 4 | meta_id | u32 |

## 📤 导出功能

### 模型导出

| 格式 | 内容 | 说明 |
|------|------|------|
| OBJ | v / vn / vt / f | 含顶点、法线、UV、面，1-based 索引 |
| GLB | glTF 2.0 二进制 | 含位置、法线、UV、索引，Y轴翻转 |
| FBX | ASCII FBX 7.4 | 兼容 Blender/Maya/3ds Max |

### 贴图导出

| 格式 | 说明 |
|------|------|
| PNG | 无损压缩，保留 Alpha 通道 |
| JPEG | 有损压缩（质量 95），Alpha 通道自动合成黑色背景 |

### 地图导出（完整关卡）

导出内容包含**两部分**合并为一个文件：

1. **地形数据** — 所有 .meshes terrain blob 的顶点和索引
2. **mesh 实例** — 关卡中放置的所有模型，每个实例的顶点位置应用 4x4 变换矩阵到世界空间坐标

| 格式 | 说明 |
|------|------|
| OBJ (完整) | 按 `g terrain_0`、`g mesh_0_Name` 分组，含变换后的顶点/法线/UV |
| GLB (完整) | 地形 + mesh 合并为一个 glTF mesh |
| FBX (完整) | 地形 + mesh 合并为一个 FBX geometry |

**变换处理**：
- 顶点位置：完整 4x4 变换（旋转 + 平移）→ 世界空间
- 法线：仅旋转部分，变换后重新归一化
- UV：直接保留

## 🛠️ 技术实现

### meshopt 顶点解码

纯 Java 移植自 [meshoptimizer](https://github.com/zeux/meshoptimizer)，支持：
- **Version 0**：无控制字节，所有通道为 u8 zigzag delta，bit-packed 编码
- **Version 1**：有控制字节和通道配置，支持 u8/u16/u32 delta 编码

### LZ4 块解压

两套实现：
- `.meshes` 文件：纯 Java LZ4 块解码器
- `.mesh` 文件：使用 lz4-java 库

### 材质颜色

80+ 种游戏材质映射到 RGB 颜色，顶点颜色通过 4 个材质 ID 和权重的加权混合计算。

## 🚧 项目状态与局限性

- **状态**：此为社区维护的移植版本，处于持续开发中。
- **格式支持**：对网格、材质、着色器、纹理的解析**尚不完善**，基于对部分本地文件格式的逆向工程实现。
- **平台限制**：目前仅支持 **Android** 平台。原 Windows 版本需要 .NET 9 SDK 构建。
- **不包含任何游戏文件**：你必须自行提供合法的本地游戏安装路径。

## ⚖️ 法律声明

- **独立性**：本项目和其贡献者**独立于 thatgamecompany**，与该公司及其产品**没有隶属、背书或赞助关系**。
- **知识产权**：《光·遇》（*Sky: Children of the Light*）及相关名称、商标、游戏代码和游戏资产，均归其各自所有者（thatgamecompany）所有。
- **使用限制**：本仓库**不包含** thatgamecompany 的任何游戏代码、二进制文件、模型、纹理、音频、关卡或其他游戏资产。工具仅读取用户设备上已有的文件，供应用内本地查看。
- **用户责任**：**请仅将本项目用于你拥有合法访问权限的文件。**本自述文件不构成法律建议。

## 📥 下载与安装

你可以在右侧的 [**Releases**](https://github.com/XianXiaoWei/SkyModelViewer-Android/releases) 页面下载最新的 APK 安装包。

## 🛠️ 构建与开发

如果你希望从源代码构建此项目：

1. **克隆仓库**
```
git clone https://github.com/XianXiaoWei/SkyModelViewer-Android.git
```

2. **使用 Android Studio** 打开项目。

3. 同步 Gradle 并构建 APK。

**构建环境**：
- Android Studio + Gradle 7.4.2
- compileSdk 33, minSdk 24, targetSdk 33
- Java 7 兼容
- 依赖：lz4-java-1.8.0

## 📁 项目结构

```
app/src/main/java/com/sky/modelviewer/
├── MainActivity.java          # 主界面，APK 浏览和模型加载
├── parsing/
│   ├── TgcMeshReader.java     # .mesh 模型解析（0x17-0x20）
│   ├── LevelMeshesReader.java # .meshes 地形解析（0x34-0x3D）
│   ├── MeshoptDecoder.java    # meshopt 顶点解码器
│   ├── LZ4BlockDecoder.java   # 纯 Java LZ4 块解压
│   └── LevelFileParser.java   # Objects.level.bin 解析
├── render/
│   ├── MeshRenderer.java      # OpenGL ES 渲染
│   └── KtxTextureLoader.java  # KTX 纹理加载
├── scanner/
│   └── SkyAssetScanner.java   # APK 资源扫描
├── export/
│   ├── ObjExporter.java       # OBJ 格式导出（模型/地形/完整关卡）
│   ├── GlbExporter.java       # GLB 格式导出（模型/地形/完整关卡）
│   ├── FbxExporter.java       # FBX 格式导出（模型/地形/完整关卡）
│   └── TextureExporter.java   # 贴图导出（PNG/JPEG）
└── model/
    ├── MeshData.java          # 模型数据结构
    ├── BoneWeight.java        # 骨骼权重
    └── SkeletonBone.java      # 骨骼
```

## 🙏 参考项目

- [SkyModelViewer](https://github.com/kfhammond/SkyModelViewer) — 原 Windows 桌面版
- [that-sky-level](https://github.com/that-sky-project/that-sky-level) — .meshes 格式参考
- [that-sky-level-meshes](https://github.com/that-sky-project/that-sky-level-meshes) — meshopt 解码参考
- [Sky-mesh-examine](https://github.com/fishl520/Sky-mesh-examine) — 全版本 .mesh 解析参考

## 📋 更新历史

- **v1.2.0** — 多格式导出（OBJ/GLB/FBX/PNG/JPEG）+ 完整关卡导出
- **v1.1.0** — 全版本 .mesh/.meshes 解析支持（0x17-0x20, 0x34-0x3D）
- **v1.0.0** — 初始版本，基础 APK 浏览与 3D 模型预览

## 🤝 贡献与反馈

由于是个人维护的移植项目，目前可能有很多不完善的地方。欢迎通过 [**Issues**](https://github.com/XianXiaoWei/SkyModelViewer-Android/issues) 页面提出建议或报告问题。

## 📧 联系方式

- **作者**：XianXiaoWei
- **邮箱**：[3661380498@qq.com](mailto:3661380498@qq.com)

## 📜 许可证

本项目为个人学习与研究目的开源，具体许可协议请参见项目根目录的 `LICENSE` 文件（若有）。若无明确声明，默认保留所有权利。

**最后更新**：2026年7月12日
