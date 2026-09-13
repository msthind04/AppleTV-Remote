package dev.atvremote.protocol.companion

/**
 * Pointer-ballistics gain curve approximating the Siri Remote touch surface feel.
 *
 * Background: tvOS's focus engine applies its *own* velocity-based acceleration and
 * momentum on the TV — a fast swipe flings focus past several items, a slow drag steps
 * one at a time. It derives that velocity from the stream of touch coordinates +
 * timestamps we send. The phone pad is far larger than the physical ~35 mm Siri
 * surface, so an equivalent flick yields lower coordinate velocity and the TV
 * under-reacts.
 *
 * This curve compensates by amplifying finger motion as a function of speed:
 *  - at/below [SPEED_SLOW]  -> [GAIN_MIN] (near 1:1, precise)
 *  - at/above [SPEED_FAST]  -> [GAIN_MAX] (flicks travel much farther)
 *  - in between: a smoothstep ramp (no abrupt gain change)
 *
 * Speed is measured in "pad fractions per second" (fraction of the pad's dimension the
 * finger crossed, divided by elapsed seconds), so it's independent of screen
 * size/density.
 */
object TouchAcceleration {
    const val GAIN_MIN = 1.0f
    const val GAIN_MAX = 1.5f
    const val SPEED_SLOW = 0.8f
    const val SPEED_FAST = 7.5f

    fun gain(speedFracPerSec: Float): Float {
        if (speedFracPerSec <= SPEED_SLOW) return GAIN_MIN
        if (speedFracPerSec >= SPEED_FAST) return GAIN_MAX
        val t = (speedFracPerSec - SPEED_SLOW) / (SPEED_FAST - SPEED_SLOW)
        val smooth = t * t * (3f - 2f * t) // smoothstep
        return GAIN_MIN + (GAIN_MAX - GAIN_MIN) * smooth
    }
}
