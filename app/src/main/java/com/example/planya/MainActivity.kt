package com.example.planya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.planya.di.RepositoryModule
import com.example.planya.presentation.navigation.AppNavigation
import com.example.planya.presentation.screens.home.HomeViewModel
import com.example.planya.presentation.screens.access.AccountViewModel
import com.example.planya.ui.theme.PlanesTheme
import com.example.planya.data.location.DeviceSelectedLocationStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La app usa tema claro incluso si el teléfono está en modo oscuro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.BLACK)
        )
        setContent {
            PlanesTheme {
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(RepositoryModule.getPlans, RepositoryModule.getPlan,
                        DeviceSelectedLocationStore(applicationContext))
                )
                val account: AccountViewModel = viewModel()
                AppNavigation(homeViewModel, account)
            }
        }
    }
}
