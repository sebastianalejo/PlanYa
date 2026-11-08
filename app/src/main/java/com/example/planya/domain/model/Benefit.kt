package com.example.planya.domain.model

/** Estado del cupón. El servidor es la fuente de verdad; la app nunca marca un canje por su cuenta. */
enum class BenefitStatus(val label: String, val plural: String) {
    DISPONIBLE("Disponible", "Disponibles"),
    USADO("Usado", "Usados"),
    VENCIDO("Vencido", "Vencidos")
}

/** Cupón propio de un usuario junto con las condiciones del beneficio del local aliado. */
data class Benefit(
    val id: String,
    val ownerId: String,
    val merchantName: String,
    val title: String,
    val discount: String,
    val category: String? = null,
    val conditions: String = "",
    val limits: String? = null,
    val logoUrl: String? = null,
    /** Segundos Unix. */
    val validUntil: Long? = null,
    val status: BenefitStatus,
    val usedAt: Long? = null
) {
    /** Un cupón disponible cuya fecha ya pasó se muestra como vencido (el servidor lo vuelve a comprobar). */
    fun effectiveStatus(nowSeconds: Long): BenefitStatus =
        if (status == BenefitStatus.DISPONIBLE && validUntil != null && nowSeconds >= validUntil) BenefitStatus.VENCIDO else status
}

/** Código opaco de corta duración generado por el backend; no contiene datos del usuario. */
data class BenefitCode(val code: String, val expiresAt: Long)

data class BenefitPage(val items: List<Benefit>, val nextKey: String?)
