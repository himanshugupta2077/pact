package app.pact.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.pact.util.formatClockMinutes
import app.pact.util.inTimeWindow
import kotlinx.coroutines.flow.Flow

enum class ForceState {
    NONE,
    LOCKED,
    UNLOCKED,
}

enum class WindowMode {
    NONE,
    ALLOW,
    BLOCK,
}

fun WindowMode.blocksAt(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
    if (this == WindowMode.NONE || startMinutes == endMinutes) return false
    val inside = inTimeWindow(nowMinutes, startMinutes, endMinutes)
    return when (this) {
        WindowMode.ALLOW -> !inside
        WindowMode.BLOCK -> inside
        WindowMode.NONE -> false
    }
}

@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val dailyLimitMinutes: Int,
    val forceState: String = ForceState.NONE.name,
    val windowMode: String = WindowMode.NONE.name,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val force: ForceState
        get() = runCatching { ForceState.valueOf(forceState) }.getOrDefault(ForceState.NONE)

    val window: WindowMode
        get() = runCatching { WindowMode.valueOf(windowMode) }.getOrDefault(WindowMode.NONE)

    val hasLimit: Boolean
        get() = dailyLimitMinutes > 0

    val hasWindow: Boolean
        get() = window != WindowMode.NONE && windowStartMinutes != windowEndMinutes

    val isBlank: Boolean
        get() = !hasLimit && force == ForceState.NONE && !hasWindow

    fun blocksAt(nowMinutes: Int): Boolean =
        window.blocksAt(nowMinutes, windowStartMinutes, windowEndMinutes)

    fun nextAvailableClock(nowMinutes: Int, usedMs: Long): String? {
        if (force == ForceState.LOCKED) return null
        val limitHit = hasLimit && usedMs >= dailyLimitMinutes * 60_000L
        if (!limitHit && !blocksAt(nowMinutes)) return null
        val start = nowMinutes + 1
        val end = nowMinutes + 24 * 60 * 2
        for (t in start until end) {
            val day = t / (24 * 60)
            val minute = t % (24 * 60)
            val overLimit = day == 0 && limitHit
            if (!overLimit && !blocksAt(minute)) return formatClockMinutes(minute)
        }
        return formatClockMinutes(0)
    }
}

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AppRule>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAll(): List<AppRule>

    @Query("SELECT * FROM app_rules WHERE packageName = :pkg LIMIT 1")
    fun observe(pkg: String): Flow<AppRule?>

    @Query("SELECT * FROM app_rules WHERE packageName = :pkg LIMIT 1")
    suspend fun get(pkg: String): AppRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AppRule)

    @Query("DELETE FROM app_rules WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Database(
    entities = [AppRule::class, AppGroup::class, AppGroupMember::class],
    version = 4,
    exportSchema = false,
)
abstract class PactDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun appGroupDao(): AppGroupDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_rules ADD COLUMN windowMode TEXT NOT NULL DEFAULT 'NONE'",
                )
                db.execSQL(
                    "ALTER TABLE app_rules ADD COLUMN windowStartMinutes INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE app_rules ADD COLUMN windowEndMinutes INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_group_members` (`groupId` TEXT NOT NULL, `packageName` TEXT NOT NULL, PRIMARY KEY(`groupId`, `packageName`), FOREIGN KEY(`groupId`) REFERENCES `app_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_group_members_groupId` ON `app_group_members` (`groupId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_app_group_members_packageName` ON `app_group_members` (`packageName`)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_groups ADD COLUMN windowMode TEXT NOT NULL DEFAULT 'NONE'",
                )
                db.execSQL(
                    "ALTER TABLE app_groups ADD COLUMN windowStartMinutes INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE app_groups ADD COLUMN windowEndMinutes INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun create(context: Context): PactDatabase {
            return Room.databaseBuilder(context, PactDatabase::class.java, "pact.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
