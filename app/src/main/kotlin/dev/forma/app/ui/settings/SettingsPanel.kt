@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forma.app.ui.FormaButton as Button
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.settings.*

/** Stateless preference boundary. Navigation/search are saved UI state; no native work is owned here. */
@Composable fun SettingsPanel(
    draft: SettingsDraft, appDefaults: PreferenceValues, jobScope: Boolean,
    onValuesChanged: (PreferenceValues) -> Unit, onSave: () -> Unit,
    onDiscard: () -> Unit, onClose: () -> Unit,
    busy: Boolean = false, error: String? = null, canSave: Boolean = true, backRequest: Int = 0,
    runControls: @Composable () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var resetIds by remember { mutableStateOf<List<String>?>(null) }
    val category = categoryId?.let(SettingCategory::valueOf)
    val resolved = remember(draft.values, appDefaults, jobScope) {
        SettingsResolver.resolve(if (jobScope) appDefaults else draft.values,
            job=if (jobScope) draft.values else PreferenceValues.EMPTY)
    }
    val categories = SettingCategory.entries.filter { c -> SettingCatalog.all.any { it.category == c && (!jobScope || it.scope == SettingScope.JOB) } }
    val matches = remember(query, filter, draft.values, jobScope) {
        SettingCatalog.search(query).filter { (!jobScope || it.scope == SettingScope.JOB) &&
            when (filter) { "Changed" -> draft.values[it.id] != null; "Planned" -> !it.wired; else -> true } }
    }
    val compact = (resolved.getValue("ui.density").value as SettingValue.Choice).value == "compact"
    val technical = (resolved.getValue("ui.technical_details").value as SettingValue.Choice).value == "always"
    fun back() {
        if (busy) return
        when { selectedId != null -> selectedId = null; query.isNotBlank() -> query = ""
            category != null -> categoryId = null; else -> onClose() }
    }
    BackHandler { back() }
    // Dialog window Back reaches onDismissRequest, not necessarily the Activity dispatcher.
    LaunchedEffect(backRequest) { if (backRequest > 0) back() }
    Scaffold(
        topBar = { Surface(tonalElevation=1.dp) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={ back() }, enabled=!busy) { Text("Back") }
                    TextButton(enabled=!busy && draft.values.entries.isNotEmpty(), onClick={
                        resetIds=draft.values.entries.keys.filter { category == null || SettingCatalog[it].category == category }
                    }) { Text(if (category == null) "Reset all" else "Reset section") }
                }
                Text(category?.title ?: "Settings", style=MaterialTheme.typography.headlineSmall)
                Text(if (jobScope) "This editor draft" else "App defaults",
                    style=MaterialTheme.typography.labelMedium, modifier=Modifier.padding(bottom=12.dp))
            }
        } },
        bottomBar={ Column {
            runControls()
            if (draft.dirty) Surface(tonalElevation=3.dp) {
            Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=onDiscard, enabled=!busy, modifier=Modifier.weight(1f)) { Text("Discard") }
                Button(onClick=onSave, enabled=!busy && canSave, modifier=Modifier.weight(1f)) {
                    Text(if (busy) "Saving…" else if (jobScope) "Apply to job" else "Save defaults")
                }
            }
        } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp)) {
            OutlinedTextField(value=query, onValueChange={ query=it }, singleLine=true,
                label={ Text("Search settings") }, modifier=Modifier.fillMaxWidth().testTag("settings-search"))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("All", "Changed", "Planned").forEach { name ->
                    FilterChip(selected=filter == name, onClick={ filter=name }, modifier=Modifier.heightIn(min=52.dp),
                        label={ Text(if (name == "Changed") "Changed (${draft.values.entries.size})" else name) })
                }
            }
            error?.let { Text(it, color=MaterialTheme.colorScheme.error, modifier=Modifier.padding(vertical=8.dp)) }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 800.dp && LocalDensity.current.fontScale <= 1.6f
                val showResults = query.isNotBlank() || filter != "All"
                Row(Modifier.fillMaxSize()) {
                    if (wide || category == null && !showResults) {
                        LazyColumn(if (wide) Modifier.width(250.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                            items(categories, key={ it.name }) { item ->
                                val count = matches.count { it.category == item }
                                ListItem(headlineContent={ Text(item.title) }, supportingContent={ Text("$count settings") },
                                    trailingContent={ Text("›") }, modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)
                                        .clickable(role=Role.Button) { categoryId=item.name })
                            }
                        }
                    }
                    if (wide || category != null || showResults) {
                        val rows = if (showResults) matches else matches.filter { it.category == (category ?: categories.first()) }
                        LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding=PaddingValues(bottom=20.dp)) {
                            if (category == SettingCategory.ENGINE) item(key="cpu-action") {
                                TextButton(enabled=!busy, onClick={ onValuesChanged(NativePreferences.cpuOnly(draft.values)) }) {
                                    Text("Use CPU for the whole pipeline")
                                }
                                Text("Previews three overrides below; nothing changes until you apply.", style=MaterialTheme.typography.bodySmall)
                            }
                            if (jobScope) item(key="scope-help") {
                                Text("Current editor values are explicit overrides. Reset a field to inherit its app default. Queued jobs are unchanged.",
                                    style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(vertical=8.dp))
                            }
                            if (rows.isEmpty()) item(key="no-results") { Text("No matching settings.", Modifier.padding(16.dp)) }
                            items(rows, key={ it.id }) { spec ->
                                val value = resolved.getValue(spec.id)
                                val provenance = when (value.origin) {
                                    ValueOrigin.FACTORY -> "Factory default"; ValueOrigin.APP -> "App default"
                                    ValueOrigin.PRESET -> "Preset"; ValueOrigin.JOB -> "This job override"
                                }
                                Column(Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("settings-row:${spec.id}")
                                    .clickable(enabled=!busy, role=Role.Button) { keyboard?.hide(); selectedId=spec.id }
                                    .padding(horizontal=12.dp, vertical=if (compact) 8.dp else 14.dp)) {
                                    Text(spec.label, style=MaterialTheme.typography.titleSmall)
                                    Text(spec.display(value.value), style=MaterialTheme.typography.bodyLarge)
                                    Text(if (spec.wired) provenance else "Planned · $provenance", style=MaterialTheme.typography.labelMedium,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (showResults) Text(spec.category.title, style=MaterialTheme.typography.bodySmall)
                                    if (technical) Text(spec.id, style=MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    selectedId?.let { id ->
        SettingSheet(SettingCatalog[id], resolved.getValue(id).value, jobScope,
            onChange={ onValuesChanged(draft.values.with(id,it)); selectedId=null },
            onReset={ onValuesChanged(draft.values.without(id)); selectedId=null }, onClose={ selectedId=null })
    }
    resetIds?.let { ids -> AlertDialog(onDismissRequest={ resetIds=null }, title={ Text("Remove these overrides?") },
        text={ LazyColumn(Modifier.heightIn(max=300.dp)) { items(ids) { Text(SettingCatalog[it].label, Modifier.padding(vertical=4.dp)) } } },
        confirmButton={ TextButton(onClick={
            var next=draft.values; ids.forEach { next=next.without(it) }; onValuesChanged(next); resetIds=null
        }) { Text("Reset") } }, dismissButton={ TextButton(onClick={ resetIds=null }) { Text("Keep settings") } }) }
}

@Composable private fun SettingSheet(spec: SettingSpec, value: SettingValue, jobScope: Boolean,
    onChange: (SettingValue) -> Unit, onReset: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest=onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).navigationBarsPadding()) {
            Text(spec.label, style=MaterialTheme.typography.titleLarge)
            Text(spec.help, Modifier.padding(vertical=8.dp), style=MaterialTheme.typography.bodyMedium)
            Text(spec.id, style=MaterialTheme.typography.labelSmall)
            if (!spec.wired) Text("Planned — not active in exports.", Modifier.padding(vertical=8.dp))
            when (value) {
                is SettingValue.Choice -> LazyColumn(Modifier.fillMaxWidth().heightIn(max=400.dp)) {
                    items(spec.options, key={ it.id }) { option ->
                        val enabled = spec.wired && option.available
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("settings-option:${spec.id}:${option.id}")
                            .selectable(selected=value.value == option.id, enabled=enabled, role=Role.RadioButton,
                                onClick={ onChange(SettingValue.Choice(option.id)) }), verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=value.value == option.id, onClick=null, enabled=enabled)
                            Text(option.label + if (!enabled) " · Planned" else "", Modifier.padding(start=12.dp))
                        }
                    }
                }
                is SettingValue.Flag -> Row(Modifier.fillMaxWidth().heightIn(min=56.dp), verticalAlignment=Alignment.CenterVertically) {
                    Text(if (value.value) "On" else "Off", Modifier.weight(1f))
                    Switch(checked=value.value, onCheckedChange={ onChange(SettingValue.Flag(it)) }, enabled=spec.wired)
                }
                else -> NumericOrTextEditor(spec, value, onChange)
            }
            TextButton(onClick=onReset) { Text(if (jobScope) "Use inherited value" else "Use factory default") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun NumericOrTextEditor(spec: SettingSpec, value: SettingValue, onChange: (SettingValue) -> Unit) {
    var text by rememberSaveable(spec.id) { mutableStateOf(when (value) {
        is SettingValue.Integer -> value.value.toString(); is SettingValue.Decimal -> value.value.toString()
        is SettingValue.Text -> value.value; else -> ""
    }) }
    val parsed = when (value) {
        is SettingValue.Integer -> text.toLongOrNull()?.let(SettingValue::Integer)
        is SettingValue.Decimal -> text.toDoubleOrNull()?.let(SettingValue::Decimal)
        is SettingValue.Text -> SettingValue.Text(text)
        else -> null
    }
    val error = parsed?.let(spec::error) ?: if (parsed == null) "Enter a valid value" else null
    OutlinedTextField(value=text, onValueChange={ text=it }, enabled=spec.wired,
        keyboardOptions=KeyboardOptions(keyboardType=if (value is SettingValue.Text) KeyboardType.Text else KeyboardType.Decimal),
        label={ Text("Value") }, isError=error != null, modifier=Modifier.fillMaxWidth().padding(top=8.dp))
    if (error != null) Text(error, color=MaterialTheme.colorScheme.error)
    if (spec.allowedIntegers.isNotEmpty()) Text("Choices: ${spec.allowedIntegers.joinToString()}", style=MaterialTheme.typography.bodySmall)
    Button(onClick={ parsed?.let(onChange) }, enabled=spec.wired && parsed != null && error == null,
        modifier=Modifier.fillMaxWidth().padding(top=12.dp)) { Text("Use value") }
}

/** Cancellation stays independent of settings load/save/validation and does not collect raw progress. */
@Composable fun SettingsRunControls(running: Boolean, stopping: Boolean, onStop: () -> Unit) {
    if (running) Surface(tonalElevation=4.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp, vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text(if (stopping) "Stopping safely…" else "Conversion is running", Modifier.weight(1f),
                style=MaterialTheme.typography.labelLarge)
            TextButton(enabled=!stopping, onClick=onStop, modifier=Modifier.testTag("settings-stop")) { Text("Stop") }
        }
    }
}
