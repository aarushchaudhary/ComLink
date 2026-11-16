package com.aarushchaudhary.comlink.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Opens the chat database encrypted with SQLCipher.
 *
 * Installs from before encryption have a plain `comlink_db`. On the first launch after the update
 * it is copied into an encrypted `comlink_secure_db` with `sqlcipher_export`. The copy is then
 * reopened exactly the way Room will open it, and every table's row count and the schema version
 * are compared with the original. Only after that check passes is the plain database deleted. If
 * anything fails, the plain database stays in use and the move is tried again next launch, so no
 * chat is ever lost to a failed migration.
 */
object SecureDatabase {
    private const val TAG = "SecureDatabase"
    const val PLAIN_NAME = "comlink_db"
    const val ENCRYPTED_NAME = "comlink_secure_db"
    private const val TEMP_SUFFIX = ".migrating"

    /** True when the one-time move from the old plain database still has to run (it can take a while). */
    fun needsMigration(context: Context): Boolean =
        context.getDatabasePath(PLAIN_NAME).exists() && !context.getDatabasePath(ENCRYPTED_NAME).exists()

    /**
     * The file names are parameters only so device tests can use their own files: they run inside
     * the app's process, next to the app's real database.
     */
    fun open(
        context: Context,
        passphrase: String,
        plainName: String = PLAIN_NAME,
        encryptedName: String = ENCRYPTED_NAME
    ): ComLinkDatabase {
        System.loadLibrary("sqlcipher")
        val plain = context.getDatabasePath(plainName)
        val encrypted = context.getDatabasePath(encryptedName)

        if (plain.exists() && !encrypted.exists()) {
            val moved = try {
                migrate(context, plain, encrypted, passphrase)
            } catch (e: Exception) {
                Log.e(TAG, "Encrypting the database failed; keeping the plain one for now", e)
                false
            }
            if (!moved) {
                // Keep working on the plain database; try again on the next launch
                return Room.databaseBuilder(context, ComLinkDatabase::class.java, plainName).build()
            }
        }

        return Room.databaseBuilder(context, ComLinkDatabase::class.java, encryptedName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
            .build()
    }

    /** Returns true once [encrypted] holds a verified copy and [plain] (with its side files) is gone. */
    private fun migrate(context: Context, plain: File, encrypted: File, passphrase: String): Boolean {
        val temp = File(encrypted.path + TEMP_SUFFIX)
        deleteWithSideFiles(temp) // leftovers of an earlier, interrupted attempt

        // CREATE_IF_NECESSARY matters: SQLite gives an ATTACHed database the main one's open flags,
        // so without it the ATTACH below can't create the new encrypted file (found on a device:
        // "unable to open database ... (code 14)"). The plain database itself always exists here.
        val source = SQLiteDatabase.openDatabase(
            plain.path, "", null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null
        )
        val expected: Map<String, Long>
        val version: Int
        try {
            version = source.version
            // Every table the old database has (whatever its schema version), Room's own included
            expected = tableNames(source).associateWith { countRows(source, it) }
            // The passphrase is 64 hex characters, so it is safe inside a SQL string literal
            require(passphrase.matches(Regex("^[0-9a-f]{64}$"))) { "Unexpected passphrase format" }
            source.execSQL("ATTACH DATABASE '${temp.path.replace("'", "''")}' AS encrypted KEY '$passphrase'")
            source.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
            source.execSQL("PRAGMA encrypted.user_version = $version")
            source.execSQL("DETACH DATABASE encrypted")
        } finally {
            source.close()
        }

        // Reopen the copy the same way Room will (same password bytes, same open call) and compare
        val check = SQLiteDatabase.openDatabase(
            temp.path, passphrase.toByteArray(Charsets.UTF_8), null, SQLiteDatabase.OPEN_READWRITE, null
        )
        val ok = try {
            check.version == version && tableNames(check) == expected.keys && expected.all { (t, n) -> countRows(check, t) == n }
        } finally {
            check.close()
        }
        if (!ok) {
            Log.e(TAG, "Encrypted copy didn't match the original (expected $expected, version $version)")
            deleteWithSideFiles(temp)
            return false
        }

        if (!temp.renameTo(encrypted)) {
            deleteWithSideFiles(temp)
            return false
        }
        deleteWithSideFiles(plain)
        Log.i(TAG, "Database encrypted: $expected")
        return true
    }

    private fun tableNames(db: SQLiteDatabase): Set<String> =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            emptyArray<String>()
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM `$table`", emptyArray<String>()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1
        }

    /** A SQLite database is the file plus its -wal, -shm and -journal side files. */
    private fun deleteWithSideFiles(db: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
    }
}
