# VerifyHub · 验证码聚合

一个 LSPosed / Xposed 模块：自动从 **短信 / Gmail / Outlook / Google Voice** 里识别验证码与验证链接，
复制到剪贴板、Toast 提示、并按位注入当前 OTP 输入框。

## 功能

- 多来源捕获：系统短信、Gmail、Outlook、Google Voice。
- 智能提取：基于关键词 + 候选码评分的规则引擎（见 `CodeExtractor`）。
- 自动副作用：复制剪贴板、Toast、逐位填入聚焦的输入框。
- 历史记录：本地 Room 数据库，支持复制 / 删除 / 清空。
- **提取测试**（v0.3.0 新增）：手动粘贴短信原文，实时查看规则命中情况，识别有误时一键导出诊断报告。

## 匹配规则（CodeExtractor）

提取分两条通道：

1. **验证码通道**：必须先命中「验证码类」关键词（`验证码 / verification code / OTP / 인증번호 …`），
   再在文本里找所有候选码，按 `(匹配度, 距最近关键词的距离)` 排序取最优。
   - 匹配度：`6位纯数字 > 4位纯数字 > 其它纯数字 > 数字字母混合`。
   - 支持格式：连续码 `123456`、字母前缀码 `G-123456`、分组码 `123-456` / `123 456`。
   - 防误判：邮箱 / URL 提前涂白；掩码手机号 `138****8888`、IP `192.168.x.x`、
     日期 `2024-01-01`、纯字母单词均不会被当成验证码；
     英文裸词 `code` 前若是 `promo / area / zip` 等干扰词则不计关键词。
2. **链接通道**：命中 `verify / confirm / magic / token …` 的 URL 直接作为验证链接返回，不依赖关键词。

规则覆盖简体 / 繁体中文、英文、日语、韩语、西/法/德/葡/意常见写法。

## 提取测试 & 反馈闭环

当某条验证码**没被正确识别**时：

1. 打开 App → 首页右上角 **🧪 提取测试**。
2. 把短信 / 邮件原文**粘贴**进去，点 **提取**。
3. 页面会摊开整个匹配过程：命中的关键词、所有候选码及其得分、最终选了谁、识别到的链接。
4. 若结果有误，点 **复制诊断报告**，把报告（含原文）发给维护者。
5. 维护者据此在 `CodeExtractor` 调整规则，并在 `CodeExtractorTest` 补一条回归用例，下次更新即修复。

> 诊断报告是纯文本，包含原文、命中关键词、候选码评分。发送前请自行打码敏感信息（如真实验证码、手机号）。

## 构建

```bash
./gradlew :app:assembleDebug      # 打包模块 APK
./gradlew :app:testDebugUnitTest  # 跑 CodeExtractor 规则单元测试
```

安装后在 **LSPosed Manager** 启用模块，并勾选作用域：

- `com.android.phone`（短信捕获 + 副作用执行）
- `com.google.android.gm` / `com.microsoft.office.outlook` / `com.google.android.apps.googlevoice`

## 版本

- **v0.3.2** — 修复邮件 / Google Voice 抓到验证码却不复制到剪贴板：
  - 根因：邮件/Voice 的抓取发生在 Gmail / Outlook / Google Voice 进程，这些第三方进程受
    Android 11+ 包可见性限制**看不到本模块的 `HistoryProvider`**（即便 `exported=true`），
    `ContentResolver.insert` 会抛 `IllegalArgumentException: Unknown URL`。而触发副作用的
    `ACTION_NEW_CODE` 广播原本是在 `HistoryProvider.insert` 内部发出的，insert 没到达 provider，
    广播就永不触发，于是邮件/Voice 的剪贴板 / Toast / 注入全都不生效（只有和 `com.android.phone`
    同进程直调的 SMS 路径可用）。`android:forceQueryable` 对普通用户安装的应用不被系统采纳。
  - 修复：邮件/Voice 的 `NotificationHook` 改为把验证码**直接定向广播给 `com.android.phone`**
    （forceQueryable 的平台 system app，对任意进程可见），由 `PhoneBroadcastHook` 统一做副作用
    （剪贴板 / Toast / 注入——都需 uid 1001 特权）**并代为落库**（system app 不受包可见性过滤，
    能正常 acquire 本 provider）。
  - 安全：发送方是第三方 app 进程、拿不到本模块签名级权限，故不再用 signature 权限限定广播，
    改为「编译期内置令牌 `IPC_TOKEN` + 显式 `setPackage` 定向投递」，接收方校验令牌后才动手，
    挡掉第三方伪造广播驱动按键注入 / 截获验证码。
- **v0.3.1** — 修复 & 加固：
  - 修复同一条短信被重复注入的问题（`InboundSmsHandler.dispatchIntent` 会为
    `SMS_DELIVER` / `SMS_RECEIVED` 各派发一次，个别 OEM 子类重写还会再多一次，
    导致验证码被向输入框打两遍、Toast 弹两次）。副作用改为按「值 + 时间窗」去重兜底。
  - 加固 `ACTION_NEW_CODE` 广播：接收方加签名级权限校验，发送方显式定向到
    `com.android.phone`，防止第三方应用伪造广播驱动按键注入、或截获明文验证码。
  - 收紧链接通道命中词，剔除 `auth / secure / account / reset / login` 等过宽词，
    避免把普通账户/营销邮件误判成验证链接进而误划掉其通知。
- v0.3.0 — 匹配规则增强（更多关键词 / 前缀码 / 分组码 / 反误判），新增「提取测试」诊断页与单元测试。
- v0.2.0 — Android 16 上可用的短信拦截 + 自动填充。
