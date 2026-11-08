package com.example.planya.data

import com.example.planya.data.mapper.toDomain
import com.example.planya.data.remote.dto.BenefitCodeDto
import com.example.planya.data.remote.dto.BenefitDto
import com.example.planya.data.repository.benefitMessage
import com.example.planya.domain.model.BenefitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BenefitMapperTest {
    private fun dto(userId: String? = "u1", status: String? = "DISPONIBLE", validUntil: Long? = 2_000L, logo: String? = "https://cdn/x.png") =
        BenefitDto(id = "c1", userId = userId, merchantName = "Café Local", title = "15% en café", discount = "15%",
            category = "Gastronomía", conditions = "Una vez por mesa", limits = null, logoUrl = logo, validUntil = validUntil,
            status = status, usedAt = null)

    @Test fun ownCouponIsMapped() {
        val benefit = dto().toDomain("u1")
        assertEquals("c1", benefit.id)
        assertEquals(BenefitStatus.DISPONIBLE, benefit.status)
        assertNull(benefit.limits)
    }

    @Test fun foreignCouponIsRejected() {
        assertThrows(IllegalStateException::class.java) { dto(userId = "otro").toDomain("u1") }
    }

    @Test fun unknownStatusIsRejected() {
        assertThrows(IllegalStateException::class.java) { dto(status = "REGALADO").toDomain("u1") }
    }

    @Test fun insecureLogoIsDropped() {
        assertNull(dto(logo = "http://cdn/x.png").toDomain("u1").logoUrl)
    }

    @Test fun availableCouponPastDeadlineShowsAsExpired() {
        val benefit = dto(validUntil = 1_000L).toDomain("u1")
        assertEquals(BenefitStatus.VENCIDO, benefit.effectiveStatus(1_000L))
        assertEquals(BenefitStatus.DISPONIBLE, benefit.effectiveStatus(999L))
    }

    @Test fun usedCouponStaysUsedEvenIfExpired() {
        val benefit = dto(status = "USADO", validUntil = 1_000L).toDomain("u1")
        assertEquals(BenefitStatus.USADO, benefit.effectiveStatus(5_000L))
    }

    @Test fun incompleteCodeIsRejected() {
        assertThrows(IllegalStateException::class.java) { BenefitCodeDto(code = " ", expiresAt = 10L).toDomain() }
        assertThrows(IllegalStateException::class.java) { BenefitCodeDto(code = "abc", expiresAt = null).toDomain() }
    }

    @Test fun serverErrorsAreExplained() {
        assertEquals("Este beneficio ya fue utilizado.", benefitMessage(409, null))
        assertEquals("Este beneficio o su código ya venció.", benefitMessage(410, null))
        assertEquals("mensaje", benefitMessage(409, "mensaje"))
    }
}
