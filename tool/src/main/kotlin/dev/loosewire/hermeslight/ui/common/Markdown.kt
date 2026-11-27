package dev.loosewire.hermeslight.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp

/**
 * The small Markdown subset an agent actually emits on this phone: bold, inline code, fenced
 * code and underlined links. Images and other media degrade to a labelled placeholder rather
 * than a broken or misleading render. Deliberately not CommonMark.
 */
private sealed interface MarkdownBlock {
    data class Paragraph(
        val text: String,
    ) : MarkdownBlock

    data class Code(
        val text: String,
    ) : MarkdownBlock

    data class Media(
        val label: String,
    ) : MarkdownBlock
}

private const val FENCE = "```"

private fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    var inCode = false
    val code = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.clear()
    }

    source.replace("\r\n", "\n").lines().forEach { line ->
        val trimmed = line.trim()
        when {
            inCode -> {
                if (trimmed.startsWith(FENCE)) {
                    blocks += MarkdownBlock.Code(code.toString().trimEnd())
                    code.clear()
                    inCode = false
                } else {
                    code.appendLine(line)
                }
            }

            trimmed.startsWith(FENCE) -> {
                flushParagraph()
                inCode = true
            }

            trimmed.isEmpty() -> flushParagraph()

            isStandaloneImage(trimmed) -> {
                flushParagraph()
                blocks += MarkdownBlock.Media(imageLabel(trimmed))
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    if (inCode) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    flushParagraph()
    return blocks
}

private fun isStandaloneImage(line: String): Boolean = line.startsWith("![") && line.contains("](") && line.endsWith(")")

private fun imageLabel(source: String): String {
    val alt = source.substringAfter("![").substringBefore("](").trim()
    return if (alt.isEmpty()) "[image]" else "[image: $alt]"
}

private fun appendInline(
    builder: AnnotatedString.Builder,
    text: String,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    mediaStyle: SpanStyle,
) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    builder.append(text[i])
                    i++
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    builder.withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    builder.append(text[i])
                    i++
                }
            }

            text.startsWith("![", i) -> {
                val close = text.indexOf("](", i + 2)
                val urlEnd = if (close >= 0) text.indexOf(')', close + 2) else -1
                if (close >= 0 && urlEnd > close) {
                    builder.withStyle(mediaStyle) {
                        append(imageLabel(text.substring(i, urlEnd + 1)))
                    }
                    i = urlEnd + 1
                } else {
                    builder.append(text[i])
                    i++
                }
            }

            text[i] == '[' -> {
                val close = text.indexOf("](", i + 1)
                val urlEnd = if (close >= 0) text.indexOf(')', close + 2) else -1
                if (close >= 0 && urlEnd > close) {
                    val label = text.substring(i + 1, close)
                    builder.withStyle(linkStyle) { append(label) }
                    i = urlEnd + 1
                } else {
                    builder.append(text[i])
                    i++
                }
            }

            else -> {
                builder.append(text[i])
                i++
            }
        }
    }
}

@Composable
private fun TextUnit.scaledOrSelf(): TextUnit = if (isSp && value.isFinite()) value.designVerticalPxToSp() else this

@Composable
private fun paragraphStyle(): TextStyle {
    val colors = LightThemeTokens.colors
    val t = LightThemeTokens.typography.paragraph
    return t.copy(
        color = colors.content,
        fontSize = t.fontSize.scaledOrSelf(),
        lineHeight = t.lineHeight.scaledOrSelf(),
        letterSpacing = t.letterSpacing.scaledOrSelf(),
    )
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val blocks = remember(text) { parseMarkdown(text) }
    val style = paragraphStyle()
    val codeStyle =
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            color = colors.content,
        )
    val linkStyle =
        SpanStyle(
            color = colors.content,
            textDecoration = TextDecoration.Underline,
        )
    val mediaStyle = SpanStyle(color = colors.content)

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(0.4f.verticalGridUnitsAsDp()))
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    val annotated =
                        remember(block.text, codeStyle, linkStyle, mediaStyle) {
                            buildAnnotatedString {
                                appendInline(this, block.text, codeStyle, linkStyle, mediaStyle)
                            }
                        }
                    BasicText(
                        text = annotated,
                        style = style,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is MarkdownBlock.Code -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawRect(
                                        color = colors.contentSecondary,
                                        size = Size(1.dp.toPx(), size.height),
                                    )
                                }.padding(start = 0.7f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = block.text,
                            variant = LightTextVariant.Detail,
                            monospace = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is MarkdownBlock.Media -> {
                    LightText(
                        text = block.label,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
