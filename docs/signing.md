# 在仓库外保管签名材料

正式构建仅生成未签名 APK，仓库不保存密钥库或口令。密钥库和口令不属于开源代码；APK 中的公开证书及其指纹可以公开。

## 签名 Mundru 1.5.0 及更新版本

使用保存有 `Mundru Android Release v1.5` 证书和私钥的 Windows 账户，在项目根目录运行：

```powershell
.\gradlew.bat :app:assembleRelease
.\scripts\Sign-MundruRelease.ps1 -SdkRoot 'D:\Android\Sdk' -WindowsStore
```

签名工具使用当前 Windows 用户的证书存储，不需要输入或保存签名口令。脚本核对 APK 的签名后，将文件放在 `%LOCALAPPDATA%\Mundru\Signing`，不会自动上传或安装。

**重装系统或换电脑前先备份私钥。** 在 Windows 用户证书管理器（`certmgr.msc`）的「个人 → 证书」中找到 `Mundru Android Release v1.5`，连同私钥导出为加密 PFX，存放到仓库之外的离线备份。更换电脑后，将 PFX 导入新 Windows 用户的「个人」证书存储并保留同一友好名称；直接生成另一张证书无法覆盖已有安装。

## 从旧版切换到新签名

1.0.0–1.4.0 使用旧签名，不能直接覆盖安装 1.5.0。卸载旧版会清除应用内整理记录、回收站记录和设置，不会删除系统相册中的照片。旧签名参数仅留作历史维护：如持有原密钥库和口令，可不带 `-WindowsStore`，并用 `-KeystorePath` 指向仓库外的密钥库。

## 历史清理的作用与边界

v1.4.0 沿用原正式签名，以支持直接覆盖旧版。此次从源码历史移除密钥文件和明文签名配置，并保留既有 Release 页面和 APK。公开证书不是私钥，不需从 APK 移除。

重写 Git 历史不能撤回别人已取得的副本，也不能证明旧私钥未被复制。GitHub 的旧提交缓存需由仓库所有者联系支持处理，见 [GitHub 敏感信息清理说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。安装包应从官方 Release 获取。
