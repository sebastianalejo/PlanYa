package com.example.planya.domain.model

data class OperatorOffer(val id: String, val title: String, val merchantName: String,
    val discount: String, val conditions: String, val planIds: List<String>)
data class OperatorCatalog(val items: List<OperatorOffer>, val nextKey: String?)
data class OperatorMember(val userId: String, val name: String, val isHost: Boolean)
data class OperatorPlan(val offerId: String, val planId: String, val title: String, val members: List<OperatorMember>)
data class OperatorReceipt(val requestId: String, val benefitId: String)
class OperatorDeniedException : IllegalStateException("Acceso exclusivo para operadores autorizados. Tu cuenta no tiene este permiso.")
class OperatorUncertainException : IllegalStateException("Resultado sin confirmar. Reintenta la misma solicitud; no se mostrará éxito hasta recibir confirmación.")
class OperatorCursorException : IllegalStateException("La continuación venció. Actualiza la lista de ofertas.")

interface OperatorRepository {
    suspend fun offers(nextKey: String? = null): OperatorCatalog
    suspend fun participants(offerId: String, planId: String): OperatorPlan
    suspend fun confirm(offerId: String, planId: String, userId: String, requestId: String): OperatorReceipt
    suspend fun redeem(code: String, requestId: String): OperatorReceipt
}

/** Guarda solo hash de la operación y requestId, nunca código, nombre o JWT. */
fun interface OperatorRequestJournal { fun requestId(payload: List<String>): String }
