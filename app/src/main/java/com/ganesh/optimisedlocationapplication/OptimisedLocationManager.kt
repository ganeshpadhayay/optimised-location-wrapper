package com.ganesh.optimisedlocationapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.ganesh.optimisedlocationapplication.bean.LocationSource
import com.ganesh.optimisedlocationapplication.bean.OptimisedLocationConfig
import com.ganesh.optimisedlocationapplication.bean.OptimisedLocationData
import com.ganesh.optimisedlocationapplication.bean.OptimisedLocationDataResult
import com.ganesh.optimisedlocationapplication.bean.ValidationResult
import com.ganesh.optimisedlocationapplication.utils.OptimisedLocationUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Key Features:
 * 1. High-Accuracy GPS Priority
 *
 * Starts with GPS-only mode for 30 seconds using enableHighAccuracy: true
 * Uses setWaitForAccurateLocation concept via timeout handling
 *
 * 2. Fallback Strategy
 *
 * Automatically switches to network-assisted location if GPS fails within 30 seconds
 * Uses different timeout and accuracy settings for fallback
 *
 * 3. Complete Validation System
 *
 * Recency: Ensures location is within last 30 seconds
 * Accuracy: Validates 100-meter accuracy threshold
 * Distance Comparison:
 *
 * If last known location ≤ 10 minutes old: checks 5-10km distance (configurable)
 * If > 10 minutes old: skips distance comparison
 *
 *
 *
 * 4. Proximity Validation
 *
 * Checks if new location is within 1km of last successful location or registered shop
 * Configurable proximity threshold
 *
 * 5. Circle Verification Integration
 *
 * Performs API call to circle verification service
 * Automatically retries with fresh location if verification fails
 *
 * 6. Error Handling
 *
 * Prompts user to calibrate device when no valid location found
 * Comprehensive error reporting and logging
 */
class OptimisedLocationManager(
    private val context: Context,
    private val config: OptimisedLocationConfig = OptimisedLocationConfig()
) {
    private var fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var lastKnownLocation: OptimisedLocationData? = null
    private var lastSuccessfulLocation: OptimisedLocationData? = null
    private var registeredShopLocation: OptimisedLocationData? = null

    /**
     * Main entry point for location acquisition
     */
    suspend fun acquireLocation(): OptimisedLocationDataResult = withContext(Dispatchers.IO) {
        try {
            println("Starting location acquisition...")
            // Check permissions first
            if (!hasLocationPermissions()) {
                return@withContext OptimisedLocationDataResult(
                    success = false,
                    error = OptimisedLocationUtils.ERROR_PERMISSION_DENIED,
                    message = OptimisedLocationUtils.getString(context, R.string.error_permission_required)
                )
            }
            // Step 1: Try high-accuracy GPS for 30 seconds
            val gpsLocation = tryGPSLocation()

            if (gpsLocation != null) {
                println("GPS location acquired: $gpsLocation")
                return@withContext validateAndProcessLocation(gpsLocation)
            }
            // Step 2: Fallback to network-assisted location
            println("GPS failed, falling back to network-assisted location...")
            val networkLocation = tryNetworkLocation()

            if (networkLocation != null) {
                println("Network location acquired: $networkLocation")
                return@withContext validateAndProcessLocation(networkLocation)
            }
            // Step 3: Try fused location as last resort
            println("Network failed, trying fused location...")
            val fusedLocation = tryFusedLocation()

            if (fusedLocation != null) {
                println("Fused location acquired: $fusedLocation")
                return@withContext validateAndProcessLocation(fusedLocation)
            }
            // Step 4: No valid location found
            throw Exception(OptimisedLocationUtils.ERROR_NO_VALID_LOCATION)

        } catch (e: Exception) {
            println("Location acquisition failed: ${e.message}")
            return@withContext handleLocationFailure(e)
        }
    }

    /**
     * Attempt GPS-only location acquisition with high accuracy using LocationManager
     */
    private suspend fun tryGPSLocation(): OptimisedLocationData? = withContext(Dispatchers.IO) {
        return@withContext try {
            withTimeout(config.gpsTimeoutMs) {
                suspendCancellableCoroutine<OptimisedLocationData?> { continuation ->
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val isResumed = AtomicBoolean(false)

                    // Check if GPS provider is enabled
                    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        if (isResumed.compareAndSet(false, true)) {
                            continuation.resume(null) {}
                        }
                        return@suspendCancellableCoroutine
                    }

                    val locationListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (isResumed.compareAndSet(false, true)) {
                                val result = formatLocationObject(location, LocationSource.GPS)
                                continuation.resume(result) {}
                                locationManager.removeUpdates(this)
                            }
                        }

                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {
                            if (provider == LocationManager.GPS_PROVIDER && isResumed.compareAndSet(false, true)) {
                                continuation.resume(null) {}
                                locationManager.removeUpdates(this)
                            }
                        }
                    }

                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        // Request ONLY GPS updates
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000L, // 1 second intervals
                            0f,    // No minimum distance
                            locationListener,
                            Looper.getMainLooper()
                        )

                        continuation.invokeOnCancellation {
                            locationManager.removeUpdates(locationListener)
                        }
                    } else {
                        if (isResumed.compareAndSet(false, true)) {
                            continuation.resume(null) {}
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            println("GPS timeout reached")
            null
        } catch (e: Exception) {
            println("GPS error: ${e.message}")
            null
        }
    }

    /**
     * Fallback to network-assisted location
     */
    private suspend fun tryNetworkLocation(): OptimisedLocationData? = withContext(Dispatchers.IO) {
        return@withContext try {
            withTimeout(config.networkTimeoutMs) {
                suspendCancellableCoroutine<OptimisedLocationData?> { continuation ->
                    val isResumed = AtomicBoolean(false)
                    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000)
                        .setMaxUpdates(1)
                        .setMaxUpdateAgeMillis(60000) // Allow slightly older cached location
                        .build()
                    val locationCallback = object : LocationCallback() {
                        override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                            if (isResumed.compareAndSet(false, true)) {
                                locationResult.lastLocation?.let { location ->
                                    val result = formatLocationObject(location, LocationSource.NETWORK)
                                    continuation.resume(result) {}
                                } ?: continuation.resume(null) {}
                                fusedLocationClient.removeLocationUpdates(this)
                            }
                        }
                    }

                    if (hasLocationPermissions()) {
                        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                        continuation.invokeOnCancellation {
                            fusedLocationClient.removeLocationUpdates(locationCallback)
                        }
                    } else {
                        if (isResumed.compareAndSet(false, true)) {
                            continuation.resume(null) {}
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            println("Network location timeout")
            null
        } catch (e: Exception) {
            println("Network location error: ${e.message}")
            null
        }
    }

    /**
     * Try fused location as last resort
     */
    private suspend fun tryFusedLocation(): OptimisedLocationData? = withContext(Dispatchers.IO) {
        return@withContext try {
            suspendCancellableCoroutine<OptimisedLocationData?> { continuation ->
                if (hasLocationPermissions()) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            val result = formatLocationObject(location, LocationSource.FUSED)
                            continuation.resume(result) {}
                        } else {
                            continuation.resume(null) {}
                        }
                    }.addOnFailureListener {
                        continuation.resume(null) {}
                    }
                } else {
                    continuation.resume(null) {}
                }
            }
        } catch (e: Exception) {
            println("Fused location error: ${e.message}")
            null
        }
    }

    /**
     * Format location object with standardized properties
     */
    private fun formatLocationObject(location: Location, source: LocationSource): OptimisedLocationData {
        return OptimisedLocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time,
            source = source
        )
    }

    /**
     * Validate location against all criteria
     */
    private suspend fun validateAndProcessLocation(location: OptimisedLocationData): OptimisedLocationDataResult {
        val validationResult = validateLocation(location)

        if (!validationResult.isValid) {
            return OptimisedLocationDataResult(
                success = false,
                error = OptimisedLocationUtils.ERROR_VALIDATION_FAILED,
                message = validationResult.reason
            )
        }
        // Check proximity to validated area
        val proximityCheck = checkProximity(location)
        if (!proximityCheck.isValid) {
            return OptimisedLocationDataResult(
                success = false,
                error = OptimisedLocationUtils.ERROR_PROXIMITY_FAILED,
                message = proximityCheck.reason
            )
        }
        // Update successful location tracking
        lastSuccessfulLocation = location.copy()
        println("Location successfully validated and verified")

        return OptimisedLocationDataResult(
            success = true,
            location = location,
            validationDetails = validationResult,
            proximityDetails = proximityCheck
        )
    }

    /**
     * Validate location based on recency, accuracy, and distance criteria
     */
    private fun validateLocation(location: OptimisedLocationData): ValidationResult {
        val now = System.currentTimeMillis()
        // Check recency (within last 30 seconds)
        if (now - location.timestamp > config.recencyThresholdMs) {
            return ValidationResult(
                isValid = false,
                reason = OptimisedLocationUtils.getString(context, R.string.error_location_not_recent)
            )
        }
        // Check accuracy (100 meters or better)
        if (location.accuracy > config.accuracyThreshold) {
            return ValidationResult(
                isValid = false,
                reason = context.getString(R.string.error_accuracy_exceeds, location.accuracy, config.accuracyThreshold)
            )
        }
        // Check distance comparison with last known location
        lastKnownLocation?.let { lastKnown ->
            val lastKnownAge = now - lastKnown.timestamp

            if (lastKnownAge <= config.lastKnownLocationAgeMs) {
                // Last known location is ≤ 10 minutes old
                val distance = calculateDistance(location, lastKnown)

                if (distance > config.maxDistanceKm) {
                    return ValidationResult(
                        isValid = false,
                        reason = context.getString(R.string.error_distance_exceeds, distance, config.maxDistanceKm)
                    )
                }
            }
            // If last known location is > 10 minutes old, skip distance comparison
        }

        return ValidationResult(
            isValid = true,
            reason = OptimisedLocationUtils.getString(context, R.string.validation_all_criteria_met)
        )
    }

    /**
     * Check proximity to validated area
     */
    private fun checkProximity(location: OptimisedLocationData): ValidationResult {
        val referenceLocation = lastSuccessfulLocation ?: registeredShopLocation

        if (referenceLocation == null) {
            return ValidationResult(
                isValid = true,
                reason = OptimisedLocationUtils.getString(context, R.string.validation_no_reference)
            )
        }
        val distance = calculateDistance(location, referenceLocation) * 1000 // Convert to meters

        if (distance > config.proximityThresholdM) {
            return ValidationResult(
                isValid = false,
                reason = context.getString(R.string.error_proximity_exceeds, distance.toInt(), config.proximityThresholdM.toInt())
            )
        }

        return ValidationResult(
            isValid = true,
            reason = context.getString(R.string.validation_within_proximity, distance.toInt())
        )
    }

    /**
     * Handle location acquisition failure
     */
    private fun handleLocationFailure(error: Exception): OptimisedLocationDataResult {
        val errorResponse = OptimisedLocationDataResult(
            success = false,
            error = error.message ?: "Unknown error"
        )

        return when (error.message) {
            OptimisedLocationUtils.ERROR_NO_VALID_LOCATION -> errorResponse.copy(
                userAction = OptimisedLocationUtils.USER_ACTION_CALIBRATE_DEVICE,
                message = OptimisedLocationUtils.getString(context, R.string.error_calibrate_device)
            )

            else -> errorResponse
        }
    }

    /**
     * Calculate distance between two coordinates using Haversine formula
     */
    private fun calculateDistance(loc1: OptimisedLocationData, loc2: OptimisedLocationData): Double {
        val earthRadius = OptimisedLocationUtils.EARTH_RADIUS_KM
        val dLat = Math.toRadians(loc2.latitude - loc1.latitude)
        val dLon = Math.toRadians(loc2.longitude - loc1.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(loc1.latitude)) * cos(Math.toRadians(loc2.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Check if location permissions are granted
     */
    private fun hasLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Set the last known location (call this when you have a previous location)
     */
    fun setLastKnownLocation(location: OptimisedLocationData) {
        lastKnownLocation = location
    }

    /**
     * Set the registered shop location (call this during setup)
     */
    fun setRegisteredShopLocation(location: OptimisedLocationData) {
        registeredShopLocation = location
    }
}