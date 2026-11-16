package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumMediaSession;

final class CefriumMediaController {
    private final CefriumBrowser browser;
    private final Consumer<MediaInfo> onMedia;

    CefrumMediaController(CefriumBrowser browser, Consumer<MediaInfo> onMedia) {
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
