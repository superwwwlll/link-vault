@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.linkvault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        if (selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            selected.forEach { tag ->
                TagChip(
                    tag,
                    onClick = { update(selected.filter { Links.tagKey(it) != Links.tagKey(tag) }) },
                    trailing = "×",
                    maxLines = 2,
                    description = "移除标签 $tag",
                    enabled = !vm.busy,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        }
        TextField(input, { value ->
            if (value.any { it in ",，;；\n" }) commit(value)
            else if ((selected.joinToString(",") + "," + value).length <= 1000) input = value
            else vm.fail("标签长度已达上限")
        }, enabled = !vm.busy, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 26.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            shape = RoundedCornerShape(0.dp),
            colors = readingLineColors(),
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                if (focused && !state.isFocused && input.isNotBlank()) commit()
                focused = state.isFocused
            })
        if (common.isNotEmpty()) {
            Text("常用标签", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                (if (expanded) common else common.take(12)).forEach { tag ->
                    TagChip(tag, onClick = { update(parseTags((vm.draft ?: draft).tags) + tag) }, enabled = !vm.busy)
                }
                if (!expanded && common.size > 12) TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("更多…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
