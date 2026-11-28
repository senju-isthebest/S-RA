package com.example

import android.content.Context
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.core.AppLanguage
import com.example.core.createLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SŌRA", appName)
    }

    @Test
    fun `localized context switches strings and layout direction for arabic`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val enCtx = createLocalizedContext(base, AppLanguage.ENGLISH)
        val arCtx = createLocalizedContext(base, AppLanguage.ARABIC)

        val enHome = enCtx.getString(R.string.nav_home)
        val arHome = arCtx.getString(R.string.nav_home)

        assertEquals("Home", enHome)
        assertEquals("الرئيسية", arHome)
        assertNotEquals(enHome, arHome)
        assertEquals(LayoutDirection.Ltr, AppLanguage.ENGLISH.layoutDirection)
        assertEquals(LayoutDirection.Rtl, AppLanguage.ARABIC.layoutDirection)
    }
}
