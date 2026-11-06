package com.example.planya.data

import com.example.planya.data.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InterestsSyncTest {
    private class Store(var value: InterestSelection) : InterestsStore {
        override fun read(userId: String) = value
        fun select(values: Set<String>) { value = InterestSelection(values, true, true, value.version + 1) }
        override fun markSynced(userId: String, version: Long): Boolean {
            if (value.version != version) return false
            value = value.copy(dirty = false); return true
        }
        override fun pull(userId: String, values: Set<String>, version: Long): Boolean {
            if (value.dirty || value.version != version) return false
            value = InterestSelection(values, true, false, version + 1); return true
        }
    }
    private class Gateway : ProfileGateway {
        var profile = UserProfile("user", "Nombre remoto", avatarKey = "avatar-keep", revision = 3, saved = true, interests = listOf("CAMINATA"))
        var writes = 0
        var beforeGet: (() -> Unit)? = null
        var beforeSave: (() -> Unit)? = null
        var getFailure = false
        var conflict = false
        override suspend fun get(): UserProfile {
            beforeGet?.invoke(); beforeGet = null
            if (getFailure) error("Sin red")
            return profile
        }
        override suspend fun upload(name: String): String = error("No debe subir una foto")
        override suspend fun save(name: String, revision: Long, avatarKey: String?, removeAvatar: Boolean, interests: Set<String>): UserProfile {
            writes++
            assertEquals(profile.name, name); assertNull(avatarKey); assertFalse(removeAvatar)
            beforeSave?.invoke(); beforeSave = null
            if (conflict) throw ProfileConflictException("Cambió")
            profile = profile.copy(interests = interests.toList(), revision = revision + 1, saved = true)
            return profile
        }
    }
    @Test fun dirtySelectionIsUploadedWithoutChangingNameOrAvatar() = runBlocking {
        val store = Store(InterestSelection(setOf("CAFE"), true, true, 1)); val gateway = Gateway()
        InterestsSync(store).sync("user", gateway)
        assertEquals(listOf("CAFE"), gateway.profile.interests)
        assertEquals("avatar-keep", gateway.profile.avatarKey)
        assertFalse(store.value.dirty)
    }
    @Test fun newDeviceLoadsRemotePreferencesWithoutWritingAProfile() = runBlocking {
        val store = Store(InterestSelection(emptySet(), false, false, 0)); val gateway = Gateway()
        InterestsSync(store).sync("user", gateway)
        assertEquals(setOf("CAMINATA"), store.value.values)
        assertTrue(store.value.complete); assertFalse(store.value.dirty); assertEquals(0, gateway.writes)
    }
    @Test fun offlineFailureKeepsLocalSelectionPending() = runBlocking {
        val store = Store(InterestSelection(setOf("CAFE"), true, true, 1)); val gateway = Gateway().apply { getFailure = true }
        assertTrue(runCatching { InterestsSync(store).sync("user", gateway) }.isFailure)
        assertEquals(setOf("CAFE"), store.value.values); assertTrue(store.value.dirty)
    }
    @Test fun aSlowReadCannotReplaceANewerLocalSelection() = runBlocking {
        val store = Store(InterestSelection(emptySet(), false, false, 0)); val gateway = Gateway()
        gateway.beforeGet = { store.select(setOf("DEPORTES")) }
        InterestsSync(store).sync("user", gateway)
        assertEquals(setOf("DEPORTES"), store.value.values)
        assertEquals(listOf("DEPORTES"), gateway.profile.interests)
    }
    @Test fun choiceChangedDuringUploadIsSentAgainBeforeMarkingSynced() = runBlocking {
        val store = Store(InterestSelection(setOf("CAFE"), true, true, 1)); val gateway = Gateway()
        gateway.beforeSave = { store.select(setOf("FIESTA")) }
        InterestsSync(store).sync("user", gateway)
        assertEquals(2, gateway.writes); assertEquals(listOf("FIESTA"), gateway.profile.interests)
        assertFalse(store.value.dirty)
    }
    @Test fun anotherUsersProfileCannotBeWrittenOrPulled() = runBlocking {
        val store = Store(InterestSelection(setOf("CAFE"), true, true, 1)); val gateway = Gateway()
        gateway.profile = gateway.profile.copy(userId = "other")
        assertTrue(runCatching { InterestsSync(store).sync("user", gateway) }.isFailure)
        assertEquals(0, gateway.writes); assertTrue(store.value.dirty)
    }
    @Test fun repeatedConflictsStopAndKeepTheLocalChoice() = runBlocking {
        val store = Store(InterestSelection(setOf("CAFE"), true, true, 1)); val gateway = Gateway().apply { conflict = true }
        assertTrue(runCatching { InterestsSync(store).sync("user", gateway) }.exceptionOrNull() is ProfileConflictException)
        assertEquals(2, gateway.writes); assertTrue(store.value.dirty)
    }
    @Test fun skippingInterestsStillSynchronizesAnExplicitEmptySelection() = runBlocking {
        val store = Store(InterestSelection(emptySet(), true, true, 1)); val gateway = Gateway()
        InterestsSync(store).sync("user", gateway)
        assertEquals(emptyList<String>(), gateway.profile.interests); assertFalse(store.value.dirty)
    }
}
