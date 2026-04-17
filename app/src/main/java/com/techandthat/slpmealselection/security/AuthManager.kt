package com.techandthat.slpmealselection.security

import com.google.firebase.auth.FirebaseAuth

/**
 * Manager responsible for Firebase Authentication.
 * Handles user sessions and token lifecycle to ensure secure communication with backend services.
 */
class AuthManager(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    // Determines if a Firebase session is currently active.
    fun currentUserExists(): Boolean = firebaseAuth.currentUser != null

    // Ends the current authentication session.
    fun signOut() {
        firebaseAuth.signOut()
    }

    // Authenticates the tablet anonymously, meeting Firestore security requirements.
    fun signInAnonymously(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        firebaseAuth.signInAnonymously()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Ensures the app has a valid, non-expired authentication token before critical API calls.
    fun ensureFreshAuth(
        onReady: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val currentUser = firebaseAuth.currentUser

        // If no user is logged in, attempt an anonymous sign-in first.
        if (currentUser == null) {
            signInAnonymously(onReady, onFailure)
            return
        }

        // Force-refresh the JWT token to avoid 401 Unauthenticated errors from Cloud Functions.
        currentUser.getIdToken(true)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener {
                // If refresh fails (e.g., account revoked), re-sign in.
                signInAnonymously(onReady, onFailure)
            }
    }
}
