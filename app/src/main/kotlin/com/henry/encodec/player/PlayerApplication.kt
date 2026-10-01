package com.henry.encodec.player

import android.app.Application

class PlayerApplication : Application() {
    val playerModel: PlayerViewModel by lazy { PlayerViewModel(this) }
}
