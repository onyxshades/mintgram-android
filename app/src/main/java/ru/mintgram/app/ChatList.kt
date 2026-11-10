package ru.mintgram.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(vm: AppVM, onSettings: () -> Unit) {
    val connected by vm.socket.ready.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MintGram")
                        if (!connected) Text("Подключение…", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { search = true }) { Icon(Icons.Filled.Search, "Найти") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Новая группа или канал") }, onClick = { menu = false; newGroup = true })
                            DropdownMenuItem(text = { Text("Профиль и настройки") }, onClick = { menu = false; onSettings() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        val chats = vm.sortedChats
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(chats, key = { it.key }) { c ->
                ChatRow(vm, c)
                HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    if (search) SearchDialog(vm) { search = false }
    if (newGroup) NewGroupDialog(vm) { newGroup = false }
}

@Composable
private fun ChatRow(vm: AppVM, c: ChatItem) {
    val self = c.user?.username?.equals(vm.meName, true) == true
    val isOnline = c.user != null && !self && vm.online.any { it.equals(c.user.username, true) }
    Row(
        Modifier.fillMaxWidth().clickable { vm.open(c.key) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            when {
                c.key == GENERAL -> Avatar("Общий", null, 200, 52.dp, Icons.Filled.Public)
                c.groupId != null -> Avatar(c.title, null, null, 52.dp, if (c.isChannel) Icons.Filled.Campaign else Icons.Filled.Group)
                self -> Avatar("Избранное", null, 210, 52.dp, Icons.Filled.Bookmark)
                else -> Avatar(c.title, c.user?.avatar?.let { vm.api.fileUrl(it) }, c.user?.accent, 52.dp)
            }
            if (isOnline) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background).padding(2.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                c.lastText.ifBlank { " " }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                fmtListTime(c.lastTs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (c.unread > 0) {
                Spacer(Modifier.size(4.dp))
                Box(
                    Modifier.heightIn(min = 22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (c.unread > 99) "99+" else c.unread.toString(),
                        color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(vm: AppVM, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        vm.searchUsers(q.trim().removePrefix("@")) { results = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Найти человека") },
        text = {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text("Имя или @username") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.username }) { u ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onDismiss(); vm.open(u.username) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(u.title, u.avatar?.let { vm.api.fileUrl(it) }, u.accent, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(u.title, style = MaterialTheme.typography.titleSmall)
                                Text("@${u.username}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun NewGroupDialog(vm: AppVM, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (channel) "Новый канал" else "Новая группа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Описание") })
                Row(Modifier.clickable { channel = !channel }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = channel, onCheckedChange = { channel = it })
                    Text("Канал (пишут только админы)")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.createGroup(name, desc, channel); onDismiss() },
                enabled = name.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
