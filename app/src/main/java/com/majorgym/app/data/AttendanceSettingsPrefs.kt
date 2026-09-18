package com.majorgym.app.data

import android.content.Context

/** The three numerical counts shown at the top of the Attendance Logs page
 *  that can each be shown/hidden independently (Attendance Count Visibility
 *  Settings). Kept in its own SharedPreferences file, the same pattern as
 *  [DashboardPrivacyPrefs], so it never touches the Dashboard's own privacy
 *  settings or storage.
 *
 * Hiding a count only hides its numeric value - the card itself, its label,
 * and the underlying attendance data/calculations are all unaffected. See
 * [com.majorgym.app.ui.StatCard]'s existing `value = null` behavior, which
 * this simply drives.
 */
enum class AttendanceCount { PRESENT, MORNING, EVENING }

class AttendanceSettingsPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("majorgym_attendance_settings", Context.MODE_PRIVATE)

    fun isCountVisible(count: AttendanceCount): Boolean = prefs.getBoolean(keyFor(count), true)

    fun setCountVisible(count: AttendanceCount, visible: Boolean) {
        prefs.edit().putBoolean(keyFor(count), visible).apply()
    }

    private fun keyFor(count: AttendanceCount) = "count_visible_${count.name}"
}
