package com.techandthat.slpmealselection

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.firebase.functions.FirebaseFunctionsException
import com.techandthat.slpmealselection.network.ArborPayloadMapper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Extension functions managing Arbor student sync, paged batching, and auth handling.

// Loads student records from Firebase cache if they exist, otherwise syncs from Arbor.
// forceSync=true skips the cache check and always re-syncs from Arbor.
internal fun MainActivity.fetchStudentsFromArbor(forceSync: Boolean = false) {
    isLoadingMeals = true
    firebaseStatusMessage = null
    syncProgressCurrent = 0
    syncProgressTotal = 0
    renderKitchenView()

    if (forceSync) {
        // User explicitly requested a fresh pull from Arbor.
        ensureAuthenticatedThenSync(retryOnUnauthenticated = true)
        return
    }

    // Check Firebase cache first — instant load if data already exists.
    repository.hasRecordsForSchool(
        schoolName = selectedSchool,
        onResult = { hasRecords ->
            if (hasRecords) {
                // Records are in Firebase — load immediately, no Arbor sync needed.
                firebaseStatusMessage = null
                loadChildRecordsFromFirestore()
                isLoadingMeals = false
                renderKitchenView()
                // Background: sync today's Arbor meal choices to update mealSelected.
                runMealChoicesSync(silent = true)
            } else {
                // No local data — trigger full Arbor sync.
                firebaseStatusMessage = getString(R.string.loading_todays_meals)
                renderKitchenView()
                ensureAuthenticatedThenSync(retryOnUnauthenticated = true)
            }
        },
        onFailure = {
            // On cache check failure, fall through to Arbor sync.
            ensureAuthenticatedThenSync(retryOnUnauthenticated = true)
        }
    )
}

// Verifies a fresh auth token exists prior to invoking the callable sync.
internal fun MainActivity.ensureAuthenticatedThenSync(retryOnUnauthenticated: Boolean) {
    authManager.ensureFreshAuth(
        onReady = {
            runArborSyncCallable(
                retryOnUnauthenticated = retryOnUnauthenticated,
                offset = 0,
                totalWritten = 0,
                knownTotal = null
            )
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
                        if (rateLimited) {
                            // Arbor rate-limited us mid-batch. The callable stopped
                            // immediately (no internal sleep) so we are safe here.
                            // Wait 65s on the Android side before resuming.
                            val waitMs = 65_000L
                            firebaseStatusMessage =
                                "Rate limited — resuming in 65s " +
                                "($newTotalWritten/$progressTotal synced)…"
                            renderKitchenView()
                            Handler(Looper.getMainLooper()).postDelayed({
                                firebaseStatusMessage = null
                                runArborSyncCallable(
                                    retryOnUnauthenticated = retryOnUnauthenticated,
                                    offset = nextOffset,
                                    totalWritten = newTotalWritten,
                                    knownTotal = totalAvailable
                                )
                            }, waitMs)
                        } else {
                            firebaseStatusMessage = null
                            renderKitchenView()
                            runArborSyncCallable(
                                retryOnUnauthenticated = retryOnUnauthenticated,
                                offset = nextOffset,
                                totalWritten = newTotalWritten,
                                knownTotal = totalAvailable
                            )
                        }
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
                    // Also sync today's meal choices from Arbor (background).
                    runMealChoicesSync(silent = false)
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

// Returns today's date as YYYY-MM-DD in Europe/London timezone.
private fun todayLondonDate(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
    sdf.timeZone = TimeZone.getTimeZone("Europe/London")
    return sdf.format(Date())
}

// Calls syncTodaysMealChoices (single GraphQL call) to update mealSelected.
// When silent=true, completes without toasts (background refresh after cache hit).
internal fun MainActivity.runMealChoicesSync(silent: Boolean) {
    arborSyncService.syncMealChoices(
        schoolName = selectedSchool,
        targetDate = todayLondonDate(),
        onSuccess = { data ->
            val updated = (data?.get("updated") as? Number)?.toInt() ?: 0
            Log.d("MealChoicesSync", "Sync complete: updated=$updated")
            // Reload from Firestore so fresh meal choices appear in the UI.
            loadChildRecordsFromFirestore()
            renderKitchenView()
            if (!silent && updated > 0) {
                Toast.makeText(
                    this,
                    "Meal choices updated: $updated students",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onFailure = { error ->
            // Non-fatal — student roster still shows, meal choice column will be blank.
            Log.w("MealChoicesSync", "Meal choices sync failed (non-fatal)", error)
        }
    )
}
