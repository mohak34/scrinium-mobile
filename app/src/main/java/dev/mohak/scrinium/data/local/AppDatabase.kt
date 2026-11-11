package dev.mohak.scrinium.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class, MoveEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        private val ADD_VERSIONS_AND_MOVES = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN version TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE moves (seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "opId TEXT NOT NULL, fromPath TEXT NOT NULL, toPath TEXT NOT NULL)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scrinium.db")
                .addMigrations(ADD_VERSIONS_AND_MOVES)
                .build()
    }
}
