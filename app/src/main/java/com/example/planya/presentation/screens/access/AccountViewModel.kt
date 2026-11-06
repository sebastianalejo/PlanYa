package com.example.planya.presentation.screens.access

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthRepository
import com.example.planya.data.auth.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.example.planya.data.auth.CognitoGoogleAuth
import com.example.planya.data.auth.CognitoRequestException
import com.example.planya.data.auth.AuthSessionRequiredException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.example.planya.data.profile.*
import com.example.planya.data.photos.PlanPhotoRepository
import com.example.planya.di.NetworkModule
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationException

enum class AccessMode { LOGIN, REGISTER, VERIFY, RECOVER, RESET }
data class AccountState(val ready: Boolean = false, val session: UserSession? = null,
    val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val mode: AccessMode = AccessMode.LOGIN, val loginEvent: Int = 0, val googleInProgress: Boolean = false,
    val restoring: Boolean = false, val restoreError: String? = null, val resendAvailableAt: Long = 0L,
    val interestsSyncing: Boolean = false, val interestsError: String? = null, val preferencesVersion: Int = 0)

class AccountViewModel(application: Application, private val savedState: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    val repository = AuthRepository(application)
    private val preferences = application.getSharedPreferences("user_preferences", 0)
    private val interestsStore = DeviceInterestsStore(preferences)
    private val interestsSync = InterestsSync(interestsStore)
    private val pendingSyncs = mutableMapOf<String, Int>()
    private val _state = MutableStateFlow(AccountState(busy = savedState.contains("google_request"),
        googleInProgress = savedState.contains("google_request"), resendAvailableAt = savedState["resend_after"] ?: 0L))
    val state = _state.asStateFlow()
    fun expiredSession() {
        val old = _state.value.session?.idToken
        _state.update { it.copy(session = null, busy = true, message = "Tu sesión venció. Ingresa de nuevo para continuar.") }
        viewModelScope.launch {
            try { repository.clearExpiredSession(old) }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    init { restoreSavedSession() }
    fun retrySession() { if (!_state.value.busy && !_state.value.restoring) restoreSavedSession() }
    private fun restoreSavedSession() { viewModelScope.launch {
        val wasReady = _state.value.ready
        _state.update { it.copy(restoring = true, restoreError = null) }
        try {
            val restored = withTimeout(10_000) { repository.restore() }
            _state.update { it.copy(session = restored) }
            if (restored != null) syncPreferences(restored.userId)
            _state.update { it.copy(session = restored, ready = true,
                loginEvent = if (wasReady && restored != null) it.loginEvent + 1 else it.loginEvent) }
        }
        catch (_: AuthSessionRequiredException) { _state.update { it.copy(ready = true, session = null, message = "Tu sesión venció. Ingresa de nuevo para continuar.") } }
        catch (_: TimeoutCancellationException) { _state.update { it.copy(ready = true, restoreError = "No pudimos recuperar tu sesión a tiempo. Puedes reintentar; no borramos tus datos guardados.") } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(ready = true, restoreError = "No pudimos recuperar tu sesión. Revisa la conexión o AWS y vuelve a intentar; tus datos guardados se conservan.") } }
        finally { _state.update { it.copy(restoring = false) } }
    } }
    fun beginGoogle(): AuthorizationRequest? {
        if (_state.value.busy) return null
        return try {
            CognitoGoogleAuth.request().also {
                savedState["google_request"] = it.jsonSerializeString()
                savedState["google_started"] = System.currentTimeMillis()
                _state.update { state -> state.copy(busy = true, googleInProgress = true, error = null, message = null) }
            }
        } catch (_: Exception) {
            _state.update { it.copy(error = "No se pudo preparar el acceso con Google.") }
            null
        }
    }
    fun googleLaunchFailed() {
        clearGoogle()
        _state.update { it.copy(busy = false, error = "No se pudo abrir el navegador. Instala o habilita un navegador para continuar con Google.") }
    }
    private fun clearGoogle() {
        savedState.remove<String>("google_request"); savedState.remove<Long>("google_started")
        _state.update { it.copy(googleInProgress = false) }
    }
    fun cancelGoogle() {
        if (!_state.value.googleInProgress) return
        clearGoogle()
        _state.update { it.copy(busy = false, error = null, message = "Inicio con Google cancelado.") }
    }
    fun googleResult(data: Intent?) {
        val json = savedState.get<String>("google_request") ?: return
        val started = savedState.get<Long>("google_started") ?: 0L
        clearGoogle() // Un regreso no se puede consumir dos veces.
        _state.update { it.copy(busy = false) }
        val parsed = runCatching {
            val expected = AuthorizationRequest.jsonDeserialize(json)
            val response = data?.let(AuthorizationResponse::fromIntent)
            val error = data?.let(AuthorizationException::fromIntent)
            Triple(expected, response, error)
        }.getOrElse {
            _state.update { it.copy(error = "No se pudo leer el regreso de Google. Intenta de nuevo.") }
            return
        }
        val (expected, response, error) = parsed
        if (response == null) {
            val cancelled = error == null || (error.type == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.type &&
                error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code)
            _state.update { it.copy(message = if (cancelled) "Inicio con Google cancelado." else null,
                error = if (cancelled) null else "Google o Cognito rechazó el acceso. Revisa el proveedor, los usuarios permitidos y la URL de retorno.") }
            return
        }
        action {
            CognitoGoogleAuth.validate(expected, response, System.currentTimeMillis() - started)
            val tokens = CognitoGoogleAuth.exchange(getApplication(), response)
            val session = repository.acceptGoogle(tokens, expected.nonce!!)
            completeLogin(session)
        }
    }
    fun mode(mode: AccessMode) { if (!_state.value.busy) _state.update { it.copy(mode = mode, error = null, message = null) } }
    private fun codeSent() {
        val deadline = System.currentTimeMillis() + 60_000
        savedState["resend_after"] = deadline
        _state.update { it.copy(resendAvailableAt = deadline) }
    }
    fun submit(name: String, email: String, password: String, code: String) = action {
        when (_state.value.mode) {
            AccessMode.LOGIN -> completeLogin(repository.login(email, password))
            AccessMode.REGISTER -> {
                val confirmed = repository.register(name, email, password)
                if (!confirmed) codeSent()
                _state.update { it.copy(mode = if (confirmed) AccessMode.LOGIN else AccessMode.VERIFY,
                    message = if (confirmed) "Cuenta creada. Ya puedes ingresar." else "Revisa el código que enviamos a tu correo.") }
            }
            AccessMode.VERIFY -> { repository.confirm(email, code); _state.update { it.copy(mode = AccessMode.LOGIN, message = "Correo confirmado. Inicia sesión.") } }
            AccessMode.RECOVER -> { repository.forgot(email); codeSent(); _state.update { it.copy(mode = AccessMode.RESET, message = "Si tu cuenta permite recuperación, recibirás un código.") } }
            AccessMode.RESET -> { repository.reset(email, code, password); _state.update { it.copy(mode = AccessMode.LOGIN, message = "Contraseña actualizada. Inicia sesión.") } }
        }
    }
    fun resend(email: String) {
        if (System.currentTimeMillis() < _state.value.resendAvailableAt) return
        action {
            if (_state.value.mode == AccessMode.RESET) repository.forgot(email) else repository.resend(email)
            codeSent(); _state.update { it.copy(message = "Revisa tu correo y usa el código más reciente.") }
        }
    }
    fun logout() = action { repository.logout(); _state.update { it.copy(session = null, mode = AccessMode.LOGIN, interestsError = null, interestsSyncing = false) } }
    fun interests(): Set<String> = preferences.getStringSet("interests_${_state.value.session?.userId ?: "guest"}", emptySet()).orEmpty().toSet()
    fun interestsComplete(): Boolean = preferences.getBoolean("onboarding_${_state.value.session?.userId ?: "guest"}", false)
    fun saveInterests(values: Set<String>) {
        val id = _state.value.session?.userId ?: "guest"
        try {
            interestsStore.select(id, values)
            _state.update { it.copy(preferencesVersion = it.preferencesVersion + 1) }
            if (id != "guest") viewModelScope.launch { syncPreferences(id) }
        } catch (failure: Exception) { _state.update { it.copy(interestsError = failure.message ?: "No se pudieron guardar tus intereses.") } }
    }
    private suspend fun completeLogin(session: UserSession) {
        _state.update { it.copy(session = session) }
        syncPreferences(session.userId)
        _state.update { it.copy(loginEvent = it.loginEvent + 1) }
    }
    fun retryInterests() {
        val id = _state.value.session?.userId ?: return
        viewModelScope.launch { syncPreferences(id) }
    }
    private suspend fun syncPreferences(userId: String) {
        if (_state.value.session?.userId != userId) return
        pendingSyncs[userId] = (pendingSyncs[userId] ?: 0) + 1
        _state.update { it.copy(interestsSyncing = true, interestsError = null) }
        try {
            val photos = PlanPhotoRepository(getApplication(), NetworkModule.planPhotos, repository)
            val gateway = ProfileRepository(NetworkModule.profileApi, repository, photos, userId)
            withTimeout(5_000) { interestsSync.sync(userId, gateway) }
        } catch (_: TimeoutCancellationException) {
            if (_state.value.session?.userId == userId) _state.update { it.copy(interestsError = "Tus intereses están en este teléfono, pero AWS no respondió a tiempo. Reintenta desde Perfil.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (_state.value.session?.userId == userId) _state.update { it.copy(interestsError = "No pudimos sincronizar tus intereses con AWS. Conservamos tu selección local; puedes reintentar aquí.") }
        } finally {
            val remaining = ((pendingSyncs[userId] ?: 1) - 1).coerceAtLeast(0)
            pendingSyncs[userId] = remaining
            if (_state.value.session?.userId == userId) _state.update { it.copy(interestsSyncing = remaining > 0, preferencesVersion = it.preferencesVersion + 1) }
        }
    }
    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy || _state.value.restoring) return
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = if (failure is CognitoRequestException) {
                    if (failure.code == "UserNotConfirmedException") _state.update { it.copy(mode = AccessMode.VERIFY) }
                    if (failure.code == "PasswordResetRequiredException") _state.update { it.copy(mode = AccessMode.RECOVER) }
                    failure.message ?: "No se pudo completar el acceso."
                } else if (failure is HttpException) runCatching {
                    val json = Gson().fromJson(failure.response()?.errorBody()?.string(), JsonObject::class.java)
                    when (json.get("__type")?.asString?.substringAfterLast('#')) {
                        "UserNotConfirmedException" -> { _state.update { it.copy(mode = AccessMode.VERIFY) }; "Confirma tu correo antes de ingresar." }
                        "NotAuthorizedException" -> "Correo o contraseña incorrectos, o sesión vencida."
                        "UsernameExistsException" -> "Este correo ya tiene una cuenta. Inicia sesión o recupera tu contraseña."
                        "CodeMismatchException", "ExpiredCodeException" -> "El código es incorrecto o venció. Solicita otro."
                        "InvalidPasswordException" -> "La contraseña no cumple los requisitos de la cuenta."
                        "LimitExceededException", "TooManyRequestsException" -> "Hubo muchos intentos. Espera un momento y reintenta."
                        else -> "No se pudo completar el acceso. Revisa los datos e intenta de nuevo."
                    }
                }.getOrDefault("No se pudo completar el acceso.") else failure.message ?: "Revisa tu conexión e intenta de nuevo."
                _state.update { it.copy(error = message) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }
}
