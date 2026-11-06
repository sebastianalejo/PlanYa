package com.example.planya.core.network

object AuthConfig {
    const val REGION = "us-east-1"
    // Identificadores públicos de un User Pool Cognito y un cliente móvil SIN secreto.
    const val USER_POOL_ID = "us-east-1_G3sywIYqT"
    const val CLIENT_ID = "2tvt69ic49crhu93ank9gkklv0"
    const val DOMAIN = "https://us-east-1g3sywiyqt.auth.us-east-1.amazoncognito.com"
    const val REDIRECT_URI = "com.example.planya://auth/callback"
    val issuer get() = "https://cognito-idp.$REGION.amazonaws.com/$USER_POOL_ID"
    val configured get() = USER_POOL_ID.isNotBlank() && CLIENT_ID.isNotBlank()
    val googleConfigured get() = configured && DOMAIN.startsWith("https://")
}
