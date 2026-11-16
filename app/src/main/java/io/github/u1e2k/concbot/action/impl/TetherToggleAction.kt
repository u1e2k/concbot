package io.github.u1e2k.concbot.action.impl

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.u1e2k.concbot.action.AutomationAction
import io.github.u1e2k.concbot.service.ConcAccessibilityService

/**
 * Wi-Fiテザリング（アクセスポイント）の自動切り替えアクション。
 * Pixel 9a (Android 15/16) のテザリング設定画面のUI構造に対応し、
 * 1段階トグルまたは2段階サブ画面遷移トグルの両方を自動判定してスイッチを切り替えます。
 */
class TetherToggleAction : AutomationAction {

    override val id: String = ACTION_ID

    @Volatile
    override var isRunning: Boolean = false
        private set

    private enum class Step {
        WAITING_INITIAL_SCREEN,
        WAITING_SUB_SCREEN,
        COMPLETED
    }

    private var currentStep = Step.WAITING_INITIAL_SCREEN
    private var depthCount = 1 // 戻るキーを押す階層数
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startTimeMs = 0L

    private val timeoutRunnable = Runnable {
        if (isRunning) {
            Log.w(TAG, "TetherToggleAction timed out (3000ms)")
            ConcAccessibilityService.instance?.let { onTimeout(it) }
        }
    }

    override fun execute(context: Context, service: ConcAccessibilityService) {
        if (isRunning) {
            Log.w(TAG, "Already running, skipping duplicate execute request")
            return
        }
        isRunning = true
        currentStep = Step.WAITING_INITIAL_SCREEN
        depthCount = 1
        startTimeMs = SystemClock.elapsedRealtime()

        // 3秒タイムアウトを設定
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        // テザリング設定画面を起動
        launchTetherSettings(context)
    }

    private fun launchTetherSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.TetherSettings")
            ),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")
            ),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        var launched = false
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                launched = true
                Log.d(TAG, "Successfully launched tether settings with intent: $intent")
                break
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "Candidate intent not found: $intent")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed launching intent: $intent", t)
            }
        }

        if (!launched) {
            Log.e(TAG, "Could not open Tether settings screen")
            cancel()
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent,
        service: ConcAccessibilityService
    ): Boolean {
        if (!isRunning || currentStep == Step.COMPLETED) return true

        val rootNode = service.rootInActiveWindow ?: return false

        try {
            when (currentStep) {
                Step.WAITING_INITIAL_SCREEN -> {
                    // まず画面内に直接トグル可能なスイッチがあるか探索
                    val directSwitch = findHotspotSwitch(rootNode)
                    if (directSwitch != null) {
                        Log.d(TAG, "Direct hotspot switch found, clicking...")
                        if (clickNode(directSwitch)) {
                            finishWithFeedback(service)
                            return true
                        }
                    }

                    // スイッチが直接ない場合、「Wi-Fi アクセス ポイント」項目をタップしてサブ画面へ進む
                    val hotspotItem = findHotspotNavigationItem(rootNode)
                    if (hotspotItem != null) {
                        Log.d(TAG, "Hotspot navigation item found, clicking to open sub-screen...")
                        if (clickNode(hotspotItem)) {
                            currentStep = Step.WAITING_SUB_SCREEN
                            depthCount = 2
                            return false // サブ画面のロードを待つ
                        }
                    }
                }

                Step.WAITING_SUB_SCREEN -> {
                    // サブ画面（WifiTetherSettings）内のメインスイッチを探索
                    val subScreenSwitch = findSubScreenSwitch(rootNode)
                    if (subScreenSwitch != null) {
                        Log.d(TAG, "Sub-screen hotspot switch found, clicking...")
                        if (clickNode(subScreenSwitch)) {
                            finishWithFeedback(service)
                            return true
                        }
                    }
                }

                Step.COMPLETED -> return true
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }

        return false
    }

    /**
     * 1階層目で直接操作できるテザリングスイッチを検索
     */
    private fun findHotspotSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (keyword in HOTSPOT_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val sw = findSwitchNearNode(node)
                if (sw != null) return sw
            }
        }
        return null
    }

    /**
     * 「Wi-Fi アクセス ポイント」サブ画面に遷移するためのクリック可能ノードを検索
     */
    private fun findHotspotNavigationItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (keyword in HOTSPOT_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val clickableParent = findClickableAncestor(node)
                if (clickableParent != null) {
                    return clickableParent
                }
            }
        }
        return null
    }

    /**
     * サブ画面（WifiTetherSettings）で「Wi-Fi アクセス ポイントの使用」等のメインスイッチを検索
     */
    private fun findSubScreenSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (keyword in SUB_SCREEN_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val sw = findSwitchNearNode(node)
                if (sw != null) return sw
            }
        }
        return findFirstSwitchNode(root)
    }

    private fun findSwitchNearNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchNode(node)) return node

        val parent = node.parent ?: return null
        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val child = parent.getChild(i) ?: continue
            if (isSwitchNode(child)) return child
            for (j in 0 until child.childCount) {
                val grandChild = child.getChild(j) ?: continue
                if (isSwitchNode(grandChild)) return grandChild
            }
        }
        return null
    }

    private fun findFirstSwitchNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchNode(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstSwitchNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun isSwitchNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        return node.isCheckable ||
                className.contains("Switch", ignoreCase = true) ||
                className.contains("CompoundButton", ignoreCase = true)
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun finishWithFeedback(service: ConcAccessibilityService) {
        currentStep = Step.COMPLETED
        mainHandler.removeCallbacks(timeoutRunnable)

        // 触覚フィードバック
        triggerHapticFeedback(service)

        // 元の画面へ復帰（階層分バックキーを実行）
        mainHandler.postDelayed({
            navigateBack(service, depthCount)
            isRunning = false
        }, 150)
    }

    private fun triggerHapticFeedback(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
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
            Log.w(TAG, "Failed triggering haptic feedback", t)
        }
    }

    private fun navigateBack(service: ConcAccessibilityService, times: Int) {
        for (i in 0 until times) {
            mainHandler.postDelayed({
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }, (i * 120).toLong())
        }
    }

    override fun onTimeout(service: ConcAccessibilityService) {
        Log.w(TAG, "Operation timed out. Resetting state.")
        cancel()
    }

    override fun cancel() {
        isRunning = false
        currentStep = Step.COMPLETED
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    companion object {
        const val ACTION_ID = "tether_toggle"
        private const val TAG = "TetherToggleAction"
        private const val TIMEOUT_MS = 3000L

        private val HOTSPOT_KEYWORDS = listOf(
            "Wi-Fi アクセス ポイント",
            "Wi-Fiアクセスポイント",
            "Wi-Fi テザリング",
            "Wi-Fiテザリング",
            "アクセス ポイント",
            "Wi-Fi hotspot",
            "Wi‑Fi hotspot",
            "Hotspot",
            "Tethering & portable hotspot"
        )

        private val SUB_SCREEN_KEYWORDS = listOf(
            "Wi-Fi アクセス ポイントの使用",
            "アクセス ポイントの使用",
            "Use Wi-Fi hotspot",
            "Use hotspot"
        )
    }
}
