package com.example.planya.data.operator

import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.domain.model.*
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

class OperatorRepositoryImpl(private val api: OperatorApi, private val userId: String,
    private val token: suspend () -> String) : OperatorRepository {
    override suspend fun offers(nextKey: String?): OperatorCatalog = request(paging = nextKey != null) {
        val data = api.offers(it, nextKey).payload()
        identity(data.userId)
        OperatorCatalog(checkNotNull(data.items).map { offer ->
            val plans = checkNotNull(offer.planIds)
            check(plans.size <= 32 && plans.all(::uuid)) { "La oferta tiene encuentros no válidos." }
            OperatorOffer(required(offer.id), required(offer.title), required(offer.merchantName),
                required(offer.discount), required(offer.conditions), plans.distinct())
        }, data.nextKey?.takeIf(String::isNotBlank))
    }
    override suspend fun participants(offerId: String, planId: String): OperatorPlan = request(attendance = true) {
        val data = api.participants(it, offerId, planId).payload()
        identity(data.userId)
        check(data.offerId == offerId && data.planId == planId) { "La respuesta corresponde a otro encuentro." }
        OperatorPlan(offerId, planId, required(data.title), checkNotNull(data.members).map { member ->
            OperatorMember(required(member.userId), required(member.name), member.isHost == true)
        }.filter { member -> member.userId != userId }.distinctBy { member -> member.userId })
    }
    override suspend fun confirm(offerId: String, planId: String, userId: String, requestId: String): OperatorReceipt = request(writing = true, attendance = true) {
        val result = api.confirm(it, AttendanceBody(offerId, planId, userId, requestId)).payload()
        check(result.attendanceConfirmed == true) { "El servidor no confirmó la asistencia." }
        receipt(result, requestId)
    }
    override suspend fun redeem(code: String, requestId: String): OperatorReceipt = request(writing = true) {
        val result = api.redeem(it, RedemptionBody(code, requestId)).payload()
        check(result.status == "USADO") { "El servidor no confirmó el canje." }
        receipt(result, requestId)
    }
    private fun receipt(value: OperatorReceiptDto, requestId: String): OperatorReceipt {
        check(value.requestId == requestId) { "La confirmación no corresponde a la solicitud." }
        return OperatorReceipt(requestId, required(value.benefitId))
    }
    private fun identity(actual: String?) { if (actual != userId) throw AuthSessionRequiredException() }
    private fun <T> OperatorResponse<T>.payload(): T { check(success == true) { "El servidor no confirmó la operación." }; return checkNotNull(data) }
    private fun required(value: String?): String = checkNotNull(value?.takeIf(String::isNotBlank)) { "El servidor devolvió datos incompletos." }
    private fun uuid(value: String?) = value != null && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private suspend fun <T> request(writing: Boolean = false, paging: Boolean = false, attendance: Boolean = false, block: suspend (String) -> T): T {
        try { return block("Bearer ${token()}") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: HttpException) {
            when (failure.code()) {
                401 -> throw AuthSessionRequiredException()
                403 -> throw OperatorDeniedException()
                400, 409 -> if (paging) throw OperatorCursorException() else throw IllegalStateException("La solicitud o sus datos cambiaron. Actualiza antes de volver a intentarlo.")
                404 -> throw IllegalStateException("El servicio de operadores aún no está disponible o el código ya no existe.")
                // El servidor también devuelve 410 ANTES del inicio. No afirmar
                // que venció ni inferir el horario usando el reloj del teléfono.
                410 -> throw IllegalStateException(when {
                    attendance -> "No se puede confirmar asistencia en este horario. El encuentro puede no haber empezado, haber terminado o la oferta no estar vigente."
                    writing -> "El beneficio o su código ya venció. Solicita un código vigente."
                    else -> "La oferta no está vigente. Actualiza las ofertas."
                })
                in 500..599 -> if (writing) throw OperatorUncertainException() else throw IllegalStateException("No se pudo consultar el servicio. Reintenta.")
                else -> throw IllegalStateException("No se pudo completar la operación.")
            }
        } catch (_: IOException) {
            if (writing) throw OperatorUncertainException()
            throw IllegalStateException("Sin conexión. Reintenta para consultar los datos del servidor.")
        }
    }
}
