package dev.forma.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.forma.core.UploadFit
import java.math.BigDecimal

/** Decimal byte intent is explicit; manual mode retains the chosen quality settings. */
@Composable fun ByteLimitControl(targetBytes: Long?, onChange: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("10") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Box {
        FormaOutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().testTag("size-limit")) {
            Text(targetBytes?.let { "Limit · ${BigDecimal(it).movePointLeft(6).stripTrailingZeros().toPlainString()} MB" } ?: "No size limit")
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            for (mb in listOf(10,20,25,50,100,500)) DropdownMenuItem(text={Text("$mb MB")},onClick={expanded=false;onChange(mb*1_000_000L)})
            DropdownMenuItem(text={Text("Custom limit")},onClick={expanded=false;custom=true;error=null})
            DropdownMenuItem(text={Text("No size limit")},onClick={expanded=false;onChange(null)})
        }
        }
        if(targetBytes != null) Text("Bitrate adjusts to fit.", style=MaterialTheme.typography.bodySmall)
    }
    if(custom) AlertDialog(onDismissRequest={custom=false}, title={Text("Limit in MB")}, text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=text,onValueChange={text=it;error=null},singleLine=true,label={Text("MB")})
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={FormaTextButton(onClick={
        val parsed=runCatching { BigDecimal(text).movePointRight(6).longValueExact().also(UploadFit::validateTarget) }
        parsed.onSuccess { custom=false;onChange(it) }.onFailure { error="Use 0.032–2,000 MB with whole-byte precision." }
    }) { Text("Set limit") }},dismissButton={FormaTextButton(onClick={custom=false}) { Text("Cancel") }})
}
