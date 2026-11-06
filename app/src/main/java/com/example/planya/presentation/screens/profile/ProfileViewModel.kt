package com.example.planya.presentation.screens.profile

import android.net.Uri
import androidx.lifecycle.*
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.photos.PlanPhotoStore
import com.example.planya.data.profile.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProfileState(val profile: UserProfile? = null, val name: String = "", val localPhoto: String? = null,
    val removeAvatar: Boolean = false, val loading: Boolean = false, val loaded: Boolean = false,
    val busy: Boolean = false, val photoBusy: Boolean = false, val dirty: Boolean = false,
    val error: String? = null, val conflict: Boolean = false, val savedEvent: Int = 0, val requiresLogin: Boolean = false)

class ProfileViewModel(private val gateway: ProfileGateway, val store: PlanPhotoStore,
    private val userId: String, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState(name = saved["name"] ?: "", localPhoto = saved["photo"],
        removeAvatar = saved["remove"] ?: false, dirty = saved["dirty"] ?: false))
    val state = _state.asStateFlow()
    init { load(preserveDraft = true) }
    fun name(value: String) {
        if (!canEdit()) return
        saved["name"] = value; saved["dirty"] = true
        _state.update { it.copy(name = value, dirty = true, error = if (it.conflict) it.error else null) }
    }
    private fun canEdit() = _state.value.loaded && !_state.value.loading && !_state.value.busy && !_state.value.photoBusy
    fun error(message: String) { _state.update { it.copy(error = message) } }
    fun select(uri: Uri) {
        if (!canEdit()) return
        _state.update { it.copy(photoBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val file = store.prepare(uri)
                store.delete(_state.value.localPhoto)
                saved["photo"] = file; saved["remove"] = false; saved["dirty"] = true
                saved.remove<String>("uploadedKey")
                _state.update { it.copy(localPhoto = file, removeAvatar = false, dirty = true) }
                if (uri.authority == "com.example.planya.photos" && uri.lastPathSegment != file) store.delete(uri.lastPathSegment)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: "No se pudo preparar la foto.") }
            finally { _state.update { it.copy(photoBusy = false) } }
        }
    }
    fun removePhoto() {
        if (!canEdit()) return
        store.delete(_state.value.localPhoto)
        saved.remove<String>("photo"); saved.remove<String>("uploadedKey"); saved["remove"] = true; saved["dirty"] = true
        _state.update { it.copy(localPhoto = null, removeAvatar = true, dirty = true) }
    }
    fun load(preserveDraft: Boolean = false) {
        if (_state.value.busy || _state.value.photoBusy || _state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = gateway.get()
                check(profile.userId == userId) { "El perfil recibido no corresponde a tu sesión." }
                val keep = preserveDraft && _state.value.dirty
                val changed = keep && saved.get<Long>("revision")?.let { it != profile.revision } == true
                if (!keep) {
                    store.delete(_state.value.localPhoto)
                    saved.remove<String>("photo"); saved.remove<String>("uploadedKey")
                    saved["name"] = profile.name; saved["dirty"] = false; saved["remove"] = false
                    saved["revision"] = profile.revision
                }
                _state.update { it.copy(profile = profile, loading = false, loaded = true, conflict = changed,
                    error = if (changed) "Tu perfil cambió mientras la app estaba cerrada. Recarga antes de guardar; tu borrador sigue aquí." else null,
                    name = if (keep) it.name else profile.name, localPhoto = if (keep) it.localPhoto else null,
                    removeAvatar = keep && it.removeAvatar, dirty = keep) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { _state.update { it.copy(loading = false, requiresLogin = true) } }
            catch (failure: Exception) { _state.update { it.copy(loading = false, error = failure.message ?: "No pudimos cargar tu perfil.") } }
        }
    }
    fun acknowledgeLogin() { _state.update { it.copy(requiresLogin = false) } }
    fun acknowledgeSaved() { _state.update { it.copy(savedEvent = 0) } }
    fun discard() {
        if (_state.value.busy || _state.value.photoBusy) return
        store.delete(_state.value.localPhoto)
        saved.remove<String>("photo"); saved.remove<String>("uploadedKey")
        saved["dirty"] = false; saved["remove"] = false; saved["name"] = _state.value.profile?.name.orEmpty()
        _state.update { it.copy(name = it.profile?.name.orEmpty(), localPhoto = null, removeAvatar = false, dirty = false, error = null, conflict = false) }
    }
    fun save(interests: Set<String>) {
        val current = _state.value
        if (!canEdit() || current.conflict) return
        val name = current.name.trim()
        if (name.length !in 1..80 || name.any { it.code < 32 }) { error("Escribe un nombre de 1 a 80 caracteres, sin saltos de línea."); return }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                var key: String? = null
                current.localPhoto?.let { file ->
                    val age = System.currentTimeMillis() - (saved.get<Long>("uploadedAt") ?: 0L)
                    key = saved.get<String>("uploadedKey")?.takeIf { age in 0..1_200_000L }
                    if (key == null) {
                        key = gateway.upload(file); saved["uploadedKey"] = key; saved["uploadedAt"] = System.currentTimeMillis()
                    }
                }
                val profile = gateway.save(name, current.profile!!.revision, key, current.removeAvatar, interests)
                check(profile.userId == userId) { "El perfil recibido no corresponde a tu sesión." }
                store.delete(current.localPhoto)
                saved.remove<String>("photo"); saved.remove<String>("uploadedKey")
                saved["name"] = profile.name; saved["dirty"] = false; saved["remove"] = false
                saved["revision"] = profile.revision
                _state.update { it.copy(profile = profile, name = profile.name, localPhoto = null, removeAvatar = false,
                    busy = false, dirty = false, savedEvent = it.savedEvent + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { saved.remove<String>("uploadedKey"); _state.update { it.copy(busy = false, requiresLogin = true) } }
            catch (failure: ProfileConflictException) { _state.update { it.copy(busy = false, conflict = true, error = failure.message) } }
            catch (failure: Exception) { _state.update { it.copy(busy = false, error = failure.message ?: "No se pudo guardar. Conservamos tus cambios.") } }
        }
    }
    override fun onCleared() { store.delete(_state.value.localPhoto); super.onCleared() }
}
