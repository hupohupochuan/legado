# 业务辅助层

- `book/`：章节、正文和书籍业务处理。
- `config/`：应用、主题、阅读等配置封装。
- `coroutine/`：项目协程工具。
- `crypto/`：Rhino 可调用的加密封装。
- `http/`：HTTP、Cookie、代理和 Cronet 接入。
- `source/`：书源相关辅助逻辑。
- `storage/`：文件与存储访问。
- `tts/`：朗读引擎辅助逻辑。
- `update/`：应用更新检查。
- `exoplayer/`、`glide/`、`media/`、`rhino/`：对应第三方能力的项目封装。

顶层还包含 `Backup`、`Restore`、`AppWebDav`、`ContentProcessor` 等跨域协调类；修改前应先沿调用链确认职责边界。
