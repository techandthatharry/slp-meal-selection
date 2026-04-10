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

fun hideSystemUi(window: Window) {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}

fun setupSchoolSpinner(
    context: Context,
    binding: ActivityMainBinding,
    schools: List<String>
) {
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, schools)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    binding.initialSchoolSpinner.adapter = adapter
}

fun decodeAssetBitmap(assetManager: AssetManager, fileName: String) = try {
    assetManager.open(fileName).use { BitmapFactory.decodeStream(it) }
} catch (_: IOException) {
    null
}

fun showSplashThenSetup(binding: ActivityMainBinding) {
    binding.splashContainer.visibility = View.VISIBLE
    binding.setupContainer.visibility = View.GONE
    binding.appContainer.visibility = View.GONE

    binding.root.postDelayed({
        binding.splashContainer.visibility = View.GONE
        binding.setupContainer.visibility = View.VISIBLE
    }, 1800)
}

fun setupKeyboardSafeLoginScroll(binding: ActivityMainBinding) {
    val rootView = binding.root
    val setupContainer = binding.setupContainer

    rootView.viewTreeObserver.addOnGlobalLayoutListener {
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val keyboardHeight = rootView.height - rect.bottom
        val keyboardVisible = keyboardHeight > rootView.height * 0.15

        if (setupContainer.visibility != View.VISIBLE) return@addOnGlobalLayoutListener

        val extraBottomPadding = if (keyboardVisible) keyboardHeight + 24 else 24
        binding.setupFormContainer.setPadding(
            binding.setupFormContainer.paddingLeft,
            binding.setupFormContainer.paddingTop,
            binding.setupFormContainer.paddingRight,
            extraBottomPadding
        )

        if (!keyboardVisible) return@addOnGlobalLayoutListener

        setupContainer.post {
            setupContainer.smoothScrollTo(0, binding.loginButton.bottom)
        }
    }
}
