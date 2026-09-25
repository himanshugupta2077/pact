package app.pact.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "app_groups")
data class AppGroup(
    @PrimaryKey val id: String,
    val name: String,
    val dailyLimitMinutes: Int,
    val windowMode: String = WindowMode.NONE.name,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val window: WindowMode
        get() = runCatching { WindowMode.valueOf(windowMode) }.getOrDefault(WindowMode.NONE)

    val hasWindow: Boolean
        get() = window != WindowMode.NONE && windowStartMinutes != windowEndMinutes
}

@Entity(
    tableName = "app_group_members",
    primaryKeys = ["groupId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = AppGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["packageName"], unique = true),
    ],
)
data class AppGroupMember(
    val groupId: String,
    val packageName: String,
)

data class AppGroupWithMembers(
    @Embedded val group: AppGroup,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId",
    )
    val members: List<AppGroupMember>,
) {
    val packageNames: Set<String>
        get() = members.mapTo(LinkedHashSet()) { it.packageName }

    fun toRule(): GroupRule = GroupRule(
        id = group.id,
        name = group.name,
        dailyLimitMinutes = group.dailyLimitMinutes,
        windowMode = group.windowMode,
        windowStartMinutes = group.windowStartMinutes,
        windowEndMinutes = group.windowEndMinutes,
        packages = packageNames,
    )
}

data class GroupRule(
    val id: String,
    val name: String,
    val dailyLimitMinutes: Int,
    val windowMode: String = WindowMode.NONE.name,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
    val packages: Set<String>,
) {
    val window: WindowMode
        get() = runCatching { WindowMode.valueOf(windowMode) }.getOrDefault(WindowMode.NONE)

    val hasLimit: Boolean
        get() = dailyLimitMinutes > 0

    val hasWindow: Boolean
        get() = window != WindowMode.NONE && windowStartMinutes != windowEndMinutes

    val hasRestriction: Boolean
        get() = hasLimit || hasWindow

    fun blocksAt(nowMinutes: Int): Boolean =
        window.blocksAt(nowMinutes, windowStartMinutes, windowEndMinutes)
}

@Dao
interface AppGroupDao {
    @Transaction
    @Query("SELECT * FROM app_groups ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AppGroupWithMembers>>

    @Transaction
    @Query("SELECT * FROM app_groups WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<AppGroupWithMembers?>

    @Transaction
    @Query("SELECT * FROM app_groups ORDER BY updatedAt DESC")
    suspend fun getAll(): List<AppGroupWithMembers>

    @Transaction
    @Query("SELECT * FROM app_groups WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AppGroupWithMembers?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: AppGroup)

    @Query("DELETE FROM app_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    @Query("DELETE FROM app_group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<AppGroupMember>)

    @Transaction
    suspend fun save(group: AppGroup, packages: List<String>) {
        upsertGroup(group)
        deleteMembers(group.id)
        if (packages.isNotEmpty()) {
            insertMembers(packages.map { AppGroupMember(groupId = group.id, packageName = it) })
        }
    }
}
