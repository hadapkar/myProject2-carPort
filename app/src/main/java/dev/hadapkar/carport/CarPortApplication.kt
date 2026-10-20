package dev.hadapkar.carport

import android.app.Application
import dev.hadapkar.carport.data.Logbook
import dev.hadapkar.carport.media.MediaEngine

class CarPortApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logbook.init(this)
        // Make sure Android Auto sees exactly the selected media engine's service.
        MediaEngine.apply(this)
        // Record crashes before the default handler ends the process.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Logbook.log("CRASH on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
