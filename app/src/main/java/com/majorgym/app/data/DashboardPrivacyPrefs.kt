package com.majorgym.app.data

import android.content.Context

/** The six Dashboard cards/sections that display a member count and can be
 *  individually hidden (Feature 3). */
enum class DashboardCard { TOTAL, ACTIVE, EXPIRING, EXPIRED, HOLD, DUE }

/**
 * Dashboard privacy settings (Features 3 & 4) - purely a display preference,
 * never touches member data, membership status, Sync, Backup, or anything
 * else. Stored in its own SharedPreferences file (same pattern as
 * [BackupHistoryPrefs]/[SyncPrefs]) so no Room database/schema change was
 * needed for either feature.
 *
 * - [masterPrivacyOn] (Feature 4): when true, the Dashboard shows only the
 *   MAJOR GYM header and the master toggle itself - every count/card below
 *   it is hidden. Independent of the per-card settings below: turning it
 *   back off restores the normal Dashboard with whatever per-card choices
 *   were already in place.
 * - Per-[DashboardCard] number visibility (Feature 3): each of the six
 *   count-showing cards remembers its own ON/OFF choice independently -
 *   toggling one never affects another. Defaults to true (visible) for
 *   every card, i.e. the same "always show the number" behavior the app had
 *   before this feature existed.
 */
class DashboardPrivacyPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("majorgym_dashboard_privacy", Context.MODE_PRIVATE)

    var masterPrivacyOn: Boolean
        get() = prefs.getBoolean(KEY_MASTER_PRIVACY, false)
        set(value) { prefs.edit().putBoolean(KEY_MASTER_PRIVACY, value).apply() }

    fun isNumberVisible(card: DashboardCard): Boolean = prefs.getBoolean(keyFor(card), true)

    fun setNumberVisible(card: DashboardCard, visible: Boolean) {
        prefs.edit().putBoolean(keyFor(card), visible).apply()
    }

    private fun keyFor(card: DashboardCard) = "number_visible_${card.name}"

    companion object {
        private const val KEY_MASTER_PRIVACY = "master_privacy_on"
    }
}
