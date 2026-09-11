package com.bit.util

import android.content.Context
import android.content.SharedPreferences
import com.bit.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class BetaStatus {
    object Unlimited : BetaStatus()
    data class Active(val daysRemaining: Int, val expiryDateFormatted: String) : BetaStatus()
    data class ExpiringSoon(val daysRemaining: Int, val expiryDateFormatted: String) : BetaStatus()
    data class Expired(val reason: String, val expiryDateFormatted: String) : BetaStatus()
}

object BetaExpiryManager {

    private const val PREFS_NAME = "bit_beta_expiry_prefs"
    private const val KEY_LAST_SEEN_TIME = "last_seen_time"

    /**
     * Checks the current Beta/Debug expiration status for this build.
     */
    fun checkStatus(context: Context): BetaStatus {
        val expiryDays = BuildConfig.BETA_EXPIRY_DAYS
        if (expiryDays <= 0) {
            return BetaStatus.Unlimited
        }

        val buildTime = BuildConfig.BUILD_TIMESTAMP
        val expiryDurationMs = TimeUnit.DAYS.toMillis(expiryDays.toLong())
        val expiryTime = buildTime + expiryDurationMs
        val now = System.currentTimeMillis()

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val expiryDateFormatted = dateFormat.format(Date(expiryTime))

        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN_TIME, buildTime)

        // Anti-tamper check: If user rolled back system clock more than 1 day before last seen
        if (now < lastSeen - TimeUnit.DAYS.toMillis(1)) {
            return BetaStatus.Expired(
                reason = "System clock was rolled back. Please restore accurate device time.",
                expiryDateFormatted = expiryDateFormatted
            )
        }

        // Update latest observed timestamp
        if (now > lastSeen) {
            prefs.edit().putLong(KEY_LAST_SEEN_TIME, now).apply()
        }

        // Check if build has passed expiration deadline
        if (now >= expiryTime) {
            return BetaStatus.Expired(
                reason = "This debug/beta build has expired ($expiryDays days reached). Please update to the latest build.",
                expiryDateFormatted = expiryDateFormatted
            )
        }

        val remainingMs = expiryTime - now
        val daysRemaining = (TimeUnit.MILLISECONDS.toDays(remainingMs)).toInt().coerceAtLeast(1)

        return if (daysRemaining <= 3) {
            BetaStatus.ExpiringSoon(
                daysRemaining = daysRemaining,
                expiryDateFormatted = expiryDateFormatted
            )
        } else {
            BetaStatus.Active(
                daysRemaining = daysRemaining,
                expiryDateFormatted = expiryDateFormatted
            )
        }
    }
}
