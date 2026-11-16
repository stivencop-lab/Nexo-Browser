package com.nexo.cefriumprototype;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumConsoleMessage;
import com.cefrium.CefriumLoadError;
import com.cefrium.CefriumNavigationClient;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumRendererTerminationStatus;

import java.util.function.Consumer;

/**
 * Public-API-only session adapter used to prove the BrowserEngine shape: one
 * CefriumBrowser with every Nexo seam (shield, bridge, chrome callbacks,
 * downloads, cookies) attached. All calls on the main thread.
 */
final class CefriumSession {
    /** Host callbacks; any may be null. */
    record Hooks(
            CefriumPageBridge.Listener bridge,
            Consumer<CefriumPermissionHandler.PermissionPrompt> permissions,
            CefriumWindowHandler.Listener windows,
            Consumer<Boolean> fullscreen,
            Consumer<CefriumMediaController.MediaAccess> media) {}

    interface StateListener { void onLoaded(String url, boolean canGoBack, boolean canGoForward); }

    static final String BRIDGE_ORIGIN = "https://example.com";

    private final CefriumBrowser browser;
    private final CefriumProfile profile;
    private final CefriumCookieStore cookies;
    private final CefriumDownloadManager downloads;
    private final CefriumMediaController media;
    private String currentUrl = "";
    private StateListener stateListener;

    private CefriumSession(CefriumBrowser browser, CefriumProfile profile, Hooks hooks) {
        this.browser = browser;
        this.profile = profile;
        this.cookies = new CefriumCookieStore(profile.getCookieManager());
        this.downloads = new CefriumDownloadManager(profile);
        this.media = new CefriumMediaController(browser, hooks.media());

        browser.setNavigationClient(new CefriumNavigationClient() {
            @Override public void onUrlChanged(CefriumBrowser b, String url) { currentUrl = url == null ? "" : url; }
            @Override public void onLoadingStateChanged(CefriumBrowser b, boolean loading, boolean back, boolean fwd) {
                Log.i(Nexo.TAG, "nav loading=" + loading + " back=" + back + " fwd=" + fwd + " url=" + currentUrl);
                if (!loading && stateListener != null) stateListener.onLoaded(currentUrl, back, fwd);
            }
            @Override public void onLoadError(CefriumBrowser b, CefriumLoadError error) {
                Log.w(Nexo.TAG, "load error " + error);
            }
            @Override public void onRenderProcessTerminated(CefriumBrowser b, CefriumRendererTerminationStatus status, int code) {
                Log.e(Nexo.TAG, "renderer terminated " + status + " code=" + code);
            }
        });
        browser.setResourceInterceptor(new CefriumShieldInterceptor());
        browser.setQueryHandler(
                CefriumBrowser.QueryOriginPolicy.exactly(BRIDGE_ORIGIN),
                new CefriumPageBridge(hooks.bridge()));

        CefriumChromeClient console = new CefriumChromeClient() {
            @Override public void onConsoleMessage(CefriumBrowser b, CefriumConsoleMessage m) {
                Log.i(Nexo.TAG, "console[" + m.level() + "] " + m.message());
            }
        };
        browser.setChromeClient(new CefriumCompositeChromeClient(
                new CefriumPermissionHandler(hooks.permissions()),
                new CefriumWindowHandler(hooks.windows()),
                new CefriumFullscreenHandler(hooks.fullscreen()),
                media,
                console));
        browser.setDownloadClient(downloads);
        downloads.attachObserver();
    }

    static CefriumSession create(Activity activity, boolean incognito, Hooks hooks) {
        CefriumProfile profile = incognito ? CefriumProfile.incognito() : CefriumProfile.getDefault();
        CefriumBrowser browser = CefriumBrowser.createWithSurface(activity, profile);
        Log.i(Nexo.TAG, "session created incognito=" + profile.isIncognito() + " profile=" + profile.getName());
        return new CefriumSession(browser, profile, hooks);
    }

    FrameLayout view() { return browser.getSurfaceContainer(); }
    CefriumBrowser browser() { return browser; }
    boolean isIncognito() { return profile.isIncognito(); }
    CefriumCookieStore cookies() { return cookies; }
    CefriumDownloadManager downloads() { return downloads; }
    CefriumMediaController media() { return media; }
    void setStateListener(StateListener l) { stateListener = l; }

    String url() { return currentUrl; }
    void loadUrl(String url) { browser.loadUrl(url); }
    void reload() { browser.reload(); }
    boolean canGoBack() { return browser.canGoBack(); }
    void goBack() { browser.goBack(); }
    boolean canGoForward() { return browser.canGoForward(); }
    void goForward() { browser.goForward(); }
    void evaluateJavaScript(String script) { browser.evaluateJavaScript(script); }
    Bundle saveState() { return browser.saveState(); }
    void restoreState(Bundle state) { if (state != null) browser.restoreState(state); }
    void onResume() { browser.onResume(); }
    void onPause() { browser.onPause(); }

    void close() {
        downloads.detachObserver();
        browser.close();
    }
}
