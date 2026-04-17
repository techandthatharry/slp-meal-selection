package com.techandthat.slpmealselection

import android.util.Log
import android.widget.Toast
import com.google.firebase.functions.FirebaseFunctionsException
import com.techandthat.slpmealselection.model.ChildScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Extension functions for MainActivity that manage the end-of-service workflow.
 * This includes uploading the billing queue to Arbor via Firebase Functions,
 * clearing the local and remote child records, and generating summary statistics.
 */

// Begins the process of ending the day's service and uploading records to Arbor.
internal fun MainActivity.endServiceAndDeleteRecords() {
    isLoadingMeals = true
    firebaseStatusMessage = null
    binding.prepLoadingText.text = getString(R.string.uploading_to_arbor)
    
    // Refresh the UI to show the loading state.
    renderKitchenView()
    
    // Initiate the authenticated upload process.
    ensureAuthenticatedThenUploadBillingQueue(retryOnUnauthenticated = true)
}

// Ensures a valid Firebase Auth session is active before calling the billing upload function.
internal fun MainActivity.ensureAuthenticatedThenUploadBillingQueue(retryOnUnauthenticated: Boolean) {
    authManager.ensureFreshAuth(
        onReady = {
            // Auth is valid, proceed to call the cloud function.
            runBillingUploadCallable(retryOnUnauthenticated)
        },
        onFailure = { error ->
            // Auth failed; stop loading and display the error message.
            isLoadingMeals = false
            val failureMessage = getString(
                R.string.firebase_auth_failed_with_reason,
                error.message ?: "unknown"
            )
            firebaseStatusMessage = failureMessage
            Log.e("ArborBillingUpload", "Auth failed before billing upload", error)
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
            renderKitchenView()
        }
    )
}

// Executes the HttpsCallable to sync local meal selection data to the Arbor MIS.
internal fun MainActivity.runBillingUploadCallable(retryOnUnauthenticated: Boolean) {
    arborSyncService.uploadBillingQueue(
        schoolName = selectedSchool,
        onSuccess = { data ->
            // Parse response data from the Firebase Cloud Function.
            val success = data?.get("success") as? Boolean ?: false
            val uploaded = (data?.get("uploaded") as? Number)?.toInt() ?: 0
            val deleted = (data?.get("deleted") as? Number)?.toInt() ?: 0
            val failed = (data?.get("failed") as? Number)?.toInt() ?: 0
            val message = data?.get("message") as? String
            val backendError = data?.get("errorMessage") as? String
            val firstFailureReason = data?.get("firstFailureReason")?.toString()
            val firstFailureStatusCode = (data?.get("firstFailureStatusCode") as? Number)?.toInt()

            Log.i(
                "ArborBillingUpload",
                "Upload result success=$success uploaded=$uploaded deleted=$deleted failed=$failed message=$message"
            )

            // Handle partial or total failures from the Arbor API side.
            if (!success) {
                Log.e("ArborBillingUpload", "Upload failed but force ending service for UI feedback. Error: $message")
                repository.logErrorToFirebase(
                    "ArborBillingUpload", 
                    Exception("Upload result success=false. message=$message backendError=$backendError firstFailureReason=$firstFailureReason"), 
                    selectedSchool
                )
                
                val arborFailureText = firstFailureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    val codeSuffix = firstFailureStatusCode?.let { " (HTTP $it)" }.orEmpty()
                    "Arbor rejected billing payload: $reason$codeSuffix"
                }
                val detailText = arborFailureText
                    ?: backendError?.takeIf { it.isNotBlank() }
                    ?: message
                    ?: "Failed to upload all billing rows to Arbor sandbox"
                
                firebaseStatusMessage = "Arbor upload incomplete. Uploaded $uploaded, failed $failed. $detailText"
                Toast.makeText(this, "Arbor Sync Failed: $detailText. Ending service anyway.", Toast.LENGTH_LONG).show()
            } else {
                firebaseStatusMessage = "Arbor billing uploaded ($uploaded) and queue cleaned ($deleted). Ending service..."
            }
            
            renderKitchenView()

            // Calculate final statistics for the session summary dashboard.
            val mealsServedCount = simulatedDatabase.count { it.served }
            val mealVolumesMap = simulatedDatabase.filter { it.served }.groupingBy { it.meal }.eachCount()
            val loadedTime = mealsLoadedTime ?: System.currentTimeMillis()
            val startTime = serviceStartedTime ?: System.currentTimeMillis()
            val prepMins = ((startTime - loadedTime) / 60000).toInt().coerceAtLeast(0)
            val loadedTimeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(loadedTime))

            val statsAtEnd = MainActivity.ServiceStats(
                mealsServed = mealsServedCount,
                studentsLoaded = simulatedDatabase.size,
                arborUploaded = uploaded,
                arborFailed = failed,
                endedAtLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                mealsLoadedTimeLabel = loadedTimeLabel,
                prepDurationMinutes = prepMins,
                mealVolumes = mealVolumesMap,
                weekTotal = 156, // Simulated realistic week total
                monthTotal = 684 // Simulated realistic month total
            )

            // Clear the Firestore collection for the school after a successful (or soft-failed) sync.
            repository.deleteAllRecords(
                schoolName = selectedSchool,
                onSuccess = {
                    latestServiceStats = statsAtEnd
                    simulatedDatabase.clear()
                    activeOrder = null
                    selectedClass = null
                    showWaitingOverlayAfterConfirm = false
                    serviceStarted = false
                    servicePausedByKitchen = false
                    showingServiceStats = true
                    mealTimeStarted = false
                    childScreen = ChildScreen.IDLE
                    isLoadingMeals = false
                    firebaseStatusMessage = null
                    
                    // Switch UI to the summary statistics dashboard.
                    renderAppContent()
                    Toast.makeText(
                        this,
                        "Service ended. Arbor upload: $uploaded, queue deleted: $deleted",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { error ->
                    isLoadingMeals = false
                    val failureMessage = getString(
                        R.string.firebase_delete_failed_with_reason,
                        error.message ?: "unknown"
                    )
                    firebaseStatusMessage =
                        "Arbor upload succeeded but childRecords delete failed: $failureMessage"
                    Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                    renderKitchenView()
                }
            )
        },
        onFailure = { error ->
            // Handle network or cloud function infrastructure errors.
            val functionsException = error as? FirebaseFunctionsException
            val isUnauthenticated =
                functionsException?.code == FirebaseFunctionsException.Code.UNAUTHENTICATED

            // Retry once if the token expired during the request.
            if (retryOnUnauthenticated && isUnauthenticated) {
                Log.w(
                    "ArborBillingUpload",
                    "Billing upload callable returned UNAUTHENTICATED, retrying with fresh auth"
                )
                authManager.signOut()
                ensureAuthenticatedThenUploadBillingQueue(retryOnUnauthenticated = false)
                return@uploadBillingQueue
            }

            isLoadingMeals = false
            val codeText = functionsException?.code?.name ?: "UNKNOWN"
            repository.logErrorToFirebase("ArborBillingUpload", error, selectedSchool)
            firebaseStatusMessage = "Failed to upload billing queue to Arbor sandbox ($codeText)"
            Log.e("ArborBillingUpload", "Callable failed code=$codeText", error)
            Toast.makeText(
                this,
                functionsException?.details?.toString()
                    ?: functionsException?.message
                    ?: error.message
                    ?: "Failed to upload billing queue",
                Toast.LENGTH_LONG
            ).show()
            renderKitchenView()
        }
    )
}
