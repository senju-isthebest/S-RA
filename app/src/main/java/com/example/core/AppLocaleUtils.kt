package com.example.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.LayoutDirection
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppLanguage(val code: String, val nativeLabel: String) {
    ENGLISH("en", "English"),
    ARABIC("ar", "العربية");

    val layoutDirection: LayoutDirection
        get() = if (this == ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr

    val locale: Locale
        get() = Locale(code)

    companion object {
        fun fromCodeOrNull(code: String?): AppLanguage? =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }

        fun resolve(persistedCode: String?, systemLocale: Locale = Locale.getDefault()): AppLanguage {
            fromCodeOrNull(persistedCode)?.let { return it }
            return if (systemLocale.language.equals("ar", ignoreCase = true)) ARABIC else ENGLISH
        }
    }
}

val LocalAppLanguage = compositionLocalOf { AppLanguage.ENGLISH }

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun createLocalizedContext(base: Context, language: AppLanguage): Context {
    val locale = language.locale
    val config = Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    val resContext = base.createConfigurationContext(config)
    return object : ContextWrapper(base) {
        override fun getResources(): Resources = resContext.resources
        override fun getAssets(): AssetManager = resContext.assets
    }
}

object SoraFormatters {
    fun formatDate(millis: Long, language: AppLanguage, pattern: String = "MMM dd, yyyy"): String {
        val sdf = SimpleDateFormat(pattern, language.locale)
        return sdf.format(Date(millis))
    }

    fun formatDateTime(millis: Long, language: AppLanguage): String {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", language.locale)
        return sdf.format(Date(millis))
    }

    fun formatPercent(value: Int, language: AppLanguage): String {
        val nf = NumberFormat.getIntegerInstance(language.locale)
        return "${nf.format(value)}%"
    }

    /** Wraps technical LTR tokens (URLs, emails, equations, code) with Unicode directional isolates. */
    fun ltrIsolate(text: String): String = "\u2066$text\u2069"
}
