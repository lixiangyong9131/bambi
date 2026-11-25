package com.shieldstream.tv.network.di

import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.network.api.AllDebridApi
import com.shieldstream.tv.network.api.RealDebridApi
import com.shieldstream.tv.network.api.TmdbApi
import com.shieldstream.tv.network.api.TorrentioApi
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

object ApiConfig {
    const val ALLDEBRID_BASE_URL = "https://api.alldebrid.com/"
    const val REAL_DEBRID_BASE_URL = "https://api.real-debrid.com/"
    const val TORRENTIO_BASE_URL = "https://torrentio.strem.fun/"
    const val TMDB_BASE_URL = "https://api.themoviedb.org/"
    const val DOUBAN_BASE_URL = "https://movie.douban.com/"
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .dns(com.shieldstream.tv.network.CloudflareFallbackDns)
        .eventListener(com.shieldstream.tv.network.CloudflareFallbackDns.failureListener)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (debuggable) addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                Log.d("Http", "${request.method} ${request.url.host} -> ${response.code}")
                response
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideAllDebridApi(client: OkHttpClient, json: Json): AllDebridApi =
        buildRetrofit(ApiConfig.ALLDEBRID_BASE_URL, client, json).create(AllDebridApi::class.java)

    @Provides
    @Singleton
    fun provideRealDebridApi(client: OkHttpClient, json: Json): RealDebridApi =
        buildRetrofit(ApiConfig.REAL_DEBRID_BASE_URL, client, json).create(RealDebridApi::class.java)

    @Provides
    @Singleton
    fun provideTorrentioApi(client: OkHttpClient, json: Json): TorrentioApi =
        buildRetrofit(ApiConfig.TORRENTIO_BASE_URL, client, json).create(TorrentioApi::class.java)

    @Provides
    @Singleton
    fun provideDoubanApi(client: OkHttpClient, json: Json): com.shieldstream.tv.network.api.DoubanApi {
        // Douban blocks requests without a browser UA + matching Referer (HTTP 418/404).
        val doubanClient = client.newBuilder()
            .addInterceptor { chain ->
                val orig = chain.request()
                val isMobileApi = orig.url.host.contains("m.douban.com")
                val request = orig.newBuilder()
                    .header(
                        "User-Agent",
                        if (isMobileApi) {
                            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Mobile Safari/537.36"
                        } else {
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                        },
                    )
                    .header("Referer", if (isMobileApi) "https://m.douban.com/" else "https://movie.douban.com/")
                    .build()
                chain.proceed(request)
            }
            .build()
        return buildRetrofit(ApiConfig.DOUBAN_BASE_URL, doubanClient, json)
            .create(com.shieldstream.tv.network.api.DoubanApi::class.java)
    }

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
