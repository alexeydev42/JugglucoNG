package tk.glucodata.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.ui.WearSectionTitle

/**
 * Wear alerts are intentionally device-local. The phone and watch can use
 * different enabled states, thresholds and delivery settings while still
 * sharing glucose readings and alarm acknowledgements.
 */
@Composable
fun AlertsScreen(
    onEditAlert: (AlertType) -> Unit = {},
) {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        UiRefreshBus.revision.collect { revision++ }
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                WearSectionTitle(stringResource(R.string.alarms))
            }
            items(AlertType.settingsEntries) { type ->
                val config = remember(type, revision) {
                    runCatching { AlertRepository.loadConfig(type) }.getOrNull()
                } ?: return@items
                val snoozed = remember(type, revision) {
                    runCatching { SnoozeManager.isSnoozed(type) }.getOrDefault(false)
                }

                SwitchButton(
                    checked = config.enabled,
                    onCheckedChange = { on ->
                        runCatching { AlertRepository.saveConfig(config.copy(enabled = on)) }
                        revision++
                    },
                    label = { Text(stringResource(type.nameResId)) },
                    secondaryLabel = config.threshold?.let { threshold ->
                        {
                            Text(
                                if (isMmol) {
                                    String.format(java.util.Locale.getDefault(), "%.1f", threshold)
                                } else {
                                    threshold.roundToInt().toString()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onEditAlert(type) },
                    label = { Text(stringResource(R.string.edit)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (snoozed) {
                    Button(
                        onClick = {
                            runCatching { SnoozeManager.clearSnooze(type) }
                            revision++
                        },
                        label = { Text(stringResource(R.string.cancel)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Local Wear editor. Threshold is changed with the crown or +/- buttons.
 * Sound and vibration stay local to the watch, so the phone can remain silent.
 */
@Composable
fun AlertEditScreen(
    typeId: Int,
    onDone: () -> Unit,
) {
    val type = remember(typeId) { AlertType.fromId(typeId) }
    val initial = remember(type) {
        type?.let { runCatching { AlertRepository.loadConfig(it) }.getOrNull() }
    }

    if (type == null || initial == null) {
        ScreenScaffold(timeText = { TimeText() }) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.error),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    val hasThreshold = initial.threshold != null
    val step = if (isMmol) 0.1f else 1f
    val minValue = if (isMmol) 1.0f else 18f
    val maxValue = if (isMmol) 30.0f else 540f
    val focusRequester = remember { FocusRequester() }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var threshold by remember { mutableFloatStateOf(initial.threshold ?: minValue) }
    var soundEnabled by remember { mutableStateOf(initial.soundEnabled) }
    var vibrationEnabled by remember { mutableStateOf(initial.vibrationEnabled) }
    var rotaryAccum by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun formatThreshold(value: Float): String =
        if (isMmol) String.format(java.util.Locale.getDefault(), "%.1f", value)
        else value.roundToInt().toString()

    ScreenScaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .onRotaryScrollEvent { event ->
                    if (!hasThreshold) return@onRotaryScrollEvent false
                    rotaryAccum += event.verticalScrollPixels
                    if (abs(rotaryAccum) > 20f) {
                        threshold = (
                            threshold + if (rotaryAccum > 0f) step else -step
                        ).coerceIn(minValue, maxValue)
                        rotaryAccum = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(type.nameResId),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SwitchButton(
                checked = enabled,
                onCheckedChange = { enabled = it },
                label = { Text(stringResource(R.string.enabled_status)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasThreshold) {
                Text(
                    text = stringResource(R.string.threshold),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Button(
                        onClick = { threshold = (threshold - step).coerceIn(minValue, maxValue) },
                        label = { Text("−") },
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = formatThreshold(threshold),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = { threshold = (threshold + step).coerceIn(minValue, maxValue) },
                        label = { Text("+") },
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            SwitchButton(
                checked = soundEnabled,
                onCheckedChange = { soundEnabled = it },
                label = { Text(stringResource(R.string.soundname)) },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchButton(
                checked = vibrationEnabled,
                onCheckedChange = { vibrationEnabled = it },
                label = { Text(stringResource(R.string.vibrationname)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    AlertRepository.saveConfig(
                        initial.copy(
                            enabled = enabled,
                            threshold = if (hasThreshold) threshold else initial.threshold,
                            soundEnabled = soundEnabled,
                            vibrationEnabled = vibrationEnabled,
                        )
                    )
                    onDone()
                },
                label = { Text(stringResource(R.string.save)) },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
