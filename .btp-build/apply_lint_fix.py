#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "generated")
path = root / "app/src/main/java/fr/controlebtp/app/scan/BtpCardAnalyzer.kt"
text = path.read_text(encoding="utf-8")
old = "    @OptIn(ExperimentalGetImage::class)\n    override fun analyze(imageProxy: ImageProxy) {"
new = "    @ExperimentalGetImage\n    override fun analyze(imageProxy: ImageProxy) {"
if old not in text:
    raise RuntimeError(f"CameraX opt-in source fragment not found in {path}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("CameraX lint fix applied")
