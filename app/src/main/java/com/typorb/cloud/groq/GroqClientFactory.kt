package com.typorb.cloud.groq

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.typorb.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Builds the Retrofit stack once; both the dashboard (ping test) and the engine reuse it. */
object GroqClientFactory {

    val gson: Gson = GsonBuilder().setLenient().create()

    fun okHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // whisper-large-v3 can take a while on cold starts
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
        }

        // Never let an interceptor leak the Authorization header to a redirect target.
        builder.addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                require(host == "api.groq.com") { "Refusing to send credentials to $host" }
                chain.proceed(request)
            },
        )
        return builder.build()
    }

    fun groqApi(client: OkHttpClient = okHttpClient()): GroqApi = Retrofit.Builder()
        .baseUrl("${BuildConfig.GROQ_BASE_URL}/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(GroqApi::class.java)
}