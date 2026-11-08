package com.example.planya.presentation.screens.benefits

import com.example.planya.domain.model.Benefit
import com.example.planya.domain.model.BenefitCode
import com.example.planya.domain.model.BenefitStatus

data class BenefitsUiState(
    val filter: BenefitStatus = BenefitStatus.DISPONIBLE,
    val benefits: List<Benefit> = emptyList(),
    val nextKey: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    val errorMessage: String? = null,
    val requiresLogin: Boolean = false,
    // Detalle
    val selected: Benefit? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    // Código: solo existe si lo generó el servidor; nunca se inventa uno local.
    val code: BenefitCode? = null,
    val codeLoading: Boolean = false,
    val codeError: String? = null
)
