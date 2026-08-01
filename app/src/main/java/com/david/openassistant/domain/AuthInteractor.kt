package com.david.openassistant.domain

import android.content.Context
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterClient
import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterKeyInfo
import com.david.openassistant.data.security.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthInteractor(context: Context, private val openRouterClient: OpenRouterClient) {
    private val keyStore = ApiKeyStore(context)
    private val diagnostics = RuntimeDiagnostics(context)

    suspend fun loadApiKey(): String? = withContext(Dispatchers.IO) {
        keyStore.load()
    }

    suspend fun hasEncryptedCredential(): Boolean = withContext(Dispatchers.IO) {
        keyStore.hasEncryptedCredential()
    }

    suspend fun clearCredential() = withContext(Dispatchers.IO) {
        keyStore.clear()
    }

    suspend fun validateAndSaveKey(apiKey: String): Result<OpenRouterKeyInfo> = withContext(Dispatchers.IO) {
        runCatching {
            openRouterClient.validateKey(apiKey).also {
                keyStore.save(apiKey)
            }
        }
    }

    suspend fun validateKey(apiKey: String): Result<OpenRouterKeyInfo> = withContext(Dispatchers.IO) {
        runCatching {
            openRouterClient.validateKey(apiKey)
        }
    }
}
