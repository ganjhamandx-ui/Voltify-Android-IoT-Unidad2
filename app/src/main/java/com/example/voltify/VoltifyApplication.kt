package com.example.voltify

import android.app.Application
import com.example.voltify.data.VoltifyRepositoryProvider
import java.util.TimeZone

class VoltifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"))
        VoltifyRepositoryProvider.get(this).refresh()
    }
}
