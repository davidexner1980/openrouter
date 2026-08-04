package com.david.openassistant.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Single source of truth for the real-time operational state of the device
 * and application credentials. Used to determine tool availability.
 */
object AgentOperationalState {

    fun isNetworkAvailable(context: Context?): Boolean {
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // Fallback to true if context is missing, allowing the call to fail at the transport layer
        
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Determines if OpenRouter credentials are functionally available.
     */
    fun areCredentialsAvailable(apiKey: String?): Boolean {
        return !apiKey.isNullOrBlank() && apiKey.startsWith("sk-or-")
    }
}
