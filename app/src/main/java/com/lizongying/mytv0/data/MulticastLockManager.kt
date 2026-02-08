package com.lizongying.mytv0.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * WiFi组播锁管理器
 * 防止播放时WiFi进入休眠导致组播中断
 */
class MulticastLockManager(context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquire() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock(TAG)?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
            Log.i(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    fun release() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
            Log.i(TAG, "Multicast lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MulticastLockManager"
    }
}