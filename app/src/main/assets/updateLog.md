# 更新日志

## cronet版本: 146.0.7680.119

## **必读**

【温馨提醒】 *更新前一定要做好备份，以免数据丢失！*


* 正文出现缺字漏字、内容缺失、排版错乱等情况，有可能是净化规则或简繁转换出现问题。


**2026/06/26**
Android 17 (API 37) 适配前置闸门：compileSdk/targetSdk 抬至 37，验证 AGP 9.2.1 + Gradle 9.4.1 + Kotlin 2.3.21 + KSP 2.3.7 组合直接支持 API 37，无需升级 AGP；Debug 编译与完整 APK 打包均通过。后续行为变更适配以独立条目推进
16KB page size 验证：libarchive-jni.so 的 LOAD 段对齐 0x4000(16KB) 且 APK 内 zip 偏移已对齐；cronet .so 走系统 HttpEngine / play-services 侧，兜底下载路径不在 16KB 设备触发，当前无需升级 cronet/libarchive 或改任何代码

**2026/06/25**
修复目录弹窗不随当前章节定位的问题：Web 服务阅读页 BookChapter.vue 改用 store 中的 popCataVisible（PopCatalog.vue 的 onUpdated 钩子依赖此状态触发 scrollToIndex），消除本地 ref 与 store 不同步导致的滚动拦截；App 原生阅读页打开目录前同步 ReadBook 当前章节到传入目录页的 Book，避免异步保存进度尚未落库时目录仍定位旧章节

**2026/06/24**
修复阅读时长记录器协程泄漏：ReadTimeRecorder 改用内部 SupervisorJob scope 替代 GlobalScope，会话延迟结束协程随生命周期可控，不再残留不可取消的全局协程
优化 WebDAV 初始化：AppWebDav 移除 init 块的 runBlocking 同步网络校验，改为 App.onCreate IO 协程异步预热，避免单例首次被主线程访问时阻塞 UI
抽取 ContentDownloadState 共享类：统一 ReadBook 与 ReadMangaViewModel 的预下载状态字段（downloadedChapters/downloadFailChapters/downloadScope/preDownloadSemaphore/preDownloadTask），消除重复定义，附单元测试
清理 ReadBook 中 book!! 强解包：loadContent/downloadAwait 改为安全返回，避免 book 为 null 时 NPE
cronet onResponseStarted 调试日志改用脱敏 URL（仅 host/path），避免泄露 WebDAV URL

**2026/06/18**
修复 WebDAV 阅读进度同步时误触发本地书目录权限提示的问题：进度校验拆分 RangeOnly/ReadableRequired，仅同步进度不再对本地书执行 checkBookReadable，跨设备旧 content:// URI 失效时不再弹目录权限

**2026/06/16**
WebDAV 在线恢复新增“仅恢复阅读进度”模式，跨设备恢复本地书时不再写入其他设备的 SAF 路径
WebDAV 备份恢复入口新增恢复方式选择，仍保留完整恢复备份


**2026/06/15**
新增自动更新检查间隔设置（不检查/每次启动/每周/每月）
新增"跳过此版本"功能，取消更新对话框时可跳过当前版本
修复自动更新检查仍指向旧 fork 仓库的问题
清理分享文案和远程资源中的旧项目链接
清理 README 顶部旧站点链接和社区频道入口


**2026/06/14**
修复 EPUB/ZIP 目录读取失败时错误显示空指针的问题
修复云端进度异常时仍可能影响本地书打开的问题
修复本地书籍读取失败时阅读页卡在加载中的问题
修复 WebDAV 全量同步进度时可能把远端进度写入不可读本地书的问题
修复远程书籍入口 401 认证失败与本地权限错误混在一起的问题
修复 WebDAV 下载到本地的 EPUB 书籍在部分设备上无法打开的问题（缓存回退路径对 WebDAV 来源书籍生效）


**2026/06/10**
书架新增 RSS 订阅角标，列表和网格模式下可直接区分订阅与普通书籍

**2026/06/08**
修复本地 EPUB 读取权限丢失后阅读页卡加载中的问题
修复远程书籍同名本地文件失效后仍直接打开失效路径的问题

**2026/06/06**
修复全文搜索章节查询丢失 bookUrl 的问题
修复阅读器切换已缓存章节偶发卡加载中的问题
更新本地环境文件忽略规则
新增提交前本地敏感文件检查
新增本地 Debug 编译验证脚本
增强 WebDAV 配置和进度同步日志
新增 Web 前端 assets 同步提交检查
新增阅读器章节加载状态单元测试
收敛本地书籍和 WebDAV 书籍边界命名
收敛阅读器章节加载状态表达
抽离阅读器章节读取职责
清理 Kotlin 编译警告

**2026/06/05**
修复本地 epub 图片加载与预加载
恢复书架导出菜单和 WebDAV 上传菜单入口
抽取书架导出逻辑为公共工具类
同步 Web 端修复与构建配置

**2026/06/01**
开源阅读原github删库，本人水平不足只能做兼容性更新
hupo on 2026/5/31 at 23:29兼容低版本获取书籍分组参数
长标题有限条件自动换行
统一前台服务启动入口为 safeStartForegroundService
