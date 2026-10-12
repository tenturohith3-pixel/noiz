package com.noiz.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.noiz.designsystem.NoizTypography
import com.noiz.datastore.SessionStore
import com.noiz.network.NoizClientFactory
import com.noiz.network.TokenSnapshot
import com.noiz.network.normalizeBaseUrl
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * First-run sign-in (§3.2 "First connection").
 *
 * Collects a server URL and credentials, normalises the URL, exchanges them for tokens
 * and persists the session. Until this succeeds the app has no catalogue and cannot
 * play anything, which is why it is a separate full-screen surface rather than a
 * settings item.
 */
@Composable
fun ConnectScreen(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverInput by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val connecting = stringResource(R.string.connect_connecting)
    val signIn = stringResource(R.string.connect_sign_in)

    fun submit() {
        if (busy) return
        val base = normalizeBaseUrl(serverInput)
        if (base == null) {
            error = "Enter a valid server address, e.g. https://noiz.example"
            return
        }
        if (username.isBlank() || password.isBlank()) {
            error = "Username and password are required."
            return
        }

        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                SignInUseCase(appContext).run(base, username.trim(), password)
            }
            busy = false
            result.fold(
                onSuccess = { onSignedIn() },
                onFailure = { error = it.message ?: "Sign-in failed." },
            )
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("noiz", style = NoizTypography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.connect_tagline),
                style = NoizTypography.bodyMedium,
            )
            Spacer(Modifier.height(36.dp))

            OutlinedTextField(
                value = serverInput,
                onValueChange = { serverInput = it; error = null },
                label = { Text(stringResource(R.string.connect_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text(stringResource(R.string.connect_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text(stringResource(R.string.connect_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = NoizTypography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::submit,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  $connecting")
                } else {
                    Text(signIn)
                }
            }
        }
    }
}

/**
 * Exchanges credentials for tokens and writes the session.
 *
 * Kept out of the composable so the network call is testable and so the failure
 * messages come from one place.
 */
class SignInUseCase(private val context: Context) {

    suspend fun run(baseUrl: String, username: String, password: String) {
        val appContext = context.applicationContext
        val sessionStore = SessionStore(appContext)

        val store = object : com.noiz.network.TokenStore {
            override fun snapshot(): TokenSnapshot? = null
            override fun update(access: String, refresh: String, expiresAtEpochMs: Long) = Unit
        }
        val factory = NoizClientFactory(
            tokens = store,
            serverUrl = { baseUrl },
        )

        val response = factory.api.login(
            com.noiz.network.LoginRequestDto(
                username = username,
                password = password,
                device = com.noiz.network.DeviceInfoDto(
                    // A stable per-install id so refresh rotation can find the device row.
                    id = UUID.nameUUIDFromBytes(
                        "${appContext.packageName}:${username}".toByteArray(),
                    ).toString(),
                    name = "Android",
                    platform = "android",
                    appVersion = com.noiz.app.BuildConfig.VERSION_NAME,
                ),
            ),
        )

        sessionStore.write(
            com.noiz.model.NoizSession(
                serverUrl = baseUrl,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                expiresAtEpochMs = System.currentTimeMillis() + response.expiresIn * 1000L,
                user = com.noiz.model.User(
                    id = response.user.id,
                    username = response.user.username,
                    displayName = response.user.displayName,
                    role = response.user.role,
                    canUpload = response.user.canUpload,
                    canDownload = response.user.canDownload,
                ),
            ),
        )
    }
}