package app.codeg.android.feature.quick

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.IdeaStore
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The ball's 记录想法 sheet (批v150 §1.4): title optional (auto-named
 * 想法 MM-dd HH:mm), description free — no device/project required. Saves to
 * [IdeaStore]; the idea surfaces in the inbox tab like any other draft.
 */
@HiltViewModel
class QuickIdeaViewModel @Inject constructor(
    private val ideaStore: IdeaStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** One-shot "saved" event the sheet closes on. */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 双击防抖：一次保存只落一条草稿（UUID 各异、内容相同的两条只会添乱）。
    private val saving = java.util.concurrent.atomic.AtomicBoolean(false)

    fun save(title: String, description: String) {
        if (title.isBlank() && description.isBlank()) return
        if (!saving.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val autoTitle = appContext.getString(
                    R.string.quick_idea_auto_title,
                    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(LocalDateTime.now()),
                )
                val entry = IdeaEntry(
                    id = UUID.randomUUID().toString(),
                    title = title.trim().ifBlank { autoTitle },
                    description = description.trim(),
                    createdAt = now,
                    updatedAt = now,
                )
                try {
                    ideaStore.upsert(entry)
                    _saved.value = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _error.value = "想法保存失败（本地存储异常）"
                }
            } finally {
                saving.set(false)
            }
        }
    }

    fun consumeSaved() {
        _saved.value = false
    }

    /** The VM is activity-scoped and outlives the sheet — clear last open's
     *  error so a fresh sheet doesn't greet the user with old news. */
    fun clearError() {
        _error.value = null
    }
}

/**
 * Quick idea editor. ≤2-tap acceptance path: ball tap → panel card → this sheet;
 * 保存 lands the idea in the inbox. Drag-lock per the v148 sheet contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickIdeaSheet(
    onDismiss: () -> Unit,
    viewModel: QuickIdeaViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val emptyHint = stringResource(R.string.quick_idea_empty_hint)

    LaunchedEffect(saved) {
        if (saved) {
            viewModel.consumeSaved()
            onDismiss()
        }
    }
    // VM 比 sheet 长寿（activity 作用域）——清掉上次打开残留的错误行。
    LaunchedEffect(Unit) { viewModel.clearError() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.quick_idea_title),
                style = MaterialTheme.typography.titleMedium,
                color = CodegTheme.colors.textPrimary,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.quick_idea_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.quick_idea_desc_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Text(error!!, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                PrimaryButton(
                    text = stringResource(R.string.common_save),
                    onClick = { viewModel.save(title, description) },
                    enabled = title.isNotBlank() || description.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (title.isBlank() && description.isBlank()) {
                Text(emptyHint, style = MaterialTheme.typography.labelSmall, color = CodegTheme.colors.textTertiary)
            }
        }
    }
}
