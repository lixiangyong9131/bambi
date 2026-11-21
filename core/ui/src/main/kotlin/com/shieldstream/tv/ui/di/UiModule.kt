package com.shieldstream.tv.ui.di

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.shieldstream.tv.model.DeviceProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UiModule {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        val model = Build.MODEL
        val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                if (host.contains("doubanio.com")) {
                    chain.proceed(req.newBuilder()
                        .header("Referer", "https://movie.douban.com/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build())
                } else chain.proceed(req)
            }
            .build()
        return ImageLoader.Builder(context)
            // Fading every poster/backdrop adds animation and compositing work on low-power TV boxes.
            .crossfade(false)
            .okHttpClient(httpClient)
            .memoryCache(
                MemoryCache.Builder(context)
                    .maxSizeBytes(DeviceProfile.imageMemoryCacheBytes(model))
                    .build(),
            )
            .diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DeviceProfile.imageDiskCacheBytes(model))
                    .build(),
            )
            .build()
    }
}
