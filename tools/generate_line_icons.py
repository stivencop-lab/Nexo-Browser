#!/usr/bin/env python3
"""Iconografía lineal de Nexo UI 7.0 «Liquid Craft».

Una sola gramática para toda la app: rejilla 24, dibujo a 20dp, trazo 1.6dp reales
(1.92 unidades de rejilla), extremos y uniones redondeados, sin rellenos decorativos.
Uso: python3 tools/generate_line_icons.py  (reescribe app/src/main/res/drawable/ic_*.xml)
"""
import math
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
SIZE_DP = 20
STROKE = 1.6 * 24 / SIZE_DP  # 1.6dp a 20dp


def circle(cx, cy, r):
    return f"M{cx + r:g},{cy:g}a{r:g},{r:g} 0 1,1 {-2 * r:g},0a{r:g},{r:g} 0 1,1 {2 * r:g},0z"


def rrect(x, y, w, h, r):
    return (f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0,1 {r:g},{r:g}v{h - 2 * r:g}"
            f"a{r:g},{r:g} 0 0,1 {-r:g},{r:g}h{-(w - 2 * r):g}a{r:g},{r:g} 0 0,1 {-r:g},{-r:g}"
            f"v{-(h - 2 * r):g}a{r:g},{r:g} 0 0,1 {r:g},{-r:g}z")


def arc(cx, cy, r, start_deg, sweep_deg):
    a0, a1 = math.radians(start_deg), math.radians(start_deg + sweep_deg)
    x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
    x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
    large = 1 if abs(sweep_deg) > 180 else 0
    sweep = 1 if sweep_deg > 0 else 0
    return f"M{x0:.2f},{y0:.2f}A{r:g},{r:g} 0 {large},{sweep} {x1:.2f},{y1:.2f}"


def gear():
    teeth, ro, ri, cx = 8, 9.2, 7.0, 12.0
    pts = []
    for i in range(teeth):
        base = i * 2 * math.pi / teeth
        for da, r in ((-0.20, ri), (-0.12, ro), (0.12, ro), (0.20, ri)):
            pts.append((cx + r * math.cos(base + da), cx + r * math.sin(base + da)))
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"
    return [d, circle(12, 12, 2.9)]


ICONS = {
    "ic_nav_back": (["M19,12H5", "M11,6l-6,6 6,6"], {"mirror": True}),
    "ic_nav_forward": (["M5,12h14", "M13,6l6,6 -6,6"], {"mirror": True}),
    "ic_nav_up": (["M12,19V5", "M6,11l6,-6 6,6"], {}),
    "ic_nav_down": (["M12,5v14", "M6,13l6,6 6,-6"], {}),
    "ic_nav_add": (["M12,5v14", "M5,12h14"], {}),
    "ic_nav_chevron": (["M9.5,6l6,6 -6,6"], {"mirror": True}),
    "ic_chevron_down": (["M6.5,9.5l5.5,5.5 5.5,-5.5"], {}),
    "ic_nav_menu": (["M4,7h16", "M4,12h16", "M4,17h16"], {}),
    "ic_nav_home": (["M4,10.2L12,4l8,6.2", "M6.5,8.6V19a1,1 0 0,0 1,1h9a1,1 0 0,0 1,-1V8.6", "M10,20v-5.5h4V20"], {}),
    "ic_nav_reload": ([arc(12, 12, 7.5, 30, 300), "M18.5,3.9v4.4h-4.4"], {}),
    "ic_search": ([circle(11, 11, 6.5), "M16,16l4,4"], {}),
    "ic_lock": (["M7.5,10.5V8a4.5,4.5 0 0,1 9,0v2.5", rrect(5, 10.5, 14, 10, 2)], {}),
    "ic_tab_tools": (["M4,7.5h9", "M17,7.5h3", "M15,5.5v4", "M4,16.5h3", "M11,16.5h9", "M9,14.5v4"], {}),
    "ic_check": (["M5,12.5l4.5,4.5L19,7.5"], {}),
    "ic_menu_close": (["M6,6l12,12", "M18,6L6,18"], {}),
    "ic_menu_copy": ([rrect(9, 9, 11, 11, 2), "M15,9V6a2,2 0 0,0 -2,-2H6a2,2 0 0,0 -2,2v7a2,2 0 0,0 2,2h3"], {}),
    "ic_menu_bookmark": (["M7,4h10a1,1 0 0,1 1,1v15l-6,-4 -6,4V5a1,1 0 0,1 1,-1z"], {}),
    "ic_menu_private": (["M3.5,3.5l17,17", "M10.6,5.1A9.7,9.7 0 0,1 12,5c5,0 8.5,4.5 9.5,7a13,13 0 0,1 -2.6,3.8",
                         "M6.6,6.6A13.4,13.4 0 0,0 2.5,12c1,2.5 4.5,7 9.5,7a9.4,9.4 0 0,0 5.4,-1.6", "M9.9,9.9a3,3 0 0,0 4.2,4.2"], {}),
    "ic_menu_burner": (["M12,21c3.6,0 6,-2.4 6,-5.6 0,-3.4 -2.6,-5.6 -3.6,-8.9 -2.3,1.3 -3,3.6 -3,5.2 -1.2,-0.6 -2,-1.8 -2.2,-3.2C7.5,10 6,12.4 6,15.4 6,18.6 8.4,21 12,21z"], {}),
    "ic_menu_desktop": ([rrect(3.5, 4.5, 17, 12, 1.8), "M9,20h6", "M12,16.5V20"], {}),
    "ic_menu_download": (["M12,4v11", "M7,10.5l5,5 5,-5", "M5,20h14"], {}),
    "ic_menu_extensions": (["M9,4.5a2,2 0 0,1 4,0V6h3.5a1,1 0 0,1 1,1v3.5H19a2,2 0 0,1 0,4h-1.5V18a1,1 0 0,1 -1,1H13v-1.5a2,2 0 0,0 -4,0V19H5.5a1,1 0 0,1 -1,-1v-3.5H6a2,2 0 0,0 0,-4H4.5V7a1,1 0 0,1 1,-1H9z"], {}),
    "ic_menu_file": (["M14,3.5H7.5A1.5,1.5 0 0,0 6,5v14a1.5,1.5 0 0,0 1.5,1.5h9A1.5,1.5 0 0,0 18,19V7.5z", "M14,3.5v4h4"], {}),
    "ic_menu_find": ([circle(11, 11, 7), "M20,20l-4,-4", "M8,11h6"], {}),
    "ic_menu_folder": (["M3.5,7.5A1.5,1.5 0 0,1 5,6h4l2,2.5h8a1.5,1.5 0 0,1 1.5,1.5v7.5A1.5,1.5 0 0,1 19,19H5a1.5,1.5 0 0,1 -1.5,-1.5z"], {}),
    "ic_menu_history": ([arc(12, 12, 8, 180, 330), "M4,4.6v4.2h4.2", "M12,8v4.3l3,1.8"], {}),
    "ic_menu_image": ([rrect(3.5, 4.5, 17, 15, 2), "M3.5,16l5,-5 4,4 2.5,-2.5 5,5", circle(15.5, 9.5, 1.5)], {}),
    "ic_menu_link": (["M10,14a4,4 0 0,0 5.7,0l3,-3a4,4 0 0,0 -5.7,-5.7l-1.2,1.2", "M14,10a4,4 0 0,0 -5.7,0l-3,3a4,4 0 0,0 5.7,5.7l1.2,-1.2"], {}),
    "ic_menu_open": (["M14,4h6v6", "M20,4l-9,9", "M18,14v4.5a1.5,1.5 0 0,1 -1.5,1.5h-11A1.5,1.5 0 0,1 4,18.5v-11A1.5,1.5 0 0,1 5.5,6H10"], {}),
    "ic_menu_pdf": (["M14,3.5H7.5A1.5,1.5 0 0,0 6,5v14a1.5,1.5 0 0,0 1.5,1.5h9A1.5,1.5 0 0,0 18,19V7.5z", "M14,3.5v4h4", "M9,13h6", "M9,16.5h4"], {}),
    "ic_menu_pin": (["M9,4h6", "M10,4v5.5L7,13h10l-3,-3.5V4", "M12,13v7"], {}),
    "ic_menu_play": (["M8,5.5v13l10.5,-6.5z"], {}),
    "ic_menu_reader": (["M12,6.5C10.5,5.3 8.5,4.5 4,4.5V18c4.5,0 6.5,0.8 8,2 1.5,-1.2 3.5,-2 8,-2V4.5c-4.5,0 -6.5,0.8 -8,2z", "M12,6.5V20"], {}),
    "ic_menu_reopen": (["M9,14L4,9l5,-5", "M4,9h10.5a5.5,5.5 0 0,1 0,11H11"], {"mirror": True}),
    "ic_menu_screenshot": (["M4,8V5.5A1.5,1.5 0 0,1 5.5,4H8", "M16,4h2.5A1.5,1.5 0 0,1 20,5.5V8", "M20,16v2.5a1.5,1.5 0 0,1 -1.5,1.5H16",
                            "M8,20H5.5A1.5,1.5 0 0,1 4,18.5V16", circle(12, 12, 2.5)], {}),
    "ic_menu_settings": (gear(), {}),
    "ic_menu_share": (["M12,4v11", "M8,8l4,-4 4,4", "M7.5,11H6a1.5,1.5 0 0,0 -1.5,1.5v6A1.5,1.5 0 0,0 6,20h12a1.5,1.5 0 0,0 1.5,-1.5v-6A1.5,1.5 0 0,0 18,11h-1.5"], {}),
    "ic_menu_shield": (["M12,3.5l7,2.6v5.4c0,4.3 -2.9,7.6 -7,9 -4.1,-1.4 -7,-4.7 -7,-9V6.1z", "M9,12l2.2,2.2L15.2,10"], {}),
    "ic_quick_shield": (["M12,3.5l7,2.6v5.4c0,4.3 -2.9,7.6 -7,9 -4.1,-1.4 -7,-4.7 -7,-9V6.1z", "M9,12l2.2,2.2L15.2,10"], {}),
    "ic_menu_store": (["M4.5,9.5V19a1,1 0 0,0 1,1h13a1,1 0 0,0 1,-1V9.5", "M3.5,9.5L5,4.5h14l1.5,5z", "M9.5,20v-5h5v5"], {}),
    "ic_menu_translate": (["M4,5.5h9", "M8.5,4v1.5", "M11,5.5c-1,3.8 -3.6,6.7 -6.5,8", "M6.5,9c1.2,2.2 3,3.8 5,4.6", "M13,20l3.5,-8.5L20,20", "M14.2,17.2h4.6"], {}),
    "ic_menu_update": ([circle(12, 12, 8.5), "M12,16V8", "M8.5,11.5L12,8l3.5,3.5"], {}),
    "ic_menu_volume_on": (["M4,9.5v5h3.5l4.5,4V5.5l-4.5,4z", "M15.5,9a4,4 0 0,1 0,6", "M18,6.5a7.5,7.5 0 0,1 0,11"], {}),
    "ic_menu_volume_off": (["M4,9.5v5h3.5l4.5,4V5.5l-4.5,4z", "M16,9.5l5,5", "M21,9.5l-5,5"], {}),
    "ic_menu_workspace": ([rrect(4, 4, 6.5, 6.5, 1.6), rrect(13.5, 4, 6.5, 6.5, 1.6), rrect(4, 13.5, 6.5, 6.5, 1.6), rrect(13.5, 13.5, 6.5, 6.5, 1.6)], {}),
    "ic_view_grid": ([rrect(4, 4, 6.5, 6.5, 1.6), rrect(13.5, 4, 6.5, 6.5, 1.6), rrect(4, 13.5, 6.5, 6.5, 1.6), rrect(13.5, 13.5, 6.5, 6.5, 1.6)], {}),
    "ic_view_list": (["M9,6.5h11", "M9,12h11", "M9,17.5h11", "M4.5,6.5h0.5", "M4.5,12h0.5", "M4.5,17.5h0.5"], {}),
    "ic_view_compact": (["M4,6h16", "M4,10h16", "M4,14h16", "M4,18h16"], {}),
    "ic_ai_benchmark": (["M4,6h9", "M4,12h14", "M4,18h6"], {}),
    "ic_ai_collapse": (["M4,14h6v6", "M20,10h-6V4", "M14,10l6,-6", "M4,20l6,-6"], {}),
    "ic_ai_expand": (["M14,4h6v6", "M10,20H4v-6", "M20,4l-6,6", "M4,20l6,-6"], {}),
    "ic_ai_send": (["M12,19V5", "M6,11l6,-6 6,6"], {}),
    "ic_extension_placeholder": (["M9,4.5a2,2 0 0,1 4,0V6h3.5a1,1 0 0,1 1,1v3.5H19a2,2 0 0,1 0,4h-1.5V18a1,1 0 0,1 -1,1H13v-1.5a2,2 0 0,0 -4,0V19H5.5a1,1 0 0,1 -1,-1v-3.5H6a2,2 0 0,0 0,-4H4.5V7a1,1 0 0,1 1,-1H9z"], {}),
    "ic_pip": ([rrect(3, 5, 18, 14, 2), rrect(12, 11.5, 6, 4.5, 1)], {}),
    "ic_nexo_aperture": ([arc(12, 12, 8.4, -30, 270), arc(12, 12, 4.6, 150, 220)], {"dot": (12, 12, 1.6), "stroke": 1.7 * 24 / SIZE_DP}),
}

FILLED = {
    "ic_ai_stop": rrect(7, 7, 10, 10, 2.2),
}


def vector(paths, opts):
    mirror = '\n    android:autoMirrored="true"' if opts.get("mirror") else ""
    width = opts.get("stroke", STROKE)
    body = "\n".join(
        f'    <path\n        android:pathData="{d}"\n        android:strokeColor="#FFFFFFFF"\n'
        f'        android:strokeWidth="{width:.2f}"\n        android:strokeLineCap="round"\n'
        f'        android:strokeLineJoin="round" />' for d in paths)
    if "dot" in opts:
        cx, cy, r = opts["dot"]
        body += f'\n    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{circle(cx, cy, r)}" />'
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Nexo UI 7.0: icono lineal (trazo 1.6dp, extremos redondos). Generado por tools/generate_line_icons.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{SIZE_DP}dp"
    android:height="{SIZE_DP}dp"
    android:viewportWidth="24"
    android:viewportHeight="24"{mirror}>
{body}
</vector>
"""


def filled(d):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Nexo UI 7.0. Generado por tools/generate_line_icons.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{SIZE_DP}dp"
    android:height="{SIZE_DP}dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{d}" />
</vector>
"""


if __name__ == "__main__":
    for name, (paths, opts) in ICONS.items():
        (OUT / f"{name}.xml").write_text(vector(paths, opts))
    for name, d in FILLED.items():
        (OUT / f"{name}.xml").write_text(filled(d))
    print(f"{len(ICONS) + len(FILLED)} iconos")
