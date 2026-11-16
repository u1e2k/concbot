package io.github.u1e2k.concbot.util

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityNodeInfo 操作に関する拡張関数群。
 */

/**
 * 画面のアクティブルートノードを多角的に取得する。
 * rootInActiveWindow, event.source, windows リストの順に対象パッケージを探索。
 */
fun getActiveRootNode(
    service: AccessibilityService,
    event: AccessibilityEvent?,
    targetPackage: String? = null
): AccessibilityNodeInfo? {
    // 1. rootInActiveWindow（最も確実）
    try {
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString()
            if (targetPackage == null || pkg == targetPackage) {
                return activeRoot
            }
        }
    } catch (t: Throwable) {
        Log.w("AccessibilityNodeExt", "Error getting rootInActiveWindow", t)
    }

    // 2. event.source
    try {
        val eventSource = event?.source
        if (eventSource != null) {
            val pkg = eventSource.packageName?.toString()
            if (targetPackage == null || pkg == targetPackage) {
                return eventSource
            }
        }
    } catch (t: Throwable) {
        Log.w("AccessibilityNodeExt", "Error getting event.source", t)
    }

    // 3. windows リスト
    try {
        val windows = service.windows
        if (!windows.isNullOrEmpty()) {
            for (w in windows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString()
                if (targetPackage == null || pkg == targetPackage) {
                    return r
                }
            }
        }
    } catch (t: Throwable) {
        Log.w("AccessibilityNodeExt", "Error getting windows list", t)
    }

    return null
}

/**
 * ノード自身が Switch / Toggle などの切り替え可能要素であるかを判定
 */
fun AccessibilityNodeInfo.isSwitchNode(): Boolean {
    val name = className?.toString() ?: ""
    return isCheckable ||
            name.contains("Switch", ignoreCase = true) ||
            name.contains("CompoundButton", ignoreCase = true)
}

/**
 * ノード自身または親をたどり、クリック可能なノードを探してクリックアクションを実行
 */
fun AccessibilityNodeInfo.clickAncestral(): Boolean {
    var target: AccessibilityNodeInfo? = this
    while (target != null && !target.isClickable) {
        target = target.parent
    }
    return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
}

/**
 * クリック可能な先祖ノードを取得
 */
fun AccessibilityNodeInfo.findClickableAncestor(): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = this
    while (current != null) {
        if (current.isClickable) return current
        current = current.parent
    }
    return null
}

/**
 * ノード自身または同一の行（Preferenceアイテム内）にある Switch ノードを探索
 */
fun AccessibilityNodeInfo.findNearbySwitch(): AccessibilityNodeInfo? {
    if (this.isSwitchNode()) return this

    val parentNode = this.parent ?: return null
    val parentClass = parentNode.className?.toString() ?: ""
    if (parentClass.contains("Recycler") || parentClass.contains("List") || parentClass.contains("Scroll")) {
        return null
    }
    if (parentNode.childCount > 6) {
        return null
    }

    for (i in 0 until parentNode.childCount) {
        val sibling = parentNode.getChild(i) ?: continue
        if (sibling.isSwitchNode()) return sibling
        for (j in 0 until sibling.childCount) {
            val grandChild = sibling.getChild(j) ?: continue
            if (grandChild.isSwitchNode()) return grandChild
        }
    }
    return null
}

/**
 * ルート配下から最初に見つかる Switch ノードを深さ優先探索
 */
fun AccessibilityNodeInfo.findFirstSwitch(): AccessibilityNodeInfo? {
    if (this.isSwitchNode()) return this
    for (i in 0 until this.childCount) {
        val child = this.getChild(i) ?: continue
        val found = child.findFirstSwitch()
        if (found != null) return found
    }
    return null
}
