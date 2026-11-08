package com.example.planya.domain.repository

import com.example.planya.domain.model.Benefit
import com.example.planya.domain.model.BenefitCode
import com.example.planya.domain.model.BenefitPage
import com.example.planya.domain.model.BenefitStatus

interface BenefitsRepository {
    suspend fun getBenefits(status: BenefitStatus, nextKey: String? = null): BenefitPage
    suspend fun getBenefit(id: String): Benefit
    suspend fun getCode(id: String): BenefitCode
}
