package com.example.planya.domain.usecase.plan

import com.example.planya.domain.repository.PlanRepository

class GetPlansUseCase(private val repository: PlanRepository) {
    suspend operator fun invoke(nextKey: String? = null) = repository.getPlans(nextKey)
}
