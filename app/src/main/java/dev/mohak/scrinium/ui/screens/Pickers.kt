package dev.mohak.scrinium.ui.screens

import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePickerDefaults
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.TextAction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import java.util.Calendar
import java.util.TimeZone

// Material's DatePicker speaks UTC midnight; tasks store local timestamps
// (web parity: a date-only due is local midnight). These two convert.
private fun localToUtcDate(localMs: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun utcDateToLocal(utcMs: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Picks a day; returns local midnight of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalDatePickerDialog(initial: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = localToUtcDate(initial ?: System.currentTimeMillis())
    )
    val colors = DatePickerDefaults.colors(
        containerColor = Sc.raise,
        todayContentColor = Sc.accent,
        todayDateBorderColor = Sc.accent,
        selectedDayContainerColor = Sc.fill,
        selectedDayContentColor = Sc.onFill,
        selectedYearContainerColor = Sc.fill,
        selectedYearContentColor = Sc.onFill
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction("OK", { state.selectedDateMillis?.let { onPick(utcDateToLocal(it)) } })
        },
        dismissButton = { TextAction("Cancel", onDismiss) },
        colors = colors
    ) {
        DatePicker(state = state, colors = colors)
    }
}

/** Picks a time of day; returns minutes since midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialMinutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sc.raise,
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Sc.press,
                    selectorColor = Sc.fill,
                    timeSelectorSelectedContainerColor = Sc.fill,
                    timeSelectorSelectedContentColor = Sc.onFill,
                    timeSelectorUnselectedContainerColor = Sc.press,
                    periodSelectorSelectedContainerColor = Sc.fill,
                    periodSelectorSelectedContentColor = Sc.onFill
                )
            )
        },
        confirmButton = { TextAction("OK", { onPick(state.hour * 60 + state.minute) }) },
        dismissButton = { TextAction("Cancel", onDismiss) }
    )
}

fun minutesOfDay(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

/** Local midnight of [dayMs] plus [minutes]. */
fun atMinutes(dayMs: Long, minutes: Int): Long = Calendar.getInstance().apply {
    timeInMillis = dayMs
    set(Calendar.HOUR_OF_DAY, minutes / 60)
    set(Calendar.MINUTE, minutes % 60)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Returns a callback that asks for the notification permission (Android 13+)
 * if it isn't granted yet. Reminders need it; the system stops showing the
 * prompt by itself after the user declines twice.
 */
@Composable
fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
