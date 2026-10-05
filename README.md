# 拼豆图纸

纯离线的安卓拼豆图纸生成器。导入照片，一键生成拼豆图纸，手动精修后按行拼装，导出打印版与采购清单。

## 功能

**图纸生成**

- 主导色降采样（避免最近邻采样的噪点）
- K-means 限色（控制颜色数）
- CIEDE2000 色差映射（比 RGB 欧氏距离更接近人眼感知）
- 孤立像素清理
- 背景移除（四边洪水填充 + CIEDE2000 容差）
- Floyd–Steinberg 抖动（可选）

**色卡**

内置 9 套色卡 2000+ 色：MARD（马牌）291 色、COCO 291、漫漫 290、盼盼 291、咪小窝 291、Perler 103、Hama 89、Artkal S 系列 176（5mm）、Artkal Mini 系列 221（2.6mm）。色值以 JSON 资产内置，可自行扩展。

**编辑器**

双指缩放平移画布（1~20 倍），画笔、橡皮、吸管三种工具，单格锁定防止误改，100 步撤销重做，色号与网格线开关。色号画在格子下沿窄标签内，不遮挡颜色。

**施工模式**

拼豆时的引导界面：按行或按列逐行高亮，当前行放大提示所需色号与数量，已完成格打勾，点击格子修正。进度持久化到本地数据库，关掉 App 再打开接着拼。

**导出**

- 带色号 PNG（每格标注色号，自动分块处理大图）
- 黑白符号版 PNG（黑白打印机友好，含符号对照表）
- A4 分页 PDF（图纸分页 + 采购清单页）
- 采购清单文本（按用量降序，附 500/1000 粒装购买建议）

全部保存到系统「下载/拼豆图纸」目录。

**项目库**

Room 数据库持久化，项目卡片显示缩略图与尺寸色卡信息，支持重命名与删除，打开后可继续编辑。

**其他**

空白画布自由创作、文字拼豆（输入文字自动生成像素图案）、中英双语色号标注、深色模式。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 34）。首次构建在项目根目录创建 `local.properties` 写入 `sdk.dir=你的SDK路径`。

```
./gradlew assembleDebug          # 调试包
./gradlew :app:testDebugUnitTest # 运行 62 个单元测试
./gradlew assembleRelease        # 正式签名包（需自备签名密钥）
```

发布签名：在根目录创建 `keystore.properties`（已加入 .gitignore）：

```
storeFile=keystore/release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

## 技术栈

Kotlin · Jetpack Compose · Material 3 · Room · DataStore · Hilt（未用） · kotlinx-serialization

视觉遵循 [clay 设计体系](DESIGN.md)（奶油画布 + 多巴胺彩色 + 圆润），已 token 化。

## 架构

```
core/
├── color/       CIEDE2000 色差、sRGB↔Lab 转换
├── palette/     色卡加载、最近色查找、排除色号
├── pattern/     生成管线（降采样→限色→映射→清理→抖动→背景移除）
├── editor/      编辑引擎（格数据、锁定、撤销栈）
├── build/       施工模式状态（行/列引导、进度追踪）
├── export/      位图渲染（内嵌 5×7 点阵字体）、清单生成、MediaStore/PDF
└── data/        Room 数据库、DataStore 设置
ui/              Compose 界面（clay 主题 token）
```

`core` 层全部为纯 Kotlin，可脱离 Android 单测。

## 数据来源

- MARD/COCO/漫漫/盼盼/咪小窝 色值：[Zippland/perler-beads](https://github.com/Zippland/perler-beads)（AGPL-3.0）
- Perler/Hama/Artkal 色值：[xuange6610/PindouAI](https://github.com/xuange6610/PindouAI)（Apache-2.0）

色值为工程近似值，与实体豆可能存在色差，请以实物色卡为准。

## 许可

[AGPL-3.0](LICENSE)
