package com.botvistas.app.util

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceUtils {

    /**
     * Obtiene el ID único del dispositivo Android basado en ANDROID_ID.
     */
    fun getDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank()) {
                "ANDROID-${androidId.take(8).uppercase()}"
            } else {
                "ANDROID-${Build.MODEL.replace(" ", "_").take(8).uppercase()}"
            }
        } catch (e: Exception) {
            "ANDROID-DEV-001"
        }
    }

    /**
     * Nombre amigable del modelo de dispositivo.
     */
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Nivel actual de batería (0-100).
     */
    fun getBatteryPercentage(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Obtiene la dirección IP local asignada al dispositivo en Wi-Fi.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return "127.0.0.1"
    }
}
