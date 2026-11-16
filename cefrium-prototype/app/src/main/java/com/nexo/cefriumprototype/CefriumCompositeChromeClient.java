package com.nexo.cefriumprototype;

import android.view.KeyEvent;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumConsoleMessage;
import com.cefrium.CefriumContextMenu;
import com.cefrium.CefriumFileChooserCallback;
import com.cefrium.CefriumFileChooserParams;
import com.cefrium.CefriumJsDialogType;
import com.cefrium.CefriumPermissionRequest;

import java.util.List;

/**
 * CefriumBrowser has a single chrome-client slot, so every adapter that needs
 * chrome callbacks is fanned out from here. Void callbacks reach every client;
 * boolean callbacks stop at the first client that handles them. Permission
 * requests go only to the permission handler: CefriumChromeClient's default
 * onPermissionRequest denies, so fanning it out would answer before the user.
 */
final class CefriumCompositeChromeClient implements CefriumChromeClient {
    private final CefriumPermissionHandler permissions;
    private final CefriumChromeClient[] clients;

    CefriumCompositeChromeClient(CefriumPermissionHandler permissions, CefriumChromeClient... clients) {
        this.permissions = permissions;
        this.clients = clients.clone();
    }

    @Override public void onTitleChanged(CefriumBrowser b, String title) {
        for (CefriumChromeClient c : clients) c.onTitleChanged(b, title);
    }

    @Override public void onFaviconUrlsChanged(CefriumBrowser b, List<String> urls) {
        for (CefriumChromeClient c : clients) c.onFaviconUrlsChanged(b, urls);
    }

    @Override public void onFullscreenChanged(CefriumBrowser b, boolean fullscreen) {
        for (CefriumChromeClient c : clients) c.onFullscreenChanged(b, fullscreen);
    }

    @Override public void onConsoleMessage(CefriumBrowser b, CefriumConsoleMessage message) {
        for (CefriumChromeClient c : clients) c.onConsoleMessage(b, message);
    }

    @Override public void onStatusMessage(CefriumBrowser b, String text) {
        for (CefriumChromeClient c : clients) c.onStatusMessage(b, text);
    }

    @Override public void onMediaAccessChanged(CefriumBrowser b, boolean camera, boolean microphone) {
        for (CefriumChromeClient c : clients) c.onMediaAccessChanged(b, camera, microphone);
    }

    @Override public void onFindResult(CefriumBrowser b, int activeMatch, int matches) {
        for (CefriumChromeClient c : clients) c.onFindResult(b, activeMatch, matches);
    }

    @Override public boolean onJsDialog(CefriumBrowser b, CefriumJsDialogType type, String message,
            String defaultText, CefriumBrowser.JsDialogCallback callback) {
        for (CefriumChromeClient c : clients) if (c.onJsDialog(b, type, message, defaultText, callback)) return true;
        return false;
    }

    @Override public boolean onBeforeUnloadDialog(CefriumBrowser b, String message, boolean isReload,
            CefriumBrowser.JsDialogCallback callback) {
        for (CefriumChromeClient c : clients) if (c.onBeforeUnloadDialog(b, message, isReload, callback)) return true;
        return false;
    }

    @Override public boolean onShowFileChooser(CefriumBrowser b, CefriumFileChooserParams params,
            CefriumFileChooserCallback callback) {
        for (CefriumChromeClient c : clients) if (c.onShowFileChooser(b, params, callback)) return true;
        return false;
    }

    @Override public boolean onContextMenu(CefriumBrowser b, CefriumContextMenu menu) {
        for (CefriumChromeClient c : clients) if (c.onContextMenu(b, menu)) return true;
        return false;
    }

    @Override public boolean shouldOverrideKeyEvent(CefriumBrowser b, KeyEvent event) {
        for (CefriumChromeClient c : clients) if (c.shouldOverrideKeyEvent(b, event)) return true;
        return false;
    }

    @Override public void onUnhandledKeyEvent(CefriumBrowser b, KeyEvent event) {
        for (CefriumChromeClient c : clients) c.onUnhandledKeyEvent(b, event);
    }

    @Override public boolean onCreateWindow(CefriumBrowser b, CefriumBrowser popup, String url, boolean userGesture) {
        for (CefriumChromeClient c : clients) if (c.onCreateWindow(b, popup, url, userGesture)) return true;
        return false;
    }

    @Override public void onCloseWindow(CefriumBrowser b, CefriumBrowser popup) {
        for (CefriumChromeClient c : clients) c.onCloseWindow(b, popup);
    }

    @Override public void onPermissionRequest(CefriumBrowser b, CefriumPermissionRequest request) {
        if (permissions != null) permissions.onPermissionRequest(b, request);
        else request.deny();
    }

    @Override public void onPermissionRequestCanceled(CefriumBrowser b, CefriumPermissionRequest request) {
        if (permissions != null) permissions.onPermissionRequestCanceled(b, request);
    }
}
