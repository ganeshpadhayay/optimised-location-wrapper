package com.ganesh.optimisedlocationapplication.bean

data class OptimisedLocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val source: LocationSource
)
