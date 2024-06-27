package com.alex.obstaclealert.ui.utils

import com.alex.obstaclealert.ui.information.GalleryFragment
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

class RequestHttpUser {
    interface ApiService {
        @POST("api/service/v1/user")
        suspend fun postData(@Body data: UserRequest): Response<UserResponse>
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

    data class UserResponse(
        val user: String,
        val message: String
    )

    data class UserRequest(
        val status: String,
        val name: String
    )
}