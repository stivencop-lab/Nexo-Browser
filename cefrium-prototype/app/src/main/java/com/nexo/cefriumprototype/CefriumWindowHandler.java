package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

import java.util.function.Consumer;

final class CefriumWindowHandler {
    private final Consumer<CefriumWindow> onWindow;

    CefriumWindowHandler(Consumer<CefriumWindow> onWindow) { this.onWindow = onWindow; }

    CefriumChromeClient createChromeClient() {
        return new CefriumChromeClient() {
            @Override public boolean onCreateWindow(CefriumBrowser browser, boolean isIncognito, boolean isPopup, boolean isRated, String targetUrl, CefriumWindow window) {
                if (onWindow != null) onWindow.accept(window);
                return true;
            }
        };
    }
}
