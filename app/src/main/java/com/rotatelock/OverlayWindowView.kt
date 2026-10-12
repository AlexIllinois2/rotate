package com.rotatelock

import android.content.Context
import android.view.View

/**
 * 覆盖层窗口用的空 View。
 *
 * 之所以要单独一个具名子类：这个窗口会和普通应用窗口一样发出 TYPE_WINDOW_STATE_CHANGED 无障碍事件，
 * 事件里的 className 就是本类名。服务据此把它认出来并忽略（见 RotationAccessibilityService），
 * 否则"挂上悬浮窗"这件事会被服务当成"用户切到了另一个应用"，导致刚锁上立刻解锁。
 *
 * 同时关掉它的无障碍可见性，尽量少产生事件。
 */
class OverlayWindowView(context: Context) : View(context) {

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        contentDescription = null
    }
}
