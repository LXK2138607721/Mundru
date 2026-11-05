# 在仓库外保管签名材料

正式构建仅生成未签名 APK，仓库不保存密钥库或口令。密钥库和口令不属于开源代码；APK 中的公开证书及其指纹可以公开。

## 本机签名

将正式密钥库保存在仓库外，默认路径为 `%LOCALAPPDATA%\Mundru\Signing\release.jks`。在项目根目录运行：

```powershell
.\gradlew.bat :app:assembleRelease
.\scripts\Sign-MundruRelease.ps1 -SdkRoot 'D:\Android\Sdk'
```

仅在 Android 签名工具提示时输入口令。签好的 APK 输出到同一私有目录，脚本核对其签名是否与已发布的 Mundru 一致，不会自动上传或安装。密钥存放在其他私有目录时，可传入 `-KeystorePath`。

## 历史清理的作用与边界

v1.4.0 沿用原正式签名，以支持直接覆盖旧版。此次从源码历史移除密钥文件和明文签名配置，并保留既有 Release 页面和 APK。公开证书不是私钥，不需从 APK 移除。

重写 Git 历史不能撤回别人已取得的副本，也不能证明旧私钥未被复制。GitHub 的旧提交缓存需由仓库所有者联系支持处理，见 [GitHub 敏感信息清理说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。安装包应从官方 Release 获取。
