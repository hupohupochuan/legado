# 数据与持久化

- `AppDatabase.kt`：Room 数据库入口；其 `@Database` 声明是当前持久化实体和数据库视图的权威清单。
- `DatabaseMigrations.kt`：数据库迁移。
- `dao/`：Room DAO。
- `entities/`：Room 实体、数据库视图以及部分普通传输模型。

当前数据库包含书籍、分组、书源、章节、替换规则、搜索关键字、Cookie、书签、TXT 目录规则、阅读记录、HTTP TTS、缓存、规则订阅、字典规则、键盘辅助和远程书库服务器等实体；`BookSourcePart` 是数据库视图。

`SearchBook` 是搜索结果 DTO，不是 Room 实体。旧 `OldRssSource` 仅用于导入旧 RSS JSON/备份；独立 RSS 表和实体已删除，RSS 源已合并为 `bookSourceType=5` 的 `BookSource`。
