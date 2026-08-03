package io.legado.app

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    private val ALL_MIGRATIONS: Array<Migration> = DatabaseMigrations.migrations

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create earliest version of the database.
        helper.createDatabase(TEST_DB, 50).apply {
            close()
        }

        // Open latest version of the database. Room will validate the schema
        // once all migrations execute.
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate83To84AllowsBooksWithSameNameAndAuthor() {
        val dbName = "migration-test-83-84"
        helper.createDatabase(dbName, 83).apply {
            execSQL(
                "INSERT INTO books(bookUrl, originName, name, author) VALUES(?, ?, ?, ?)",
                arrayOf("file:///books/first.txt", "first.txt", "同名书", "同一作者")
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 84, true, *ALL_MIGRATIONS).use { db ->
            db.execSQL(
                "UPDATE books SET localFileKey = ? WHERE bookUrl = ?",
                arrayOf("sha1:first", "file:///books/first.txt")
            )
            db.execSQL(
                """INSERT OR REPLACE INTO books(
                    bookUrl, originName, name, author, localFileKey
                ) VALUES(?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf(
                    "file:///books/second.epub",
                    "second.epub",
                    "同名书",
                    "同一作者",
                    "sha1:second"
                )
            )

            db.query(
                "SELECT bookUrl FROM books WHERE name = ? AND author = ? ORDER BY bookUrl",
                arrayOf("同名书", "同一作者")
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertEquals(true, cursor.moveToFirst())
                assertEquals("file:///books/first.txt", cursor.getString(0))
                assertEquals(true, cursor.moveToNext())
                assertEquals("file:///books/second.epub", cursor.getString(0))
            }

            db.query("PRAGMA index_list(`books`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
                var nameAuthorIndexIsUnique: Boolean? = null
                var localFileKeyIndexIsUnique: Boolean? = null
                while (cursor.moveToNext()) {
                    when (cursor.getString(nameColumn)) {
                        "index_books_name_author" -> {
                            nameAuthorIndexIsUnique = cursor.getInt(uniqueColumn) != 0
                        }
                        "index_books_localFileKey" -> {
                            localFileKeyIndexIsUnique = cursor.getInt(uniqueColumn) != 0
                        }
                    }
                }
                assertEquals(false, nameAuthorIndexIsUnique)
                assertEquals(true, localFileKeyIndexIsUnique)
            }
        }

        val roomDb = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val first = checkNotNull(roomDb.bookDao.getBook("file:///books/first.txt"))
            roomDb.bookDao.relocate(first, "file:///books/moved.txt", null)

            assertNull(roomDb.bookDao.getBook("file:///books/first.txt"))
            assertEquals(first, roomDb.bookDao.getBook("file:///books/moved.txt"))
        } finally {
            roomDb.close()
        }
    }
}
