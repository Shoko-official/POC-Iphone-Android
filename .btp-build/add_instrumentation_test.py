#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "generated")
target = root / "app/src/androidTest/java/fr/controlebtp/app/FirstRunFlowTest.kt"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(
    r'''@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package fr.controlebtp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createsInitialAdministratorAndFirstProject() {
        composeRule.waitUntilAtLeastOneExists(hasText("Créer l’administrateur"), 8_000)
        composeRule.onNodeWithText("Créer l’administrateur").assertIsDisplayed()

        val setupFields = composeRule.onAllNodes(hasSetTextAction())
        setupFields[0].performTextInput("Admin Test")
        setupFields[1].performTextInput("admin.test@controle-btp.local")
        setupFields[2].performTextInput("MotDePasse123")
        composeRule.onNodeWithText("Créer le compte").performClick()

        composeRule.waitUntilAtLeastOneExists(hasText("Chantiers"), 10_000)
        composeRule.onNodeWithText("Chantiers").assertIsDisplayed()
        composeRule.onNodeWithText("Nouveau chantier").performClick()

        composeRule.waitUntilAtLeastOneExists(hasText("Nom du chantier"), 8_000)
        val projectFields = composeRule.onAllNodes(hasSetTextAction())
        projectFields[0].performTextInput("Chantier test")
        projectFields[1].performTextInput("Entreprise test")
        projectFields[2].performTextInput("Paris")
        composeRule.onNodeWithText("Créer le chantier").performClick()

        composeRule.waitUntilAtLeastOneExists(hasText("Préparer le contrôle"), 10_000)
        composeRule.onNodeWithText("Préparer le contrôle").assertIsDisplayed()
    }
}
''',
    encoding="utf-8",
)
print(f"Instrumentation test written to {target}")
