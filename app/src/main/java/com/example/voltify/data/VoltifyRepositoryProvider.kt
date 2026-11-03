package com.example.voltify.data

import android.content.Context

/** Punto único para obtener el repositorio final de la aplicación. */
object VoltifyRepositoryProvider {
    @Volatile
    private var instance: VoltifyRepository? = null

    fun get(context: Context): VoltifyRepository = instance ?: synchronized(this) {
        instance ?: HybridVoltifyRepository(context.applicationContext).also { instance = it }
    }
}
