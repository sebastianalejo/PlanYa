package com.example.planya.data

import com.example.planya.domain.model.*
import com.example.planya.presentation.screens.operator.OperatorViewModel
import org.junit.Assert.*
import org.junit.Test

/** US-14: lo que se lee de un QR solo prepara una revisión; nunca envía ni consume nada por sí solo. */
class OperatorScanTest {
    private val code12 = "A1B2C3D4E5F6"
    private val code32 = "0123456789ABCDEF0123456789ABCDEF"

    private class FakeRepository : OperatorRepository {
        var redeemCalls = 0
        override suspend fun offers(nextKey: String?) = OperatorCatalog(emptyList(), null)
        override suspend fun participants(offerId: String, planId: String) = error("no se usa")
        override suspend fun confirm(offerId: String, planId: String, userId: String, requestId: String) = error("no se usa")
        override suspend fun redeem(code: String, requestId: String): OperatorReceipt { redeemCalls++; return OperatorReceipt(requestId, "coupon") }
    }
    private class CountingJournal : OperatorRequestJournal {
        var calls = 0
        override fun requestId(payload: List<String>): String { calls++; return "request-${payload.joinToString("-")}" }
    }
    private val repository = FakeRepository()
    private val journal = CountingJournal()
    private fun model() = OperatorViewModel(repository, journal)

    @Test fun acceptsOnlyOpaqueUppercaseHexOfTwelveOrThirtyTwo() {
        assertEquals(code12, parseOperatorCode(code12))
        assertEquals(code32, parseOperatorCode(code32))
        listOf(null, "", "a1b2c3d4e5f6", "A1B2C3D4E5F", "A1B2C3D4E5F6A", "A1B2C3D4E5GZ", " A1B2C3D4E5F6", "A1B2C3D4E5F6\n",
            "https://planya.example/canje/A1B2C3D4E5F6", "WIFI:S:red;T:WPA;P:clave;;", "tel:+51999999999").forEach {
            assertNull("Debe rechazar: $it", parseOperatorCode(it))
        }
    }

    @Test fun validScanOnlyPreparesAReviewWithoutRedeeming() {
        val vm = model()
        assertTrue(vm.scanCode(code12))
        val state = vm.state.value
        assertEquals(code12, state.pending?.code)
        assertNull(state.scanError)
        assertFalse(state.writing)
        assertEquals(0, repository.redeemCalls)
    }

    @Test fun repeatedFramesOfTheSameQrDoNotCreateNewRequests() {
        val vm = model()
        assertTrue(vm.scanCode(code12))
        repeat(29) { assertFalse(vm.scanCode(code12)) }
        assertEquals(1, journal.calls)
        assertEquals(0, repository.redeemCalls)
        assertEquals("request-redemption-$code12", vm.state.value.pending?.requestId)
    }

    @Test fun anotherQrIsIgnoredWhileOneIsUnderReview() {
        val vm = model()
        vm.scanCode(code12)
        vm.scanCode(code32)
        assertEquals(code12, vm.state.value.pending?.code)
        assertEquals(1, journal.calls)
    }

    @Test fun foreignQrIsRejectedWithoutAnyRequest() {
        val vm = model()
        assertFalse(vm.scanCode("https://example.com/promo"))
        val state = vm.state.value
        assertNull(state.pending)
        assertEquals("", state.code)
        assertNotNull(state.scanError)
        assertEquals(0, journal.calls)
        assertEquals(0, repository.redeemCalls)
    }

    @Test fun rescanClearsTheReadAndAllowsANewOne() {
        val vm = model()
        vm.scanCode(code12)
        vm.rescan()
        assertNull(vm.state.value.pending)
        assertEquals("", vm.state.value.code)
        assertTrue(vm.scanCode(code32))
        assertEquals(code32, vm.state.value.pending?.code)
    }

    @Test fun validScanClearsAPreviousRejection() {
        val vm = model()
        vm.scanCode("texto cualquiera")
        assertNotNull(vm.state.value.scanError)
        vm.scanCode(code12)
        assertNull(vm.state.value.scanError)
    }

    @Test fun manualReviewUsesTheSameValidation() {
        val vm = model()
        vm.code("a1b2c3d4e5f6")
        vm.reviewCode()
        assertEquals(code12, vm.state.value.pending?.code)
        val bad = model()
        bad.code("123")
        bad.reviewCode()
        assertNull(bad.state.value.pending)
        assertNotNull(bad.state.value.error)
    }
}
