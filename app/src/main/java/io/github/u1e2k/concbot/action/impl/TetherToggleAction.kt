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
    private var depthCount = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val timeoutRunnable = Runnable {
        if (isRunning) {
            Log.w(TAG, "TetherToggleAction timed out (${TIMEOUT_MS}ms)")
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

        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        if (!TetherSettingsLauncher.launch(context)) {
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
                Step.WAITING_INITIAL_SCREEN -> handleInitialScreen(rootNode, service)
                Step.WAITING_SUB_SCREEN -> handleSubScreen(rootNode, service)
                Step.COMPLETED -> return true
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }

        return currentStep == Step.COMPLETED
    }

    private fun handleInitialScreen(root: AccessibilityNodeInfo, service: ConcAccessibilityService) {
        // 1. 直下のスイッチ探索
        val directSwitch = findHotspotSwitch(root)
        if (directSwitch != null && directSwitch.clickAncestral()) {
            Log.d(TAG, "Direct switch clicked")
            finishWithFeedback(service)
            return
        }

        // 2. スイッチがない場合、サブ画面へ遷移
        val hotspotItem = findHotspotNavigationItem(root)
        if (hotspotItem != null && hotspotItem.clickAncestral()) {
            Log.d(TAG, "Opening hotspot sub-screen")
            currentStep = Step.WAITING_SUB_SCREEN
            depthCount = 2
        }
    }

    private fun handleSubScreen(root: AccessibilityNodeInfo, service: ConcAccessibilityService) {
        val subSwitch = findSubScreenSwitch(root)
        if (subSwitch != null && subSwitch.clickAncestral()) {
            Log.d(TAG, "Sub-screen switch clicked")
            finishWithFeedback(service)
        }
    }

    private fun findHotspotSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (keyword in HOTSPOT_KEYWORDS) {
            for (node in root.findAccessibilityNodeInfosByText(keyword)) {
                val sw = node.findNearbySwitch()
                if (sw != null) return sw
            }
        }
        return null
    }

    private fun findHotspotNavigationItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (keyword in HOTSPOT_KEYWORDS) {
            for (node in root.findAccessibilityNodeInfosByText(keyword)) {
                val clickable = node.findClickableAncestor()
                if (clickable != null) return clickable
            }
        }
        return null
    }

    private fun findSubScreenSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (keyword in SUB_SCREEN_KEYWORDS) {
            for (node in root.findAccessibilityNodeInfosByText(keyword)) {
                val sw = node.findNearbySwitch()
                if (sw != null) return sw
            }
        }
        return root.findFirstSwitch()
    }

    private fun finishWithFeedback(service: ConcAccessibilityService) {
        currentStep = Step.COMPLETED
        mainHandler.removeCallbacks(timeoutRunnable)

        HapticHelper.performClickFeedback(service)

        // 画面復帰
        mainHandler.postDelayed({
            for (i in 0 until depthCount) {
                mainHandler.postDelayed({
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }, (i * 120).toLong())
            }
            isRunning = false
        }, 150)
    }

    override fun onTimeout(service: ConcAccessibilityService) {
        Log.w(TAG, "Operation timed out. Resetting.")
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
