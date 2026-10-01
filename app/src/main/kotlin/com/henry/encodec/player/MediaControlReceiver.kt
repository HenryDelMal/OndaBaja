package com.henry.encodec.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MediaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlayerViewModel.dispatchMediaAction(intent.action)
    }
}
