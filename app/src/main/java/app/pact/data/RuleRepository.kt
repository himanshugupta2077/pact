package app.pact.data

import kotlinx.coroutines.flow.Flow

class RuleRepository(
    private val dao: AppRuleDao,
    private val canEdit: () -> Boolean = { true },
) {
    fun observeAll(): Flow<List<AppRule>> = dao.observeAll()
    fun observe(pkg: String): Flow<AppRule?> = dao.observe(pkg)
    suspend fun get(pkg: String): AppRule? = dao.get(pkg)
    suspend fun getAll(): List<AppRule> = dao.getAll()

    suspend fun setLimit(packageName: String, minutes: Int): Boolean {
        return persist(
            base(packageName).copy(dailyLimitMinutes = minutes.coerceAtLeast(0)),
        )
    }

    suspend fun setWindow(
        packageName: String,
        mode: WindowMode,
        startMinutes: Int,
        endMinutes: Int,
    ): Boolean {
        return persist(
            base(packageName).copy(
                windowMode = mode.name,
                windowStartMinutes = startMinutes.coerceIn(0, 24 * 60 - 1),
                windowEndMinutes = endMinutes.coerceIn(0, 24 * 60 - 1),
            ),
        )
    }

    suspend fun clear(packageName: String): Boolean {
        if (!canEdit()) return false
        dao.delete(packageName)
        return true
    }

    private suspend fun base(packageName: String): AppRule {
        return dao.get(packageName) ?: AppRule(packageName = packageName, dailyLimitMinutes = 0)
    }

    private suspend fun persist(rule: AppRule): Boolean {
        if (!canEdit()) return false
        val next = rule.copy(updatedAt = System.currentTimeMillis(), forceState = ForceState.NONE.name)
        if (next.isBlank) dao.delete(next.packageName) else dao.upsert(next)
        return true
    }
}
