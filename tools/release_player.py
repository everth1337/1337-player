"""GPL-3.0-only. Package a release from a clean, committed source checkout."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check-tag', required=True)
    parser.add_argument('--package', action='store_true')
    args = parser.parse_args()
    props = dict(line.split('=', 1) for line in (ROOT / 'base/gradle.properties').read_text().splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    version, code = props['appVersionName'], int(props['appVersionCode'])
    if not re.fullmatch(r'v\d+\.\d+\.\d+', args.check_tag) or args.check_tag != 'v' + version:
        raise SystemExit('Tag must match the stable appVersionName in base/gradle.properties')
    if code <= 505:
        raise SystemExit('Public releases must have a versionCode above the private 2.4.5 build (505)')
    print(f'Version: {version}; code: {code}; updates: {props["updateRepo"]}')
    if not args.package:
        return
    git = lambda *a: subprocess.check_output(['git', '-C', str(ROOT), *a], text=True).strip()
    if git('status', '--porcelain'):
        raise SystemExit('Commit source changes before packaging; APK and source must correspond')
    stage = ROOT / 'build/player-release'
    stage.mkdir(parents=True, exist_ok=True)
    apk = stage / f'1337-Player-{version}-arm64-v8a.apk'
    shutil.copy2(ROOT / 'base/app/build/outputs/apk/release/app-release.apk', apk)
    source = stage / '1337-Player-source.zip'
    subprocess.run(['git', '-C', str(ROOT), 'archive', '--format=zip', '--prefix=1337-player/',
                    '-o', str(source), 'HEAD'], check=True)
    files = {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in [apk, source]}
    (stage / 'SHA256SUMS').write_text(''.join(f'{v["sha256"]}  {k}\n' for k, v in files.items()), encoding='utf-8')
    (stage / 'release.json').write_text(json.dumps({
        'version': version, 'versionCode': code, 'applicationId': 'app.nexi.player',
        'sourceCommit': git('rev-parse', 'HEAD'), 'updateRepo': props['updateRepo'], 'files': files,
    }, indent=2) + '\n', encoding='utf-8')
    changelog = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
    section = changelog.split(f'## {version}\n', 1)
    if len(section) != 2:
        raise SystemExit('Add a changelog section for this version before releasing')
    notes = section[1].split('\n## ', 1)[0].strip()
    notes += '\n\nAndroid 10+, ARM64. Устанавливайте поверх предыдущей версии, чтобы сохранить загрузки.\n'
    notes += '\nGPL-3.0; основа — [Myspoti](https://github.com/kota-zo/myspoti). Полные исходники и SHA-256 приложены.\n'
    (stage / 'notes.md').write_text(notes, encoding='utf-8')
    print(f'Packaged release: {stage}')

if __name__ == '__main__':
    main()
