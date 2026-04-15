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

// Provides shared setup and shell UI helpers used by MainActivity.

// Hides status/navigation bars for kiosk-like full-screen tablet experience.
fun hideSystemUi(window: Window) {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}

// Populates the school spinner from the configured school list.
fun setupSchoolSpinner(
    context: Context,
    binding: ActivityMainBinding,
    schools: List<String>
) {
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, schools)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    binding.initialSchoolSpinner.adapter = adapter
}

// Decodes a bitmap from assets and returns null if the image cannot be loaded.
fun decodeAssetBitmap(assetManager: AssetManager, fileName: String) = try {
    assetManager.open(fileName).use { BitmapFactory.decodeStream(it) }
} catch (_: IOException) {
    null
}

// Shows splash briefly and then transitions to setup screen.
fun showSplashThenSetup(binding: ActivityMainBinding) {
    binding.splashContainer.visibility = View.VISIBLE
    binding.setupContainer.visibility = View.GONE
    binding.appContainer.visibility = View.GONE

    // Delay transition so branding splash is visible to the user.
    binding.root.postDelayed({
        binding.splashContainer.visibility = View.GONE
        binding.setupContainer.visibility = View.VISIBLE
    }, 1800)
}

// Adjusts setup form padding/scroll so login controls stay visible above keyboard.
fun setupKeyboardSafeLoginScroll(binding: ActivityMainBinding) {
    val rootView = binding.root
    val setupContainer = binding.setupContainer

    rootView.viewTreeObserver.addOnGlobalLayoutListener {
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val keyboardHeight = rootView.height - rect.bottom
        val keyboardVisible = keyboardHeight > rootView.height * 0.15

        // Ignore updates while setup screen is not visible.
        if (setupContainer.visibility != View.VISIBLE) return@addOnGlobalLayoutListener

        val extraBottomPadding = if (keyboardVisible) keyboardHeight + 24 else 24
        binding.setupFormContainer.setPadding(
            binding.setupFormContainer.paddingLeft,
            binding.setupFormContainer.paddingTop,
            binding.setupFormContainer.paddingRight,
            extraBottomPadding
        )

        // No auto-scroll needed once keyboard is hidden.
        if (!keyboardVisible) return@addOnGlobalLayoutListener

        // Scroll login button into view once keyboard appears.
        setupContainer.post {
            setupContainer.smoothScrollTo(0, binding.loginButton.bottom)
        }
    }
}
