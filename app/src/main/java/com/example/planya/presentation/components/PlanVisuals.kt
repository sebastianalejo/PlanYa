package com.example.planya.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.example.planya.domain.model.PlanCategory
import com.example.planya.ui.theme.*

fun categoryIcon(category: PlanCategory?): ImageVector = when (category) {
    PlanCategory.CAFE -> Icons.Outlined.LocalCafe
    PlanCategory.ALMUERZO -> Icons.Outlined.Restaurant
    PlanCategory.CAMINATA -> Icons.AutoMirrored.Outlined.DirectionsWalk
    PlanCategory.FIESTA -> Icons.Outlined.Celebration
    PlanCategory.DEPORTES -> Icons.Outlined.SportsSoccer
    null -> Icons.Outlined.Explore
}

fun categoryColor(category: PlanCategory?): Color = when (category) {
    PlanCategory.CAFE -> BadgeBrown
    PlanCategory.ALMUERZO -> Orange
    PlanCategory.CAMINATA -> Indigo
    PlanCategory.FIESTA -> BadgeBrown
    PlanCategory.DEPORTES -> Indigo
    null -> Indigo
}

@Composable
fun CategoryMark(category: PlanCategory?, size: Dp = 42.dp) {
    val tint = categoryColor(category)
    Box(Modifier.size(size).background(tint.copy(alpha = .10f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center) {
        Icon(categoryIcon(category), null, tint = tint, modifier = Modifier.size(size * .53f))
    }
}

@Composable
fun UserAvatar(name: String) {
    Box(Modifier.size(38.dp).background(ChipBg, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Indigo,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun ProfileAvatar(imageUrl: String? = null) {
    Box(Modifier.size(36.dp).background(ChipBg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.PersonOutline, null, tint = Indigo, modifier = Modifier.size(24.dp))
        if (imageUrl != null) AsyncImage(model = imageUrl, contentDescription = "Foto del organizador",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
    }
}
