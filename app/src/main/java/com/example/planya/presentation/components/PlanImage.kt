package com.example.planya.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.planya.R
import com.example.planya.domain.model.PlanCategory
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import androidx.compose.ui.platform.LocalContext
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.gallery
import com.example.planya.ui.theme.ChipBg
import com.example.planya.ui.theme.Indigo

@Composable
fun PlanImage(plan: Plan, modifier: Modifier = Modifier) {
    val photos = plan.gallery()
    if (photos.isNotEmpty()) {
        PhotoGallery(photos.map { it.imageUrl }, plan.title, modifier)
        return
    }
    var loaded by remember(plan.imageUrl) { mutableStateOf(false) }
    Box(modifier.background(ChipBg), contentAlignment = Alignment.Center) {
        if (!loaded) {
            if (plan.category == PlanCategory.CAFE) {
                Image(painterResource(R.drawable.coffee_reference), contentDescription = "Fotografía referencial de café",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .9f)) {
                    Text("Imagen referencial", Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
                }
            } else Icon(categoryIcon(plan.category), contentDescription = "Imagen de ${plan.category?.label ?: "plan"}",
                tint = Indigo, modifier = Modifier.fillMaxSize(.28f))
        }
        if (plan.imageUrl != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(plan.imageUrl)
            .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(), contentDescription = plan.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
