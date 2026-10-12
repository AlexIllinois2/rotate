package com.rotatelock

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.util.Locale

/**
 * 图标在 [AppCatalog] 里就已经转成位图（那些查询本来就跑在 IO 线程上）。
 * 如果在 Compose 里现转，滑动列表时每一行都要在主线程做一次 Drawable -> Bitmap，
 * 几百个应用的列表就会明显掉帧。
 */
data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * 通过 <queries> 声明的 MAIN/LAUNCHER 查询枚举应用，不使用 QUERY_ALL_PACKAGES。
 */
object AppCatalog {

    private const val ICON_PX = 96

    private fun toIconBitmap(drawable: Drawable?): ImageBitmap? =
        runCatching { drawable?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap() }.getOrNull()

    @Volatile
    private var launchableCache: Set<String>? = null

    /**
     * 设备上所有可启动应用（含桌面，也含本应用）。
     * 用来区分"真的切换到了另一个应用"和"系统弹窗 / 输入法"。
     */
    fun launchable(context: Context): Set<String> {
        launchableCache?.let { return it }
        val set = HashSet<String>()
        query(context).forEach { info ->
            val pkg = info.activityInfo?.packageName
            if (pkg != null) set.add(pkg)
        }
        // 有些桌面（例如 Pixel Launcher）在 HOME 里跑的是另一个包，那个包自己并没有
        // MAIN/LAUNCHER 入口 —— 只按 launcher 查询会漏掉它，导致"回到桌面"被误判成
        // "还在目标应用里"，锁定会话不结束、快照不刷新。把解析出来的 HOME 包也算进去。
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager
                .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName?.let { set.add(it) }
        }
        launchableCache = set
        return set
    }

    /** 供选择面板使用：排除本应用，按中文名称排序。 */
    fun listForPicker(context: Context): List<AppEntry> {
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        query(context).forEach { info ->
            val appInfo = info.activityInfo
            if (appInfo == null) return@forEach
            val pkg = appInfo.packageName
            if (pkg == context.packageName || !seen.add(pkg)) return@forEach
            runCatching {
                result.add(
                    AppEntry(
                        pkg,
                        appInfo.loadLabel(context.packageManager).toString(),
                        toIconBitmap(appInfo.loadIcon(context.packageManager)),
                    )
                )
            }
        }
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        result.sortWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        return result
    }

    /** 为已配置的包名补齐名称与图标（例如冷启动时）。 */
    fun entriesFor(context: Context, packages: Collection<String>): Map<String, AppEntry> {
        val result = HashMap<String, AppEntry>()
        packages.forEach { pkg ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                result[pkg] = AppEntry(
                    pkg,
                    info.loadLabel(context.packageManager).toString(),
                    toIconBitmap(info.loadIcon(context.packageManager)),
                )
            }
        }
        return result
    }

    private fun query(context: Context): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    }

    /**
     * 调试用一行：桌面包解析结果 + 它在不在"可启动"集合里。
     * 桌面（HOME）应用往往没有自己的 MAIN/LAUNCHER 图标，而包可见性又会把"只声明 HOME"的包藏起来，
     * 这行日志就是为了确认这个前提（真机上"回桌面不还原"基本都出在这里）。
     */
    fun debugHome(context: Context): String {
        val resolved = runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        val homePackage = resolved?.activityInfo?.packageName ?: "null"
        val set = launchable(context)
        return "homePkg=" + homePackage + " homeInSet=" + (homePackage != "null" && homePackage in set) +
            " launchableCount=" + set.size
    }
}
