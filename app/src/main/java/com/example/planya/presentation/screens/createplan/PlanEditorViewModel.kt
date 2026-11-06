package com.example.planya.presentation.screens.createplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.gson.Gson
import androidx.lifecycle.viewModelScope
import com.example.planya.domain.repository.PlanActions
import com.example.planya.data.photos.PlanPhotoGateway
import android.net.Uri
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.data.repository.PlanConflictException
import com.example.planya.domain.model.*
import com.example.planya.domain.usecase.plan.GetPlanUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DraftPhoto(val imageKey: String? = null, val imageUrl: String = "", val localName: String? = null, val uploadedAt: Long? = null)

data class PlanDraft(val title: String = "", val description: String = "", val category: PlanCategory = PlanCategory.CAFE,
    val location: LocatedArea? = null, val capacity: String = "5", val duration: Int = 60, val startsIn: Int = 15,
    val imageUrl: String = "", val imageKey: String? = null, val localPhotoName: String? = null,
    val originalStartsAt: Long? = null, val originalExpiresAt: Long? = null,
    val expectedRevision: Long? = null, val minimumCapacity: Int = 2,
    val photos: List<DraftPhoto>? = null) {
    fun gallery(): List<DraftPhoto> = photos ?: if (imageKey != null || imageUrl.isNotBlank() || localPhotoName != null)
        listOf(DraftPhoto(imageKey, imageUrl, localPhotoName)) else emptyList()
    fun withPhotos(items: List<DraftPhoto>): PlanDraft = copy(photos = items,
        imageKey = items.firstOrNull()?.imageKey, imageUrl = items.firstOrNull()?.imageUrl.orEmpty(), localPhotoName = items.firstOrNull()?.localName)
    fun error(): String? = when {
        title.trim().isEmpty() || title.trim().length > 120 -> "Escribe un título de hasta 120 caracteres."
        description.length > 1000 -> "La descripción admite hasta 1000 caracteres."
        location == null -> "Elige el punto de encuentro."
        listOfNotNull(location.fullAddress ?: location.label, location.reference).joinToString(" · ").length > 400 -> "Acorta la dirección o la referencia a 400 caracteres."
        capacity.toIntOrNull() !in 2..100 -> "El cupo debe ser de 2 a 100 personas, incluyéndote."
        (capacity.toIntOrNull() ?: 0) < minimumCapacity -> "Ya hay $minimumCapacity asistentes. No puedes reducir el cupo por debajo de esa cantidad."
        duration !in 30..120 || startsIn !in 0 until duration -> "El inicio debe ocurrir antes del vencimiento."
        gallery().size > 4 -> "Puedes incluir como máximo 4 fotos."
        imageUrl.isNotBlank() && !imageUrl.trim().matches(Regex("https://[^/\\s]+(?:/[^\\s]*)?")) -> "La foto debe tener una dirección HTTPS válida."
        else -> null
    }
    fun payload(key: String): Map<String, Any> {
        val area = requireNotNull(location)
        return mutableMapOf<String, Any>("title" to title.trim(), "description" to description.trim(),
            "category" to category.name, "meetingPlace" to listOfNotNull(area.fullAddress ?: area.label, area.reference).joinToString(" · "),
            "latitude" to area.point.latitude, "longitude" to area.point.longitude, "capacity" to capacity.toInt(),
            "durationMinutes" to duration, "startsInMinutes" to startsIn, "requestId" to key).apply {
            expectedRevision?.let { put("expectedRevision", it) }
            if (imageKey != null) put("imageKey", imageKey)
            else if (imageUrl.isNotBlank() && localPhotoName == null) put("imageUrl", imageUrl.trim())
            put("photos", gallery().map { photo ->
                if (photo.imageKey != null) mapOf("imageKey" to photo.imageKey)
                else if (photo.localName == null && photo.imageUrl.isNotBlank()) mapOf("imageUrl" to photo.imageUrl)
                else error("Hay una foto pendiente de subir.")
            })
        }
    }
}
data class EditorState(val draft: PlanDraft = PlanDraft(), val loading: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val saved: Plan? = null, val dirty: Boolean = false,
    val loaded: Boolean = true, val requiresLogin: Boolean = false,
    val photoBusy: Boolean = false, val stage: String? = null, val conflict: Boolean = false)

class PlanEditorViewModel(private val id: String?, private val getPlan: GetPlanUseCase,
    private val actions: PlanActions, private val savedState: SavedStateHandle = SavedStateHandle(),
    private val photos: PlanPhotoGateway? = null) : ViewModel() {
    private val restored = runCatching { savedState.get<String>("draft")?.let { Gson().fromJson(it, PlanDraft::class.java) } }.getOrNull()
    private val _state = MutableStateFlow(EditorState(draft = restored ?: PlanDraft(),
        dirty = savedState["dirty"] ?: false, loading = id != null && restored == null,
        loaded = id == null || restored != null))
    val state = _state.asStateFlow()
    private val requestId = savedState.get<String>("requestId") ?: UUID.randomUUID().toString().also { savedState["requestId"] = it }
    init { photos?.store?.cleanOld(); if (id != null && restored == null) load() }
    fun change(draft: PlanDraft) { if (!_state.value.busy && !_state.value.loading && _state.value.loaded) {
        savedState["draft"] = Gson().toJson(draft)
        savedState["dirty"] = true
        _state.update { it.copy(draft = draft, dirty = true, error = if (it.conflict) it.error else null) }
    } }
    fun acknowledgeLogin() { _state.update { it.copy(requiresLogin = false) } }
    fun photoError(message: String) { _state.update { it.copy(error = message) } }
    fun selectPhoto(uri: Uri) {
        val repository = photos ?: run { photoError("No se pudo preparar el selector de fotos."); return }
        if (_state.value.busy || _state.value.photoBusy || _state.value.loading || !_state.value.loaded) return
        if (_state.value.draft.gallery().size >= 4) { photoError("Ya tienes 4 fotos. Quita una para añadir otra."); return }
        _state.update { it.copy(photoBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val name = repository.store.prepare(uri)
                change(_state.value.draft.withPhotos(_state.value.draft.gallery() + DraftPhoto(localName = name)))
                savedState.remove<String>("uploadedPhotoName")
                // Una captura propia solo se mantiene hasta terminar la conversión.
                if (uri.authority == "com.example.planya.photos" && uri.lastPathSegment != name)
                    repository.store.delete(uri.lastPathSegment)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { photoError(failure.message ?: "No se pudo preparar la foto. Elige otra.") }
            finally { _state.update { it.copy(photoBusy = false) } }
        }
    }
    fun removePhoto() = removePhotoAt(0)
    fun removePhotoAt(index: Int) {
        if (_state.value.busy || _state.value.photoBusy) return
        val items = _state.value.draft.gallery()
        if (index !in items.indices) return
        photos?.store?.delete(items[index].localName)
        savedState.remove<String>("uploadedPhotoName")
        change(_state.value.draft.withPhotos(items.filterIndexed { i, _ -> i != index }))
    }
    fun makeCover(index: Int) {
        val items = _state.value.draft.gallery()
        if (_state.value.busy || _state.value.photoBusy || index !in items.indices) return
        change(_state.value.draft.withPhotos(listOf(items[index]) + items.filterIndexed { i, _ -> i != index }))
    }
    fun load() {
        if (id == null || _state.value.busy || _state.value.photoBusy) return
        val previousPhotos = _state.value.draft.gallery().mapNotNull { it.localName }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val plan = getPlan(id)
                _state.update { it.copy(loading = false, loaded = true, dirty = false, conflict = false, draft = PlanDraft(plan.title, plan.description.orEmpty(), plan.category ?: PlanCategory.CAFE,
                    plan.coordinates?.let { point -> LocatedArea(point, plan.meetingPlace ?: "Punto de encuentro", true,
                        fullAddress = plan.meetingPlace) }, plan.capacity?.toString() ?: "5", imageUrl = plan.imageUrl.orEmpty(), imageKey = plan.imageKey,
                    originalStartsAt = plan.startsAt, originalExpiresAt = plan.expiresAt, expectedRevision = plan.revision,
                    minimumCapacity = maxOf(2, plan.attendeeIds?.size ?: plan.attendees?.size ?: 0))
                    .withPhotos(plan.gallery().map { photo -> DraftPhoto(photo.imageKey, photo.imageUrl.orEmpty()) })) }
                savedState["draft"] = Gson().toJson(_state.value.draft)
                savedState["dirty"] = false
                savedState.remove<String>("uploadedPhotoName")
                previousPhotos.forEach { photos?.store?.delete(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = "No se pudo cargar el plan. Reintenta.") } }
        }
    }
    fun save() {
        val current = _state.value
        if (current.busy || current.photoBusy || current.loading || !current.loaded) return
        if (current.conflict) return
        if (id != null && current.draft.originalExpiresAt?.let { it <= System.currentTimeMillis() / 1000 } == true) {
            _state.update { it.copy(error = "Este plan ya finalizó. No se pueden guardar más cambios.") }; return
        }
        val error = current.draft.error()
        if (error != null) { _state.update { it.copy(error = error) }; return }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                var draft = current.draft
                val items = draft.gallery().toMutableList()
                items.indices.forEach { index ->
                    val photo = items[index]
                    val localName = photo.localName
                    val uploadAge = System.currentTimeMillis() - (photo.uploadedAt ?: 0L)
                    if (localName != null && (photo.imageKey == null || uploadAge !in 0L..1_200_000L)) {
                    _state.update { it.copy(stage = "Subiendo foto ${index + 1} de ${items.size}…") }
                    val key = (photos ?: error("No se pudo preparar la subida de fotos.")).upload(localName, requestId, id)
                    items[index] = photo.copy(imageKey = key, uploadedAt = System.currentTimeMillis())
                    draft = draft.withPhotos(items.toList())
                    savedState["draft"] = Gson().toJson(draft)
                    savedState["uploadedPhotoName"] = localName
                    savedState["uploadedPhotoAt"] = System.currentTimeMillis()
                    val uploaded = draft
                    _state.update { it.copy(draft = uploaded) }
                    }
                }
                _state.update { it.copy(stage = "Guardando plan…") }
                val data = draft.payload(requestId)
                val plan = if (id == null) actions.create(data) else actions.edit(id, data)
                savedState.remove<String>("draft"); savedState.remove<Boolean>("dirty")
                draft.gallery().mapNotNull { it.localName }.forEach { photos?.store?.delete(it) }
                _state.update { it.copy(busy = false, saved = plan, dirty = false, stage = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) {
                savedState.remove<String>("uploadedPhotoName")
                val retryDraft = _state.value.draft.withPhotos(_state.value.draft.gallery().map {
                    if (it.localName != null) it.copy(imageKey = null, uploadedAt = null) else it
                })
                savedState["draft"] = Gson().toJson(retryDraft)
                _state.update { it.copy(draft = retryDraft, busy = false, requiresLogin = true, stage = null) }
            }
            catch (failure: PlanConflictException) { _state.update { it.copy(busy = false, stage = null, conflict = id != null,
                error = if (id != null) "${failure.message} Tus cambios siguen aquí. Recarga la versión actual para revisarlos de nuevo."
                    else failure.message ?: "No se pudo publicar. Conservamos tu borrador.") } }
            catch (failure: Exception) { _state.update { it.copy(busy = false, stage = null, error = failure.message ?: "No se pudo guardar. Reintenta.") } }
        }
    }
    override fun onCleared() { _state.value.draft.gallery().mapNotNull { it.localName }.forEach { photos?.store?.delete(it) }; super.onCleared() }
    class Factory(private val id: String?, private val getPlan: GetPlanUseCase, private val actions: PlanActions,
        private val photos: PlanPhotoGateway? = null) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(PlanEditorViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return PlanEditorViewModel(id, getPlan, actions, extras.createSavedStateHandle(), photos) as T
        }
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlanEditorViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return PlanEditorViewModel(id, getPlan, actions, photos = photos) as T
        }
    }
}
