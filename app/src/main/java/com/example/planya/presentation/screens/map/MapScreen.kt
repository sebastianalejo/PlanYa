package com.example.planya.presentation.screens.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.BuildConfig
import com.example.planya.domain.model.*
import com.example.planya.domain.usecase.plan.MapPin
import com.example.planya.domain.usecase.plan.buildMapPins
import com.example.planya.domain.usecase.plan.countWithoutLocation
import com.example.planya.presentation.components.*
import com.example.planya.presentation.screens.home.CategoryFilter
import com.example.planya.ui.theme.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

private val LIMA = GeoPoint(-12.0464, -77.0428)

/**
 * US-07: mapa para explorar planes reales de AWS. No reemplaza el selector de
 * dirección (MapPointPicker). Unirse se hace en Detalle, no al tocar un pin.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    area: LocatedArea?,
    nowSeconds: Long,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onCategory: (PlanCategory?) -> Unit,
    onSelectPin: (String?) -> Unit,
    onCamera: (MapCamera) -> Unit,
    onLocate: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissLocationMessage: () -> Unit,
    onOpenPlan: (Plan) -> Unit,
    onNavigate: (BottomTab) -> Unit,
    onCreate: () -> Unit,
    mapsAvailable: Boolean = BuildConfig.GOOGLE_MAPS_ENABLED,
    mapContent: (@Composable (List<MapPin>, (String) -> Unit) -> Unit)? = null
) {
    // Los pines se recalculan con el reloj: un plan vencido desaparece y cierra su ficha.
    val pins = remember(state.plans, state.category, nowSeconds) { buildMapPins(state.plans, state.category, nowSeconds) }
    val withoutLocation = remember(state.plans, nowSeconds) { countWithoutLocation(state.plans, nowSeconds) }
    val selected = pins.firstOrNull { it.key == state.selectedPinKey }
    LaunchedEffect(state.selectedPinKey, selected) {
        if (state.selectedPinKey != null && selected == null) onSelectPin(null)
    }
    // Distancia: primero GPS pedido aquí; si no, la zona elegida en Inicio (aproximada).
    val origin = state.origin ?: area?.point
    val originIsApproximate = state.origin == null || state.myLocation?.approximate == true
    val start = state.camera?.let { GeoPoint(it.latitude, it.longitude) } ?: area?.point ?: LIMA
    val showingDefaultLima = state.camera == null && area == null && state.myLocation == null

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) onLocate() else onPermissionDenied()
    }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.locationMessage) {
        state.locationMessage?.let { snackbars.showSnackbar(it); onDismissLocationMessage() }
    }

    Scaffold(containerColor = ScreenBg, snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = { AppBottomBar(onNavigate, onCreate, selectedTab = BottomTab.MAPA) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Encabezado: título, contador honesto de planes cargados y Actualizar.
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Planes en el mapa", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    if (state.hasLoaded) Text(buildString {
                        append("${pins.sumOf { it.plans.size }} en el mapa de ${state.plans.size} planes cargados")
                        if (withoutLocation > 0) append(" · $withoutLocation sin ubicación")
                    }, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoading && !state.isLoadingMore,
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Refresh, "Actualizar planes", tint = Indigo)
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { CategoryFilter(null, state.category == null) { onCategory(null) } }
                items(PlanCategory.entries) { category ->
                    CategoryFilter(category, state.category == category) { onCategory(category) }
                }
            }
            if (state.isLoading || state.isLoadingMore) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Indigo)
            if (state.showingStaleData) StaleBanner(state.errorMessage!!, onRetry)

            Box(Modifier.fillMaxWidth().weight(1f).testTag("plans-map")) {
                when {
                    mapContent != null -> mapContent(pins) { onSelectPin(it) }
                    mapsAvailable -> PlansGoogleMap(start, state.camera?.zoom ?: 13f, pins, state.selectedPinKey,
                        state.myLocation?.point, state.recenterEvent, onSelectPin, onCamera)
                    else -> Text("El mapa no está configurado en esta compilación (GOOGLE_MAPS_ENABLED). " +
                        "Los planes siguen disponibles en Inicio.", Modifier.align(Alignment.Center).padding(24.dp),
                        color = TextMuted)
                }
                // Mi ubicación: el permiso se pide solo al pulsar.
                FilledTonalButton(onClick = {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }, enabled = !state.locating, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).heightIn(min = 48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = Indigo)) {
                    Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(6.dp))
                    Text(if (state.locating) "Ubicando…" else "Mi ubicación")
                }
                if (showingDefaultLima) Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
                    color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    Text("Vista inicial de Lima, no tu ubicación", Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                // Estados: carga inicial, error sin datos, vacío.
                when {
                    state.isLoading && !state.hasLoaded -> CenterCard {
                        CircularProgressIndicator(color = Indigo); Text("Cargando planes…")
                    }
                    state.errorMessage != null && state.plans.isEmpty() -> CenterCard {
                        Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text("Reintentar") }
                    }
                    state.hasLoaded && pins.isEmpty() && selected == null -> CenterCard {
                        Text(if (state.category != null) "No hay planes de ${state.category.label} con ubicación."
                            else "No hay planes activos con ubicación.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.category != null) OutlinedButton(onClick = { onCategory(null) }) { Text("Ver todos") }
                            if (state.nextKey != null) OutlinedButton(onClick = onLoadMore) { Text("Cargar más") }
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (selected == null && state.nextKey != null && pins.isNotEmpty()) {
                        TextButton(onClick = onLoadMore, enabled = !state.isLoadingMore,
                            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                            Surface(color = Color.White, shape = RoundedCornerShape(50)) {
                                Text("Cargar más planes", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Indigo)
                            }
                        }
                    }
                    if (selected != null) PinSheet(selected, origin, originIsApproximate, nowSeconds,
                        onClose = { onSelectPin(null) }, onOpenPlan = onOpenPlan)
                    if (mapsAvailable && mapContent == null) Box(Modifier.padding(start = 12.dp, bottom = 4.dp)) { GoogleMapsAttribution() }
                }
            }
        }
    }
}

@Composable
private fun PlansGoogleMap(start: GeoPoint, zoom: Float, pins: List<MapPin>, selectedKey: String?,
    myLocation: GeoPoint?, recenterEvent: Int, onSelectPin: (String?) -> Unit, onCamera: (MapCamera) -> Unit) {
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), zoom)
    }
    // Centrar solo cuando el usuario acaba de pulsar Mi ubicación; al volver del
    // detalle se respeta la zona guardada aunque ya exista una ubicación previa.
    val initialEvent = remember { recenterEvent }
    LaunchedEffect(recenterEvent) {
        if (recenterEvent != initialEvent) myLocation?.let {
            camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15f))
        }
    }
    // Guardar la cámara al detenerse para restaurarla al volver del detalle.
    LaunchedEffect(camera.isMoving) {
        if (!camera.isMoving) camera.position.let { onCamera(MapCamera(it.target.latitude, it.target.longitude, it.zoom)) }
    }
    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera,
        properties = MapProperties(isMyLocationEnabled = false, isIndoorEnabled = false),
        uiSettings = MapUiSettings(mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false),
        onMapClick = { onSelectPin(null) }) {
        pins.forEach { pin ->
            key(pin.key) {
                Marker(state = rememberUpdatedMarkerState(LatLng(pin.point.latitude, pin.point.longitude)),
                    title = if (pin.isGroup) "${pin.plans.size} planes" else pin.plans.first().title,
                    icon = BitmapDescriptorFactory.defaultMarker(markerHue(pin.category)),
                    alpha = if (selectedKey == null || selectedKey == pin.key) 1f else .6f,
                    onClick = { onSelectPin(pin.key); true })
            }
        }
        myLocation?.let {
            Marker(state = rememberUpdatedMarkerState(LatLng(it.latitude, it.longitude)), title = "Tu ubicación",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        }
    }
}

/** Tonos de marcador cercanos a la paleta: índigo, naranja y marrón. */
private fun markerHue(category: PlanCategory?): Float = when (categoryColor(category)) {
    Orange -> 14f
    BadgeBrown -> 30f
    else -> 231f
}

/** Ficha inferior. Si varios planes comparten punto, primero se elige uno de la lista. */
@Composable
private fun PinSheet(pin: MapPin, origin: GeoPoint?, approximate: Boolean, nowSeconds: Long,
    onClose: () -> Unit, onOpenPlan: (Plan) -> Unit) {
    var chosenId by remember(pin.key) { mutableStateOf(if (pin.isGroup) null else pin.plans.first().id) }
    val chosen = pin.plans.firstOrNull { it.id == chosenId }
    Surface(Modifier.fillMaxWidth().padding(12.dp).testTag("map-sheet"), color = Color.White,
        shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (chosen == null) "${pin.plans.size} planes en este punto" else chosen.category?.label ?: "Plan",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Indigo)
                if (pin.isGroup && chosen != null) TextButton(onClick = { chosenId = null }) { Text("Ver lista") }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Cerrar ficha") }
            }
            if (chosen == null) pin.plans.forEach { plan ->
                Surface(onClick = { chosenId = plan.id }, shape = RoundedCornerShape(14.dp), color = ScreenBg,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CategoryMark(plan.category, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(plan.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Expira: ${plan.expirationLabel(nowSeconds)}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
                    }
                }
            } else PlanSummary(chosen, origin, approximate, nowSeconds, onOpenPlan)
        }
    }
}

@Composable
private fun PlanSummary(plan: Plan, origin: GeoPoint?, approximate: Boolean, nowSeconds: Long, onOpenPlan: (Plan) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryMark(plan.category)
        Column(Modifier.weight(1f)) {
            Text(plan.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(plan.authorName, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
    plan.meetingPlace?.takeIf { it.isNotBlank() }?.let { InfoLine(Icons.Outlined.LocationOn, it) }
    val distance = distanceLabel(origin, plan.coordinates)
    InfoLine(Icons.Outlined.Straighten, when {
        distance == null -> "Distancia desconocida: usa Mi ubicación o elige una zona en Inicio"
        approximate -> "A $distance aprox. · En línea recta desde la zona elegida"
        else -> "A $distance · En línea recta"
    })
    InfoLine(Icons.Outlined.Timer, "Expira en: ${plan.expirationLabel(nowSeconds)}")
    Button(onClick = { onOpenPlan(plan) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange), shape = RoundedCornerShape(50)) {
        Text("Ver detalles"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
    }
}

@Composable
private fun StaleBanner(message: String, onRetry: () -> Unit) {
    Surface(color = Color(0xFFFFF3E0), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$message Se muestran los planes cargados antes.", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = BadgeBrown)
            TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

@Composable
private fun BoxScope.CenterCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.align(Alignment.Center).padding(24.dp), color = Color.White, shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
