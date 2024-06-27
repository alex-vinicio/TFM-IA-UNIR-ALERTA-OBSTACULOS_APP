package com.alex.obstaclealert.ui.utils

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

class RequestHttpLogs {
    interface ApiService {
        @POST("/api/service/v1/logs-analitic")
        suspend fun postData(@Body data: List<LogsRequest>): Response<LogsResponse>
    }

    object RetrofitInstance {
        val api: ApiService by lazy {
            Retrofit.Builder()
                .baseUrl("https://i8o724ju5g.execute-api.us-east-2.amazonaws.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }

    data class LogsResponse(
        val status: String,
        val message: String
    )

    data class LogsRequest(
        val user: String,
        val startDateTime: String,
        val updateDateTime: String,
        val status: String,
        val modelType: String,
        val metadata: String
    )
}