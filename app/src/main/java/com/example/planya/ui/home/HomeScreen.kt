package com.example.planya.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuapp.planes.model.Plan
import com.tuapp.planes.model.PlanCategory
import com.tuapp.planes.ui.theme.*

@Composable
fun HomeScreen(
    plans: List<Plan>,
    location: String = "San Isidro, Lima",
    onJoin: (Plan) -> Unit = {},
    onCreate: () -> Unit = {},
    onNavigate: (BottomTab) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(PlanCategory.CAFE) }

    Scaffold(
        containerColor = ScreenBg,
        bottomBar = { BottomBar(BottomTab.HOME, onNavigate, onCreate) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Header(location, query) { query = it } }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(PlanCategory.entries) { cat ->
                        CategoryChip(cat, cat == selected) { selected = cat }
                    }
                }
            }
            items(plans, key = { it.id }) { plan ->
                PlanCard(plan, onJoin = { onJoin(plan) })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun Header(location: String, query: String, onQuery: (String) -> Unit) {
    Column(Modifier.background(Color(0xFFF7F7FC)).statusBarsPadding().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "UBICACIÓN ACTUAL",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, color = TextMuted
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, null, tint = IndigoDark, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(location, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IndigoDark)
                }
            }
            Avatar(size = 48.dp)
        }
        Spacer(Modifier.height(16.dp))
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Buscar planes…", color = Color(0xFFB9B9C6)) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun CategoryChip(cat: PlanCategory, selected: Boolean, onClick: () -> Unit) {
    val icon: ImageVector = when (cat) {
        PlanCategory.CAFE -> Icons.Outlined.LocalCafe
        PlanCategory.ALMUERZO -> Icons.Outlined.Restaurant
        PlanCategory.CAMINATA -> Icons.AutoMirrored.Outlined.DirectionsWalk
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Color(0xFFD9DBEA) else Color.White,
        border = if (selected) BorderStroke(1.dp, Color(0xFFB4B8DA)) else null
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = IndigoDark, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(cat.label, fontWeight = FontWeight.Bold, color = IndigoDark, fontSize = 16.sp)
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, onJoin: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(size = 62.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.authorName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(plan.timeAgo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
                }
                if (plan.startsSoon) StartsSoonBadge()
            }
            Spacer(Modifier.height(18.dp))
            Text(plan.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            plan.description?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 17.sp, color = TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            DistancePill(plan.distance)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onJoin,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                elevation = ButtonDefaults.buttonElevation(6.dp)
            ) {
                Text("Unirme", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun StartsSoonBadge() {
    Surface(shape = CircleShape, color = BadgeBrown) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Inicia pronto", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DistancePill(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = ChipBg) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, null, tint = IndigoDark, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("A $text", color = IndigoDark, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Avatar(size: Dp) {
    // Reemplazar por AsyncImage de Coil cuando haya URLs reales
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFFCFD2E8)),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.6f)) }
}

enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    MAPA("Mapa", Icons.Outlined.Map),
    PERFIL("Perfil", Icons.Outlined.Person)
}

@Composable
private fun BottomBar(current: BottomTab, onNavigate: (BottomTab) -> Unit, onCreate: () -> Unit) {
    Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = Color.White, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem(BottomTab.HOME, current == BottomTab.HOME, onNavigate)
            TabItem(BottomTab.MAPA, current == BottomTab.MAPA, onNavigate)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FloatingActionButton(
                    onClick = onCreate, containerColor = Orange, contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.size(52.dp)
                ) { Icon(Icons.Default.Add, "Crear") }
                Text("Crear", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            TabItem(BottomTab.PERFIL, current == BottomTab.PERFIL, onNavigate)
        }
    }
}

@Composable
private fun TabItem(tab: BottomTab, selected: Boolean, onClick: (BottomTab) -> Unit) {
    Surface(
        onClick = { onClick(tab) },
        shape = RoundedCornerShape(50),
        color = if (selected) Indigo else Color.Transparent,
        modifier = Modifier.size(width = 84.dp, height = 84.dp)
    ) {
        val tint = if (selected) Color.White else TextPrimary
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(tab.icon, null, tint = tint)
            Spacer(Modifier.height(4.dp))
            Text(tab.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun HomePreview() {
    PlanesTheme {
        HomeScreen(
            plans = listOf(
                Plan("1", "Carlos M.", null, "Hace 5 min", "Ceviche rápido en 20 min", distance = "500m"),
                Plan("2", "Lucia R.", null, "Hace 15 min", "Café y charla sobre diseño", distance = "1.2 km", startsSoon = true)
            )
        )
    }
}
