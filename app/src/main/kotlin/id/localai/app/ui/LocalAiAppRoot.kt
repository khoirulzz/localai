package id.localai.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import id.localai.app.LocalAiApp
import id.localai.app.models.DownloadStatus
import id.localai.app.models.formatBytes
import id.localai.app.orchestrator.ContextBuilder
import id.localai.app.ui.markdown.LinkifiedText
import id.localai.app.ui.markdown.Markdown
import id.localai.app.ui.markdown.MarkdownText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import java.util.Locale
import kotlin.math.roundToInt
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
    val hasTextLayer: Boolean = true,
    val localPath: String? = null,
    val documentId: String? = null,
    val isProcessing: Boolean = false
)

data class UiMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val attachment: UiAttachment? = null,
    val citations: List<UiCitation> = emptyList(),
    val status: String = "COMPLETED", // "STREAMING" | "COMPLETED" | "STOPPED" | "FAILED"
    val errorText: String? = null,
    val thinking: String = "",
    val generatedTokens: Int = 0,
    val elapsedMs: Long = 0L,
    val tokensPerSecond: Float = 0f,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class UiConversation(
    val id: String,
    val title: String,
    val messages: List<UiMessage>,
    val documentId: String? = null,
    val summary: String = "",
    val summarizedThroughMs: Long = 0L
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
    val uriHandler = LocalUriHandler.current
    val keyStore = remember { (context.applicationContext as LocalAiApp).graph.keyStore }
    var keyInput by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showThinkingSheet by remember { mutableStateOf(false) }

    val vm: ChatViewModel = viewModel()
    val installedModelIds by vm.installedModelIds.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val visionInstalled by vm.visionInstalled.collectAsState()
    val visionDownloads by vm.visionDownloads.collectAsState()
    val activeModelId = vm.activeModelId
    val activeModel: ModelManifestEntry? = if (installedModelIds.isNotEmpty()) vm.activeEntry else null

    // Tavily BYOK Settings state
    var savedTavilyKey by remember { mutableStateOf(if (runCatching { keyStore.hasSavedKey() }.getOrDefault(false)) "saved" else "") }
    var tavilyStatus by remember { mutableStateOf(if (savedTavilyKey.isNotBlank()) "API key tersimpan (terenkripsi)" else "API key belum disimpan") }
    val searchEnabled = vm.searchEnabled

    // Pengaturan lanjutan, percakapan, lampiran: semuanya dipegang ViewModel
    val contextSize = vm.contextSize
    val cpuThreads = vm.cpuThreads
    val temperature = vm.temperature
    val conversations = vm.conversations
    val activeConvId = vm.activeConvId
    val currentConv = vm.currentConversation
    val pendingAttachment = vm.pendingAttachment
    val generationPhase = vm.generationPhase

    var composerText by remember { mutableStateOf("") }
    var instrDraft by remember { mutableStateOf(vm.customInstructions) }
    var importTarget by remember { mutableStateOf<ModelManifestEntry?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val target = importTarget
        importTarget = null
        if (target != null && uris.isNotEmpty()) vm.importModel(target, uris)
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val startModelDownload: (ModelManifestEntry) -> Unit = { entry ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.startDownload(entry)
    }

    val startNewConversation = {
        vm.newConversation()
        composerText = ""
    }
    val displayNameOf: (Uri) -> String = { uri ->
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "lampiran"
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) vm.attachImage(uri, displayNameOf(uri))
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) vm.attachPdf(uri, displayNameOf(uri))
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
                            Text("Nalaro AI", style = LocalAiTypography.headerModel, color = colors.foreground)
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
                                                vm.selectConversation(c.id)
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
                                                .clickable { vm.deleteConversation(c.id) }
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
                                HeaderCircleAction(
                                    menu = true, colors = colors, description = "Buka riwayat percakapan"
                                ) { scope.launch { drawerState.open() } }

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

                                HeaderCircleAction(
                                    menu = false, colors = colors, description = "Percakapan baru"
                                ) { startNewConversation() }
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
                                    val lastMsg = currentConv.messages.lastOrNull()
                                    val dragging by listState.interactionSource.collectIsDraggedAsState()
                                    var followStream by remember(currentConv.id) { mutableStateOf(true) }
                                    LaunchedEffect(dragging) { if (dragging) followStream = false }
                                    LaunchedEffect(currentConv.id, currentConv.messages.size) {
                                        if (followStream && currentConv.messages.isNotEmpty()) {
                                            listState.scrollToItem(currentConv.messages.lastIndex)
                                        }
                                    }
                                    LaunchedEffect(lastMsg?.text?.length, lastMsg?.thinking?.length) {
                                        if (followStream && currentConv.messages.isNotEmpty()) {
                                            listState.scrollToItem(currentConv.messages.lastIndex, 100000)
                                        }
                                    }
                                    Box(modifier = Modifier.fillMaxSize()) {
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
                                                        Column {
                                                            msg.attachment?.let { a ->
                                                                Text(
                                                                    text = "${if (a.type == "pdf") "PDF" else "Foto"} · ${a.name}",
                                                                    style = LocalAiTypography.metadata,
                                                                    color = colors.secondaryText,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Spacer(Modifier.height(4.dp))
                                                            }
                                                            SelectionContainer {
                                                                LinkifiedText(
                                                                    text = msg.text,
                                                                    style = LocalAiTypography.userBody,
                                                                    color = colors.foreground
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                // Assistant response: plain text on canvas, no bubble, no avatar
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.Start
                                                ) {
                                                    if (msg.thinking.isNotBlank()) {
                                                        ThinkingDisclosure(
                                                            text = msg.thinking,
                                                            streaming = msg.status == "STREAMING",
                                                            colors = colors
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                    }
                                                    if (msg.text.isBlank() && msg.status == "STREAMING") {
                                                        LoadingDots(vm.phaseLabel ?: if (msg.thinking.isNotBlank()) "Menyusun jawaban" else "Menyiapkan", colors)
                                                    } else if (msg.text.isNotBlank()) {
                                                        SelectionContainer {
                                                            if (msg.status == "STREAMING") {
                                                                Text(msg.text, style = LocalAiTypography.assistantBody, color = colors.foreground)
                                                            } else {
                                                                MarkdownText(text = msg.text)
                                                            }
                                                        }
                                                    }
                                                    if (msg.generatedTokens > 0 && msg.elapsedMs > 0L && msg.status != "STREAMING") {
                                                        Spacer(Modifier.height(6.dp))
                                                        Text(
                                                            "${String.format(Locale.US, "%.1f", msg.tokensPerSecond)} tok/dtk  ·  " +
                                                                "${String.format(Locale.US, "%.1f", msg.elapsedMs / 1000.0)} dtk  ·  ${msg.generatedTokens} token",
                                                            style = LocalAiTypography.metadata,
                                                            color = colors.secondaryText
                                                        )
                                                    }
                                                    if (msg.status == "STOPPED") {
                                                        Spacer(Modifier.height(4.dp))
                                                        Text("Dihentikan", style = LocalAiTypography.metadata, color = colors.secondaryText)
                                                    }
                                                    msg.errorText?.let { err ->
                                                        Spacer(Modifier.height(4.dp))
                                                        Text(err, style = LocalAiTypography.metadata, color = colors.foreground)
                                                    }
                                                    if (msg.citations.isNotEmpty()) {
                                                        Spacer(Modifier.height(8.dp))
                                                        Row(
                                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                                        ) {
                                                            msg.citations.forEach { cit ->
                                                                val openable = cit.isWeb && Markdown.isSafeUrl(cit.urlOrPage)
                                                                Text(
                                                                    text = cit.label,
                                                                    style = LocalAiTypography.metadata,
                                                                    color = if (openable) colors.foreground else colors.secondaryText,
                                                                    textDecoration = if (openable) TextDecoration.Underline else null,
                                                                    maxLines = 1,
                                                                    modifier = if (openable) {
                                                                        Modifier
                                                                            .clickable { runCatching { uriHandler.openUri(cit.urlOrPage) } }
                                                                            .padding(vertical = 6.dp)
                                                                    } else Modifier.padding(vertical = 6.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (!followStream && generationPhase != GenerationPhase.IDLE) {
                                        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                                            OutlinedButton(onClick = {
                                                followStream = true
                                                scope.launch {
                                                    listState.scrollToItem(currentConv.messages.lastIndex, 100000)
                                                }
                                            }) {
                                                Text("↓  Jawaban terbaru")
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
                                vm.notice?.let { n ->
                                    Text(
                                        text = "$n  ✕",
                                        style = LocalAiTypography.metadata,
                                        color = colors.foreground,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { vm.dismissNotice() }
                                            .padding(bottom = 8.dp)
                                    )
                                }
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
                                            modifier = Modifier.clickable { vm.removeAttachment() }
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

                                        // Tombol pencarian tetap terpisah dari reasoning lokal.
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
                                                        vm.updateSearchEnabled(!searchEnabled)
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
                                        Spacer(Modifier.width(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(18.dp))
                                                .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                                                .clickable(enabled = activeModel != null) { showThinkingSheet = true }
                                                .padding(horizontal = 11.dp, vertical = 6.dp)
                                                .semantics { contentDescription = "Mode berpikir" }
                                        ) {
                                            Text(
                                                when (vm.thinkingMode) {
                                                    "STANDARD" -> "Pikir"
                                                    "DEEP" -> "Pikir lebih"
                                                    else -> "Cepat"
                                                },
                                                fontSize = 13.sp, color = colors.secondaryText
                                            )
                                        }
                                    }

                                    val canSend = activeModel != null && pendingAttachment?.isProcessing != true && (composerText.isNotBlank() || pendingAttachment != null)
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
                                                    vm.stop()
                                                } else {
                                                    val q = composerText
                                                    composerText = ""
                                                    vm.send(q)
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
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Model GGUF utama cukup untuk chat teks. Proyektor mmproj adalah tambahan opsional untuk membaca gambar.",
                                style = LocalAiTypography.metadata,
                                color = colors.secondaryText
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                ModelCatalog.entries.forEach { entry ->
                                    val isInstalled = installedModelIds.contains(entry.id)
                                    val ui = downloads[entry.id]
                                    val status = ui?.status ?: DownloadStatus.IDLE
                                    val busy = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.VERIFYING
                                    val visionReady = visionInstalled.contains(entry.id)
                                    val visionUi = visionDownloads[entry.id]
                                    val visionStatus = visionUi?.status ?: DownloadStatus.IDLE
                                    val visionBusy = visionStatus == DownloadStatus.DOWNLOADING || visionStatus == DownloadStatus.VERIFYING
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(entry.displayName, style = LocalAiTypography.headerModel, color = colors.foreground)
                                                Text(
                                                    "${entry.quantization} · ${formatBytes(entry.ggufSizeBytes)}",
                                                    style = LocalAiTypography.metadata,
                                                    color = colors.secondaryText
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                when {
                                                    isInstalled -> {
                                                        if (entry.id != activeModelId) {
                                                            Text("Pakai", color = colors.foreground, modifier = Modifier.clickable { vm.selectModel(entry.id) })
                                                        }
                                                        Text(
                                                            "Impor",
                                                            color = colors.secondaryText,
                                                            modifier = Modifier.clickable {
                                                                importTarget = entry
                                                                importPicker.launch(arrayOf("*/*"))
                                                            }
                                                        )
                                                        Text("Hapus", color = colors.foreground, modifier = Modifier.clickable { vm.deleteModel(entry) })
                                                    }
                                                    busy -> Text("Jeda", color = colors.foreground, modifier = Modifier.clickable { vm.pauseDownload(entry) })
                                                    status == DownloadStatus.PAUSED -> {
                                                        Text("Lanjutkan", color = colors.foreground, modifier = Modifier.clickable { startModelDownload(entry) })
                                                        Text("Hapus", color = colors.secondaryText, modifier = Modifier.clickable { vm.deleteModel(entry) })
                                                    }
                                                    else -> {
                                                        Text(
                                                            "Impor",
                                                            color = colors.secondaryText,
                                                            modifier = Modifier.clickable {
                                                                importTarget = entry
                                                                importPicker.launch(arrayOf("*/*"))
                                                            }
                                                        )
                                                        Text(
                                                            if (status == DownloadStatus.FAILED) "Coba lagi" else "Unduh",
                                                            color = colors.foreground,
                                                            modifier = Modifier.clickable { startModelDownload(entry) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Text(entry.roleSummary, style = LocalAiTypography.metadata, color = colors.secondaryText)
                                        if (entry.supportsVision && entry.projectorUrl != null) {
                                            Spacer(Modifier.height(8.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    if (visionReady) "Visi siap · mmproj"
                                                    else "Visi opsional · ${formatBytes(entry.projectorSizeBytes)}",
                                                    style = LocalAiTypography.metadata,
                                                    color = colors.secondaryText,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isInstalled && !visionReady) {
                                                    Text(
                                                        if (visionBusy) "Jeda"
                                                        else if (visionStatus == DownloadStatus.PAUSED) "Lanjutkan visi"
                                                        else "Unduh visi",
                                                        style = LocalAiTypography.metadata,
                                                        color = colors.foreground,
                                                        modifier = Modifier.clickable {
                                                            if (visionBusy) vm.pauseDownload(entry) else vm.downloadProjector(entry)
                                                        }
                                                    )
                                                }
                                            }
                                            if (visionUi != null && visionStatus != DownloadStatus.IDLE &&
                                                visionStatus != DownloadStatus.DOWNLOADING && visionStatus != DownloadStatus.VERIFYING &&
                                                !visionReady && visionUi.message != null
                                            ) {
                                                Text(visionUi.message, style = LocalAiTypography.metadata, color = colors.secondaryText)
                                            }
                                            if (visionBusy) {
                                                LinearProgressIndicator(
                                                    progress = { visionUi?.fraction ?: 0f },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = colors.interactive,
                                                    trackColor = colors.hairline
                                                )
                                                Text(
                                                    "Proyektor · ${formatBytes(visionUi?.downloadedBytes ?: 0)}",
                                                    style = LocalAiTypography.metadata, color = colors.secondaryText
                                                )
                                            }
                                        }
                                        if (!isInstalled && ui != null && status != DownloadStatus.IDLE) {
                                            Spacer(Modifier.height(6.dp))
                                            if (status == DownloadStatus.VERIFYING) {
                                                LinearProgressIndicator(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = colors.interactive,
                                                    trackColor = colors.hairline
                                                )
                                            } else {
                                                LinearProgressIndicator(
                                                    progress = { ui.fraction },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = colors.interactive,
                                                    trackColor = colors.hairline
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = when (status) {
                                                    DownloadStatus.VERIFYING -> "Memverifikasi berkas…"
                                                    DownloadStatus.PAUSED -> "Dijeda · ${formatBytes(ui.downloadedBytes)} dari ${formatBytes(ui.totalBytes)}"
                                                    DownloadStatus.FAILED -> ui.message ?: "Unduhan gagal."
                                                    else -> "${formatBytes(ui.downloadedBytes)} dari ${formatBytes(ui.totalBytes)} · ${(ui.fraction * 100).toInt()}%"
                                                },
                                                style = LocalAiTypography.metadata,
                                                color = if (status == DownloadStatus.FAILED) colors.foreground else colors.secondaryText
                                            )
                                        }
                                        if (isInstalled && entry.id == activeModelId) {
                                            Text("Sedang dipakai", style = LocalAiTypography.metadata, color = colors.secondaryText)
                                        }
                                    }
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
                                        vm.updateSearchEnabled(false)
                                        tavilyStatus = "API key belum disimpan"
                                    }
                                }
                            }

                            Spacer(Modifier.height(28.dp))
                            Text("Instruksi kustom", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Ditambahkan ke aturan bawaan Nalaro AI di setiap percakapan. Contoh: \"Panggil saya Budi dan jawab dengan singkat.\"",
                                style = LocalAiTypography.metadata,
                                color = colors.secondaryText
                            )
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.inputSurface)
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                BasicTextField(
                                    value = instrDraft,
                                    onValueChange = { instrDraft = it.take(ContextBuilder.MAX_CUSTOM_INSTRUCTIONS) },
                                    minLines = 4,
                                    maxLines = 10,
                                    textStyle = LocalAiTypography.composerInput.copy(color = colors.foreground),
                                    cursorBrush = SolidColor(colors.foreground),
                                    decorationBox = { inner ->
                                        if (instrDraft.isEmpty()) {
                                            Text("Tulis instruksi tambahan…", style = LocalAiTypography.composerInput, color = colors.secondaryText)
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            val instrSaved = instrDraft.trim() == vm.customInstructions.trim()
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${instrDraft.length}/${ContextBuilder.MAX_CUSTOM_INSTRUCTIONS}", style = LocalAiTypography.metadata, color = colors.secondaryText)
                                Text(
                                    if (instrSaved) "Tersimpan" else "Belum disimpan",
                                    style = LocalAiTypography.metadata,
                                    color = colors.secondaryText
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionChip("Simpan", !instrSaved, colors) {
                                    if (!instrSaved) vm.updateCustomInstructions(instrDraft.trim())
                                }
                                if (instrDraft.isNotEmpty() || vm.customInstructions.isNotEmpty()) {
                                    OptionChip("Kosongkan", false, colors) {
                                        instrDraft = ""
                                        vm.updateCustomInstructions("")
                                    }
                                }
                            }

                            Spacer(Modifier.height(28.dp))
                            Text("Performa dan gaya jawaban", style = LocalAiTypography.headerModel, color = colors.foreground)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Perubahan dipakai pada pesan berikutnya; model dimuat ulang otomatis bila perlu.",
                                style = LocalAiTypography.metadata,
                                color = colors.secondaryText
                            )
                            Spacer(Modifier.height(16.dp))

                            val cores = remember { Runtime.getRuntime().availableProcessors().coerceIn(4, 12) }
                            val recommended = (cores / 2).coerceAtLeast(2)

                            SettingSlider(
                                title = "Panjang konteks",
                                value = contextSize.toFloat().coerceIn(2048f, 8192f),
                                range = 2048f..8192f,
                                steps = 11,
                                colors = colors,
                                valueText = { "${it.roundToInt()} token" },
                                caption = { "Sekitar ${(it * 0.7f / 10).toInt() * 10} kata percakapan yang diingat. Makin besar, makin banyak RAM dan makin lambat." },
                                onCommit = { vm.updateContextSize(it.roundToInt()) }
                            )
                            Spacer(Modifier.height(20.dp))
                            SettingSlider(
                                title = "Thread CPU",
                                value = cpuThreads.toFloat().coerceIn(1f, cores.toFloat()),
                                range = 1f..cores.toFloat(),
                                steps = cores - 2,
                                colors = colors,
                                valueText = { "${it.roundToInt()} thread" },
                                caption = { "HP ini punya $cores core. Disarankan $recommended thread supaya tidak cepat panas." },
                                onCommit = { vm.updateCpuThreads(it.roundToInt()) }
                            )
                            Spacer(Modifier.height(20.dp))
                            SettingSlider(
                                title = "Suhu (kreativitas)",
                                value = temperature.coerceIn(0f, 1.5f),
                                range = 0f..1.5f,
                                steps = 29,
                                colors = colors,
                                valueText = { String.format(Locale("id", "ID"), "%.2f", it) },
                                caption = {
                                    when {
                                        it < 0.2f -> "Sangat konsisten dan presisi, cocok untuk fakta dan kode."
                                        it < 0.5f -> "Fokus dan akurat."
                                        it < 0.9f -> "Seimbang untuk obrolan sehari-hari."
                                        else -> "Kreatif, tetapi lebih sulit ditebak."
                                    }
                                },
                                startLabel = "Presisi",
                                endLabel = "Kreatif",
                                onCommit = { vm.updateTemperature((it * 20).roundToInt() / 20f) }
                            )
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
                                    .clickable { vm.selectModel(e.id); showModelPicker = false }
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

            if (showThinkingSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showThinkingSheet = false },
                    containerColor = colors.inputSurface,
                    contentColor = colors.foreground
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                        Text("Mode berpikir", style = LocalAiTypography.headerModel, color = colors.foreground)
                        Spacer(Modifier.height(12.dp))
                        listOf(
                            "OFF" to "Cepat",
                            "STANDARD" to "Pikir",
                            "DEEP" to "Pikir lebih"
                        ).forEach { (mode, label) ->
                            Text(
                                "${if (vm.thinkingMode == mode) "✓  " else "     "}$label",
                                color = colors.foreground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.updateThinkingMode(mode); showThinkingSheet = false }
                                    .padding(vertical = 14.dp)
                            )
                        }
                        Text(
                            "Mode Pikir lebih memberi anggaran token lebih besar, bukan level bawaan model. " +
                                "Kecepatan tergantung model dan panjang konteks.",
                            style = LocalAiTypography.metadata, color = colors.secondaryText
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
private fun HeaderCircleAction(
    menu: Boolean,
    colors: LocalAiColors,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.inputSurface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(25.dp)) {
            val w = size.width
            val h = size.height
            val sw = 2.3.dp.toPx()
            if (menu) {
                drawLine(colors.foreground, Offset(w * 0.16f, h * 0.35f), Offset(w * 0.84f, h * 0.35f), sw, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * 0.16f, h * 0.65f), Offset(w * 0.65f, h * 0.65f), sw, cap = StrokeCap.Round)
            } else {
                drawLine(colors.foreground, Offset(w * 0.24f, h * 0.77f), Offset(w * 0.76f, h * 0.23f), sw, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * 0.18f, h * 0.83f), Offset(w * 0.34f, h * 0.78f), sw, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * 0.64f, h * 0.21f), Offset(w * 0.79f, h * 0.36f), sw, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun LoadingDots(label: String, colors: LocalAiColors) {
    val transition = rememberInfiniteTransition(label = "thinking-dots")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        repeat(3) { index ->
            val opacity by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650, delayMillis = index * 140),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(opacity)
                    .clip(CircleShape)
                    .background(colors.foreground)
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = colors.secondaryText, style = LocalAiTypography.metadata)
    }
}

@Composable
private fun ThinkingDisclosure(text: String, streaming: Boolean, colors: LocalAiColors) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (streaming) "Sedang berpikir" else "Proses berpikir",
                style = LocalAiTypography.metadata,
                color = colors.secondaryText
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "⌃" else "⌄", color = colors.secondaryText)
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val parts = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                parts.forEachIndexed { i, part ->
                    var open by remember(i) { mutableStateOf(true) }
                    if (parts.size > 1) {
                        Text(
                            "Bagian ${i + 1}  ${if (open) "⌃" else "⌄"}",
                            style = LocalAiTypography.metadata,
                            color = colors.secondaryText,
                            modifier = Modifier.clickable { open = !open }
                        )
                    }
                    if (open) {
                        Text(part, style = LocalAiTypography.metadata, color = colors.secondaryText)
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

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    colors: LocalAiColors,
    valueText: (Float) -> String,
    caption: (Float) -> String,
    onCommit: (Float) -> Unit,
    startLabel: String? = null,
    endLabel: String? = null
) {
    var drag by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = LocalAiTypography.metadata, color = colors.secondaryText)
            Text(valueText(drag), style = LocalAiTypography.headerModel, color = colors.foreground)
        }
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onCommit(drag) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.interactive,
                activeTrackColor = colors.interactive,
                inactiveTrackColor = colors.hairline,
                activeTickColor = colors.interactiveContent.copy(alpha = 0.6f),
                inactiveTickColor = colors.secondaryText.copy(alpha = 0.35f)
            )
        )
        if (startLabel != null && endLabel != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(startLabel, style = LocalAiTypography.metadata, color = colors.secondaryText)
                Text(endLabel, style = LocalAiTypography.metadata, color = colors.secondaryText)
            }
        }
        Text(caption(drag), style = LocalAiTypography.metadata, color = colors.secondaryText)
    }
}
