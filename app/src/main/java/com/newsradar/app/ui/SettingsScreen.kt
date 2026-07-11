package com.newsradar.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.newsradar.app.data.Outlets
import com.newsradar.app.prefs.ColorScheme
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
