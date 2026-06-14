package com.verifyhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("默认行为", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "捕获到验证码后，以下动作自动执行：\n" +
                            "• 复制到剪贴板\n" +
                            "• 弹 Toast 提示\n" +
                            "• 一位一位注入当前焦点输入框（适配 6 格 OTP）\n" +
                            "• 短信不再派发给默认信息 App（无通知），但仍写入 inbox 为已读\n" +
                            "• 邮件 / Voice 通知划掉（邮件本身不动）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("提取测试", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "首页右上角「🧪」进入测试页：\n" +
                            "• 把没被正确识别的短信 / 邮件原文粘进去，点「提取」即可看到\n" +
                            "  命中了哪些关键词、有哪些候选码、各自得分、最终选了谁。\n" +
                            "• 若识别有误，点「复制诊断报告」把报告发给维护者，\n" +
                            "  即可据此调整匹配规则后在下次更新里修复。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("作用范围", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "需要在 LSPosed Manager 里启用作用域：\n" +
                            "• 电话服务 com.android.phone（SMS 捕获 + 副作用大本营）\n" +
                            "• Gmail / Outlook / Google Voice（邮件 / 语音消息）\n" +
                            "其它包名不需要勾，模块不依赖 system_server。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
