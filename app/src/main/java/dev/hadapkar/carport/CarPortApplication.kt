package dev.hadapkar.carport

import android.app.Application
import dev.hadapkar.carport.data.Logbook

class CarPortApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logbook.init(this)
        // Record crashes before the default handler ends the process.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Logbook.log("CRASH on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
