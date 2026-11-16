package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

import java.util.function.Consumer;

final class CefriumFullscreenHandler {
    private final Consumer<Boolean> onFullscreen;

    CefriumFullscreenHandler(Consumer<Boolean> onFullscreen) {
        this.onFullscreen = onFullscreen;
    }

    CefriumChromeClient createChromeClient() {
        return new CefriumChromeClient() {
            @Override public void onFullscreenChanged(CefriumBrowser b, boolean fullscreen) {
                if (onFullscreen != null) onFullscreen.accept(fullscreen);
            }
        };
    }
}
