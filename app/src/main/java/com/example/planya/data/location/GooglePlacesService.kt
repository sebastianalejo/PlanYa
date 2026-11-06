package com.example.planya.data.location

import android.content.Context
import com.example.planya.BuildConfig
import com.example.planya.core.network.GooglePlacesConfig
import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.LocatedArea
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos

// Separado de LocationSuggestion: una predicción aún NO tiene coordenadas.
data class GooglePlacePrediction(val placeId: String, val title: String, val subtitle: String)

// Datos de Google solo en memoria. No pasar este resultado al almacén nativo o a
// DynamoDB: el guardado de placeId y la recuperación/atribución se integrarán aparte.
data class GooglePlaceSelection(val placeId: String, val area: LocatedArea)

interface GooglePlacesSource {
    suspend fun predictions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<GooglePlacePrediction>
    suspend fun select(prediction: GooglePlacePrediction): GooglePlaceSelection
    suspend fun restore(placeId: String): GooglePlaceSelection
}

class GooglePlacesService(context: Context, private val enabled: Boolean = GooglePlacesConfig.enabled) : GooglePlacesSource {
    private val applicationContext = context.applicationContext
    private val client by lazy {
        check(enabled) { "La búsqueda con Google todavía no está activada." }
        if (!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(
            applicationContext, BuildConfig.GOOGLE_PLACES_API_KEY, Locale.forLanguageTag("es-PE"))
        Places.createClient(applicationContext)
    }

    // Crear un servicio por sesión de búsqueda; renovar token tras elegir un lugar.
    private var token: AutocompleteSessionToken? = null

    override suspend fun predictions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<GooglePlacePrediction> {
        check(enabled) { "La búsqueda con Google todavía no está activada." }
        if (query.trim().length < 3) return emptyList()
        val cancellation = CancellationTokenSource()
        val session = token ?: AutocompleteSessionToken.newInstance().also { token = it }
        val request = FindAutocompletePredictionsRequest.builder().setQuery(query.trim())
            .setSessionToken(session).setCancellationToken(cancellation.token)
        if (nearbyOnly) {
            // Sesgo de proximidad, no bloqueo por país: permite escribir otra ciudad.
            (origin?.point ?: GeoPoint(-12.0464, -77.0428)).let { point ->
                request.setOrigin(LatLng(point.latitude, point.longitude))
                val latitudeDelta = 0.9
                val longitudeDelta = (latitudeDelta / cos(Math.toRadians(point.latitude)).coerceAtLeast(.01)).coerceAtMost(180.0)
                // Evitar un rectángulo invertido al cruzar el antimeridiano.
                if (point.longitude - longitudeDelta >= -180 && point.longitude + longitudeDelta <= 180) {
                    request.setLocationBias(RectangularBounds.newInstance(
                        LatLng((point.latitude - latitudeDelta).coerceAtLeast(-90.0), point.longitude - longitudeDelta),
                        LatLng((point.latitude + latitudeDelta).coerceAtMost(90.0), point.longitude + longitudeDelta)))
                }
            }
        }
        val result = withTimeout(12_000) { client.findAutocompletePredictions(request.build()).awaitCancellable(cancellation) }
        return result.autocompletePredictions.map {
            GooglePlacePrediction(it.placeId, it.getPrimaryText(null).toString(), it.getSecondaryText(null).toString())
        }
    }

    override suspend fun select(prediction: GooglePlacePrediction): GooglePlaceSelection = fetch(prediction.placeId, prediction.title)

    override suspend fun restore(placeId: String): GooglePlaceSelection = fetch(placeId, null)

    private suspend fun fetch(placeId: String, title: String?): GooglePlaceSelection {
        check(enabled) { "La búsqueda con Google todavía no está activada." }
        val cancellation = CancellationTokenSource()
        // Campos Essentials: no pedir fotos, reseñas, estrellas ni DISPLAY_NAME.
        val request = FetchPlaceRequest.builder(placeId,
            listOf(Place.Field.ID, Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS, Place.Field.ADDRESS_COMPONENTS))
            .setSessionToken(token).setCancellationToken(cancellation.token).build()
        val place = withTimeout(12_000) { client.fetchPlace(request).awaitCancellable(cancellation).place }
        token = null
        val point = place.location ?: throw IOException("Google no devolvió coordenadas para ese lugar.")
        val components = place.addressComponents?.asList().orEmpty()
        fun component(type: String) = components.firstOrNull { type in it.types }
        val street = component("route")?.name
        val number = component("street_number")?.name
        val area = LocatedArea(GeoPoint(point.latitude, point.longitude), title ?: place.formattedAddress ?: "Lugar seleccionado",
            approximate = number.isNullOrBlank(), city = component("locality")?.name,
            country = component("country")?.name, countryCode = component("country")?.shortName,
            fullAddress = place.formattedAddress, street = street, streetNumber = number,
            googlePlaceId = placeId, googleAttributions = place.attributions.orEmpty())
        return GooglePlaceSelection(placeId, area)
    }
}

private suspend fun <T> Task<T>.awaitCancellable(source: CancellationTokenSource): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { source.cancel() }
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { failure ->
            if (continuation.isActive) continuation.resumeWithException(IOException(
                if (failure is ApiException) when (failure.statusCode) {
                    PlacesStatusCodes.NETWORK_ERROR -> "No hay conexión con Google. Puedes usar la búsqueda del teléfono."
                    PlacesStatusCodes.OVER_QUERY_LIMIT -> "Google indicó un límite de uso o un problema de facturación. Usa la búsqueda del teléfono."
                    PlacesStatusCodes.REQUEST_DENIED -> "Google rechazó la configuración. Revisa la clave, las restricciones y la API."
                    else -> "No se pudo consultar Google. Puedes usar la búsqueda del teléfono."
                } else "No se pudo consultar Google. Puedes usar la búsqueda del teléfono."))
        }
        addOnCanceledListener { continuation.cancel(CancellationException("Búsqueda cancelada")) }
    }
