package com.shieldstream.tv

import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.AppLanguage
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.shieldstream.tv.data.security.TokenStore
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.shieldstream.tv.domain.ApiService
import com.shieldstream.tv.domain.EpisodeTrackingRepository
import com.shieldstream.tv.watchnext.WatchNextManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), ImageLoaderFactory, Configuration.Provider {

    // Lazy-injected so cold startup does not block App.onCreate initializing crypto/network graphs.
    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var tokenStore: dagger.Lazy<TokenStore>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var episodeTracking: dagger.Lazy<EpisodeTrackingRepository>

    @Inject
    lateinit var watchNext: dagger.Lazy<WatchNextManager>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        StartupTrace.mark("App.onCreate")
        Lang.current = AppLanguage.fromName(
            getSharedPreferences(AppLanguage.PREFS, MODE_PRIVATE).getString(AppLanguage.PREF_KEY, null),
        ) ?: AppLanguage.default()
        super.onCreate()
        StartupTrace.mark("App inyectada")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val store = tokenStore.get()
            store.seedOnce(TokenStore.RD_TOKEN, BuildConfig.RD_TOKEN)
            store.seedOnce(TokenStore.TMDB_KEY, BuildConfig.TMDB_API_KEY)
            store.seedOnce(TokenStore.SUBDL_KEY, BuildConfig.SUBDL_API_KEY)
            if (BuildConfig.DEBUG) {
                store.seedOnce(TokenStore.keyNameFor(ApiService.AIOSTREAMS), DEBUG_SPANISH_ADDON)
            }
            StartupTrace.mark("App claves")
            watchNext.get().start()
            StartupTrace.mark("App watchNext")
            episodeTracking.get().notificationsEnabled.collect { enabled ->
                EpisodeNotificationScheduler.sync(this@App, enabled)
            }
        }
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()

    private companion object {
        const val DEBUG_SPANISH_ADDON =
            "https://torrentio.strem.fun/providers=mejortorrent,wolfmax4k,cinecalidad|language=spanish/manifest.json"
    }
}
