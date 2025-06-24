package com.ganesh.optimisedlocationapplication.bean

data class OptimisedLocationDataResult(
    val success: Boolean,
    val location: OptimisedLocationData? = null,
    val error: String? = null,
    val userAction: String? = null,
    val message: String? = null,
    val validationDetails: ValidationResult? = null,
    val proximityDetails: ValidationResult? = null,
    val circleVerification: CircleVerificationResult? = null
)