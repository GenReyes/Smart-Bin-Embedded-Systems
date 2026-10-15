package smartbin.notify

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * How the app feels under the finger: a small vibration and a soft sound on
 * keys and switches. Both can be turned off in Settings. Alert sounds are
 * separate; those belong to Android's notification settings.
 */
object Feel {
    @Volatile var sounds = true
    @Volatile var haptics = true

    /** Any control: its own sound and a vibration that fits it. */
    fun play(view: View, sfx: Sfx) {
        // Events have patterns of their own; plain keys use Android's touch clicks.
        val buzz = Haptics.forSfx(sfx)
        if (buzz != null) Haptics.play(buzz)
        else if (haptics) view.performHapticFeedback(hapticFor(sfx))
        Sounds.play(sfx)
    }

    /** A sound and its vibration with no key involved, such as a bin going full. */
    fun event(sfx: Sfx) {
        Haptics.forSfx(sfx)?.let(Haptics::play)
        Sounds.play(sfx)
    }

    private fun hapticFor(sfx: Sfx): Int {
        val v = Build.VERSION.SDK_INT
        val key = HapticFeedbackConstants.VIRTUAL_KEY
        return when (sfx) {
            Sfx.CONFIRM -> if (v >= 30) HapticFeedbackConstants.CONFIRM else key
            Sfx.SWITCH_ON -> if (v >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.KEYBOARD_TAP
            Sfx.SWITCH_OFF -> if (v >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.KEYBOARD_TAP
            Sfx.STEP_UP, Sfx.STEP_DOWN, Sfx.CHOICE -> if (v >= 30) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.KEYBOARD_TAP
            Sfx.BACK -> HapticFeedbackConstants.CLOCK_TICK
            Sfx.PIN_KEY, Sfx.TAB, Sfx.THEME_LIGHT, Sfx.THEME_DARK, Sfx.OPEN -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> key
        }
    }

    /** Something weighty, such as a long hold being recognised. */
    fun thump(view: View) {
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
