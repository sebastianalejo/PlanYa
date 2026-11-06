package com.example.planya.data.remote.api

import com.example.planya.data.remote.dto.PlansResponseDto
import com.example.planya.data.remote.dto.PlanResponseDto
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Path

interface PlanApiService {
    @GET("planes/{id}")
    suspend fun getPlan(@Path("id") id: String): PlanResponseDto
    @GET("planes")
    suspend fun getPlans(@Query("nextKey") nextKey: String? = null): PlansResponseDto
}
