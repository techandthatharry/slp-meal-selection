package com.techandthat.slpmealselection

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
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

    private enum class ChildScreen {
        IDLE,
        CLASS_SELECTION,
        NAME_SELECTION,
        SUCCESS
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
    private var serviceStarted = false
    private var mealTimeStarted = false
    private var selectedClass: String? = null
    private var childScreen: ChildScreen = ChildScreen.IDLE
    private var activeOrder: MealEntry? = null
    private var showWaitingOverlayAfterConfirm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi()
        setupSchoolSpinners()
        loadBrandAssets()
        showSplashThenSetup()

        binding.loginButton.setOnClickListener { handleLogin() }
        binding.startServiceButton.setOnClickListener {
            serviceStarted = true
            renderAppContent()
        }
        binding.startMealTimeButton.setOnClickListener {
            mealTimeStarted = true
            selectedClass = null
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }
        binding.backToClassesButton.setOnClickListener {
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }
        binding.oopsButton.setOnClickListener {
            childScreen = ChildScreen.NAME_SELECTION
            renderChildView()
        }
        binding.checkInSuccessButton.setOnClickListener {
            showWaitingOverlayAfterConfirm = true
            childScreen = ChildScreen.CLASS_SELECTION
            renderChildView()
        }
        binding.mealServedButton.setOnClickListener {
            activeOrder?.served = true
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            selectedClass = null
            childScreen = ChildScreen.CLASS_SELECTION
            renderAppContent()
        }
        binding.backToSetupButton.setOnClickListener { returnToSetup() }

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

    private fun isCredentialValid(tabletType: TabletType, username: String, password: String): Boolean = when (tabletType) {
        TabletType.KITCHEN -> username == "kitchen" && password == "kitchen"
        TabletType.CHILD -> username == "child" && password == "child"
    }

    private fun showSetupError(message: String) {
        binding.setupErrorText.text = message
        binding.setupErrorText.visibility = View.VISIBLE
    }

    private fun returnToSetup() {
        binding.appContainer.visibility = View.GONE
        binding.setupContainer.visibility = View.VISIBLE

        selectedTabletType = null
        selectedClass = null
        activeOrder = null
        showWaitingOverlayAfterConfirm = false
        mealTimeStarted = false
        childScreen = ChildScreen.IDLE
        binding.tabletTypeGroup.clearCheck()
        binding.usernameInput.text?.clear()
        binding.passwordInput.text?.clear()
        binding.setupErrorText.visibility = View.GONE
    }

    private fun renderAppContent() {
        when (selectedTabletType) {
            TabletType.KITCHEN -> renderKitchenView()
            TabletType.CHILD -> renderChildView()
            null -> Unit
        }
    }

    private fun renderKitchenView() {
        val school = binding.activeSchoolSpinner.selectedItem?.toString().orEmpty()
        binding.schoolSelectorRow.visibility = View.VISIBLE
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, R.color.slp_blue))
        binding.headerTitle.text = getString(R.string.kitchen_side)
        binding.contentTitle.text = getString(R.string.kitchen_dashboard_title)
        binding.contentSubtitle.text = getString(R.string.kitchen_dashboard_subtitle, school)

        binding.kitchenContent.visibility = View.VISIBLE
        binding.childContent.visibility = View.GONE

        binding.serviceStatusText.text = if (serviceStarted) {
            getString(R.string.service_status_started)
        } else {
            getString(R.string.service_status_not_started)
        }
        binding.startServiceButton.isEnabled = !serviceStarted

        if (serviceStarted) {
            binding.kitchenContent.removeView(binding.prepListContainer)
            binding.kitchenContent.addView(binding.prepListContainer)
        } else {
            binding.kitchenContent.removeView(binding.prepListContainer)
            binding.kitchenContent.addView(binding.prepListContainer, 0)
        }

        val outstandingMeals = simulatedDatabase.filterNot { it.served }
        val volumes = outstandingMeals.groupingBy { it.meal }.eachCount()
        binding.prepSummary.text = if (volumes.isEmpty()) {
            getString(R.string.all_meals_served)
        } else {
            volumes.entries.joinToString(separator = "\n") { (meal, count) -> "• $meal: $count" }
        }

        if (serviceStarted && activeOrder != null) {
            binding.kitchenOrderContainer.visibility = View.VISIBLE
            binding.kitchenOrderChildName.text = activeOrder?.name
            binding.kitchenOrderMealName.text = activeOrder?.meal
            binding.mealServedButton.isEnabled = true
        } else {
            binding.kitchenOrderContainer.visibility = View.GONE
        }

        (binding.appContainer.getChildAt(2) as? NestedScrollView)?.scrollTo(0, 0)
    }

    private fun renderChildView() {
        binding.schoolSelectorRow.visibility = View.GONE

        val headerColor = if (activeOrder != null) R.color.child_orange else R.color.child_green
        binding.headerBar.setBackgroundColor(ContextCompat.getColor(this, headerColor))
        binding.headerTitle.text = getString(R.string.child_facing)
        binding.headerSubtitle.text = getString(R.string.child_tablet_mode)
        binding.contentTitle.text = getString(R.string.child_dashboard_title)
        binding.contentSubtitle.text = getString(R.string.child_dashboard_subtitle_simple)

        binding.kitchenContent.visibility = View.GONE
        binding.childContent.visibility = View.VISIBLE
        binding.contentTitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.contentSubtitle.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

        if (!serviceStarted) {
            mealTimeStarted = false
            selectedClass = null
            activeOrder = null
            showWaitingOverlayAfterConfirm = false
            childScreen = ChildScreen.IDLE
            binding.childServiceGateText.text = getString(R.string.child_waiting_for_service)
            binding.startMealTimeButton.visibility = View.GONE
            binding.childStepContainer.visibility = View.GONE
            binding.waitingOverlay.visibility = View.GONE
            return
        }

        binding.childServiceGateText.text = getString(R.string.child_service_live)
        binding.childServiceGateText.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE
        binding.startMealTimeButton.visibility = if (mealTimeStarted) View.GONE else View.VISIBLE

        if (!mealTimeStarted) {
            childScreen = ChildScreen.IDLE
            binding.contentTitle.visibility = View.VISIBLE
            binding.contentSubtitle.visibility = View.VISIBLE
            binding.childStepContainer.visibility = View.GONE
            binding.waitingOverlay.visibility = View.GONE
            return
        }

        binding.childStepContainer.visibility = View.VISIBLE

        when (childScreen) {
            ChildScreen.IDLE -> {
                childScreen = ChildScreen.CLASS_SELECTION
                showClassSelectionScreen()
            }
            ChildScreen.CLASS_SELECTION -> showClassSelectionScreen()
            ChildScreen.NAME_SELECTION -> showNameSelectionScreen()
            ChildScreen.SUCCESS -> showSuccessScreen()
        }

        val shouldShowOverlay = showWaitingOverlayAfterConfirm && activeOrder != null && childScreen == ChildScreen.CLASS_SELECTION
        binding.waitingOverlay.visibility = if (shouldShowOverlay) View.VISIBLE else View.GONE
    }

    private fun showClassSelectionScreen() {
        binding.classSelectionContainer.visibility = View.VISIBLE
        binding.nameSelectionContainer.visibility = View.GONE
        binding.checkInSuccessContainer.visibility = View.GONE

        val classes = simulatedDatabase.filterNot { it.served }.map { it.clazz }.distinct().sorted()
        binding.classButtonsContainer.removeAllViews()

        val classButtonHeight = (binding.childStepContainer.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels) / 4

        classes.forEach { className ->
            val button = MaterialButton(this).apply {
                text = className
                textSize = 34f
                isAllCaps = false
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    classButtonHeight
                ).apply {
                    topMargin = 12
                }
                setOnClickListener {
                    if (!showWaitingOverlayAfterConfirm) {
                        selectedClass = className
                        childScreen = ChildScreen.NAME_SELECTION
                        showNameSelectionScreen()
                    }
                }
            }
            binding.classButtonsContainer.addView(button)
        }
    }

    private fun showNameSelectionScreen() {
        binding.classSelectionContainer.visibility = View.GONE
        binding.nameSelectionContainer.visibility = View.VISIBLE
        binding.checkInSuccessContainer.visibility = View.GONE
        binding.backToClassesButton.visibility = View.GONE

        val chosenClass = selectedClass
        binding.nameButtonsContainer.removeAllViews()

        if (chosenClass.isNullOrBlank()) {
            childScreen = ChildScreen.CLASS_SELECTION
            showClassSelectionScreen()
            return
        }

        val children = simulatedDatabase.filter { !it.served && it.clazz == chosenClass }
        val childButtonHeight = (binding.childStepContainer.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels) / 5

        children.forEach { child ->
            val button = MaterialButton(this).apply {
                text = child.name
                textSize = 34f
                isAllCaps = false
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    childButtonHeight
                ).apply {
                    topMargin = 12
                }
                setOnClickListener {
                    if (activeOrder == null && !showWaitingOverlayAfterConfirm) {
                        activeOrder = child
                        childScreen = ChildScreen.SUCCESS
                        renderAppContent()
                    }
                }
            }
            binding.nameButtonsContainer.addView(button)
        }
    }

    private fun showSuccessScreen() {
        binding.classSelectionContainer.visibility = View.GONE
        binding.nameSelectionContainer.visibility = View.GONE
        binding.checkInSuccessContainer.visibility = View.VISIBLE
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
