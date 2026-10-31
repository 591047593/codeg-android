package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.security.ConfigSyncCrypto
import app.codeg.android.core.security.ConfigSyncException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject

/**
 * Double-phone config sync (batch v142): encrypted-text export / import of the
 * server list. Entirely local — no network, no server API, no file access;
 * the ciphertext travels via the system clipboard only.
 */
@HiltViewModel
class ConfigSyncViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    @Serializable
    data class PayloadServer(
        val server_id: String,
        val name: String,
        val base_url: String,
        val token: String,
        val visible: Boolean = true,
        val include_in_all: Boolean = true,
    )

    @Serializable
    data class Payload(
        val format: String = "codeg-mobile-config",
        val schema_version: Int = 1,
        val exported_at: String,
        val servers: List<PayloadServer>,
    )

    data class ImportCandidate(
        val payload: PayloadServer,
        val existsLocally: Boolean,
        val localName: String?,
        /** Include in the import. New entries default YES, conflicts default NO
         * (keep local) — both user-changeable. */
        val useImported: Boolean,
    )

    data class UiState(
        val exportBusy: Boolean = false,
        val exportError: String? = null,
        val exportText: String? = null,
        val importBusy: Boolean = false,
        val importError: String? = null,
        val preview: List<ImportCandidate>? = null,
        val importResult: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    fun export(password: CharArray, onCopied: (String, Int) -> Unit) {
        if (_ui.value.exportBusy) return
        if (password.size < 12) {
            _ui.value = _ui.value.copy(exportError = "临时密码至少 12 位")
            return
        }
        _ui.value = _ui.value.copy(exportBusy = true, exportError = null, exportText = null)
        viewModelScope.launch {
            try {
                var skipped = 0
                val text = withContext(Dispatchers.Default) {
                    val all = repository.profiles.first()
                    val total = all.size
                    val servers = all.mapNotNull { profile ->
                        // A profile without a token can't restore a connection —
                        // export only complete entries.
                        repository.token(profile.id)?.let { token ->
                            PayloadServer(
                                server_id = profile.id, name = profile.name,
                                base_url = profile.baseUrl, token = token,
                                visible = profile.visible, include_in_all = profile.includeInAll,
                            )
                        }
                    }
                    if (servers.isEmpty()) throw ConfigSyncException("没有可导出的服务器配置")
                    skipped = total - servers.size
                    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
                    val payload = json.encodeToString(
                        Payload.serializer(),
                        Payload(exported_at = Instant.now().toString(), servers = servers),
                    )
                    ConfigSyncCrypto.encrypt(password, payload.toByteArray(Charsets.UTF_8))
                }
                _ui.value = _ui.value.copy(
                    exportBusy = false,
                    exportText = text,
                    exportError = if (skipped > 0) "注意：有 $skipped 台未配置 Token 的设备已跳过" else null,
                )
                onCopied(text, skipped)
            } catch (e: ConfigSyncException) {
                _ui.value = _ui.value.copy(exportBusy = false, exportError = e.message)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(exportBusy = false, exportError = "导出失败")
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun previewImport(text: String, password: CharArray) {
        if (_ui.value.importBusy) return
        _ui.value = _ui.value.copy(importBusy = true, importError = null, importResult = null)
        viewModelScope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    val plain = ConfigSyncCrypto.decrypt(password, text)
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString(Payload.serializer(), plain.decodeToString())
                }.let { parsed ->
                    // Schema + field validation BEFORE any local write (go.md §8.3).
                    if (parsed.format != "codeg-mobile-config" || parsed.schema_version != 1) {
                        throw ConfigSyncException("不支持的数据格式")
                    }
                    parsed.servers.forEach { s ->
                        require(s.server_id.isNotBlank() && s.base_url.isNotBlank() && s.token.isNotBlank()) { "缺少必要字段" }
                    }
                    if (parsed.servers.size > 200) throw ConfigSyncException("设备数量超出限制")
                    parsed
                }
                val locals = repository.profiles.first().associateBy { it.id }
                _ui.value = _ui.value.copy(
                    importBusy = false,
                    preview = payload.servers.map { s ->
                        val local = locals[s.server_id]
                        ImportCandidate(s, local != null, local?.name, useImported = local == null)
                    },
                )
            } catch (e: ConfigSyncException) {
                _ui.value = _ui.value.copy(importBusy = false, importError = e.message)
            } catch (e: IllegalArgumentException) {
                _ui.value = _ui.value.copy(importBusy = false, importError = "数据校验失败：${e.message ?: "字段缺失"}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(importBusy = false, importError = "导入失败：数据无效")
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun toggleUse(serverId: String) {
        val preview = _ui.value.preview ?: return
        _ui.value = _ui.value.copy(preview = preview.map {
            if (it.payload.server_id == serverId) it.copy(useImported = !it.useImported) else it
        })
    }

    /**
     * Applies the confirmed import. Validation already happened in
     * [previewImport]; writes are sequential and each is a full upsert, so a
     * failure aborts the rest and reports exactly what landed.
     */
    fun applyImport() {
        if (_ui.value.importBusy) return
        val preview = _ui.value.preview ?: return
        val chosen = preview.filter { it.useImported }
        if (chosen.isEmpty()) {
            _ui.value = _ui.value.copy(preview = null, importResult = "没有选择要导入的服务器")
            return
        }
        _ui.value = _ui.value.copy(importBusy = true, importError = null)
        viewModelScope.launch {
            var applied = 0
            var failure: String? = null
            for (c in chosen) {
                try {
                    repository.save(
                        app.codeg.android.core.datastore.ServerProfile(
                            id = c.payload.server_id,
                            name = c.payload.name,
                            baseUrl = c.payload.base_url,
                            visible = c.payload.visible,
                            includeInAll = c.payload.include_in_all,
                        ),
                        c.payload.token,
                    )
                    applied++
                } catch (e: Exception) {
                    failure = "前 $applied 台已写入，${c.payload.name} 写入失败，其余未导入；该服务器 Token 可能已部分覆盖，请重新编辑"
                    break
                }
            }
            // Warn when an imported entry replaced the currently selected device —
            // that device's URL/token silently changed.
            val selectedId = repository.selectedId.first()
            val overwroteSelected = failure == null && chosen.any { it.useImported && it.existsLocally && it.payload.server_id == selectedId }
            _ui.value = _ui.value.copy(
                importBusy = false,
                preview = null,
                importResult = (failure ?: "已导入 $applied 台服务器") +
                    (if (overwroteSelected) "（注意：当前选中设备的配置已被导入覆盖）" else ""),
            )
        }
    }

    fun copyFailed() {
        _ui.value = _ui.value.copy(exportError = "复制到剪贴板失败，请重试")
    }

    fun resetExport() {
        _ui.value = _ui.value.copy(exportText = null, exportError = null)
    }

    fun resetImport() {
        _ui.value = _ui.value.copy(preview = null, importError = null, importResult = null)
    }
}
