package dev.fpv.flight

/**
 * Pure-logic onboarding wizard state machine. No Minecraft, no display: the GUI drives
 * these transitions; behavior is headless-verified.
 *
 * Steps: detect radio -> pick hand mode -> graphical calibration -> arm lesson ->
 * first-flight提示. Each step may go back / skip; finish or skip-all persists completion.
 */
object OnboardingFlow {
    const val STEP_DETECT = 0
    const val STEP_HAND = 1
    const val STEP_CALIBRATE = 2
    const val STEP_ARM = 3
    const val STEP_FLY = 4
    const val STEP_COUNT = 5

    fun clamp(step: Int): Int = step.coerceIn(STEP_DETECT, STEP_FLY)

    /** Next step (no skip). */
    fun next(step: Int): Int = (step + 1).coerceAtMost(STEP_FLY)

    /** Previous step (clamped at first). */
    fun prev(step: Int): Int = (step - 1).coerceAtLeast(STEP_DETECT)

    /** Skipping the whole wizard marks it done. */
    fun skippedToDone(): Done = Done(skipped = true)

    /** Finishing the last step marks it done. */
    fun finished(): Done = Done(skipped = false)

    data class Done(val skipped: Boolean)

    /** Whether to auto-launch the wizard on first entry. */
    fun shouldAutoLaunch(onboardingCompleted: Boolean): Boolean = !onboardingCompleted
}
