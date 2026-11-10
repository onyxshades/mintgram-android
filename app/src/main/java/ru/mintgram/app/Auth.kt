package ru.mintgram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(vm: AppVM) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.prefs.server) }
    var showServer by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.ChatBubble, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.height(12.dp))
        Text("MintGram", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        if (vm.regUsername != null) {
            Text(
                "Мы отправили 6-значный код на ${vm.regEmailMasked}. Он действует 10 минут.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Код из письма") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorLine(vm.authError)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.confirmCode(code) },
                enabled = code.length == 6 && !vm.authBusy, modifier = Modifier.fillMaxWidth(),
            ) { Busy(vm.authBusy, "Подтвердить") }
            TextButton(onClick = { vm.resendCode() }, enabled = !vm.authBusy) { Text("Отправить код ещё раз") }
            TextButton(onClick = { vm.cancelRegistration() }) { Text("Назад") }
            return@Column
        }

        TabRow(selectedTabIndex = if (register) 1 else 0, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = !register, onClick = { register = false; vm.authError = null }, text = { Text("Вход") })
            Tab(selected = register, onClick = { register = true; vm.authError = null }, text = { Text("Регистрация") })
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username, onValueChange = { username = it.trim() },
            label = { Text("Имя пользователя") }, singleLine = true,
            supportingText = if (register) ({ Text("3–20 символов: латиница, цифры и _") }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (register) {
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() },
                label = { Text("Почта") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(vm.authError)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                vm.setServer(server)
                if (register) vm.register(username, password, email) else vm.login(username, password)
            },
            enabled = !vm.authBusy && username.isNotBlank() && password.isNotEmpty() && (!register || email.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) { Busy(vm.authBusy, if (register) "Получить код" else "Войти") }

        TextButton(onClick = { showServer = !showServer }) { Text("Адрес сервера") }
        if (showServer) {
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Адрес") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ErrorLine(text: String?) {
    if (text != null) {
        Text(
            text, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Busy(busy: Boolean, label: String) {
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(label)
}
