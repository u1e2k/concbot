package io.github.u1e2k.concbot.action

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import io.github.u1e2k.concbot.service.ConcAccessibilityService

/**
 * 自動化アクションの基底インターフェース。
 * 将来の様々なUI操作アクション（Bluetooth切替、アプリ操作等）はこのインターフェースを実装する。
 */
interface AutomationAction {
    /** アクション固有の識別ID（例: "tether_toggle"） */
    val id: String

    /** 現在アクションが実行待機/実行中かどうか */
    val isRunning: Boolean

    /**
     * アクションの実行を開始する（対象画面のIntent起動や初期化など）。
     */
    fun execute(context: Context, service: ConcAccessibilityService)

    /**
     * AccessibilityServiceから画面イベントを受信した際の処理。
     * @return アクションの全工程が完了した場合は true、後続イベント待機時は false
     */
    fun onAccessibilityEvent(event: AccessibilityEvent, service: ConcAccessibilityService): Boolean

    /**
     * 規定時間内に完了しなかった場合のフォールバック・クリーンアップ処理。
     */
    fun onTimeout(service: ConcAccessibilityService)

    /**
     * 外部からの強制キャンセル処理。
     */
    fun cancel()
}
