package io.github.u1e2k.concbot.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 触覚（ハプティクス）フィードバックを提供するユーティリティ。
 */
object HapticHelper {

    private const val TAG = "HapticHelper"

    /**
     * ボタン押下やトグル完了時の軽快なクリックフィードバックを再生
     */
    fun performClickFeedback(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                if (it.hasVibrator()) {
                    it.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed playing haptic feedback", t)
        }
    }
}
