package com.example.planya.presentation.screens.home

import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.LocatedArea

data class HomeUiState(
    val plans: List<Plan> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val nextKey: String? = null,
    val area: LocatedArea? = null,
    val locationSaveError: String? = null,
    val savedGooglePlaceId: String? = null
)
