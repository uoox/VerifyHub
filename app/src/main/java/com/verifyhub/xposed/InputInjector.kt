package com.verifyhub.xposed

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * 把一段文本以"虚拟键盘按键"的形式注入到当前焦点窗口。
 *
 * 移植自 tianma8023/XposedSmsCode 的 `InputHelper`，思路：
 *   1) 用 [KeyCharacterMap.VIRTUAL_KEYBOARD] 把字符序列转成 [KeyEvent]。
 *   2) 调 [InputManager.injectInputEvent]（hidden API）派发事件。
 *
 * 调用方必须运行在拥有 `android.permission.INJECT_EVENTS` 的进程中：
 *   - `system_server`（system uid，天然有此权限）  ← 我们用这个
 *   - `com.android.phone` + PermissionGranter 给它授权的方案（XposedSmsCode 用）
 *
 * 注入的 KeyEvent 会进入 Android 输入子系统，自动落到当前焦点窗口的 EditText
 * 上，**和目标 App 是哪个无关**，所以一处注入覆盖所有 App，省去对每个 App
 * 单独 Hook。
 */
object InputInjector {

    /** 调用前需先在 system_server 进程里完成至少一次 hook 初始化。 */
    fun sendText(context: Context?, text: String): Boolean {
        if (text.isEmpty()) return false
        val im = resolveInputManager(context) ?: run {
            Logger.w("InputInjector: InputManager unavailable")
            return false
        }
        val kcm = try {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        } catch (t: Throwable) {
            Logger.w("InputInjector: KeyCharacterMap.load failed", t); return false
        }
        val events: Array<KeyEvent>? = kcm.getEvents(text.toCharArray())
        if (events.isNullOrEmpty()) {
            Logger.w("InputInjector: no KeyEvents for text (unprintable chars?)")
            return false
        }
        val mode = injectModeConstant() ?: return false
        var ok = true
        for (e in events) {
            val src = if (e.source != InputDevice.SOURCE_KEYBOARD) {
                KeyEvent(e).apply { source = InputDevice.SOURCE_KEYBOARD }
            } else e
            ok = injectOne(im, src, mode) && ok
        }
        return ok
    }

    /**
     * 找到当前进程的 InputManager 实例。Android 14 起 `getInstance()` 不可
     * 见，所以优先用 Context 系统服务；Context 拿不到再回退到反射。
     */
    private fun resolveInputManager(ctx: Context?): InputManager? {
        if (ctx != null) {
            val svc = ctx.getSystemService(Context.INPUT_SERVICE)
            if (svc is InputManager) return svc
        }
        return try {
            val m = InputManager::class.java.getDeclaredMethod("getInstance")
            m.isAccessible = true
            m.invoke(null) as? InputManager
        } catch (t: Throwable) {
            Logger.w("InputInjector: reflective getInstance failed", t); null
        }
    }

    private fun injectModeConstant(): Int? = try {
        val f = InputManager::class.java.getDeclaredField("INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH")
        f.isAccessible = true
        f.getInt(null)
    } catch (t: Throwable) {
        Logger.w("InputInjector: cannot read INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH", t); null
    }

    private fun injectOne(im: InputManager, event: KeyEvent, mode: Int): Boolean = try {
        val m = InputManager::class.java.getDeclaredMethod(
            "injectInputEvent", KeyEvent::class.java, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        m.invoke(im, event, mode) as? Boolean ?: true
    } catch (t: Throwable) {
        Logger.w("InputInjector: injectInputEvent failed for keyCode=${event.keyCode}", t)
        false
    }

    /** 方便从其它进程触发的"睡 X 毫秒再注入"包装。 */
    fun sendTextDelayed(context: Context?, text: String, delayMs: Long): Boolean {
        if (delayMs <= 0) return sendText(context, text)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < delayMs) {
            try { Thread.sleep(50) } catch (_: InterruptedException) {}
        }
        return sendText(context, text)
    }
}
