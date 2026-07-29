#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "native-build")
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
print("Native Android XML theme fixed")
