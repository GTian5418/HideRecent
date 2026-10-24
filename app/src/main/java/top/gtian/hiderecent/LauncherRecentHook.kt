package top.gtian.hiderecent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面进程 hook（ColorOS OplusLauncher，进程名 com.android.launcher）。
 *
 * 反编译 OplusLauncher.apk 定位的真正过滤点：
 *   com.oplus.quickstep.data.OplusRecentTasksFilter.filterTask(GroupTask): boolean
 * 调用方 com.android.quickstep.OplusRecentTasksListImpl.loadTasksInBackground():
 *   if (getFilter().filterTask(map)) { map = null; }   // true = 隐藏, false = 显示
 *
 * 旧 AOSP 路径 RecentTasks.isVisibleRecentTask 在 ColorOS 上是死代码（system hook 装上但从不触发），
 * 真正过滤在 launcher 侧的 OplusRecentTasksFilter。
 *
 * 快照刷新时机（三层保障，缺一不可）：
 *   1) OplusRecentTasksListImpl.loadTasksInBackground 前置同步刷新——每次打开最近任务的必经之路，
 *      在后台线程上先刷新快照再放行，保证紧随其后的 filterTask 拿到最新名单；
 *   2) 模块配置变更广播（receiver 在 loadTasks hook / RecentsActivity.onCreate 补注册）；
 *   3) 15s 周期兜底——开机初期 remote prefs / provider 尚未就绪、广播接收器又常因
 *      Application 未就绪而注册失败，没有定时器则 launcher 只有 init 那一次刷新机会。
 */
object LauncherRecentHook {

    private const val TAG = "${Main.TAG}/launch"
    private val hiddenSnapshot = HiddenPackagesSnapshot()
    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HideRecentTiles-launch-refresh").apply { isDaemon = true }
    }

    /** 周期兜底刷新参数与执行器（与 system 侧同款，single scheduled executor） */
    private const val SNAPSHOT_REFRESH_SEC = 15L
    private val periodicStarted = AtomicBoolean(false)
    private val periodicExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HideRecentTiles-launch-periodic").apply { isDaemon = true }
    }

    /** 诊断计数器：限制前 N 次调用打日志，避免刷屏 */
    private var diagCount = 0

    fun hook(module: Main, param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        var count = 0

        // 主 hook：OplusRecentTasksFilter.filterTask(GroupTask): boolean
        count += hookFilterTask(module, loader)

        // 诊断 hook：RecentsActivity.onCreate（确认 launcher 注入成功，不影响显示）
        count += hookDiag(module, loader)

        // 关键 hook：最近任务加载入口。每次打开最近任务先同步刷新快照再放行，
        // 保证紧随其后的 filterTask 一定拿到最新名单（消除首次打开读到空快照的时序问题）
        count += hookRecentsLoad(module, loader)

        // ColorOS 17（SystemLauncher 整体混淆）适配：真实类名已改为 ko.a / com.android.quickstep.f7
        count += hookColorOs17Filter(module, loader)
        count += hookColorOs17RecentsLoad(module, loader)

        // 注册配置变更广播接收器：模块进程每次保存配置都会广播，桌面进程即时更新缓存，
        // 不再依赖轮询 / 不再依赖模块进程长期存活
        registerPrefsReceiver(module)

        // 周期兜底刷新：开机初期各通道可能尚未就绪（实测 init 刷新会失败），
        // 广播接收器又常因 Application 未就绪注册不上——没有定时器，
        // launcher 进程从启动到死掉只有 init 那一次刷新机会，配置就绪晚了就永远读不到。
        startPeriodicRefresh(module)

        refreshSnapshotAsync(module, "init")
        module.log(Log.INFO, TAG, "launcher hooks=$count")
    }

    /** 广播接收器强引用，避免被 GC 回收导致收不到广播 */
    private var prefsReceiver: BroadcastReceiver? = null

    /**
     * 动态注册配置变更接收器。
     *
     * 安全性：使用 signature 级权限 [Main.PREFS_PERMISSION] 作为 receiverPermission，
     * 只有同签名（即模块自身）的应用才能发送该广播。
     */
    private fun registerPrefsReceiver(module: Main) {
        if (prefsReceiver != null) return
        val ctx = currentLauncherContext() ?: run {
            module.log(Log.WARN, TAG, "no Context, prefs receiver not registered")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val raw = i?.getStringExtra(Main.EXTRA_HIDE)
                if (raw != null) {
                    // 走 Main.onPrefsPushed：更新内存缓存 + 落盘磁盘缓存（模块进程死后兜底）
                    module.onPrefsPushed(raw)
                    hiddenSnapshot.replace(module.hiddenPackages)
                } else {
                    refreshSnapshotAsync(module, "broadcast-no-extra")
                }
            }
        }
        val ok = try {
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                IntentFilter(Main.ACTION_PREFS_CHANGED),
                Main.PREFS_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            true
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "registerReceiver failed: $t")
            false
        }
        if (ok) {
            prefsReceiver = receiver
            module.log(Log.INFO, TAG, "prefs receiver registered")
        }
    }

    /** 取桌面进程可用的 Context（ActivityThread.currentApplication） */
    private fun currentLauncherContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getMethod("currentApplication").invoke(null)
        app as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * hook OplusRecentTasksFilter.filterTask(GroupTask): boolean
     * 语义：返回 true = 隐藏该任务，false = 显示。
     * 我们在原方法执行后判断包名，若在隐藏集合则强制返回 true。
     */
    private fun hookFilterTask(module: Main, cl: ClassLoader): Int {
        val filterCls = try {
            cl.loadClass("com.oplus.quickstep.data.OplusRecentTasksFilter")
        } catch (_: Throwable) {
            module.log(Log.WARN, TAG, "OplusRecentTasksFilter not found (non-ColorOS?)")
            return 0
        }
        val groupTaskCls = try {
            cl.loadClass("com.android.quickstep.util.GroupTask")
        } catch (_: Throwable) { return 0 }
        val taskCls = try {
            cl.loadClass("com.android.systemui.shared.recents.model.Task")
        } catch (_: Throwable) { return 0 }

        val task1Field: Field = try {
            groupTaskCls.getDeclaredField("task1").apply { isAccessible = true }
        } catch (_: Throwable) { return 0 }
        val getPackageName: Method = try {
            taskCls.getMethod("getPackageName")
        } catch (_: Throwable) { return 0 }

        val filterTask: Method = try {
            filterCls.getDeclaredMethod("filterTask", groupTaskCls).apply { isAccessible = true }
        } catch (_: Throwable) {
            module.log(Log.ERROR, TAG, "filterTask(GroupTask) method not found")
            return 0
        }

        module.hook(filterTask).setId("filterTask").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "hook fallback: ${it.message}") }
            ) {
                val hidden = hiddenSnapshot.get()
                val groupTask = chain.args.firstOrNull()
                val pkg = groupTask?.let { packageNameOf(it, task1Field, getPackageName) }

                // 诊断：前 5 次调用打印状态，避免刷屏
                val n = diagCount
                if (n < 5) {
                    diagCount = n + 1
                    module.log(Log.INFO, TAG, "filterTask#$n pkg=$pkg hidden=${hidden.size}")
                }

                if (pkg != null && pkg in hidden) {
                    return@evaluateOrProceed true   // true = 隐藏
                }
                null
            }
        }
        module.log(Log.INFO, TAG, "hooked OplusRecentTasksFilter.filterTask")
        return 1
    }

    /** 从 GroupTask.task1 取包名 */
    private fun packageNameOf(groupTask: Any, task1Field: Field, getPackageName: Method): String? {
        return try {
            val task = task1Field.get(groupTask) ?: return null
            getPackageName.invoke(task) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** 诊断 hook：RecentsActivity.onCreate，仅日志，确认注入 */
    private fun hookDiag(module: Main, cl: ClassLoader): Int {
        val cls = try {
            cl.loadClass("com.android.quickstep.RecentsActivity")
        } catch (_: Throwable) { return 0 }
        val onCreate = try {
            cls.getDeclaredMethod("onCreate", Bundle::class.java).apply { isAccessible = true }
        } catch (_: Throwable) {
            return 0
        }
        module.hook(onCreate).setId("diag/RecentsActivity/onCreate").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "diag fallback: ${it.message}") }
            ) {
                // 这里 Application 一定已创建，是补注册广播接收器的可靠时机：
                // onPackageLoaded 阶段 ActivityThread.currentApplication() 常常还是 null，
                // 那样接收器就永远注册不上，推送通道形同虚设。
                registerPrefsReceiver(module)
                refreshSnapshotAsync(module, "recents-onCreate")
                null
            }
        }
        return 1
    }

    private fun refreshSnapshotAsync(module: Main, reason: String) {
        refreshExecutor.execute {
            runCatching {
                hiddenSnapshot.replace(module.hiddenPackages)
            }.onFailure {
                module.log(Log.WARN, TAG, "snapshot refresh failed($reason): ${it.message}")
            }
        }
    }

    /** 周期兜底刷新：与 system 侧同款（首刷延迟一个周期），覆盖通道晚就绪 / 广播注册失败的窗口 */
    private fun startPeriodicRefresh(module: Main) {
        if (!periodicStarted.compareAndSet(false, true)) return
        periodicExecutor.scheduleWithFixedDelay({
            runCatching {
                hiddenSnapshot.replace(module.hiddenPackages)
            }.onFailure {
                module.log(Log.WARN, TAG, "periodic refresh failed: ${it.message}")
            }
        }, SNAPSHOT_REFRESH_SEC, SNAPSHOT_REFRESH_SEC, TimeUnit.SECONDS)
    }

    /**
     * hook 最近任务加载入口 OplusRecentTasksListImpl.loadTasksInBackground()：
     *   if (getFilter().filterTask(map)) { map = null; }
     * 每次打开最近任务都会先进这里。放行前**同步**刷新快照——该方法在后台线程执行，
     * 读一次配置（TTL 1.5s 内复用缓存；provider 首查会冷启动模块进程，仅一次、数百 ms）
     * 不会卡 UI，却能保证紧随其后的 filterTask 一定拿到最新名单。
     * 同时是广播接收器补注册的可靠时机（此时 Application 一定已就绪）。
     */
    private fun hookRecentsLoad(module: Main, cl: ClassLoader): Int {
        val cls = try {
            cl.loadClass("com.android.quickstep.OplusRecentTasksListImpl")
        } catch (_: Throwable) {
            module.log(Log.WARN, TAG, "OplusRecentTasksListImpl not found (non-ColorOS?)")
            return 0
        }
        val methods = cls.declaredMethods.filter { it.name == "loadTasksInBackground" }
        if (methods.isEmpty()) {
            module.log(Log.WARN, TAG, "loadTasksInBackground not found")
            return 0
        }
        var n = 0
        for (m in methods) {
            m.isAccessible = true
            module.hook(m).setId("recentsLoad/${m.parameterTypes.size}").setExceptionMode(
                XposedInterface.ExceptionMode.PROTECTIVE
            ).intercept { chain ->
                HookDecision.evaluateOrProceed(
                    proceed = { chain.proceed() },
                    onError = { module.log(Log.WARN, TAG, "recents-load fallback: ${it.message}") }
                ) {
                    // Application 此时必已就绪，补注册广播接收器（幂等）
                    registerPrefsReceiver(module)
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        // 意外情况：在主线程上调用则退化为异步，绝不阻塞 UI
                        refreshSnapshotAsync(module, "recents-load")
                    } else {
                        runCatching {
                            hiddenSnapshot.replace(module.hiddenPackages)
                            module.log(Log.INFO, TAG, "recents-load refresh: ${hiddenSnapshot.get().size}")
                        }.onFailure {
                            module.log(Log.WARN, TAG, "recents-load refresh failed: ${it.message}")
                        }
                    }
                    null // 不干预原方法，仅前置刷新
                }
            }
            n++
        }
        module.log(Log.INFO, TAG, "hooked OplusRecentTasksListImpl.loadTasksInBackground x$n")
        return n
    }

    // ===== ColorOS 17（SystemLauncher 17.x 整体混淆）适配 =====
    //
    // 17.3.12 对照（源码文件名未混淆，但类名/成员名被 R8 改名）：
    //   com.oplus.quickstep.data.OplusRecentTasksFilter  -> ko.a
    //   filterTask(GroupTask)                            -> ko.a.c(la.j)
    //   主过滤 filter(...)                                -> ko.a.d(int,int,GroupedTaskInfo,ArrayList):boolean
    //   com.android.quickstep.util.GroupTask             -> la.j
    //   com.android.quickstep.OplusRecentTasksListImpl   -> com.android.quickstep.f7
    //   loadTasksInBackground()                          -> f7.h(int,boolean):RecentTasksList$TaskLoadResult
    //
    // 类名仍保留（未混淆）：com.android.wm.shell.shared.GroupedTaskInfo、
    //   com.android.systemui.shared.recents.model.Task、com.android.quickstep.RecentsActivity
    // 因此取包名时优先用这些稳定类型，而不是被改名的成员名。

    private fun hookColorOs17Filter(module: Main, cl: ClassLoader): Int {
        val filterCls = try {
            cl.loadClass("ko.a")
        } catch (_: Throwable) {
            module.log(Log.WARN, TAG, "ko.a not found (not ColorOS 17?)")
            return 0
        }
        var n = 0
        n += hookColorOs17FilterD(module, filterCls)
        n += hookColorOs17FilterTask(module, filterCls, cl)
        return n
    }

    /**
     * ko.a.d(int,int,GroupedTaskInfo,ArrayList): boolean —— 真正的主过滤，返回 true = 隐藏。
     * 包名从 GroupedTaskInfo.e() 返回的 android.app.TaskInfo 取。
     */
    private fun hookColorOs17FilterD(module: Main, filterCls: Class<*>): Int {
        val method = filterCls.declaredMethods.firstOrNull {
            it.name == "d" && it.parameterTypes.size == 4 &&
                it.returnType == java.lang.Boolean.TYPE
        } ?: run {
            module.log(Log.WARN, TAG, "ko.a.d(II..) not found")
            return 0
        }
        // GroupedTaskInfo.e(): TaskInfo（task1；优先按名取 e()，退化到第一个返回 TaskInfo 的无参方法）
        val groupedCls = method.parameterTypes[2]
        val eMethod: Method? = groupedCls.methods.firstOrNull {
            it.name == "e" && it.parameterTypes.isEmpty() &&
                android.app.TaskInfo::class.java.isAssignableFrom(it.returnType)
        } ?: groupedCls.methods.firstOrNull {
            it.parameterTypes.isEmpty() &&
                android.app.TaskInfo::class.java.isAssignableFrom(it.returnType)
        }
        if (eMethod == null) module.log(Log.WARN, TAG, "GroupedTaskInfo.e() not found, pkg may be null")
        method.isAccessible = true
        module.hook(method).setId("c17/filter/d").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "c17 filter/d fallback: ${it.message}") }
            ) {
                val hidden = hiddenSnapshot.get()
                if (hidden.isNotEmpty() && eMethod != null) {
                    val groupedTask = chain.args.getOrNull(2)
                    val pkg = groupedTask?.let { packageNameOfGroupedTask(it, eMethod) }
                    if (pkg != null && pkg in hidden) {
                        return@evaluateOrProceed true
                    }
                }
                null
            }
        }
        module.log(Log.INFO, TAG, "hooked ko.a.d (ColorOS 17 filter)")
        return 1
    }

    /**
     * ko.a.c(la.j): boolean —— 即老的 filterTask(GroupTask)，返回 true = 隐藏。
     * 包名从 la.j 里包裹的 Task 取（按字段类型定位，比改名字段名更稳）。
     */
    private fun hookColorOs17FilterTask(module: Main, filterCls: Class<*>, cl: ClassLoader): Int {
        val method = filterCls.declaredMethods.firstOrNull {
            it.name == "c" && it.parameterTypes.size == 1 &&
                it.returnType == java.lang.Boolean.TYPE
        } ?: run {
            module.log(Log.WARN, TAG, "ko.a.c(la.j) not found")
            return 0
        }
        val groupTaskCls = method.parameterTypes[0]
        // la.j 包裹的 Task：Task 类名未混淆，按类型找字段
        val taskCls = try {
            cl.loadClass("com.android.systemui.shared.recents.model.Task")
        } catch (_: Throwable) {
            null
        }
        val task1Field: Field? = taskCls?.let { tc ->
            groupTaskCls.declaredFields.firstOrNull { it.type == tc }?.apply { isAccessible = true }
        }
        val getPackageName: Method? = try {
            taskCls?.getMethod("getPackageName")
        } catch (_: Throwable) {
            null
        }
        if (task1Field == null || getPackageName == null) {
            module.log(Log.WARN, TAG, "la.j Task field/getPackageName not found")
        }
        method.isAccessible = true
        module.hook(method).setId("c17/filterTask").setExceptionMode(
            XposedInterface.ExceptionMode.PROTECTIVE
        ).intercept { chain ->
            HookDecision.evaluateOrProceed(
                proceed = { chain.proceed() },
                onError = { module.log(Log.WARN, TAG, "c17 filterTask fallback: ${it.message}") }
            ) {
                val hidden = hiddenSnapshot.get()
                if (hidden.isNotEmpty() && task1Field != null && getPackageName != null) {
                    val groupTask = chain.args.firstOrNull()
                    val task = groupTask?.let { runCatching { task1Field.get(it) }.getOrNull() }
                    val pkg = task?.let { runCatching { getPackageName.invoke(it) as? String }.getOrNull() }
                    if (pkg != null && pkg in hidden) {
                        return@evaluateOrProceed true
                    }
                }
                null
            }
        }
        module.log(Log.INFO, TAG, "hooked ko.a.c (ColorOS 17 filterTask)")
        return 1
    }

    /**
     * com.android.quickstep.f7.h(int,boolean): RecentTasksList$TaskLoadResult
     * —— 即老的 loadTasksInBackground：放行前同步刷新快照 + 补注册广播接收器（不干预返回值）。
     */
    private fun hookColorOs17RecentsLoad(module: Main, cl: ClassLoader): Int {
        val cls = try {
            cl.loadClass("com.android.quickstep.f7")
        } catch (_: Throwable) {
            module.log(Log.WARN, TAG, "f7 not found (not ColorOS 17?)")
            return 0
        }
        val methods = cls.declaredMethods.filter {
            it.name == "h" && it.parameterTypes.size == 2 &&
                it.returnType.name == "com.android.quickstep.RecentTasksList\$TaskLoadResult"
        }
        if (methods.isEmpty()) {
            module.log(Log.WARN, TAG, "f7.h(IZ) not found")
            return 0
        }
        var n = 0
        for (m in methods) {
            m.isAccessible = true
            module.hook(m).setId("c17/recentsLoad").setExceptionMode(
                XposedInterface.ExceptionMode.PROTECTIVE
            ).intercept { chain ->
                HookDecision.evaluateOrProceed(
                    proceed = { chain.proceed() },
                    onError = { module.log(Log.WARN, TAG, "c17 recents-load fallback: ${it.message}") }
                ) {
                    registerPrefsReceiver(module)
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        refreshSnapshotAsync(module, "c17-recents-load")
                    } else {
                        runCatching { hiddenSnapshot.replace(module.hiddenPackages) }
                            .onFailure {
                                module.log(Log.WARN, TAG, "c17 recents-load refresh failed: ${it.message}")
                            }
                    }
                    null
                }
            }
            n++
        }
        module.log(Log.INFO, TAG, "hooked f7.h (ColorOS 17 loadTasksInBackground) x$n")
        return n
    }

    /** GroupedTaskInfo.e() -> TaskInfo -> 包名 */
    private fun packageNameOfGroupedTask(groupedTask: Any, eMethod: Method): String? {
        val taskInfo = try {
            eMethod.invoke(groupedTask)
        } catch (_: Throwable) {
            null
        } as? android.app.TaskInfo ?: return null
        return packageNameOfTaskInfo(taskInfo)
    }

    private fun packageNameOfTaskInfo(taskInfo: android.app.TaskInfo): String? =
        taskInfo.baseActivity?.packageName
            ?: taskInfo.baseIntent?.component?.packageName
            ?: taskInfo.baseIntent?.`package`

    private fun parse(raw: String): Set<String> =
        if (raw.isEmpty()) emptySet() else raw.split(',').filter { it.isNotEmpty() }.toSet()
}
