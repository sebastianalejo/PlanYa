package com.example.planya.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.example.planya.ui.theme.*

enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Outlined.Home), MAPA("Mapa", Icons.Outlined.Map),
    PERFIL("Perfil", Icons.Outlined.PersonOutline)
}

@Composable
fun AppBottomBar(onNavigate: (BottomTab) -> Unit, onCreate: () -> Unit, selectedTab: BottomTab = BottomTab.HOME) {
    Surface(color = Color.White, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BottomTab.entries.take(2).forEach { tab ->
                BottomItem(tab, tab == selectedTab, Modifier.weight(1f)) { onNavigate(tab) }
            }
            TextButton(onClick = onCreate, modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(4.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Surface(color = Orange, shape = RoundedCornerShape(11.dp)) {
                        Icon(Icons.Default.Add, null, tint = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp).size(22.dp))
                    }
                    Text("Crear", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
            BottomItem(BottomTab.PERFIL, selectedTab == BottomTab.PERFIL, Modifier.weight(1f)) { onNavigate(BottomTab.PERFIL) }
        }
    }
}

@Composable
private fun BottomItem(tab: BottomTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 64.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Surface(color = if (selected) ChipBg else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                Icon(tab.icon, null, tint = if (selected) Indigo else TextMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp).size(22.dp))
            }
            Text(tab.label, color = if (selected) Indigo else TextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}
