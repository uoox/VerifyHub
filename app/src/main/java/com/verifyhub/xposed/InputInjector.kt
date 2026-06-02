package com.verifyhub.xposed

import android.content.Context
import android.hardware.input.InputManager
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

    /**
     * 把 [text] 一字符一字符地注入到当前焦点框。
     *
     * 关于注入入口的演变：
     *   - Android ≤ 13: `InputManager.injectInputEvent(InputEvent, int)` 可反射
     *   - Android 14+: 该方法被搬到 `android.hardware.input.InputManagerGlobal`，
     *     `InputManager` 上的同名方法被删/隐藏。这里通过 [Injector] 自适应。
     *
     * 关于 per-char delay：很多网站/App（GitHub、Apple ID、Google 等）把 OTP 输入
     * 做成 6 个独立格子，每格只接一位、onChange 时 focus 自动跳到下一格。一口气
     * 派完会丢字，所以每位之间插 [perCharDelayMs] 毫秒空档。
     */
    fun sendText(context: Context?, text: String, perCharDelayMs: Long = 100L): Boolean {
        if (text.isEmpty()) return false
        val inj = Injector.resolve(context) ?: run {
            Logger.w("InputInjector: no usable injectInputEvent method found on this Android")
            return false
        }
        val kcm = try {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        } catch (t: Throwable) {
            Logger.w("InputInjector: KeyCharacterMap.load failed", t); return false
        }
        var ok = true
        text.forEachIndexed { idx, ch ->
            val events: Array<KeyEvent>? = kcm.getEvents(charArrayOf(ch))
            if (events.isNullOrEmpty()) {
                Logger.w("InputInjector: no KeyEvents for '$ch'")
                ok = false
                return@forEachIndexed
            }
            for (e in events) {
                val src = if (e.source != InputDevice.SOURCE_KEYBOARD) {
                    KeyEvent(e).apply { source = InputDevice.SOURCE_KEYBOARD }
                } else e
                ok = inj.inject(src) && ok
            }
            if (idx < text.length - 1 && perCharDelayMs > 0) {
                try { Thread.sleep(perCharDelayMs) } catch (_: InterruptedException) {}
            }
        }
        return ok
    }

    /**
     * 实际反射调用 injectInputEvent 的绑定。第一次成功调用后缓存 receiver + Method。
     * 三条候选路径：
     *   1) InputManagerGlobal.getInstance().injectInputEvent(InputEvent, int)  ← Android 14+
     *   2) InputManager.injectInputEvent(InputEvent, int)                       ← Android ≤ 13
     *   3) InputManager.injectInputEvent(InputEvent)                            ← 极个别版本
     */
    private class Injector(
        private val receiver: Any,
        private val method: java.lang.reflect.Method,
        private val mode: Int?,
    ) {
        fun inject(event: KeyEvent): Boolean = try {
            val result = if (mode != null) method.invoke(receiver, event, mode)
                         else method.invoke(receiver, event)
            (result as? Boolean) ?: true
        } catch (t: Throwable) {
            Logger.w("InputInjector: inject failed keyCode=${event.keyCode}", t); false
        }

        companion object {
            @Volatile private var cached: Injector? = null

            fun resolve(ctx: Context?): Injector? {
                cached?.let { return it }
                synchronized(this) {
                    cached?.let { return it }
                    val found = tryInputManagerGlobal() ?: tryInputManager(ctx)
                    if (found != null) {
                        cached = found
                        Logger.i("InputInjector: bound to ${found.receiver.javaClass.simpleName}.${found.method.name}(${found.method.parameterTypes.size} args)")
                    }
                    return found
                }
            }

            private fun tryInputManagerGlobal(): Injector? = runCatching {
                val cls = Class.forName("android.hardware.input.InputManagerGlobal")
                val getInstance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
                val instance = getInstance.invoke(null) ?: return@runCatching null
                resolveOn(cls, instance)
            }.getOrNull()

            private fun tryInputManager(ctx: Context?): Injector? = runCatching {
                val im = ctx?.getSystemService(Context.INPUT_SERVICE) as? InputManager
                    ?: InputManager::class.java.getDeclaredMethod("getInstance").apply {
                        isAccessible = true
                    }.invoke(null) as? InputManager
                    ?: return@runCatching null
                resolveOn(InputManager::class.java, im)
            }.getOrNull()

            private fun resolveOn(cls: Class<*>, receiver: Any): Injector? {
                val mode = readMode(cls) ?: readMode(InputManager::class.java)
                // 优先 (InputEvent, int)，回退 (InputEvent)
                val twoArg = cls.declaredMethods.firstOrNull {
                    it.name == "injectInputEvent" &&
                        it.parameterTypes.size == 2 &&
                        android.view.InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }
                if (twoArg != null) {
                    twoArg.isAccessible = true
                    return Injector(receiver, twoArg, mode ?: 2 /* WAIT_FOR_FINISH */)
                }
                val oneArg = cls.declaredMethods.firstOrNull {
                    it.name == "injectInputEvent" &&
                        it.parameterTypes.size == 1 &&
                        android.view.InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (oneArg != null) {
                    oneArg.isAccessible = true
                    return Injector(receiver, oneArg, null)
                }
                return null
            }

            private fun readMode(cls: Class<*>): Int? = try {
                val f = cls.getDeclaredField("INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH")
                f.isAccessible = true
                f.getInt(null)
            } catch (_: Throwable) { null }
        }
    }
}
