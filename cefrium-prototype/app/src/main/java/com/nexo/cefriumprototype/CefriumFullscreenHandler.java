package com.nexo.cefriumprototype;

import android.util.Log;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

import java.util.function.Consumer;

final class CefriumFullscreenHandler implements CefriumChromeClient {
    private final Consumer<Boolean> onFullscreen;

    CefriumFullscreenHandler(Consumer<Boolean> onFullscreen) { this.onFullscreen = onFullscreen; }

    @Override public void onFullscreenChanged(CefriumBrowser browser, boolean fullscreen) {
        Log.i(Nexo.TAG, "fullscreen=" + fullscreen);
        if (onFullscreen != null) onFullscreen.accept(fullscreen);
    }
}
