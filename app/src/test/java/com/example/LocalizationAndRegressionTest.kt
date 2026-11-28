package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.core.AppLanguage
import com.example.core.SoraFormatters
import com.example.core.UserPreferences
import com.example.core.createLocalizedContext
import com.example.data.ai.*
import com.example.domain.StudentToolboxEngine
import com.example.ui.components.SoraLanguageSwitcher
import com.example.ui.screens.AiSettingsSheet
import com.example.ui.theme.SoraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizationAndRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `english and arabic string resources have complete key and format parity`() {
        val enFile = resolveResourceFile("src/main/res/values/strings.xml")
        val arFile = resolveResourceFile("src/main/res/values-ar/strings.xml")
        assertTrue("Missing values/strings.xml at ${enFile.absolutePath}", enFile.exists())
        assertTrue("Missing values-ar/strings.xml at ${arFile.absolutePath}", arFile.exists())

        val enStrings = parseStringEntries(enFile)
        val arStrings = parseStringEntries(arFile)

        val missingInAr = enStrings.keys - arStrings.keys
        val extraInAr = arStrings.keys - enStrings.keys
        assertTrue("Keys missing in Arabic strings.xml: $missingInAr", missingInAr.isEmpty())
        assertTrue("Unexpected extra keys in Arabic strings.xml: $extraInAr", extraInAr.isEmpty())

        val emptyAr = arStrings.filterValues { it.isBlank() }.keys
        assertTrue("Blank translations in Arabic strings.xml: $emptyAr", emptyAr.isEmpty())

        // Verify positional format placeholders match between EN and AR
        val placeholderRegex = Regex("%\\d+\\$[sd]")
        for ((key, enVal) in enStrings) {
            val arVal = arStrings.getValue(key)
            val enPlaceholders = placeholderRegex.findAll(enVal).map { it.value }.toSet()
            val arPlaceholders = placeholderRegex.findAll(arVal).map { it.value }.toSet()
            assertEquals("Format placeholder mismatch for '$key'", enPlaceholders, arPlaceholders)
        }

        // Verify plurals keys parity
        val enPlurals = parsePluralsNames(enFile)
        val arPlurals = parsePluralsNames(arFile)
        assertEquals("Plurals parity mismatch", enPlurals, arPlurals)
    }

    @Test
    fun `app language resolution honors persisted override and falls back to system locale`() {
        assertEquals(AppLanguage.ARABIC, AppLanguage.resolve("ar", Locale.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve("en", Locale.forLanguageTag("ar")))
        assertEquals(AppLanguage.ARABIC, AppLanguage.resolve("", Locale.forLanguageTag("ar")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(null, Locale.FRENCH))
    }

    @Test
    fun `user preferences persists language and ai settings and clears api key`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferences(context)

        prefs.setAppLanguage(AppLanguage.ARABIC)
        prefs.updateAiSettings(
            AiProviderConfig(
                strategy = AiRoutingStrategy.OLLAMA_FIRST,
                fallbackEnabled = false,
                ollamaEndpoint = "http://10.0.2.2:11434",
                ollamaModel = "llama3.2:3b",
                ollamaVisionModel = "llava:7b",
                geminiModel = "gemini-3.8-flash",
                geminiApiKey = "AIzaSyTempKey999",
                requestTimeoutMs = 45_000L
            )
        )

        val saved = prefs.settings.first()
        assertEquals(AppLanguage.ARABIC, saved.appLanguage)
        assertEquals(AiRoutingStrategy.OLLAMA_FIRST, saved.aiStrategy)
        assertFalse(saved.aiFallbackEnabled)
        assertEquals("http://10.0.2.2:11434", saved.ollamaEndpoint)
        assertEquals("llama3.2:3b", saved.ollamaModel)
        assertEquals("llava:7b", saved.ollamaVisionModel)
        assertEquals("AIzaSyTempKey999", saved.geminiApiKey)
        assertEquals(45_000L, saved.aiTimeoutMs)

        prefs.clearGeminiApiKey()
        assertEquals("", prefs.settings.first().geminiApiKey)
    }

    @Test
    fun `language switcher toggles state and switches layout direction cleanly`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)
        var observedDirection by mutableStateOf(LayoutDirection.Ltr)

        composeRule.setContent {
            val localizedCtx = createLocalizedContext(baseContext, currentLanguage)
            CompositionLocalProvider(
                LocalLayoutDirection provides currentLanguage.layoutDirection
            ) {
                observedDirection = LocalLayoutDirection.current
                SoraTheme(appLanguage = currentLanguage) {
                    Column {
                        SoraLanguageSwitcher(
                            currentLanguage = currentLanguage,
                            onLanguageSelected = { currentLanguage = it }
                        )
                        Text(
                            text = localizedCtx.getString(R.string.nav_library),
                            modifier = androidx.compose.ui.Modifier.testTag("localized_label")
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("localized_label").assertTextEquals("Library")
        assertEquals(LayoutDirection.Ltr, observedDirection)

        composeRule.onNodeWithTag("lang_option_ar").performClick()
        composeRule.waitForIdle()

        assertEquals(AppLanguage.ARABIC, currentLanguage)
        assertEquals(LayoutDirection.Rtl, observedDirection)
        composeRule.onNodeWithTag("localized_label").assertTextEquals("المكتبة")

        composeRule.onNodeWithTag("lang_option_en").performClick()
        composeRule.waitForIdle()
        assertEquals(AppLanguage.ENGLISH, currentLanguage)
        assertEquals(LayoutDirection.Ltr, observedDirection)
    }

    @Test
    fun `ai settings sheet validates endpoints and saves updated provider config`() {
        var savedConfig: AiProviderConfig? = null
        val prefsDefaults = AiProviderConfig()

        composeRule.setContent {
            SoraTheme(appLanguage = AppLanguage.ENGLISH) {
                AiSettingsSheet(
                    settings = com.example.core.SoraSettings(
                        aiStrategy = prefsDefaults.strategy,
                        aiFallbackEnabled = true,
                        ollamaEndpoint = prefsDefaults.ollamaEndpoint,
                        ollamaModel = prefsDefaults.ollamaModel
                    ),
                    lastAiResponse = null,
                    connectionTestResult = null,
                    isTestingConnection = false,
                    onSelectLanguage = {},
                    onSelectTheme = {},
                    onSaveAiConfig = { savedConfig = it },
                    onClearApiKey = {},
                    onTestOllama = {},
                    onTestGemini = {},
                    onClearConnectionTest = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.waitForIdle()

        // Enter insecure remote cleartext URL -> Save & Test Ollama must disable
        composeRule.onNodeWithTag("ollama_endpoint_input")
            .performScrollTo()
            .performTextReplacement("http://remote-ai.example.com:11434")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("save_ai_settings_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("test_ollama_connection_button").assertIsNotEnabled()

        // Tap Emulator bridge preset -> valid again and updates endpoint
        composeRule.onNodeWithTag("ollama_preset_emulator").performScrollTo().performClick()
        composeRule.onNodeWithTag("strategy_ollama_first").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("save_ai_settings_button")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()

        assertNotNull(savedConfig)
        assertEquals(AiProviderConfig.EMULATOR_OLLAMA_ENDPOINT, savedConfig?.ollamaEndpoint)
        assertEquals(AiRoutingStrategy.OLLAMA_FIRST, savedConfig?.strategy)
    }

    @Test
    fun `sora json, formatters, and student toolbox regression checks`() {
        val obj = SoraJson.parseObject("""{"name":"SŌRA","nums":["a","b"],"nested":{"count":3}}""")
        assertEquals("SŌRA", obj.str("name"))
        assertEquals(listOf("a", "b"), obj.strList("nums"))
        assertEquals(3, obj.obj("nested")?.intVal("count"))

        val isolated = SoraFormatters.ltrIsolate("http://localhost:11434")
        assertTrue(isolated.startsWith("\u2066") && isolated.endsWith("\u2069"))

        val calc = StudentToolboxEngine.evaluateScientificExpression("sqrt(144) + 2^5 * 0.5")
        assertTrue(calc.isSuccess)
        assertEquals(28.0, calc.getOrThrow().first, 1e-6)

        val (avg, weight) = StudentToolboxEngine.calculateWeightedGrade(listOf(90.0 to 40.0, 80.0 to 60.0))
        assertEquals(84.0, avg, 1e-6)
        assertEquals(100.0, weight, 1e-6)

        val (words, _, mins) = StudentToolboxEngine.countTextMetrics("One two three four five")
        assertEquals(5, words)
        assertEquals(1, mins)
    }

    private fun resolveResourceFile(relativeFromApp: String): File {
        val candidates = listOf(
            File(relativeFromApp),
            File("app/$relativeFromApp"),
            File("../app/$relativeFromApp")
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    private fun parseStringEntries(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            map[name] = el.textContent.orEmpty()
        }
        return map
    }

    private fun parsePluralsNames(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        val set = LinkedHashSet<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            set.add(name)
        }
        return set
    }
}
