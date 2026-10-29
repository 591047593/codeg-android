package app.codeg.android.core.designsystem.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Block-level Markdown for assistant text — the Compose port of iOS
 * `MarkdownContent`. Splits the source into blocks (paragraphs, headings, lists,
 * quotes, fenced code, rules, GFM tables) so an agent's replies render like a
 * chat client. Inline spans within each block go through [rememberInlineMarkdown].
 */
@Composable
fun MarkdownContent(
    raw: String,
    modifier: Modifier = Modifier,
    /**
     * Incremental-parse key for text that grows while it is displayed — a streaming
     * reply or a live thinking block.
     *
     * Without it every delta re-parsed the whole string through [parseMarkdownBlocks]
     * and rebuilt every block composable from scratch. For a long reasoning trace flushed
     * every ~50 ms that is a full re-layout per tick, which reads on screen as the block
     * continuously scrolling or flickering. [LiveBlockParser] keeps the finalized prefix
     * and re-parses only the tail, so each flush costs the new text rather than all of it.
     *
     * Null (the default) keeps the one-shot path for text that does not change.
     */
    incrementalKey: String? = null,
) {
    // Slot-scoped parse only. The shared [MarkdownCache] is reserved for the persisted
    // node-build path; routing streaming callers (e.g. an auto-expanded ReasoningBlock)
    // through it would churn/evict persisted entries with dead growing-tail prefixes.
    val blocks = remember(raw, incrementalKey) {
        if (incrementalKey != null) LiveBlockParser.parse(incrementalKey, raw)
        else parseMarkdownBlocks(raw)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Long-press to select and copy. A coding assistant's replies are mostly text a
        // user wants to lift out — a path, an error, half a diff — and without this the
        // only copyable content in the whole transcript was a fenced code block, which
        // carries its own copy button. Wrapping the whole column rather than each block
        // keeps a drag selecting across paragraph boundaries.
        SelectionContainer {
            for (block in blocks) SingleBlockView(block)
        }
    }
}

/**
 * Renders one already-parsed Markdown block. Extracted from the old private
 * `BlockView` so the session timeline can emit one LazyColumn item per block (a long
 * message is no longer a single non-lazy [Column]); [MarkdownContent] loops over it.
 */
@Composable
internal fun SingleBlockView(block: MarkdownBlock, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    when (block) {
        is MarkdownBlock.Paragraph -> Text(
            text = rememberInlineMarkdown(block.text),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            color = colors.textPrimary,
            modifier = modifier.fillMaxWidth(),
        )

        is MarkdownBlock.Heading -> Text(
            text = rememberInlineMarkdown(block.text),
            fontSize = headingSize(block.level),
            // ~1.25× the size, as iOS sets its title styles; without it the larger sizes
            // crowd their own second line.
            lineHeight = (headingSize(block.level).value * 1.25f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = colors.textPrimary,
            modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        )

        is MarkdownBlock.BulletList -> Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (item in block.items) ListRow(marker = "•", content = item)
        }

        is MarkdownBlock.NumberedList -> Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((marker, content) in block.items) ListRow(marker = marker, content = content)
        }

        is MarkdownBlock.Quote -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.accent.copy(alpha = 0.5f)),
            )
            Text(
                text = rememberInlineMarkdown(block.text),
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontStyle = FontStyle.Italic,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is MarkdownBlock.Code -> CodeBlock(code = block.code, modifier = modifier, language = block.language)

        MarkdownBlock.Rule -> Box(
            modifier.fillMaxWidth().height(0.5.dp).padding(vertical = 2.dp).background(colors.hairline),
        )

        is MarkdownBlock.Table -> MarkdownTable(block, modifier)
    }
}

@Composable
private fun ListRow(marker: String, content: String) {
    val colors = CodegTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = marker,
            // The bullet/number stays a step smaller than the body it introduces: at 17sp
            // the marker competes with the text, and a two-digit "10." no longer fits the
            // gutter the fixed width reserves.
            fontSize = 15.sp,
            color = colors.textTertiary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = rememberInlineMarkdown(content),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState())) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.codeSurface)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp)),
        ) {
            TableRow(table.header, isHeader = true)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline))
            table.rows.forEachIndexed { idx, cells ->
                TableRow(cells, isHeader = false)
                if (idx < table.rows.size - 1) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean) {
    val colors = CodegTheme.colors
    Row {
        for (cell in cells) {
            Text(
                text = rememberInlineMarkdown(cell),
                fontSize = 12.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHeader) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.width(130.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Heading sizes, scaled against the 17sp body.
 *
 * The old ramp topped out at 20sp and bottomed at 14.5sp, which put h3 *below* body text
 * once the body moved to the iOS 17sp — a heading that renders smaller than the paragraph
 * under it is not a heading. Each level now stays clearly above the body, matching the
 * ratio Apple uses for title2 → headline → body.
 */
private fun headingSize(level: Int) = when (level) {
    1 -> 26.sp
    2 -> 22.sp
    3 -> 19.sp
    else -> 17.sp
}
