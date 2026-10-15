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
 * 锁定方式。**整个应用只有一处设置**：按应用锁定与全局旋转共用同一种方式。
 *  SETTINGS — 改系统旋转设置（快照/还原，兼容性最好，动的是用户设置）
 *  OVERLAY  — 加一个固定 screenOrientation 的 0×0 悬浮窗（不动系统设置，能盖过应用自锁方向，需 Android 12+）
 *  SHIZUKU  — 用 Shizuku(shell 身份) 打开显示级 fixed-to-user-rotation，让系统忽略应用自己请求的方向
 *
 * 历史版本是"每个应用各选一种方式"，实践下来没有道理：同一次锁定里两套机制会互相打架，
 * 而且用户根本记不住"哪个应用用了哪种方式"。现在统一成一个开关。
 */
enum class EngineKind(val storedName: String, val label: String, val desc: String) {
    SETTINGS(
        "settings",
        "系统设置",
        "改系统自动旋转与角度，进入时记快照、离开时精确还原。兼容性最好，但掰不动自己锁死方向的应用。",
    ),
    OVERLAY(
        "overlay",
        "悬浮窗",
        "加一个看不见的 0×0 窗口固定方向，完全不动系统设置；它在应用窗口之上，能盖过应用自己锁死的方向（需 Android 12+）。",
    ),
    SHIZUKU(
        "shizuku",
        "Shizuku 强制",
        "用 Shizuku 以 shell 身份强制显示方向，连自带硬锁的应用也掰得动，四个方向都支持；需要 Shizuku 已启动并授权。",
    ),
    ;

    companion object {
        /** 悬浮窗引擎依赖 API 31 起的方向来源实现（见 OverlayRotation 注释）。 */
        fun from(value: String?): EngineKind = entries.firstOrNull { it.storedName == value } ?: SETTINGS
    }
}

data class RotationConfig(
    val enabled: Boolean,
    val targets: Map<String, OrientationMode>,
    /** 全局唯一的锁定方式：按应用锁定和全局旋转都用这一个。 */
    val engine: EngineKind,
    /**
     * 强力模式：包名 → 被强制到的方向（只可能是 PORTRAIT 或 LANDSCAPE）。
     * 用于应用自己在清单里锁死了方向的场景，由一次性 adb 打开的 compat 覆盖生效。
     */
    val forceModes: Map<String, OrientationMode>,
    /** 全局强制旋转档位；FOLLOW_SYSTEM 表示不接管。 */
    val globalMode: GlobalRotationMode,
)

/**
 * 全部配置 + 锁定会话状态。SharedPreferences + org.json，零额外依赖。
 */
object AppPrefs {

    private const val FILE = "rotation_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGETS = "targets"
    /** 全局唯一的锁定方式（旧版本是 KEY_ENGINES 的"每个应用一份"映射，见 [engine] 的迁移）。 */
    private const val KEY_ENGINE = "engine"
    /** 已废弃：旧版本"每个应用一份"的方式映射，只在 [legacyEngines] 里读一次用于迁移。 */
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

    // ---- 全局强制旋转档位 ----
    private const val KEY_GLOBAL_MODE = "global_mode"
    /** 接管前的 ACCELEROMETER_ROTATION；-1 表示当前没有接管（也是"是否要记快照"的判据）。 */
    private const val KEY_GLOBAL_BASE_AUTO = "global_base_auto"
    private const val KEY_GLOBAL_BASE_USER = "global_base_user"
    /** 接管前的 `wm fixed-to-user-rotation`（Shizuku 方式用，default/enabled/disabled）。 */
    private const val KEY_GLOBAL_BASE_FIXED = "global_base_fixed"

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
        return RotationConfig(
            store.getBoolean(KEY_ENABLED, true),
            map,
            engine(context),
            force,
            GlobalRotationMode.from(store.getString(KEY_GLOBAL_MODE, null)),
        )
    }

    /**
     * 当前锁定方式。
     *
     * 旧版本把方式存在"每个应用一份"的 engines 映射里。升级后统一成一个值：
     * 第一次读取时把旧映射里用过的那种方式（取第一个非默认项）搬过来，用户不会莫名其妙退回系统设置。
     */
    fun engine(context: Context): EngineKind {
        val store = sp(context)
        val stored = store.getString(KEY_ENGINE, null)
        if (stored != null) return EngineKind.from(stored)
        val legacy = legacyEngines(context).values.firstOrNull { it != EngineKind.SETTINGS }
            ?: EngineKind.SETTINGS
        store.edit().putString(KEY_ENGINE, legacy.storedName).apply()
        journal(context, "migrate engine -> " + legacy.storedName)
        return legacy
    }

    fun setEngine(context: Context, kind: EngineKind) {
        sp(context).edit().putString(KEY_ENGINE, kind.storedName).apply()
        journal(context, "setEngine " + kind.storedName)
    }

    /** 旧"每个应用一份"的方式映射，只用于一次性迁移。 */
    private fun legacyEngines(context: Context): Map<String, EngineKind> {
        val raw = sp(context).getString(KEY_ENGINES, null)
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val map = LinkedHashMap<String, EngineKind>()
            json.keys().forEach { key -> map[key] = EngineKind.from(json.optString(key, "")) }
            map
        }.getOrDefault(emptyMap())
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
        journal(context, "removeTarget " + packageName + " (targets=" + map.size + ")")
    }

    private fun writeTargets(context: Context, map: Map<String, OrientationMode>) {
        val json = JSONObject()
        map.forEach { (pkg, mode) -> json.put(pkg, mode.userRotation) }
        sp(context).edit().putString(KEY_TARGETS, json.toString()).apply()
    }

    // ---- 全局强制旋转档位 ----

    fun globalMode(context: Context): GlobalRotationMode =
        GlobalRotationMode.from(sp(context).getString(KEY_GLOBAL_MODE, null))

    fun setGlobalMode(context: Context, mode: GlobalRotationMode) {
        sp(context).edit().putString(KEY_GLOBAL_MODE, mode.storedName).apply()
        journal(context, "setGlobalMode " + mode.storedName)
    }

    /** 接管前的 ACCELEROMETER_ROTATION；-1 表示当前没有接管。 */
    fun globalBaseAuto(context: Context): Int = sp(context).getInt(KEY_GLOBAL_BASE_AUTO, -1)

    /** 接管前的 USER_ROTATION；-1 表示没有记录。 */
    fun globalBaseUser(context: Context): Int = sp(context).getInt(KEY_GLOBAL_BASE_USER, -1)

    /**
     * 接管前的 `wm fixed-to-user-rotation`（Shizuku 方式用）；null 表示还没记录。
     * 这是"显示级"设置，不改回去的话整个屏幕都会一直被固定住。
     */
    fun globalBaseFixed(context: Context): String? =
        sp(context).getString(KEY_GLOBAL_BASE_FIXED, null)?.takeIf { it.isNotEmpty() }

    fun setGlobalBaseFixed(context: Context, value: String) {
        sp(context).edit().putString(KEY_GLOBAL_BASE_FIXED, value).apply()
    }

    /** 首次接管全局时记录用户原本的自动旋转开关与角度，供 release 精确还原。 */
    fun beginGlobal(context: Context) {
        sp(context).edit()
            .putInt(KEY_GLOBAL_BASE_AUTO, RotationController.autoRotate(context))
            .putInt(KEY_GLOBAL_BASE_USER, RotationController.userRotation(context))
            .apply()
    }

    fun clearGlobal(context: Context) {
        sp(context).edit()
            .remove(KEY_GLOBAL_BASE_AUTO)
            .remove(KEY_GLOBAL_BASE_USER)
            .remove(KEY_GLOBAL_BASE_FIXED)
            .apply()
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
