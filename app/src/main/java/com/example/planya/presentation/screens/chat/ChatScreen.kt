package com.example.planya.presentation.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.planya.domain.model.ChatBlockReason
import com.example.planya.domain.model.MAX_CHAT_TEXT
import com.example.planya.domain.model.chatRemainingLabel
import com.example.planya.presentation.components.UserAvatar
import com.example.planya.ui.theme.*
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val peruLocale: Locale = Locale.forLanguageTag("es-PE")

/**
 * Chat temporal del plan (US-08). Solo muestra datos devueltos por PlanYaApi.
 * Se actualiza al abrir, al volver a la app, después de enviar y con el botón Actualizar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    userId: String,
    hostId: String?,
    fallbackTitle: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onBack: () -> Unit,
    nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    var now by remember { mutableLongStateOf(nowMillis()) }
    val latestRefresh by rememberUpdatedState(onRefresh)
    val latestNow by rememberUpdatedState(nowMillis)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = latestNow()
            while (true) { delay(15_000); now = latestNow() }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { now = latestNow(); latestRefresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val secondsLeft = state.secondsLeft(now)
    val closedLocally = secondsLeft != null && secondsLeft <= 0
    // Al llegar el cierre se consulta al servidor, que es quien bloquea definitivamente.
    LaunchedEffect(closedLocally) { if (closedLocally) latestRefresh() }
    val title = state.planTitle ?: fallbackTitle

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat del plan", style = MaterialTheme.typography.titleSmall, color = IndigoDark)
                        if (title != null) Text(title, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    if (state.blocked == null && state.loaded) {
                        if (state.refreshing) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Indigo)
                        } else IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Actualizar mensajes", tint = Indigo) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ScreenBg)
            )
        },
        bottomBar = {
            if (state.canWrite && !closedLocally) Composer(state.draft, true, onDraftChange, onSend)
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                val blocked = state.blocked
                when {
                    blocked != null -> BlockedPanel(blocked, onBack)
                    closedLocally -> BlockedPanel(ChatBlockReason.CHAT_CLOSED, onBack)
                    state.requiresLogin -> CenterPanel(Icons.Outlined.Lock, "Tu sesión venció",
                        "Vuelve a ingresar para abrir el chat.", "Volver", onBack)
                    !state.loaded && state.error != null -> CenterPanel(Icons.Outlined.ErrorOutline,
                        "No pudimos cargar el chat", state.error, "Reintentar", onRefresh)
                    !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Indigo)
                    }
                    else -> {
                        ExpiryBanner(secondsLeft, state.chatExpiresAt, state.lastUpdatedMillis)
                        state.refreshError?.let { InlineError(it, onRefresh) }
                        if (state.messages.isEmpty() && state.outgoing.isEmpty()) {
                            CenterPanel(Icons.Outlined.ChatBubbleOutline, "Aún no hay mensajes",
                                "Escribe el primero para coordinar el encuentro: punto exacto, hora de llegada o dudas.",
                                null, {}, Modifier.weight(1f))
                        } else {
                            MessageList(state, userId, state.hostId ?: hostId, onRetry, onDiscard, onLoadOlder, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState, userId: String, hostId: String?,
    onRetry: (String) -> Unit, onDiscard: (String) -> Unit, onLoadOlder: () -> Unit, modifier: Modifier
) {
    val listState = rememberLazyListState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT, peruLocale) }
    // Al enviar, mostrar siempre el mensaje nuevo; al recibir, solo si la persona ya estaba abajo.
    val lastOutgoing = state.outgoing.lastOrNull()?.clientMessageId
    LaunchedEffect(lastOutgoing) { if (lastOutgoing != null) listState.animateScrollToItem(0) }
    val newestConfirmed = state.messages.lastOrNull()?.id
    LaunchedEffect(newestConfirmed) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }

    // reverseLayout: el elemento 0 queda abajo, como en cualquier chat.
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.outgoing.asReversed(), key = { "envio-" + it.clientMessageId }) { item ->
            Bubble(item.text, mine = true, sender = null, senderIsHost = false) { OutgoingFooter(item, onRetry, onDiscard) }
        }
        items(state.messages.asReversed(), key = { it.id }) { message ->
            val mine = message.senderId == userId
            Bubble(message.text, mine, sender = if (mine) null else message.senderName, senderIsHost = message.senderId == hostId) {
                Text(timeFormat.format(Date(message.createdAtMillis)) + if (mine) " · Enviado" else "",
                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
        item(key = "inicio") {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                val olderError = state.olderError
                when {
                    state.loadingOlder -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Indigo)
                    olderError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(olderError, style = MaterialTheme.typography.bodySmall, color = TextMuted, textAlign = TextAlign.Center)
                        TextButton(onClick = onLoadOlder) { Text("Reintentar", color = Indigo) }
                    }
                    state.olderKey != null -> OutlinedButton(onClick = onLoadOlder, modifier = Modifier.heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, OutlineSoft)) { Text("Cargar mensajes anteriores", color = Indigo) }
                    else -> Text("Inicio de la conversación", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, sender: String?, senderIsHost: Boolean, footer: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom) {
        if (!mine && sender != null) { UserAvatar(sender); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.widthIn(max = 300.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                color = if (mine) Indigo else Color.White,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp),
                border = if (mine) null else BorderStroke(1.dp, OutlineSoft)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (sender != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sender, style = MaterialTheme.typography.labelMedium, color = Indigo, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (senderIsHost) Surface(color = ChipBg, shape = RoundedCornerShape(50)) {
                            Text("Anfitrión", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = Indigo)
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = if (mine) Color.White else TextPrimary)
                }
            }
            footer()
        }
    }
}

@Composable
private fun OutgoingFooter(item: OutgoingMessage, onRetry: (String) -> Unit, onDiscard: (String) -> Unit) {
    when (item.status) {
        OutgoingStatus.SENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = TextMuted)
            Text("Enviando…", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        OutgoingStatus.FAILED -> Column(horizontalAlignment = Alignment.End) {
            Text(listOfNotNull("Envío sin confirmar.", item.error).joinToString(" "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.End)
            Row {
                TextButton(onClick = { onDiscard(item.clientMessageId) }) { Text("Eliminar", color = TextMuted) }
                TextButton(onClick = { onRetry(item.clientMessageId) }) { Text("Reintentar", color = Orange) }
            }
        }
    }
}

@Composable
private fun ExpiryBanner(secondsLeft: Long?, closesAt: Long?, lastUpdated: Long?) {
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT, peruLocale) }
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), color = Orange.copy(alpha = .07f),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Orange.copy(alpha = .25f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Timer, null, tint = Orange, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (secondsLeft != null && secondsLeft <= 0) "El chat se está cerrando…"
                    else "Chat temporal · se cierra en ${secondsLeft?.let(::chatRemainingLabel) ?: "—"}",
                    style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                if (closesAt != null) Text("Disponible hasta las ${format.format(Date(closesAt * 1000))}, 2 horas después del plan. " +
                    "Luego nadie podrá leer ni escribir.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Text("Sin actualización automática: toca ↻ para ver mensajes nuevos" +
                    (lastUpdated?.let { " · Actualizado ${format.format(Date(it))}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth(), color = Color.White,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, OutlineSoft)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextPrimary)
            TextButton(onClick = onRetry) { Text("Reintentar", color = Indigo) }
        }
    }
}

@Composable
private fun Composer(draft: String, enabled: Boolean, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(if (enabled) "Escribe un mensaje…" else "El chat se está cerrando") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = if (draft.length > MAX_CHAT_TEXT - 100) {
                    { Text("${draft.length}/$MAX_CHAT_TEXT") }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Indigo, unfocusedBorderColor = OutlineSoft,
                    focusedContainerColor = ScreenBg, unfocusedContainerColor = ScreenBg)
            )
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp).size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Orange, contentColor = Color.White)
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar mensaje") }
        }
    }
}

@Composable
private fun BlockedPanel(reason: ChatBlockReason, onBack: () -> Unit) {
    val (title, detail) = when (reason) {
        ChatBlockReason.NOT_MEMBER -> "Ya no tienes acceso a este chat" to
            "Solo el anfitrión y los participantes confirmados pueden leer y escribir."
        ChatBlockReason.PLAN_CANCELLED -> "El plan fue cancelado" to "Su chat se cerró y ya no admite mensajes."
        ChatBlockReason.CHAT_CLOSED -> "Este chat se cerró" to "Los chats de PlanYa se cierran 2 horas después del encuentro."
        ChatBlockReason.NO_SCHEDULE -> "Chat no disponible" to "El plan todavía no tiene un horario confirmado."
        ChatBlockReason.PLAN_NOT_FOUND -> "El plan no existe" to "Es posible que ya no esté disponible."
    }
    CenterPanel(Icons.Outlined.Lock, title, detail, "Volver", onBack)
}

@Composable
private fun CenterPanel(
    icon: ImageVector, title: String, detail: String, action: String?, onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Surface(color = ChipBg, shape = CircleShape) {
            Icon(icon, null, tint = Indigo, modifier = Modifier.padding(16.dp).size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = IndigoDark, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text(action) }
        }
    }
}
