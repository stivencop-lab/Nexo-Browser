#!/usr/bin/env python3
"""Genera values/themes.xml y values-v28/themes.xml de Nexo UI 7.0 «Liquid Craft».

Los colores viven en values/colors.xml, generado desde BrowserColors.kt por
NativeThemeTokensTest (NEXO_WRITE_TOKENS=true). Aquí solo se cablean los estilos.
Uso: python3 tools/generate_native_themes.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/res"
ACCENTS = ["mono", "signal", "cobalt", "orange", "sage"]


def base(mode: str, v28: bool) -> str:
    light = mode == "light"
    parent = "android:Theme.Material.Light.NoActionBar" if light else "android:Theme.Material.NoActionBar"
    dparent = "android:Theme.Material.Light.Dialog.Alert" if light else "android:Theme.Material.Dialog.Alert"
    cap = mode.capitalize()
    nav = f'\n    <item name="android:windowLightNavigationBar">{"true" if light else "false"}</item>' if v28 else ""
    corner = '\n    <item name="android:dialogCornerRadius">@dimen/shape_sheet</item>' if v28 else ""
    return f"""  <style name="Theme.Nexo.Base.{cap}" parent="{parent}">
    <item name="android:windowBackground">@color/nexo_{mode}_background</item>
    <item name="android:colorBackground">@color/nexo_{mode}_background</item>
    <item name="android:colorBackgroundFloating">@color/nexo_{mode}_surface</item>
    <item name="android:textColorPrimary">@color/nexo_{mode}_text</item>
    <item name="android:fontFamily">@font/plus_jakarta_sans</item>
    <item name="android:windowLightStatusBar">{"true" if light else "false"}</item>{nav}
    <item name="android:popupMenuStyle">@style/Nexo.PopupMenu.{cap}</item>
    <item name="android:listPopupWindowStyle">@style/Nexo.PopupMenu.{cap}</item>
    <item name="android:spinnerDropDownItemStyle">@style/Nexo.DropDownItem</item>
  </style>
  <style name="Nexo.PopupMenu.{cap}" parent="android:Widget.Material.PopupMenu">
    <item name="android:popupBackground">@drawable/nexo_popup_bg_{mode}</item>
    <item name="android:popupElevation">{"6dp" if light else "0dp"}</item>
  </style>
  <style name="Theme.Nexo.DialogBase.{cap}" parent="{dparent}">
    <item name="android:windowBackground">@drawable/nexo_dialog_bg_{mode}</item>
    <item name="android:colorBackgroundFloating">@color/nexo_{mode}_surface</item>
    <item name="android:textColorPrimary">@color/nexo_{mode}_text</item>
    <item name="android:fontFamily">@font/plus_jakarta_sans</item>
    <item name="android:windowMinWidthMajor">88%</item>
    <item name="android:windowMinWidthMinor">88%</item>{corner}
  </style>
"""


def accent(mode: str, name: str) -> str:
    cap, n = mode.capitalize(), name.capitalize()
    c = f"nexo_{mode}_{name}"
    return f"""  <style name="Theme.Nexo.Dialog.{cap}.{n}" parent="Theme.Nexo.DialogBase.{cap}">
    <item name="android:colorAccent">@color/{c}_primary</item>
    <item name="android:colorControlActivated">@color/{c}_primary</item>
    <item name="android:textColorPrimary">@color/{c}_text</item>
    <item name="android:textColorSecondary">@color/{c}_muted</item>
    <item name="android:colorBackgroundFloating">@color/{c}_elevated</item>
  </style>
  <style name="Theme.Nexo.{cap}.{n}" parent="Theme.Nexo.Base.{cap}">
    <item name="android:colorAccent">@color/{c}_primary</item>
    <item name="android:colorControlActivated">@color/{c}_primary</item>
    <item name="android:textColorPrimary">@color/{c}_text</item>
    <item name="android:textColorSecondary">@color/{c}_muted</item>
    <item name="android:colorBackgroundFloating">@color/{c}_elevated</item>
    <item name="android:colorPrimary">@color/{c}_primary</item>
    <item name="android:windowBackground">@color/{c}_background</item>
    <item name="android:colorBackground">@color/{c}_background</item>
    <item name="android:textColorHighlight">@color/{c}_primary_container</item>
    <item name="android:alertDialogTheme">@style/Theme.Nexo.Dialog.{cap}.{n}</item>
    <item name="android:dialogTheme">@style/Theme.Nexo.Dialog.{cap}.{n}</item>
    <item name="android:datePickerDialogTheme">@style/Theme.Nexo.Dialog.{cap}.{n}</item>
    <item name="android:timePickerDialogTheme">@style/Theme.Nexo.Dialog.{cap}.{n}</item>
  </style>
"""


def document(v28: bool) -> str:
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Nexo UI 7.0 «Liquid Craft»: generado por tools/generate_native_themes.py. -->",
           "<resources>"]
    for mode in ("light", "dark"):
        out.append(base(mode, v28).rstrip("\n"))
        for name in ACCENTS:
            out.append(accent(mode, name).rstrip("\n"))
    out.append("""  <style name="Nexo.DropDownItem" parent="android:Widget.Material.DropDownItem.Spinner">
    <item name="android:paddingStart">16dp</item>
    <item name="android:paddingEnd">16dp</item>
    <item name="android:minHeight">@dimen/touch_target</item>
  </style>
</resources>
""")
    return "\n".join(out)


(ROOT / "values/themes.xml").write_text(document(False))
(ROOT / "values-v28/themes.xml").write_text(document(True))
