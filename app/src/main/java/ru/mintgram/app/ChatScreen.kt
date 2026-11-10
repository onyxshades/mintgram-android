package ru.mintgram.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private sealed interface Row_ {
    data class Day(val label: String) : Row_
    data class Message(val m: Msg) : Row_
}

private fun buildRows(list: List<Msg>): List<Row_> {
    val out = ArrayList<Row_>()
    var lastDay = ""
    for (m in list) {
        val d = if (m.pending) fmtDay(System.currentTimeMillis()) else fmtDay(m.ts)
        if (d != lastDay) { out.add(Row_.Day(d)); lastDay = d }
        out.add(Row_.Message(m))
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: AppVM, key: String) {
    val isGroup = key.startsWith("g:")
    val isGeneral = key == GENERAL
    val groupId = if (isGroup) key.substring(2).toLong() else null
    val group = groupId?.let { vm.groups[it] }
    val self = !isGroup && !isGeneral && key.equals(vm.meName, true)
    val peer = if (!isGroup && !isGeneral) vm.users[key] else null
    val list = vm.messages[key] ?: emptyList()

    var input by remember(key) { mutableStateOf("") }
    var replyTo by remember(key) { mutableStateOf<Msg?>(null) }
    var editing by remember(key) { mutableStateOf<Msg?>(null) }
    var menuFor by remember(key) { mutableStateOf<Msg?>(null) }
    var profileOf by remember(key) { mutableStateOf<String?>(null) }
    var viewer by remember(key) { mutableStateOf<String?>(null) }
    var groupInfo by remember(key) { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            vm.sendImage(key, uri, input, replyTo)
            input = ""; replyTo = null
        }
    }

    val askCall = rememberMediaPermission(vm) { video -> vm.calls.start(key, video) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val typing = (vm.typingUntil[key] ?: 0) > now
    val title = when {
        isGeneral -> "Общий чат"
        self -> "Избранное"
        isGroup -> group?.name ?: "Группа"
        else -> peer?.title ?: key
    }
    val status = when {
        typing -> if (isGroup) "${vm.typingWho[key] ?: ""} печатает…" else "печатает…"
        isGroup -> if (group?.isChannel == true) "канал · ${group.memberCount} подписч." else "${group?.memberCount ?: 0} участн."
        isGeneral -> "все пользователи"
        self -> "заметки для себя"
        vm.online.any { it.equals(key, true) } -> "в сети"
        else -> lastSeenText(peer?.lastSeen)
    }
    val canWrite = !(group?.isChannel == true && group.role == "member")
    val isAdmin = group?.role == "admin" || group?.role == "owner"

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---------- шапка
        TopAppBar(
            title = {
                Row(
                    Modifier.clickable {
                        if (isGroup) groupInfo = true else if (!isGeneral && !self) profileOf = key
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        isGeneral -> Avatar("Общий", null, 200, 40.dp, Icons.Filled.Public)
                        isGroup -> Avatar(title, null, null, 40.dp, if (group?.isChannel == true) Icons.Filled.Campaign else Icons.Filled.Group)
                        self -> Avatar("Избранное", null, 210, 40.dp, Icons.Filled.Bookmark)
                        else -> Avatar(title, peer?.avatar?.let { vm.api.fileUrl(it) }, peer?.accent, 40.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            status, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = if (typing || status == "в сети") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            },
            navigationIcon = { IconButton(onClick = { vm.close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = {
                if (!isGroup && !isGeneral && !self) {
                    IconButton(onClick = { askCall(false) }) { Icon(Icons.Filled.Call, "Позвонить") }
                    IconButton(onClick = { askCall(true) }) { Icon(Icons.Filled.Videocam, "Видеозвонок") }
                }
                if (isGroup) IconButton(onClick = { groupInfo = true }) { Icon(Icons.Filled.Info, "О группе") }
            },
        )
        if (vm.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())

        // ---------- сообщения
        val rows = remember(list) { buildRows(list).reversed() }
        val state = rememberLazyListState()
        LaunchedEffect(list.size) {
            if (state.firstVisibleItemIndex <= 3) state.animateScrollToItem(0)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, reverseLayout = true) {
            items(rows, key = {
                when (it) {
                    is Row_.Day -> "d" + it.label
                    is Row_.Message -> if (it.m.pending) "c" + it.m.cid else "m" + it.m.id
                }
            }) { row ->
                when (row) {
                    is Row_.Day -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            row.label, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                    is Row_.Message -> MessageBubble(
                        vm = vm, key = key, m = row.m,
                        showSender = isGroup || isGeneral,
                        onLongPress = { if (!row.m.pending && row.m.kind != "call") menuFor = row.m },
                        onImage = { viewer = it },
                        onSender = { profileOf = it },
                    )
                }
            }
            if (vm.hasMore[key] == true) {
                item(key = "more") {
                    LaunchedEffect(list.firstOrNull()?.id) { vm.loadOlder(key) }
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        // ---------- ввод
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding()) {
                if (!canWrite) {
                    Text(
                        "В этом канале писать могут только администраторы",
                        Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    replyTo?.let { r ->
                        Banner("Ответ: ${vm.users[r.from]?.title ?: r.from}", previewOf(r.kind, r.text, r.deleted)) { replyTo = null }
                    }
                    editing?.let { e ->
                        Banner("Изменение сообщения", e.text) { editing = null; input = "" }
                    }
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { picker.launch("image/*") }, enabled = editing == null && !vm.uploading) {
                            Icon(Icons.Filled.AttachFile, "Фото")
                        }
                        TextField(
                            value = input,
                            onValueChange = { input = it; if (it.isNotEmpty()) vm.onTyping(key) },
                            placeholder = { Text("Сообщение") },
                            modifier = Modifier.weight(1f),
                            maxLines = 5,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        IconButton(
                            onClick = {
                                val e = editing
                                if (e != null) { vm.edit(key, e, input); editing = null; input = "" }
                                else { vm.send(key, input, replyTo); input = ""; replyTo = null }
                            },
                            enabled = input.isNotBlank(),
                        ) {
                            Icon(
                                if (editing != null) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send, "Отправить",
                                tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- меню сообщения
    menuFor?.let { m ->
        val mine = m.from.equals(vm.meName, true)
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(bottom = 16.dp)) {
                if (!isGroup && !m.deleted) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        REACTIONS.forEach { e ->
                            Text(
                                e, style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.clip(CircleShape).clickable { vm.react(m, e); menuFor = null }.padding(8.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (!m.deleted && (canWrite)) {
                    MenuItem("Ответить") { replyTo = m; editing = null; menuFor = null }
                }
                if (!m.deleted && m.text.isNotBlank()) {
                    MenuItem("Копировать") { clipboard.setText(AnnotatedString(m.text)); menuFor = null }
                }
                if (mine && !m.deleted && m.kind == "text") {
                    MenuItem("Изменить") { editing = m; replyTo = null; input = m.text; menuFor = null }
                }
                if ((mine || isAdmin) && !m.deleted) {
                    MenuItem("Удалить", danger = true) { vm.delete(key, m); menuFor = null }
                }
            }
        }
    }

    // ---------- просмотр фото
    viewer?.let { url ->
        Dialog(onDismissRequest = { viewer = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewer = null }) {
                AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                IconButton(onClick = { viewer = null }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Filled.Close, "Закрыть", tint = Color.White)
                }
            }
        }
    }

    // ---------- профиль человека
    profileOf?.let { name -> UserDialog(vm, name, showWrite = name != key) { profileOf = null } }

    // ---------- информация о группе
    if (groupInfo && group != null) GroupDialog(vm, group) { groupInfo = false }
}

@Composable
private fun MenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(text, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Banner(title: String, text: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Отменить") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    vm: AppVM,
    key: String,
    m: Msg,
    showSender: Boolean,
    onLongPress: () -> Unit,
    onImage: (String) -> Unit,
    onSender: (String) -> Unit,
) {
    val mine = m.from.equals(vm.meName, true)
    val sender = vm.users[m.from]
    val senderName = sender?.title ?: m.from
    val primary = MaterialTheme.colorScheme.primary
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine && showSender) {
            Box(Modifier.clip(CircleShape).clickable { onSender(m.from) }) {
                Avatar(senderName, sender?.avatar?.let { vm.api.fileUrl(it) }, sender?.accent, 32.dp)
            }
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            color = bg, shape = shape,
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                onClick = { if (m.failed) vm.retry(key, m) },
                onLongClick = onLongPress,
            ),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (!mine && showSender) {
                    Text(
                        senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = avatarColor(m.from, sender?.accent),
                    )
                }
                m.reply?.let { r ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.width(3.dp).height(32.dp).background(primary))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(vm.users[r.from]?.title ?: r.from, style = MaterialTheme.typography.labelSmall, color = primary)
                            Text(
                                previewOf(r.kind, r.text, r.deleted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                when {
                    m.deleted -> Text(
                        "Сообщение удалено", fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    m.kind == "image" && m.fileId != null -> {
                        val url = vm.api.fileUrl(m.fileId)
                        val ratio = if (m.w != null && m.h != null && m.h > 0) (m.w.toFloat() / m.h).coerceIn(0.5f, 2f) else 1.33f
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(top = 2.dp).fillMaxWidth().aspectRatio(ratio)
                                .clip(RoundedCornerShape(12.dp)).clickable { onImage(url) },
                        )
                        if (m.text.isNotBlank()) Text(m.text, Modifier.padding(top = 4.dp))
                    }
                    m.kind == "call" -> {
                        val what = if (m.callVideo) "Видеозвонок" else "Голосовой звонок"
                        val st = when (m.callStatus) {
                            "missed" -> "пропущен"
                            "declined" -> "отклонён"
                            "cancelled" -> "отменён"
                            "ended", "answered" -> if (m.callDuration > 0) "${m.callDuration / 60}:%02d".format(m.callDuration % 60) else "завершён"
                            else -> m.callStatus.orEmpty()
                        }
                        Text("📞 $what" + if (st.isNotBlank()) " · $st" else "")
                    }
                    else -> Text(m.text)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    if (m.failed) {
                        Text("Не отправлено · нажмите, чтобы повторить", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        if (m.edited != null) Text("изм. ", style = MaterialTheme.typography.labelSmall, color = dim)
                        Text(
                            fmtTime(if (m.pending) System.currentTimeMillis() else m.ts),
                            style = MaterialTheme.typography.labelSmall, color = dim,
                        )
                        if (mine) {
                            val isDirect = key != GENERAL && !key.startsWith("g:")
                            val read = isDirect && !m.pending &&
                                (key.equals(vm.meName, true) || m.id <= (vm.peerRead[key] ?: 0L))
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                when { m.pending -> Icons.Filled.Schedule; read -> Icons.Filled.DoneAll; else -> Icons.Filled.Done },
                                null, Modifier.size(14.dp), tint = if (read) primary else dim,
                            )
                        }
                    }
                }
                if (m.reactions.isNotEmpty() && !m.deleted) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.reactions.forEach { r ->
                            val own = r.users.any { it.equals(vm.meName, true) }
                            Text(
                                "${r.emoji} ${r.users.size}", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(if (own) primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface)
                                    .clickable { vm.react(m, r.emoji) }.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserDialog(vm: AppVM, name: String, showWrite: Boolean, onDismiss: () -> Unit) {
    LaunchedEffect(name) { vm.ensureUser(name) }
    val u = vm.users[name]
    val online = vm.online.any { it.equals(name, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(u?.title ?: name, u?.avatar?.let { vm.api.fileUrl(it) }, u?.accent, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(u?.title ?: name, style = MaterialTheme.typography.titleLarge)
                    Text("@$name", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        text = {
            Column {
                Text(if (online) "в сети" else lastSeenText(u?.lastSeen), style = MaterialTheme.typography.bodyMedium)
                if (!u?.bio.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(u?.bio.orEmpty())
                }
            }
        },
        confirmButton = {
            if (showWrite) TextButton(onClick = { onDismiss(); vm.open(name) }) { Text("Написать") }
            else TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = if (showWrite) ({ TextButton(onClick = onDismiss) { Text("Закрыть") } }) else null,
    )
}

@Composable
private fun GroupDialog(vm: AppVM, g: GroupInfo, onDismiss: () -> Unit) {
    var member by remember { mutableStateOf("") }
    val canAdd = g.role == "owner" || g.role == "admin"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(g.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!g.description.isNullOrBlank()) Text(g.description)
                Text(
                    (if (g.isChannel) "Канал" else "Группа") + " · ${g.memberCount} участн. · ваша роль: ${g.role ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (canAdd) {
                    OutlinedTextField(
                        value = member, onValueChange = { member = it }, singleLine = true,
                        label = { Text("Добавить по @username") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default,
                    )
                    OutlinedButton(
                        onClick = { vm.addMember(g.id, member); member = "" },
                        enabled = member.isNotBlank(),
                    ) { Text("Добавить") }
                }
                TextButton(onClick = { onDismiss(); vm.leaveGroup(g.id) }) {
                    Text("Выйти из группы", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
