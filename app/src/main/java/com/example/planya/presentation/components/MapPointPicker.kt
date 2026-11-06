package com.example.planya.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.planya.BuildConfig
import com.example.planya.core.network.GooglePlacesConfig
import com.example.planya.data.location.*
import com.example.planya.domain.model.*
import com.example.planya.ui.theme.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Un mapa grande, una búsqueda y una sola confirmación. Sin Routes ni Geocoding API.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPointPicker(origin: GeoPoint?, onSelect: (LocatedArea) -> Unit, onDismiss: () -> Unit,
    mapContent: (@Composable (GeoPoint, GeoPoint?, (GeoPoint) -> Unit) -> Unit)? = null,
    source: LocationRepository? = null, googleSource: GooglePlacesSource? = null,
    allowGoogle: Boolean = true) {
    val context = LocalContext.current
    val repository = source ?: remember { DeviceLocationRepository(context.applicationContext) }
    val google = googleSource ?: remember { GooglePlacesService(context.applicationContext) }
    val useGoogle = allowGoogle && (googleSource != null || GooglePlacesConfig.enabled) && mapContent == null
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var label by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var area by remember { mutableStateOf<LocatedArea?>(null) }
    var addressBusy by remember { mutableStateOf(false) }
    var gpsBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var predictions by remember { mutableStateOf<List<GooglePlacePrediction>>(emptyList()) }
    var alternatives by remember { mutableStateOf<List<LocationSuggestion>>(emptyList()) }
    var loaded by remember { mutableStateOf(mapContent != null) }
    var slow by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var lookup by remember { mutableIntStateOf(0) }
    val point = latitude?.let { lat -> longitude?.let { GeoPoint(lat, it) } }
    val start = origin ?: GeoPoint(-12.0464, -77.0428)
    val available = mapContent != null || BuildConfig.GOOGLE_MAPS_ENABLED
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), 16f)
    }
    fun choose(value: GeoPoint) {
        latitude = value.latitude; longitude = value.longitude
        area = null; label = ""; error = null; query = ""; lookup++
        keyboard?.hide()
    }
    suspend fun center(value: GeoPoint) {
        if (mapContent == null) camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(value.latitude, value.longitude), 17f))
    }
    fun locate() {
        if (gpsBusy) return
        gpsBusy = true; error = null
        scope.launch {
            try {
                val found = repository.current()
                choose(found.point); area = found; label = found.displayAddress
                center(found.point)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "No pudimos ubicarte. Busca o toca el mapa." }
            finally { gpsBusy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else error = "Puedes buscar o tocar el mapa sin dar permiso de ubicación."
    }
    // Al abrir, se centra en la dirección elegida o usa GPS solo si ya se concedió permiso.
    LaunchedEffect(Unit) {
        if (mapContent == null && point == null) {
            if (origin != null) choose(origin)
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        }
    }
    LaunchedEffect(loaded, reload) { if (!loaded && available) { delay(12_000); slow = true } }
    // Cancelar la geocodificación anterior evita mostrar la calle de un pin viejo.
    LaunchedEffect(point, lookup) {
        val chosen = point ?: return@LaunchedEffect
        if (mapContent != null && source == null) return@LaunchedEffect
        if (area != null) return@LaunchedEffect
        addressBusy = true
        try {
            delay(450)
            val found = repository.addressAt(chosen)
            area = found?.copy(point = chosen, manuallySelected = true)
            label = found?.displayAddress.orEmpty()
            if (found == null) error = "No encontramos la calle de este punto. Escribe una referencia para confirmarlo."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "No se pudo consultar la calle. Puedes escribir la dirección o referencia." }
        finally { addressBusy = false }
    }
    LaunchedEffect(query) {
        predictions = emptyList(); alternatives = emptyList(); searching = false
        if (query.trim().length < 3) return@LaunchedEffect
        searching = true
        try {
            delay(900)
            val nearby = area ?: LocatedArea(point ?: start, "Zona del mapa", true)
            if (useGoogle) {
                try { predictions = google.predictions(query, nearby, true) }
                catch (failure: Exception) {
                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                    alternatives = repository.suggestions(query, nearby, true)
                }
            } else alternatives = repository.suggestions(query, nearby, true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "No pudimos buscar. Puedes elegir el punto en el mapa." }
        finally { searching = false }
    }
    fun use(found: LocatedArea) {
        choose(found.point); area = found; label = found.displayAddress
        scope.launch { center(found.point) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(containerColor = ScreenBg, modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            topBar = { TopAppBar(title = { Text("Ubicación del encuentro", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cerrar mapa") } }) },
            bottomBar = {
                Surface(shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (addressBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (point != null) {
                            OutlinedTextField(label, { label = it.take(240) }, label = { Text("Dirección del punto") },
                                modifier = Modifier.fillMaxWidth().testTag("map-address"), enabled = !addressBusy,
                                maxLines = 2, shape = RoundedCornerShape(14.dp),
                                supportingText = { Text(if (area?.streetNumber != null) "Comprueba la calle y el número antes de confirmar."
                                    else "Revisa el acceso. El número exacto puede no estar disponible.") })
                            if (area?.googlePlaceId != null) GoogleMapsAttribution(area!!.googleAttributions)
                        } else Text("Toca el mapa, busca una dirección o usa tu ubicación actual.", style = MaterialTheme.typography.bodyMedium)
                        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            val chosen = point ?: return@Button
                            val found = area
                            // Una edición propia ya no se presenta como dirección certificada.
                            val unchanged = found != null && label.trim() == found.displayAddress
                            onSelect(if (unchanged) found!!.copy(point = chosen)
                                else if (found?.googlePlaceId != null) found.copy(point = chosen, fullAddress = label.trim(),
                                    approximate = true, streetNumber = null)
                                else LocatedArea(chosen, label.trim(), true, fullAddress = label.trim(), manuallySelected = true))
                        }, enabled = point != null && label.trim().length in 3..240 && loaded && !addressBusy && !gpsBusy && !resolving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo), shape = RoundedCornerShape(50)) {
                            Text("Elegir este punto")
                        }
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(query, { query = it; error = null }, placeholder = { Text("Buscar calle, número o lugar") },
                    enabled = !resolving,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("map-search"),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(50))
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (predictions.isNotEmpty() || alternatives.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(predictions, key = { it.placeId }) { prediction ->
                            TextButton(enabled = !resolving, onClick = {
                                resolving = true
                                scope.launch {
                                    try { use(google.select(prediction).area) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "No se pudo abrir ese lugar. Reintenta o toca el mapa." }
                                    finally { resolving = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) { Text(prediction.title); Text(prediction.subtitle, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        items(alternatives) { suggestion ->
                            TextButton(onClick = { use(suggestion.area) }, modifier = Modifier.fillMaxWidth()) { Text(suggestion.area.displayAddress) }
                        }
                    }
                    if (predictions.isNotEmpty()) GoogleMapsAttribution()
                }
                Box(Modifier.fillMaxWidth().weight(1f).testTag("point-map"), contentAlignment = Alignment.Center) {
                    if (mapContent != null) mapContent(start, point, ::choose)
                    else if (available) key(reload) {
                        GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera,
                            properties = MapProperties(isMyLocationEnabled = false, isIndoorEnabled = false),
                            uiSettings = MapUiSettings(mapToolbarEnabled = false, myLocationButtonEnabled = false),
                            onMapLoaded = { loaded = true; slow = false }, onMapClick = { if (!resolving && !gpsBusy) choose(GeoPoint(it.latitude, it.longitude)) }) {
                            point?.let { Marker(state = rememberUpdatedMarkerState(LatLng(it.latitude, it.longitude))) }
                        }
                    } else Text("El mapa no está configurado. Vuelve al buscador.", Modifier.padding(24.dp))
                    if (available && !loaded && !slow) CircularProgressIndicator()
                    FilledTonalButton(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = Indigo),
                        enabled = !gpsBusy && !resolving, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                        Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(6.dp))
                        Text(if (gpsBusy) "Ubicando…" else "Mi ubicación")
                    }
                    if (origin == null && point == null) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        color = Color.White, shape = RoundedCornerShape(12.dp)) {
                        Text("Vista inicial de Lima, no tu ubicación detectada", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    if (slow && !loaded) Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(12.dp)) {
                        TextButton(onClick = { slow = false; reload++ }) { Text("Reintentar carga del mapa") }
                    }
                }
            }
        }
    }
}
