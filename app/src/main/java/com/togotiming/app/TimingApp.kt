package com.togotiming.app

import android.app.Application
import com.togotiming.app.timer.NotificationHelper

class TimingApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        container.bootstrap()
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as TimingApp).container
