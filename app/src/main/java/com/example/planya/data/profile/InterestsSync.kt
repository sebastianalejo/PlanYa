package com.example.planya.data.profile

import android.content.SharedPreferences
import com.example.planya.domain.model.PlanCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InterestSelection(val values: Set<String>, val complete: Boolean, val dirty: Boolean, val version: Long)
interface InterestsStore {
    fun read(userId: String): InterestSelection
    fun markSynced(userId: String, version: Long): Boolean
    fun pull(userId: String, values: Set<String>, version: Long): Boolean
}

class DeviceInterestsStore(private val prefs: SharedPreferences) : InterestsStore {
    @Synchronized override fun read(userId: String): InterestSelection {
        val complete = prefs.getBoolean("onboarding_$userId", false)
        return InterestSelection(prefs.getStringSet("interests_$userId", emptySet()).orEmpty().toSet(), complete,
            prefs.getBoolean("interests_dirty_$userId", complete), prefs.getLong("interests_version_$userId", 0))
    }
    @Synchronized fun select(userId: String, values: Set<String>) {
        val version = read(userId).version + 1
        check(prefs.edit().putStringSet("interests_$userId", values).putBoolean("onboarding_$userId", true)
            .putBoolean("interests_dirty_$userId", true).putLong("interests_version_$userId", version).commit()) { "No se pudieron guardar tus intereses en este teléfono." }
    }
    @Synchronized override fun markSynced(userId: String, version: Long): Boolean {
        if (read(userId).version != version) return false
        return prefs.edit().putBoolean("interests_dirty_$userId", false).commit()
    }
    @Synchronized override fun pull(userId: String, values: Set<String>, version: Long): Boolean {
        val current = read(userId)
        if (current.dirty || current.version != version) return false
        return prefs.edit().putStringSet("interests_$userId", values).putBoolean("onboarding_$userId", true)
            .putBoolean("interests_dirty_$userId", false).putLong("interests_version_$userId", version + 1).commit()
    }
}

// Una actualización reciente del teléfono no puede ser reemplazada por una lectura lenta.
class InterestsSync(private val store: InterestsStore) {
    private val mutex = Mutex()
    suspend fun sync(userId: String, gateway: ProfileGateway) = mutex.withLock {
        var conflicts = 0
        repeat(4) {
            val remote = gateway.get()
            check(remote.userId == userId) { "No se sincronizaron intereses de otra cuenta." }
            val local = store.read(userId)
            if (!local.dirty && remote.saved) {
                val allowed = PlanCategory.entries.map { it.name }.toSet()
                val pulled = store.pull(userId, remote.interests.filter { it in allowed }.toSet(), local.version)
                if (!pulled) {
                    if (store.read(userId).dirty) return@repeat
                    error("No se pudieron guardar los intereses recuperados en este teléfono.")
                }
                return@withLock
            }
            if (!local.complete) return@withLock
            try {
                val updated = gateway.save(remote.name, remote.revision, null, false, local.values)
                check(updated.userId == userId) { "La respuesta no corresponde a tu cuenta." }
                if (store.markSynced(userId, local.version)) return@withLock
            } catch (failure: ProfileConflictException) {
                if (++conflicts >= 2) throw failure
            }
        }
        error("Hay cambios recientes sin sincronizar. Vuelve a intentar desde tu perfil.")
    }
}
