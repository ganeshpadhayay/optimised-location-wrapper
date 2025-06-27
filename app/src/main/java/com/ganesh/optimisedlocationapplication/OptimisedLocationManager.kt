package com.ganesh.optimisedlocationapplication

import android.Manifest
import android.annotation.SuppressLint
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
import com.ganesh.optimisedlocationapplication.utils.GPSDisabledException
import com.ganesh.optimisedlocationapplication.utils.OptimisedLocationUtils
import com.ganesh.optimisedlocationapplication.utils.isGPSEnabled
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Concurrent Location Manager with cascading timeout execution
 *
 * Key Features:
 * 1. Cascading Timeouts: GPS (30s) > Network (15s) > Fused (fallback)
 * 2. Early Termination: GPS success cancels all others immediately
 * 3. Priority-based Fallback: GPS > Network > Fused
 * 4. Maximum 30s total execution time
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
     * Main entry point for concurrent location acquisition
     * Modified to handle GPS-off case by stopping all threads
     */
    suspend fun acquireLocation(): OptimisedLocationDataResult = withContext(Dispatchers.IO) {
        try {
            println("Starting concurrent location acquisition...")
            // Check permissions first
            if (!hasLocationPermissions()) {
                return@withContext OptimisedLocationDataResult(
                    success = false,
                    error = OptimisedLocationUtils.ERROR_PERMISSION_DENIED,
                    message = OptimisedLocationUtils.getString(context, R.string.error_permission_required),
                    userAction = OptimisedLocationUtils.USER_ACTION_REQUEST_PERMISSIONS
                )
            }
            // Check if GPS is enabled before starting any location requests
            if (!context.isGPSEnabled()) {
                println("GPS provider is disabled - stopping all location requests")
                return@withContext OptimisedLocationDataResult(
                    success = false,
                    error = OptimisedLocationUtils.ERROR_GPS_DISABLED,
                    message = OptimisedLocationUtils.getString(context, R.string.error_gps_disabled),
                    userAction = OptimisedLocationUtils.USER_ACTION_ENABLE_GPS
                )
            }
            // Run all location methods with cascading timeouts
            val bestLocation = tryAllLocationMethodsWithCascadingTimeouts()

            if (bestLocation != null) {
                println("Best location acquired: ${bestLocation.source} - ${bestLocation}")
                return@withContext validateAndProcessLocation(bestLocation)
            }
            // No valid location found
            throw Exception(OptimisedLocationUtils.ERROR_NO_VALID_LOCATION)

        } catch (e: Exception) {
            println("Concurrent location acquisition failed: ${e.message}")
            return@withContext handleLocationFailure(e)
        }
    }

    /**
     * Run all location methods with cascading timeouts and priority-based selection
     * 1. GPS gets full timeout (30s) - if succeeds, cancels others
     * 2. Network gets 15s - if GPS fails and Network succeeds, return Network
     * 3. Fused is fallback - return if both GPS and Network fail
     * 4. Maximum execution time is GPS timeout (30s)
     */
    private suspend fun tryAllLocationMethodsWithCascadingTimeouts(): OptimisedLocationData? = coroutineScope {
        println("Starting cascading timeout location requests...")

        // Start all three location requests concurrently
        val gpsJob = async { tryGPSLocationConcurrent() }
        val networkJob = async { tryNetworkLocationConcurrent() }
        val fusedJob = async { tryFusedLocationConcurrent() }

        try {
            // Overall timeout is GPS timeout (30s max)
            withTimeout(config.gpsTimeoutMs) {
                // Wait for GPS to complete first (it has the longest timeout)
                val gpsResult = try {
                    gpsJob.await()
                } catch (e: Exception) {
                    println("GPS request failed: ${e.message}")
                    null
                }

                // If GPS succeeded, cancel others and return GPS result
                if (gpsResult != null) {
                    println("GPS location acquired - cancelling other requests")
                    networkJob.cancel()
                    fusedJob.cancel()
                    return@withTimeout gpsResult
                }

                // GPS failed, check Network result
                val networkResult = try {
                    networkJob.await()
                } catch (e: Exception) {
                    println("Network request failed: ${e.message}")
                    null
                }

                // If Network succeeded, cancel Fused and return Network result
                if (networkResult != null) {
                    println("Network location acquired (GPS failed) - cancelling Fused")
                    fusedJob.cancel()
                    return@withTimeout networkResult
                }

                // Both GPS and Network failed, get Fused result as fallback
                val fusedResult = try {
                    fusedJob.await()
                } catch (e: Exception) {
                    println("Fused request failed: ${e.message}")
                    null
                }

                if (fusedResult != null) {
                    println("Fused location acquired (GPS and Network failed)")
                } else {
                    println("All location methods failed")
                }

                return@withTimeout fusedResult
            }

        } catch (e: TimeoutCancellationException) {
            println("Overall location acquisition timed out after ${config.gpsTimeoutMs}ms")
            // Cancel all running jobs
            gpsJob.cancel()
            networkJob.cancel()
            fusedJob.cancel()
            return@coroutineScope null
        } catch (e: Exception) {
            println("Error in cascading location requests: ${e.message}")
            // Cancel all running jobs
            gpsJob.cancel()
            networkJob.cancel()
            fusedJob.cancel()
            throw e
        }
    }

    /**
     * GPS location with enhanced GPS status monitoring
     */
    private suspend fun tryGPSLocationConcurrent(): OptimisedLocationData? = withContext(Dispatchers.IO) {
        return@withContext try {
            println("Starting GPS location request (${config.gpsTimeoutMs}ms timeout)...")
            withTimeout(config.gpsTimeoutMs) {
                suspendCancellableCoroutine<OptimisedLocationData?> { continuation ->
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val isResumed = AtomicBoolean(false)
                    // Double-check if GPS provider is enabled
                    if (!context.isGPSEnabled()) {
                        println("GPS provider is disabled")
                        if (isResumed.compareAndSet(false, true)) {
                            // Instead of resuming with null, throw an exception to stop all threads
                            continuation.resumeWithException(
                                GPSDisabledException("GPS is disabled during location request")
                            )
                        }
                        return@suspendCancellableCoroutine
                    }
                    val locationListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            println("GPS location received: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}")
                            if (isResumed.compareAndSet(false, true)) {
                                val result = formatLocationObject(location, LocationSource.GPS)
                                continuation.resume(result) {}
                                locationManager.removeUpdates(this)
                            }
                        }

                        override fun onProviderEnabled(provider: String) {
                            println("GPS provider enabled: $provider")
                        }

                        override fun onProviderDisabled(provider: String) {
                            println("GPS provider disabled during request: $provider")
                            if (provider == LocationManager.GPS_PROVIDER && isResumed.compareAndSet(false, true)) {
                                // GPS was disabled during the request - stop all threads
                                continuation.resumeWithException(
                                    GPSDisabledException("GPS was disabled during location request")
                                )
                                locationManager.removeUpdates(this)
                            }
                        }
                    }

                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000L, // 1 second intervals
                            0f,    // No minimum distance
                            locationListener,
                            Looper.getMainLooper()
                        )

                        continuation.invokeOnCancellation {
                            println("GPS location request cancelled")
                            locationManager.removeUpdates(locationListener)
                        }
                    } else {
                        if (isResumed.compareAndSet(false, true)) {
                            continuation.resume(null) {}
                        }
                    }
                }
            }
        } catch (e: GPSDisabledException) {
            println("GPS disabled exception: ${e.message}")
            // Re-throw to be caught by the parent coroutine scope
            throw e
        } catch (e: TimeoutCancellationException) {
            println("GPS timeout after ${config.gpsTimeoutMs}ms")
            null
        } catch (e: Exception) {
            println("GPS error: ${e.message}")
            null
        }
    }

    /**
     * Network location with configurable timeout
     */
    @SuppressLint("MissingPermission")
    private suspend fun tryNetworkLocationConcurrent(): OptimisedLocationData? = withContext(Dispatchers.IO) {
        return@withContext try {
            println("Starting Network location request (${config.networkTimeoutMs}ms timeout)...")
            withTimeout(config.networkTimeoutMs) {
                suspendCancellableCoroutine<OptimisedLocationData?> { continuation ->
                    val isResumed = AtomicBoolean(false)
                    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000)
                        .setMaxUpdates(1)
                        .setMaxUpdateAgeMillis(60000)
                        .build()
                    val locationCallback = object : LocationCallback() {
                        override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                            println("Network location result received")
                            if (isResumed.compareAndSet(false, true)) {
                                locationResult.lastLocation?.let { location ->
                                    println("Network location: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}")
                                    val result = formatLocationObject(location, LocationSource.NETWORK)
                                    continuation.resume(result) {}
                                } ?: run {
                                    println("Network location result was null")
                                    continuation.resume(null) {}
                                }
                                fusedLocationClient.removeLocationUpdates(this)
                            }
                        }
                    }

                    if (hasLocationPermissions()) {
                        println("Requesting network location updates...")
                        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                        continuation.invokeOnCancellation {
                            println("Network location request cancelled")
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
            println("Network location timeout after ${config.networkTimeoutMs}ms")
            null
        } catch (e: Exception) {
            println("Network location error: ${e.message}")
            null
        }
    }

    /**
     * Fused location with no timeout (instant last known location)
     */
    @SuppressLint("MissingPermission")
    private suspend fun tryFusedLocationConcurrent(): OptimisedLocationData? = withContext(Dispatchers.IO) {
        return@withContext try {
            println("Starting Fused location request (no timeout)...")
            suspendCancellableCoroutine<OptimisedLocationData?> { continuation ->
                if (hasLocationPermissions()) {
                    println("Requesting last known fused location...")
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            println("Fused location: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}")
                            val result = formatLocationObject(location, LocationSource.FUSED)
                            continuation.resume(result) {}
                        } else {
                            println("Fused location was null")
                            continuation.resume(null) {}
                        }
                    }.addOnFailureListener { exception ->
                        println("Fused location failed: ${exception.message}")
                        continuation.resume(null) {}
                    }
                } else {
                    println("Location permissions not available for fused location")
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
    private fun validateAndProcessLocation(location: OptimisedLocationData): OptimisedLocationDataResult {
        println("Validating location: ${location.source} - lat=${location.latitude}, lng=${location.longitude}")
        val validationResult = validateLocation(location)

        if (!validationResult.isValid) {
            println("Location validation failed: ${validationResult.reason}")
            return OptimisedLocationDataResult(
                success = false,
                error = OptimisedLocationUtils.ERROR_VALIDATION_FAILED,
                message = validationResult.reason
            )
        }
        // Check proximity to validated area
        val proximityCheck = checkProximity(location)
        if (!proximityCheck.isValid) {
            println("Proximity check failed: ${proximityCheck.reason}")
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
            } else {
                // Last known location is > 10 minutes old, skip distance check
                println("Skipping distance check as last known location is older than 10 minutes")
                return ValidationResult(
                    isValid = false,
                    reason = context.getString(R.string.error_last_location_data_old, lastKnownAge)
                )
            }
        }
        // All validation criteria met
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
        println("Last known location set: lat=${location.latitude}, lng=${location.longitude}")
    }

    /**
     * Set the registered shop location (call this during setup)
     */
    fun setRegisteredShopLocation(location: OptimisedLocationData) {
        registeredShopLocation = location
        println("Registered shop location set: lat=${location.latitude}, lng=${location.longitude}")
    }
}