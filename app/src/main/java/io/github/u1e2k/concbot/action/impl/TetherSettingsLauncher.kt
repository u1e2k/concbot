package io.github.u1e2k.concbot.action.impl

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Pixel 等の各種 Android 環境でテザリング設定画面を起動するためのインテントランチャー。
 */
object TetherSettingsLauncher {

    private const val TAG = "TetherLauncher"

    private val CANDIDATE_INTENTS = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent().setComponent(
            ComponentName("com.android.settings", "com.android.settings.TetherSettings")
        ),
        Intent().setComponent(
            ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")
        ),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )

    /**
     * テザリング設定画面を起動する
     * @return 起動に成功したか
     */
    fun launch(context: Context): Boolean {
        for (intent in CANDIDATE_INTENTS) {
            try {
                val launchIntent = Intent(intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched tether settings with intent: $intent")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "Candidate intent not found: $intent")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed launching intent: $intent", t)
            }
        }
        Log.e(TAG, "Could not launch any Tether settings intent")
        return false
    }
}
