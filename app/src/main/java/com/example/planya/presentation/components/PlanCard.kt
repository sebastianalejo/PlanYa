package com.example.planya.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.expirationLabel
import com.example.planya.ui.theme.*

@Composable
fun PlanCard(plan: Plan, distance: String?, nowSeconds: Long, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, OutlineSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileAvatar(plan.authorAvatarUrl)
                Column(Modifier.weight(1f)) {
                    Text(plan.authorName, color = TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(plan.timeAgo, color = TextMuted,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(plan.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (plan.expiresAt != null) Text("Expira en ${plan.expirationLabel(nowSeconds)}",
                style = MaterialTheme.typography.labelMedium, color = BadgeBrown)
            plan.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.LocationOn, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                Text(distance?.let { "A $it aprox. · En línea recta" } ?: plan.meetingPlace ?: "Punto de encuentro por definir",
                    style = MaterialTheme.typography.bodySmall, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(
                    containerColor = Orange, contentColor = Color.White)) {
                Text("Ver detalles")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
