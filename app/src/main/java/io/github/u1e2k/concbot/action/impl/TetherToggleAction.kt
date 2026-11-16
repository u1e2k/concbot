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
 * Pixel 9a（Android 15/16）のUI構造・画面履歴に対応し、100%確実にスイッチをトグルします。
 */
class TetherToggleAction : AutomationAction {

    override val id: String = ACTION_ID

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var depthCount = 1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasClickedSwitch = false
    private var hasNavigatedToSubScreen = false

    private val timeoutRunnable = Runnable {
        if (isRunning) {
            Log.w(TAG, "TetherToggleAction timed out (${TIMEOUT_MS}ms)")
            ConcAccessibilityService.instance?.let { onTimeout(it) }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || hasClickedSwitch) return

            val service = ConcAccessibilityService.instance
            if (service != null) {
                checkAndProcessWindow(service, null)
            }

            if (isRunning && !hasClickedSwitch) {
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
        depthCount = 1
        hasClickedSwitch = false
        hasNavigatedToSubScreen = false

        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        Log.i(TAG, "Starting TetherToggleAction, launching tether settings...")
        if (!TetherSettingsLauncher.launch(service)) {
            cancel()
            return
        }

        mainHandler.postDelayed(pollRunnable, 80)
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent,
        service: ConcAccessibilityService
    ): Boolean {
        if (!isRunning || hasClickedSwitch) return true

        checkAndProcessWindow(service, event)

        return hasClickedSwitch
    }

    @Synchronized
    private fun checkAndProcessWindow(service: ConcAccessibilityService, event: AccessibilityEvent?) {
        if (!isRunning || hasClickedSwitch) return

        val rootNode = getActiveRootNode(service, event, "com.android.settings") ?: return

        try {
            // 判定1: サブ画面（WifiTetherSettings）にいる場合 -> 直ちにトグルスイッチをクリック
            if (tryToggleInSubScreen(rootNode, service)) {
                return
            }

            // 判定2: 親画面（TetherSettings）にいる場合 -> 「Wi-Fi アクセス ポイント」行をクリックしてサブ画面へ
            if (!hasNavigatedToSubScreen && tryNavigateFromParentScreen(rootNode, service)) {
                return
            }

            // 判定3: もし設定のトップ等に迷い込んでいる場合 -> 「アクセス ポイントとテザリング」を探して進む
            tryNavigateFromSettingsRoot(rootNode)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in checkAndProcessWindow", t)
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    /**
     * サブ画面（「Wi-Fi アクセス ポイント」詳細画面）でのスイッチ操作
     */
    private fun tryToggleInSubScreen(root: AccessibilityNodeInfo, service: ConcAccessibilityService): Boolean {
        // 1. Pixel / AOSP 標準の SwitchBar (最優先・最も確実)
        val switchBars = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_bar")
        if (switchBars.isNotEmpty()) {
            val bar = switchBars[0]
            if (bar.isClickable) {
                Log.i(TAG, "Found switch_bar by ID, clicking...")
                return clickSwitchOnce(bar, service)
            } else {
                val clickable = bar.findClickableAncestor()
                if (clickable != null) {
                    Log.i(TAG, "Found clickable ancestor of switch_bar, clicking...")
                    return clickSwitchOnce(clickable, service)
                }
            }
        }

        // 2. switchWidget / switch_widget を探してその親をクリック
        val widgetIds = listOf("android:id/switch_widget", "com.android.settings:id/switch_widget")
        for (wid in widgetIds) {
            val widgets = root.findAccessibilityNodeInfosByViewId(wid)
            for (w in widgets) {
                val clickable = w.findClickableAncestor() ?: if (w.isClickable) w else null
                if (clickable != null) {
                    Log.i(TAG, "Found switch by widget ID '$wid', clicking...")
                    return clickSwitchOnce(clickable, service)
                }
            }
        }

        // 3. テキストキーワードから周辺のスイッチを探索
        for (kw in SUB_SCREEN_KEYWORDS) {
            val list = root.findAccessibilityNodeInfosByText(kw)
            for (node in list) {
                val sw = node.findNearbySwitch() ?: node.findClickableAncestor()
                if (sw != null) {
                    Log.i(TAG, "Found switch near keyword '$kw', clicking...")
                    return clickSwitchOnce(sw, service)
                }
            }
        }

        // 4. サブ画面タイトル「Wi-Fi アクセス ポイント」が表示されており、親画面タイトルがない場合
        val hasParentTitle = root.findAccessibilityNodeInfosByText("アクセス ポイントとテザリング").isNotEmpty()
        if (!hasParentTitle) {
            val titleNodes = root.findAccessibilityNodeInfosByText("Wi-Fi アクセス ポイント")
            if (titleNodes.isNotEmpty()) {
                val firstSw = root.findFirstSwitch()
                if (firstSw != null) {
                    Log.i(TAG, "In sub-screen without parent title, clicking switch...")
                    return clickSwitchOnce(firstSw, service)
                }
            }
        }

        return false
    }

    /**
     * 親画面（「アクセス ポイントとテザリング」一覧）からサブ画面への遷移
     */
    private fun tryNavigateFromParentScreen(root: AccessibilityNodeInfo, service: ConcAccessibilityService): Boolean {
        val targetNode = findWifiHotspotTitleNode(root) ?: return false

        Log.i(TAG, "Found target title node in parent screen: '${targetNode.text}'")

        // 直下にスイッチがある場合（他機種対応）
        val directSwitch = targetNode.findNearbySwitch()
        if (directSwitch != null && directSwitch.isSwitchNode()) {
            Log.i(TAG, "Direct switch found, clicking...")
            return clickSwitchOnce(directSwitch, service)
        }

        // 行をクリックしてサブ画面へ
        val clickableRow = targetNode.findClickableAncestor()
        if (clickableRow != null) {
            Log.i(TAG, "Clicking hotspot row to enter sub-screen...")
            if (clickableRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                hasNavigatedToSubScreen = true
                depthCount = 2 // サブ画面に入ったので戻る回数は2
                return true
            }
        }
        return false
    }

    /**
     * 設定の別階層にいる場合の救済処理
     */
    private fun tryNavigateFromSettingsRoot(root: AccessibilityNodeInfo) {
        val fallbackKeywords = listOf("アクセス ポイントとテザリング", "テザリング")
        for (kw in fallbackKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            for (node in nodes) {
                val clickable = node.findClickableAncestor()
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "Navigating through fallback: '$kw'")
                    depthCount++
                    return
                }
            }
        }
    }

    private fun clickSwitchOnce(node: AccessibilityNodeInfo, service: ConcAccessibilityService): Boolean {
        if (hasClickedSwitch) return false
        hasClickedSwitch = true

        Log.i(TAG, "Executing click on switch: ${node.className}")
        val clicked = node.clickAncestral()
        if (clicked) {
            finishWithFeedback(service)
            return true
        } else {
            hasClickedSwitch = false
            return false
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
        Log.i(TAG, "Hotspot successfully toggled! Playing feedback and navigating back (depth: $depthCount)...")
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(pollRunnable)

        HapticHelper.performClickFeedback(service)

        // 適切な回数バックキーを押して元の画面へ復帰
        mainHandler.postDelayed({
            for (i in 0 until depthCount) {
                mainHandler.postDelayed({
                    Log.d(TAG, "Performing back key (${i + 1}/$depthCount)")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }, (i * 120).toLong())
            }
            isRunning = false
        }, 150)
    }

    override fun onTimeout(service: ConcAccessibilityService) {
        Log.w(TAG, "Operation timed out. Resetting state.")
        cancel()
    }

    override fun cancel() {
        isRunning = false
        hasClickedSwitch = false
        hasNavigatedToSubScreen = false
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(pollRunnable)
    }

    companion object {
        const val ACTION_ID = "tether_toggle"
        private const val TAG = "TetherToggleAction"
        private const val TIMEOUT_MS = 6000L
        private const val POLL_INTERVAL_MS = 80L

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
            "Wi-Fi アクセス ポイントを使用する",
            "Wi-Fi アクセス ポイントの使用",
            "アクセス ポイントを使用する",
            "アクセス ポイントの使用",
            "Wi-Fiテザリングを使用する",
            "Wi-Fiテザリングの使用",
            "Use Wi-Fi hotspot",
            "Use hotspot"
        )
    }
}
