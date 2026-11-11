package com.example.planya.data.operator

import retrofit2.http.*

data class OperatorResponse<T>(val success: Boolean?, val data: T?)
data class OfferDto(val id: String?, val title: String?, val merchantName: String?, val discount: String?,
    val conditions: String?, val planIds: List<String>?)
data class CatalogDto(val userId: String?, val items: List<OfferDto>?, val nextKey: String?)
data class MemberDto(val userId: String?, val name: String?, val isHost: Boolean?)
data class OperatorPlanDto(val userId: String?, val offerId: String?, val planId: String?, val title: String?, val members: List<MemberDto>?)
data class OperatorReceiptDto(val requestId: String?, val benefitId: String?, val attendanceConfirmed: Boolean?, val status: String?)
data class AttendanceBody(val offerId: String, val planId: String, val userId: String, val requestId: String)
data class RedemptionBody(val code: String, val requestId: String)

interface OperatorApi {
    @GET("operador/ofertas") suspend fun offers(@Header("Authorization") token: String,
        @Query("nextKey") nextKey: String?): OperatorResponse<CatalogDto>
    @GET("operador/ofertas/{id}/planes/{planId}") suspend fun participants(@Header("Authorization") token: String,
        @Path("id") offerId: String, @Path("planId") planId: String): OperatorResponse<OperatorPlanDto>
    @POST("asistencias") suspend fun confirm(@Header("Authorization") token: String, @Body body: AttendanceBody): OperatorResponse<OperatorReceiptDto>
    @POST("canjes") suspend fun redeem(@Header("Authorization") token: String, @Body body: RedemptionBody): OperatorResponse<OperatorReceiptDto>
}
