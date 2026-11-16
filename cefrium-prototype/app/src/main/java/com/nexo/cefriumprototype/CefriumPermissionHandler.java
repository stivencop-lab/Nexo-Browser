package com.nexo.cefriumprototype;

import android.util.Log;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumPermission;
import com.cefrium.CefriumPermissionRequest;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns Cefrium permission requests into prompts. Cefrium answers the whole
 * set at once: grant() or deny() covers every permission in the request.
 */
final class CefriumPermissionHandler implements CefriumChromeClient {
    private final Consumer<PermissionPrompt> onPrompt;

    CefriumPermissionHandler(Consumer<PermissionPrompt> onPrompt) { this.onPrompt = onPrompt; }

    @Override public void onPermissionRequest(CefriumBrowser browser, CefriumPermissionRequest request) {
        Log.i(Nexo.TAG, "permission request origin=" + request.getOrigin() + " perms=" + request.getPermissions());
        if (onPrompt == null) { request.deny(); return; }
        onPrompt.accept(new PermissionPrompt(request));
    }

    @Override public void onPermissionRequestCanceled(CefriumBrowser browser, CefriumPermissionRequest request) {
        Log.i(Nexo.TAG, "permission request canceled origin=" + request.getOrigin());
    }

    record PermissionPrompt(CefriumPermissionRequest request) {
        String origin() { return request.getOrigin(); }
        Set<CefriumPermission> permissions() { return request.getPermissions(); }
        void grant() { Log.i(Nexo.TAG, "permission granted " + permissions()); request.grant(); }
        void deny() { Log.i(Nexo.TAG, "permission denied " + permissions()); request.deny(); }
    }
}
