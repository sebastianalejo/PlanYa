package com.example.planya.presentation.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.data.location.DeviceLocationRepository
import com.example.planya.data.location.LocationRepository
import com.example.planya.data.location.GooglePlacePrediction
import com.example.planya.data.location.GooglePlacesService
import com.example.planya.data.location.GooglePlacesSource
import com.example.planya.core.network.GooglePlacesConfig
import com.example.planya.domain.model.*
import com.example.planya.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPicker(
    currentArea: LocatedArea?, onSelect: (LocatedArea) -> Unit, onDismiss: () -> Unit,
    // Permite comprobar la interfaz sin depender de la red ni crear datos en AWS.
    source: LocationRepository? = null,
    googleSource: GooglePlacesSource? = null,
    allowGoogle: Boolean = true,
    savedGooglePlaceId: String? = null
) {
    val context = LocalContext.current
    val repository = source ?: remember { DeviceLocationRepository(context.applicationContext) }
    val google = googleSource ?: remember { GooglePlacesService(context.applicationContext) }
    val googleAvailable = allowGoogle && (googleSource != null || GooglePlacesConfig.enabled)
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val nearbyOnly = true
    var gpsBusy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var gpsError by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<LocationSuggestion>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    var searched by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LocatedArea?>(null) }
    var reference by rememberSaveable { mutableStateOf("") }
    // Un solo buscador; la alternativa nativa se utiliza internamente si falla.
    val useGoogle = googleAvailable && (source == null || googleSource != null)
    var fallbackNotice by remember { mutableStateOf<String?>(null) }
    var predictions by remember { mutableStateOf<List<GooglePlacePrediction>>(emptyList()) }
    var resolving by remember { mutableStateOf(false) }
    var showMap by rememberSaveable { mutableStateOf(false) }

    fun resolve(prediction: GooglePlacePrediction? = null) {
        if (resolving) return
        resolving = true
        searchError = null
        keyboard?.hide()
        scope.launch {
            try {
                val selection = if (prediction != null) google.select(prediction)
                    else google.restore(savedGooglePlaceId ?: error("No hay una dirección guardada."))
                currentCoroutineContext().ensureActive()
                pending = selection.area
                reference = ""
            } catch (_: TimeoutCancellationException) {
                searchError = "Google tardó demasiado. Reintenta o usa la búsqueda del teléfono."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { searchError = failure.message ?: "No se pudo recuperar esa dirección." }
            finally { resolving = false }
        }
    }

    fun select(area: LocatedArea) {
        keyboard?.hide()
        onSelect(area)
        onDismiss()
    }

    fun locate() {
        if (gpsBusy || resolving) return
        gpsBusy = true
        gpsError = null
        keyboard?.hide()
        scope.launch {
            try { pending = repository.current(); reference = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { gpsError = failure.message ?: "No pudimos obtener tu ubicación. Puedes buscar una zona." }
            finally { gpsBusy = false }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate()
        else gpsError = "Puedes seguir buscando sin dar permiso. Para usar el GPS, permite la ubicación en los ajustes de PlanYa."
    }

    // Al cambiar el texto se cancela la consulta anterior. Una respuesta antigua
    // nunca reemplaza los resultados de la búsqueda que se está escribiendo.
    LaunchedEffect(query, nearbyOnly, currentArea, retry, gpsBusy, useGoogle, pending) {
        suggestions = emptyList()
        predictions = emptyList()
        searchError = null
        searched = false
        searching = false
        if (query.trim().length < 3 || gpsBusy || pending != null) return@LaunchedEffect
        searching = true
        try {
            delay(if (useGoogle) 900 else 500)
            fallbackNotice = null
            if (useGoogle) {
                try { predictions = google.predictions(query, currentArea, nearbyOnly) }
                catch (cancelled: TimeoutCancellationException) {
                    suggestions = repository.suggestions(query, currentArea, nearbyOnly)
                    fallbackNotice = "Mostrando resultados alternativos; Google no respondió."
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    suggestions = repository.suggestions(query, currentArea, nearbyOnly)
                    fallbackNotice = "Mostrando resultados alternativos; Google no respondió."
                }
            } else suggestions = repository.suggestions(query, currentArea, nearbyOnly)
            currentCoroutineContext().ensureActive()
            searched = true
        } catch (_: TimeoutCancellationException) {
            searchError = "La búsqueda tardó demasiado. Reintenta o usa la búsqueda del teléfono."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            searchError = failure.message ?: "No pudimos buscar lugares. Revisa Internet y reintenta."
        } finally { searching = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = ScreenBg, dragHandle = { BottomSheetDefaults.DragHandle(color = OutlineSoft) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding()
            .padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (pending == null) "Elige tu ubicación" else "Confirma la dirección", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                    Text(if (pending == null) "Encuentra planes cerca de ti" else "Aún no se ha cambiado tu ubicación",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cerrar ubicación") }
            }
            Spacer(Modifier.height(18.dp))
            if (pending != null) {
                val place = pending!!
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(place.fullAddress ?: place.label, style = MaterialTheme.typography.titleMedium, color = IndigoDark)
                        if (place.googlePlaceId != null) {
                            GoogleMapsAttribution(place.googleAttributions)
                            Text("Se conservará el identificador del lugar. Al reabrir, podrás recuperar la dirección con Google.",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        Text(if (place.manuallySelected) "Punto elegido en el mapa. Comprueba que la dirección corresponda al acceso que buscas."
                            else if (place.street != null && place.streetNumber != null)
                            "El buscador encontró calle y número. Comprueba que corresponda al lugar."
                            else "El buscador solo confirmó una calle o zona. Para una dirección con número, vuelve a buscarla completa.",
                            style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        place.accuracyMeters?.let { Text("Precisión del teléfono: aproximadamente ${it.toInt()} m",
                            style = MaterialTheme.typography.bodySmall, color = TextMuted) }
                    }
                    item {
                        OutlinedTextField(value = reference, onValueChange = { reference = it.take(160) },
                            label = { Text("Referencia o departamento (opcional)") },
                            modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    }
                    item {
                        Button(onClick = { select(place.copy(reference = reference.trim().ifBlank { null })) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (place.manuallySelected) "Usar este punto" else if (place.streetNumber != null && place.street != null) "Usar esta dirección" else "Usar esta zona aproximada")
                        }
                        TextButton(onClick = { query = place.fullAddress ?: place.label; pending = null; retry++ }) {
                            Text("Cambiar o completar dirección")
                        }
                    }
                }
            } else {
            OutlinedTextField(value = query, onValueChange = {
                query = it
                suggestions = emptyList()
                predictions = emptyList()
                searched = false
                searchError = null
            }, modifier = Modifier.fillMaxWidth(), enabled = !gpsBusy && !resolving,
                placeholder = { Text("Calle y número, distrito o lugar") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Indigo) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, enabled = !resolving && !gpsBusy) {
                    Icon(Icons.Outlined.Close, "Borrar búsqueda")
                } }, singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White, unfocusedBorderColor = OutlineSoft),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); retry++ }))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { keyboard?.hide(); showMap = true }, enabled = !resolving && !gpsBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Elegir en mapa") }
            if (resolving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Obteniendo dirección…", style = MaterialTheme.typography.bodySmall)
            }

            // Todo el contenido bajo el campo puede desplazarse con el teclado abierto.
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("location-results"),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Surface(onClick = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }, enabled = !gpsBusy && !resolving, color = Color.White, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(color = ChipBg, shape = CircleShape) {
                                Icon(Icons.Outlined.MyLocation, null, tint = Indigo, modifier = Modifier.padding(10.dp).size(22.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (gpsBusy) "Obteniendo tu ubicación…" else "Usar mi ubicación",
                                    style = MaterialTheme.typography.titleSmall, color = Indigo)
                                Text("Con la ubicación del teléfono", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                            if (gpsBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Indigo, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (gpsError != null) item {
                    Text(gpsError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Activar ubicación") }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"))) }) { Text("Permisos") }
                    }
                }
                if (currentArea != null) item {
                    Text("Dirección seleccionada: ${currentArea.displayAddress}", style = MaterialTheme.typography.bodySmall,
                        color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (currentArea.googlePlaceId != null) GoogleMapsAttribution(currentArea.googleAttributions)
                }
                if (googleAvailable && savedGooglePlaceId != null && currentArea == null) item {
                    OutlinedButton(onClick = { resolve() }, enabled = !resolving && !gpsBusy) {
                        Text("Recuperar mi dirección con Google")
                    }
                    Text("Solo se consulta al tocar este botón.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                fallbackNotice?.let { notice -> item { Text(notice, style = MaterialTheme.typography.bodySmall, color = TextMuted) } }
                if (searching) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Buscando lugares…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                if (searchError != null) item {
                    Text(searchError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { retry++ }, enabled = !resolving && !gpsBusy) { Text("Reintentar búsqueda") }
                }
                if (query.trim().length < 3 && !gpsBusy) item {
                    Text(if (query.isBlank()) "Escribe y elige uno de los lugares sugeridos."
                        else "Escribe un poco más para buscar lugares.",
                        Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                if (searched && suggestions.isEmpty() && predictions.isEmpty() && !searching && !resolving && searchError == null) item {
                    Text(if (nearbyOnly) "No encontramos coincidencias en esta zona" else "No encontramos ese lugar",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (nearbyOnly) "Prueba con calle, número y ciudad, o elige un punto en el mapa."
                        else "Prueba con el nombre del lugar, la ciudad y el país.",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                items(suggestions, key = { "${it.area.point}|${it.title}" }) { result ->
                    Surface(onClick = { pending = result.area; reference = ""; keyboard?.hide() },
                        enabled = !resolving && !gpsBusy, shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.LocationOn, null, tint = Indigo, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(result.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(result.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                distanceLabel(currentArea?.point, result.area.point)?.let {
                                    Text("A $it aprox.", style = MaterialTheme.typography.labelSmall, color = Indigo)
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Elegir ${result.title}",
                                tint = Indigo, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                items(predictions, key = { it.placeId }) { result ->
                    Surface(onClick = { resolve(result) }, enabled = !resolving,
                        shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.LocationOn, null, tint = Indigo)
                            Column(Modifier.weight(1f)) {
                                Text(result.title, style = MaterialTheme.typography.titleSmall)
                                Text(result.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Elegir ${result.title}", tint = Indigo)
                        }
                    }
                }
                if (useGoogle) item {
                    GoogleMapsAttribution()
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Row {
                        TextButton(onClick = { uriHandler.openUri("https://policies.google.com/privacy") }) { Text("Privacidad") }
                        TextButton(onClick = { uriHandler.openUri("https://maps.google.com/help/terms_maps/") }) { Text("Términos") }
                    }
                }
            }
            }
        }
    }
    if (showMap) MapPointPicker(currentArea?.point,
        onSelect = { select(it) }, onDismiss = { showMap = false }, source = repository,
        googleSource = if (useGoogle) google else null, allowGoogle = useGoogle)
}
