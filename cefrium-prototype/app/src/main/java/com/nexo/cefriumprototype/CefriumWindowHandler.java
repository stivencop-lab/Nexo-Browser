package com.nexo.cefriumprototype;

import android.util.Log;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;

/**
 * window.open / target=_blank. Cefrium hands over the popup as a new
 * CefriumBrowser (same mode and profile, window.opener kept). Returning true
 * means the host shows it and owns closing it; false closes it.
 */
final class CefriumWindowHandler implements CefriumChromeClient {
    interface Listener {
        boolean onPopup(CefriumBrowser opener, CefriumBrowser popup, String url, boolean userGesture);
        void onPopupClosed(CefriumBrowser opener, CefriumBrowser popup);
    }

    private final Listener listener;

    CefriumWindowHandler(Listener listener) { this.listener = listener; }

    @Override public boolean onCreateWindow(CefriumBrowser opener, CefriumBrowser popup, String url, boolean userGesture) {
        boolean shown = listener != null && listener.onPopup(opener, popup, url, userGesture);
        Log.i(Nexo.TAG, "popup url=" + url + " gesture=" + userGesture + " shown=" + shown);
        return shown;
    }

    @Override public void onCloseWindow(CefriumBrowser opener, CefriumBrowser popup) {
        Log.i(Nexo.TAG, "popup closed by page");
        if (listener != null) listener.onPopupClosed(opener, popup);
    }
}
