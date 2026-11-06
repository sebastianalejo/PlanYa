package com.example.planya.data

import com.example.planya.data.auth.*
import com.example.planya.data.repository.planActionMessage
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CognitoFailureTest {
    @Test fun serverFailuresDoNotBlameTheUsersConnectionAndPreserveSpecificValidation() {
        assertTrue(planActionMessage(503, null, "cancelar el plan").contains("HTTP 503"))
        assertTrue(planActionMessage(503, null, "cancelar el plan").contains("laboratorio"))
        assertEquals("No hay cupos", planActionMessage(409, "No hay cupos", "unirte"))
    }
    @Test fun typedCognitoErrorsAreReadableWithoutEchoingTheResponseBody() {
        val error = cognitoFailure(HttpException(Response.error<Any>(400,
            """{"__type":"namespace#TooManyRequestsException","message":"private-debug-marker"}""".toResponseBody())))
        assertEquals("TooManyRequestsException", error.code)
        assertTrue(error.message!!.contains("Espera"))
        assertFalse(error.message!!.contains("private-debug-marker"))
    }
    @Test fun missingOrMalformedErrorBodiesStillKeepTheHttpStatus() {
        val error = cognitoFailure(HttpException(Response.error<Any>(503, "not-json".toResponseBody())))
        assertEquals(503, error.status)
        assertTrue(error.message!!.contains("no está disponible"))
    }
}
