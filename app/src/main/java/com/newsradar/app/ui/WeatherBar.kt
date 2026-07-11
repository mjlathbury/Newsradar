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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.newsradar.app.weather.WeatherData
import kotlin.math.roundToInt

/**
 * Collapsible weather bar pinned above the feed.
 * Collapsed: today's summary. Tap to expand into today's detail + 7-day ahead.
 */
@Composable
fun WeatherBar(state: WeatherUiState) {
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
                        WeatherDetail(w)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherDetail(w: WeatherData) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            "Feels like ${w.current.feelsLikeC.roundToInt()}°C · " +
                "Wind ${w.current.windKph.roundToInt()} km/h · " +
                "Humidity ${w.current.humidity}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Week ahead",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(w.daily) { day -> DayColumn(day) }
        }
    }
}

@Composable
private fun DayColumn(day: DailyForecast) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day.dayLabel, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        Text(day.emoji, style = MaterialTheme.typography.titleLarge)
        Text("${day.maxC.roundToInt()}°", style = MaterialTheme.typography.bodyMedium)
        Text("${day.minC.roundToInt()}°", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (day.precipProb > 0) {
            Text("${day.precipProb}%", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
