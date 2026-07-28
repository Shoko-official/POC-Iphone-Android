#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "generated")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected source fragment not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Fix UiNotice constructor: the first positional argument is the generated identifier.
replace_once(
    root / "app/src/main/java/fr/controlebtp/app/MainViewModel.kt",
    'notice = UiNotice(if (success) "Rapport PDF signé enregistré." else "Enregistrement du PDF annulé.", isError = !success),',
    '''notice = UiNotice(
                text = if (success) "Rapport PDF signé enregistré." else "Enregistrement du PDF annulé.",
                isError = !success,
            ),''',
)

# Make shared cards clickable and section headings externally paddable.
components = root / "app/src/main/java/fr/controlebtp/app/ui/components/Components.kt"
replace_once(
    components,
    '''@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
        content = content,
    )
}''',
    '''@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
        content = content,
    )
}''',
)
replace_once(
    components,
    '''@Composable
fun SectionHeading(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable (() -> Unit))? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {''',
    '''@Composable
fun SectionHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable (() -> Unit))? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {''',
)

# Modal bottom sheets are experimental in the selected Material 3 version.
auxiliary = root / "app/src/main/java/fr/controlebtp/app/ui/screens/AuxiliaryScreens.kt"
text = auxiliary.read_text(encoding="utf-8")
if not text.startswith("@file:OptIn"):
    auxiliary.write_text(
        "@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)\n\n" + text,
        encoding="utf-8",
    )

# Add the missing destructive confirmation dialog used by the preparation screen.
prepare = root / "app/src/main/java/fr/controlebtp/app/ui/screens/PrepareScreen.kt"
prepare_text = prepare.read_text(encoding="utf-8")
marker = """@Composable
private fun ReadinessRow("""
helper = """@Composable
private fun ConfirmDangerDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

"""
if "private fun ConfirmDangerDialog(" not in prepare_text:
    if marker not in prepare_text:
        raise RuntimeError(f"ReadinessRow marker not found in {prepare}")
    prepare.write_text(prepare_text.replace(marker, helper + marker, 1), encoding="utf-8")

# The Signature icon is not part of the resolved material-icons artifact.
result = root / "app/src/main/java/fr/controlebtp/app/ui/screens/ResultScreen.kt"
result_text = result.read_text(encoding="utf-8")
result_text = result_text.replace("import androidx.compose.material.icons.outlined.Signature\n", "")
result_text = result_text.replace(
    "Icons.Outlined.Check else Icons.Outlined.Signature",
    "Icons.Outlined.Check else Icons.Outlined.Edit",
)
result.write_text(result_text, encoding="utf-8")

# Expose the metric names used by the result screen.
replace_once(
    root / "app/src/main/java/fr/controlebtp/app/core/Models.kt",
    '''data class ReportMetrics(
    val expectedCount: Int,
    val scannedCount: Int,
    val declaredCount: Int,
    val undeclaredCount: Int,
    val validCardCount: Int,
    val invalidCardCount: Int,
    val conformityCount: Int,
    val conformityPercent: Float,
)''',
    '''data class ReportMetrics(
    val expectedCount: Int,
    val scannedCount: Int,
    val declaredCount: Int,
    val undeclaredCount: Int,
    val validCardCount: Int,
    val invalidCardCount: Int,
    val conformityCount: Int,
    val conformityPercent: Float,
) {
    val declaredPresentCount: Int get() = declaredCount
    val outsideRosterCount: Int get() = undeclaredCount
    val unknownValidityCount: Int get() = (scannedCount - validCardCount - invalidCardCount).coerceAtLeast(0)
    val absentOrUncontrolledCount: Int get() = (expectedCount - declaredCount).coerceAtLeast(0)
}''',
)

print("BTP source fixes applied successfully")
