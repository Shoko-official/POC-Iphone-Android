#!/usr/bin/env python3
from pathlib import Path
import base64
import gzip
import hashlib
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "generated")
builder = Path(__file__).resolve().parent

required = [
    builder / "ui.part00",
    builder / "ui.part00c1",
    builder / "ui.part00c2",
    builder / "ui.part01",
    builder / "ui.part02",
]
missing = [str(part) for part in required if not part.is_file()]
if missing:
    raise RuntimeError(f"Missing exact-UI payload parts: {missing}")

# The first GitHub payload retained the first 7,014 exact characters. The two
# correction fragments replace everything after that boundary without changing
# one CSS rule, one visual token or one interaction from the supplied HTML.
prefix = (builder / "ui.part00").read_text(encoding="utf-8")[:7014]
encoded = "".join(
    [
        prefix,
        (builder / "ui.part00c1").read_text(encoding="utf-8"),
        (builder / "ui.part00c2").read_text(encoding="utf-8"),
        (builder / "ui.part01").read_text(encoding="utf-8"),
        (builder / "ui.part02").read_text(encoding="utf-8"),
    ]
)

actual_hash = hashlib.sha256(encoded.encode()).hexdigest()
expected_hash = "6d886edb9246488c76de2478ec984f1529f8f6915d582d352cb8cbc2e630379b"
if len(encoded) != 53604 or actual_hash != expected_hash:
    diagnostic_dir = root / "app/build/reports/ui-payload"
    diagnostic_dir.mkdir(parents=True, exist_ok=True)
    (diagnostic_dir / "reconstructed-ui.b64").write_text(encoded, encoding="utf-8")
    raise RuntimeError(f"Exact UI payload mismatch: size={len(encoded)}, sha256={actual_hash}")

html = gzip.decompress(base64.b64decode(encoded))
if b"UX V8 Forest Motion" not in html or b"V10" not in html:
    raise RuntimeError("The reconstructed HTML is not the user-supplied V10 interface")

asset = root / "app/src/main/assets/btp/index.html"
asset.parent.mkdir(parents=True, exist_ok=True)
asset.write_bytes(html)

java_root = root / "app/src/main/java/fr/controlebtp/app"
java_root.mkdir(parents=True, exist_ok=True)
shutil.copyfile(builder / "templates/MainActivity.kt", java_root / "MainActivity.kt")
shutil.copyfile(builder / "templates/BtpJavascriptBridge.kt", java_root / "BtpJavascriptBridge.kt")

gradle_path = root / "app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
webkit = '    implementation("androidx.webkit:webkit:1.12.1")\n'
if "androidx.webkit:webkit" not in gradle:
    anchor = '    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")\n'
    if anchor not in gradle:
        raise RuntimeError("Cannot locate dependency insertion point")
    gradle = gradle.replace(anchor, anchor + webkit, 1)
gradle_path.write_text(gradle, encoding="utf-8")

manifest_path = root / "app/src/main/AndroidManifest.xml"
manifest = manifest_path.read_text(encoding="utf-8")
if 'android:usesCleartextTraffic="false"' not in manifest:
    manifest = manifest.replace(
        'android:theme="@style/Theme.ControleBtp">',
        'android:theme="@style/Theme.ControleBtp"\n        android:usesCleartextTraffic="false">',
        1,
    )
if 'android:windowSoftInputMode="adjustResize"' not in manifest:
    manifest = manifest.replace(
        'android:screenOrientation="portrait">',
        'android:screenOrientation="portrait"\n            android:windowSoftInputMode="adjustResize">',
        1,
    )
manifest_path.write_text(manifest, encoding="utf-8")

print(f"Exact HTML UI installed: {len(html)} bytes")
print("Native Android OCR/QR/PDF bridge installed")
