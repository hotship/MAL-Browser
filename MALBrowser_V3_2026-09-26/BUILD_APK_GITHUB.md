# MAL 浏览器自动生成 APK（无需本机安装 Android Studio）

工程已经包含 `.github/workflows/build-apk.yml`。

## 使用方法

1. 新建一个 GitHub 仓库。
2. 把本工程全部文件上传到仓库根目录。
3. 打开仓库顶部的 **Actions**。
4. 选择 **Build Android APK**。
5. 点击 **Run workflow**。
6. 构建成功后，在该次运行页面底部下载：
   `MALBrowser-debug-apk`
7. 解压后得到：
   `MALBrowser-debug.apk`

这是 Debug APK，会由 Android 构建系统自动使用调试签名签名，可以直接安装测试，不需要你准备正式签名证书。

## 后续升级

测试阶段只要保持 `applicationId = com.shixu.minibrowser` 不变即可。
正式长期使用前，建议创建自己的 release keystore；正式版一旦开始使用，就应一直保留同一签名证书，否则无法覆盖升级原 App。
