package com.techandthat.slpmealselection.security

import com.google.firebase.auth.FirebaseAuth

// Handles Firebase Authentication responsibilities for the app.
class AuthManager(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    // Checks whether an authenticated Firebase user already exists in session.
    fun currentUserExists(): Boolean = firebaseAuth.currentUser != null

    // Clears the current Firebase auth session.
    fun signOut() {
        firebaseAuth.signOut()
    }

    // Signs in anonymously so callable functions can run with an auth context.
    fun signInAnonymously(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        firebaseAuth.signInAnonymously()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Ensures a valid current token exists before protected backend calls.
    fun ensureFreshAuth(
        onReady: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val currentUser = firebaseAuth.currentUser

        // If no user exists, create one anonymously first.
        if (currentUser == null) {
            signInAnonymously(onReady, onFailure)
            return
        }

        // Force-refresh token and fallback to anonymous sign-in if refresh fails.
        currentUser.getIdToken(true)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener {
                signInAnonymously(onReady, onFailure)
            }
    }
}
