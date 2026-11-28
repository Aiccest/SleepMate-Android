package com.sleepmate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.KeyEvent
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.LinkedHashSet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProtectionService : LifecycleService(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var detector: FaceDetector? = null
    private val detectorLock = Any()
    private val detectorClosing = AtomicBoolean(false)
    private val inFlightDetectorTasks = AtomicInteger(0)
    @Volatile private var faceValidationEngine: FaceValidationEngine? = null
    private val faceValidationLock = Any()
    @Volatile private var activeImageAnalysis: ImageAnalysis? = null
    private lateinit var sensorManager: SensorManager
    private lateinit var usageStatsManager: UsageStatsManager

    private var targetAppForeground = false
    private var lastForegroundPackage: String? = null
    private var lastForegroundEventTimestamp = 0L
    private var lastUsageEventQueryAt = 0L
    private val processedUsageEvents = LinkedHashSet<String>()
    private var foregroundMonitorJob: Job? = null
    private var lastMonitorHeartbeatAt = 0L
    private var lastLoggedForegroundPackage: String? = null
    private var lastStartAction: String = "尚未收到启动请求"
    private var lastStartId: Int = 0
    private var sensorRegistered = false
    private var lastActivityAt = 0L
    private var stillSince = 0L
    private var eyesClosedSince = 0L
    @Volatile private var faceDetectedInWindow = false
    @Volatile private var validFrameAnalyzedInWindow = false
    @Volatile private var eyeStateDetectedInWindow = false
    @Volatile private var eyesOpenDetectedInWindow = false
    @Volatile private var eyesClosedDetectedInWindow = false
    @Volatile private var analyzedFrameCount = 0
    @Volatile private var candidateFaceCount = 0
    @Volatile private var rejectedCandidateFaceCount = 0
    @Volatile private var reliableFaceFrameCount = 0
    @Volatile private var stableOpenEyeFrameCount = 0
    private var consecutiveOpenEyeFrames = 0
    @Volatile private var stableClosedEyeFrameCount = 0
    @Volatile private var unknownEyeStateFrameCount = 0
    @Volatile private var cameraFramesReceived = 0
    @Volatile private var cameraDuplicateTimestampFrames = 0
    @Volatile private var cameraLastFrameTimestampNs = -1L
    @Volatile private var cameraFirstFrameTimestampNs = -1L
    @Volatile private var cameraLastFrameArrivalElapsedNs = 0L
    @Volatile private var cameraMaxFrameGapMs = 0L
    @Volatile private var cameraDiagnosticFrameSaved = false
    @Volatile private var earDiagnosticSamplesInWindow = 0
    @Volatile private var faceValidationFailedInWindow = false
    private var serviceCreatedAtElapsed = 0L
    private var consecutiveOpenEyeChecks = 0
    private val triggered = AtomicBoolean(false)
    @Volatile private var screenInteractive = true

    private var detectionJob: Job? = null
    private var cameraJob: Job? = null
    private val cameraMutex = Mutex()
    @Volatile private var cameraWindowId = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    SleepMateLog.add(this@ProtectionService, "屏幕关闭广播；暂停前台应用监测")
                    screenInteractive = false
                    stopForegroundMonitor()
                    triggered.set(false)
                    detectionJob?.cancel()
                    detectionJob = null
                    cameraJob?.cancel()
                    cameraJob = null
                    eyesClosedSince = 0L
                    stillSince = 0L
                    unregisterMotionSensor()
                    updateNotification("屏幕已关闭；睡眠检测与摄像头已停止")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    SleepMateLog.add(this@ProtectionService, "屏幕亮起广播；检查并恢复前台应用监测")
                    if (hasUsageAccess()) startForegroundMonitor()
                    refreshForegroundState()
                    resumeMonitoringIfEligible()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceCreatedAtElapsed = SystemClock.elapsedRealtime()
        val instanceId = System.identityHashCode(this)
        val lifecyclePrefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val previousInstanceId = lifecyclePrefs.getInt("last_service_instance", 0)
        val previousPid = lifecyclePrefs.getInt("last_service_pid", -1)
        val previousCreatedAt = lifecyclePrefs.getLong("last_service_created_at", 0L)
        if (previousInstanceId != 0) {
            SleepMateLog.add(this, "检测到上一次服务实例记录；previousInstance=$previousInstanceId；previousPid=$previousPid；previousCreatedElapsed=$previousCreatedAt；currentInstance=$instanceId；currentPid=${Process.myPid()}；pidChanged=${previousPid != Process.myPid()}；说明=记录可确认实例变化，但不能单凭此记录确定系统回收原因")
        }
        lifecyclePrefs.edit().putInt("last_service_instance", instanceId).putInt("last_service_pid", Process.myPid()).putLong("last_service_created_at", serviceCreatedAtElapsed).apply()
        SleepMateLog.add(this, "服务实例创建；instance=$instanceId；pid=${Process.myPid()}；elapsedRealtime=$serviceCreatedAtElapsed；期望保持开启=${isProtectionDesired(this)}；说明=等待系统调用onStartCommand后再决定是否启动前台服务")
        createNotificationChannel()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        // A service can be recreated before its start request is redelivered on some
        // device builds. If no onStartCommand arrives, make one bounded recovery request
        // from this newly-created instance instead of waiting for MainActivity to open.
        android.os.Handler(mainLooper).postDelayed({
            if (lastStartId == 0 && isProtectionDesired(this) && !running.value) {
                SleepMateLog.add(this, "服务恢复诊断：实例创建250毫秒后仍未收到onStartCommand；instance=${System.identityHashCode(this)}；pid=${Process.myPid()}；running=${running.value}；期望保持开启=true；动作=主动补发一次RECOVER启动请求")
                startRequestPending.set(false)
                try {
                    requestServiceStart(this, ACTION_RECOVER)
                    SleepMateLog.add(this, "服务恢复请求已提交；instance=${System.identityHashCode(this)}；pid=${Process.myPid()}；action=$ACTION_RECOVER")
                } catch (e: Exception) {
                    SleepMateLog.add(this, "实例创建后的RECOVER补发失败：${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Deferred service recovery request failed", e)
                }
            }
        }, 250L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startRequestPending.set(false)
        lastStartAction = intent?.action ?: "系统重建（intent=null）"
        lastStartId = startId
        SleepMateLog.add(this, "onStartCommand进入；instance=${System.identityHashCode(this)}；pid=${Process.myPid()}；startId=$startId；action=$lastStartAction；服务实例存活=${SystemClock.elapsedRealtime() - serviceCreatedAtElapsed}毫秒；期望保持开启=${isProtectionDesired(this)}；flags=$flags")
        if (intent?.action == ACTION_STOP) {
            setProtectionDesired(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isProtectionDesired(this)) {
            SleepMateLog.add(this, "收到启动/恢复请求，但用户期望状态为关闭；拒绝启动服务；action=${intent?.action ?: "系统重建（intent=null）"}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            SleepMateLog.add(this, "系统以null intent重建服务；用户期望保持开启=true；继续初始化前台服务和监测循环")
        }
        running.value = true
        SleepMateLog.add(this, "保护服务收到启动请求；instance=${System.identityHashCode(this)}；pid=${Process.myPid()}；action=${intent?.action ?: "系统重建"}；期望保持开启=${isProtectionDesired(this)}；监测循环活动=${foregroundMonitorJob?.isActive == true}")
        startForeground(NOTIFICATION_ID, notification("睡眠保护已启动，正在等待监测条件"))
        if (intent?.action == ACTION_SELECTION_CHANGED || intent?.action == ACTION_RECONCILE) {
            if (!hasUsageAccess()) {
                updateNotification("请开启使用情况访问权限")
                return START_STICKY
            }
            startForegroundMonitor()
            refreshForegroundState()
            return START_STICKY
        }
        if (!hasUsageAccess()) {
            updateNotification("请开启使用情况访问权限")
            return START_STICKY
        }
        startForegroundMonitor()
        refreshForegroundState()
        return START_STICKY
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try { ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED }
        catch (_: Exception) { false }
    }

    private fun startForegroundMonitor() {
        if (!screenInteractive) {
            SleepMateLog.add(this, "未启动前台监测循环：屏幕处于关闭状态")
            return
        }
        if (foregroundMonitorJob?.isActive == true) return
        lastMonitorHeartbeatAt = SystemClock.elapsedRealtime()
        SleepMateLog.add(this, "前台应用监测循环已启动；心跳日志间隔=${MONITOR_HEARTBEAT_LOG_INTERVAL_MS / 60_000L}分钟；屏幕亮起=$screenInteractive")
        foregroundMonitorJob = scope.launch {
            while (isActive) {
                try {
                    refreshForegroundState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SleepMateLog.add(this@ProtectionService, "前台监测循环异常，稍后继续：${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Foreground monitor iteration failed", e)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastMonitorHeartbeatAt >= MONITOR_HEARTBEAT_LOG_INTERVAL_MS) {
                    lastMonitorHeartbeatAt = now
                    SleepMateLog.add(this@ProtectionService, "前台监测心跳正常；instance=${System.identityHashCode(this@ProtectionService)}；pid=${Process.myPid()}；循环活动=${foregroundMonitorJob?.isActive == true}；屏幕亮起=$screenInteractive；目标应用前台=$targetAppForeground；最近识别=$lastForegroundPackage")
                }
                delay(if (targetAppForeground) ACTIVE_FOREGROUND_POLL_MS else IDLE_FOREGROUND_POLL_MS)
            }
        }
    }

    private fun stopForegroundMonitor() {
        if (foregroundMonitorJob != null) SleepMateLog.add(this, "前台应用监测循环已请求停止/暂停")
        foregroundMonitorJob?.cancel()
        foregroundMonitorJob = null
    }

    private fun refreshForegroundState(): Boolean {
        if (!screenInteractive) return false
        if (!hasUsageAccess()) { setTargetAppForeground(false); return false }
        val selected = MediaAppPreferences.getSelectedPackages(this)
        if (selected.isEmpty()) { setTargetAppForeground(false); return false }
        val now = System.currentTimeMillis()
        // Initialize from a short recent window; thereafter query incrementally with
        // a small overlap so delayed UsageEvents are less likely to be missed.
        val eventWindowStart = if (lastUsageEventQueryAt == 0L) {
            now - INITIAL_USAGE_EVENT_WINDOW_MS
        } else {
            (lastUsageEventQueryAt - USAGE_EVENT_OVERLAP_MS).coerceAtLeast(0L)
        }
        val events = try {
            usageStatsManager.queryEvents(eventWindowStart, now)
        } catch (e: Exception) {
            SleepMateLog.add(this, "UsageStats前台事件查询失败：${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "UsageStats queryEvents failed", e)
            return false
        }
        val event = UsageEvents.Event()
        var foreground = lastForegroundPackage
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val eventKey = "${event.timeStamp}:${event.eventType}:${event.packageName}"
            if (!processedUsageEvents.add(eventKey)) continue
            if (processedUsageEvents.size > MAX_REMEMBERED_USAGE_EVENTS) {
                val oldest = processedUsageEvents.firstOrNull()
                if (oldest != null) processedUsageEvents.remove(oldest)
            }
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foreground = event.packageName
                    if (event.timeStamp > lastForegroundEventTimestamp) {
                        lastForegroundEventTimestamp = event.timeStamp
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (foreground == event.packageName) foreground = null
            }
        }
        lastUsageEventQueryAt = now
        lastForegroundPackage = foreground
        if (lastLoggedForegroundPackage != foreground) {
            SleepMateLog.add(this, "前台应用识别变化：${lastLoggedForegroundPackage ?: "无/未知"} → ${foreground ?: "无/未知"}；所选应用前台=${foreground in selected}")
            lastLoggedForegroundPackage = foreground
        }
        setTargetAppForeground(foreground in selected)
        return true
    }

    private fun setTargetAppForeground(active: Boolean) {
        if (targetAppForeground == active) return
        targetAppForeground = active
        SleepMateLog.add(this, if (active) "所选监测应用进入前台；恢复睡眠检测" else "已离开全部所选监测应用；暂停睡眠检测")
        if (active) {
            lastActivityAt = SystemClock.elapsedRealtime()
            stillSince = 0L
            triggered.set(false)
            if (screenInteractive) {
                registerMotionSensor()
                updateNotification("已进入所选应用；开始等待睡眠检测条件")
                scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
            }
        } else {
            detectionJob?.cancel()
            cameraJob?.cancel()
            unregisterMotionSensor()
            triggered.set(false)
            eyesClosedSince = 0L
            consecutiveOpenEyeChecks = 0
            updateNotification("已离开所选应用，睡眠检测已暂停")
        }
    }

    private fun resumeMonitoringIfEligible() {
        if (!screenInteractive || !targetAppForeground || triggered.get()) return
        lastActivityAt = SystemClock.elapsedRealtime()
        stillSince = 0L
        registerMotionSensor()
        updateNotification("屏幕已亮起且所选应用仍在前台；重新等待睡眠检测条件")
        scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
    }

    private fun registerMotionSensor() {
        if (sensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // Sample at approximately 3.3 Hz without batching so movement callbacks are not delayed.
        sensorRegistered = sensorManager.registerListener(
            this,
            sensor,
            MOTION_SENSOR_SAMPLING_PERIOD_US,
            MOTION_SENSOR_MAX_REPORT_LATENCY_US
        )
        stillSince = 0L
    }

    private fun unregisterMotionSensor() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(this)
        sensorRegistered = false
        stillSince = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!screenInteractive || !targetAppForeground || triggered.get() ||
            event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = kotlin.math.sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        val now = SystemClock.elapsedRealtime()
        if (kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH) > MOVEMENT_THRESHOLD) {
            lastActivityAt = now
            stillSince = 0L
            consecutiveOpenEyeChecks = 0
            cameraJob?.cancel()
            scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
        } else if (stillSince == 0L) {
            stillSince = now
        }
    }

    private fun scheduleDetection(delayMs: Long) {
        detectionJob?.cancel()
        if (!screenInteractive || !targetAppForeground || triggered.get()) return
        detectionJob = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            if (!screenInteractive || !targetAppForeground || triggered.get()) return@launch
            val now = SystemClock.elapsedRealtime()
            val idleFor = now - lastActivityAt
            if (idleFor < NO_ACTIVITY_REQUIRED_MS) {
                scheduleDetection(NO_ACTIVITY_REQUIRED_MS - idleFor)
                return@launch
            }
            val stillFor = if (stillSince == 0L) 0L else now - stillSince
            if (stillFor < PHONE_STILL_REQUIRED_MS) {
                updateNotification("等待手机保持静止")
                scheduleDetection((PHONE_STILL_REQUIRED_MS - stillFor).coerceAtLeast(1000L))
                return@launch
            }
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!power.isInteractive || !screenInteractive) return@launch
            startCameraCheck()
        }
    }

    private fun startCameraCheck() {
        if (cameraJob?.isActive == true || !screenInteractive || !targetAppForeground || triggered.get()) return
        cameraJob = scope.launch {
            eyesClosedSince = 0L
            updateNotification("正在进行短时睡眠确认")
            var roundResult: Triple<Boolean, Boolean, Boolean>? = null
            var roundFailure: String? = null
            try {
                val result = runCameraWindow()
                roundResult = result
                if (!result.second) {
                    updateNotification("摄像头未取得有效画面；本轮不作睡眠判断")
                } else if (!result.first && screenInteractive && targetAppForeground &&
                    !triggered.get() && stillSince > 0L &&
                    SystemClock.elapsedRealtime() - stillSince >= PHONE_STILL_REQUIRED_MS) {
                    updateNotification("连续30秒未检测到可靠人脸；判定睡眠并执行保护")
                    if (triggered.compareAndSet(false, true)) performProtection()
                } else if (result.first && !result.third && screenInteractive && targetAppForeground &&
                    !triggered.get() && stillSince > 0L &&
                    SystemClock.elapsedRealtime() - stillSince >= PHONE_STILL_REQUIRED_MS) {
                    updateNotification("连续30秒检测到可靠人脸但始终无法取得双眼有效概率；判定睡眠并执行保护")
                    if (triggered.compareAndSet(false, true)) performProtection()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                roundFailure = "${e.javaClass.simpleName}: ${e.message ?: "无异常详情"}"
                updateNotification("本轮摄像头检测未能启动；稍后重试")
            }
            if (!triggered.get() && screenInteractive && targetAppForeground && isActive) {
                val result = roundResult
                val stillFor = if (stillSince > 0L) SystemClock.elapsedRealtime() - stillSince else 0L
                val reason = when {
                    roundFailure != null -> "摄像头检测异常：${roundFailure}"
                    result == null -> "未取得检测结果"
                    !result.second -> "摄像头窗口内没有取得有效分析画面"
                    !result.first -> "本轮没有检测到通过结构校验的可靠人脸；最终保护条件未同时满足（可能是手机活动或前台状态变化）"
                    !result.third -> "检测到可靠人脸，但整个窗口没有取得双眼有效概率"
                    eyesOpenDetectedInWindow -> "检测到眼睛睁开；本轮未满足持续闭眼条件"
                    eyesClosedDetectedInWindow -> "检测到闭眼，但持续时间未达到3秒"
                    stillSince == 0L || stillFor < PHONE_STILL_REQUIRED_MS -> "确认期间手机发生移动或静止时间不足"
                    else -> "检测到人脸且眼部状态有效，但未达到睡眠判定条件（具体帧状态未能进一步区分）"
                }
                consecutiveOpenEyeChecks = (consecutiveOpenEyeChecks + 1).coerceAtMost(4)
                val minutes = when (consecutiveOpenEyeChecks) {
                    1 -> 15
                    2 -> 20
                    3 -> 25
                    else -> 30
                }
                SleepMateLog.add(this@ProtectionService, "本轮未确认睡眠；原因=${reason}；有效画面=${result?.second}；可靠人脸=${result?.first}；取得双眼有效概率=${result?.third}；候选脸帧数=${candidateFaceCount}；拒绝候选脸数=${rejectedCandidateFaceCount}；可靠人脸帧数=${reliableFaceFrameCount}；有效分析帧数=${analyzedFrameCount}；稳定睁眼帧数=${stableOpenEyeFrameCount}；稳定闭眼帧数=${stableClosedEyeFrameCount}；眼睛状态无法判断帧数=${unknownEyeStateFrameCount}；手机静止时长=${stillFor}毫秒；下次复查约${minutes}分钟后")
                updateNotification("本轮未确认睡眠；约${minutes}分钟后复查")
                scheduleDetection(minutes * 60_000L)
            }
        }
    }

    private suspend fun runCameraWindow(): Triple<Boolean, Boolean, Boolean> = cameraMutex.withLock {
        faceDetectedInWindow = false
        validFrameAnalyzedInWindow = false
        eyeStateDetectedInWindow = false
        eyesOpenDetectedInWindow = false
        eyesClosedDetectedInWindow = false
        analyzedFrameCount = 0
        candidateFaceCount = 0
        rejectedCandidateFaceCount = 0
        reliableFaceFrameCount = 0
        stableOpenEyeFrameCount = 0
        consecutiveOpenEyeFrames = 0
        stableClosedEyeFrameCount = 0
        unknownEyeStateFrameCount = 0
        cameraFramesReceived = 0
        cameraDuplicateTimestampFrames = 0
        cameraLastFrameTimestampNs = -1L
        cameraFirstFrameTimestampNs = -1L
        cameraLastFrameArrivalElapsedNs = 0L
        cameraMaxFrameGapMs = 0L
        cameraDiagnosticFrameSaved = false
        earDiagnosticSamplesInWindow = 0
        faceValidationFailedInWindow = false
        val windowId = ++cameraWindowId
        var analysis: ImageAnalysis? = null
        var provider: ProcessCameraProvider? = null
        var cameraStateObserver: Observer<CameraState>? = null
        var boundCameraForState: androidx.camera.core.Camera? = null
        try {
            val cameraPermissionGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            SleepMateLog.add(this, "摄像头审计：准备获取CameraProvider；window=$windowId；CAMERA权限已授权=$cameraPermissionGranted")
            if (!cameraPermissionGranted) throw SecurityException("CAMERA permission not granted")
            val future = ProcessCameraProvider.getInstance(this)
            val resolvedProvider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                future.addListener({
                    try {
                        if (cont.isActive) cont.resume(future.get())
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, cameraExecutor)
            }
            provider = resolvedProvider
            val frontCameraAvailable = resolvedProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            SleepMateLog.add(this, "摄像头审计：CameraProvider已就绪；window=$windowId；前置摄像头可用=$frontCameraAvailable")
            if (!frontCameraAvailable) throw IllegalStateException("Front camera is unavailable")
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis = imageAnalysis
            activeImageAnalysis = imageAnalysis
            imageAnalysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy, windowId) }
            val boundCamera = withContext(Dispatchers.Main) {
                resolvedProvider.bindToLifecycle(this@ProtectionService, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
            }
            boundCameraForState = boundCamera
            val stateObserver = Observer<CameraState> { state ->
                SleepMateLog.add(this@ProtectionService, "摄像头审计：CameraState变化；window=$windowId；state=${state.type}；error=${state.error?.code}:${state.error?.cause?.javaClass?.simpleName}")
            }
            cameraStateObserver = stateObserver
            withContext(Dispatchers.Main) {
                boundCamera.cameraInfo.cameraState.observe(this@ProtectionService, stateObserver)
            }
            val initialCameraState = boundCamera.cameraInfo.cameraState.value
            SleepMateLog.add(this, "摄像头审计：bindToLifecycle已返回；window=$windowId；cameraState=${initialCameraState?.type}；cameraError=${initialCameraState?.error?.code}:${initialCameraState?.error?.cause?.javaClass?.simpleName}；resolution=${imageAnalysis.resolutionInfo?.resolution}")
            delay(CAMERA_WINDOW_MS)
            val result = Triple(faceDetectedInWindow, validFrameAnalyzedInWindow && !faceValidationFailedInWindow, eyeStateDetectedInWindow)
            SleepMateLog.add(this, "摄像头审计：30秒窗口结束；window=$windowId；收到帧数=$cameraFramesReceived；重复时间戳帧=$cameraDuplicateTimestampFrames；分析成功帧=$analyzedFrameCount；首帧时间戳=$cameraFirstFrameTimestampNs；末帧时间戳=$cameraLastFrameTimestampNs；最大帧间隔=${cameraMaxFrameGapMs}毫秒；可靠脸=${result.first}；有效分析=${result.second}；取得眼概率=${result.third}")
            result
        } finally {
            // The mutex keeps a new camera window from binding until this window's own
            // analyzer and use case have been detached. Never unbind another window's use cases.
            if (cameraWindowId == windowId) cameraWindowId++
            withContext(NonCancellable + Dispatchers.Main) {
                val observer = cameraStateObserver
                val camera = boundCameraForState
                if (observer != null && camera != null) {
                    camera.cameraInfo.cameraState.removeObserver(observer)
                }
                analysis?.let { useCase ->
                    useCase.clearAnalyzer()
                    provider?.unbind(useCase)
                    if (activeImageAnalysis === useCase) activeImageAnalysis = null
                }
            }
            SleepMateLog.add(this, "摄像头审计：本窗口use case已解除绑定；window=$windowId；收到帧数=$cameraFramesReceived；有效分析帧=$analyzedFrameCount")
        }
    }

    // A detector task outlives the coroutine that opened the camera window. Keep
    // the detector and its executor alive until every submitted ML Kit Task completes.
    private fun getFaceValidationEngine(): FaceValidationEngine = synchronized(faceValidationLock) {
        faceValidationEngine ?: FaceValidationEngine().also { faceValidationEngine = it }
    }

    private fun beginDetectorTask(): FaceDetector? = synchronized(detectorLock) {
        if (detectorClosing.get()) return@synchronized null
        val activeDetector = detector ?: run {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
            FaceDetection.getClient(options).also { detector = it }
        }
        inFlightDetectorTasks.incrementAndGet()
        activeDetector
    }

    // Reject obvious false-positive boxes without requiring a fixed screen position,
    // frontal pose, or a particular face size. A profile may expose only one eye;
    // at least one eye landmark plus a nose or mouth landmark is therefore enough.
    private fun isStructurallyPlausibleFace(face: Face, imageWidth: Int, imageHeight: Int): Boolean {
        val box = face.boundingBox
        if (imageWidth <= 0 || imageHeight <= 0 || box.width() < 1 || box.height() < 1) return false
        if (box.width() > imageWidth * 1.05f || box.height() > imageHeight * 1.05f) return false
        val ratio = box.width().toFloat() / box.height().toFloat()
        if (ratio !in 0.35f..2.2f) return false

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val eyePoints = listOfNotNull(leftEye, rightEye)
        if (eyePoints.isEmpty() || (nose == null && mouth == null)) return false

        val marginX = (box.width() * 0.18f).coerceAtLeast(3f)
        val marginY = (box.height() * 0.18f).coerceAtLeast(3f)
        val minX = box.left - marginX
        val maxX = box.right + marginX
        val minY = box.top - marginY
        val maxY = box.bottom + marginY
        val landmarks = eyePoints + listOfNotNull(nose, mouth)
        if (landmarks.any { it.x < minX || it.x > maxX || it.y < minY || it.y > maxY }) return false

        // Require the lower-face anchor to sit below the eye line, but allow a
        // generous range for tilted heads and non-frontal viewing angles.
        val eyeY = eyePoints.map { it.y }.average().toFloat()
        val lowerAnchor = nose ?: mouth!!
        if (lowerAnchor.y <= eyeY - box.height() * 0.08f) return false
        return true
    }

    private fun finishDetectorTask() {
        val remaining = inFlightDetectorTasks.decrementAndGet()
        if (remaining < 0) {
            inFlightDetectorTasks.set(0)
            Log.e(TAG, "Detector task counter underflow")
        }
        closeDetectorIfIdle()
    }

    private fun closeDetectorIfIdle() {
        synchronized(detectorLock) {
            if (!detectorClosing.get() || inFlightDetectorTasks.get() != 0) return
            detector?.close()
            detector = null
            // shutdown() drains already queued analyzer/completion callbacks; it does
            // not interrupt an ML Kit operation that is still running.
            cameraExecutor.shutdown()
        }
    }

    private fun analyzeFrame(proxy: ImageProxy, windowId: Long) {
        if (windowId != cameraWindowId || triggered.get() || !screenInteractive || !targetAppForeground) {
            proxy.close()
            return
        }
        val activeDetector = beginDetectorTask()
        if (activeDetector == null) {
            proxy.close()
            return
        }
        try {
            val frameTimestampNs = proxy.imageInfo.timestamp
            val frameArrivalElapsedNs = SystemClock.elapsedRealtimeNanos()
            cameraFramesReceived++
            if (cameraFirstFrameTimestampNs < 0L) cameraFirstFrameTimestampNs = frameTimestampNs
            if (frameTimestampNs == cameraLastFrameTimestampNs) cameraDuplicateTimestampFrames++
            if (cameraLastFrameArrivalElapsedNs > 0L) {
                val gapMs = ((frameArrivalElapsedNs - cameraLastFrameArrivalElapsedNs).coerceAtLeast(0L)) / 1_000_000L
                if (gapMs > cameraMaxFrameGapMs) cameraMaxFrameGapMs = gapMs
            }
            cameraLastFrameTimestampNs = frameTimestampNs
            cameraLastFrameArrivalElapsedNs = frameArrivalElapsedNs
            if (cameraFramesReceived == 1) SleepMateLog.add(this, "摄像头审计：收到首帧；window=$windowId；timestampNs=$frameTimestampNs；尺寸=${proxy.width}x${proxy.height}；rotation=${proxy.imageInfo.rotationDegrees}；format=${proxy.format}")
            val mediaImage = proxy.image
            if (mediaImage == null) {
                proxy.close()
                finishDetectorTask()
                return
            }
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            activeDetector.process(image)
                .addOnSuccessListener(cameraExecutor) { faces ->
                    if (windowId != cameraWindowId || !screenInteractive || !targetAppForeground || triggered.get()) return@addOnSuccessListener
                    validFrameAnalyzedInWindow = true
                    analyzedFrameCount++
                    candidateFaceCount += faces.size
                    if (faces.isNotEmpty() && !cameraDiagnosticFrameSaved) {
                        cameraDiagnosticFrameSaved = true
                        try {
                            val saved = CameraDiagnosticCapture.saveLatest(this@ProtectionService, proxy)
                            val faceDetails = faces.take(3).mapIndexed { index, face ->
                                val box = face.boundingBox
                                "face" + (index + 1) + "{box=" + box.left + "," + box.top + "," + box.right + "," + box.bottom + ";leftEye=" + face.leftEyeOpenProbability + ";rightEye=" + face.rightEyeOpenProbability + ";eulerX=" + face.headEulerAngleX + ";eulerY=" + face.headEulerAngleY + ";eulerZ=" + face.headEulerAngleZ + ";trackingId=" + face.trackingId + "}"
                            }.joinToString(";")
                            SleepMateLog.add(this@ProtectionService, "假脸诊断样本已保存；window=" + windowId + "；文件=" + saved + "；rotation=" + proxy.imageInfo.rotationDegrees + "；候选数=" + faces.size + "；" + faceDetails)
                        } catch (captureError: Exception) {
                            SleepMateLog.add(this@ProtectionService, "假脸诊断样本保存失败；window=" + windowId + "；异常=" + captureError.javaClass.simpleName + ":" + captureError.message)
                            Log.e(TAG, "Failed to save face diagnostic frame", captureError)
                        }
                    }
                    val rotation = proxy.imageInfo.rotationDegrees
                    val orientedWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
                    val orientedHeight = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
                    // EAR diagnostics are sampled sparsely and are strictly observational:
                    // they do not alter contourAcceptedIndexes or any ABC sleep decision.
                    if (faces.isNotEmpty() && earDiagnosticSamplesInWindow < 3 &&
                        (analyzedFrameCount == 1 || analyzedFrameCount % 10 == 0)) {
                        earDiagnosticSamplesInWindow++
                        val earSnapshot = getFaceValidationEngine().diagnosticSnapshot(faces)
                        SleepMateLog.add(
                            this@ProtectionService,
                            "[EAR_TEST] window=$windowId; sample=$earDiagnosticSamplesInWindow; frame=$analyzedFrameCount; image=${orientedWidth}x${orientedHeight}; rotation=$rotation; $earSnapshot"
                        )
                    }
                    val contourAcceptedIndexes = try {
                        getFaceValidationEngine().validateFrame(faces, orientedWidth, orientedHeight)
                    } catch (validationError: Exception) {
                        faceValidationFailedInWindow = true
                        SleepMateLog.add(this@ProtectionService, "ML Kit轮廓几何验证异常；本轮不作B/C睡眠判断；异常=${validationError.javaClass.simpleName}:${validationError.message}")
                        Log.e(TAG, "ML Kit contour validation failed", validationError)
                        emptySet()
                    }
                    val reliableFaces = faces.filterIndexed { index, face ->
                        contourAcceptedIndexes.contains(index) && isStructurallyPlausibleFace(face, orientedWidth, orientedHeight)
                    }
                    rejectedCandidateFaceCount += faces.size - reliableFaces.size
                    if (reliableFaces.isNotEmpty()) {
                        faceDetectedInWindow = true
                        reliableFaceFrameCount++
                    }

                    // Prefer the largest structurally valid face; do not assume the
                    // first ML Kit result is the person looking at the phone.
                    val faceWithEyes = reliableFaces
                        .filter { f ->
                            val l = f.leftEyeOpenProbability
                            val r = f.rightEyeOpenProbability
                            l != null && r != null && l in 0f..1f && r in 0f..1f
                        }
                        .maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height().toLong() }
                    val left = faceWithEyes?.leftEyeOpenProbability
                    val right = faceWithEyes?.rightEyeOpenProbability
                    val frontalPoseReliable = faceWithEyes != null &&
                        kotlin.math.abs(faceWithEyes.headEulerAngleY) <= 18f
                    val bothEyesClosed = left != null && right != null &&
                        left < 0.25f && right < 0.25f
                    val bothEyesOpen = left != null && right != null &&
                        left >= 0.25f && right >= 0.25f
                    val eyeStateUnknown = !frontalPoseReliable || (!bothEyesClosed && !bothEyesOpen)

                    if (left != null && right != null) eyeStateDetectedInWindow = true
                    val bothEyesReliablyOpen = !eyeStateUnknown && bothEyesOpen
                    if (bothEyesReliablyOpen) {
                        stableOpenEyeFrameCount++
                        consecutiveOpenEyeFrames++
                        // A single open frame is not enough to interrupt the closed-eye
                        // timer. Only consecutive, frontal, dual-eye-open frames confirm awake.
                        if (consecutiveOpenEyeFrames >= 2) eyesOpenDetectedInWindow = true
                    } else {
                        consecutiveOpenEyeFrames = 0
                        if (eyeStateUnknown) {
                            // Uncertain or conflicting eye state follows the sleep direction,
                            // but remains separately counted rather than mislabeled as closed.
                            unknownEyeStateFrameCount++
                        } else if (bothEyesClosed) {
                            stableClosedEyeFrameCount++
                            if (stableClosedEyeFrameCount >= 2) eyesClosedDetectedInWindow = true
                        }
                    }

                    // A first reliable both-eyes-open frame is a pending confirmation:
                    // preserve the existing closed timer, but do not trigger protection yet.
                    // The next analyzed frame either confirms awake (resets the timer) or
                    // cancels this pending state and resumes the closed/sleep decision.
                    val eyesConfirmedOpen = bothEyesReliablyOpen && consecutiveOpenEyeFrames >= 2
                    val pendingOpenConfirmation = bothEyesReliablyOpen && consecutiveOpenEyeFrames == 1
                    if (!pendingOpenConfirmation) {
                        val now = SystemClock.elapsedRealtime()
                        if (eyesConfirmedOpen) {
                            eyesClosedSince = 0L
                        } else {
                            if (eyesClosedSince == 0L) eyesClosedSince = now
                            val eyesClosedLongEnough = now - eyesClosedSince >= EYES_CLOSED_REQUIRED_MS
                            val phoneStillLongEnough = stillSince > 0L && now - stillSince >= PHONE_STILL_REQUIRED_MS
                            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (eyesClosedLongEnough && phoneStillLongEnough &&
                                screenInteractive && power.isInteractive && targetAppForeground &&
                                triggered.compareAndSet(false, true)) {
                                scope.launch { performProtection() }
                            }
                        }
                    }
                }
                .addOnFailureListener(cameraExecutor) { error ->
                    faceValidationFailedInWindow = true
                    // A failed frame breaks the consecutive-open confirmation sequence.
                    consecutiveOpenEyeFrames = 0
                    SleepMateLog.add(this, "图像分析任务失败；本轮不作B/C睡眠判断；window=$windowId；异常=${error.javaClass.simpleName}:${error.message}")
                    Log.e(TAG, "ML Kit frame processing failed", error)
                }
                .addOnCompleteListener(cameraExecutor) {
                    try {
                        proxy.close()
                    } finally {
                        finishDetectorTask()
                    }
                }
        } catch (e: Exception) {
            // A frame that cannot be submitted/analyzed cannot count toward
            // consecutive both-eyes-open confirmation.
            consecutiveOpenEyeFrames = 0
            SleepMateLog.add(this, "图像帧提交/读取异常；window=$windowId；异常=${e.javaClass.simpleName}:${e.message}")
            Log.e(TAG, "Image frame submission failed", e)
            // Submission can fail before a Task is returned; release both resources.
            try {
                proxy.close()
            } finally {
                finishDetectorTask()
            }
        }
    }

    private suspend fun performProtection() {
        val currentJob = currentCoroutineContext()[Job]
        cameraJob?.takeIf { it !== currentJob }?.cancel()
        val activeWindowId = cameraWindowId
        cameraWindowId = activeWindowId + 1L
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!screenInteractive || !power.isInteractive || !targetAppForeground) {
            triggered.set(false)
            return
        }
        updateNotification("确认符合睡眠条件；正在尝试暂停媒体")
        detectionJob?.cancel()
        unregisterMotionSensor()

        // Media pause is best-effort. A player may not expose a media-button
        // receiver; that must never block HOME or the device-admin lock.
        try {
            pauseForegroundMediaBestEffort()
            delay(MEDIA_PAUSE_SETTLE_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Media pause attempt failed; continuing protection flow", e)
            SleepMateLog.add(this, "主动暂停媒体尝试失败：${e.javaClass.simpleName}: ${e.message}；继续返回桌面和锁屏")
        }
        if (!screenInteractive || !power.isInteractive || !targetAppForeground) {
            triggered.set(false)
            return
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminActive = dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))
        val overlayAllowed = Settings.canDrawOverlays(this)
        Log.i(TAG, "HOME_DIAG: SYSTEM_ALERT_WINDOW granted=$overlayAllowed")
        SleepMateLog.add(this, "悬浮窗特殊权限（SYSTEM_ALERT_WINDOW）已授权=$overlayAllowed")
        Log.i(TAG, "HOME_DIAG: sleep confirmed; adminActive=$adminActive; currentForeground=$lastForegroundPackage; targetForeground=$targetAppForeground")
        SleepMateLog.add(this, "确认睡眠；设备管理员已启用=$adminActive；当前前台=$lastForegroundPackage；目标应用前台=$targetAppForeground")

        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val homeHandler = homeIntent.resolveActivity(packageManager)
        Log.i(TAG, "HOME_DIAG: resolvedHomeActivity=$homeHandler")
        SleepMateLog.add(this, "解析到的默认桌面=$homeHandler")

        var returnedToLauncher = false
        try {
            if (homeHandler == null) {
                throw android.content.ActivityNotFoundException("No HOME activity resolved")
            }
            // HOME is best-effort only. Failure to switch apps must never prevent
            // an authorized device-admin lock after sleep has been confirmed.
            homeIntent.component = homeHandler
            val homeRequestAt = System.currentTimeMillis()
            updateNotification("已确认睡眠；正在先返回桌面")
            startActivity(homeIntent)
            Log.i(TAG, "HOME_DIAG: explicit launcher startActivity returned without exception")
            SleepMateLog.add(this, "已向默认桌面发送明确组件跳转请求；等待确认前台切换")

            var previousCheckAt = 0L
            for (checkAtMs in listOf(500L, 1500L, 3000L)) {
                delay(checkAtMs - previousCheckAt)
                previousCheckAt = checkAtMs
                if (!screenInteractive || !power.isInteractive) break
                val usageQuerySucceeded = refreshForegroundState()
                val freshLauncherEvent = lastForegroundEventTimestamp > homeRequestAt
                returnedToLauncher = usageQuerySucceeded && freshLauncherEvent &&
                    lastForegroundPackage == homeHandler.packageName
                val message = "发出HOME请求后${checkAtMs}毫秒：前台=$lastForegroundPackage；默认桌面=${homeHandler.packageName}；UsageStats查询成功=$usageQuerySucceeded；HOME请求后新前台事件=$freshLauncherEvent；已回桌面=$returnedToLauncher；屏幕亮起=$screenInteractive"
                Log.i(TAG, "HOME_DIAG: $message")
                SleepMateLog.add(this, message)
                if (returnedToLauncher) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "HOME_DIAG: launcher return attempt failed; lock will still be evaluated", e)
            SleepMateLog.add(this, "返回桌面尝试失败：${e.javaClass.simpleName}: ${e.message}；继续检查锁屏条件")
        }

        if (!screenInteractive || !power.isInteractive) {
            SleepMateLog.add(this, "返回桌面尝试结束时屏幕已关闭；不再重复执行锁屏")
            return
        }

        if (!returnedToLauncher) {
            SleepMateLog.add(this, "未确认返回桌面；仍按设计继续执行自动锁屏")
            updateNotification("未确认返回桌面；继续执行自动锁屏")
        } else {
            SleepMateLog.add(this, "已确认返回桌面")
            updateNotification("已返回桌面；正在执行自动锁屏")
        }

        if (adminActive) {
            try {
                SleepMateLog.add(this, if (returnedToLauncher)
                    "已返回桌面；现在执行设备管理员锁屏"
                else
                    "未确认返回桌面；仍执行设备管理员锁屏")
                updateNotification("正在锁定屏幕")
                dpm.lockNow()
                SleepMateLog.add(this, "设备管理员锁屏请求已调用")
            } catch (e: Exception) {
                Log.e(TAG, "HOME_DIAG: device-admin lock failed", e)
                SleepMateLog.add(this, "设备管理员锁屏调用失败：${e.javaClass.simpleName}: ${e.message}")
                updateNotification("自动锁屏失败；请检查设备管理员权限")
            }
        } else {
            updateNotification(if (returnedToLauncher)
                "已返回桌面；设备管理员未启用，无法自动锁屏"
            else
                "未确认返回桌面；设备管理员未启用，无法自动锁屏")
            SleepMateLog.add(this, if (returnedToLauncher)
                "已确认返回桌面；设备管理员未启用，本次仅返回桌面"
            else
                "未确认返回桌面；设备管理员未启用，本次无法自动锁屏")
        }
    }

    private fun pauseForegroundMediaBestEffort() {
        val selectedPackages = MediaAppPreferences.getSelectedPackages(this)
        val targetPackage = lastForegroundPackage?.takeIf { it in selectedPackages }
        if (targetPackage == null) {
            SleepMateLog.add(this, "当前前台应用不在所选媒体应用列表中；跳过定向暂停广播，继续保护流程")
            return
        }

        val down = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setPackage(targetPackage)
            .putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
        val up = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setPackage(targetPackage)
            .putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )

        sendOrderedBroadcast(down, null)
        sendOrderedBroadcast(up, null)
        SleepMateLog.add(this, "已向当前前台媒体应用发送MEDIA_PAUSE按键广播：$targetPackage；播放器是否响应由应用自身决定")
        updateNotification("已发送媒体暂停指令；正在返回桌面")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onTaskRemoved(rootIntent: Intent?) {
        SleepMateLog.add(this, "SleepMate最近任务被划掉；instance=${System.identityHashCode(this)}；pid=${Process.myPid()}；startAction=$lastStartAction；startId=$lastStartId；期望保持开启=${isProtectionDesired(this)}；监测循环活动=${foregroundMonitorJob?.isActive == true}；屏幕亮起=$screenInteractive；目标应用前台=$targetAppForeground；最近识别=$lastForegroundPackage；rootAction=${rootIntent?.action}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SleepMateLog.add(this, "保护服务 onDestroy；instance=${System.identityHashCode(this)}；pid=${Process.myPid()}；服务实例存活=${SystemClock.elapsedRealtime() - serviceCreatedAtElapsed}毫秒；lastStartAction=$lastStartAction；lastStartId=$lastStartId；期望保持开启=${isProtectionDesired(this)}；监测循环活动=${foregroundMonitorJob?.isActive == true}；说明=onDestroy本身无法区分系统回收、用户停止或其他销毁原因")
        running.value = false
        startRequestPending.set(false)
        detectionJob?.cancel()
        cameraJob?.cancel()
        foregroundMonitorJob?.cancel()
        scope.cancel()
        unregisterMotionSensor()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        cameraWindowId++
        // Stop new frames before marking the detector as closing. In-flight ML Kit
        // Tasks retain ownership until their completion listener closes each proxy.
        activeImageAnalysis?.clearAnalyzer()
        synchronized(detectorLock) {
            detectorClosing.set(true)
        }
        closeDetectorIfIdle()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SleepMate 保护状态", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(contentPendingIntent)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("SleepMate")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val instance = System.identityHashCode(this)
        SleepMateLog.add(this, "通知刷新请求；instance=$instance；pid=${Process.myPid()}；内容=$message")
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
            SleepMateLog.add(this, "通知刷新完成；instance=$instance；内容=$message")
        } catch (e: Exception) {
            SleepMateLog.add(this, "通知刷新失败；instance=$instance；异常=${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Notification update failed", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        val running = MutableStateFlow(false)
        private val startRequestPending = AtomicBoolean(false)
        private const val PREFERENCES_NAME = "sleepmate_protection_state"
        private const val KEY_PROTECTION_DESIRED = "protection_enabled"

        private fun isProtectionDesired(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PROTECTION_DESIRED, false)

        private fun setProtectionDesired(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROTECTION_DESIRED, enabled)
                .apply()
        }

        const val ACTION_START = "com.sleepmate.app.START"
        const val ACTION_STOP = "com.sleepmate.app.STOP"
        const val ACTION_RECOVER = "com.sleepmate.app.RECOVER"
        const val ACTION_SELECTION_CHANGED = "com.sleepmate.app.SELECTION_CHANGED"
        private const val ACTION_RECONCILE = "com.sleepmate.app.RECONCILE"
        private const val ACTIVE_FOREGROUND_POLL_MS = 2500L
        private const val IDLE_FOREGROUND_POLL_MS = 4500L
        private const val MOTION_SENSOR_SAMPLING_PERIOD_US = 300_000
        private const val MOTION_SENSOR_MAX_REPORT_LATENCY_US = 0
        private const val EYES_CLOSED_REQUIRED_MS = 3000L
        private const val PHONE_STILL_REQUIRED_MS = 8000L
        private const val NO_ACTIVITY_REQUIRED_MS = 5 * 60_000L
        private const val INITIAL_USAGE_EVENT_WINDOW_MS = 10_000L
        private const val USAGE_EVENT_OVERLAP_MS = 5_000L
        private const val MAX_REMEMBERED_USAGE_EVENTS = 128
        private const val MONITOR_HEARTBEAT_LOG_INTERVAL_MS = 5 * 60_000L
        private const val CAMERA_WINDOW_MS = 30_000L
        private const val MEDIA_PAUSE_SETTLE_MS = 250L
        private const val MOVEMENT_THRESHOLD = 1.0f
        private const val TAG = "SleepMate"
        private const val CHANNEL_ID = "sleepguard_protection"
        private const val NOTIFICATION_ID = 1001

        private fun requestServiceStart(context: Context, action: String) {
            if (action == ACTION_RECONCILE) {
                if (!running.value) return
            } else if (running.value) {
                return
            }
            if (!startRequestPending.compareAndSet(false, true)) return
            val intent = Intent(context, ProtectionService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                startRequestPending.set(false)
                throw e
            }
        }

        // User-initiated start: persist the user's intent before starting protection.
        fun start(context: Context) {
            setProtectionDesired(context, true)
            requestServiceStart(context, ACTION_START)
        }

        // Recovery never changes the saved preference; it only reconciles a missing service.
        fun restoreIfEnabled(context: Context) {
            if (!isProtectionDesired(context)) return
            requestServiceStart(context, if (running.value) ACTION_RECONCILE else ACTION_RECOVER)
        }

        fun stop(context: Context) {
            setProtectionDesired(context, false)
            startRequestPending.set(false)
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }
}
