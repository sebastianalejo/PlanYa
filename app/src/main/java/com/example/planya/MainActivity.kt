package com.example.planya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.planya.model.Plan
import com.example.planya.ui.home.HomeScreen
import com.example.planya.ui.theme.PlanesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlanesTheme {
                HomeScreen(plans = samplePlans)
            }
        }
    }
}

val samplePlans = listOf(
    Plan("1", "Carlos M.", null, "Hace 5 min", "Ceviche rápido en 20 min", distance = "500m"),
    Plan("2", "Lucia R.", null, "Hace 15 min", "Café y charla sobre diseño", distance = "1.2 km", startsSoon = true),
    Plan(
        "3", "Mateo P.", null, "Hace 1 hora", "Caminata por el malecón",
        description = "Busco grupo para caminar un par de horas antes del atardecer.",
        distance = "2.5 km"
    )
)
