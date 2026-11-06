package com.example.planya.domain.repository

import com.example.planya.domain.model.Plan

interface PlanActions {
    suspend fun create(data: Map<String, Any>): Plan
    suspend fun edit(id: String, data: Map<String, Any>): Plan
    suspend fun join(id: String): Plan
    suspend fun leave(id: String): Plan
    suspend fun cancel(id: String): Plan
}
