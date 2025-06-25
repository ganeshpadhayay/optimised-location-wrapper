package com.ganesh.optimisedlocationapplication.utils

import android.content.Context
import android.location.LocationManager

/**
 * Custom exception for GPS disabled scenarios
 */
class GPSDisabledException(message: String) : Exception(message)

/**
 * Utility method to check GPS status
 */
fun Context.isGPSEnabled(): Boolean {
    val locationManager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
}
