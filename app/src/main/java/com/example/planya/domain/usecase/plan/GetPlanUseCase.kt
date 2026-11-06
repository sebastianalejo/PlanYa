package com.example.planya.domain.usecase.plan

import com.example.planya.domain.repository.PlanRepository

class GetPlanUseCase(private val repository: PlanRepository) {
    suspend operator fun invoke(id: String) = repository.getPlan(id)
}
