package com.newsradar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.newsradar.app.data.Outlets
import com.newsradar.app.prefs.ColorScheme
import com.newsradar.app.prefs.RatingDisplay
import com.newsradar.app.prefs.ThemeMode
import com.newsradar.app.weather.WeatherProvider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val theme by vm.themeMode.collectAsState()
    val scheme by vm.colorScheme.collectAsState()
    val outletStates by vm.outletStates.collectAsState()
    val savedName by vm.userName.collectAsState()
    val savedTown by vm.town.collectAsState()
    val weatherProviderId by vm.weatherProviderId.collectAsState()
    val weatherEnabled by vm.weatherEnabled.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val ratingDisplay by vm.ratingDisplay.collectAsState()
    val seedInterests by vm.seedInterests.collectAsState()
    val dislikeInterests by vm.dislikeInterests.collectAsState()
    val showDateBar by vm.showDateBar.collectAsState()
    val showSun by vm.showSun.collectAsState()
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Profile ----
            SectionTitle("Your profile")
            OutlinedTextField(
                value = savedName,
                onValueChange = { vm.setUserName(it) },
                label = { Text("Your name (for the greeting)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- Weather ----
            SectionTitle("Weather")
            Row(
                Modifier.fillMaxWidth().clickable { vm.setWeatherEnabled(!weatherEnabled) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show weather bar", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = weatherEnabled, onCheckedChange = { vm.setWeatherEnabled(it) })
            }
            OutlinedTextField(
                value = savedTown,
                onValueChange = { vm.setTown(it) },
                label = { Text("Your town (e.g. Tamworth)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Your interests",
                style = MaterialTheme.typography.titleMedium
            )
            var interestsText by remember(seedInterests) {
                mutableStateOf(seedInterests.joinToString(", "))
            }
            OutlinedTextField(
                value = interestsText,
                onValueChange = { interestsText = it },
                label = { Text("Topics you care about (comma separated)") },
                placeholder = { Text("e.g. football, space, economy") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    vm.setSeedInterests(
                        interestsText.split(Regex("[,\\n]")).map { w -> w.trim() }
                            .filter { w -> w.isNotBlank() })
                    focusManager.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Seeds bias the early feed toward these topics until you've rated enough " +
                    "stories. Type a few words, then tap Done and refresh the feed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Topics you'd rather avoid",
                style = MaterialTheme.typography.titleMedium
            )
            var dislikeText by remember(dislikeInterests) {
                mutableStateOf(dislikeInterests.joinToString(", "))
            }
            OutlinedTextField(
                value = dislikeText,
                onValueChange = { dislikeText = it },
                label = { Text("Topics to show rarely (comma separated)") },
                placeholder = { Text("e.g. celebrity, reality tv, gossip") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    vm.setDislikeInterests(
                        dislikeText.split(Regex("[,\\n]")).map { w -> w.trim() }
                            .filter { w -> w.isNotBlank() })
                    focusManager.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "These topics are pushed to the bottom of the feed — shown rarely (via the " +
                    "exploration mix) but never fully hidden, so the app keeps learning.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Weather provider",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "One at a time. Defaults to Met Office (UK). " +
                    "(BBC has no public weather API, so we use official Met Office data via Open-Meteo.)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WeatherProvider.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.setWeatherProvider(p.id) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = weatherProviderId == p.id,
                        onClick = { vm.setWeatherProvider(p.id) }
                    )
                    Text(p.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- Appearance ----
            SectionTitle("Appearance")
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Text("Colour scheme", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorScheme.entries.forEach { cs ->
                    FilterChip(
                        selected = scheme == cs,
                        onClick = { vm.setScheme(cs) },
                        label = { Text(cs.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setShowImages(!showImages) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show images in feed", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = showImages, onCheckedChange = { vm.setShowImages(it) })
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setShowDateBar(!showDateBar) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show date bar at top", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = showDateBar, onCheckedChange = { vm.setShowDateBar(it) })
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setShowSun(!showSun) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show sunrise/sunset in weather", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = showSun, onCheckedChange = { vm.setShowSun(it) })
            }
            Text("Rating buttons", style = MaterialTheme.typography.titleMedium)
            Text(
                "Full buttons train the app; switch to colours or hide once it's learned your taste.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RatingDisplay.entries.forEach { mode ->
                    FilterChip(
                        selected = ratingDisplay == mode,
                        onClick = { vm.setRatingDisplay(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    RatingDisplay.FULL -> "Full"
                                    RatingDisplay.COLOUR -> "Colours"
                                    RatingDisplay.NONE -> "Hidden"
                                }
                            )
                        }
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- News sources ----
            SectionTitle("News sources")
            Text(
                "Turn any outlet off to hide its stories. A 🔒 means a subscription may be " +
                    "needed to read the full article (headlines always show). Subscription-only " +
                    "outlets like the Telegraph and FT start switched off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Outlets.ALL.forEach { outlet ->
                val enabled = outletStates.firstOrNull { it.outletId == outlet.id }?.enabled
                    ?: outlet.defaultEnabled
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.setOutletEnabled(outlet.id, !enabled) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(outlet.name, style = MaterialTheme.typography.bodyLarge)
                        if (outlet.paywall != com.newsradar.app.data.Paywall.NONE) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Subscription may be required",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setOutletEnabled(outlet.id, it) }
                    )
                }
            }

            // ---- Debug (crash log) ----
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SectionTitle("Debug")
            Spacer(Modifier.height(8.dp))
            val crash by com.newsradar.app.CrashLogger.lastCrash.collectAsState()
            val clipboard = LocalClipboardManager.current
            if (crash == null) {
                Text(
                    "No crashes recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    Text(
                        crash ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(crash ?: "")) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy crash log")
                    }
                    Button(onClick = { com.newsradar.app.CrashLogger.clear() }) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
