package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.AppLanguage
import com.example.core.SoraSettings
import com.example.data.ai.AiProviderConfig
import com.example.data.ai.AiResponse
import com.example.data.ai.AiRoutingStrategy
import com.example.data.ai.ConnectionTestResult
import com.example.data.ai.EndpointValidator
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraThemeMode
import com.example.ui.theme.SoraViolet
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(
    settings: SoraSettings,
    lastAiResponse: AiResponse?,
    connectionTestResult: ConnectionTestResult?,
    isTestingConnection: Boolean,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectTheme: (SoraThemeMode) -> Unit,
    onSaveAiConfig: (AiProviderConfig) -> Unit,
    onClearApiKey: () -> Unit,
    onTestOllama: (AiProviderConfig) -> Unit,
    onTestGemini: (AiProviderConfig) -> Unit,
    onClearConnectionTest: () -> Unit,
    onDismiss: () -> Unit
) {
    var strategy by remember(settings.aiStrategy) { mutableStateOf(settings.aiStrategy) }
    var fallbackEnabled by remember(settings.aiFallbackEnabled) { mutableStateOf(settings.aiFallbackEnabled) }
    var ollamaEndpoint by remember(settings.ollamaEndpoint) { mutableStateOf(settings.ollamaEndpoint) }
    var ollamaModel by remember(settings.ollamaModel) { mutableStateOf(settings.ollamaModel) }
    var ollamaVisionModel by remember(settings.ollamaVisionModel) { mutableStateOf(settings.ollamaVisionModel) }
    var geminiModel by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }
    var geminiApiKey by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var timeoutSeconds by remember(settings.aiTimeoutMs) {
        mutableIntStateOf((settings.aiTimeoutMs / 1000L).toInt().coerceIn(15, 120))
    }
    var showApiKey by remember { mutableStateOf(false) }

    fun buildConfig(): AiProviderConfig = AiProviderConfig(
        strategy = strategy,
        fallbackEnabled = fallbackEnabled,
        ollamaEndpoint = ollamaEndpoint.trim().ifBlank { AiProviderConfig.DEFAULT_OLLAMA_ENDPOINT },
        ollamaModel = ollamaModel.trim().ifBlank { AiProviderConfig.DEFAULT_OLLAMA_MODEL },
        ollamaVisionModel = ollamaVisionModel.trim(),
        geminiModel = geminiModel.trim().ifBlank { AiProviderConfig.DEFAULT_GEMINI_MODEL },
        geminiApiKey = geminiApiKey.trim(),
        requestTimeoutMs = timeoutSeconds * 1000L
    )

    val endpointCheck = remember(ollamaEndpoint) {
        EndpointValidator.validateOllamaEndpoint(ollamaEndpoint)
    }

    ModalBottomSheet(
        onDismissRequest = {
            onClearConnectionTest()
            onDismiss()
        },
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_settings_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.ai_settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    onClearConnectionTest()
                    onDismiss()
                }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close)
                    )
                }
            }

            if (lastAiResponse != null) {
                SoraStatusBadge(
                    text = stringResource(
                        R.string.ai_last_provider_badge,
                        lastAiResponse.provider.localizedLabel(),
                        lastAiResponse.modelUsed
                    ),
                    color = SoraSoftBlue,
                    icon = Icons.Rounded.AutoAwesome
                )
            }

            // Language & Theme Card
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.language_label),
                            style = MaterialTheme.typography.titleSmall
                        )
                        SoraLanguageSwitcher(
                            currentLanguage = settings.appLanguage,
                            onLanguageSelected = onSelectLanguage,
                            onDarkBackdrop = false
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Text(
                        text = stringResource(R.string.theme_label),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoraThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onSelectTheme(mode) },
                                label = { Text(mode.localizedLabel()) }
                            )
                        }
                    }
                }
            }

            // Routing Strategy Card
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.ai_routing_strategy),
                        style = MaterialTheme.typography.titleSmall
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AiRoutingStrategy.entries) { item ->
                            FilterChip(
                                selected = strategy == item,
                                onClick = { strategy = item },
                                label = { Text(item.localizedLabel()) },
                                modifier = Modifier.testTag("strategy_${item.name.lowercase()}")
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(R.string.ai_fallback_title),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.ai_fallback_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fallbackEnabled,
                            onCheckedChange = { fallbackEnabled = it },
                            modifier = Modifier.testTag("ai_fallback_switch")
                        )
                    }
                }
            }

            // Local Ollama Card
            SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.ai_ollama_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = SoraSoftBlue
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = ollamaEndpoint,
                            onValueChange = { ollamaEndpoint = it },
                            label = { Text(stringResource(R.string.ai_ollama_endpoint_label)) },
                            isError = !endpointCheck.isValid,
                            supportingText = {
                                if (!endpointCheck.isValid && endpointCheck.errorMessage.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.ai_endpoint_invalid, endpointCheck.errorMessage),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ollama_endpoint_input")
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { ollamaEndpoint = AiProviderConfig.DEFAULT_OLLAMA_ENDPOINT },
                            label = { Text(stringResource(R.string.ai_ollama_quick_localhost)) },
                            modifier = Modifier.testTag("ollama_preset_localhost")
                        )
                        AssistChip(
                            onClick = { ollamaEndpoint = AiProviderConfig.EMULATOR_OLLAMA_ENDPOINT },
                            label = { Text(stringResource(R.string.ai_ollama_quick_emulator)) },
                            modifier = Modifier.testTag("ollama_preset_emulator")
                        )
                    }
                    Text(
                        text = stringResource(R.string.ai_ollama_device_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = ollamaModel,
                            onValueChange = { ollamaModel = it },
                            label = { Text(stringResource(R.string.ai_ollama_model_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ollama_model_input")
                        )
                        OutlinedTextField(
                            value = ollamaVisionModel,
                            onValueChange = { ollamaVisionModel = it },
                            label = { Text(stringResource(R.string.ai_ollama_vision_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    FilledTonalButton(
                        onClick = { onTestOllama(buildConfig()) },
                        enabled = !isTestingConnection && endpointCheck.isValid,
                        modifier = Modifier.fillMaxWidth().testTag("test_ollama_connection_button")
                    ) {
                        Icon(Icons.Rounded.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_test_connection))
                    }
                }
            }

            // Cloud Gemini Card
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.ai_gemini_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = SoraViolet
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = geminiModel,
                            onValueChange = { geminiModel = it },
                            label = { Text(stringResource(R.string.ai_gemini_model_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = geminiApiKey,
                            onValueChange = { geminiApiKey = it },
                            label = { Text(stringResource(R.string.ai_gemini_key_label)) },
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = stringResource(R.string.cd_toggle_secret)
                                        )
                                    }
                                    if (geminiApiKey.isNotBlank()) {
                                        IconButton(onClick = {
                                            geminiApiKey = ""
                                            onClearApiKey()
                                        }) {
                                            Icon(
                                                Icons.Rounded.Clear,
                                                contentDescription = stringResource(R.string.action_clear)
                                            )
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_input")
                        )
                    }
                    Text(
                        text = stringResource(R.string.ai_gemini_key_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { onTestGemini(buildConfig()) },
                        enabled = !isTestingConnection,
                        modifier = Modifier.fillMaxWidth().testTag("test_gemini_connection_button")
                    ) {
                        Icon(Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_gemini_test_btn))
                    }
                }
            }

            if (isTestingConnection) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.ai_testing_progress),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (connectionTestResult != null) {
                Surface(
                    color = if (connectionTestResult.success) {
                        SoraSuccess.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().testTag("ai_connection_result_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val resultText = if (connectionTestResult.success) {
                            if (connectionTestResult.provider == com.example.data.ai.AiProviderId.OLLAMA) {
                                stringResource(
                                    R.string.ai_conn_success_ollama,
                                    connectionTestResult.discoveredModels.size
                                )
                            } else {
                                stringResource(
                                    R.string.ai_conn_success_gemini,
                                    connectionTestResult.message
                                )
                            }
                        } else {
                            stringResource(R.string.ai_conn_failed, connectionTestResult.message)
                        }
                        Text(
                            text = resultText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectionTestResult.success) SoraSuccess else MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (connectionTestResult.discoveredModels.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.ai_ollama_discovered_models),
                                style = MaterialTheme.typography.labelMedium
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(connectionTestResult.discoveredModels) { modelInfo ->
                                    FilterChip(
                                        selected = ollamaModel == modelInfo.name,
                                        onClick = { ollamaModel = modelInfo.name },
                                        label = { Text(modelInfo.name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Timeout Slider
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.ai_timeout_label, timeoutSeconds),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = timeoutSeconds.toFloat(),
                        onValueChange = { timeoutSeconds = it.roundToInt().coerceIn(15, 120) },
                        valueRange = 15f..120f
                    )
                }
            }

            // Bottom Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val defaults = AiProviderConfig()
                        strategy = defaults.strategy
                        fallbackEnabled = defaults.fallbackEnabled
                        ollamaEndpoint = defaults.ollamaEndpoint
                        ollamaModel = defaults.ollamaModel
                        ollamaVisionModel = defaults.ollamaVisionModel
                        geminiModel = defaults.geminiModel
                        timeoutSeconds = (defaults.requestTimeoutMs / 1000L).toInt()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_reset_defaults))
                }
                Button(
                    onClick = {
                        onSaveAiConfig(buildConfig())
                        onDismiss()
                    },
                    enabled = endpointCheck.isValid,
                    modifier = Modifier.weight(1f).testTag("save_ai_settings_button")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
