package com.example.planya.presentation.screens.interests

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.PlanCategory
import com.example.planya.presentation.components.categoryIcon
import com.example.planya.ui.theme.*

@Composable
fun InterestsScreen(initial: Set<String>, onSave: (Set<String>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial.toList()) }
    Surface(color = ScreenBg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("¿Qué planes te gustan?", style = MaterialTheme.typography.headlineSmall, color = IndigoDark)
                Text("Elige tus intereses. Puedes cambiarlos después desde tu cuenta.", color = TextMuted)
                PlanCategory.entries.forEach { category ->
                    FilterChip(selected = category.name in selected, onClick = {
                        selected = if (category.name in selected) selected - category.name else selected + category.name
                    }, label = { Text(category.label) }, leadingIcon = { Icon(categoryIcon(category), null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp))
                }
                Button(onClick = { onSave(selected.toSet()) }, enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Continuar") }
                TextButton(onClick = { onSave(emptySet()) }) { Text("Elegir más adelante") }
            }
        }
    }
}
