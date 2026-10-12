package com.rotatelock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf

class MainActivity : ComponentActivity() {

    /** 每次回到前台自增：从系统设置页授权返回后，界面上的权限状态需要立刻刷新。 */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate extras=" + intent?.extras?.keySet()?.joinToString(","))
        applyAutomationIntent(intent)
        enableEdgeToEdge()
        setContent {
            AppRotateApp(refreshTick = resumeTick.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // 顺手清掉可能残留的显示级固定（上次进程被杀留下的）
        Thread { ShizukuRotation.cleanupStaleForce(this) }.start()
    }

    /**
     * 自动化入口必须同时接在这里：`am start` 对"已经在栈顶的同一个 Activity"不会新建实例，
     * 而是把 intent 交给 onNewIntent（am 会打印 "intent has been delivered to currently running
     * top-most instance"）。正常使用时用户不会带 target extra，所以这条分支不会触发。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent extras=" + intent.extras?.keySet()?.joinToString(","))
        setIntent(intent)
        applyAutomationIntent(intent)
        resumeTick.intValue++
    }

    /**
     * 自动化验证用入口（正常使用不受影响）：
     *   adb shell am start -n com.rotatelock/.MainActivity --es target <包名> --ei mode <0..4> \
     *       [--es engine settings|overlay|shizuku] [--ez apply true] [--ez reset true] [--ez remove true]
     *       [--ez overlayProbe true] [--ez overlayClear true]
     * mode: 0=竖屏 1=横屏 2=反向竖屏 3=反向横屏 4=保持进入时方向
     * engine: 缺省 settings；apply/reset 表示立刻用 Shizuku 执行系统级覆盖的开/关（结果打到 logcat）
     * overlayProbe / overlayClear：只挂一次悬浮窗窗口 / 移除它（不做版本判断），用于验证窗口本身
     */
    private fun applyAutomationIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("overlayProbe", false)) {
            val mode = OrientationMode.from(intent.getIntExtra("mode", OrientationMode.LANDSCAPE.userRotation))
            val applied = OverlayRotation.probe(this, mode)
            AppPrefs.setLastResult(this, "overlay probe applied=" + applied + " active=" + OverlayRotation.isActive())
            Log.i(TAG, "overlay probe applied=" + applied + " active=" + OverlayRotation.isActive())
        }
        if (intent.getBooleanExtra("overlayClear", false)) {
            OverlayRotation.clear(this)
            AppPrefs.setLastResult(this, "overlay cleared active=" + OverlayRotation.isActive())
            Log.i(TAG, "overlay cleared active=" + OverlayRotation.isActive())
        }
        if (intent.hasExtra("enable")) {
            val on = intent.getBooleanExtra("enable", true)
            AppPrefs.setEnabled(this, on)
            AppPrefs.journal(this, "automation set enabled=" + on)
            Log.i(TAG, "automation set enabled=" + on)
        }

        val target = intent.getStringExtra("target") ?: return
        if (intent.getBooleanExtra("remove", false)) {
            AppPrefs.removeTarget(this, target)
            AppPrefs.setForce(this, target, null)
            RotationController.resetAll(this)
            return
        }
        val mode = OrientationMode.from(intent.getIntExtra("mode", 0))
        AppPrefs.setTarget(this, target, mode)
        AppPrefs.setEngine(this, target, EngineKind.from(intent.getStringExtra("engine")))
        AppPrefs.setForce(this, target, if (intent.getBooleanExtra("force", false)) mode else null)
        if (intent.getBooleanExtra("reset", false)) {
            AppPrefs.setLastResult(this, "shizuku live reset pending")
            Thread {
                val ok = ShizukuRotation.restore(this)
                Log.i(TAG, "automation shizuku live reset ok=" + ok)
                AppPrefs.setLastResult(this, "shizuku live reset ok=" + ok)
            }.start()
        }
        if (intent.getBooleanExtra("apply", false)) {
            AppPrefs.setLastResult(this, "shizuku live apply pending")
            Thread {
                val ok = ShizukuRotation.apply(this, target, mode)
                Log.i(TAG, "automation shizuku live apply ok=" + ok)
                AppPrefs.setLastResult(this, "shizuku live apply ok=" + ok)
            }.start()
        }
    }

    private companion object {
        const val TAG = "RotationLock"
    }
}
