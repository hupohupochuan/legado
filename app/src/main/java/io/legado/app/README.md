# Android 主包结构

- `App.kt`：Application 入口与进程级初始化。
- `api/`：Web/Content Provider 共用的数据控制器与外部接口。
- `base/`：Activity、Fragment、Dialog、Service、ViewModel 等基类。
- `constant/`：事件、偏好键和应用常量。
- `data/`：Room 数据库、DAO、实体及兼容数据模型。
- `exception/`：业务异常类型。
- `help/`：书籍处理、配置、网络、备份、更新等跨域辅助逻辑。
- `lib/`：项目内维护的第三方库封装和自定义组件。
- `model/`：阅读、音频、缓存等运行时业务状态及书源/文件书解析实现。
- `receiver/`：BroadcastReceiver。
- `service/`：朗读、音频、缓存、导出和 Web 服务。
- `ui/`：按功能划分的 Android 界面。
- `utils/`：通用 Kotlin 扩展与工具类。
- `web/`：嵌入式 HTTP/WebSocket 服务和 APK Web 静态资源响应。
