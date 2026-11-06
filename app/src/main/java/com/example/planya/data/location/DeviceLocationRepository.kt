package com.example.planya.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.example.planya.domain.model.*
import kotlinx.coroutines.*
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos

interface LocationRepository {
    suspend fun current(): LocatedArea
    suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<LocationSuggestion>
    suspend fun addressAt(point: GeoPoint): LocatedArea? = null
}

class DeviceLocationRepository(private val context: Context) : LocationRepository {
    private val cache = linkedMapOf<String, List<LocationSuggestion>>()
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun addressAt(point: GeoPoint): LocatedArea? {
        val address = reverse(point) ?: return null
        return LocatedArea(point, address.areaLabel(), address.subThoroughfare.isNullOrBlank(),
            address.locality ?: address.subAdminArea, address.countryName, address.countryCode,
            address.getAddressLine(0), address.thoroughfare, address.subThoroughfare,
            manuallySelected = true)
    }

    @SuppressLint("MissingPermission")
    override suspend fun current(): LocatedArea {
        val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        check(precise || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { "Permite la ubicación o busca una zona." }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        check(LocationManagerCompat.isLocationEnabled(manager)) { "Activa la ubicación del teléfono o busca una zona." }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { (precise || it != LocationManager.GPS_PROVIDER) && manager.isProviderEnabled(it) }
        check(providers.isNotEmpty()) { "No hay un proveedor de ubicación disponible. Puedes buscar una zona." }
        val location = withTimeoutOrNull(20_000) {
            providers.firstNotNullOfOrNull { provider ->
                withTimeoutOrNull(9_000) {
                    suspendCancellableCoroutine<android.location.Location?> { continuation ->
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        LocationManagerCompat.getCurrentLocation(manager, provider, signal,
                            ContextCompat.getMainExecutor(context)) { value ->
                            if (continuation.isActive) continuation.resume(value)
                        }
                    }
                }
            }
        } ?: error("No se pudo obtener tu ubicación. Reintenta o busca una zona.")
        val point = GeoPoint(location.latitude, location.longitude)
        val address = try { reverse(point) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
        return LocatedArea(point, address?.areaLabel() ?: "Mi ubicación actual",
            !precise || location.accuracy > 200, address?.locality ?: address?.subAdminArea,
            address?.countryName, address?.countryCode, address?.getAddressLine(0),
            address?.thoroughfare, address?.subThoroughfare, location.accuracy)
    }

    override suspend fun suggestions(query: String, origin: LocatedArea?, nearbyOnly: Boolean): List<LocationSuggestion> {
        val text = query.trim()
        if (text.length < 3) return emptyList()
        check(Geocoder.isPresent()) { "Este dispositivo no tiene búsqueda de direcciones. Puedes usar tu ubicación." }
        val contextual = LocationSearchPolicy.contextualQuery(text, origin, nearbyOnly)
        val key = "$contextual|${origin?.point}|${origin?.countryCode}|$nearbyOnly"
        synchronized(cache) { cache[key] }?.let { return it }
        val bounds = if (nearbyOnly && origin != null) SearchBounds.around(origin.point) else null
        val addresses = forward(contextual, bounds)
        val candidates = addresses.mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
            val point = runCatching { GeoPoint(address.latitude, address.longitude) }.getOrNull()
                ?: return@mapNotNull null
            val streetLine = listOfNotNull(address.thoroughfare, address.subThoroughfare).joinToString(" ").ifBlank { null }
            val title = streetLine ?: address.featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                ?: address.thoroughfare ?: address.subLocality ?: address.locality ?: address.areaLabel()
            val subtitle = listOfNotNull(address.getAddressLine(0), address.subLocality, address.locality,
                address.adminArea, address.countryName).filter { it.isNotBlank() && it != title }
                .distinct().joinToString(", ")
            val label = listOf(title, address.locality ?: address.adminArea.orEmpty())
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            val searchable = listOfNotNull(title, subtitle, address.getAddressLine(0)).joinToString(" ")
            LocationSuggestion(title, subtitle, LocatedArea(point, label, address.subThoroughfare.isNullOrBlank(),
                address.locality ?: address.subAdminArea, address.countryName, address.countryCode,
                address.getAddressLine(0), address.thoroughfare, address.subThoroughfare), searchable)
        }
        val result = LocationSearchPolicy.rank(candidates, text, origin, nearbyOnly)
        synchronized(cache) {
            // No guardar búsquedas vacías: un fallo temporal no debe impedir reintentar.
            if (result.isNotEmpty()) {
                if (cache.size >= 24) cache.remove(cache.keys.first())
                cache[key] = result
            }
        }
        return result
    }

    private fun Address.areaLabel(): String = listOfNotNull(subLocality ?: locality, adminArea)
        .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { "Mi ubicación actual" }

    private suspend fun reverse(point: GeoPoint): Address? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(8_000) {
            val geocoder = Geocoder(context, Locale.forLanguageTag("es-PE"))
            if (Build.VERSION.SDK_INT >= 33) asyncGeocode { listener ->
                geocoder.getFromLocation(point.latitude, point.longitude, 1, listener)
            }.firstOrNull()
            else runInterruptible(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
            }
        }
    }

    private suspend fun forward(query: String, bounds: SearchBounds?): List<Address> {
        return withTimeoutOrNull(12_000) {
            val geocoder = Geocoder(context, Locale.forLanguageTag("es-PE"))
            if (Build.VERSION.SDK_INT >= 33) asyncGeocode { listener ->
                if (bounds == null) geocoder.getFromLocationName(query, 8, listener)
                else geocoder.getFromLocationName(query, 8, bounds.south, bounds.west,
                    bounds.north, bounds.east, listener)
            } else runInterruptible(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                if (bounds == null) geocoder.getFromLocationName(query, 8).orEmpty()
                else geocoder.getFromLocationName(query, 8, bounds.south, bounds.west,
                    bounds.north, bounds.east).orEmpty()
            }
        } ?: throw IOException("La búsqueda tardó demasiado. Inténtalo otra vez.")
    }

    @androidx.annotation.RequiresApi(33)
    private suspend fun asyncGeocode(start: (Geocoder.GeocodeListener) -> Unit): List<Address> =
        suspendCancellableCoroutine { continuation ->
            start(object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses)
                }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("No se pudo buscar. Revisa Internet y reintenta."))
                }
            })
        }

    private data class SearchBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
        companion object {
            fun around(point: GeoPoint): SearchBounds {
                val lat = 1.0
                val lon = (1 / cos(Math.toRadians(point.latitude)).coerceAtLeast(.01)).coerceAtMost(180.0)
                // Al cruzar el antimeridiano se amplía el rectángulo; la regla de distancia sigue filtrando.
                val crossesDateLine = point.longitude - lon < -180 || point.longitude + lon > 180
                return SearchBounds((point.latitude - lat).coerceAtLeast(-90.0),
                    if (crossesDateLine) -180.0 else point.longitude - lon,
                    (point.latitude + lat).coerceAtMost(90.0),
                    if (crossesDateLine) 180.0 else point.longitude + lon)
            }
        }
    }
}
