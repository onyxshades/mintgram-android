package ru.mintgram.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppVM, onBack: () -> Unit) {
    val me = vm.me
    var name by remember(me?.displayName) { mutableStateOf(me?.displayName.orEmpty()) }
    var bio by remember(me?.bio) { mutableStateOf(me?.bio.orEmpty()) }
    var accent by remember(me?.accent) { mutableStateOf(me?.accent) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setAvatar(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (vm.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Avatar(
                me?.title ?: "?", me?.avatar?.let { vm.api.fileUrl(it) }, accent, 96.dp,
            )
            TextButton(onClick = { pick.launch("image/*") }) { Text("Сменить фото") }
            Text("@${vm.meName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                label = { Text("Имя") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bio, onValueChange = { bio = it.take(140) },
                label = { Text("О себе") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Цвет аватарки", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = (accent ?: 160).toFloat(), onValueChange = { accent = it.toInt() },
                valueRange = 0f..359f,
            )
            if (accent != null) TextButton(onClick = { accent = null }) { Text("Сбросить цвет") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.saveProfile(name, bio, accent) }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Сервер: ${vm.prefs.server}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text("Выйти из аккаунта") }
        }
    }
}
