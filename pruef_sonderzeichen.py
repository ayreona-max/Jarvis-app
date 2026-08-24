#!/usr/bin/env python3
"""
pruef_sonderzeichen.py - Prueft eine Datei auf die im Jarvis-Projekt
wiederholt aufgetretenen Sonderzeichen-Fallen, bevor sie committet/deployt
wird:

  1. BOM am Dateianfang (bricht main.py, verfaelscht Encoding-Vergleiche).
  2. Stille Anfuehrungszeichen-Vertauschung gegenueber der letzten
     Git-Version (gerade <-> geschwungen). Das ist die eigentliche Gefahr,
     nicht die bloße Anwesenheit geschwungener Zeichen - die sind in
     persona.txt und deutschen XML-Texten oft gewollt.
  3. XML-Wohlgeformtheit (.xml-Dateien) - faengt kaputte Attribut-
     Anfuehrungszeichen direkt.
  4. Python-Syntaxgueltigkeit (.py-Dateien) ueber py_compile.
  5. Klammernbilanz ( ) [ ] { } als grober Zusatz-Check fuer alle anderen
     Dateitypen (z.B. Kotlin).

Nutzung:
    python pruef_sonderzeichen.py <datei> [--against <git-ref>]

  --against  Git-Referenz fuer den Vorher-Vergleich (Default: HEAD).
             Ohne Git-Historie fuer die Datei wird Schritt 2 uebersprungen.

Exit-Code 0 = keine Funde, 1 = mindestens ein Fund, 2 = Datei nicht gefunden.
"""

import argparse
import difflib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
import py_compile

# Windows-Konsolen-Encoding zerstoert sonst geschwungene Anfuehrungszeichen/
# Umlaute in der print()-Ausgabe (bekannte Falle in diesem Projekt).
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8")

STRAIGHT_QUOTES = {'"', "'"}
CURLY_QUOTES = {'“', '”', '‘', '’', '„', '‚', '«', '»'}
ALL_QUOTES = STRAIGHT_QUOTES | CURLY_QUOTES


def quote_signature(line):
    """Anfuehrungszeichen einer Zeile in Reihenfolge, ohne den sonstigen Text."""
    return [c for c in line if c in ALL_QUOTES]


def check_bom(raw_bytes):
    return raw_bytes.startswith(b"\xef\xbb\xbf")


def path_git_relative(path):
    root = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    abs_path = os.path.abspath(path)
    return os.path.relpath(abs_path, root).replace("\\", "/")


def check_quote_flips(path, ref):
    try:
        rel = path_git_relative(path)
        result = subprocess.run(
            ["git", "show", f"{ref}:{rel}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        if result.returncode != 0:
            return None  # keine Git-Historie fuer diese Datei/Referenz
        old_content = result.stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None

    with open(path, encoding="utf-8", errors="replace") as f:
        new_content = f.read()

    old_lines = old_content.splitlines()
    new_lines = new_content.splitlines()
    sm = difflib.SequenceMatcher(a=old_lines, b=new_lines, autojunk=False)

    findings = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag != "replace" or (i2 - i1) != (j2 - j1):
            continue  # nur 1:1 ersetzte Zeilen sind zuverlaessig paarbar
        for old_line, new_line, lineno in zip(
            old_lines[i1:i2], new_lines[j1:j2], range(j1 + 1, j2 + 1)
        ):
            old_q, new_q = quote_signature(old_line), quote_signature(new_line)
            if len(old_q) != len(new_q):
                continue
            for oc, nc in zip(old_q, new_q):
                if oc != nc and ((oc in STRAIGHT_QUOTES) != (nc in STRAIGHT_QUOTES)):
                    findings.append((lineno, oc, nc, new_line.strip()))
                    break
    return findings


def check_xml(path):
    try:
        ET.parse(path)
        return None
    except ET.ParseError as e:
        return str(e)


def check_python(path):
    try:
        py_compile.compile(path, doraise=True)
        return None
    except py_compile.PyCompileError as e:
        return str(e)


def check_bracket_balance(text):
    pairs = {"(": ")", "[": "]", "{": "}"}
    closing = {v: k for k, v in pairs.items()}
    stack = []
    for i, ch in enumerate(text):
        if ch in pairs:
            stack.append((ch, i))
        elif ch in closing:
            if not stack or stack[-1][0] != closing[ch]:
                return f"Unausgewogene Klammer bei Zeichenposition {i}: '{ch}'"
            stack.pop()
    if stack:
        ch, i = stack[-1]
        return f"Nicht geschlossene Klammer bei Zeichenposition {i}: '{ch}'"
    return None


def main():
    parser = argparse.ArgumentParser(
        description="Prueft eine Datei auf bekannte Jarvis-Sonderzeichen-Fallen."
    )
    parser.add_argument("datei")
    parser.add_argument(
        "--against", default="HEAD",
        help="Git-Referenz fuer den Vorher-Vergleich (Default: HEAD)",
    )
    args = parser.parse_args()

    path = args.datei
    if not os.path.isfile(path):
        print(f"FEHLER: Datei nicht gefunden: {path}")
        sys.exit(2)

    found_any = False

    with open(path, "rb") as f:
        raw = f.read()

    if check_bom(raw):
        print("[BOM] Datei beginnt mit einer UTF-8-BOM - Python-Import/Encoding-Vergleiche koennen daran scheitern.")
        found_any = True
    else:
        print("[BOM] ok, keine BOM.")

    flips = check_quote_flips(path, args.against)
    if flips is None:
        print(f"[Quote-Flip] uebersprungen (keine Git-Historie fuer diese Datei gegen '{args.against}').")
    elif flips:
        print(f"[Quote-Flip] {len(flips)} verdaechtige Anfuehrungszeichen-Vertauschung(en) ggue. '{args.against}':")
        for lineno, oc, nc, line in flips:
            print(f"  Zeile {lineno}: '{oc}' -> '{nc}'  |  {line[:100]}")
        found_any = True
    else:
        print(f"[Quote-Flip] ok, keine Vertauschung ggue. '{args.against}'.")

    ext = os.path.splitext(path)[1].lower()
    if ext == ".xml":
        err = check_xml(path)
        if err:
            print(f"[XML] NICHT wohlgeformt: {err}")
            found_any = True
        else:
            print("[XML] ok, wohlgeformt.")
    elif ext == ".py":
        err = check_python(path)
        if err:
            print(f"[Python] Syntaxfehler: {err}")
            found_any = True
        else:
            print("[Python] ok, kompiliert.")
    else:
        text = raw.decode("utf-8", errors="replace")
        err = check_bracket_balance(text)
        if err:
            print(f"[Klammern] {err}")
            found_any = True
        else:
            print("[Klammern] ok, ausgeglichen (grober Zusatz-Check, kein Ersatz fuer einen Compiler).")

    print()
    if found_any:
        print("ERGEBNIS: mindestens ein Fund - vor Commit/Deploy pruefen.")
        sys.exit(1)
    else:
        print("ERGEBNIS: sauber.")
        sys.exit(0)


if __name__ == "__main__":
    main()
