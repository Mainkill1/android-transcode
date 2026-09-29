package dev.forma.app.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Enlarge the component itself, not only the padding outside its visual bounds. */
internal fun Modifier.formaTouchTarget(): Modifier = sizeIn(minWidth = 52.dp, minHeight = 52.dp)

@Composable internal fun FormaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.Button(onClick, modifier.formaTouchTarget(), enabled, content = content)

@Composable internal fun FormaTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.TextButton(onClick, modifier.formaTouchTarget(), enabled, content = content)

@Composable internal fun FormaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.OutlinedButton(onClick, modifier.formaTouchTarget(), enabled, content = content)

@Composable internal fun FormaIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) = androidx.compose.material3.IconButton(onClick, modifier.formaTouchTarget(), enabled, content = content)
