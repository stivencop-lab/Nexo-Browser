package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

import java.util.function.Consumer;

/** Media session wrapper using Cefrium's media callbacks. */
final class CefriumMediaController {
    private final CefriumBrowser browser;
    private final Consumer<MediaInfo> onMedia;

    CefriumMediaController(CefriumBrowser browser, Consumer<MediaInfo> onMedia) {
        this.browser = browser;
        this.onMedia = onMedia;
        browser.setChromeClient(new CefriumChromeClient() {
            @Override public void onMediaPlaybackStateChanged(CefriumBrowser b, boolean playing, String title, String artist, String artworkUrl) {
                if (onMedia != null) onMedia.accept(new MediaInfo(playing, title, artist, artworkUrl));
            }
        });
        browser.setMediaSessionEnabled(true);
    }

    record MediaInfo(boolean playing, String title, String artist, String artworkUrl) {}
    void setPlaybackRequiresUserGesture(boolean require) { browser.setMediaPlaybackRequiresUserGesture(require); }
}
