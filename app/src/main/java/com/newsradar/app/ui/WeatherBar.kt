package com.newsradar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newsradar.app.weather.DailyForecast
import com.newsradar.app.weather.HourlyPoint
import com.newsradar.app.weather.WeatherData
import kotlin.math.roundToInt

/**
 * Collapsible weather bar pinned above the feed.
 * Collapsed: today's summary. Tap to expand into today's detail + 7-day ahead.
 */
@Composable
fun WeatherBar(state: WeatherUiState, showSun: Boolean = true) {
    if (!state.enabled) return
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.data != null) { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            when {
                state.loading && state.data == null ->
                    Text("Loading weather…", style = MaterialTheme.typography.bodyMedium)

                state.message != null && state.data == null ->
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)

                state.data != null -> {
                    val w = state.data
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.current.emoji, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "${w.current.tempC.roundToInt()}°C · ${w.current.description}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    w.locationName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }

                    AnimatedVisibility(visible = expanded) {
                        WeatherDetail(w, showSun)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherDetail(w: WeatherData, showSun: Boolean) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
        "Feels like ${w.current.feelsLikeC.roundToInt()}°C · " +
            "Wind ${w.current.windKph.roundToInt()} km/h · " +
            "Humidity ${w.current.humidity}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showSun) {
        val today = w.daily.firstOrNull()
        if (today != null && today.sunrise.isNotBlank() && today.sunset.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "🌅 ${formatTime(today.sunrise)}  🌇 ${formatTime(today.sunset)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Week ahead — tap a day for hourly",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            w.daily.forEach { day ->
                DayRow(day, w.hourlyByDay[day.epochDay] ?: emptyList())
            }
        }
    }
}

@Composable
private fun DayRow(day: DailyForecast, hours: List<HourlyPoint>) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day.emoji, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(day.dayLabel, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold)
                    if (day.description.isNotBlank()) {
                        Text(day.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (day.precipProb > 0) {
                    Text("${day.precipProb}%", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                }
                Text("${day.maxC.roundToInt()}°", style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${day.minC.roundToInt()}°", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Show hourly",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        AnimatedVisibility(visible = open && hours.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp)
            ) {
                // Show every 3rd hour to keep it scannable.
                hours.filterIndexed { i, _ -> i % 3 == 0 }.forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "%02d:00".format(h.hour),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.emoji, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(10.dp))
                            if (h.precipProb > 0) {
                                Text("${h.precipProb}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text("${h.tempC.roundToInt()}°",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Pull "HH:MM" out of an ISO local datetime like "2026-07-11T05:03". */
private fun formatTime(iso: String): String {
    val t = iso.substringAfter('T')
    return if (t.length >= 5) t.substring(0, 5) else iso
}
