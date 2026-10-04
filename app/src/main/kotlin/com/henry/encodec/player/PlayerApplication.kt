package com.henry.encodec.player

import android.app.Application

class PlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkProtocolSettings.initialize(this)
        HttpCompressionSettings.initialize(this)
        TcpTransportSettings.initialize(this)
        CronetTransports.initialize(this)
    }

    val playerModel: PlayerViewModel by lazy { PlayerViewModel(this) }
}
