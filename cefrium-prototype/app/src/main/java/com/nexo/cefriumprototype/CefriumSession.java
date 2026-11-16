package com.nexo.cefriumprototype;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumProfile;

/** Public-API-only session adapter used to prove the BrowserEngine shape. */
final class CefriumSession {
    private final CefriumBrowser browser;
    private final ViewGroup surface;
    private String currentUrl = "";
    private CefriumSession(CefriumBrowser browser, ViewGroup surface) { this.browser = browser; this.surface = surface; browser.setNavigationClient(new CefriumNavigationClient() { @Override public void onUrlChanged(CefriumBrowser ignored, String url) { currentUrl = url == null ? "" : url; } }); }
    static CefriumSession create(Activity activity, boolean incognito) { CefriumProfile profile = incognito ? CefriumProfile.incognito() : CefriumProfile.getDefault(); CefriumBrowser browser = CefriumBrowser.createWithSurface(activity, profile); View surface = browser.getSurfaceContainer(); FrameLayout container = new FrameLayout(activity); container.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); return new CefriumSession(browser, container); }
    ViewGroup view() { return surface; }
    String url() { return currentUrl; }
    void loadUrl(String url) { browser.loadUrl(url); }
    void reload() { browser.reload(); }
    boolean canGoBack() { return browser.canGoBack(); }
    void goBack() { browser.goBack(); }
    boolean canGoForward() { return browser.canGoForward(); }
    void goForward() { browser.goForward(); }
    Bundle saveState() { return browser.saveState(); }
    void restoreState(Bundle state) { browser.restoreState(state); }
    void close() { browser.close(); }
}
