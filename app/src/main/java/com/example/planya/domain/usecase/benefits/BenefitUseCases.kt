package com.example.planya.domain.usecase.benefits

import com.example.planya.domain.model.BenefitStatus
import com.example.planya.domain.repository.BenefitsRepository

class GetBenefitsUseCase(private val repository: BenefitsRepository) {
    suspend operator fun invoke(status: BenefitStatus, nextKey: String? = null) = repository.getBenefits(status, nextKey)
}

class GetBenefitUseCase(private val repository: BenefitsRepository) {
    suspend operator fun invoke(id: String) = repository.getBenefit(id)
}

class GetBenefitCodeUseCase(private val repository: BenefitsRepository) {
    suspend operator fun invoke(id: String) = repository.getCode(id)
}
