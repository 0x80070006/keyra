"""Contrôle du manifeste d'un APK de release contre les listes blanches de docs/.

Usage : python tools/check_manifest.py <aapt2> <apk>
Échoue (code 1) si l'APK :
  - demande ou déclare une permission absente de docs/allowed-permissions.txt (vide : aucune) ;
  - contient un composant (activité, service, récepteur, fournisseur) absent de docs/allowed-components.txt ;
  - est débogable, ou « profileable » par le shell.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "http://schemas.android.com/apk/res/android:"


def allowed(name):
    lines = (ROOT / "docs" / name).read_text(encoding="utf-8").splitlines()
    return {line.strip() for line in lines if line.strip() and not line.startswith("#")}


def main(aapt2, apk):
    tree = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                          capture_output=True, text=True, check=True).stdout
    element = None
    found = {"permission": set(), "component": set(), "flags": set()}
    for line in tree.splitlines():
        m = re.match(r"\s*E: ([\w-]+)", line)
        if m:
            element = m.group(1)
            if element == "profileable":
                found["flags"].add("profileable")
            continue
        m = re.match(r'\s*A: ' + re.escape(ANDROID) + r'(\w+)\([^)]*\)=(?:"([^"]*)"|\(type 0x12\)(0x[0-9a-f]+)|(true|false))', line)
        if not m:
            continue
        attribute, value = m.group(1), m.group(2)
        boolean = "0x0" if m.group(4) == "false" else (m.group(3) or m.group(4))
        if attribute == "name" and element in ("uses-permission", "uses-permission-sdk-23", "permission"):
            found["permission"].add(value)
        elif attribute == "name" and element in ("activity", "activity-alias", "service", "receiver", "provider"):
            found["component"].add(f"{element} {value}")
        elif attribute == "debuggable" and boolean not in (None, "0x0"):
            found["flags"].add("debuggable")
        elif attribute == "shell" and element == "profileable" and boolean not in (None, "0x0"):
            found["flags"].add("profileable-shell")

    problems = []
    for permission in sorted(found["permission"] - allowed("allowed-permissions.txt")):
        problems.append(f"permission non autorisée : {permission}")
    for component in sorted(found["component"] - allowed("allowed-components.txt")):
        problems.append(f"composant non autorisé : {component}")
    for flag in sorted(found["flags"]):
        problems.append(f"drapeau interdit en release : {flag}")
    print(f"Permissions : {sorted(found['permission']) or 'aucune'}")
    print("Composants :\n  " + "\n  ".join(sorted(found["component"])))
    if problems:
        print("\nÉCHEC :\n  " + "\n  ".join(problems))
        return 1
    print("\nManifeste conforme aux listes blanches.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
