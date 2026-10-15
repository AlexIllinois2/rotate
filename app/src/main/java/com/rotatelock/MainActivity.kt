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
        Thread {
            // 顺手清掉可能残留的显示级固定（上次进程被杀留下的）
            ShizukuRotation.cleanupStaleForce(this)
            // 授权后重放：首次使用的顺序是"启用 → 选 Shizuku → 选档位 → 授权"，
            // 第一次 apply 跑在授权之前必然失败，而授权回来没有任何东西会补一次，
            // 表现为"不生效，禁用再启用才好"。回到前台时若全局档位还挂着就重放一遍，
            // 三种方式都幂等：系统设置重写同值、悬浮窗同角度 no-op、Shizuku 补跑命令。
            // 这同时盖掉了另外两种方式"授权回来不重放"的一类问题。
            if (GlobalRotation.isEngaged(this)) GlobalRotation.apply(this)
        }.start()
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
     *   adb shell am start -n com.rotatelock/.MainActivity \
     *       [--es engine settings|overlay|shizuku] \
     *       [--es target <包名> --ei mode <0..4>] [--ez apply true] [--ez reset true] [--ez remove true] \
     *       [--ez overlayProbe true] [--ez overlayClear true] \
     *       [--es gmode follow|auto_360|auto_270|auto_180|lock_0|lock_90|lock_180|lock_270]
     * mode: 0=竖屏 1=横屏 2=反向竖屏 3=反向横屏 4=保持进入时方向
     * engine: 全局唯一的锁定方式（按应用锁定与全局旋转共用），缺省 settings
     * apply/reset 表示立刻用 Shizuku 执行一次强开/关（结果打到 logcat）
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

        // 锁定方式是全局唯一的（按应用锁定与全局旋转共用）：settings / overlay / shizuku
        if (intent.hasExtra("engine")) {
            val engine = EngineKind.from(intent.getStringExtra("engine"))
            // 和界面上的「锁定方式」卡片走同一条路：清干净旧方式，再按新方式补回全局档位
            RotationController.switchEngine(this, engine)
            AppPrefs.journal(this, "automation set engine=" + engine.storedName)
            Log.i(TAG, "automation set engine=" + engine.storedName)
        }

        // 全局强制旋转档位：follow / auto_360 / auto_270 / auto_180 / lock_0 / lock_90 / lock_180 / lock_270
        val gmode = intent.getStringExtra("gmode")
        if (gmode != null) {
            val mode = GlobalRotationMode.from(gmode)
            AppPrefs.setGlobalMode(this, mode)
            val ok = if (mode.engages) GlobalRotation.apply(this) else GlobalRotation.release(this)
            AppPrefs.setLastResult(this, "global mode=" + mode.storedName + " applied=" + ok)
            AppPrefs.journal(this, "automation global mode=" + mode.storedName + " applied=" + ok)
            Log.i(TAG, "automation global mode=" + mode.storedName + " applied=" + ok)
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
