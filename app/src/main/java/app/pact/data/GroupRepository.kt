package app.pact.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class GroupRepository(
    private val dao: AppGroupDao,
    private val canEdit: () -> Boolean = { true },
) {
    fun observeAll(): Flow<List<AppGroupWithMembers>> = dao.observeAll()
    fun observe(id: String): Flow<AppGroupWithMembers?> = dao.observe(id)
    suspend fun get(id: String): AppGroupWithMembers? = dao.get(id)
    suspend fun getAll(): List<AppGroupWithMembers> = dao.getAll()

    suspend fun save(
        id: String?,
        name: String,
        dailyLimitMinutes: Int,
        packages: Collection<String>,
        windowMode: WindowMode = WindowMode.NONE,
        windowStartMinutes: Int = 0,
        windowEndMinutes: Int = 0,
    ): String? {
        if (!canEdit()) return null
        val groupId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val group = AppGroup(
            id = groupId,
            name = name.trim(),
            dailyLimitMinutes = dailyLimitMinutes.coerceAtLeast(0),
            windowMode = windowMode.name,
            windowStartMinutes = windowStartMinutes.coerceIn(0, 24 * 60 - 1),
            windowEndMinutes = windowEndMinutes.coerceIn(0, 24 * 60 - 1),
            updatedAt = System.currentTimeMillis(),
        )
        val members = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        dao.save(group, members)
        return groupId
    }

    suspend fun delete(id: String): Boolean {
        if (!canEdit()) return false
        dao.deleteGroup(id)
        return true
    }
}
