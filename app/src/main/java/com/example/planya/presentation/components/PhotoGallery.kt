package com.example.planya.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.planya.ui.theme.*

@Composable
fun PhotoGallery(models: List<Any?>, description: String, modifier: Modifier = Modifier) {
    key(models) {
        val pager = rememberPagerState(pageCount = { models.size })
        Box(modifier, contentAlignment = Alignment.Center) {
            HorizontalPager(pager, Modifier.fillMaxSize().semantics { contentDescription = "Galería de fotos del plan" }) { index ->
                var failed by remember(models[index]) { mutableStateOf(models[index] == null) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(ImageRequest.Builder(LocalContext.current).data(models[index])
                        .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(),
                        if (models.size == 1) description else "$description, foto ${index + 1} de ${models.size}",
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        onError = { failed = true }, onSuccess = { failed = false })
                    if (failed) Surface(color = ScreenBg) {
                        Text("No se pudo cargar esta foto. Vuelve a abrir el detalle para actualizarla.", Modifier.padding(16.dp),
                            color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (models.size > 1) Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), color = Color.Black.copy(alpha = .65f),
                shape = MaterialTheme.shapes.large) {
                Text("${pager.currentPage + 1} / ${models.size}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
