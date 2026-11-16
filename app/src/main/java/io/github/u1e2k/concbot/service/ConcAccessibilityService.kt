package io.github.u1e2k.concbot.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.u1e2k.concbot.action.AutomationAction

/**
 * ConcBot の自動操作を実行する AccessibilityService。
 * ActionDispatcher からディスパッチされた AutomationAction に画面イベントを委譲します。
 */
class ConcAccessibilityService : AccessibilityService() {

    var currentAction: AutomationAction? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ConcAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val action = currentAction
        if (action != null && action.isRunning) {
            val isFinished = action.onAccessibilityEvent(event, this)
            if (isFinished) {
                Log.d(TAG, "Action ${action.id} completed successfully")
                currentAction = null
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ConcAccessibilityService interrupted")
        currentAction?.cancel()
        currentAction = null
    }

    override fun onDestroy() {
        super.onDestroy()
        currentAction?.cancel()
        currentAction = null
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "ConcAccessibilityService destroyed")
    }

    fun submitAction(action: AutomationAction, context: Context): Boolean {
        currentAction?.cancel()
        currentAction = action
        action.execute(context, this)
        return true
    }

    companion object {
        private const val TAG = "ConcAccessibility"

        @Volatile
        var instance: ConcAccessibilityService? = null
            private set

        /**
         * システム設定を参照して、本サービスが有効になっているかを判定
         */
        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${ConcAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                if (componentNameString.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}
