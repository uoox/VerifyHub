package com.verifyhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verifyhub.common.CodeExtractor

/**
 * 「提取测试」页：把任意短信/邮件正文粘进来，点「提取」就能看到匹配器
 * 命中了什么、为什么命中、有哪些候选码、各自得分。
 *
 * 当某条验证码没被正确识别时：在这里粘贴 → 提取 → 看诊断 → 「复制诊断报告」，
 * 把报告发给维护者，即可据此调整 [CodeExtractor] 的匹配规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var diagnosis by remember { mutableStateOf<CodeExtractor.Diagnosis?>(null) }

    fun clipboard(): ClipboardManager? = ctx.getSystemService(ClipboardManager::class.java)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提取测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "把没能正确识别的短信 / 邮件原文粘进来，点「提取」查看匹配结果。" +
                    "若识别有误，点「复制诊断报告」发给维护者即可据此调整规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                label = { Text("短信 / 邮件内容") },
                placeholder = { Text("例如：【某App】您的验证码为 123456，5分钟内有效，请勿泄露。") },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { diagnosis = CodeExtractor.diagnose(input) },
                    enabled = input.isNotBlank(),
                ) { Text("提取") }
                OutlinedButton(onClick = {
                    val text = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!text.isNullOrBlank()) {
                        input = text
                        diagnosis = CodeExtractor.diagnose(text)
                    } else {
                        Toast.makeText(ctx, "剪贴板为空", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("粘贴") }
                if (input.isNotBlank() || diagnosis != null) {
                    TextButton(onClick = { input = ""; diagnosis = null }) { Text("清空") }
                }
            }

            diagnosis?.let { ResultCard(it, input) { value ->
                clipboard()?.setPrimaryClip(ClipData.newPlainText("VerifyHub", value))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            } }
        }
    }
}

@Composable
private fun ResultCard(
    d: CodeExtractor.Diagnosis,
    rawInput: String,
    onCopy: (String) -> Unit,
) {
    val hit = d.hit
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hit != null)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // —— 头部结论 ——
            if (hit != null) {
                val kindLabel = if (hit.kind == CodeExtractor.Kind.CODE) "验证码" else "链接"
                Text("✅ 已识别（$kindLabel）", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hit.value,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCopy(hit.value) }) { Text("复制结果") }
                }
            } else {
                Text("❌ 未识别到验证码 / 链接", style = MaterialTheme.typography.titleMedium)
                Text(
                    reasonForMiss(d),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Divider()

            // —— 命中关键词 ——
            Text("命中关键词", style = MaterialTheme.typography.labelLarge)
            Text(
                if (d.matchedKeywords.isEmpty()) "（无）" else d.matchedKeywords.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            // —— 候选码 ——
            Text("候选码（按得分）", style = MaterialTheme.typography.labelLarge)
            if (d.candidates.isEmpty()) {
                Text("（无符合格式的候选）", style = MaterialTheme.typography.bodySmall)
            } else {
                d.candidates
                    .sortedWith(compareByDescending<CodeExtractor.Candidate> { it.chosen }.thenByDescending { it.level })
                    .forEach { c -> CandidateRow(c) }
            }

            // —— 链接 ——
            if (d.links.isNotEmpty()) {
                Text("文本中的链接", style = MaterialTheme.typography.labelLarge)
                d.links.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }

            Divider()

            OutlinedButton(
                onClick = { onCopy(buildReport(rawInput, d)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制诊断报告（发给维护者）") }
        }
    }
}

@Composable
private fun CandidateRow(c: CodeExtractor.Candidate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (c.chosen) "● " else "○ ", fontSize = 12.sp)
        Text(
            c.value,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (c.chosen) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.size(8.dp))
        val dist = if (c.distance < 0) "无关键词" else "距关键词${c.distance}"
        Text(
            "[${CodeExtractor.levelLabel(c.level)} 得分${c.level} · $dist]" +
                if (c.raw != c.value) " 原文「${c.raw}」" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun reasonForMiss(d: CodeExtractor.Diagnosis): String = when {
    d.matchedKeywords.isEmpty() && d.candidates.isEmpty() ->
        "既没命中任何验证码关键词，也没有符合格式的候选码。"
    d.matchedKeywords.isEmpty() ->
        "有候选码，但文本里没命中任何验证码关键词，因此不抓取（防止误判普通数字）。"
    d.candidates.isEmpty() ->
        "命中了关键词，但没找到 4-8 位的候选码格式。"
    else -> "命中了关键词，但候选码未通过筛选。"
}

/** 拼出可直接发给维护者的纯文本诊断报告。 */
private fun buildReport(input: String, d: CodeExtractor.Diagnosis): String = buildString {
    appendLine("VerifyHub 提取诊断报告")
    appendLine("====================")
    appendLine("【原文】")
    appendLine(input)
    appendLine("--------------------")
    val hit = d.hit
    if (hit != null) {
        val kindLabel = if (hit.kind == CodeExtractor.Kind.CODE) "验证码" else "链接"
        appendLine("结果：✅ 已识别（$kindLabel）= ${hit.value}")
    } else {
        appendLine("结果：❌ 未识别 —— ${reasonForMiss(d)}")
    }
    appendLine("命中关键词：" + if (d.matchedKeywords.isEmpty()) "（无）" else d.matchedKeywords.joinToString("、"))
    appendLine("候选码：")
    if (d.candidates.isEmpty()) {
        appendLine("  （无）")
    } else {
        d.candidates.sortedByDescending { it.level }.forEach { c ->
            val mark = if (c.chosen) "← 选中" else ""
            val dist = if (c.distance < 0) "无关键词" else "距关键词${c.distance}"
            val rawNote = if (c.raw != c.value) " 原文「${c.raw}」" else ""
            appendLine("  • ${c.value}  [${CodeExtractor.levelLabel(c.level)} 得分${c.level} · $dist]$rawNote $mark")
        }
    }
    if (d.links.isNotEmpty()) {
        appendLine("链接：")
        d.links.forEach { appendLine("  • $it") }
    }
    appendLine("====================")
    appendLine("（若识别有误，请把本报告连同期望的正确结果一起发给维护者。）")
}
