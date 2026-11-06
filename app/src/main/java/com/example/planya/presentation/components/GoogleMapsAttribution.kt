package com.example.planya.presentation.components

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.example.planya.ui.theme.TextMuted

@Composable
fun GoogleMapsAttribution(attributions: List<String> = emptyList()) {
    Column {
        // Texto compacto permitido para atribuciones en espacios reducidos.
        Text("Google Maps", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        attributions.forEach { attribution ->
            AndroidView(factory = { context -> TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                textSize = 12f
            } }, update = { it.text = HtmlCompat.fromHtml(attribution, HtmlCompat.FROM_HTML_MODE_LEGACY) })
        }
    }
}
