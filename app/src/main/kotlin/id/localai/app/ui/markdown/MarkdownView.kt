package id.localai.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import id.localai.app.ui.theme.LocalAiColors
import id.localai.app.ui.theme.LocalAiTypography
import id.localai.app.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

private class MdStyle(
    val colors: LocalAiColors,
    val body: TextStyle,
    val textColor: Color,
    val link: Color,
    val codeBg: Color,
    val line: Color
) {
    fun withText(color: Color) = MdStyle(colors, body, color, link, codeBg, line)
}

@Composable
private fun rememberMdStyle(): MdStyle {
    val c = LocalAppColors.current
    return remember(c) {
        MdStyle(
            colors = c,
            body = LocalAiTypography.assistantBody,
            textColor = c.foreground,
            link = if (c.isDark) Color(0xFF8AB4F8) else Color(0xFF1A5FB4),
            codeBg = c.inputSurface,
            line = c.hairline
        )
    }
}

/** Buka tautan hanya jika skemanya aman, dan jangan crash bila tidak ada aplikasi pembukanya. */
@Composable
private fun SafeLinks(content: @Composable () -> Unit) {
    val base = LocalUriHandler.current
    val safe = remember(base) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (Markdown.isSafeUrl(uri)) runCatching { base.openUri(uri) }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides safe, content = content)
}

/** Jawaban AI: markdown penuh, tautan bisa diklik. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val st = rememberMdStyle()
    val blocks = remember(text) { Markdown.parse(text) }
    SafeLinks {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { RenderBlock(it, st, depth = 0) }
        }
    }
}

/** Teks pengguna: tidak diformat, tetapi URL polos tetap bisa diklik. */
@Composable
fun LinkifiedText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val st = rememberMdStyle()
    val annotated = remember(text, st) {
        buildAnnotatedString { appendInlines(Markdown.linkifyPlain(text), st) }
    }
    SafeLinks { Text(text = annotated, style = style, color = color, modifier = modifier) }
}

// ------------------------------------------------------------------ inline

private fun AnnotatedString.Builder.appendInlines(list: List<MdInline>, st: MdStyle) {
    for (n in list) {
        when (n) {
            is MdInline.Text -> append(n.text)
            is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlines(n.children, st) }
            is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(n.children, st) }
            is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(n.children, st) }
            is MdInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = st.codeBg, fontSize = 0.9.em)
            ) { append(n.code) }
            is MdInline.Link -> withLink(
                LinkAnnotation.Url(
                    n.url,
                    TextLinkStyles(SpanStyle(color = st.link, textDecoration = TextDecoration.Underline))
                )
            ) { appendInlines(n.children, st) }
        }
    }
}

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    st: MdStyle,
    style: TextStyle = st.body,
    modifier: Modifier = Modifier
) {
    val annotated = remember(inlines, st) { buildAnnotatedString { appendInlines(inlines, st) } }
    Text(text = annotated, style = style, color = st.textColor, modifier = modifier)
}

// ------------------------------------------------------------------ blok

@Composable
private fun RenderBlock(block: MdBlock, st: MdStyle, depth: Int) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.inlines, st)

        is MdBlock.Heading -> {
            val size = when (block.level) { 1 -> 22; 2 -> 19; 3 -> 17; else -> 16 }
            InlineText(
                block.inlines, st,
                style = st.body.copy(fontSize = size.sp, lineHeight = (size * 1.35f).sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = if (depth == 0) 6.dp else 0.dp)
            )
        }

        is MdBlock.Code -> CodeBlock(block, st)

        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = if (block.ordered) "${block.start + index}." else listOf("•", "◦", "▪")[depth % 3],
                        style = st.body,
                        color = st.colors.secondaryText,
                        modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp)
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.forEach { RenderBlock(it, st, depth + 1) }
                    }
                }
            }
        }

        is MdBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(st.colors.secondaryText.copy(alpha = 0.45f))
            )
            Spacer(Modifier.width(10.dp))
            val quoteStyle = st.withText(st.colors.secondaryText)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.blocks.forEach { RenderBlock(it, quoteStyle, depth + 1) }
            }
        }

        MdBlock.Rule -> HorizontalDivider(color = st.line, modifier = Modifier.padding(vertical = 4.dp))

        is MdBlock.Table -> TableBlock(block, st)
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, st: MdStyle) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(st.codeBg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = block.language.ifBlank { "kode" },
                style = LocalAiTypography.metadata,
                color = st.colors.secondaryText
            )
            Text(
                text = if (copied) "Tersalin" else "Salin",
                style = LocalAiTypography.metadata,
                color = st.colors.foreground,
                modifier = Modifier
                    .clickable {
                        clipboard.setText(AnnotatedString(block.code))
                        copied = true
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        Box(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                text = block.code,
                style = LocalAiTypography.codeBlock,
                color = st.colors.foreground,
                softWrap = false
            )
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, st: MdStyle) {
    val cellStyle = st.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    fun align(i: Int) = when (block.aligns.getOrNull(i)) {
        MdAlign.CENTER -> TextAlign.Center
        MdAlign.END -> TextAlign.End
        else -> TextAlign.Start
    }
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, st.line, RoundedCornerShape(8.dp))
        ) {
            Row(modifier = Modifier.background(st.codeBg)) {
                block.header.forEachIndexed { i, cell ->
                    InlineText(
                        cell, st,
                        style = cellStyle.copy(fontWeight = FontWeight.SemiBold, textAlign = align(i)),
                        modifier = Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            block.rows.forEach { row ->
                HorizontalDivider(color = st.line)
                Row {
                    row.forEachIndexed { i, cell ->
                        InlineText(
                            cell, st,
                            style = cellStyle.copy(textAlign = align(i)),
                            modifier = Modifier.width(140.dp).padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
