package com.music.vivi.vivimusic.updater

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.node.*
import org.commonmark.parser.Parser
import java.net.URI

internal data class NoteBlock(val node: Node, val prefix: String = "", val level: Int = 0)
internal fun noteBlocks(body: String): List<NoteBlock> {
    val document = Parser.builder().build().parse(cleanReleaseNotes(body))
    val result = mutableListOf<NoteBlock>()
    fun walk(parent: Node, prefix: String = "") {
        var node = parent.firstChild
        var number = (parent as? OrderedList)?.markerStartNumber ?: 1
        while (node != null) {
            when (node) {
                is HtmlBlock -> Unit // No WebView, raw HTML, image fetches or executable embeds.
                is BulletList, is OrderedList, is BlockQuote -> walk(node, prefix)
                is ListItem -> walk(node, prefix + if (parent is OrderedList) "${number++}. " else "• ")
                is Heading -> result += NoteBlock(node, prefix, node.level)
                is org.commonmark.node.Paragraph, is FencedCodeBlock, is IndentedCodeBlock, is ThematicBreak -> result += NoteBlock(node, prefix)
            }
            node = node.next
        }
    }
    walk(document)
    return result
}

internal fun safeNoteLink(url: String): Boolean = try {
    URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
} catch (_: Exception) { false }

internal fun annotatedNote(block: NoteBlock, primary: Color): AnnotatedString = buildAnnotatedString {
    append(block.prefix)
    fun children(parent: Node) {
        var node = parent.firstChild
        while (node != null) {
            val current = node
            when (current) {
                is org.commonmark.node.Text -> append(current.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(current.literal) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(current) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(current) }
                is SoftLineBreak, is HardLineBreak -> append("\n")
                is Link -> if (safeNoteLink(current.destination)) {
                    pushStringAnnotation("URL", current.destination)
                    withStyle(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)) { children(current) }
                    pop()
                } else children(current)
                is Image, is HtmlInline -> Unit
                else -> children(current)
            }
            node = current.next
        }
    }
    when (val node = block.node) {
        is FencedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal.trimEnd()) }
        is IndentedCodeBlock -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal.trimEnd()) }
        is ThematicBreak -> append("────────")
        else -> children(node)
    }
}

@Composable
internal fun ReleaseNoteBlock(block: NoteBlock, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.primary
    val text = remember(block, color) { annotatedNote(block, color) }
    val typography = MaterialTheme.typography
    ClickableText(text = text, modifier = modifier,
        style = (when (block.level) { 1 -> typography.headlineSmall; 2 -> typography.titleLarge; 3, 4, 5, 6 -> typography.titleMedium; else -> typography.bodyLarge })
            .copy(color = MaterialTheme.colorScheme.onBackground),
        onClick = { offset -> text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { openHttpsInBrowser(context, it.item) } })
}
