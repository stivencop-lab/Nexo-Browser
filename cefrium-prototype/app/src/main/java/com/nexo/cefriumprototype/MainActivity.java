package com.nexo.cefriumprototype;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.cefrium.CefriumBrowser;

/**
 * Smoke test. On a fresh start it walks example.com -> iana.org -> back ->
 * forward -> reload and logs PASS/FAIL lines under the NexoCefrium tag:
 *   adb logcat -s NexoCefrium CefriumBrowser
 * Start with "--ez incognito true" to run the same checks in a private profile.
 */
public final class MainActivity extends Activity {
    private static final String STATE_CEF = "cefrium_state";
    private static final String HOME = "https://example.com/";
    private static final String SECOND = "https://www.iana.org/help/example-domains";
    private static final String SMOKE_COOKIE = "nexo_smoke";
    private static final String INCOGNITO_COOKIE = "nexo_incog";

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private CefriumSession session;
    private CefriumBrowser popup;
    private boolean incognito;
    private int step;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        incognito = getIntent().getBooleanExtra("incognito", false);
        session = CefriumSession.create(this, incognito, new CefriumSession.Hooks(
                (type, payload) -> Log.i(Nexo.TAG, "bridge message type=" + type + " payload=" + payload),
                this::showPermissionPrompt,
                new PopupHost(),
                this::setFullscreen,
                access -> { }));
        root = new FrameLayout(this);
        root.addView(session.view(), match());
        setContentView(root);
        session.setStateListener(this::onLoaded);

        Bundle saved = state == null ? null : state.getBundle(STATE_CEF);
        if (saved != null) {
            step = -1; // restored: no smoke walk
            session.restoreState(saved);
        } else {
            Log.i(Nexo.TAG, "SMOKE start incognito=" + incognito);
            session.loadUrl(HOME);
        }
    }

    private void onLoaded(String url, boolean canGoBack, boolean canGoForward) {
        String host = Uri.parse(url).getHost();
        switch (step) {
            case 0:
                if (!"example.com".equals(host)) return;
                step = 1;
                check("example.com rendered", true);
                runBridgeAndShieldChecks();
                runCookieChecks();
                later(() -> session.loadUrl(SECOND));
                break;
            case 1:
                if (!"www.iana.org".equals(host)) return;
                step = 2;
                check("canGoBack after second page", canGoBack);
                session.evaluateJavaScript(FOREIGN_BRIDGE_JS);
                later(session::goBack);
                break;
            case 2:
                if (!"example.com".equals(host)) return;
                step = 3;
                check("back returned to example.com", true);
                check("canGoForward after back", canGoForward);
                later(session::goForward);
                break;
            case 3:
                if (!"www.iana.org".equals(host)) return;
                step = 4;
                check("forward returned to iana.org", true);
                later(session::reload);
                break;
            case 4:
                if (!"www.iana.org".equals(host)) return;
                step = 5;
                check("reload finished", true);
                Bundle state = session.saveState();
                check("saveState non-empty", state != null && !state.isEmpty());
                Log.i(Nexo.TAG, "SMOKE navigation done; returning to example.com for manual checks");
                later(() -> session.loadUrl(HOME));
                break;
            default:
                break;
        }
    }

    private static final String BRIDGE_AND_SHIELD_JS =
            "(function(){"
            + "window.cefriumQuery({request: JSON.stringify({type:'smoke'}),"
            + " onSuccess: function(r){ console.log('NEXO PASS bridge on example.com: ' + r); },"
            + " onFailure: function(c, m){ console.log('NEXO FAIL bridge on example.com: ' + c + ' ' + m); }});"
            + "fetch('https://securepubads.g.doubleclick.net/tag/js/gpt.js', {mode:'no-cors'})"
            + " .then(function(){ console.log('NEXO FAIL shield: doubleclick request went through'); })"
            + " .catch(function(e){ console.log('NEXO PASS shield blocked doubleclick: ' + e); });"
            + "})();";

    private static final String FOREIGN_BRIDGE_JS =
            "(function(){"
            + "if (typeof window.cefriumQuery !== 'function') { console.log('NEXO PASS bridge absent on foreign origin'); return; }"
            + "window.cefriumQuery({request: JSON.stringify({type:'smoke'}),"
            + " onSuccess: function(){ console.log('NEXO FAIL bridge answered a foreign origin'); },"
            + " onFailure: function(c, m){ console.log('NEXO PASS bridge denied foreign origin: ' + c + ' ' + m); }});"
            + "})();";

    private void runBridgeAndShieldChecks() { session.evaluateJavaScript(BRIDGE_AND_SHIELD_JS); }

    /** Each profile writes its own cookie and must not see the other one's. */
    private void runCookieChecks() {
        CefriumCookieStore cookies = session.cookies();
        String own = incognito ? INCOGNITO_COOKIE : SMOKE_COOKIE;
        String other = incognito ? SMOKE_COOKIE : INCOGNITO_COOKIE;
        cookies.setCookie(HOME, own + "=1; Max-Age=3600; Path=/; Secure")
                .thenCompose(ok -> { check("setCookie accepted", Boolean.TRUE.equals(ok)); return cookies.cookieHeader(HOME); })
                .thenAccept(header -> main.post(() -> {
                    Log.i(Nexo.TAG, "cookies for example.com: " + header);
                    check("profile sees its own cookie " + own, header.contains(own + "="));
                    check("profile does not see " + other, !header.contains(other + "="));
                }));
    }

    private void showPermissionPrompt(CefriumPermissionHandler.PermissionPrompt prompt) {
        new AlertDialog.Builder(this)
                .setTitle(prompt.origin())
                .setMessage("Permitir " + prompt.permissions() + "?")
                .setPositiveButton("Permitir", (d, w) -> prompt.grant())
                .setNegativeButton("Bloquear", (d, w) -> prompt.deny())
                .setOnCancelListener(d -> prompt.deny())
                .show();
    }

    private void setFullscreen(boolean fullscreen) {
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (fullscreen) insets.hide(WindowInsetsCompat.Type.systemBars());
        else insets.show(WindowInsetsCompat.Type.systemBars());
    }

    /** Shows one popup over the page; back or window.close() dismisses it. */
    private final class PopupHost implements CefriumWindowHandler.Listener {
        @Override public boolean onPopup(CefriumBrowser opener, CefriumBrowser created, String url, boolean userGesture) {
            if (popup != null) return false;
            popup = created;
            root.addView(created.getSurfaceContainer(), match());
            return true;
        }

        @Override public void onPopupClosed(CefriumBrowser opener, CefriumBrowser closed) {
            if (closed == popup) closePopup();
        }
    }

    private void closePopup() {
        if (popup == null) return;
        root.removeView(popup.getSurfaceContainer());
        popup.close();
        popup = null;
    }

    private void check(String name, boolean ok) { Log.i(Nexo.TAG, (ok ? "PASS " : "FAIL ") + name); }

    private void later(Runnable r) { main.postDelayed(r, 1500); }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (popup != null) closePopup();
        else if (session != null && session.canGoBack()) session.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (session != null) state.putBundle(STATE_CEF, session.saveState());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); if (session != null) session.onResume(); }

    @Override protected void onPause() { if (session != null) session.onPause(); super.onPause(); }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        closePopup();
        if (session != null) session.close();
        super.onDestroy();
    }
}
