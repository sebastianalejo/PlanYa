package com.example.planya.di

import com.example.planya.core.network.ApiConfig
import com.example.planya.data.remote.api.PlanApiService
import com.example.planya.data.remote.api.PlanActionsApi
import com.example.planya.data.remote.api.PlanPhotoApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    val profileApi: com.example.planya.data.profile.ProfileApi by lazy {
        Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .client(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(com.example.planya.data.profile.ProfileApi::class.java)
    }
    val planPhotos: PlanPhotoApi by lazy {
        Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .client(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(PlanPhotoApi::class.java)
    }
    val planActions: PlanActionsApi by lazy {
        Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .client(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(PlanActionsApi::class.java)
    }
    val planApi: PlanApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PlanApiService::class.java)
    }
}
