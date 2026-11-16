package io.github.u1e2k.concbot.action.impl

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.u1e2k.concbot.action.AutomationAction
import io.github.u1e2k.concbot.service.ConcAccessibilityService
import io.github.u1e2k.concbot.util.HapticHelper
import io.github.u1e2k.concbot.util.clickAncestral
import io.github.u1e2k.concbot.util.findClickableAncestor
import io.github.u1e2k.concbot.util.findFirstSwitch
import io.github.u1e2k.concbot.util.findNearbySwitch
import io.github.u1e2k.concbot.util.getActiveRootNode
import io.github.u1e2k.concbot.util.isSwitchNode

/**
 * Wi-Fiテザリング（アクセスポイント）の自動切り替えアクション。
 * イベント検知 + 定期ポーリングのリトライループにより、描画遅延に左右されず確実に操作します。
 */
class TetherToggleAction : AutomationAction {

    override val id: String = ACTION_ID

    @Volatile
    override var isRunning: Boolean = false
        private set

    private enum class Step {
        INITIAL_SCREEN,
        SUB_SCREEN,
        COMPLETED
    }

    private var currentStep = Step.INITIAL_SCREEN
    private var depthCount = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val timeoutRunnable = Runnable {
        if (isRunning) {
            Log.w(TAG, "TetherToggleAction timed out (${TIMEOUT_MS}ms) at step: $currentStep")
            ConcAccessibilityService.instance?.let { onTimeout(it) }
        }
    }

    // ノード描画待ちのための定期ポーリング用 Runnable
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || currentStep == Step.COMPLETED) return

            val service = ConcAccessibilityService.instance
            if (service != null) {
                checkAndProcessWindow(service, null)
            }

            if (isRunning && currentStep != Step.COMPLETED) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun execute(context: Context, service: ConcAccessibilityService) {
        if (isRunning) {
            Log.w(TAG, "Already running, skipping duplicate execute request")
            return
        }
        isRunning = true
        currentStep = Step.INITIAL_SCREEN
        depthCount = 1

        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        Log.i(TAG, "Starting TetherToggleAction, launching settings via AccessibilityService...")
        if (!TetherSettingsLauncher.launch(service)) {
            cancel()
            return
        }

        // 起動直後からポーリング開始
        mainHandler.postDelayed(pollRunnable, 100)
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent,
        service: ConcAccessibilityService
    ): Boolean {
        if (!isRunning || currentStep == Step.COMPLETED) return true

        checkAndProcessWindow(service, event)

        return currentStep == Step.COMPLETED
    }

    @Synchronized
    private fun checkAndProcessWindow(service: ConcAccessibilityService, event: AccessibilityEvent?) {
        if (!isRunning || currentStep == Step.COMPLETED) return

        val rootNode = getActiveRootNode(service, event, "com.android.settings")
        if (rootNode == null) {
            // まだ設定画面が開いていない
            return
        }

        try {
            when (currentStep) {
                Step.INITIAL_SCREEN -> handleInitialScreen(rootNode, service)
                Step.SUB_SCREEN -> handleSubScreen(rootNode, service)
                Step.COMPLETED -> return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing window nodes", t)
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    private fun handleInitialScreen(root: AccessibilityNodeInfo, service: ConcAccessibilityService) {
        // 1. ピンポイントで「Wi-Fi アクセス ポイント」項目を探す
        val targetNode = findWifiHotspotTitleNode(root)
        if (targetNode == null) {
            Log.d(TAG, "Hotspot title node not yet rendered")
            return
        }

        Log.i(TAG, "Found target title node: '${targetNode.text}'")

        // 2. 直下にスイッチがあるか（他機種向け）
        val directSwitch = targetNode.findNearbySwitch()
        if (directSwitch != null && directSwitch.isSwitchNode()) {
            Log.i(TAG, "Direct switch found near title node, clicking switch...")
            if (directSwitch.clickAncestral()) {
                finishWithFeedback(service)
                return
            }
        }

        // 3. スイッチがない場合（Pixel標準）、行をクリックしてサブ画面へ遷移
        val clickableRow = targetNode.findClickableAncestor()
        if (clickableRow != null) {
            Log.i(TAG, "Clicking hotspot row to enter sub-screen...")
            if (clickableRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                currentStep = Step.SUB_SCREEN
                depthCount = 2
                Log.i(TAG, "Transitioned to SUB_SCREEN, waiting for sub-screen nodes...")
            }
        }
    }

    private fun handleSubScreen(root: AccessibilityNodeInfo, service: ConcAccessibilityService) {
        // サブ画面（WifiTetherSettings）
        // 1. 「使用」や「有効」テキストの近くのSwitchを探す
        for (kw in SUB_SCREEN_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            for (node in nodes) {
                val sw = node.findNearbySwitch() ?: node.findClickableAncestor()
                if (sw != null) {
                    Log.i(TAG, "Found switch near sub-screen keyword '$kw', clicking...")
                    if (sw.clickAncestral()) {
                        finishWithFeedback(service)
                        return
                    }
                }
            }
        }

        // 2. サブ画面内の最初のSwitchを探してクリック
        val firstSwitch = root.findFirstSwitch()
        if (firstSwitch != null) {
            Log.i(TAG, "Found first switch in sub-screen, clicking...")
            if (firstSwitch.clickAncestral()) {
                finishWithFeedback(service)
                return
            }
        }
    }

    private fun findWifiHotspotTitleNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (exact in HOTSPOT_EXACT_TITLES) {
            val list = root.findAccessibilityNodeInfosByText(exact)
            for (node in list) {
                val text = node.text?.toString() ?: continue
                if (text.equals(exact, ignoreCase = true) || text.startsWith(exact, ignoreCase = true)) {
                    if (text.length <= exact.length + 5) {
                        return node
                    }
                }
            }
        }
        return null
    }

    private fun finishWithFeedback(service: ConcAccessibilityService) {
        Log.i(TAG, "Hotspot toggled successfully! Triggering feedback and navigating back...")
        currentStep = Step.COMPLETED
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(pollRunnable)

        HapticHelper.performClickFeedback(service)

        // 画面復帰
        mainHandler.postDelayed({
            for (i in 0 until depthCount) {
                mainHandler.postDelayed({
                    Log.d(TAG, "Performing back key (${i + 1}/$depthCount)")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }, (i * 200).toLong())
            }
            isRunning = false
        }, 250)
    }

    override fun onTimeout(service: ConcAccessibilityService) {
        Log.w(TAG, "Operation timed out. Resetting state.")
        cancel()
    }

    override fun cancel() {
        isRunning = false
        currentStep = Step.COMPLETED
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(pollRunnable)
    }

    companion object {
        const val ACTION_ID = "tether_toggle"
        private const val TAG = "TetherToggleAction"
        private const val TIMEOUT_MS = 6000L
        private const val POLL_INTERVAL_MS = 120L

        private val HOTSPOT_EXACT_TITLES = listOf(
            "Wi-Fi アクセス ポイント",
            "Wi-Fiアクセスポイント",
            "Wi-Fi hotspot",
            "Wi‑Fi hotspot",
            "Wi-Fi テザリング",
            "Wi-Fiテザリング",
            "Portable hotspot"
        )

        private val SUB_SCREEN_KEYWORDS = listOf(
            "Wi-Fi アクセス ポイントの使用",
            "アクセス ポイントの使用",
            "Wi-Fiテザリングの使用",
            "Use Wi-Fi hotspot",
            "Use hotspot"
        )
    }
}
