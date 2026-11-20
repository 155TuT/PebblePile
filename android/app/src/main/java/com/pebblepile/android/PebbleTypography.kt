package com.pebblepile.android

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal val PebbleWenKai = FontFamily(
    Font(R.font.lxgw_wenkai_regular, FontWeight.Normal),
    Font(R.font.lxgw_wenkai_medium, FontWeight.Medium),
)
private val latinRuns = Regex("""[\p{IsLatin}\p{Nd}]+""")
private val latinStyle = SpanStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
    fontSynthesis = FontSynthesis.None)

private fun styleLatin(value: AnnotatedString): AnnotatedString = AnnotatedString.Builder(value).apply {
    for (run in latinRuns.findAll(value.text)) {
        addStyle(latinStyle, run.range.first, run.range.last + 1)
    }
}.toAnnotatedString()

internal val PebbleEditableText = VisualTransformation {
    TransformedText(styleLatin(it), OffsetMapping.Identity)
}

@Composable
internal fun PebbleText(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current, fontWeight: FontWeight? = null,
) {
    Text(remember(text) { styleLatin(AnnotatedString(text)) }, modifier = modifier,
        color = color, style = style, fontWeight = fontWeight)
}
