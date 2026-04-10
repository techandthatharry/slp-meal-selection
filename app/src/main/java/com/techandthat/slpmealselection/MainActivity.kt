package com.techandthat.slpmealselection

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import androidx.activity.ComponentActivity
import androidx.core.widget.NestedScrollView
import com.techandthat.slpmealselection.databinding.ActivityMainBinding
import java.io.IOException

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val schools = listOf(
        "St Luke's Primary",
        "St Mary's Primary",
        "St Peter's Primary"
    )

    private enum class TabletType {
        KITCHEN,
        CHILD
    }

    private data class MealEntry(
        val name: String,
        val clazz: String,
        val meal: String,
        var served: Boolean = false
    )

    private val simulatedDatabase = mutableListOf(
        MealEntry("Liam Smith", "Reception", "Tomato Pasta"),
        MealEntry("Emma Jones", "Reception", "Fish & Chips"),
        MealEntry("Noah Williams", "Reception", "Tomato Pasta"),
        MealEntry("Olivia Brown", "Year 1", "Jacket Potato"),
        MealEntry("George Taylor", "Year 1", "Fish & Chips"),
        MealEntry("Mia Davies", "Year 2", "Jacket Potato")
    )

    private var selectedTabletType: TabletType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi()
        setupSchoolSpinners()
        loadBrandAssets()
        showSplashThenSetup()

        binding.loginButton.setOnClickListener {
            handleLogin()
        }

        binding.activeSchoolSpinner.onItemSelectedListener = SimpleItemSelectedListener { school ->
            binding.headerSubtitle.text = getString(R.string.school_selected, school)
            renderAppContent()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun setupSchoolSpinners() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, schools)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.initialSchoolSpinner.adapter = adapter
        binding.activeSchoolSpinner.adapter = adapter
    }

    private fun loadBrandAssets() {
        val slpBitmap = decodeAssetBitmap("SLP.jpg")
        if (slpBitmap != null) {
            binding.splashLogo.setImageBitmap(slpBitmap)
            binding.headerLogo.setImageBitmap(slpBitmap)
        }

        val techBitmap = decodeAssetBitmap("TechandThatLogoWhite.png")
        if (techBitmap != null) {
            binding.footerLogo.setImageBitmap(techBitmap)
        }
    }

    private fun decodeAssetBitmap(fileName: String) = try {
        assets.open(fileName).use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) {
        null
    }

    private fun showSplashThenSetup() {
        binding.splashContainer.visibility = View.VISIBLE
        binding.setupContainer.visibility = View.GONE
        binding.appContainer.visibility = View.GONE

        binding.root.postDelayed({
            binding.splashContainer.visibility = View.GONE
            binding.setupContainer.visibility = View.VISIBLE
        }, 1800)
    }

    private fun handleLogin() {
        val tabletType = resolveTabletType() ?: run {
            showSetupError(getString(R.string.select_tablet_type_error))
            return
        }

        val username = binding.usernameInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val password = binding.passwordInput.text?.toString()?.trim()?.lowercase().orEmpty()

        if (!isCredentialValid(tabletType, username, password)) {
            showSetupError(getString(R.string.invalid_credentials_error))
            return
        }

        selectedTabletType = tabletType
        binding.setupErrorText.visibility = View.GONE
        binding.activeSchoolSpinner.setSelection(binding.initialSchoolSpinner.selectedItemPosition)

        binding.setupContainer.visibility = View.GONE
        binding.appContainer.visibility = View.VISIBLE
        renderAppContent()
    }

    private fun resolveTabletType(): TabletType? {
        val checkedId = binding.tabletTypeGroup.checkedRadioButtonId
        if (checkedId == View.NO_ID) return null

        val selectedButton = findViewById<RadioButton>(checkedId)
        return when (selectedButton.id) {
            binding.kitchenTabletRadio.id -> TabletType.KITCHEN
            binding.childTabletRadio.id -> TabletType.CHILD
            else -> null
        }
    }

    private fun isCredentialValid(tabletType: TabletType, username: String, password: String): Boolean {
        return when (tabletType) {
            TabletType.KITCHEN -> username == "kitchen" && password == "kitchen"
            TabletType.CHILD -> username == "child" && password == "child"
        }
    }

    private fun showSetupError(message: String) {
        binding.setupErrorText.text = message
        binding.setupErrorText.visibility = View.VISIBLE
    }

    private fun renderAppContent() {
        val school = binding.activeSchoolSpinner.selectedItem?.toString().orEmpty()
        when (selectedTabletType) {
            TabletType.KITCHEN -> renderKitchenView(school)
            TabletType.CHILD -> renderChildView(school)
            null -> Unit
        }
    }

    private fun renderKitchenView(school: String) {
        binding.headerTitle.text = getString(R.string.kitchen_side)
        binding.contentTitle.text = getString(R.string.kitchen_dashboard_title)
        binding.contentSubtitle.text = getString(R.string.kitchen_dashboard_subtitle, school)

        binding.kitchenContent.visibility = View.VISIBLE
        binding.childContent.visibility = View.GONE

        val outstandingMeals = simulatedDatabase.filterNot { it.served }
        val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()

        val summary = if (volumes.isEmpty()) {
            getString(R.string.all_meals_served)
        } else {
            volumes.entries.joinToString(separator = "\n") { (meal, count) ->
                "• $meal: $count"
            }
        }

        binding.prepSummary.text = summary
        (binding.appContainer.getChildAt(2) as? NestedScrollView)?.scrollTo(0, 0)
    }

    private fun renderChildView(school: String) {
        binding.headerTitle.text = getString(R.string.child_facing)
        binding.contentTitle.text = getString(R.string.child_dashboard_title)
        binding.contentSubtitle.text = getString(R.string.child_dashboard_subtitle, school)

        binding.kitchenContent.visibility = View.GONE
        binding.childContent.visibility = View.VISIBLE

        val classes = simulatedDatabase.filterNot { it.served }.map { it.clazz }.distinct().sorted()
        binding.checkInSummary.text = if (classes.isEmpty()) {
            getString(R.string.all_meals_served)
        } else {
            getString(R.string.available_classes, classes.joinToString())
        }

        (binding.appContainer.getChildAt(2) as? NestedScrollView)?.scrollTo(0, 0)
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: (String) -> Unit
) : AdapterView.OnItemSelectedListener {

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val value = parent?.getItemAtPosition(position)?.toString() ?: return
        onSelected(value)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
