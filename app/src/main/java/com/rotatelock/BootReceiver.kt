package com.rotatelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动（界面「开机自启动」开关控制，默认开）。
 *
 * 开机后要恢复的东西有两样：
 *  1. **后台守护服务**（若开着）—— [RotationGuardService] 被拉起时会把全局旋转档位
 *     立即重放一遍，同时用前台服务把进程优先级提起来；
 *  2. **全局旋转档位** —— 守护没开时这里自己尽力重放一次（固定档本身是持久设置，
 *     自动档要靠进程活着，重放后由无障碍服务重新绑定时的 applyGlobal 接管）。
 *
 * 无障碍服务不用在这里管：只要用户在系统设置里开着，系统每次开机都会自己重新绑定它，
 * onServiceConnected 里会把全局档位落实下去（见 RotationAccessibilityService）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        if (!AppPrefs.autoStart(context)) {
            AppPrefs.journal(context, "boot: auto-start off, ignore " + action)
            return
        }
        val guard = AppPrefs.guardEnabled(context)
        AppPrefs.journal(context, "boot: restore (" + action + ") guard=" + guard)
        if (guard) {
            // 守护服务的 onStartCommand 里会重放全局档位，这里只负责把它拉起来
            RotationGuardService.start(context)
        } else if (AppPrefs.globalMode(context).engages) {
            // 没有守护保进程：重放一次是一次性的（固定档足够，自动档随后交给无障碍服务）
            Thread { GlobalRotation.apply(context) }.start()
        }
    }

    private companion object {
        /**
         * QUICKBOOT_POWERON 是部分厂商 ROM「快速开机」发的广播，和 BOOT_COMPLETED 二选一，
         * 两个都接才不漏；MY_PACKAGE_REPLACED 覆盖应用更新后恢复运行的场景。
         */
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
