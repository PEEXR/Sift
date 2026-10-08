package com.peexr.sift.ui.onboarding

/**
 * Steps in the first-run onboarding flow.
 */
enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    MODEL,
    READY
}

/**
 * Determines whether the onboarding flow should be displayed to the user.
 *
 * It is shown on fresh installs where onboarding hasn't been completed.
 * Existing installs that already have media permission and model installed will skip it.
 */
fun shouldShowOnboarding(
    onboardingDone: Boolean,
    hasMediaPermission: Boolean,
    modelInstalled: Boolean
): Boolean {
    return !onboardingDone && !(hasMediaPermission && modelInstalled)
}

/**
 * Returns the next step in the onboarding sequence, or null if [current] is [OnboardingStep.READY].
 */
fun nextStep(current: OnboardingStep): OnboardingStep? {
    return when (current) {
        OnboardingStep.WELCOME -> OnboardingStep.PERMISSIONS
        OnboardingStep.PERMISSIONS -> OnboardingStep.MODEL
        OnboardingStep.MODEL -> OnboardingStep.READY
        OnboardingStep.READY -> null
    }
}

/**
 * Returns the previous step in the onboarding sequence, or null if [current] is [OnboardingStep.WELCOME].
 */
fun previousStep(current: OnboardingStep): OnboardingStep? {
    return when (current) {
        OnboardingStep.WELCOME -> null
        OnboardingStep.PERMISSIONS -> OnboardingStep.WELCOME
        OnboardingStep.MODEL -> OnboardingStep.PERMISSIONS
        OnboardingStep.READY -> OnboardingStep.MODEL
    }
}

/**
 * Checks whether the user can advance from the given [step].
 *
 * [OnboardingStep.PERMISSIONS] requires media permission to be granted.
 * Every other step can always be advanced from (MODEL is skippable).
 */
fun canAdvanceFrom(step: OnboardingStep, hasMediaPermission: Boolean): Boolean {
    return when (step) {
        OnboardingStep.PERMISSIONS -> hasMediaPermission
        OnboardingStep.WELCOME,
        OnboardingStep.MODEL,
        OnboardingStep.READY -> true
    }
}
