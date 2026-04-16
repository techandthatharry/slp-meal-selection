package com.techandthat.slpmealselection

import android.util.Log
import android.widget.Toast
import com.google.firebase.functions.FirebaseFunctionsException
import com.techandthat.slpmealselection.network.ArborPayloadMapper

// Extension functions managing Arbor student sync, paged batching, and auth handling.

// Starts Arbor sync flow and resets progress state before invoking the callable.
internal fun MainActivity.fetchStudentsFromArbor() {
    isLoadingMeals = true
    firebaseStatusMessage = null
    syncProgressCurrent = 0
    syncProgressTotal = 0
    renderKitchenView()
    ensureAuthenticatedThenSync(retryOnUnauthenticated = true)
}

// Verifies a fresh auth token exists prior to invoking the callable sync.
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.ensureAuthenticatedThenSync(retryOnUnauthenticated: Boolean) {
    authManager.ensureFreshAuth(
        onReady = {
            // Build class map first; student sync starts only after map is complete.
            runClassMapBuild(formOffset = 0, totalForms = null)
        },
        onFailure = { error ->
            val failureMessage = getString(
                R.string.firebase_auth_failed_with_reason,
                error.message ?: "unknown"
            )
            Log.e("ArborIntegration", "Auth failed before sync", error)
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
        }
    )
}

// Builds arborClassMap in batches; transitions to student sync when complete.
internal fun MainActivity.runClassMapBuild(formOffset: Int, totalForms: Int?) {
    val progressText = if (totalForms != null && totalForms > 0)
        "Building class map… $formOffset/$totalForms forms"
    else
        "Building class map…"
    firebaseStatusMessage = progressText
    renderKitchenView()

    arborSyncService.buildClassMap(
        schoolName = selectedSchool,
        formOffset = formOffset,
        onSuccess = { data ->
            val hasMore = data?.get("hasMore") as? Boolean ?: false
            val nextOffset = (data?.get("nextFormOffset") as? Number)?.toInt() ?: formOffset
            val total = (data?.get("totalForms") as? Number)?.toInt() ?: totalForms

            Log.d("ArborClassMap", "Class map batch done: formOffset=$formOffset next=$nextOffset total=$total hasMore=$hasMore")

            if (hasMore) {
                runClassMapBuild(formOffset = nextOffset, totalForms = total)
            } else {
                Log.d("ArborClassMap", "Class map fully built — starting student sync")
                firebaseStatusMessage = null
                runArborSyncCallable(
                    retryOnUnauthenticated = true,
                    offset = 0,
                    totalWritten = 0,
                    knownTotal = null
                )
            }
        },
        onFailure = { error ->
            // Class map is optional — log the failure and continue student sync.
            Log.w("ArborClassMap", "Class map build failed (students will have Unknown class)", error)
            firebaseStatusMessage = null
            runArborSyncCallable(
                retryOnUnauthenticated = true,
                offset = 0,
                totalWritten = 0,
                knownTotal = null
            )
        }
    )
}

// Calls getArborStudents in pages and persists each batch into Firestore.
internal fun MainActivity.runArborSyncCallable(
    retryOnUnauthenticated: Boolean,
    offset: Int,
    totalWritten: Int,
    knownTotal: Int?
) {
    arborSyncService.syncStudents(
        schoolName = selectedSchool,
        maxRecords = arborSyncBatchSize,
        offset = offset,
        onSuccess = { data ->
            Log.d("ArborIntegration", "Success: $data")

            val rateLimited = data?.get("rateLimited") as? Boolean ?: false
            val hasMore = data?.get("hasMore") as? Boolean ?: false
            val nextOffset = (data?.get("nextOffset") as? Number)?.toInt() ?: offset
            val totalAvailable = (data?.get("totalAvailable") as? Number)?.toInt()
                ?: knownTotal
                ?: 0
            val mapped = ArborPayloadMapper.mapStudentRecords(data)

            if (mapped.isEmpty()) {
                isLoadingMeals = false
                firebaseStatusMessage = if (rateLimited) {
                    "Arbor rate limited while syncing. Synced $totalWritten/$totalAvailable so far."
                } else {
                    null
                }
                loadChildRecordsFromFirestore()
                renderKitchenView()
                val finalMessage = if (rateLimited) {
                    "Arbor rate limited. Synced $totalWritten of $totalAvailable."
                } else {
                    "No more Arbor students returned."
                }
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show()
                return@syncStudents
            }

            repository.upsertArborRecords(
                records = mapped,
                onSuccess = {
                    val newTotalWritten = totalWritten + mapped.size
                    val progressTotal = if (totalAvailable > 0) totalAvailable else newTotalWritten

                    syncProgressCurrent = newTotalWritten
                    syncProgressTotal = progressTotal

                    if (hasMore) {
                        val statusText = if (rateLimited) {
                            "Arbor rate limited. Retrying from $nextOffset/$progressTotal..."
                        } else {
                            null
                        }
                        firebaseStatusMessage = statusText
                        renderKitchenView()
                        runArborSyncCallable(
                            retryOnUnauthenticated = retryOnUnauthenticated,
                            offset = nextOffset,
                            totalWritten = newTotalWritten,
                            knownTotal = totalAvailable
                        )
                        return@upsertArborRecords
                    }

                    loadChildRecordsFromFirestore()
                    isLoadingMeals = false
                    firebaseStatusMessage = null
                    renderKitchenView()
                    Toast.makeText(
                        this,
                        "Arbor sync complete: $newTotalWritten/$progressTotal students synced",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { error ->
                    Log.e("ArborIntegration", "Client-side Firestore upsert failed", error)
                    isLoadingMeals = false
                    firebaseStatusMessage = "Failed to save meals locally"
                    loadChildRecordsFromFirestore()
                    renderKitchenView()
                }
            )
        },
        onFailure = { exception ->
            val functionsException = exception as? FirebaseFunctionsException
            val isUnauthenticated =
                functionsException?.code == FirebaseFunctionsException.Code.UNAUTHENTICATED

            if (retryOnUnauthenticated && isUnauthenticated) {
                Log.w("ArborIntegration", "Callable returned UNAUTHENTICATED, retrying with fresh auth")
                authManager.signOut()
                ensureAuthenticatedThenSync(retryOnUnauthenticated = false)
                return@syncStudents
            }

            isLoadingMeals = false
            firebaseStatusMessage = "Failed to sync meals"
            renderKitchenView()
            Log.e("ArborIntegration", "Failed to fetch students", exception)
            Toast.makeText(this, "Failed to sync students from Arbor", Toast.LENGTH_LONG).show()
        }
    )
}
