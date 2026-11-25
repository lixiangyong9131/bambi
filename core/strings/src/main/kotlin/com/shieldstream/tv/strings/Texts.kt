package com.shieldstream.tv.strings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.Lang
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface Texts {
    fun get(@StringRes id: Int, vararg formatArgs: Any): String
    fun quantity(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String
}

@Singleton
class ResourceTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : Texts {
    override fun get(id: Int, vararg formatArgs: Any): String {
        val res = context.localizedContext(Lang.current).resources
        return if (formatArgs.isEmpty()) {
            res.getString(id)
        } else {
            runCatching { res.getString(id, *formatArgs) }
                .getOrElse { res.getString(id) }
        }
    }

    override fun quantity(id: Int, count: Int, vararg formatArgs: Any): String {
        val res = context.localizedContext(Lang.current).resources
        return runCatching { res.getQuantityString(id, count, *formatArgs) }
            .getOrElse { res.getQuantityString(id, count) }
    }
}

@Composable
fun rememberTexts(): Texts {
    val context = LocalContext.current
    return remember(context) { ResourceTexts(context) }
}

@Composable
fun localizedStringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val resources = localizedResources()
    return remember(resources, id, formatArgs.toList()) {
        if (formatArgs.isEmpty()) {
            resources.getString(id)
        } else {
            runCatching { resources.getString(id, *formatArgs) }
                .getOrElse { resources.getString(id) }
        }
    }
}

@Composable
fun localizedPluralResource(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    val resources = localizedResources()
    return remember(resources, id, count, formatArgs.toList()) {
        runCatching { resources.getQuantityString(id, count, *formatArgs) }
            .getOrElse { resources.getQuantityString(id, count) }
    }
}

@Composable
private fun localizedResources(): Resources {
    val context = LocalContext.current
    val language = Lang.current
    val localizedContext = remember(context, language) { context.localizedContext(language) }
    return localizedContext.resources
}

private fun Context.localizedContext(language: AppLanguage): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(language.locale)
    return createConfigurationContext(configuration)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StringsModule {
    @Binds
    abstract fun bindTexts(implementation: ResourceTexts): Texts
}
