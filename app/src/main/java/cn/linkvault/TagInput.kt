@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Committed tags stay in Draft; only unfinished input is local state. */
@Composable
internal fun TagInput(vm: VaultViewModel, draft: Draft) {
    var input by rememberSaveable(draft.id) { mutableStateOf("") }
    var expanded by rememberSaveable(draft.id) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val selected = parseTags(draft.tags)
    val selectedKeys = selected.map(Links::tagKey).toSet()
    val common = Links.tagTotals(vm.items).map { it.first }.filter { Links.tagKey(it) !in selectedKeys }

    fun update(tags: List<String>): Boolean {
        val joined = parseTags(tags.joinToString(",")).joinToString(",")
        if (joined.length > 1000) {
            vm.fail("标签长度已达上限")
            return false
        }
        // A focus change must not restore older values of the other editor fields.
        vm.edit((vm.draft ?: draft).copy(tags = joined))
        return true
    }
    fun commit(value: String = input): Boolean {
        if (update(parseTags((vm.draft ?: draft).tags) + parseTags(value))) {
            input = ""
            return true
        }
        return false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.forEach { tag ->
                Surface(onClick = { update(selected.filter { Links.tagKey(it) != Links.tagKey(tag) }) }, enabled = !vm.busy,
                    color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = "移除标签 $tag" }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tag, style = MaterialTheme.typography.labelMedium)
                        Text("×", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        OutlinedTextField(input, { value ->
            if (value.any { it in ",，;；\n" }) commit(value)
            else if ((selected.joinToString(",") + "," + value).length <= 1000) input = value
            else vm.fail("标签长度已达上限")
        }, enabled = !vm.busy, singleLine = true,
            placeholder = { Text("添加标签，回车确认", style = MaterialTheme.typography.bodyLarge) },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                if (focused && !state.isFocused && input.isNotBlank()) commit()
                focused = state.isFocused
            })
        if (common.isNotEmpty()) {
            SectionLabel("常用标签")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (expanded) common else common.take(12)).forEach { tag ->
                    OutlinedButton(onClick = { update(parseTags((vm.draft ?: draft).tags) + tag) }, enabled = !vm.busy,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tag, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!expanded && common.size > 12) TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("更多…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
