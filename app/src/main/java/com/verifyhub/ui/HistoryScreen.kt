package com.verifyhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verifyhub.data.AppDatabase
import com.verifyhub.data.CodeRecord
import com.verifyhub.data.Source
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember(ctx) { AppDatabase.get(ctx).codeRecords() }
    val records by dao.recent().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("验证码") },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { scope.launch { dao.clear() } }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding -> HistoryContent(padding, records, dao = dao, ctx = ctx, scope = scope) }
}

@Composable
private fun HistoryContent(
    padding: PaddingValues,
    records: List<CodeRecord>,
    dao: com.verifyhub.data.CodeRecordDao,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "等待短信 / Gmail / Outlook / Google Voice 触发验证码…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(records, key = { it.id }) { rec ->
            RecordRow(
                rec,
                onCopy = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("VerifyHub", rec.value))
                },
                onDelete = { scope.launch { dao.delete(rec.id) } },
            )
        }
    }
}

@Composable
private fun RecordRow(rec: CodeRecord, onCopy: () -> Unit, onDelete: () -> Unit) {
    val kindLabel = when (rec.kind) {
        "CODE" -> "验证码"
        "LINK" -> "链接"
        else -> rec.kind
    }
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 顶部：来源 chip + 时间 + 操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text("${sourceLabel(rec.source)} · $kindLabel", fontSize = 12.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    formatTime(rec.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除")
                }
            }
            // 验证码主体（大字、等宽）
            Text(
                rec.value,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
            // 发件人 / 预览
            if (!rec.sender.isNullOrBlank()) {
                Text(
                    rec.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rec.preview.isNotBlank()) {
                Text(
                    rec.preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceLabel(name: String): String = when (Source.entries.firstOrNull { it.name == name }) {
    Source.SMS -> "短信"
    Source.GOOGLE_VOICE -> "Google Voice"
    Source.GMAIL -> "Gmail"
    Source.OUTLOOK -> "Outlook"
    Source.UNKNOWN, null -> "未知"
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun formatTime(ts: Long): String = timeFmt.format(Date(ts))
