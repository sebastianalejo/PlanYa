package com.example.planya.data.mapper

import com.example.planya.data.remote.dto.BenefitCodeDto
import com.example.planya.data.remote.dto.BenefitDto
import com.example.planya.domain.model.Benefit
import com.example.planya.domain.model.BenefitCode
import com.example.planya.domain.model.BenefitStatus

/** Falla si el cupón es ajeno o está incompleto: nunca se muestra algo que no sea del usuario en sesión. */
fun BenefitDto.toDomain(expectedUserId: String): Benefit {
    check(userId == expectedUserId) { "El beneficio recibido no corresponde a tu sesión." }
    val state = BenefitStatus.entries.firstOrNull { it.name == status?.uppercase() }
    val benefitId = id?.takeIf { it.isNotBlank() }
    val benefitTitle = title?.takeIf { it.isNotBlank() }
    val merchant = merchantName?.takeIf { it.isNotBlank() }
    check(benefitId != null && benefitTitle != null && merchant != null && state != null) { "El servidor devolvió un beneficio incompleto." }
    return Benefit(
        id = benefitId, ownerId = expectedUserId, merchantName = merchant, title = benefitTitle,
        discount = discount.orEmpty(), category = category?.takeIf { it.isNotBlank() },
        conditions = conditions.orEmpty(), limits = limits?.takeIf { it.isNotBlank() },
        logoUrl = logoUrl?.takeIf { it.startsWith("https://") },
        validUntil = validUntil?.takeIf { it > 0 }, status = state, usedAt = usedAt?.takeIf { it > 0 }
    )
}

fun BenefitCodeDto.toDomain(): BenefitCode {
    val value = code?.takeIf { it.isNotBlank() }
    val expiry = expiresAt?.takeIf { it > 0 }
    check(value != null && expiry != null) { "El servidor no devolvió un código válido." }
    return BenefitCode(value, expiry)
}
