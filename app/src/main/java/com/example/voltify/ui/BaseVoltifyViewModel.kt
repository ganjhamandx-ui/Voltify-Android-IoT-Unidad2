package com.example.voltify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.voltify.data.VoltifyRepository
import com.example.voltify.data.VoltifyRepositoryProvider

open class BaseVoltifyViewModel(application: Application) : AndroidViewModel(application) {
    protected val repository: VoltifyRepository = VoltifyRepositoryProvider.get(application)
}
