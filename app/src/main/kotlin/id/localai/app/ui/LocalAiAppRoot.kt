package id.localai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    var drawerOpen by remember { mutableStateOf(false) }
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
    var savedTavilyKey by remember { mutableStateOf("") }
    var tavilyStatus by remember { mutableStateOf("API key belum disimpan") }
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

    CompositionLocalProvider(LocalAppColors provides colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.foreground
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
                                        .clickable { drawerOpen = true }
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
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                            Text("Web Search", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Pencarian mengirim kata kunci ke Tavily. Jawaban AI tetap dibuat di perangkat.",
                                style = LocalAiTypography.metadata,
                                color = colors.secondaryText
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Status: $tavilyStatus", style = LocalAiTypography.metadata, color = colors.foreground)
                        }
                    }
                }
            }
        }
    }
}
