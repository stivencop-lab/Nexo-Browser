package com.nexo.cefriumprototype;

import android.util.Log;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

import java.util.function.Consumer;

/**
 * Media seam. Cefrium 0.21.0 exposes no playback-metadata callback on its
 * public API: it reports camera/microphone use, and the host polls or drives
 * playback (isPlayingAudio, mute, stop). The media notification comes from
 * setMediaSessionEnabled (needs POST_NOTIFICATIONS on Android 13+).
 */
final class CefriumMediaController implements CefriumChromeClient {
    record MediaAccess(boolean camera, boolean microphone) {}

    private final CefriumBrowser browser;
    private final Consumer<MediaAccess> onAccess;

    CefriumMediaController(CefriumBrowser browser, Consumer<MediaAccess> onAccess) {
        this.browser = browser;
        this.onAccess = onAccess;
        browser.setMediaSessionEnabled(true);
    }

    @Override public void onMediaAccessChanged(CefriumBrowser b, boolean camera, boolean microphone) {
        Log.i(Nexo.TAG, "media access camera=" + camera + " mic=" + microphone);
        if (onAccess != null) onAccess.accept(new MediaAccess(camera, microphone));
    }

    boolean isPlayingAudio() { return browser.isPlayingAudio(); }
    boolean isMuted() { return browser.isAudioMuted(); }
    void setMuted(boolean muted) { browser.setAudioMuted(muted); }
    void stop() { browser.stopMediaSession(); }
    void setPlaybackRequiresUserGesture(boolean require) { browser.setMediaPlaybackRequiresUserGesture(require); }
}
