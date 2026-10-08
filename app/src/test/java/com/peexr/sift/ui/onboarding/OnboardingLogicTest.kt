package com.peexr.sift.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {

    @Test
    fun shouldShowOnboarding_whenOnboardingAlreadyDone_returnsFalse() {
        assertFalse(shouldShowOnboarding(onboardingDone = true, hasMediaPermission = true, modelInstalled = true))
        assertFalse(shouldShowOnboarding(onboardingDone = true, hasMediaPermission = true, modelInstalled = false))
        assertFalse(shouldShowOnboarding(onboardingDone = true, hasMediaPermission = false, modelInstalled = true))
        assertFalse(shouldShowOnboarding(onboardingDone = true, hasMediaPermission = false, modelInstalled = false))
    }

    @Test
    fun shouldShowOnboarding_whenFreshInstall_returnsTrue() {
        // Brand new install: onboarding not done, permissions not granted, model not installed
        assertTrue(shouldShowOnboarding(onboardingDone = false, hasMediaPermission = false, modelInstalled = false))
    }

    @Test
    fun shouldShowOnboarding_whenPermissionGrantedOnly_returnsTrue() {
        // User granted permissions previously, but model is not installed yet
        assertTrue(shouldShowOnboarding(onboardingDone = false, hasMediaPermission = true, modelInstalled = false))
    }

    @Test
    fun shouldShowOnboarding_whenModelInstalledOnly_returnsTrue() {
        // Model files present, but media permission is not granted
        assertTrue(shouldShowOnboarding(onboardingDone = false, hasMediaPermission = false, modelInstalled = true))
    }

    @Test
    fun shouldShowOnboarding_whenExistingUserWithPermissionAndModel_returnsFalse() {
        // Existing user who had already set up the app before onboarding existed
        assertFalse(shouldShowOnboarding(onboardingDone = false, hasMediaPermission = true, modelInstalled = true))
    }

    @Test
    fun nextStep_traversesCorrectSequence() {
        assertEquals(OnboardingStep.PERMISSIONS, nextStep(OnboardingStep.WELCOME))
        assertEquals(OnboardingStep.MODEL, nextStep(OnboardingStep.PERMISSIONS))
        assertEquals(OnboardingStep.READY, nextStep(OnboardingStep.MODEL))
        assertNull(nextStep(OnboardingStep.READY))
    }

    @Test
    fun previousStep_traversesBackwardCorrectly() {
        assertNull(previousStep(OnboardingStep.WELCOME))
        assertEquals(OnboardingStep.WELCOME, previousStep(OnboardingStep.PERMISSIONS))
        assertEquals(OnboardingStep.PERMISSIONS, previousStep(OnboardingStep.MODEL))
        assertEquals(OnboardingStep.MODEL, previousStep(OnboardingStep.READY))
    }

    @Test
    fun canAdvanceFrom_respectsMediaPermissionRule() {
        // Welcome is always advanceable
        assertTrue(canAdvanceFrom(OnboardingStep.WELCOME, hasMediaPermission = false))
        assertTrue(canAdvanceFrom(OnboardingStep.WELCOME, hasMediaPermission = true))

        // Permissions step requires media permission
        assertFalse(canAdvanceFrom(OnboardingStep.PERMISSIONS, hasMediaPermission = false))
        assertTrue(canAdvanceFrom(OnboardingStep.PERMISSIONS, hasMediaPermission = true))

        // Model step is skippable (advanceable even without model installed)
        assertTrue(canAdvanceFrom(OnboardingStep.MODEL, hasMediaPermission = false))
        assertTrue(canAdvanceFrom(OnboardingStep.MODEL, hasMediaPermission = true))

        // Ready step is always advanceable
        assertTrue(canAdvanceFrom(OnboardingStep.READY, hasMediaPermission = false))
        assertTrue(canAdvanceFrom(OnboardingStep.READY, hasMediaPermission = true))
    }
}
