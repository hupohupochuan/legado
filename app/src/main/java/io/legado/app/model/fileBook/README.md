# 书籍文件导入与解析

- `BaseFileBook.kt`：文件书解析基类，覆盖本地、远程和归档重导入场景。
- `LocalBookFormatHandler.kt`：各格式处理器的统一能力接口。
- `FileBook.kt`：格式选择和导入解析总入口。
- `TextFile.kt`：TXT/未知格式的最终文本兜底。
- `EpubFile.kt`：EPUB。
- `PdfFile.kt`：PDF，使用 Android `PdfRenderer` 按图片页读取。
- `UmdFile.kt`：UMD。
- `MobiFile.kt`：MOBI、AZW、AZW3。
- `CbzFile.kt`：CBZ 漫画。
- `LocalZipWrapper.kt`、`ContentZipWrapper.kt`、`RemoteZipWrapper.kt`：本地、SAF 和远程 ZIP 访问；导入层还可解压 ZIP/RAR/7Z 容器。
