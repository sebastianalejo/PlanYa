package com.example.planya.domain.repository

import com.example.planya.domain.model.PlanPage
import com.example.planya.domain.model.Plan

class PlanUnavailableException : IllegalStateException("El plan ya no está disponible")

interface PlanRepository {
    suspend fun getPlan(id: String): Plan
    suspend fun getPlans(nextKey: String? = null): PlanPage
}
