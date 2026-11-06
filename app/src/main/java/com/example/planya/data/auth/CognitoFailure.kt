package com.example.planya.data.auth

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException

class CognitoRequestException(val code: String, val status: Int) : IllegalStateException(cognitoMessage(code, status))

fun cognitoFailure(error: HttpException): CognitoRequestException {
    val code = runCatching {
        val json = Gson().fromJson(error.response()?.errorBody()?.string(), JsonObject::class.java)
        (json.get("__type") ?: json.get("error"))?.asString?.substringAfterLast('#')
    }.getOrNull() ?: "Http${error.code()}"
    return CognitoRequestException(code, error.code())
}

fun cognitoMessage(code: String, status: Int): String = when (code) {
    "UserNotConfirmedException" -> "Confirma tu correo antes de ingresar."
    "NotAuthorizedException", "UserNotFoundException" -> "Correo o contraseña incorrectos, o sesión vencida."
    "UsernameExistsException", "AliasExistsException" -> "Este correo ya tiene una cuenta. Inicia sesión o recupera tu contraseña."
    "CodeMismatchException" -> "El código es incorrecto. Revisa el último correo recibido."
    "ExpiredCodeException" -> "El código venció. Solicita uno nuevo."
    "InvalidPasswordException" -> "La contraseña no cumple los requisitos de la cuenta."
    "LimitExceededException", "TooManyRequestsException", "slow_down" -> "Hubo muchos intentos. Espera un momento y reintenta; no borraremos tu sesión por este error."
    "InvalidParameterException", "InvalidLambdaResponseException", "InvalidUserPoolConfigurationException", "invalid_client", "unauthorized_client" ->
        "Cognito no permite este flujo o falta completar su configuración. Revisa el cliente de PlanYa en AWS."
    "CodeDeliveryFailureException" -> "No se pudo enviar el correo. Reintenta más tarde y revisa la configuración de correo en AWS."
    "PasswordResetRequiredException" -> "Tu cuenta necesita restablecer la contraseña. Usa Olvidé mi contraseña."
    "invalid_grant", "RefreshTokenReuseException" -> "Tu sesión venció. Vuelve a ingresar."
    else -> if (status >= 500) "El servicio de acceso no está disponible. Reintenta sin borrar tus datos."
        else "No se pudo completar el acceso. Revisa los datos e intenta de nuevo."
}

interface SessionStore {
    suspend fun read(): UserSession?
    suspend fun write(value: UserSession)
    suspend fun clear()
}
