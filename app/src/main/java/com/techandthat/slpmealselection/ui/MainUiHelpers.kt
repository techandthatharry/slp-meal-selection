package com.techandthat.slpmealselection.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.View
import android.view.Window
import android.widget.ArrayAdapter
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import java.io.IOException

/**
 * Shared UI utility functions for the MainActivity.
 * Handles system UI configuration, asset loading, splash screen transitions,
 * and keyboard-aware layout adjustments.
 */

// configures the activity window for an immersive, kiosk-like full-screen experience.
fun hideSystemUi(window: Window) {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}

// Configures the school selection spinner with the provided list of names.
fun setupSchoolSpinner(
    context: Context,
    binding: ActivityMainBinding,
    schools: List<String>
) {
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, schools)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    binding.initialSchoolSpinner.adapter = adapter
}

// Loads and decodes a bitmap image from the application's assets folder.
fun decodeAssetBitmap(assetManager: AssetManager, fileName: String) = try {
    assetManager.open(fileName).use { BitmapFactory.decodeStream(it) }
} catch (_: IOException) {
    // Return null if the asset is missing or corrupted.
    null
}

// Manages the transition from the branding splash screen to the initial setup screen.
fun showSplashThenSetup(binding: ActivityMainBinding) {
    binding.splashContainer.visibility = View.VISIBLE
    binding.setupContainer.visibility = View.GONE
    binding.appContainer.visibility = View.GONE

    // Display the splash screen for 1.8 seconds to establish school branding.
    binding.root.postDelayed({
        binding.splashContainer.visibility = View.GONE
        binding.setupContainer.visibility = View.VISIBLE
    }, 1800)
}

// Adjusts the setup screen's padding and scroll position to keep the login button visible when the soft keyboard is open.
fun setupKeyboardSafeLoginScroll(binding: ActivityMainBinding) {
    val rootView = binding.root
    val setupContainer = binding.setupContainer

    rootView.viewTreeObserver.addOnGlobalLayoutListener {
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val keyboardHeight = rootView.height - rect.bottom
        val keyboardVisible = keyboardHeight > rootView.height * 0.15

        // Only process layout changes while the setup/login screen is active.
        if (setupContainer.visibility != View.VISIBLE) return@addOnGlobalLayoutListener

        // Increase bottom padding to push the form above the keyboard.
        val extraBottomPadding = if (keyboardVisible) keyboardHeight + 24 else 24
        binding.setupFormContainer.setPadding(
            binding.setupFormContainer.paddingLeft,
            binding.setupFormContainer.paddingTop,
            binding.setupFormContainer.paddingRight,
            extraBottomPadding
        )

        // Ensure the "Login" button is smoothly scrolled into view.
        if (!keyboardVisible) return@addOnGlobalLayoutListener

        setupContainer.post {
            setupContainer.smoothScrollTo(0, binding.loginButton.bottom)
        }
    }
}
