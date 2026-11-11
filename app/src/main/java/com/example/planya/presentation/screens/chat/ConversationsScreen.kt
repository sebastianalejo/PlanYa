package com.example.planya.presentation.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.planya.domain.model.Conversation
import com.example.planya.domain.model.chatRemainingLabel
import com.example.planya.presentation.navigation.ForegroundRefreshObserver
import com.example.planya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(state: ConversationsUiState, nowSeconds: Long,
    onOpen: (Conversation) -> Unit, onRefresh: () -> Unit, onRetry: () -> Unit,
    onLoadMore: () -> Unit, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    DisposableEffect(lifecycle) {
        val observer = ForegroundRefreshObserver { refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val now = nowSeconds + state.clockOffsetSeconds
    val visible = if (state.requiresLogin) emptyList() else state.items.filter { it.isOpen(now) }
    Scaffold(containerColor = ScreenBg, topBar = {
        TopAppBar(title = { Text("Mis conversaciones", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            actions = { IconButton(onClick = onRefresh, enabled = !state.loading && !state.loadingMore) {
                Icon(Icons.Outlined.Refresh, "Actualizar conversaciones", tint = Indigo)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("conversations-list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Coordina tus encuentros", style = MaterialTheme.typography.titleLarge, color = IndigoDark)
                    Spacer(Modifier.height(6.dp))
                    Text("Chats de los planes que organizas o en los que participas. Se cierran 2 horas después de que vence el plan.",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Text("Actualiza para ver cambios del grupo.", Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = TextMuted)
                }
                if (state.loading || state.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Indigo) }
                if (state.error != null) item {
                    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, OutlineSoft)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            if (visible.isNotEmpty()) Text("Ves la lista cargada antes. El acceso se comprueba al abrir cada chat.",
                                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            TextButton(onClick = onRetry, enabled = !state.loading && !state.loadingMore) { Text("Reintentar") }
                        }
                    }
                }
                if (state.requiresLogin) item { Text("Tu sesión venció. Vuelve a ingresar.", color = IndigoDark) }
                if (state.loaded && visible.isEmpty() && state.error == null) item {
                    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Indigo, modifier = Modifier.size(32.dp))
                            Text(if (state.nextKey != null) "Continúa la búsqueda" else "No tienes chats disponibles",
                                style = MaterialTheme.typography.titleMedium, color = IndigoDark)
                            Text(if (state.nextKey != null) "Esta página no contiene chats disponibles. Puedes cargar la siguiente."
                                else "Al organizar un plan o unirte, aparecerá aquí mientras su chat siga abierto.",
                                style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                }
                items(visible, key = { it.planId }) { conversation -> ConversationCard(conversation, now) { onOpen(conversation) } }
                if (state.nextKey != null) item {
                    OutlinedButton(onClick = onLoadMore, enabled = !state.loading && !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cargar más conversaciones") }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(item: Conversation, nowSeconds: Long, onOpen: () -> Unit) {
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("conversation-${item.planId}"),
        color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, OutlineSoft)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = ChipBg, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.padding(12.dp).size(24.dp), tint = Indigo)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.category?.let { Text(it.label, style = MaterialTheme.typography.labelMedium, color = Indigo) }
                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(if (item.isHost) "Organizas este plan" else item.hostName?.let { "Con $it · Participas en el plan" }
                ?: "Participas en este plan", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            if (nowSeconds >= item.planExpiresAt) Text("Plan finalizado · El chat sigue disponible",
                style = MaterialTheme.typography.labelMedium, color = BadgeBrown)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp), tint = Orange)
                Text("Se cierra en ${chatRemainingLabel(item.chatExpiresAt - nowSeconds)}",
                    style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Abrir chat", style = MaterialTheme.typography.labelLarge, color = Indigo)
                Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Indigo)
            }
        }
    }
}
