package com.example.planya.presentation.screens.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.REPORT_MAX_TEXT
import com.example.planya.domain.model.ReportReceipt
import com.example.planya.domain.model.ReportConflictException
import com.example.planya.domain.model.ReportNetworkException
import com.example.planya.domain.model.ReportReason
import com.example.planya.domain.model.ReportRejectedException
import com.example.planya.domain.model.ReportTarget
import com.example.planya.domain.model.reportDescriptionError
import com.example.planya.domain.repository.ReportProblemRepository
import com.example.planya.domain.usecase.report.SendReportUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ReportProblemUiState(
    val target: ReportTarget,
    val reason: ReportReason? = null,
    val description: String = "",
    val sending: Boolean = false,
    /** Se activa al intentar enviar: recién ahí se marcan los campos incompletos. */
    val showErrors: Boolean = false,
    /** Error del envío; el borrador se conserva. */
    val error: String? = null,
    /** Sin respuesta del servidor: el reporte pudo guardarse; reintentar recupera el mismo. */
    val uncertain: Boolean = false,
    /** El servidor rechazó el contenido (ajeno, cerrado o ya no disponible). */
    val rejected: Boolean = false,
    val receipt: ReportReceipt? = null,
    val requiresLogin: Boolean = false
) {
    val descriptionError: String? get() = reportDescriptionError(description)
    val reasonError: String? get() = if (reason == null) "Elige un motivo" else null
    val isValid: Boolean get() = reasonError == null && descriptionError == null
    val hasDraft: Boolean get() = receipt == null && (reason != null || description.isNotBlank())
}

/**
 * Clave de idempotencia del envío: el mismo motivo y texto reutilizan el requestId (un reintento
 * recupera el mismo reporte); si el contenido cambia, se genera uno nuevo.
 */
class ReportRequestKeys(private val newRequestId: () -> String) {
    private var fingerprint: String? = null
    private var requestId: String? = null

    fun idFor(reason: ReportReason, description: String): String {
        val current = reason.name + "\u0000" + description.trim()
        val existing = requestId
        if (current == fingerprint && existing != null) return existing
        fingerprint = current
        return newRequestId().also { requestId = it }
    }

    fun reset() {
        fingerprint = null
        requestId = null
    }
}

/**
 * US-12. Un mismo contenido conserva su requestId: si la respuesta se pierde y se reintenta, el
 * servidor devuelve el mismo reporte. Si el contenido cambia, es un intento nuevo con otro requestId.
 */
class ReportProblemViewModel(
    target: ReportTarget,
    private val sendReport: SendReportUseCase,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    private val _state = MutableStateFlow(ReportProblemUiState(target))
    val state = _state.asStateFlow()

    private val requestKeys = ReportRequestKeys(newRequestId)

    fun selectReason(reason: ReportReason) {
        if (_state.value.sending || _state.value.receipt != null) return
        _state.update { it.copy(reason = reason, error = null, rejected = false) }
    }

    fun description(text: String) {
        if (_state.value.sending || _state.value.receipt != null) return
        // Se deja escribir un poco más del límite para poder corregir; el aviso indica el máximo.
        _state.update { it.copy(description = text.take(REPORT_MAX_TEXT + 100), error = null, rejected = false) }
    }

    fun send() {
        val current = _state.value
        if (current.sending || current.receipt != null) return  // bloquea el doble envío
        if (!current.isValid) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val reason = current.reason ?: return
        val text = current.description.trim()
        val requestId = requestKeys.idFor(reason, text)
        _state.update { it.copy(sending = true, showErrors = true, error = null, uncertain = false, rejected = false) }
        viewModelScope.launch {
            try {
                val receipt = sendReport(current.target, reason, text, requestId)
                _state.update { it.copy(sending = false, receipt = receipt) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthSessionRequiredException) {
                _state.update { it.copy(sending = false, requiresLogin = true,
                    error = "Tu sesión venció. Vuelve a ingresar; tu borrador se conserva.") }
            } catch (failure: ReportNetworkException) {
                // Se conserva el mismo requestId: reintentar no crea un segundo reporte.
                _state.update { it.copy(sending = false, uncertain = true, error = failure.message) }
            } catch (failure: ReportConflictException) {
                requestKeys.reset()
                _state.update { it.copy(sending = false, error = failure.message) }
            } catch (failure: ReportRejectedException) {
                _state.update { it.copy(sending = false, rejected = true, error = failure.message) }
            } catch (failure: Exception) {
                _state.update { it.copy(sending = false, error = failure.message ?: "No se pudo enviar el reporte.") }
            }
        }
    }

    fun acknowledgeLogin() {
        _state.update { it.copy(requiresLogin = false) }
    }

    class Factory(private val target: ReportTarget, private val repository: ReportProblemRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReportProblemViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return ReportProblemViewModel(target, SendReportUseCase(repository)) as T
        }
    }
}
