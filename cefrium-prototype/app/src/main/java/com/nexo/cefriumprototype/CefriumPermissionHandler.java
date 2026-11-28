package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumPermission;
import com.cefrium.CefriumPermissionRequest;

import java.util.Set;
import java.util.function.Consumer;

final class CefriumPermissionHandler implements CefriumChromeClient {
    private final Consumer<PermissionPrompt> onPrompt;
    private PermissionPrompt current;

    CefriumPermissionHandler(Consumer<PermissionPrompt> onPrompt) { this.onPrompt = onPrompt; }

    @Override public void onPermissionRequest(CefriumBrowser browser, CefriumPermissionRequest request) {
        if (onPrompt == null || current != null) {
            request.deny();
            return;
        }
        current = new PermissionPrompt(request);
        onPrompt.accept(current);
    }

    @Override public void onPermissionRequestCanceled(CefriumBrowser browser, CefriumPermissionRequest request) {
        if (current != null && current.request == request) current.resolve(null);
    }

    void close() {
        if (current != null) current.deny();
    }

    final class PermissionPrompt {
        private final CefriumPermissionRequest request;
        Runnable onResolved = () -> {};

        PermissionPrompt(CefriumPermissionRequest request) { this.request = request; }
        String origin() { return request.getOrigin(); }
        Set<CefriumPermission> permissions() { return request.getPermissions(); }
        void grant() { resolve(true); }
        void deny() { resolve(false); }

        private void resolve(Boolean grant) {
            if (current != this) return;
            current = null;
            if (Boolean.TRUE.equals(grant)) request.grant();
            else if (Boolean.FALSE.equals(grant)) request.deny();
            onResolved.run();
        }
    }
}
