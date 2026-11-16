package io.github.u1e2k.concbot.util

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * AccessibilityNodeInfo 操作に関する拡張関数群。
 */

/**
 * 画面のアクティブルートノードを多角的に取得する。
 * targetPackage が指定されている場合、そのパッケージのウィンドウが現れるまで待機（他アプリウィンドウの誤認を防止）。
 */
fun getActiveRootNode(
    service: AccessibilityService,
    event: AccessibilityEvent?,
    targetPackage: String? = null
): AccessibilityNodeInfo? {
    try {
        val windows = service.windows

        // 1. targetPackage が指定されている場合、そのパッケージのウィンドウのみを厳格に探索
        if (targetPackage != null) {
            for (w in windows) {
                val r = w.root
                if (r != null && r.packageName?.toString() == targetPackage) {
                    return r
                }
            }
            // event.source が対象パッケージの場合
            val eventSource = event?.source
            if (eventSource != null && eventSource.packageName?.toString() == targetPackage) {
                return eventSource
            }
            // まだ対象パッケージが開いていない場合は null を返しリトライさせる
            return null
        }

        // targetPackage 未指定時のフォールバック
        for (w in windows) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val r = w.root
                if (r != null && r.packageName?.toString() != "com.android.systemui") return r
            }
        }

        for (w in windows) {
            if (w.isActive || w.isFocused) {
                val r = w.root
                if (r != null && r.packageName?.toString() != "com.android.systemui") return r
            }
        }
    } catch (t: Throwable) {
        Log.w("AccessibilityNodeExt", "Error getting active root node", t)
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
