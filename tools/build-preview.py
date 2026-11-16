#!/usr/bin/env python3
"""Build a side-by-side preview with the official SDK/JDK, without Gradle downloads.

Requires ANDROID_HOME, JAVA_HOME, Python 3, SDK platform 37 (or 36) and build-tools 36.0.0 (or 35.0.0).
No runtime libraries are used by BatteryLens. For production builds, use Gradle.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ['ANDROID_HOME'])
JAVA = Path(os.environ['JAVA_HOME']) / 'bin'
BUILD = next((SDK / 'build-tools' / v for v in ('36.0.0', '35.0.0') if (SDK / 'build-tools' / v).exists()),
             SDK / 'build-tools' / '36.0.0')
ANDROID = next((SDK / 'platforms' / p / 'android.jar' for p in ('android-37', 'android-37.0', 'android-36')
                if (SDK / 'platforms' / p / 'android.jar').exists()),
               SDK / 'platforms' / 'android-37' / 'android.jar')
OUT = PROJECT / 'app' / 'build' / 'outputs' / 'apk' / 'preview'
OUT.mkdir(parents=True, exist_ok=True)

def run(*args):
    subprocess.run([str(a) for a in args], check=True)

with tempfile.TemporaryDirectory(prefix='batterylens-build-') as tmp:
    work = Path(tmp)
    res = work / 'res'
    shutil.copytree(PROJECT / 'app/src/main/res', res)
    (res / 'values' / 'generated.xml').write_text('<resources><string name="app_name">BatteryLens 3.4 Preview</string></resources>')
    ns = 'http://schemas.android.com/apk/res/android'
    ET.register_namespace('android', ns)
    manifest = ET.parse(PROJECT / 'app/src/main/AndroidManifest.xml')
    root = manifest.getroot()
    root.set('package', 'com.darren.batterylens')
    root.set('{' + ns + '}versionCode', '8')
    root.set('{' + ns + '}versionName', '3.4-preview')
    ET.SubElement(root, 'uses-sdk', {'{' + ns + '}minSdkVersion': '24', '{' + ns + '}targetSdkVersion': '37'})
    application = root.find('application')
    application.set('{' + ns + '}debuggable', 'true')
    # Keep class names attached to the namespace when the application ID is renamed.
    for component in application:
        name = component.get('{' + ns + '}name', '')
        if name.startswith('.'):
            component.set('{' + ns + '}name', 'com.darren.batterylens' + name)
    manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    run(BUILD / 'aapt2', 'compile', '--dir', res, '-o', work / 'resources.zip')
    generated = work / 'generated'; generated.mkdir()
    run(BUILD / 'aapt2', 'link', '-I', ANDROID, '--manifest', work / 'AndroidManifest.xml',
        '--rename-manifest-package', 'com.darren.batterylens.preview33',
        '--java', generated, '-o', work / 'unsigned.apk', work / 'resources.zip')
    classes = work / 'classes'; classes.mkdir()
    sources = list((PROJECT / 'app/src/main/java').rglob('*.java')) + list(generated.rglob('*.java'))
    run(JAVA / 'javac', '-source', '17', '-target', '17', '-classpath', ANDROID, '-d', classes, *sources)
    run(JAVA / 'jar', 'cf', work / 'classes.jar', '-C', classes, '.')
    dex = work / 'dex'; dex.mkdir()
    run(JAVA / 'java', '-cp', BUILD / 'lib' / 'd8.jar', 'com.android.tools.r8.D8',
        '--lib', ANDROID, '--min-api', '24', '--output', dex, work / 'classes.jar')
    with zipfile.ZipFile(work / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        for item in dex.glob('*.dex'):
            apk.write(item, item.name)
    run(BUILD / 'zipalign', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
    # Retained only in ignored build output so successive previews share a test key.
    key = OUT / 'preview.keystore'
    if not key.exists():
        run(JAVA / 'keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
            '-alias', 'androiddebugkey', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '3650', '-dname', 'CN=BatteryLens Preview,O=Android,C=US')
    output = OUT / 'BatteryLens-3.4-preview.apk'
    run(JAVA / 'java', '-jar', BUILD / 'lib' / 'apksigner.jar', 'sign', '--ks', key,
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, work / 'aligned.apk')
    run(JAVA / 'java', '-jar', BUILD / 'lib' / 'apksigner.jar', 'verify', '--verbose', output)
    run(BUILD / 'zipalign', '-c', '4', output)
    print('Preview built:', output)
