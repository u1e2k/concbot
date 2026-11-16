package io.github.u1e2k.concbot.dispatcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.u1e2k.concbot.action.AutomationAction
import io.github.u1e2k.concbot.action.impl.TetherToggleAction
import io.github.u1e2k.concbot.service.ConcAccessibilityService

/**
 * トリガー（NFCやUIボタン等）から受け取ったアクションIDを解釈し、
 * 対応する AutomationAction を生成して ConcAccessibilityService に渡す司令塔。
 */
object ActionDispatcher {

    private const val TAG = "ActionDispatcher"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 利用可能なアクションのファクトリマップ。
     */
    private val actionFactories = mapOf<String, () -> AutomationAction>(
        TetherToggleAction.ACTION_ID to { TetherToggleAction() }
    )

    /**
     * アクションを実行キューに投入する
     * @param actionId アクション識別子（例: "tether_toggle"）
     * @param context コンテキスト
     * @return ディスパッチ受付に成功したか
     */
    fun dispatch(actionId: String, context: Context): Boolean {
        Log.i(TAG, "Dispatching action: $actionId")

        val factory = actionFactories[actionId]
        if (factory == null) {
            val errorMsg = "未定義のアクションです: $actionId"
            Log.e(TAG, errorMsg)
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
            return false
        }

        // 1. すでにサービスインスタンスが接続されている場合
        val service = ConcAccessibilityService.instance
        if (service != null) {
            val action = factory.invoke()
            return service.submitAction(action, context)
        }

        // 2. システム設定でサービスが有効化されているが、まだプロセス接続中（遅延）の場合
        if (ConcAccessibilityService.isEnabled(context)) {
            Log.i(TAG, "Service is enabled in settings but binding is pending. Queuing action...")
            val action = factory.invoke()
            ConcAccessibilityService.pendingAction = action

            // バックアップ: 最大1.5秒間、100msごとにバインド完了をポーリング
            var attempts = 0
            val checkRunnable = object : Runnable {
                override fun run() {
                    val currentService = ConcAccessibilityService.instance
                    if (currentService != null) {
                        if (ConcAccessibilityService.pendingAction === action) {
                            ConcAccessibilityService.pendingAction = null
                            currentService.submitAction(action, context)
                        }
                    } else if (attempts < 15) {
                        attempts++
                        mainHandler.postDelayed(this, 100)
                    } else {
                        Log.w(TAG, "Service binding timed out after 1500ms")
                    }
                }
            }
            mainHandler.postDelayed(checkRunnable, 100)
            return true
        }

        // 3. ユーザー補助サービスが無効化されている場合
        val warnMsg = "ユーザー補助サービスが無効です。設定でConcBotをONにしてください。"
        Log.w(TAG, warnMsg)
        Toast.makeText(context, warnMsg, Toast.LENGTH_LONG).show()

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return false
    }

    fun isActionSupported(actionId: String): Boolean {
        return actionFactories.containsKey(actionId)
    }
}
