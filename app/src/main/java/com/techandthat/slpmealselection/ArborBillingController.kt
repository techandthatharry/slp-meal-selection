package com.techandthat.slpmealselection

import android.util.Log
import android.widget.Toast
import com.google.firebase.functions.FirebaseFunctionsException
import com.techandthat.slpmealselection.model.ChildScreen

// Extension functions managing Arbor billing queue upload and end-of-service cleanup.

// Begins end-service flow: shows uploading state then triggers billing upload callable.
internal fun MainActivity.endServiceAndDeleteRecords() {
    isLoadingMeals = true
    firebaseStatusMessage = null
    binding.prepLoadingText.text = getString(R.string.uploading_to_arbor)
    renderKitchenView()
    ensureAuthenticatedThenUploadBillingQueue(retryOnUnauthenticated = true)
}

// Verifies auth token exists before invoking billing queue upload callable.
internal fun MainActivity.ensureAuthenticatedThenUploadBillingQueue(retryOnUnauthenticated: Boolean) {
    authManager.ensureFreshAuth(
        onReady = {
            runBillingUploadCallable(retryOnUnauthenticated)
        },
        onFailure = { error ->
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

// Calls uploadArborBillingQueue and only ends service when upload succeeds fully.
internal fun MainActivity.runBillingUploadCallable(retryOnUnauthenticated: Boolean) {
    arborSyncService.uploadBillingQueue(
        schoolName = selectedSchool,
        onSuccess = { data ->
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

            if (!success) {
                isLoadingMeals = false
                val arborFailureText = firstFailureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    val codeSuffix = firstFailureStatusCode?.let { " (HTTP $it)" }.orEmpty()
                    "Arbor rejected billing payload: $reason$codeSuffix"
                }
                val detailText = arborFailureText
                    ?: backendError?.takeIf { it.isNotBlank() }
                    ?: message
                    ?: "Failed to upload all billing rows to Arbor sandbox"
                firebaseStatusMessage = "Arbor upload incomplete. Uploaded $uploaded, failed $failed. $detailText"
                renderKitchenView()
                Toast.makeText(this, detailText, Toast.LENGTH_LONG).show()
                return@uploadBillingQueue
            }

            firebaseStatusMessage = "Arbor billing uploaded ($uploaded) and queue cleaned ($deleted). Ending service..."
            renderKitchenView()

            repository.deleteAllRecords(
                schoolName = selectedSchool,
                onSuccess = {
                    simulatedDatabase.clear()
                    activeOrder = null
                    selectedClass = null
                    showWaitingOverlayAfterConfirm = false
                    serviceStarted = false
                    servicePausedByKitchen = false
                    mealTimeStarted = false
                    childScreen = ChildScreen.IDLE
                    isLoadingMeals = false
                    firebaseStatusMessage = null
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
            val functionsException = error as? FirebaseFunctionsException
            val isUnauthenticated =
                functionsException?.code == FirebaseFunctionsException.Code.UNAUTHENTICATED

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
