package com.nexo.cefriumprototype;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.cefrium.CefriumBrowser;

/**
 * Smoke test. On a fresh start it walks two local origins, back,
 * forward and reload and logs PASS/FAIL lines under the NexoCefrium tag:
 *   adb logcat -s NexoCefrium CefriumBrowser
 * Start with "--ez incognito true" to run the same checks in a private profile.
 */
public final class MainActivity extends androidx.activity.ComponentActivity {
    private static final String SMOKE_COOKIE = "nexo_smoke";
    private static final String INCOGNITO_COOKIE = "nexo_incog";

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private CefriumSession session;
    private CefriumBrowser popup;
    private boolean incognito;
    private int step;
    private SmokeServer smoke;
    private String first;
    private String second;
    private String expecting;
    private final Runnable timeout = () -> failSmoke("timeout cargando " + expecting);
    private CefriumPermissionHandler.PermissionPrompt androidRequest;
    private final androidx.activity.result.ActivityResultLauncher<String[]> permissions =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                var current = androidRequest;
                androidRequest = null;
                if (current == null) return;
                var required = androidPermissions(current);
                if (required != null && canGrant(required)) current.grant();
                else current.deny();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int previousPort = state == null ? 0 : state.getInt("smoke_port");
        try {
            try {
                smoke = new SmokeServer(previousPort);
            } catch (java.io.IOException error) {
                if (previousPort == 0) throw error;
                smoke = new SmokeServer(0);
            }
        } catch (java.io.IOException error) {
            failSmoke("no se pudo iniciar el servidor local");
            return;
        }
        first = smoke.origin() + "/first";
        second = smoke.foreignOrigin() + "/second";
        incognito = getIntent().getBooleanExtra("incognito", false);
        session = CefriumSession.create(this, incognito, smoke.origin(), new CefriumSession.Hooks(
                (type, payload) -> Log.i(Nexo.TAG, "bridge message type=" + type + " payload=" + payload),
                this::showPermissionPrompt,
                new PopupHost(),
                this::setFullscreen,
                access -> { }));
        root = new FrameLayout(this);
        root.addView(session.view(), match());
        setContentView(root);
        session.setStateListener(this::onLoaded);

        Bundle saved = state == null ? null : state.getBundle("cefrium_state");
        if (saved != null && previousPort == smoke.port()) {
            step = -1;
            session.restoreState(saved);
        } else {
            Log.i(Nexo.TAG, "SMOKE start incognito=" + incognito);
            navigate(first, () -> session.loadUrl(first));
        }
    }

    private void navigate(String url, Runnable navigate) {
        expecting = url;
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 10_000L);
        navigate.run();
    }

    private void failSmoke(String reason) {
        step = -1;
        expecting = null;
        main.removeCallbacks(timeout);
        check(reason, false);
    }

    private void onLoaded(String url, boolean canGoBack, boolean canGoForward, String failure) {
        if (expecting == null || "about:blank".equals(url)) return;
        if (failure != null) { failSmoke("navegación: " + failure); return; }
        if (!expecting.equals(url)) return;
        main.removeCallbacks(timeout);
        expecting = null;
        switch (step++) {
            case 0:
                check("primera página local cargada", true);
                runCookieChecks();
                session.evaluateJavaScript(BRIDGE_AND_SHIELD_JS);
                waitForShield(android.os.SystemClock.uptimeMillis() + 5_000L);
                break;
            case 1:
                check("atrás disponible en el segundo origen", canGoBack);
                session.evaluateJavaScript(FOREIGN_BRIDGE_JS);
                later(() -> navigate(first, session::goBack));
                break;
            case 2:
                check("adelante disponible al volver", canGoForward);
                later(() -> navigate(second, session::goForward));
                break;
            case 3:
                check("adelante volvió al segundo origen", true);
                later(() -> navigate(second, session::reload));
                break;
            case 4:
                check("recarga completada", true);
                Bundle state = session.saveState();
                check("saveState no está vacío", state != null && !state.isEmpty());
                Log.i(Nexo.TAG, "SMOKE navigation done");
                later(() -> session.loadUrl(first));
                break;
            default:
                break;
        }
    }

    private void waitForShield(long deadline) {
        boolean controlled = smoke.controlReached && session.blockedSmokeTarget();
        if (controlled || android.os.SystemClock.uptimeMillis() >= deadline) {
            check("interceptor bloqueó /filtered y /control llegó al servidor",
                    controlled && !smoke.filteredReached);
            navigate(second, () -> session.loadUrl(second));
            return;
        }
        main.postDelayed(() -> waitForShield(deadline), 100);
    }

    private static final String BRIDGE_AND_SHIELD_JS =
            "(function(){"
            + "if(typeof window.cefriumQuery === 'function') window.cefriumQuery({"
            + "request: JSON.stringify({type:'smoke'}),"
            + "onSuccess: function(r){ console.log('NEXO PASS bridge local: ' + r); },"
            + "onFailure: function(c, m){ console.log('NEXO FAIL bridge local: ' + c + ' ' + m); }});"
            + "else console.log('NEXO FAIL bridge local ausente');"
            + "fetch('/control').then(function(r){ if(!r.ok) throw Error(r.status); })"
            + ".catch(function(e){ console.log('NEXO FAIL control: ' + e); });"
            + "fetch('/filtered').then(function(){ console.log('NEXO FAIL filtered respondió'); })"
            + ".catch(function(e){ console.log('NEXO filtered rechazado: ' + e); });"
            + "})();";

    private static final String FOREIGN_BRIDGE_JS =
            "(function(){"
            + "if (typeof window.cefriumQuery !== 'function') { console.log('NEXO PASS bridge absent on foreign origin'); return; }"
            + "window.cefriumQuery({request: JSON.stringify({type:'smoke'}),"
            + " onSuccess: function(){ console.log('NEXO FAIL bridge answered a foreign origin'); },"
            + " onFailure: function(c, m){ console.log('NEXO PASS bridge denied foreign origin: ' + c + ' ' + m); }});"
            + "})();";

    /** Each profile writes its own cookie and must not see the other one's. */
    private void runCookieChecks() {
        CefriumCookieStore cookies = session.cookies();
        String own = incognito ? INCOGNITO_COOKIE : SMOKE_COOKIE;
        String other = incognito ? SMOKE_COOKIE : INCOGNITO_COOKIE;
        cookies.setCookie(first, own + "=1; Max-Age=3600; Path=/")
                .thenCompose(ok -> { check("setCookie accepted", Boolean.TRUE.equals(ok)); return cookies.cookieHeader(first); })
                .thenAccept(header -> main.post(() -> {
                    check("profile sees its own cookie " + own, header.contains(own + "="));
                    check("profile does not see " + other, !header.contains(other + "="));
                }));
    }

    private String[] androidPermissions(CefriumPermissionHandler.PermissionPrompt prompt) {
        var required = new java.util.LinkedHashSet<String>();
        for (var permission : prompt.permissions()) {
            switch (permission) {
                case CAMERA -> required.add(Manifest.permission.CAMERA);
                case MICROPHONE -> required.add(Manifest.permission.RECORD_AUDIO);
                case GEOLOCATION -> {
                    required.add(Manifest.permission.ACCESS_FINE_LOCATION);
                    required.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                }
                case NOTIFICATIONS -> {
                    if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS);
                }
                case CLIPBOARD_READ -> {}
                default -> { return null; }
            }
        }
        return required.toArray(new String[0]);
    }

    private boolean canGrant(String[] required) {
        for (String permission : required) {
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) continue;
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) continue;
            return false;
        }
        return true;
    }

    private void showPermissionPrompt(CefriumPermissionHandler.PermissionPrompt prompt) {
        String[] required = androidPermissions(prompt);
        if (required == null || androidRequest != null || isFinishing() || isDestroyed()) {
            prompt.deny();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(prompt.origin())
                .setMessage("Permitir " + prompt.permissions() + "?")
                .setPositiveButton("Permitir", (d, w) -> {
                    if (canGrant(required)) prompt.grant();
                    else {
                        androidRequest = prompt;
                        permissions.launch(required);
                    }
                })
                .setNegativeButton("Bloquear", (d, w) -> prompt.deny())
                .setOnCancelListener(d -> prompt.deny())
                .create();
        prompt.onResolved = dialog::dismiss;
        dialog.show();
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
        if (session != null && smoke != null) {
            state.putBundle("cefrium_state", session.saveState());
            state.putInt("smoke_port", smoke.port());
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); if (session != null) session.onResume(); }

    @Override protected void onPause() { if (session != null) session.onPause(); super.onPause(); }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        closePopup();
        if (session != null) session.close();
        if (smoke != null) smoke.close();
        super.onDestroy();
    }
}
