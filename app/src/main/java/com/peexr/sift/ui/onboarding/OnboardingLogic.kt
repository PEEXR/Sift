package com.peexr.sift.ui.onboarding

/**
 * Steps in the first-run onboarding flow.
 */
enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    MODEL,
    COLLECTION,
    EXAMPLES,
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
        OnboardingStep.MODEL -> OnboardingStep.COLLECTION
        OnboardingStep.COLLECTION -> OnboardingStep.EXAMPLES
        OnboardingStep.EXAMPLES -> OnboardingStep.READY
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
        OnboardingStep.COLLECTION -> OnboardingStep.MODEL
        OnboardingStep.EXAMPLES -> OnboardingStep.COLLECTION
        OnboardingStep.READY -> OnboardingStep.EXAMPLES
    }
}

/**
 * Checks whether the user can advance from the given [step].
 *
 * [OnboardingStep.PERMISSIONS] requires media permission to be granted.
 * [OnboardingStep.COLLECTION] requires a non-blank [collectionName].
 * Every other step can always be advanced from (MODEL is skippable, EXAMPLES allows skip).
 */
fun canAdvanceFrom(
    step: OnboardingStep,
    hasMediaPermission: Boolean,
    collectionName: String = ""
): Boolean {
    return when (step) {
        OnboardingStep.PERMISSIONS -> hasMediaPermission
        OnboardingStep.COLLECTION -> collectionName.isNotBlank()
        OnboardingStep.WELCOME,
        OnboardingStep.MODEL,
        OnboardingStep.EXAMPLES,
        OnboardingStep.READY -> true
    }
}
