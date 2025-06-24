package com.ganesh.optimisedlocationapplication.bean

data class OptimisedLocationConfig(
    val maxDistanceKm: Double = 10.0,
    val accuracyThreshold: Float = 100f, // meters
    val gpsTimeoutMs: Long = 30000, // 30 seconds
    val recencyThresholdMs: Long = 30000, // 30 seconds
    val lastKnownLocationAgeMs: Long = 600000, // 10 minutes
    val proximityThresholdM: Double = 1000.0, // 1 km in meters
    val networkTimeoutMs: Long = 15000 // 15 seconds
)
