package com.techandthat.slpmealselection.security

import com.google.firebase.auth.FirebaseAuth

class AuthManager(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    fun currentUserExists(): Boolean = firebaseAuth.currentUser != null

    fun signOut() {
        firebaseAuth.signOut()
    }

    fun signInAnonymously(
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        firebaseAuth.signInAnonymously()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun ensureFreshAuth(
        onReady: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            signInAnonymously(onReady, onFailure)
            return
        }

        currentUser.getIdToken(true)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener {
                signInAnonymously(onReady, onFailure)
            }
    }
}
