package com.example.planya.core.network

import com.example.planya.BuildConfig

object GooglePlacesConfig {
    // Desactivado por defecto: compilar o abrir la app no consume cuotas de Places.
    val enabled: Boolean get() = BuildConfig.GOOGLE_PLACES_ENABLED &&
        BuildConfig.GOOGLE_PLACES_API_KEY.isNotBlank()
}
