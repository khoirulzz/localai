package id.localai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.platform.LocalContext
import id.localai.app.search.TavilyKeyStore
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.localai.app.models.ModelCatalog
import id.localai.app.models.ModelManifestEntry
import id.localai.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ActiveScreen {
    CHAT,
    MODEL_MANAGER,
    SETTINGS
}

enum class GenerationPhase {
    IDLE,
    PREPARING,
    READING_DOC,
    READING_IMAGE,
    SEARCHING_WEB,
    STREAMING
}

data class UiCitation(
    val label: String,
    val urlOrPage: String,
    val isWeb: Boolean
)

data class UiAttachment(
    val name: String,
    val type: String, // "image" | "pdf"
    val meta: String,
    val hasTextLayer: Boolean = true
)

data class UiMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val attachment: UiAttachment? = null,
    val citations: List<UiCitation> = emptyList(),
    val status: String = "COMPLETED", // "STREAMING" | "COMPLETED" | "STOPPED" | "FAILED"
    val errorText: String? = null
)

data class UiConversation(
    val id: String,
    val title: String,
    val messages: List<UiMessage>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiAppRoot() {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    val colors = resolveColors(themeMode)
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(ActiveScreen.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val context = LocalContext.current
    val keyStore = remember { TavilyKeyStore(context) }
    var keyInput by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    // Installed model state (Qwen3.5 2B & 0.8B installed by default after setup)
    var installedModelIds by remember {
        mutableStateOf(setOf("qwen3.5-2b-q4_k_m", "qwen3.5-0.8b-q4_k_m"))
    }
    var activeModelId by remember { mutableStateOf<String?>("qwen3.5-2b-q4_k_m") }
    val activeModel: ModelManifestEntry? = remember(activeModelId, installedModelIds) {
        ModelCatalog.entries.find { it.id == activeModelId && installedModelIds.contains(it.id) }
    }

    // Tavily BYOK Settings state
    var savedTavilyKey by remember { mutableStateOf(if (runCatching { keyStore.hasSavedKey() }.getOrDefault(false)) "saved" else "") }
    var tavilyStatus by remember { mutableStateOf(if (savedTavilyKey.isNotBlank()) "API key tersimpan (terenkripsi)" else "API key belum disimpan") }
    var searchEnabled by remember { mutableStateOf(false) }

    // Advanced Settings state
    var contextSize by remember { mutableStateOf(2048) }
    var cpuThreads by remember { mutableStateOf(4) }
    var temperature by remember { mutableStateOf(0.6f) }

    // Conversations state
    var conversations by remember {
        mutableStateOf(
            listOf(
                UiConversation(
                    id = "conv-1",
                    title = "Percakapan baru",
                    messages = emptyList()
                )
            )
        )
    }
    var activeConvId by remember { mutableStateOf("conv-1") }
    val currentConv = conversations.find { it.id == activeConvId } ?: conversations.first()

    var composerText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<UiAttachment?>(null) }
    var generationPhase by remember { mutableStateOf(GenerationPhase.IDLE) }

    val startNewConversation = {
        val newId = "conv-${System.currentTimeMillis()}"
        conversations = listOf(UiConversation(newId, "Percakapan baru", emptyList())) + conversations
        activeConvId = newId
        composerText = ""
        pendingAttachment = null
        searchEnabled = false
    }
    val displayNameOf: (Uri) -> String = { uri ->
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "lampiran"
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) pendingAttachment = UiAttachment(displayNameOf(uri), "image", "Foto")
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) pendingAttachment = UiAttachment(displayNameOf(uri), "pdf", "PDF")
    }

    BackHandler(enabled = drawerState.isOpen || currentScreen != ActiveScreen.CHAT) {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else currentScreen = ActiveScreen.CHAT
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.foreground
        ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = currentScreen == ActiveScreen.CHAT,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = colors.background,
                        drawerContentColor = colors.foreground
                    ) {
                        Column(modifier = Modifier.fillMaxHeight().padding(16.dp)) {
                            Text("Local AI", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(12.dp))
                            DrawerRow("✎  Percakapan baru", colors) {
                                startNewConversation()
                                currentScreen = ActiveScreen.CHAT
                                scope.launch { drawerState.close() }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Riwayat", style = LocalAiTypography.metadata, color = colors.secondaryText)
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(conversations, key = { it.id }) { c ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                activeConvId = c.id
                                                currentScreen = ActiveScreen.CHAT
                                                scope.launch { drawerState.close() }
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = c.title.ifBlank { "Percakapan baru" },
                                            color = if (c.id == activeConvId) colors.foreground else colors.secondaryText,
                                            fontWeight = if (c.id == activeConvId) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "Hapus",
                                            style = LocalAiTypography.metadata,
                                            color = colors.secondaryText,
                                            modifier = Modifier
                                                .clickable {
                                                    val remaining = conversations.filter { it.id != c.id }
                                                    conversations = remaining.ifEmpty {
                                                        listOf(UiConversation("conv-${System.currentTimeMillis()}", "Percakapan baru", emptyList()))
                                                    }
                                                    if (activeConvId == c.id) activeConvId = conversations.first().id
                                                }
                                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                                        )
                                    }
                                }
                            }
                            DrawerRow("Kelola model", colors) {
                                currentScreen = ActiveScreen.MODEL_MANAGER
                                scope.launch { drawerState.close() }
                            }
                            DrawerRow("Pengaturan", colors) {
                                currentScreen = ActiveScreen.SETTINGS
                                scope.launch { drawerState.close() }
                            }
                        }
                    }
                }
            ) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                when (currentScreen) {
                    ActiveScreen.CHAT -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 5.1 Header (56dp, no decorative badges)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(LocalAiDimens.TopBarHeight)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(LocalAiDimens.MinTouchTarget)
                                        .clickable { scope.launch { drawerState.open() } }
                                        .semantics { contentDescription = "Buka riwayat percakapan" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("≡", color = colors.foreground, fontSize = 22.sp)
                                }

                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (installedModelIds.isEmpty()) {
                                                currentScreen = ActiveScreen.MODEL_MANAGER
                                            } else {
                                                showModelPicker = true
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .semantics { contentDescription = "Pilih model aktif" },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = activeModel?.displayName ?: "Pilih model",
                                        style = LocalAiTypography.headerModel,
                                        color = colors.foreground
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("▾", color = colors.secondaryText, fontSize = 14.sp)
                                }

                                Box(
                                    modifier = Modifier
                                        .size(LocalAiDimens.MinTouchTarget)
                                        .clickable {
                                            val newId = "conv-${System.currentTimeMillis()}"
                                            conversations = listOf(
                                                UiConversation(newId, "Percakapan baru", emptyList())
                                            ) + conversations
                                            activeConvId = newId
                                            composerText = ""
                                            pendingAttachment = null
                                            searchEnabled = false
                                        }
                                        .semantics { contentDescription = "Percakapan baru" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✎", color = colors.foreground, fontSize = 18.sp)
                                }
                            }

                            // 5.2 Conversation content or Empty State
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (activeModel == null) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 32.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Unduh model untuk mulai mengobrol di perangkat ini.",
                                            style = LocalAiTypography.assistantBody,
                                            color = colors.foreground
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(colors.interactive)
                                                .clickable { currentScreen = ActiveScreen.MODEL_MANAGER }
                                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                        ) {
                                            Text("Pilih model", color = colors.interactiveContent, fontSize = 14.sp)
                                        }
                                    }
                                } else if (currentConv.messages.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Apa yang ingin kamu tanyakan?",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.foreground
                                        )
                                    }
                                } else {
                                    val listState = rememberLazyListState()
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            horizontal = LocalAiDimens.HorizontalMargin,
                                            vertical = 12.dp
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        items(currentConv.messages, key = { it.id }) { msg ->
                                            if (msg.isUser) {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.End
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .widthIn(max = 300.dp)
                                                            .clip(RoundedCornerShape(LocalAiDimens.UserBubbleRadius))
                                                            .background(colors.userBubble)
                                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                                    ) {
                                                        SelectionContainer {
                                                            Text(
                                                                text = msg.text,
                                                                style = LocalAiTypography.userBody,
                                                                color = colors.foreground
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                // Assistant response: plain text on canvas, no bubble, no avatar
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.Start
                                                ) {
                                                    SelectionContainer {
                                                        Text(
                                                            text = msg.text,
                                                            style = LocalAiTypography.assistantBody,
                                                            color = colors.foreground
                                                        )
                                                    }
                                                    if (msg.citations.isNotEmpty()) {
                                                        Spacer(Modifier.height(8.dp))
                                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                            msg.citations.forEach { cit ->
                                                                Text(
                                                                    text = cit.label,
                                                                    style = LocalAiTypography.metadata,
                                                                    color = colors.secondaryText
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 5.3 Docked Composer
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = LocalAiDimens.ComposerSideMargin,
                                        vertical = 8.dp
                                    )
                                    .clip(RoundedCornerShape(LocalAiDimens.ComposerRadius))
                                    .background(colors.inputSurface)
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                pendingAttachment?.let { att ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${att.name} · ${att.meta}",
                                            style = LocalAiTypography.metadata,
                                            color = colors.secondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Hapus",
                                            style = LocalAiTypography.metadata,
                                            color = colors.foreground,
                                            modifier = Modifier.clickable { pendingAttachment = null }
                                        )
                                    }
                                }

                                BasicTextField(
                                    value = composerText,
                                    onValueChange = { composerText = it },
                                    enabled = activeModel != null,
                                    textStyle = LocalAiTypography.composerInput.copy(color = colors.foreground),
                                    cursorBrush = SolidColor(colors.foreground),
                                    maxLines = 6,
                                    decorationBox = { innerTextField ->
                                        if (composerText.isEmpty()) {
                                            Text(
                                                text = if (currentConv.messages.isEmpty()) "Tanya apa saja…" else "Tanyakan lanjutan…",
                                                style = LocalAiTypography.composerInput,
                                                color = colors.secondaryText
                                            )
                                        }
                                        innerTextField()
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                )

                                Spacer(Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .clickable(enabled = activeModel != null) {
                                                    showAttachmentSheet = true
                                                }
                                                .semantics { contentDescription = "Tambah lampiran foto atau PDF" },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("＋", color = colors.foreground, fontSize = 20.sp)
                                        }

                                        // Functional Search toggle (monochrome selected state)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(
                                                    if (searchEnabled) colors.searchActiveSurface else colors.background.copy(alpha = 0f)
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (searchEnabled) colors.searchActiveSurface else colors.hairline,
                                                    shape = RoundedCornerShape(18.dp)
                                                )
                                                .clickable {
                                                    if (!searchEnabled && savedTavilyKey.isBlank()) {
                                                        currentScreen = ActiveScreen.SETTINGS
                                                    } else {
                                                        searchEnabled = !searchEnabled
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                                .semantics { contentDescription = "Toggle pencarian web" }
                                        ) {
                                            Text(
                                                text = "◉ Search",
                                                fontSize = 13.sp,
                                                color = if (searchEnabled) colors.searchActiveText else colors.secondaryText
                                            )
                                        }
                                    }

                                    val canSend = activeModel != null && (composerText.isNotBlank() || pendingAttachment != null)
                                    val isGenerating = generationPhase != GenerationPhase.IDLE

                                    Box(
                                        modifier = Modifier
                                            .size(LocalAiDimens.SendVisualSize)
                                            .clip(CircleShape)
                                            .background(
                                                if (isGenerating || canSend) colors.interactive
                                                else colors.hairline
                                            )
                                            .clickable(enabled = isGenerating || canSend) {
                                                if (isGenerating) {
                                                    generationPhase = GenerationPhase.IDLE
                                                } else {
                                                    val q = composerText.trim()
                                                    composerText = ""
                                                    val userMsg = UiMessage(
                                                        id = "m-${System.currentTimeMillis()}",
                                                        isUser = true,
                                                        text = q,
                                                        attachment = pendingAttachment
                                                    )
                                                    pendingAttachment = null
                                                    conversations = conversations.map { c ->
                                                        if (c.id == activeConvId) {
                                                            c.copy(
                                                                title = if (c.messages.isEmpty()) q.take(36) else c.title,
                                                                messages = c.messages + userMsg
                                                            )
                                                        } else c
                                                    }
                                                }
                                            }
                                            .semantics {
                                                contentDescription = if (isGenerating) "Hentikan pembuatan jawaban" else "Kirim pesan"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isGenerating) "■" else "↑",
                                            color = colors.interactiveContent,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ActiveScreen.MODEL_MANAGER -> {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "← Kembali",
                                    modifier = Modifier.clickable { currentScreen = ActiveScreen.CHAT }.padding(8.dp),
                                    color = colors.foreground
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("Kelola model", style = LocalAiTypography.headerModel, color = colors.foreground)
                            }
                            Spacer(Modifier.height(16.dp))
                            ModelCatalog.entries.forEach { entry ->
                                val isInstalled = installedModelIds.contains(entry.id)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(entry.displayName, style = LocalAiTypography.headerModel, color = colors.foreground)
                                        Text(
                                            text = if (isInstalled) "Hapus" else "Unduh",
                                            color = colors.foreground,
                                            modifier = Modifier.clickable {
                                                installedModelIds = if (isInstalled) {
                                                    installedModelIds - entry.id
                                                } else {
                                                    installedModelIds + entry.id
                                                }
                                            }
                                        )
                                    }
                                    Text(entry.roleSummary, style = LocalAiTypography.metadata, color = colors.secondaryText)
                                }
                            }
                        }
                    }

                    ActiveScreen.SETTINGS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "← Kembali",
                                    modifier = Modifier.clickable { currentScreen = ActiveScreen.CHAT }.padding(8.dp),
                                    color = colors.foreground
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("Pengaturan", style = LocalAiTypography.headerModel, color = colors.foreground)
                            }

                            Spacer(Modifier.height(20.dp))
                            Text("Tampilan", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionChip("Sistem", themeMode == ThemeMode.SYSTEM, colors) { themeMode = ThemeMode.SYSTEM }
                                OptionChip("Terang", themeMode == ThemeMode.LIGHT, colors) { themeMode = ThemeMode.LIGHT }
                                OptionChip("Gelap", themeMode == ThemeMode.DARK, colors) { themeMode = ThemeMode.DARK }
                            }

                            Spacer(Modifier.height(28.dp))
                            Text("Web Search", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Pencarian mengirim kata kunci ke Tavily. Jawaban AI tetap dibuat di perangkat.",
                                style = LocalAiTypography.metadata,
                                color = colors.secondaryText
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Status: $tavilyStatus", style = LocalAiTypography.metadata, color = colors.foreground)
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.inputSurface)
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                BasicTextField(
                                    value = keyInput,
                                    onValueChange = { keyInput = it },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    textStyle = LocalAiTypography.composerInput.copy(color = colors.foreground),
                                    cursorBrush = SolidColor(colors.foreground),
                                    decorationBox = { inner ->
                                        if (keyInput.isEmpty()) {
                                            Text("Tempel API key Tavily", style = LocalAiTypography.composerInput, color = colors.secondaryText)
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionChip("Simpan", keyInput.isNotBlank(), colors) {
                                    if (keyInput.isNotBlank()) {
                                        try {
                                            keyStore.saveApiKey(keyInput)
                                            savedTavilyKey = "saved"
                                            tavilyStatus = "API key tersimpan (terenkripsi)"
                                            keyInput = ""
                                        } catch (e: Exception) {
                                            tavilyStatus = "Gagal menyimpan: ${e.message}"
                                        }
                                    }
                                }
                                if (savedTavilyKey.isNotBlank()) {
                                    OptionChip("Hapus key", false, colors) {
                                        runCatching { keyStore.clearApiKey() }
                                        savedTavilyKey = ""
                                        searchEnabled = false
                                        tavilyStatus = "API key belum disimpan"
                                    }
                                }
                            }

                            Spacer(Modifier.height(28.dp))
                            Text("Lanjutan", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(8.dp))
                            Text("Ukuran konteks (token)", style = LocalAiTypography.metadata, color = colors.secondaryText)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1024, 2048, 4096).forEach { v ->
                                    OptionChip(v.toString(), contextSize == v, colors) { contextSize = v }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Thread CPU", style = LocalAiTypography.metadata, color = colors.secondaryText)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2, 4, 6).forEach { v ->
                                    OptionChip(v.toString(), cpuThreads == v, colors) { cpuThreads = v }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Suhu (kreativitas)", style = LocalAiTypography.metadata, color = colors.secondaryText)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0.3f, 0.6f, 0.9f).forEach { v ->
                                    OptionChip(v.toString(), temperature == v, colors) { temperature = v }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
            }

            // Sheet pilih model
            if (showModelPicker) {
                ModalBottomSheet(
                    onDismissRequest = { showModelPicker = false },
                    containerColor = colors.inputSurface,
                    contentColor = colors.foreground
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                        Text("Pilih model", style = LocalAiTypography.headerModel, color = colors.foreground)
                        Spacer(Modifier.height(8.dp))
                        ModelCatalog.entries.filter { installedModelIds.contains(it.id) }.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activeModelId = e.id; showModelPicker = false }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(e.displayName, color = colors.foreground)
                                    Text(e.roleSummary, style = LocalAiTypography.metadata, color = colors.secondaryText)
                                }
                                if (e.id == activeModelId) Text("✓", color = colors.foreground)
                            }
                        }
                        Text(
                            "Kelola model",
                            color = colors.foreground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showModelPicker = false; currentScreen = ActiveScreen.MODEL_MANAGER }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }

            // Sheet lampiran
            if (showAttachmentSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showAttachmentSheet = false },
                    containerColor = colors.inputSurface,
                    contentColor = colors.foreground
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                        Text("Tambah lampiran", style = LocalAiTypography.headerModel, color = colors.foreground)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Foto",
                            color = colors.foreground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAttachmentSheet = false; pickImage.launch("image/*") }
                                .padding(vertical = 14.dp)
                        )
                        Text(
                            "PDF",
                            color = colors.foreground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAttachmentSheet = false; pickPdf.launch("application/pdf") }
                                .padding(vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(label: String, colors: LocalAiColors, onClick: () -> Unit) {
    Text(
        text = label,
        color = colors.foreground,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

@Composable
private fun OptionChip(label: String, selected: Boolean, colors: LocalAiColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.interactive else colors.background.copy(alpha = 0f))
            .border(1.dp, if (selected) colors.interactive else colors.hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) colors.interactiveContent else colors.foreground)
    }
}
