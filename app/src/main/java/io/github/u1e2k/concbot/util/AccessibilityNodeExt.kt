package io.github.u1e2k.concbot.util

import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityNodeInfo 操作に関する拡張関数群。
 * 各アクション実装から低レベルなノード走査・クリックロジックを分離します。
 */

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
 * ノード自身または同一階層・親コンテナの周辺にある Switch ノードを探索
 */
fun AccessibilityNodeInfo.findNearbySwitch(): AccessibilityNodeInfo? {
    if (this.isSwitchNode()) return this

    val parentNode = this.parent ?: return null
    for (i in 0 until parentNode.childCount) {
        val sibling = parentNode.getChild(i) ?: continue
        if (sibling.isSwitchNode()) return sibling
        // 孫要素にある場合
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
