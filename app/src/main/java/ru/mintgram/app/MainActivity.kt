package ru.mintgram.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // входящий звонок показывается поверх экрана блокировки
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        handleIntent(intent)
        setContent { MintTheme { Root(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val key = i?.getStringExtra("chat") ?: return
        i.removeExtra("chat")
        if (vm.loggedIn) vm.open(key)
    }

    override fun onStart() { super.onStart(); vm.onForeground(true) }
    override fun onStop() { vm.onForeground(false); super.onStop() }
}

@Composable
fun Root(vm: AppVM) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(vm.loggedIn) {
        if (vm.loggedIn && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { snack.showSnackbar(it); vm.toast = null }
    }

    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.loggedIn) { if (!vm.loggedIn) settings = false }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            val open = vm.openChat
            when {
                !vm.loggedIn -> AuthScreen(vm)
                open != null -> {
                    BackHandler { vm.close() }
                    ChatScreen(vm, open)
                }
                settings -> {
                    BackHandler { settings = false }
                    SettingsScreen(vm) { settings = false }
                }
                else -> ChatListScreen(vm, onSettings = { settings = true })
            }
            CallOverlay(vm)
            SnackbarHost(
                snack,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
            ) { Snackbar(it) }
        }
    }
}
