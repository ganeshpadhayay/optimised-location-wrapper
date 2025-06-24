package com.ganesh.optimisedlocationapplication.bean

/**
 * this is final output data class for optimised location
 */
data class OptimisedLocationDataResult(
    val success: Boolean,
    val location: OptimisedLocationData? = null,
    val error: String? = null,
    val userAction: String? = null,
    val message: String? = null,
    val validationDetails: ValidationResult? = null,
    val proximityDetails: ValidationResult? = null
)