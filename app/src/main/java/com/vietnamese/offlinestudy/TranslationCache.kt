
package com.vietnamese.offlinestudy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TranslationCache(context: Context) :
    SQLiteOpenHelper(context, "translations.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE translations (
                source TEXT PRIMARY KEY,
                translated TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    fun getTranslation(source: String): String? {
        val db = readableDatabase

        db.query(
            "translations",
            arrayOf("translated"),
            "source = ?",
            arrayOf(source),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }

        return null
    }

    fun saveTranslation(
        source: String,
        translated: String
    ) {
        val values = ContentValues().apply {
            put("source", source)
            put("translated", translated)
        }

        writableDatabase.insertWithOnConflict(
            "translations",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
