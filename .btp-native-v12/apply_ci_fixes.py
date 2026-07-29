#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "native-build")

def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected fragment not found in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

# Use an Android platform launch theme. Compose Material 3 does not ship the
# XML Theme.Material3.* parent that the original prototype referenced.
theme = root / "app/src/main/res/values/themes.xml"
text = theme.read_text(encoding="utf-8")
text = text.replace(
    'parent="Theme.Material3.DayNight.NoActionBar"',
    'parent="android:style/Theme.Material.Light.NoActionBar"',
)
if 'android:windowActionModeOverlay' not in text:
    text = text.replace(
        '<item name="android:navigationBarColor">@android:color/transparent</item>',
        '<item name="android:navigationBarColor">@android:color/transparent</item>\n'
        '        <item name="android:windowActionModeOverlay">true</item>\n'
        '        <item name="android:windowNoTitle">true</item>',
    )
theme.write_text(text, encoding="utf-8")

# Material3 Scaffold's snackbarHost is a no-argument composable. The prototype
# had kept the old receiver-style lambda. The role selector only has two values,
# so a stable Row is more appropriate than experimental FlowRow.
aux = root / "app/src/main/java/fr/controlebtp/app/ui/screens/AuxiliaryScreens.kt"
aux_text = aux.read_text(encoding="utf-8")
aux_text = aux_text.replace("import androidx.compose.foundation.layout.FlowRow\n", "")
aux_text = aux_text.replace("import androidx.compose.material3.SnackbarHost\n", "")
aux_text = aux_text.replace('        snackbarHost = { SnackbarHost(it) },\n', "")
aux_text = aux_text.replace(
    '            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {',
    '            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {',
)
aux.write_text(aux_text, encoding="utf-8")

print("Native Android CI source fixes applied")
