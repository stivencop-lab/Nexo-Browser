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

    interface StateListener {
        void onLoaded(String url, boolean canGoBack, boolean canGoForward, String failure);
    }

    private final CefriumBrowser browser;
    private final CefriumProfile profile;
    private final CefriumCookieStore cookies;
    private final CefriumDownloadManager downloads;
    private final CefriumMediaController media;
    private final CefriumPermissionHandler permissions;
    private final CefriumShieldInterceptor shield;
    private String navigationError;
    private String currentUrl = "";
    private StateListener stateListener;

    private CefriumSession(CefriumBrowser browser, CefriumProfile profile, String testOrigin, Hooks hooks) {
        this.browser = browser;
        this.profile = profile;
        this.cookies = new CefriumCookieStore(profile.getCookieManager());
        this.downloads = new CefriumDownloadManager(profile);
        this.media = new CefriumMediaController(browser, hooks.media());
        this.permissions = new CefriumPermissionHandler(hooks.permissions());
        this.shield = new CefriumShieldInterceptor(testOrigin + "/filtered");

        browser.setNavigationClient(new CefriumNavigationClient() {
            @Override public void onUrlChanged(CefriumBrowser b, String url) { currentUrl = url == null ? "" : url; }
            @Override public void onLoadStart(CefriumBrowser b, String url) {
                currentUrl = url == null ? "" : url;
                navigationError = null;
            }
            @Override public void onLoadingStateChanged(CefriumBrowser b, boolean loading, boolean back, boolean fwd) {
                Log.i(Nexo.TAG, "nav loading=" + loading + " back=" + back + " fwd=" + fwd + " url=" + currentUrl);
                if (!loading && stateListener != null) stateListener.onLoaded(currentUrl, back, fwd, navigationError);
            }
            @Override public void onLoadError(CefriumBrowser b, CefriumLoadError error) {
                if (error.getErrorCode() == -3) return;
                Log.w(Nexo.TAG, "load error " + error);
                navigationError = error.getErrorCode() + " " + error.getDescription();
                if (stateListener != null) stateListener.onLoaded(error.getFailedUrl(), false, false, navigationError);
            }
            @Override public void onRenderProcessTerminated(CefriumBrowser b, CefriumRendererTerminationStatus status, int code) {
                navigationError = "renderer " + status + " (" + code + ")";
                if (stateListener != null) stateListener.onLoaded(currentUrl, false, false, navigationError);
            }
        });
        browser.setResourceInterceptor(shield);
        browser.setQueryHandler(
                CefriumBrowser.QueryOriginPolicy.exactly(testOrigin),
                new CefriumPageBridge(hooks.bridge()));

        CefriumChromeClient console = new CefriumChromeClient() {
            @Override public void onConsoleMessage(CefriumBrowser b, CefriumConsoleMessage m) {
                Log.i(Nexo.TAG, "console[" + m.level() + "] " + m.message());
            }
        };
        browser.setChromeClient(new CefriumCompositeChromeClient(
                permissions,
                new CefriumWindowHandler(hooks.windows()),
                new CefriumFullscreenHandler(hooks.fullscreen()),
                media,
                console));
        browser.setDownloadClient(downloads);
        downloads.attachObserver();
    }

    static CefriumSession create(Activity activity, boolean incognito, String testOrigin, Hooks hooks) {
        CefriumProfile profile = incognito ? CefriumProfile.incognito() : CefriumProfile.getDefault();
        CefriumBrowser browser = CefriumBrowser.createWithSurface(activity, profile);
        Log.i(Nexo.TAG, "session created incognito=" + profile.isIncognito() + " profile=" + profile.getName());
        return new CefriumSession(browser, profile, testOrigin, hooks);
    }

    FrameLayout view() { return browser.getSurfaceContainer(); }
    CefriumBrowser browser() { return browser; }
    boolean isIncognito() { return profile.isIncognito(); }
    CefriumCookieStore cookies() { return cookies; }
    boolean blockedSmokeTarget() { return shield.blockedSmokeTarget(); }
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
        stateListener = null;
        permissions.close();
        downloads.detachObserver();
        browser.close();
    }
}
