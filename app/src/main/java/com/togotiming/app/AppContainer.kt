package com.togotiming.app

import android.content.Context
import com.togotiming.app.data.AppDatabase
import com.togotiming.app.data.SettingsStore
import com.togotiming.app.data.ThemePreferences
import com.togotiming.app.data.TimingRepository
import com.togotiming.app.nfc.NfcController
import com.togotiming.app.timer.TimerController
import com.togotiming.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val context: Context = appContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = AppDatabase.build(appContext)

    val repository = TimingRepository(
        database,
        database.activityTypeDao(),
        database.timeRecordDao(),
        database.nfcBindingDao(),
    )

    val settings = SettingsStore(appContext)

    val themePreferences = ThemePreferences(appContext)

    val widgetUpdater = WidgetUpdater(appContext, repository, settings)

    val timerController = TimerController(appContext, repository, widgetUpdater, settings)

    val nfcController = NfcController(appContext, repository, settings, timerController, appScope)

    /** 进程启动后：恢复计时状态（前台服务 + 小部件），并刷新所有小部件 */
    fun bootstrap() {
        appScope.launch {
            try {
                widgetUpdater.updateAll()
                if (repository.activeNow() != null) {
                    timerController.ensureServiceRunning()
                }
            } catch (_: Exception) {
            }
        }
    }
}
