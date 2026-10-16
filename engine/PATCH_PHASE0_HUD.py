# -*- coding: utf-8 -*-
"""
PATCH_PHASE0_HUD.py — Фаза 0 «Демонтаж» для PoliticHellHUD.

Что делает:
  Находит в исходниках .java файлы с методами старых сундучных меню
  (installMenus / initMenus / setupMenus / registerMenus) и вставляет
  сразу после открывающей скобки метода ранний return, чтобы старые
  DeluxeMenus-сундук больше НЕ регистрировались при старте HUD.

 <My> Ты затем подымаешь новый движок Style1ScreenHub из PoliticHellEngine.jar
  или встраиваешь его классы в основной плагин (Фаза 0.2 — когда syncing
  Google Drive отработает и я увижу реальные исходникиPolit HellHUD).

Использование:
  python PATCH_PHASE0_HUD.py "<путь к папке с исходниками>"
  python PATCH_PHASE0_HUD.py "F:\\All Minecraft\\PoliticHell"
  python PATCH_PHASE0_HUD.py                # скан текущей папки

Создаёт резервные копии: файл.java.bak_phase0
Скрипт идемпотентный: повторный запуск ничего не портит.
"""

import os
import re
import shutil
import sys

METHOD_RE = re.compile(
    r'^([ \t]*)(public|private|protected)[ \w\[\]<>,]*?\bvoid\s+'
    r'(installMenus|initMenus|setupMenus|registerMenus)\s*\(\s*\)\s*\{',
    re.MULTILINE
)

GUARD = (
    "{\n"
    "\\1    // PHASE0: старое сундучное меню отключено (движок Style1ScreenHub).\n"
    "\\1    if (true) return; // installMenus -> null\n"
)
GUARDED_MARK = "PHASE0: старое сундучное меню отключено"


def patch_file(path: str) -> list[str]:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        src = f.read()

    if GUARDED_MARK in src:
        return []

    patched = []
    out = []
    last = 0
    for m in METHOD_RE.finditer(src):
        out.append(src[last:m.start()])
        indent = m.group(1)
        head = m.group(0)  # совпадение целиком: "...void installMenus() {"
        rebuilt = (
            head + "\n"
            + f"{indent}    // PHASE0: старое сундучное меню отключено (движок Style1ScreenHub).\n"
            + f"{indent}    if (true) return; // installMenus -> null\n"
        )
        out.append(rebuilt)
        patched.append(m.group(3))
        last = m.end()
    out.append(src[last:])

    if not patched:
        return []

    backup = path + ".bak_phase0"
    if not os.path.exists(backup):
        shutil.copy2(path, backup)
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(out))
    return patched


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        print(f"[FAIL] Нет папки: {root}")
        sys.exit(1)

    touched = 0
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            full = os.path.join(dirpath, fn)
            try:
                patched = patch_file(full)
            except Exception as ex:  # noqa
                print(f"[WARN] {full}: {ex}")
                continue
            if patched:
                touched += 1
                print(f"[OK] {full}: отключены {', '.join(patched)} (бэкап *.bak_phase0)")
            elif os.path.exists(full + ".bak_phase0"):
                print(f"[SKIP] уже пропатчен: {full}")

    if touched == 0:
        print("[INFO] Методы installMenus/initMenus не найдены — возможно, сундуков уже нет.")
    print(f"[DONE] Файлы пропатчено: {touched}. Теперь собери плагин заново (СОБРАТЬ.bat / mvn package).")


if __name__ == "__main__":
    main()
