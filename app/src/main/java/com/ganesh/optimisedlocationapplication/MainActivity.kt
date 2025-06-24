package com.ganesh.optimisedlocationapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.ganesh.optimisedlocationapplication.bean.LocationSource
import com.ganesh.optimisedlocationapplication.bean.OptimisedLocationConfig
import com.ganesh.optimisedlocationapplication.bean.OptimisedLocationData
import com.ganesh.optimisedlocationapplication.bean.OptimisedLocationDataResult
import com.ganesh.optimisedlocationapplication.utils.OptimisedLocationUtils
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch

/**
 * What the Client Handles Automatically:
 *
 * Permission Flow: Requests → Rationale → Retry → Settings
 * Location Services: Check → Prompt → Settings → Verify
 * Error Handling: Validation → Proximity → Circle verification failures
 * User Guidance: Clear messages and action buttons for each scenario
 * State Management: Prevents conflicts and manages callbacks
 *
 *
 * Key Features:
 * 1. Complete Permission Management
 *
 * Runtime permission requests for both fine and coarse location
 * Permission rationale dialogs with user-friendly explanations
 * Handles permanently denied permissions with settings navigation
 * Automatic retry after permission grants
 *
 * 2. Location Services Management
 *
 * Checks if location services are enabled
 * Guides users to enable location services
 * Handles location service state changes
 *
 * 3. User-Friendly Dialogs
 *
 * Permission rationale explanations
 * Error messages with retry options
 * Success confirmations with location details
 * Settings navigation when needed
 *
 * 4. Robust State Management
 *
 * Prevents multiple simultaneous location requests
 * Handles activity lifecycle properly
 * Cleans up resources and callbacks
 *
 * 5. Flexible Usage Patterns
 *
 * Simple fire-and-forget location requests
 * Callback-based location acquisition
 * Readiness checking before requests
 */
class MainActivity : AppCompatActivity() {
    private lateinit var optimisedLocationManager: OptimisedLocationManager
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var locationSettingsLauncher: ActivityResultLauncher<Intent>
    private var isLocationRequestInProgress = false
    private var pendingLocationCallback: ((OptimisedLocationDataResult) -> Unit)? = null
    private var buttonGetLocation: Button? = null

    companion object {
        private const val REQUEST_CODE_ENABLE_GPS = 1001
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeLocationManager()
        setupPermissionLaunchers()
        setupLocationSettingsLauncher()

        buttonGetLocation = findViewById(R.id.btnGetLocation)
        buttonGetLocation?.setOnClickListener {
            startLocationProcess()
        }
    }

    /**
     * Initialize the location acquisition manager with custom config
     */
    private fun initializeLocationManager() {
        val config = OptimisedLocationConfig(
            maxDistanceKm = 10.0,
            accuracyThreshold = 100f,
            gpsTimeoutMs = 30000,
            recencyThresholdMs = 30000,
            lastKnownLocationAgeMs = 600000,
            proximityThresholdM = 1000.0,
            networkTimeoutMs = 15000
        )

        optimisedLocationManager = OptimisedLocationManager(this, config)
        // Set reference locations if available
        setReferenceLocations()
    }

    /**
     * Set up permission request launchers
     */
    private fun setupPermissionLaunchers() {
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResults(permissions)
        }
    }

    /**
     * Set up location settings launcher
     */
    private fun setupLocationSettingsLauncher() {
        locationSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            // Check if location is now enabled
            if (isLocationEnabled()) {
                proceedWithLocationAcquisition()
            } else {
                showLocationDisabledDialog()
            }
        }
    }

    /**
     * Main entry point to start the location acquisition process
     */
    private fun startLocationProcess(callback: ((OptimisedLocationDataResult) -> Unit)? = null) {
        if (isLocationRequestInProgress) {
            Toast.makeText(this, getString(R.string.location_request_in_progress), Toast.LENGTH_SHORT).show()
            return
        }

        pendingLocationCallback = callback
        isLocationRequestInProgress = true

        when {
            hasAllLocationPermissions() -> {
                if (isLocationEnabled()) {
                    proceedWithLocationAcquisition()
                } else {
                    showLocationServiceDialog()
                }
            }

            shouldShowPermissionRationale() -> {
                showPermissionRationaleDialog()
            }

            else -> {
                requestLocationPermissions()
            }
        }
    }

    /**
     * Check if all required location permissions are granted
     */
    private fun hasAllLocationPermissions(): Boolean {
        val fineLocation = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation && coarseLocation
    }

    /**
     * Check if we should show permission rationale
     */
    private fun shouldShowPermissionRationale(): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) || ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /**
     * Request location permissions
     */
    private fun requestLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        locationPermissionLauncher.launch(permissions)
    }

    /**
     * Handle permission request results
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        when {
            fineLocationGranted && coarseLocationGranted -> {
                // All permissions granted
                if (isLocationEnabled()) {
                    proceedWithLocationAcquisition()
                } else {
                    showLocationServiceDialog()
                }
            }

            fineLocationGranted -> {
                // Only fine location granted, proceed anyway
                if (isLocationEnabled()) {
                    proceedWithLocationAcquisition()
                } else {
                    showLocationServiceDialog()
                }
            }

            else -> {
                // Permissions denied
                handlePermissionDenied()
            }
        }
    }

    /**
     * Handle permission denial
     */
    private fun handlePermissionDenied() {
        isLocationRequestInProgress = false

        if (shouldShowPermissionRationale()) {
            showPermissionDeniedDialog()
        } else {
            showPermissionPermanentlyDeniedDialog()
        }
        pendingLocationCallback?.invoke(
            OptimisedLocationDataResult(
                success = false,
                error = OptimisedLocationUtils.ERROR_PERMISSION_DENIED,
                message = getString(R.string.error_permission_required)
            )
        )
        pendingLocationCallback = null
    }

    /**
     * Check if location services are enabled
     */
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Proceed with actual location acquisition
     */
    private fun proceedWithLocationAcquisition() {
        lifecycleScope.launch {
            try {
                val result = optimisedLocationManager.acquireLocation()
                handleLocationResult(result)
            } catch (e: Exception) {
                handleLocationError(e)
            } finally {
                isLocationRequestInProgress = false
            }
        }
    }

    /**
     * Ask user to enable GPS if not already enabled
     */
    private fun askUserToEnableGPS(context: Context, onResult: (Boolean) -> Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true) // shows system dialog even if already satisfied
        val settingsClient = LocationServices.getSettingsClient(context)
        val task = settingsClient.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            // All location settings are satisfied
            onResult(true)
        }.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                // Location settings are not satisfied, show dialog
                try {
                    exception.startResolutionForResult(
                        context as Activity, // ensure you're calling from an Activity
                        REQUEST_CODE_ENABLE_GPS
                    )
                } catch (sendEx: Exception) {
                    onResult(false)
                }
            } else {
                onResult(false)
            }
        }
    }

    /**
     * Handle successful location acquisition result
     */
    private fun handleLocationResult(result: OptimisedLocationDataResult) {
        if (result.success) {
            result.location?.let { location ->
                showLocationSuccess(location)
            }
        } else {
            showLocationError(result)
        }
        // Notify callback
        pendingLocationCallback?.invoke(result)
        pendingLocationCallback = null
    }

    /**
     * Handle location acquisition error
     */
    private fun handleLocationError(error: Exception) {
        val result = OptimisedLocationDataResult(
            success = false,
            error = error.message ?: "Unknown error",
            message = "Failed to acquire location: ${error.message}"
        )

        showLocationError(result)
        // Notify callback
        pendingLocationCallback?.invoke(result)
        pendingLocationCallback = null
    }

    /**
     * Show location success message
     */
    @SuppressLint("DefaultLocale")
    private fun showLocationSuccess(location: OptimisedLocationData) {
        val message = getString(
            R.string.location_success_message,
            location.latitude,
            location.longitude,
            location.accuracy,
            location.source
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_found))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    /**
     * Show location error message
     */
    private fun showLocationError(result: OptimisedLocationDataResult) {
        val title = when (result.error) {
            OptimisedLocationUtils.ERROR_VALIDATION_FAILED -> getString(R.string.location_validation_failed)
            OptimisedLocationUtils.ERROR_PROXIMITY_FAILED -> getString(R.string.location_out_of_range)
            OptimisedLocationUtils.ERROR_NO_VALID_LOCATION -> getString(R.string.no_location_available)
            else -> getString(R.string.location_error)
        }
        val message = result.message ?: getString(R.string.unknown_error_occurred)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.retry)) { _, _ ->
                startLocationProcess(pendingLocationCallback)
            }
            .setNegativeButton(getString(R.string.cancel), null)

        if (result.userAction == OptimisedLocationUtils.USER_ACTION_CALIBRATE_DEVICE) {
            OptimisedLocationUtils.showCalibrationDialog(this)
        }

        if (result.userAction == OptimisedLocationUtils.USER_ACTION_REQUEST_PERMISSIONS) {
            requestLocationPermissions()
        }

        if (result.userAction == OptimisedLocationUtils.USER_ACTION_ENABLE_GPS) {
            askUserToEnableGPS(this) { gpsEnabled ->
                if (gpsEnabled) {
                    startLocationProcess()
                } else {
                    Log.e(TAG, getString(R.string.gps_still_disabled_after_prompt))
                    Toast.makeText(this, getString(R.string.gps_still_disabled_after_prompt), Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    /**
     * Show permission rationale dialog
     */
    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_permission_required))
            .setMessage(getString(R.string.permission_rationale_message))
            .setPositiveButton(getString(R.string.grant)) { _, _ ->
                requestLocationPermissions()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                handlePermissionDenied()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show permission denied dialog
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_denied))
            .setMessage(getString(R.string.permission_denied_message))
            .setPositiveButton(getString(R.string.retry)) { _, _ ->
                requestLocationPermissions()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                isLocationRequestInProgress = false
            }
            .show()
    }

    /**
     * Show permanently denied dialog
     */
    private fun showPermissionPermanentlyDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_permanently_denied_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                isLocationRequestInProgress = false
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show location service disabled dialog
     */
    private fun showLocationServiceDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_services_disabled))
            .setMessage(getString(R.string.enable_location_services_message))
            .setPositiveButton(getString(R.string.enable)) { _, _ ->
                openLocationSettings()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                isLocationRequestInProgress = false
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show location still disabled dialog
     */
    private fun showLocationDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_still_disabled))
            .setMessage(getString(R.string.location_still_disabled_message))
            .setPositiveButton(getString(R.string.try_again)) { _, _ ->
                openLocationSettings()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                isLocationRequestInProgress = false
            }
            .show()
    }

    /**
     * Open app settings
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        isLocationRequestInProgress = false
    }

    /**
     * Open location settings
     */
    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        locationSettingsLauncher.launch(intent)
    }

    /**
     * Set reference locations for validation
     */
    private fun setReferenceLocations() {
        // Example: Set a registered shop location
        val shopLocation = OptimisedLocationData(
            latitude = 28.6139, // New Delhi coordinates
            longitude = 77.2090,
            accuracy = 10f,
            timestamp = System.currentTimeMillis(),
            source = LocationSource.GPS
        )
        optimisedLocationManager.setRegisteredShopLocation(shopLocation)
        // Example: Set last known location if available
        // You can load this from SharedPreferences or database
        // optimisedLocationManager.setLastKnownLocation(lastKnownLocation)
    }

    @Deprecated("This method has been deprecated in favor of using the Activity Result API\n      which brings increased type safety via an {@link ActivityResultContract} and the prebuilt\n      contracts for common intents available in\n      {@link androidx.activity.result.contract.ActivityResultContracts}, provides hooks for\n      testing, and allow receiving results in separate, testable classes independent from your\n      activity. Use\n      {@link #registerForActivityResult(ActivityResultContract, ActivityResultCallback)}\n      with the appropriate {@link ActivityResultContract} and handling the result in the\n      {@link ActivityResultCallback#onActivityResult(Object) callback}.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_ENABLE_GPS) {
            if (resultCode == Activity.RESULT_OK) {
                startLocationProcess()
            } else {
                Log.e(TAG, getString(R.string.gps_still_disabled_after_prompt))
                Toast.makeText(this, getString(R.string.gps_still_disabled_after_prompt), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if permissions were granted while in settings
        if (isLocationRequestInProgress && hasAllLocationPermissions() && isLocationEnabled()) {
            proceedWithLocationAcquisition()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingLocationCallback = null
        isLocationRequestInProgress = false
    }
}