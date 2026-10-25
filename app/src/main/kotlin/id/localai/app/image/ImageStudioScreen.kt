package id.localai.app.image

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.localai.app.ui.theme.LocalAiColors
import id.localai.app.ui.theme.LocalAiTypography
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStudioScreen(colors: LocalAiColors, onMenu: () -> Unit) {
    val vm: ImageStudioViewModel = viewModel()
    var showModels by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.chooseSource(uri)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.results.size) {
        if (vm.results.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ImageRoundAction("menu", colors, onMenu)
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { showModels = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(vm.selectedModel.title, color = colors.foreground,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("⌄", color = colors.secondaryText)
            }
            ImageRoundAction("add", colors) { showModels = true }
        }
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageModeButton("Buat", !vm.editMode, colors) { vm.setMode(false) }
                    ImageModeButton("Edit gambar", vm.editMode, colors) { vm.setMode(true) }
                }
                if (vm.results.isEmpty() && !vm.running) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 90.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ImageGlyph("image", colors, 42.dp)
                        Spacer(Modifier.height(18.dp))
                        Text("Buat gambar dengan AI", fontSize = 20.sp, color = colors.foreground,
                            fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text("Diproses sepenuhnya di perangkat", color = colors.secondaryText, fontSize = 13.sp)
                    }
                }
            }
            if (vm.running) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(colors.inputSurface).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.foreground
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Membuat gambar…", color = colors.foreground, fontWeight = FontWeight.Medium)
                            Text("512 × 512 · diproses lokal", fontSize = 12.sp, color = colors.secondaryText)
                        }
                    }
                }
            }
            items(vm.results, key = { it.absolutePath }) { file ->
                Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(17.dp))
                            .background(colors.inputSurface)
                            .clickable { preview = file.absolutePath },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = rememberBitmap(file)
                        if (bmp != null) {
                            Image(bmp, "Hasil gambar", Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit)
                        } else {
                            Text("Gagal membaca gambar", color = colors.secondaryText)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("512 × 512", fontSize = 12.sp, color = colors.secondaryText,
                            modifier = Modifier.weight(1f))
                        Text("Lihat", fontSize = 13.sp, color = colors.foreground,
                            modifier = Modifier.clickable { preview = file.absolutePath }
                                .padding(horizontal = 15.dp, vertical = 9.dp))
                        Row(
                            Modifier.clip(CircleShape).background(colors.inputSurface)
                                .clickable { vm.saveToGallery(file) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ImageGlyph("download", colors, 17.dp)
                            Spacer(Modifier.width(7.dp))
                            Text("Simpan", fontSize = 13.sp, color = colors.foreground)
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp)).background(colors.inputSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (vm.editMode) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val selected = vm.sourceImage
                    if (selected != null && selected.exists()) {
                        rememberBitmap(selected)?.let {
                            Image(it, "Gambar referensi",
                                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Gambar referensi", color = colors.foreground,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("Hapus", fontSize = 12.sp, color = colors.secondaryText,
                            modifier = Modifier.clickable { vm.removeSource() }.padding(8.dp))
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                                .clickable { imagePicker.launch("image/*") }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ImageGlyph("image", colors, 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Pilih gambar", color = colors.foreground, fontSize = 13.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Perubahan", fontSize = 12.sp, color = colors.secondaryText)
                    Slider(vm.strength, vm::changeStrength,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        valueRange = 0.25f..0.85f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.foreground, activeTrackColor = colors.foreground,
                            inactiveTrackColor = colors.hairline
                        )
                    )
                    Text((vm.strength * 100).toInt().toString() + "%",
                        color = colors.foreground, fontSize = 12.sp)
                }
            }
            BasicTextField(
                value = vm.prompt,
                onValueChange = { vm.prompt = it.take(1300) },
                textStyle = LocalAiTypography.composerInput.copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.foreground),
                minLines = 2, maxLines = 5,
                decorationBox = { inner ->
                    Box {
                        if (vm.prompt.isBlank()) Text(
                            if (vm.editMode) "Jelaskan perubahan gambar…" else "Jelaskan gambar yang ingin dibuat…",
                            style = LocalAiTypography.composerInput, color = colors.secondaryText
                        )
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (vm.editMode) ImageRoundAction("image", colors) { imagePicker.launch("image/*") }
                Spacer(Modifier.weight(1f))
                if (vm.running) {
                    TextButton(onClick = vm::stop) { Text("Hentikan", color = colors.foreground) }
                } else {
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (vm.prompt.isBlank()) colors.hairline else colors.interactive)
                            .clickable(enabled = vm.prompt.isNotBlank()) {
                                if (vm.isInstalled()) vm.generate() else showModels = true
                            }.padding(horizontal = 18.dp, vertical = 11.dp)
                            .semantics { contentDescription = "Buat gambar" }
                    ) {
                        Text(if (vm.editMode) "Edit" else "Buat gambar",
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (vm.prompt.isBlank()) colors.secondaryText else colors.interactiveContent)
                    }
                }
            }
            if (vm.status.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(vm.status, fontSize = 12.sp, color = colors.secondaryText,
                    modifier = Modifier.fillMaxWidth().clickable { vm.dismissStatus() })
            }
        }
    }

    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            containerColor = colors.background, contentColor = colors.foreground
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
                Text("Model gambar", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                ImageModelCatalog.all.forEach { model ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !vm.running && !vm.downloading) {
                            vm.chooseModel(model.id)
                        }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(model.detail, color = colors.secondaryText, fontSize = 12.sp)
                        }
                        if (vm.isInstalled(model)) {
                            Text(if (model.id == vm.selectedModelId) "✓" else "Terpasang",
                                color = colors.secondaryText, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = colors.hairline)
                }
                Spacer(Modifier.height(18.dp))
                if (!vm.isInstalled()) {
                    if (vm.downloading) {
                        LinearProgressIndicator(
                            progress = { vm.downloadProgress },
                            modifier = Modifier.fillMaxWidth(), color = colors.foreground,
                            trackColor = colors.hairline
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Mengunduh · " + (vm.downloadProgress * 100).toInt() + "%",
                            fontSize = 12.sp, color = colors.secondaryText)
                        TextButton(onClick = vm::cancelDownload) {
                            Text("Jeda", color = colors.foreground)
                        }
                    } else {
                        Button(
                            onClick = vm::downloadModel, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.interactive,
                                contentColor = colors.interactiveContent
                            )
                        ) { Text("Unduh " + vm.selectedModel.title) }
                        TextButton(
                            onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Impor GGUF", color = colors.foreground) }
                    }
                } else {
                    Text("Siap digunakan offline", fontSize = 12.sp, color = colors.secondaryText)
                }
            }
        }
    }
    preview?.let { path ->
        val file = File(path)
        AlertDialog(
            onDismissRequest = { preview = null },
            containerColor = colors.background,
            title = { Text("Gambar", color = colors.foreground) },
            text = {
                rememberBitmap(file)?.let {
                    Image(it, "Pratinjau gambar", Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Fit)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveToGallery(file); preview = null }) {
                    Text("Simpan", color = colors.foreground)
                }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("Tutup", color = colors.secondaryText) }
            }
        )
    }
}

@Composable
private fun rememberBitmap(file: File): androidx.compose.ui.graphics.ImageBitmap? =
    remember(file.absolutePath, file.lastModified()) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }

@Composable
private fun ImageModeButton(label: String, active: Boolean, colors: LocalAiColors, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (active) colors.foreground else colors.inputSurface)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (active) colors.background else colors.foreground,
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ImageRoundAction(kind: String, colors: LocalAiColors, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(colors.inputSurface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (kind == "menu") "Buka menu" else "Pilih gambar atau model" },
        contentAlignment = Alignment.Center
    ) { ImageGlyph(kind, colors, 22.dp) }
}

@Composable
private fun ImageGlyph(kind: String, colors: LocalAiColors, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 2.dp.toPx()
        when (kind) {
            "menu" -> {
                drawLine(colors.foreground, Offset(w * .16f, h * .33f),
                    Offset(w * .85f, h * .33f), stroke, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * .16f, h * .65f),
                    Offset(w * .66f, h * .65f), stroke, cap = StrokeCap.Round)
            }
            "add" -> {
                drawLine(colors.foreground, Offset(w * .50f, h * .20f),
                    Offset(w * .50f, h * .80f), stroke, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * .20f, h * .50f),
                    Offset(w * .80f, h * .50f), stroke, cap = StrokeCap.Round)
            }
            "download" -> {
                drawLine(colors.foreground, Offset(w * .5f, h * .1f),
                    Offset(w * .5f, h * .68f), stroke, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * .28f, h * .48f),
                    Offset(w * .5f, h * .70f), stroke, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * .72f, h * .48f),
                    Offset(w * .5f, h * .70f), stroke, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * .22f, h * .86f),
                    Offset(w * .78f, h * .86f), stroke, cap = StrokeCap.Round)
            }
            else -> {
                drawRoundRect(colors.foreground,
                    topLeft = Offset(w * .12f, h * .18f),
                    size = androidx.compose.ui.geometry.Size(w * .76f, h * .65f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 1.5f),
                    style = Stroke(stroke))
                drawCircle(colors.foreground, w * .065f, Offset(w * .70f, h * .36f))
                drawLine(colors.foreground, Offset(w * .22f, h * .70f),
                    Offset(w * .44f, h * .48f), stroke, cap = StrokeCap.Round)
                drawLine(colors.foreground, Offset(w * .44f, h * .48f),
                    Offset(w * .74f, h * .70f), stroke, cap = StrokeCap.Round)
            }
        }
    }
}
