package com.devaprakash.fastdrop

import android.app.Application

class App : Application() {
    val engine by lazy { Engine(this) }
}
