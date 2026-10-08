# 拼豆图纸

把照片变成拼豆图纸的安卓 App。导入一张图，自动生成每个格子对应一颗豆子的图纸，还能手动精修、按行引导拼装、导出打印版和采购清单。

**完全离线**：不联网，照片和图纸只存在你自己的手机里。

## 能做什么

**照片转图纸**

选一张照片，自动生成拼豆图纸。背景太乱可以自动去背景，照片里的小细节（眼睛、高光）会自动保留。也可以先用"内容简化"处理图片，让图案更干净、用色更少。

**裁剪旋转**

先在照片上框选想要的区域再生成：拖动选区、四角缩放、左右旋转、镜像、可锁定网格比例防止图案被拉伸。提供三分线、黄金比例等辅助线帮忙构图。

**自适应尺寸**

不想算网格大小？点一下"自适应"，按照片比例自动设定；还能用倍数滑杆整体放大缩小，想要多大的图纸自己定。

**9 套色卡 2000+ 色**

马牌、COCO、漫漫、盼盼、咪小窝、Perler、Hama、Artkal 大/小豆。选你手头有的牌子，照着图纸买豆就能拼。也可以限制最多用多少种颜色，控制成本。

**手动精修**

画笔、橡皮、吸管三种工具，双指缩放画布放大到每一格。改错了一键撤销，重要区域可以锁定防止误改。

**拼装引导**

按行或按列逐行高亮，告诉你这一行要拼什么颜色、各多少颗。拼完的格子打勾，中途退出 App 也不丢进度，下次接着拼。

**导出**

- 带色号 PNG：每格标花色号，放大能看清
- 符号版 PNG：黑白打印友好，配符号对照表
- A4 分页 PDF：打印出来直接照着拼
- 采购清单：按用量排好，附购买建议，省得算

**其他**

- 空白画布：不导入照片，从零开始自己画
- 文字拼豆：输入文字直接生成文字图案
- 项目库：所有图纸自动保存在手机里，随时打开继续改
- 深色模式

## 下载

到 [Releases](https://github.com/LAmireuxP/pindou/releases) 下载最新 APK，安装到手机即可（Android 8.0 及以上）。

## 自己构建

需要 JDK 17 和 Android SDK（compileSdk 34）。首次构建在项目根目录创建 `local.properties` 写入 `sdk.dir=你的SDK路径`。

```
./gradlew assembleDebug          # 调试包
./gradlew :app:testDebugUnitTest # 运行单元测试
./gradlew assembleRelease        # 正式签名包（需自备签名密钥）
```

发布签名：在根目录创建 `keystore.properties`（已加入 .gitignore）：

```
storeFile=keystore/release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

## 色卡数据来源

- MARD/COCO/漫漫/盼盼/咪小窝 色值：[Zippland/perler-beads](https://github.com/Zippland/perler-beads)（AGPL-3.0）
- Perler/Hama/Artkal 色值：[xuange6610/PindouAI](https://github.com/xuange6610/PindouAI)（Apache-2.0）

色值为工程近似值，与实体豆可能存在色差，请以实物色卡为准。

## 许可

[AGPL-3.0](LICENSE)