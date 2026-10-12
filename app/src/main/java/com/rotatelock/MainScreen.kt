package com.rotatelock

import android.content.Context
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRotateApp(refreshTick: Int) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(AppPrefs.config(context)) }
    var canWrite by remember { mutableStateOf(RotationController.canWrite(context)) }
    var overlayGranted by remember { mutableStateOf(OverlayRotation.canDrawOverlay(context)) }
    var notificationsOn by remember { mutableStateOf(LockNotification.canPost(context)) }
    var accessibilityOn by remember { mutableStateOf(RotationAccessibilityService.isEnabled(context)) }
    var shizukuStatus by remember { mutableStateOf(ShizukuBridge.status(context)) }
    /** Shizuku 状态文案只在 refresh() 里算一次：放进 composable 体会每次重组都查一遍 Shizuku。 */
    var shizukuLabel by remember { mutableStateOf(ShizukuBridge.statusLabel(context)) }
    var entries by remember { mutableStateOf<Map<String, AppEntry>>(emptyMap()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var execResult by remember { mutableStateOf<String?>(null) }
    var shizukuTick by remember { mutableIntStateOf(0) }

    fun refresh() {
        config = AppPrefs.config(context)
        canWrite = RotationController.canWrite(context)
        overlayGranted = OverlayRotation.canDrawOverlay(context)
        notificationsOn = LockNotification.canPost(context)
        accessibilityOn = RotationAccessibilityService.isEnabled(context)
        shizukuStatus = ShizukuBridge.status(context)
        shizukuLabel = ShizukuBridge.statusLabel(context)
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    LaunchedEffect(refreshTick) { refresh() }
    // Shizuku 是用 ContentProvider 拿 binder 的，可能晚于界面出现，收到就刷新状态
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { shizukuTick++ }
        val dead = Shizuku.OnBinderDeadListener { shizukuTick++ }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> shizukuTick++ }
        Shizuku.addBinderReceivedListener(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
    }
    LaunchedEffect(shizukuTick) { refresh() }

    LaunchedEffect(config.targets.keys) {
        val packages = config.targets.keys.toList()
        entries = if (packages.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) { AppCatalog.entriesFor(context, packages) }
        }
    }

    /** 配置一变就把当前锁定状态清干净，避免旧方式残留（换方式、删应用、关总开关都走这里）。 */
    fun reloadAndReset() {
        RotationController.resetAll(context)
        config = AppPrefs.config(context)
        execResult = null
    }

    val usesOverlay = config.engines.values.any { it == EngineKind.OVERLAY }
    val usesShizuku = config.engines.values.any { it == EngineKind.SHIZUKU }

    // 配色方案要 remember：否则每次重组都会重建几十个颜色对象，还会让整棵子树的主题值失效
    val colorScheme = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }
    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(context.getString(R.string.app_name)) }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { pickerOpen = true }) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SwitchRow(
                        title = "启用自动切换",
                        desc = "关闭后不再锁定方向，并会还原系统设置",
                        checked = config.enabled,
                        onChange = { value ->
                            AppPrefs.setEnabled(context, value)
                            RotationController.resetAll(context)
                            config = AppPrefs.config(context)
                        },
                    )
                }
                item {
                    StatusCard(
                        title = "修改系统设置",
                        desc = "「系统设置」方式用它改屏幕方向和自动旋转开关",
                        granted = canWrite,
                        onGrant = { openWriteSettings(context) },
                    )
                }
                item {
                    StatusCard(
                        title = "无障碍服务",
                        desc = if (accessibilityOn) {
                            "用于识别当前前台应用，不读取屏幕内容"
                        } else {
                            "被系统关掉了。系统不允许应用自己打开无障碍服务，点「去授权」在系统列表里重新打开即可"
                        },
                        granted = accessibilityOn,
                        onGrant = { openAccessibilitySettings(context) },
                    )
                }
                if (usesOverlay) {
                    item {
                        StatusCard(
                            title = "显示在其他应用上层",
                            desc = if (OverlayRotation.isSupported()) {
                                "「悬浮窗」方式用它锁定方向，不需要改动系统设置"
                            } else {
                                "悬浮窗方式需要 Android 12 及以上（本机是 Android " + Build.VERSION.RELEASE + "），请改用系统设置方式"
                            },
                            granted = overlayGranted,
                            onGrant = { openOverlaySettings(context) },
                        )
                    }
                    item {
                        StatusCard(
                            title = "锁定时通知",
                            desc = "悬浮窗方式锁定时，通知栏会常驻一条「正在为某某应用锁定方向」；" +
                                "点它能回到本应用，离开应用后自动消失",
                            granted = notificationsOn,
                            onGrant = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )
                    }
                }
                if (usesShizuku) {
                    item {
                        StatusCard(
                            title = "Shizuku",
                            desc = shizukuLabel + "；非 root 设备每次开机要用「无线调试」在 Shizuku 里启动一次",
                            granted = shizukuStatus == ShizukuBridge.Status.READY,
                            grantLabel = "去授权",
                            onGrant = {
                                if (ShizukuBridge.status(context) == ShizukuBridge.Status.NO_PERMISSION) {
                                    ShizukuBridge.requestPermission()
                                } else {
                                    if (!ShizukuBridge.openShizuku(context)) openShizukuDownload(context)
                                }
                            },
                        )
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = "保持无障碍服务运行",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "厂商省电、游戏模式、上滑清理都可能把它关掉，而系统不允许应用自己把无障碍服务打开。" +
                                    "下面两件事能明显降低被关掉的概率；万一还是被关了，本应用会直接告诉你。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { openBatterySettings(context) }, modifier = Modifier.weight(1f)) {
                                    Text("关闭电池优化")
                                }
                                TextButton(onClick = { openAppDetails(context) }, modifier = Modifier.weight(1f)) {
                                    Text("自启动 / 后台白名单")
                                }
                            }
                            Row {
                                TextButton(onClick = { openAccessibilitySettings(context) }) {
                                    Text("去系统里检查无障碍开关")
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "已配置的应用",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (config.targets.isEmpty()) {
                    item {
                        Text(
                            text = "还没有配置。点右下角加号选一个应用，再指定它的方向和锁定方式。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(config.targets.keys.toList(), key = { it }) { pkg ->
                    TargetRow(
                        entry = entries[pkg],
                        packageName = pkg,
                        mode = config.targets[pkg] ?: OrientationMode.PORTRAIT,
                        engine = config.engines[pkg] ?: EngineKind.SETTINGS,
                        forceMode = config.forceModes[pkg],
                        onClick = { execResult = null; editing = pkg },
                        onRemove = {
                            AppPrefs.removeTarget(context, pkg)
                            AppPrefs.setForce(context, pkg, null)
                            reloadAndReset()
                        },
                    )
                }
                item {
                    Text(
                        text = "原理：进入你指定的应用时按配置的「锁定方式」锁方向，离开后还原。" +
                            "本应用不读取屏幕内容、不显示常驻通知、没有前台服务。\n" +
                            "• 系统设置：改系统自动旋转与角度，离开时按进入前的状态精确还原，兼容性最好。\n" +
                            "• 悬浮窗：加一个看不见的 0×0 窗口来固定方向，完全不动系统设置；" +
                            "因为它在应用窗口之上，能盖过应用自己锁死的方向（需 Android 12+，大屏设备可能被系统忽略）。\n" +
                            "• Shizuku 强制：用 Shizuku 以 shell 身份强制显示方向（连自带硬锁的应用也掰得动），" +
                            "四个方向加「保持进入时」都支持；进入该应用时生效、离开时自动还原。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item {
                    TextButton(onClick = { RotationController.resetAll(context); refresh() }) {
                        Text("立即还原系统旋转设置")
                    }
                }
                item {
                    TextButton(onClick = {
                        copyToClipboard(context, AppPrefs.journal(context))
                    }) {
                        Text("复制调试日志（排查不稳定问题时用）")
                    }
                }
            }
        }

        editing?.let { pkg ->
            AppConfigDialog(
                title = entries[pkg]?.label ?: pkg,
                packageName = pkg,
                mode = config.targets[pkg] ?: OrientationMode.PORTRAIT,
                engine = config.engines[pkg] ?: EngineKind.SETTINGS,
                overlayGranted = overlayGranted,
                shizukuLabel = shizukuLabel,
                shizukuReady = shizukuStatus == ShizukuBridge.Status.READY,
                busy = busy,
                execResult = execResult,
                onDismiss = { editing = null },
                onPickMode = { mode ->
                    AppPrefs.setTarget(context, pkg, mode)
                    reloadAndReset()
                },
                onPickEngine = { engine ->
                    AppPrefs.setEngine(context, pkg, engine)
                    reloadAndReset()
                },
                onGrantOverlay = { openOverlaySettings(context) },
                onRequestShizuku = { ShizukuBridge.requestPermission() },
                onOpenShizuku = { if (!ShizukuBridge.openShizuku(context)) openShizukuDownload(context) },
                onApplyShizuku = {
                    val mode = config.targets[pkg] ?: OrientationMode.PORTRAIT
                    scope.launch {
                        busy = true
                        execResult = null
                        val result = withContext(Dispatchers.IO) {
                            if (ShizukuRotation.isReady(context)) {
                                val ok = ShizukuRotation.apply(context, pkg, mode)
                                ShizukuBridge.Result(ok, if (ok) "已用 Shizuku 强制该应用方向" else "执行失败")
                            } else {
                                ShizukuBridge.Result(false, "Shizuku 未就绪：先在 Shizuku 里用「无线调试」启动，并授权本应用")
                            }
                        }
                        busy = false
                        execResult = if (result.ok) "已强制（进入该应用时会自动生效）" else "失败：" + result.message
                    }
                },
                onResetShizuku = {
                    scope.launch {
                        busy = true
                        execResult = null
                        val ok = withContext(Dispatchers.IO) { ShizukuRotation.restore(context) }
                        busy = false
                        execResult = if (ok) "已还原" else "没有正在生效的强制，或 Shizuku 未就绪"
                    }
                },
            )
        }

        if (pickerOpen) {
            AppPickerSheet(
                configured = config.targets.keys,
                onDismiss = { pickerOpen = false },
                onPick = { entry ->
                    AppPrefs.setTarget(context, entry.packageName, OrientationMode.PORTRAIT)
                    AppPrefs.setEngine(context, entry.packageName, EngineKind.SETTINGS)
                    config = AppPrefs.config(context)
                    entries = entries + (entry.packageName to entry)
                    pickerOpen = false
                    execResult = null
                    editing = entry.packageName
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    desc: String,
    granted: Boolean,
    onGrant: () -> Unit,
    grantLabel: String = "去授权",
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (extraLabel != null && onExtra != null) {
                TextButton(onClick = onExtra) { Text(extraLabel) }
            }
            if (granted) {
                Text(
                    text = "已完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onGrant) { Text(grantLabel) }
            }
        }
    }
}

@Composable
private fun TargetRow(
    entry: AppEntry?,
    packageName: String,
    mode: OrientationMode,
    engine: EngineKind,
    forceMode: OrientationMode?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(entry)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry?.label ?: packageName, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mode.label + " · " + engine.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (engine == EngineKind.SHIZUKU && forceMode != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "已生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            TextButton(onClick = onRemove) { Text("移除") }
        }
    }
}

@Composable
private fun AppIcon(entry: AppEntry?) {
    // 位图在 AppCatalog 里（IO 线程）就转好了，这里只负责画
    val bitmap = entry?.icon
    if (bitmap == null) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    }
}

/**
 * 一个应用的完整配置：方向 + 锁定方式（系统设置 / 悬浮窗 / Shizuku 强制）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppConfigDialog(
    title: String,
    packageName: String,
    mode: OrientationMode,
    engine: EngineKind,
    overlayGranted: Boolean,
    shizukuLabel: String,
    shizukuReady: Boolean,
    busy: Boolean,
    execResult: String?,
    onDismiss: () -> Unit,
    onPickMode: (OrientationMode) -> Unit,
    onPickEngine: (EngineKind) -> Unit,
    onGrantOverlay: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onApplyShizuku: () -> Unit,
    onResetShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val modes = OrientationMode.entries.toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("方向", style = MaterialTheme.typography.titleSmall)
                modes.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickMode(item) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = item == mode, onClick = { onPickMode(item) })
                        Spacer(Modifier.width(8.dp))
                        Text(item.label)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("锁定方式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineKind.entries.forEach { item ->
                        FilterChip(
                            selected = item == engine,
                            onClick = { onPickEngine(item) },
                            label = { Text(item.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (engine) {
                    EngineKind.SETTINGS -> Text(
                        text = "改系统自动旋转与角度，进入时记快照、离开时精确还原。兼容性最好。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    EngineKind.OVERLAY -> Column {
                        Text(
                            text = "加一个 0×0 的透明悬浮窗来固定方向，不改系统设置；" +
                                "它在应用窗口之上，所以能盖过应用自己锁死的方向。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!OverlayRotation.isSupported()) {
                            Text(
                                text = "需要 Android 12 及以上（本机 Android " + Build.VERSION.RELEASE + "），请改用系统设置方式。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!overlayGranted) {
                            TextButton(onClick = onGrantOverlay) { Text("去授权「显示在其他应用上层」") }
                        }
                    }

                    EngineKind.SHIZUKU -> Column {
                        Text(
                            text = "用 Shizuku 以 shell 身份强制显示方向（打开显示级 fixed-to-user-rotation，" +
                                "系统会忽略应用自己请求的方向），四个方向加「保持进入时」都支持；" +
                                "进入该应用时自动生效、离开时自动还原。需要 Shizuku 正在运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = shizukuLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (shizukuReady) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!shizukuReady) {
                                TextButton(onClick = onRequestShizuku) { Text("获取授权") }
                                TextButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
                            }
                            TextButton(onClick = onApplyShizuku, enabled = shizukuReady && !busy) {
                                Text("立即强制一次")
                            }
                            TextButton(onClick = onResetShizuku, enabled = !busy) {
                                Text("还原")
                            }
                        }
                        if (busy) {
                            Text("执行中…", style = MaterialTheme.typography.bodySmall)
                        }
                        execResult?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(
                            onClick = {
                                copyToClipboard(context, forceCommands(packageName, mode))
                            },
                        ) {
                            Text("复制「一次性持久覆盖」命令（备选：不用 Shizuku，在电脑上执行一次；只支持竖屏/横屏）")
                        }
                        Text(
                            text = "Shizuku 需要先用「无线调试」启动：打开 Shizuku → 通过无线调试启动 → 回到本应用点「获取授权」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    configured: Set<String>,
    onDismiss: () -> Unit,
    onPick: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppCatalog.listForPicker(context) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Text("选择应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("搜索") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val filtered = remember(list, query) {
                    if (query.isBlank()) {
                        list
                    } else {
                        list.filter {
                            it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.packageName }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(entry)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = entry.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (entry.packageName in configured) {
                                Text(
                                    text = "已配置",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openWriteSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:" + context.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openShizukuDownload(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Shizuku 强制方式的一次性命令（没装 Shizuku 时可以在电脑上执行）。
 *
 * 原理按 AOSP frameworks/base 源码逐条核对过：
 * 1. ActivityRecord.getOverrideOrientation() 会走 AppCompatOrientationPolicy.overrideOrientationIfNeeded()，
 *    应用自己请求的固定方向在没开 OVERRIDE_ANY_ORIENTATION 时原样返回 —— 这就是"改系统设置掰不动它"的原因。
 * 2. 开了 OVERRIDE_ANY_ORIENTATION 之后，另外那几个覆盖才能盖过应用自己的请求：
 *    OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT            -> SCREEN_ORIENTATION_PORTRAIT（竖屏）
 *    OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE    -> SCREEN_ORIENTATION_REVERSE_LANDSCAPE（横屏）
 * 3. 不能用 OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR：DisplayRotation.rotationForOrientation() 里
 *    NOSENSOR 既不走传感器分支也不走旋转锁分支，最终落到 default 返回 ROTATION_0，也就是固定回自然竖屏。
 * 4. am compat 支持符号名（PlatformCompat.lookupChangeId），且默认会重启目标应用让改动立即生效。
 */
private fun forceCommands(packageName: String, mode: OrientationMode): String {
    val target = if (mode == OrientationMode.LANDSCAPE) {
        "OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE"
    } else {
        "OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT"
    }
    return "adb shell am compat enable OVERRIDE_ANY_ORIENTATION " + packageName + "\n" +
        "adb shell am compat enable " + target + " " + packageName
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("approtate-force-mode", text)) }
}
