package com.ganesh.optimisedlocationapplication.bean

data class OptimisedLocationConfig(
    val maxDistanceKm: Double = 10.0, //10 km - distance from last known location of maps in kilometers
    val accuracyThreshold: Float = 100f, // 100 meters - accuracy threshold location received from google location services
    val gpsTimeoutMs: Long = 30000, // 30 seconds
    val networkTimeoutMs: Long = 15000, // 15 seconds
    val recencyThresholdMs: Long = 30000, // 30 seconds - age of last known location received from google location services
    val lastKnownLocationAgeMs: Long = 600000, // 10 minutes - threshold for how recent is the last known location of google location services
    val proximityThresholdM: Double = 1000.0, // 1 km in meters - this is distance from the last successful location in circle check API
)
