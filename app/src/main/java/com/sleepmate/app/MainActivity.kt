package com.sleepmate.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledMonitorApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap
)

private enum class Screen {
    HOME, SETTINGS, MONITOR_APPS, STRATEGY, ABOUT, LOGS
}

class MainActivity : ComponentActivity() {
    var cameraGranted by mutableStateOf(false)
    var adminActive by mutableStateOf(false)
    var usageAccessGranted by mutableStateOf(false)
    var overlayPermissionGranted by mutableStateOf(false)
    var notificationsGranted by mutableStateOf(true)
    var installedMonitorApps by mutableStateOf<List<InstalledMonitorApp>>(emptyList())
    var appsLoading by mutableStateOf(false)
    private var appLoadJob: Job? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent { SleepGuardApp(this) }
        ProtectionService.restoreIfEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        ProtectionService.restoreIfEnabled(this)
    }

    fun refreshPermissions() {
        cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        notificationsGranted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminActive = dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        usageAccessGranted = try {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        loadLaunchableApps()
    }

    @Suppress("DEPRECATION")
    private fun loadLaunchableApps() {
        if (appsLoading) return
        appLoadJob?.cancel()
        appLoadJob = lifecycleScope.launch {
            appsLoading = true
            val apps = withContext(Dispatchers.IO) {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcherIntent, 0)
                    .mapNotNull { info ->
                        val appPackage = info.activityInfo?.packageName ?: return@mapNotNull null
                        if (appPackage == packageName) return@mapNotNull null
                        try {
                            val drawable = info.loadIcon(packageManager)
                            val width = 96
                            val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            drawable.setBounds(0, 0, width, width)
                            drawable.draw(canvas)
                            InstalledMonitorApp(
                                packageName = appPackage,
                                label = info.loadLabel(packageManager)?.toString()?.ifBlank { appPackage } ?: appPackage,
                                icon = bitmap
                            )
                        } catch (_: Exception) { null }
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            installedMonitorApps = apps
            appsLoading = false
        }
    }

    fun requestCamera() = cameraPermission.launch(android.Manifest.permission.CAMERA)
    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun openUsageAccess() = startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    fun openAdminActivation() {
        val component = ComponentName(this, SleepGuardAdminReceiver::class.java)
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "允许 SleepMate 在确认符合睡眠条件后锁定屏幕。")
        })
    }
}

@Composable
private fun SleepGuardApp(activity: MainActivity) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val running by ProtectionService.running.collectAsState()
    var selectedPackages by remember {
        mutableStateOf(MediaAppPreferences.getSelectedPackages(activity))
    }

    fun updateSelection(updated: Set<String>) {
        selectedPackages = updated
        MediaAppPreferences.saveSelectedPackages(activity, updated)
        if (ProtectionService.running.value) {
            activity.startService(
                Intent(activity, ProtectionService::class.java)
                    .setAction(ProtectionService.ACTION_SELECTION_CHANGED)
            )
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (Screen.valueOf(screen)) {
                Screen.HOME -> HomeScreen(
                    activity = activity,
                    running = running,
                    selectedCount = selectedPackages.size,
                    onSettings = { screen = Screen.SETTINGS.name },
                    onStart = {
                        activity.refreshPermissions()
                        if (selectedPackages.isEmpty()) {
                            android.widget.Toast.makeText(
                                activity,
                                "请先选择需要监测的应用。",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else if (!activity.cameraGranted) activity.requestCamera()
                        else if (!activity.usageAccessGranted) activity.openUsageAccess()
                        else {
                            activity.requestNotificationsIfNeeded()
                            if (!activity.adminActive) {
                                android.widget.Toast.makeText(
                                    activity,
                                    "监测可以启动，但未激活设备管理员，确认入睡后无法自动锁屏。建议先激活自动锁屏权限。",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            ProtectionService.start(activity)
                        }
                    },
                    onStop = {
                        ProtectionService.stop(activity)
                    }
                )
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.HOME.name },
                    onMonitorApps = { screen = Screen.MONITOR_APPS.name },
                    onStrategy = { screen = Screen.STRATEGY.name },
                    onAbout = { screen = Screen.ABOUT.name },
                    onLogs = { screen = Screen.LOGS.name },
                    onTestLock = {
                        activity.refreshPermissions()
                        if (activity.adminActive) {
                            (activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                        } else activity.openAdminActivation()
                    }
                )
                Screen.MONITOR_APPS -> MonitorAppsScreen(
                    activity = activity,
                    selectedPackages = selectedPackages,
                    onBack = { screen = Screen.SETTINGS.name },
                    onSelectionChanged = ::updateSelection
                )
                Screen.STRATEGY -> StrategyScreen(onBack = { screen = Screen.SETTINGS.name })
                Screen.ABOUT -> AboutScreen(activity = activity, onBack = { screen = Screen.SETTINGS.name })
                Screen.LOGS -> LogsScreen(activity = activity, onBack = { screen = Screen.SETTINGS.name })
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("返回 ›") }
    }
}

@Composable
private fun HomeScreen(
    activity: MainActivity,
    running: Boolean,
    selectedCount: Int,
    onSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_sleepguard_foreground),
                contentDescription = "SleepMate 正式图标",
                modifier = Modifier.size(62.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SleepMate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("让用户安心入睡，让手机及时休息", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSettings) { Text("设置") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("保护状态", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running) "运行中：仅监测已选择的应用"
                    else "已停止",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text("已选择 $selectedCount 个应用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onStart, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                    Text("启动智能保护")
                }
                OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.fillMaxWidth()) {
                    Text("停止保护")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限状态", style = MaterialTheme.typography.titleMedium)
                PermissionLine("摄像头", activity.cameraGranted)
                PermissionLine("运行通知（Android 13+）", activity.notificationsGranted)
                PermissionLine("使用情况访问", activity.usageAccessGranted)
                PermissionLine("悬浮窗特殊权限（辅助后台返回桌面）", activity.overlayPermissionGranted)
                PermissionLine("设备管理员（自动锁屏）", activity.adminActive)
                if (!activity.adminActive) {
                    Text(
                        "设备管理员用于确认入睡后直接锁屏。未激活时仍可启动监测，但会尝试返回桌面，效果受系统限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(onClick = { activity.requestCamera() }, Modifier.fillMaxWidth()) { Text("授权摄像头") }
                OutlinedButton(onClick = { activity.openUsageAccess() }, Modifier.fillMaxWidth()) { Text("开启使用情况访问") }
                OutlinedButton(onClick = { activity.openOverlayPermission() }, Modifier.fillMaxWidth()) { Text("允许显示在其他应用上层") }
                Text(
                    "此权限用于申请 Android 后台启动桌面的例外；SleepMate 不会创建悬浮按钮或遮挡其他应用。是否生效仍取决于手机系统。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { activity.openAdminActivation() }, Modifier.fillMaxWidth()) { Text("激活自动锁屏权限") }
                TextButton(onClick = { activity.refreshPermissions() }, modifier = Modifier.align(Alignment.End)) { Text("刷新权限状态") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("系统运行提示", style = MaterialTheme.typography.titleMedium)
                Text("小米 / Redmi 等系统建议为 SleepMate 开启自启动、允许后台弹出界面，并将电池策略设为无限制。")
                Text("应用列表只显示具有桌面启动入口的已安装应用；前台识别依赖 Android 使用情况访问权限；部分系统可能延迟更新应用切换状态。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionLine(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (granted) "已授权" else "未授权", color = if (granted) Color(0xFF16845B) else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onMonitorApps: () -> Unit,
    onStrategy: () -> Unit,
    onAbout: () -> Unit,
    onLogs: () -> Unit,
    onTestLock: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsEntry("监测应用管理", "选择需要纳入睡眠保护的应用", onMonitorApps)
            SettingsEntry("低功耗监测策略", "查看检测流程、触发条件和时间安排", onStrategy)
            SettingsEntry("关于 SleepMate", "项目介绍、版本信息、开源协议", onAbout)
            SettingsEntry("运行日志", "查看前台应用监测、异常恢复与返回桌面诊断记录", onLogs)
            Spacer(Modifier.height(8.dp))
            Text("辅助功能", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onTestLock, modifier = Modifier.fillMaxWidth()) { Text("测试锁屏") }
            Text("仅在已激活设备管理员权限时执行锁屏测试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun MonitorAppsScreen(
    activity: MainActivity,
    selectedPackages: Set<String>,
    onBack: () -> Unit,
    onSelectionChanged: (Set<String>) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val allApps = activity.installedMonitorApps
    val sortedApps = remember(allApps, selectedPackages) {
        allApps.sortedWith(compareByDescending<InstalledMonitorApp> { it.packageName in selectedPackages }
            .thenBy { it.label.lowercase() })
    }
    val filteredApps = remember(sortedApps, query) {
        if (query.isBlank()) sortedApps
        else sortedApps.filter {
            it.label.contains(query.trim(), ignoreCase = true) ||
                it.packageName.contains(query.trim(), ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("监测应用管理", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("搜索应用名称或包名") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") }
            }
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "前台使用所选应用时启动监测",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("已选 ${selectedPackages.size} 个", style = MaterialTheme.typography.labelLarge)
            TextButton(
                onClick = { activity.refreshPermissions() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("刷新") }
        }
        HorizontalDivider()
        if (activity.appsLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredApps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(if (allApps.isEmpty()) "没有读取到可启动的应用，请刷新。" else "没有找到匹配的应用。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val selected = app.packageName in selectedPackages
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                val updated = selectedPackages.toMutableSet()
                                if (selected) updated.remove(app.packageName) else updated.add(app.packageName)
                                onSelectionChanged(updated)
                            }
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.label + " 图标",
                            modifier = Modifier.size(42.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        if (selectedPackages.isEmpty()) {
            Text(
                "当前没有选择任何应用，不会启动睡眠监测。",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StrategyScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("低功耗监测策略", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("工作流程", style = MaterialTheme.typography.titleMedium)
            Text("1. 仅当你勾选的应用处于前台时，SleepMate 才启动活动监测；离开全部所选应用后暂停。")
            Text("2. 加速度传感器在所选应用进入前台后启动。设备连续约 5 分钟没有明显活动，并且手机保持静止约 8 秒后，才尝试启动摄像头。")
            Text("3. 单次摄像头检测窗口最长约 30 秒。睡眠判断有以下三条路径：")
            Text("A. 双眼闭合：成功识别到左右眼，且双眼睁眼概率均低于 0.25，持续约 3 秒；同时手机满足静止条件。")
            Text("B. 窗口内无人脸：整个 30 秒检测窗口都没有检测到人脸；同时摄像头取得有效检测画面，手机满足静止条件。")
            Text("C. 检测到人脸但无法识别双眼：窗口内至少检测到过一次人脸，但整个 30 秒窗口始终没有成功获得左右眼概率；同时摄像头取得有效检测画面，手机满足静止条件。")
            Text("摄像头无法启动、检测异常或没有有效画面时，不按睡眠处理。只要本轮任何一帧成功获得左右眼概率，路径 C 就不成立。")
            Text("4. 达到任一睡眠判定路径后，停止摄像头与活动检测；设备管理员已授权时直接锁定屏幕，未授权时尝试返回桌面。")
            Text("5. 屏幕熄灭后不再进行睡眠检测，也不运行摄像头；重新亮屏后，仅当所选应用仍在前台时才重新开始监测。")
            HorizontalDivider()
            Text("复查节奏", style = MaterialTheme.typography.titleMedium)
            Text("未发现持续闭眼时，计划按 15、20、25 分钟递增复查，之后最多每 30 分钟复查。")
            Text("用户离开所选应用、用户活动或设备明显移动时，应取消当前睡眠候选并重新计时。")
            HorizontalDivider()
            Text("隐私与系统限制", style = MaterialTheme.typography.titleMedium)
            Text("摄像头只在满足检测条件时短暂调用，画面设计为在本机处理，不上传。")
            Text("Android 对后台摄像头访问有严格限制。屏幕关闭或应用处于后台时，系统可能不允许启动摄像头；实际行为受 Android 版本和厂商系统影响。")
            Text("普通应用无法可靠读取其他应用内的全部触摸事件，设备运动只能作为活动代理信号。前台应用识别依赖 Android 使用情况访问权限；系统多窗口和厂商后台策略可能影响识别。")
            Text("以上为当前设计策略，不代表睡眠识别准确率、后台准点运行或省电效果已经通过真机验证。")
        }
    }
}

@Composable
private fun AboutScreen(activity: MainActivity, onBack: () -> Unit) {
    val versionName = remember {
        try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "未知"
        } catch (_: Exception) { "未知" }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("关于 SleepMate", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_sleepguard_foreground),
                contentDescription = "SleepMate 正式图标",
                modifier = Modifier.size(62.dp),
                contentScale = ContentScale.Fit
            )
            Text("SleepMate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("让用户安心入睡，让手机及时休息", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            AboutLine("项目用途", "在用户使用指定应用时，尝试识别入睡状态，并在符合条件后直接锁定屏幕，减少不必要的亮屏耗电。")
            AboutLine("版本", versionName)
            AboutLine("项目状态", "开发原型，自动睡眠检测链路尚未完成全面构建与真机验证。")
            AboutLine("开源协议", "GNU General Public License v3.0 only（GPL-3.0-only）")
            AboutLine("隐私原则", "以本机处理为设计目标，不上传摄像头画面或人脸数据。")
            TextButton(onClick = {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Aiccest/SleepMate-Android")))
            }) { Text("访问 GitHub 项目主页") }
        }
    }
}

@Composable
private fun AboutLine(title: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogsScreen(activity: MainActivity, onBack: () -> Unit) {
    var entries by remember { mutableStateOf(SleepMateLog.read(activity)) }
    var earOnly by rememberSaveable { mutableStateOf(false) }
    val visibleEntries = if (earOnly) entries.filter { it.contains("[EAR_TEST]") } else entries
    Column(Modifier.fillMaxSize()) {
        PageHeader("运行日志", onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { earOnly = false }) { Text("全部日志") }
                OutlinedButton(onClick = { earOnly = true }) { Text("EAR测试记录") }
                OutlinedButton(onClick = { entries = SleepMateLog.read(activity) }) { Text("刷新") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SleepMate完整运行日志", entries.joinToString("\n")))
                    android.widget.Toast.makeText(activity, "完整日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                }, enabled = entries.isNotEmpty()) { Text("复制日志") }
                OutlinedButton(onClick = {
                    val earEntries = entries.filter { it.contains("[EAR_TEST]") }
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SleepMate EAR测试记录", earEntries.joinToString("\n")))
                    android.widget.Toast.makeText(activity, "EAR记录已复制", android.widget.Toast.LENGTH_SHORT).show()
                }, enabled = entries.any { it.contains("[EAR_TEST]") }) { Text("复制EAR记录") }
                OutlinedButton(onClick = { SleepMateLog.clear(activity); entries = emptyList() }) { Text("清空") }
            }
        }
        Text(
            if (earOnly) "EAR测试记录只采样少量真实摄像头帧，包含左右眼轮廓点坐标和原始EAR值。只用于校验点位与阈值，不参与睡眠判断。"
            else "日志保存在本机，仅保留最近300条。用于排查前台应用识别、监测循环异常、自动恢复、返回桌面及EAR轮廓数据；复现问题后点刷新，再复制发给我。",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        if (visibleEntries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (earOnly) "暂无EAR测试记录；启动保护并让摄像头识别到人脸后再刷新" else "暂无日志") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(visibleEntries.reversed()) { line ->
                Text(line, Modifier.fillMaxWidth().padding(vertical = 5.dp), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}
