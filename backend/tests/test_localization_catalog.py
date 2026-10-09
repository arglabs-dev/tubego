"""The Android catalog is verified in regular CI even when no emulator is available."""
import pathlib,subprocess,sys

def test_language_resources_keys_placeholders_and_screen_coverage():
    root=pathlib.Path(__file__).resolve().parents[2]
    subprocess.run([sys.executable,str(root/'android/localization/verify.py')],check=True)
