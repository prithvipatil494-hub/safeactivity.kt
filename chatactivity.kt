package com.example.helloworld

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ─── Chat data models ─────────────────────────────────────────────────────────

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPending: Boolean = false   // <-- added for compatibility
)

data class ChatConversation(
    val conversationId: String,
    val participants: List<String>,
    val names: Map<String, String>,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unread: Map<String, Int>
)

// ─── HTTP helper for JSONArray ────────────────────────────────────────────────
// File-private to avoid signature collisions with other top-level helpers
// in the same package.
private fun httpGetArray(path: String): JSONArray? = try {
    val conn = java.net.URL("$BACKEND$path").openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.requestMethod = "GET"
    if (conn.responseCode == 200)
        JSONArray(conn.inputStream.bufferedReader().readText())
    else null
} catch (_: Exception) { null }

// ─── JSON parsers ─────────────────────────────────────────────────────────────

fun parseConversations(arr: JSONArray): List<ChatConversation> {
    val list = mutableListOf<ChatConversation>()
    for (i in 0 until arr.length()) {
        val o  = arr.getJSONObject(i)
        val participants = mutableListOf<String>()
        val pArr = o.optJSONArray("participants")
        if (pArr != null) for (j in 0 until pArr.length()) participants.add(pArr.getString(j))
        val names   = mutableMapOf<String, String>()
        val namesObj = o.optJSONObject("names")
        namesObj?.keys()?.forEach { k -> names[k] = namesObj.getString(k) }
        val unread  = mutableMapOf<String, Int>()
        val unreadObj = o.optJSONObject("unread")
        unreadObj?.keys()?.forEach { k -> unread[k] = unreadObj.optInt(k, 0) }
        list.add(ChatConversation(
            conversationId = o.optString("conversationId"),
            participants   = participants,
            names          = names,
            lastMessage    = o.optString("lastMessage", ""),
            lastTimestamp  = o.optLong("lastTimestamp", 0L),
            unread         = unread
        ))
    }
    return list
}

fun parseMessages(arr: JSONArray): List<ChatMessage> {
    val list = mutableListOf<ChatMessage>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        list.add(ChatMessage(
            id             = o.optString("_id", UUID.randomUUID().toString()),
            conversationId = o.optString("conversationId"),
            senderId       = o.optString("senderId"),
            senderName     = o.optString("senderName"),
            text           = o.optString("text"),
            timestamp      = o.optLong("timestamp", 0L)
        ))
    }
    return list
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

fun makeConvId(a: String, b: String) = listOf(a, b).sorted().joinToString("__")

fun formatTimestamp(ts: Long): String {
    val now  = System.currentTimeMillis()
    val diff = now - ts
    val sdf  = when {
        diff < 86_400_000L  -> SimpleDateFormat("HH:mm",        Locale.getDefault())
        diff < 604_800_000L -> SimpleDateFormat("EEE HH:mm",  Locale.getDefault())
        else                -> SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    }
    return sdf.format(Date(ts))
}

// ─── Entry point ──────────────────────────────────────────────────────────────

@Composable
fun ChatEntryPoint(
    myTrackId:  String,
    myName:     String,
    trackedIds: List<String>,
    initialChatTrackId: String? = null,
    initialChatName:    String? = null,
    onBack:     () -> Unit
) {
    var openConvId   by remember { mutableStateOf<String?>(null) }
    var openFriendId by remember { mutableStateOf<String?>(null) }

    // If the caller pre-selected a friend, open that conversation immediately.
    // `initialChatName` is currently unused, but accepted for API compatibility.
    LaunchedEffect(myTrackId, initialChatTrackId) {
        if (initialChatTrackId != null) {
            openFriendId = initialChatTrackId
            openConvId   = makeConvId(myTrackId, initialChatTrackId)
        }
    }

    if (openConvId != null && openFriendId != null) {
        ChatConversationScreen(
            myTrackId      = myTrackId,
            myName         = myName,
            friendId       = openFriendId!!,
            conversationId = openConvId!!,
            onBack         = { openConvId = null; openFriendId = null }
        )
    } else {
        ChatListScreen(
            myTrackId  = myTrackId,
            trackedIds = trackedIds,
            onOpenConv = { convId, friendId ->
                openConvId   = convId
                openFriendId = friendId
            },
            onBack = onBack
        )
    }
}

// ─── Conversation list ────────────────────────────────────────────────────────
//
//  KEY CHANGE: We build the friend list from TWO sources:
//   1. trackedIds  — friends the user is currently tracking (in-memory / Firestore)
//   2. serverConvs — conversations that already exist on the backend server
//
//  This means even if trackedIds is briefly empty on app open (before Firestore
//  loads), previously-chatted friends still appear because the server knows about
//  them. The list is deduped so nobody appears twice.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    myTrackId:  String,
    trackedIds: List<String>,
    onOpenConv: (String, String) -> Unit,
    onBack:     () -> Unit
) {
    var serverConvs by remember { mutableStateOf<List<ChatConversation>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }

    // Poll conversations from the backend every 3s
    // This is the source-of-truth for "has this person ever chatted with me?"
    LaunchedEffect(myTrackId) {
        while (true) {
            val result = withContext(Dispatchers.IO) {
                try {
                    httpGetArray("/api/chat/conversations/$myTrackId")
                        ?.let { parseConversations(it) } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
            serverConvs = result
            isLoading   = false
            delay(3_000L)
        }
    }

    // Build the unified friend list:
    //  - Start with trackedIds (the friends the user is tracking)
    //  - Add any friend IDs that appear in server conversations but aren't in trackedIds
    //    (covers the "friend removed from map but chat history still exists" case)
    //  - Deduplicate
    val allFriendIds = remember(trackedIds, serverConvs) {
        val fromServer = serverConvs
            .flatMap { it.participants }
            .filter { it != myTrackId }
        (trackedIds + fromServer).distinct()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Messages", fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp, color = TextPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = SkyPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SheetBg)
            )
        },
        containerColor = SheetBg
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // Show loading spinner only on first load and only if we have nothing to show
            if (isLoading && allFriendIds.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = SkyPrimary, strokeWidth = 3.dp)
                }
                return@Scaffold
            }

            // Empty state — no friends and no server conversations
            if (allFriendIds.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", fontSize = 52.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("No conversations yet", fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text("Add a friend's Track ID to start chatting",
                            fontSize = 13.sp, color = TextMuted)
                    }
                }
                return@Scaffold
            }

            Text(
                "CONVERSATIONS",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp, color = TextMuted
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(allFriendIds, key = { it }) { friendId ->
                    val convId      = makeConvId(myTrackId, friendId)
                    val conv        = serverConvs.find { it.conversationId == convId }
                    val unreadCount = conv?.unread?.get(myTrackId) ?: 0

                    // Color slot: prefer the tracked slot index; fall back to hash for
                    // friends who appear in chat history but aren't in trackedIds
                    val colorIndex = trackedIds.indexOf(friendId)
                        .takeIf { it >= 0 }
                        ?: (friendId.hashCode().and(0x7FFFFFFF) % FriendColors.size)

                    ConversationRow(
                        friendId    = friendId,
                        conv        = conv,
                        colorIndex  = colorIndex,
                        unreadCount = unreadCount,
                        onClick     = { onOpenConv(convId, friendId) }
                    )
                }
            }
        }
    }
}

// ─── Single conversation row ──────────────────────────────────────────────────

@Composable
private fun ConversationRow(
    friendId:    String,
    conv:        ChatConversation?,
    colorIndex:  Int,
    unreadCount: Int,
    onClick:     () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable { onClick() },
        shape           = RoundedCornerShape(18.dp),
        color           = Color.White,
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (unreadCount > 0) SkyPrimary.copy(alpha = 0.35f) else SheetBorder
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(FriendColors[colorIndex % FriendColors.size].copy(alpha = 0.14f)),
                Alignment.Center
            ) {
                Text(
                    "${colorIndex + 1}",
                    fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    color = FriendColors[colorIndex % FriendColors.size]
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    friendId,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = TextPrimary, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    conv?.lastMessage?.takeIf { it.isNotEmpty() } ?: "Tap to start chatting",
                    fontSize = 12.sp,
                    color = if (unreadCount > 0) TextPrimary else TextMuted,
                    fontWeight = if (unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (conv != null && conv.lastTimestamp > 0L) {
                    Text(
                        formatTimestamp(conv.lastTimestamp),
                        fontSize = 10.sp, color = TextMuted
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (unreadCount > 0) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(SkyPrimary),
                        Alignment.Center
                    ) {
                        Text(
                            if (unreadCount > 9) "9+" else "$unreadCount",
                            fontSize = 10.sp, color = Color.White,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

// ─── Single conversation screen ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatConversationScreen(
    myTrackId:      String,
    myName:         String,
    friendId:       String,
    conversationId: String,
    onBack:         () -> Unit
) {
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var serverMessages  by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var pendingMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val allMessages     = serverMessages + pendingMessages

    var inputText     by remember { mutableStateOf("") }
    var isSending     by remember { mutableStateOf(false) }
    var sendError     by remember { mutableStateOf<String?>(null) }
    var isLoadingMsgs by remember { mutableStateOf(true) }

    // Show clear-chat confirmation dialog
    var showClearDialog by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(allMessages.size) {
        if (allMessages.isNotEmpty()) {
            listState.animateScrollToItem(allMessages.size - 1)
        }
    }

    // Poll messages + mark read on enter
    LaunchedEffect(conversationId) {
        // Mark as read immediately when opening
        withContext(Dispatchers.IO) {
            httpPost("/api/chat/read", JSONObject().apply {
                put("conversationId", conversationId)
                put("trackId", myTrackId)
            })
        }

        while (true) {
            val msgs = withContext(Dispatchers.IO) {
                try {
                    httpGetArray("/api/chat/messages/$conversationId")
                        ?.let { parseMessages(it) }
                } catch (_: Exception) { null }
            }
            if (msgs != null) {
                serverMessages  = msgs
                isLoadingMsgs   = false
                // Drop optimistic messages that are now confirmed by the server
                val serverSet = msgs.map { it.senderId to it.text }.toSet()
                pendingMessages = pendingMessages.filter { p ->
                    (p.senderId to p.text) !in serverSet
                }
            }
            delay(1_500L)
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isBlank() || isSending) return

        val optimistic = ChatMessage(
            id             = "pending-${UUID.randomUUID()}",
            conversationId = conversationId,
            senderId       = myTrackId,
            senderName     = myName,
            text           = text,
            timestamp      = System.currentTimeMillis(),
            isPending      = true
        )
        pendingMessages = pendingMessages + optimistic
        inputText       = ""
        sendError       = null
        isSending       = true

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                httpPost("/api/chat/send", JSONObject().apply {
                    put("conversationId", conversationId)
                    put("senderId",       myTrackId)
                    put("senderName",     myName)
                    put("receiverId",     friendId)
                    put("receiverName",   friendId)
                    put("text",           text)
                }) != null
            }
            isSending = false
            if (!ok) {
                pendingMessages = pendingMessages.filter { it.id != optimistic.id }
                inputText       = text
                sendError       = "Failed to send — check your connection"
            }
        }
    }

    // ── Clear chat — calls DELETE endpoint on the backend ─────────────────────
    fun clearChat() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("$BACKEND/api/chat/messages/$conversationId")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.requestMethod = "DELETE"
                    conn.responseCode  // trigger the request
                } catch (_: Exception) {}
            }
            // Clear local state immediately for instant feedback
            serverMessages  = emptyList()
            pendingMessages = emptyList()
        }
    }

    if (showClearDialog) {
        ClearChatDialog(
            friendId  = friendId,
            onConfirm = { showClearDialog = false; clearChat() },
            onDismiss = { showClearDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            friendId,
                            fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                            color = TextPrimary, fontFamily = FontFamily.Monospace
                        )
                        Text("LiveLoc Chat", fontSize = 11.sp, color = TextMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = SkyPrimary)
                    }
                },
                // Clear chat button in the top bar
                actions = {
                    TextButton(
                        onClick = { showClearDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Clear", fontSize = 13.sp, color = RedRecord.copy(alpha = 0.8f),
                            fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SheetBg)
            )
        },
        containerColor = Color(0xFFF0F9FF),
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 10.dp) {
                Column {
                    if (sendError != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(RedRecord.copy(alpha = 0.08f))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠ ", fontSize = 13.sp)
                            Text(
                                sendError!!,
                                fontSize = 12.sp, color = RedRecord,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { sendError = null },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("✕", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value         = inputText,
                            onValueChange = { inputText = it; sendError = null },
                            placeholder   = { Text("Type a message…", color = TextMuted) },
                            modifier      = Modifier.weight(1f),
                            shape         = RoundedCornerShape(24.dp),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor      = SkyPrimary,
                                unfocusedBorderColor    = SheetBorder,
                                focusedContainerColor   = SheetBg,
                                unfocusedContainerColor = SheetBg,
                                focusedTextColor        = TextPrimary,
                                unfocusedTextColor      = TextPrimary
                            ),
                            maxLines        = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendMessage() })
                        )
                        Spacer(Modifier.width(8.dp))
                        val canSend = inputText.isNotBlank() && !isSending
                        FloatingActionButton(
                            onClick        = { sendMessage() },
                            modifier       = Modifier.size(48.dp),
                            containerColor = if (canSend) SkyPrimary else SheetBorder,
                            shape          = CircleShape,
                            elevation      = FloatingActionButtonDefaults.elevation(0.dp)
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    color = Color.White, strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Send, null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            isLoadingMsgs -> {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    CircularProgressIndicator(color = SkyPrimary)
                }
            }
            allMessages.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👋", fontSize = 44.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("No messages yet", fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text("Say hello to $friendId!", fontSize = 13.sp, color = TextMuted)
                    }
                }
            }
            else -> {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(allMessages, key = { it.id }) { msg ->
                        MessageBubble(msg, isMe = msg.senderId == myTrackId)
                    }
                }
            }
        }
    }
}

// ─── Clear chat confirmation dialog ───────────────────────────────────────────

@Composable
private fun ClearChatDialog(
    friendId:  String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SheetCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Clear chat?", fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "All messages with $friendId will be deleted permanently. " +
                            "This cannot be undone.",
                    fontSize = 14.sp, color = TextSecondary, lineHeight = 21.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick  = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape    = RoundedCornerShape(12.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) { Text("Cancel") }
                    Button(
                        onClick  = onConfirm,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = RedRecord),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) { Text("Delete", fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        }
    }
}

// ─── Message bubble ───────────────────────────────────────────────────────────

@Composable
fun MessageBubble(msg: ChatMessage, isMe: Boolean) {
    val timeStr = remember(msg.timestamp) { formatTimestamp(msg.timestamp) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        if (!isMe) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(SkyPrimary.copy(alpha = 0.13f))
                    .align(Alignment.Bottom),
                Alignment.Center
            ) {
                Text(
                    msg.senderId.firstOrNull()?.toString()?.uppercase() ?: "?",
                    fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = SkyPrimary
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart    = 18.dp,
                    topEnd      = 18.dp,
                    bottomStart = if (isMe) 18.dp else 4.dp,
                    bottomEnd   = if (isMe) 4.dp  else 18.dp
                ),
                color = when {
                    isMe && msg.isPending -> SkyPrimary.copy(alpha = 0.6f)
                    isMe                 -> SkyPrimary
                    else                 -> Color.White
                },
                shadowElevation = if (isMe) 0.dp else 2.dp,
                border = if (msg.isPending) androidx.compose.foundation.BorderStroke(
                    1.dp, SkyPrimary.copy(alpha = 0.4f)
                ) else null
            ) {
                Text(
                    msg.text,
                    modifier   = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize   = 15.sp,
                    color      = if (isMe) Color.White else TextPrimary,
                    lineHeight = 22.sp
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(timeStr, fontSize = 10.sp, color = TextMuted)
                if (isMe && msg.isPending) {
                    Text("Sending…", fontSize = 10.sp, color = TextMuted)
                }
            }
        }
    }
}
