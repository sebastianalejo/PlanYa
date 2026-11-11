package com.example.planya.presentation.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.PlanCategory
import com.example.planya.domain.model.LocatedArea
import com.example.planya.domain.model.isExpired
import com.example.planya.domain.model.distanceLabel
import com.example.planya.domain.model.PlanFeedScope
import com.example.planya.domain.model.PlanFeedOrder
import com.example.planya.domain.model.visibleFeed
import com.example.planya.presentation.components.*
import com.example.planya.ui.theme.*

@Composable
fun HomeScreen(
    plans: List<Plan>, isLoading: Boolean, isLoadingMore: Boolean,
    errorMessage: String?, hasMore: Boolean, onRefresh: () -> Unit,
    area: LocatedArea?, nowSeconds: Long, onLocation: () -> Unit,
    onRetry: () -> Unit, onLoadMore: () -> Unit, onOpenPlan: (Plan) -> Unit,
    onCreate: () -> Unit, onNavigate: (BottomTab) -> Unit,
    hasSavedGoogleLocation: Boolean = false,
    userId: String? = null, interests: Set<String> = emptySet(),
    requestedScope: PlanFeedScope? = null, onScopeApplied: () -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<PlanCategory?>(null) }
    var scope by rememberSaveable { mutableStateOf(PlanFeedScope.ALL) }
    var order by rememberSaveable { mutableStateOf(PlanFeedOrder.RECOMMENDED) }
    var showOrder by remember { mutableStateOf(false) }
    LaunchedEffect(userId) { if (userId == null) scope = PlanFeedScope.ALL }
    LaunchedEffect(requestedScope) { requestedScope?.let { scope = it; onScopeApplied() } }
    val visiblePlans = visibleFeed(plans, query, selected, scope, order, userId, interests, area?.point, nowSeconds)
    Scaffold(containerColor = ScreenBg, bottomBar = { AppBottomBar(onNavigate, onCreate) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = onLocation, color = Color.Transparent, modifier = Modifier.weight(1f)) {
                        Column {
                            Text("UBICACIÓN", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Outlined.LocationOn, null, tint = Indigo,
                                    modifier = Modifier.size(17.dp))
                                Text(area?.displayAddress ?: if (hasSavedGoogleLocation) "Recuperar mi dirección" else "Elegir ubicación", style = MaterialTheme.typography.titleSmall,
                                    color = IndigoDark, modifier = Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (area?.approximate == true) "Zona aproximada · Cambiar" else "Toca para actualizar",
                                style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            if (area?.googlePlaceId != null) GoogleMapsAttribution(area.googleAttributions)
                        }
                        }
                        IconButton(onClick = { onNavigate(BottomTab.PERFIL) }) { ProfileAvatar() }
                    }
                }
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("Buscar un plan") },
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                        singleLine = true, shape = RoundedCornerShape(50),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White, unfocusedBorderColor = OutlineSoft))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        item { CategoryFilter(null, selected == null) { selected = null } }
                        items(PlanCategory.entries) { category ->
                            CategoryFilter(category, selected == category) { selected = category }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Planes disponibles", style = MaterialTheme.typography.titleSmall)
                        }
                        Box {
                            TextButton(onClick = { showOrder = true }) { Text(order.label) }
                            DropdownMenu(showOrder, { showOrder = false }) {
                                PlanFeedOrder.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, enabled = option != PlanFeedOrder.DISTANCE || area != null,
                                        onClick = { order = option; showOrder = false })
                                }
                            }
                        }
                        IconButton(onClick = onRefresh, enabled = !isLoading && !isLoadingMore) {
                            Icon(Icons.Outlined.Refresh, "Actualizar planes", tint = Indigo)
                        }
                    }
                }
                if (userId != null) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PlanFeedScope.entries) { option ->
                            FilterChip(selected = scope == option, onClick = { scope = option }, label = { Text(option.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Indigo,
                                    selectedLabelColor = Color.White, containerColor = Color.White, labelColor = TextMuted))
                        }
                    }
                }
                if (isLoading) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Actualizando planes…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (errorMessage != null) item {
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Reintentar conexión") }
                }
                if (!isLoading && visiblePlans.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No encontramos planes", style = MaterialTheme.typography.titleSmall)
                        Text(when (scope) { PlanFeedScope.ALL -> "Prueba otra búsqueda o categoría."
                            PlanFeedScope.OWNED -> "Tus planes publicados aparecerán aquí."
                            PlanFeedScope.JOINED -> "Aquí aparecerán los planes a los que te unas." }, color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium)
                        if (scope == PlanFeedScope.OWNED) TextButton(onClick = onCreate) { Text("Crear mi plan") }
                    }
                }
                items(visiblePlans, key = { it.id }) { plan ->
                    PlanCard(plan, distanceLabel(area?.point, plan.coordinates), nowSeconds) { onOpenPlan(plan) }
                }
                if (hasMore) item {
                    Text("Los filtros se aplican a los planes cargados.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    TextButton(onClick = onLoadMore, enabled = !isLoadingMore) {
                        Text(if (isLoadingMore) "Cargando…" else "Cargar más planes")
                    }
                }
            }
        }
    }
}

// Compartido con el mapa (US-07) para que los filtros se vean iguales.
@Composable
internal fun CategoryFilter(category: PlanCategory?, selected: Boolean, onClick: () -> Unit) {
        Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = if (selected) Indigo else Color.White,
        border = if (selected) null else BorderStroke(1.dp, OutlineSoft)) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (category != null) Icon(categoryIcon(category), null, Modifier.size(17.dp),
                tint = if (selected) Color.White else TextMuted)
            Text(category?.label ?: "Todos", style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White else TextMuted)
        }
    }
}
