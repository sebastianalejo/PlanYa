package com.example.planya.data.remote.api

import com.example.planya.data.remote.dto.BenefitCodeDto
import com.example.planya.data.remote.dto.BenefitDto
import com.example.planya.data.remote.dto.BenefitListDto
import com.example.planya.data.remote.dto.BenefitResponseDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface BenefitsApi {
    @GET("beneficios") suspend fun list(@Header("Authorization") token: String, @Query("estado") status: String,
        @Query("nextKey") nextKey: String?): BenefitResponseDto<BenefitListDto>
    @GET("beneficios/{id}") suspend fun get(@Header("Authorization") token: String, @Path("id") id: String): BenefitResponseDto<BenefitDto>
    @POST("beneficios/{id}/codigo") suspend fun code(@Header("Authorization") token: String, @Path("id") id: String): BenefitResponseDto<BenefitCodeDto>
}
