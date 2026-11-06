package com.example.planya.presentation.screens.splash

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.planya.R
import com.example.planya.ui.theme.*

@Composable
fun SplashScreen() {
    Surface(color = ScreenBg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.planya_wordmark), contentDescription = "Logo de PlanYa",
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(2f))
            Spacer(Modifier.height(20.dp))
            Text("Un plan cerca. Un encuentro ahora.", style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, color = TextMuted)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(Modifier.size(24.dp), color = Indigo, strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text("Preparando tu sesión…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}
