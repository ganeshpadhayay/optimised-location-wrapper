package com.ganesh.optimisedlocationapplication.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ganesh.optimisedlocationapplication.R

/**
 * Key Components:
 *
 * Dialog Layout: Programmatically created with proper spacing and styling
 * Header Section: Icon, title, and subtitle
 * Steps Section: Numbered steps with titles and descriptions
 * Tip Section: Helpful advice about calibration
 * Action Buttons: Cancel (dismisses) and Open Maps (launches Google Maps)
 * Smart App Detection: Checks if Google Maps is installed before trying to open it
 *
 * The dialog is responsive, handles different screen sizes, and provides proper error handling for cases where Google Maps or browsers aren't available.
 *
 */
object OptimisedLocationUtils {
    // Constants extracted from all files
    const val ERROR_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val ERROR_VALIDATION_FAILED = "VALIDATION_FAILED"
    const val ERROR_PROXIMITY_FAILED = "PROXIMITY_FAILED"
    const val ERROR_NO_VALID_LOCATION = "NO_VALID_LOCATION"
    const val USER_ACTION_CALIBRATE_DEVICE = "CALIBRATE_DEVICE"
    const val USER_ACTION_REQUEST_PERMISSIONS = "USER_ACTION_REQUEST_PERMISSIONS"
    const val ERROR_GPS_DISABLED = "ERROR_GPS_DISABLED"
    const val USER_ACTION_ENABLE_GPS = "USER_ACTION_ENABLE_GPS"
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    const val GOOGLE_MAPS_URL = "https://maps.google.com"
    const val GOOGLE_NAVIGATION_URI = "google.navigation:q="
    const val EARTH_RADIUS_KM = 6371.0

    // Utility to get string from resources
    fun getString(context: Context, resId: Int): String = context.getString(resId)

    /**
     * Shows a custom dialog to guide users through calibrating the Google Maps compass.
     *
     * @param context The context in which the dialog should be displayed.
     */
    fun showCalibrationDialog(context: Context) {
        // Create custom dialog layout programmatically
        val dialogView = createDialogView(context)
        // Create AlertDialog
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        // Set up button click listeners
        val cancelButton = dialogView.findViewById<Button>(R.id.btn_cancel)
        val openMapsButton = dialogView.findViewById<Button>(R.id.btn_open_maps)

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        openMapsButton.setOnClickListener {
            openGoogleMaps(context)
            dialog.dismiss()
        }
        // Show dialog
        dialog.show()
        // Optional: Set dialog window properties for better appearance
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val layoutParams = window.attributes
            layoutParams.width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            window.attributes = layoutParams
        }
    }

    private fun createDialogView(context: Context): View {
        // Create root layout
        val rootLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 24))
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        // Header section
        val headerLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        val iconText = TextView(context).apply {
            text = getString(context, R.string.calibrate_compass_icon)
            textSize = 32f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(context, 12))
        }
        val titleText = TextView(context).apply {
            text = getString(context, R.string.calibrate_compass_title)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            gravity = android.view.Gravity.CENTER
        }
        val subtitleText = TextView(context).apply {
            text = getString(context, R.string.calibrate_compass_subtitle)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 20))
        }

        headerLayout.addView(iconText)
        headerLayout.addView(titleText)
        headerLayout.addView(subtitleText)
        // Steps section
        val stepsLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val steps = listOf(
            Pair(getString(context, R.string.step_open_maps_title), getString(context, R.string.step_open_maps_desc)),
            Pair(getString(context, R.string.step_tap_blue_dot_title), getString(context, R.string.step_tap_blue_dot_desc)),
            Pair(getString(context, R.string.step_calibrate_compass_title), getString(context, R.string.step_calibrate_compass_desc)),
            Pair(getString(context, R.string.step_figure8_title), getString(context, R.string.step_figure8_desc)),
            Pair(getString(context, R.string.step_verify_accuracy_title), getString(context, R.string.step_verify_accuracy_desc))
        )

        steps.forEachIndexed { index, step ->
            val stepLayout = createStepView(context, index + 1, step.first, step.second)
            stepsLayout.addView(stepLayout)
        }
        // Tip section
        val tipLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.background_light))
            setPadding(dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12))
            val layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, dpToPx(context, 16), 0, dpToPx(context, 24))
            this.layoutParams = layoutParams
        }
        val tipText = TextView(context).apply {
            text = getString(context, R.string.calibrate_compass_tip)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
        }

        tipLayout.addView(tipText)
        // Buttons section
        val buttonLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val cancelButton = Button(context).apply {
            id = R.id.btn_cancel
            text = getString(context, R.string.cancel)
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.background_light))
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            val layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, 0, dpToPx(context, 12), 0)
            this.layoutParams = layoutParams
        }
        val openMapsButton = Button(context).apply {
            id = R.id.btn_open_maps
            text = getString(context, R.string.open_google_maps)
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_bright))
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
        }

        buttonLayout.addView(cancelButton)
        buttonLayout.addView(openMapsButton)
        // Add all sections to root layout
        rootLayout.addView(headerLayout)
        rootLayout.addView(stepsLayout)
        rootLayout.addView(tipLayout)
        rootLayout.addView(buttonLayout)

        return rootLayout
    }

    private fun createStepView(context: Context, stepNumber: Int, title: String, description: String): View {
        val stepLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, 0, 0, dpToPx(context, 16))
            this.layoutParams = layoutParams
        }
        // Step number circle
        val stepNumberView = TextView(context).apply {
            text = stepNumber.toString()
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setBackgroundResource(android.R.drawable.btn_default)
            backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_blue_bright)
            gravity = android.view.Gravity.CENTER
            val size = dpToPx(context, 24)
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, dpToPx(context, 2), dpToPx(context, 12), 0)
            }
        }
        // Step content
        val contentLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            setPadding(0, 0, 0, dpToPx(context, 4))
        }
        val descriptionView = TextView(context).apply {
            text = description
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        contentLayout.addView(titleView)
        contentLayout.addView(descriptionView)

        stepLayout.addView(stepNumberView)
        stepLayout.addView(contentLayout)

        return stepLayout
    }

    private fun openGoogleMaps(context: Context) {
        try {
            val packageManager = context.packageManager
            try {
                packageManager.getPackageInfo(GOOGLE_MAPS_PACKAGE, PackageManager.GET_ACTIVITIES)
                val intent = Intent().apply {
                    setPackage(GOOGLE_MAPS_PACKAGE)
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(GOOGLE_NAVIGATION_URI)
                }
                context.startActivity(intent)
            } catch (e: PackageManager.NameNotFoundException) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_MAPS_URL))
                context.startActivity(browserIntent)
            }
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_MAPS_URL))
                context.startActivity(browserIntent)
            } catch (browserException: Exception) {
                android.widget.Toast.makeText(
                    context,
                    getString(context, R.string.unable_to_open_maps),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}