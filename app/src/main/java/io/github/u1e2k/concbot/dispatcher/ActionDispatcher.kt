package io.github.u1e2k.concbot.dispatcher

import android.content.Context
import android.content.Intent
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

    /**
     * 利用可能なアクションのファクトリマップ。
     * 将来アクションを追加する際はここに登録する。
     */
    private val actionFactories = mapOf<String, () -> AutomationAction>(
        TetherToggleAction.ACTION_ID to { TetherToggleAction() }
    )

    /**
     * アクションを実行キューに投入する
     * @param actionId アクション識別子（例: "tether_toggle"）
     * @param context コンテキスト
     * @return ディスパッチに成功したか
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

        val service = ConcAccessibilityService.instance
        if (service == null) {
            val warnMsg = "ユーザー補助サービスが無効です。設定で有効にしてください。"
            Log.w(TAG, warnMsg)
            Toast.makeText(context, warnMsg, Toast.LENGTH_LONG).show()

            // ユーザー補助設定画面を開く
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }

        val action = factory.invoke()
        return service.submitAction(action, context)
    }

    /**
     * 指定されたアクションIDが登録されているか
     */
    fun isActionSupported(actionId: String): Boolean {
        return actionFactories.containsKey(actionId)
    }
}
