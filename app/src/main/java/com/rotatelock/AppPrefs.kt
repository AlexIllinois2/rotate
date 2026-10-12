package com.rotatelock

import android.content.Context
import org.json.JSONObject

/**
 * 每个应用可选的方向模式。userRotation 即 Settings.System.USER_ROTATION 的取值。
 */
enum class OrientationMode(val userRotation: Int, val label: String) {
    PORTRAIT(0, "锁定竖屏"),
    LANDSCAPE(1, "锁定横屏"),
    REVERSE_PORTRAIT(2, "锁定反向竖屏"),
    REVERSE_LANDSCAPE(3, "锁定反向横屏"),
    KEEP_CURRENT(-1, "保持进入时的方向");

    companion object {
        fun from(value: Int): OrientationMode = entries.firstOrNull { it.userRotation == value } ?: PORTRAIT
    }
}

/**
 * 锁定方式（按应用选择，默认 SETTINGS）：
 *  SETTINGS — 改系统旋转设置（快照/还原，兼容性最好，动的是用户设置）
 *  OVERLAY  — 加一个固定 screenOrientation 的 0×0 悬浮窗（不动系统设置，能盖过应用自锁方向，需 Android 12+）
 *  SHIZUKU  — 用 Shizuku(shell 身份) 一次性打开系统的 compat 覆盖，让系统忽略该应用自己请求的方向
 */
enum class EngineKind(val storedName: String, val label: String) {
    SETTINGS("settings", "系统设置"),
    OVERLAY("overlay", "悬浮窗"),
    SHIZUKU("shizuku", "Shizuku 强制");

    companion object {
        /** 悬浮窗引擎依赖 API 31 起的方向来源实现（见 OverlayRotation 注释）。 */
        fun from(value: String?): EngineKind = entries.firstOrNull { it.storedName == value } ?: SETTINGS
    }
}

data class RotationConfig(
    val enabled: Boolean,
    val targets: Map<String, OrientationMode>,
    /** 包名 → 锁定方式；没有条目即 SETTINGS。 */
    val engines: Map<String, EngineKind>,
    /**
     * 强力模式：包名 → 被强制到的方向（只可能是 PORTRAIT 或 LANDSCAPE）。
     * 用于应用自己在清单里锁死了方向的场景，由一次性 adb 打开的 compat 覆盖生效。
     */
    val forceModes: Map<String, OrientationMode>,
)

/**
 * 全部配置 + 锁定会话状态。SharedPreferences + org.json，零额外依赖。
 */
object AppPrefs {

    private const val FILE = "rotation_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGETS = "targets"
    private const val KEY_ENGINES = "engines"
    private const val KEY_FORCE = "force_pkgs"
    private const val KEY_LOCKED_PKG = "locked_pkg"
    private const val KEY_LOCKED_ENGINE = "locked_engine"
    private const val KEY_LOCKED_ROTATION = "locked_rotation"
    private const val KEY_BASE_AUTO = "base_auto_rotate"
    private const val KEY_BASE_USER = "base_user_rotation"
    private const val KEY_BASE_FIXED = "base_fixed_to_user"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_JOURNAL = "journal"
    private const val JOURNAL_MAX_LINES = 60

    fun config(context: Context): RotationConfig {
        val store = sp(context)
        val map = LinkedHashMap<String, OrientationMode>()
        val raw = store.getString(KEY_TARGETS, null)
        if (!raw.isNullOrEmpty()) {
            runCatching {
                val json = JSONObject(raw)
                json.keys().forEach { key -> map[key] = OrientationMode.from(json.optInt(key, 0)) }
            }
        }
        val force = LinkedHashMap<String, OrientationMode>()
        val rawForce = store.getString(KEY_FORCE, null)
        if (!rawForce.isNullOrEmpty()) {
            runCatching {
                val json = JSONObject(rawForce)
                json.keys().forEach { key ->
                    val name = json.optString(key, "")
                    OrientationMode.entries.firstOrNull { it.name == name }?.let { force[key] = it }
                }
            }
        }
        val engines = LinkedHashMap<String, EngineKind>()
        val rawEngines = store.getString(KEY_ENGINES, null)
        if (!rawEngines.isNullOrEmpty()) {
            runCatching {
                val json = JSONObject(rawEngines)
                json.keys().forEach { key -> engines[key] = EngineKind.from(json.optString(key, "")) }
            }
        }
        return RotationConfig(store.getBoolean(KEY_ENABLED, true), map, engines, force)
    }

    fun engine(context: Context, packageName: String): EngineKind =
        config(context).engines[packageName] ?: EngineKind.SETTINGS

    fun setEngine(context: Context, packageName: String, kind: EngineKind) {
        val map = LinkedHashMap(config(context).engines)
        if (kind == EngineKind.SETTINGS) map.remove(packageName) else map[packageName] = kind
        val json = JSONObject()
        map.forEach { (pkg, value) -> json.put(pkg, value.storedName) }
        sp(context).edit().putString(KEY_ENGINES, json.toString()).apply()
    }

    /** mode 为 null 表示关闭该应用的强力模式。 */
    fun setForce(context: Context, packageName: String, mode: OrientationMode?) {
        val map = LinkedHashMap(config(context).forceModes)
        if (mode == null) map.remove(packageName) else map[packageName] = mode
        val json = JSONObject()
        map.forEach { (pkg, value) -> json.put(pkg, value.name) }
        sp(context).edit().putString(KEY_FORCE, json.toString()).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setTarget(context: Context, packageName: String, mode: OrientationMode) {
        val map = LinkedHashMap(config(context).targets)
        map[packageName] = mode
        writeTargets(context, map)
        journal(context, "setTarget " + packageName + " -> " + mode.userRotation + " (targets=" + map.size + ")")
    }

    fun removeTarget(context: Context, packageName: String) {
        val map = LinkedHashMap(config(context).targets)
        map.remove(packageName)
        writeTargets(context, map)
        setEngine(context, packageName, EngineKind.SETTINGS)
        journal(context, "removeTarget " + packageName + " (targets=" + map.size + ")")
    }

    private fun writeTargets(context: Context, map: Map<String, OrientationMode>) {
        val json = JSONObject()
        map.forEach { (pkg, mode) -> json.put(pkg, mode.userRotation) }
        sp(context).edit().putString(KEY_TARGETS, json.toString()).apply()
    }

    /**
     * 最近一次 Shizuku 操作的结果，写成一行机器可读的文本（自动化验证脚本会从
     * /data/data/com.rotatelock/shared_prefs/rotation_prefs.xml 里读它）。
     */
    fun setLastResult(context: Context, text: String) {
        sp(context).edit().putString(KEY_LAST_RESULT, text).apply()
    }

    fun lastResult(context: Context): String = sp(context).getString(KEY_LAST_RESULT, "").orEmpty()

    /**
     * 决策日志（最近 JOURNAL_MAX_LINES 行，带时间）。悬浮窗"不稳定"这类问题只有把它每一步的
     * 决定记下来才查得清：是应用自己撤的窗口，还是系统把窗口/服务弄没了。
     */
    fun journal(context: Context, line: String) {
        runCatching {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val current = sp(context).getString(KEY_JOURNAL, "").orEmpty()
            val lines = current.split('\n').filter { it.isNotEmpty() }
            val pid = android.os.Process.myPid()
            val kept = (lines + (stamp + " [" + pid + "] " + line)).takeLast(JOURNAL_MAX_LINES)
            sp(context).edit().putString(KEY_JOURNAL, kept.joinToString("\n")).apply()
        }
    }

    fun journal(context: Context): String = sp(context).getString(KEY_JOURNAL, "").orEmpty()

    fun clearJournal(context: Context) {
        sp(context).edit().remove(KEY_JOURNAL).apply()
    }

    // ---- 锁定会话状态：用于离开目标应用时精确还原 ----

    /** 当前正被锁定的目标应用；null 表示没有处于锁定态。 */
    fun lockedPackage(context: Context): String? =
        sp(context).getString(KEY_LOCKED_PKG, null)?.takeIf { it.isNotEmpty() }

    /** 当前锁定会话用的是哪种方式，决定离开时怎么解除。 */
    fun lockedEngine(context: Context): EngineKind? =
        sp(context).getString(KEY_LOCKED_ENGINE, null)?.let { EngineKind.from(it) }

    fun lockedRotation(context: Context): Int = sp(context).getInt(KEY_LOCKED_ROTATION, -1)

    fun baseAutoRotate(context: Context): Int = sp(context).getInt(KEY_BASE_AUTO, -1)

    fun baseUserRotation(context: Context): Int = sp(context).getInt(KEY_BASE_USER, -1)

    /** Shizuku 方式用：进入前的 `wm fixed-to-user-rotation` 值（default/enabled/disabled）。 */
    fun baseFixedToUser(context: Context): String = sp(context).getString(KEY_BASE_FIXED, "default").orEmpty()

    /**
     * 只允许在"尚未处于锁定态"时调用，记录用户原始的系统旋转设置。
     * 这是防止把自己的锁定态当成用户设置存档的关键。
     */
    fun beginLock(
        context: Context,
        packageName: String,
        rotation: Int,
        engine: EngineKind,
        baseFixedToUser: String? = null,
    ) {
        val editor = sp(context).edit()
            .putString(KEY_LOCKED_PKG, packageName)
            .putString(KEY_LOCKED_ENGINE, engine.storedName)
            .putInt(KEY_LOCKED_ROTATION, rotation)
        // 悬浮窗引擎不碰系统设置，没有可还原的东西；另外两种都先存快照再动手
        if (engine == EngineKind.SETTINGS || engine == EngineKind.SHIZUKU) {
            editor.putInt(KEY_BASE_AUTO, RotationController.autoRotate(context))
            editor.putInt(KEY_BASE_USER, RotationController.userRotation(context))
        }
        if (baseFixedToUser != null) {
            editor.putString(KEY_BASE_FIXED, baseFixedToUser)
        }
        editor.apply()
    }

    fun updateLock(context: Context, packageName: String, rotation: Int, engine: EngineKind) {
        sp(context).edit()
            .putString(KEY_LOCKED_PKG, packageName)
            .putString(KEY_LOCKED_ENGINE, engine.storedName)
            .putInt(KEY_LOCKED_ROTATION, rotation)
            .apply()
    }

    fun clearLock(context: Context) {
        sp(context).edit()
            .remove(KEY_LOCKED_PKG)
            .remove(KEY_LOCKED_ENGINE)
            .remove(KEY_LOCKED_ROTATION)
            .remove(KEY_BASE_AUTO)
            .remove(KEY_BASE_USER)
            .remove(KEY_BASE_FIXED)
            .apply()
    }

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
