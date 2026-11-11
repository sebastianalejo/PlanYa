package com.example.planya.presentation.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.planya.data.profile.RatingCandidate
import com.example.planya.ui.theme.*

internal fun ratingDescription(score: Int): String = when (score) {
    1 -> "Muy mala experiencia"
    2 -> "Podría mejorar"
    3 -> "Buena experiencia"
    4 -> "Muy buena experiencia"
    5 -> "Excelente experiencia"
    else -> "Elige tu valoración"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RatingsScreen(state: RatingsState, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelect: (RatingCandidate) -> Unit, onScore: (Int) -> Unit, onReview: () -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit) {
    BackHandler(state.busy) { /* No abandonar una escritura en curso. */ }
    Scaffold(containerColor = ScreenBg, topBar = {
        TopAppBar(title = { Text("Valorar encuentros") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Comparte cómo fue el encuentro", style = MaterialTheme.typography.headlineSmall, color = IndigoDark) }
            item { Text("Solo puedes valorar a otras personas con asistencia acreditada al mismo plan, después de que termine. Una nota por persona y encuentro.", color = TextMuted) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Consultando encuentros…") }
            state.success?.let { message -> item { Surface(color = ChipBg, shape = RoundedCornerShape(16.dp)) {
                Text(message, Modifier.padding(16.dp), color = IndigoDark)
            } } }
            if (state.selected == null) state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item { OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.busy && state.selected == null) { Text("Actualizar encuentros") } }
            if (state.loaded && !state.loading && state.error == null && state.items.isEmpty()) item {
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sin valoraciones pendientes", style = MaterialTheme.typography.titleMedium, color = IndigoDark)
                        RatingStars(null)
                        Text("Necesitas haber compartido un encuentro terminado con otra persona y que ambas asistencias estén acreditadas. Si ya valoraste a todos, tampoco aparecerán pendientes.", color = TextMuted)
                    }
                }
            }
            items(state.items, key = { it.planId + ":" + it.targetUserId }) { candidate ->
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(candidate.name, style = MaterialTheme.typography.titleMedium, color = IndigoDark)
                        Text(candidate.planTitle, color = TextMuted)
                        Button(onClick = { onSelect(candidate) }, enabled = !state.busy && state.selected == null,
                            colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("Valorar participante") }
                    }
                }
            }
        }
    }
    state.selected?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() }, containerColor = Color.White,
            title = { Text(if (state.review) "Confirma tu valoración" else "Valorar a ${candidate.name}") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(candidate.planTitle, color = TextMuted)
                Text(if (state.review) "${state.score} de 5 para ${candidate.name}. Una vez guardada no se puede cambiar." else "¿Cómo fue compartir este encuentro? 1 es la nota más baja y 5 la más alta.")
                if (!state.review) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { value ->
                        FilterChip(selected = state.score == value, onClick = { onScore(value) }, label = { Text("$value") },
                            leadingIcon = { Icon(if (value <= state.score) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                null, Modifier.size(18.dp), tint = if (value <= state.score) Orange else TextMuted) })
                    }
                }
                if (state.score in 1..5) {
                    RatingStars(state.score.toDouble())
                    Text(ratingDescription(state.score), style = MaterialTheme.typography.titleSmall, color = IndigoDark)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(onClick = if (state.review) onSave else onReview,
                enabled = !state.busy && state.score in 1..5, colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                Text(if (state.busy) "Guardando…" else if (state.review && state.locked) "Reintentar misma nota" else if (state.review) "Confirmar valoración" else "Revisar")
            } },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cerrar") } })
    }
}
