package com.example.planya.di

import com.example.planya.data.repository.PlanRepositoryImpl
import com.example.planya.domain.repository.PlanRepository
import com.example.planya.domain.usecase.plan.GetPlansUseCase
import com.example.planya.domain.usecase.plan.GetPlanUseCase

object RepositoryModule {
    private val plans: PlanRepository by lazy { PlanRepositoryImpl(NetworkModule.planApi) }
    val getPlans: GetPlansUseCase by lazy { GetPlansUseCase(plans) }
    val getPlan: GetPlanUseCase by lazy { GetPlanUseCase(plans) }
}
