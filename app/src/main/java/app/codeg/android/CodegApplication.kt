package app.codeg.android

import android.app.Application
import app.codeg.android.core.common.CrashLog
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. [@HiltAndroidApp][HiltAndroidApp] triggers Hilt's
 * code generation and creates the application-level dependency container.
 */
@HiltAndroidApp
class CodegApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed as early as possible so a crash during startup is recorded too.
        // Records to the cache directory only; nothing leaves the device.
        CrashLog.install(this)
    }
}
