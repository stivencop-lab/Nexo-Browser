package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumPermission;
import com.cefrium.CefriumPermissionRequest;

import java.util.function.Consumer;

final class CefriumPermissionHandler {
    private final CefriumChromeClient chromeClient;

    CefriumPermissionHandler(Consumer<PermissionPrompt> onPrompt) {
        this.chromeClient = new CefriumChromeClient() {
            @Override public void onPermissionRequest(CefriumBrowser browser, CefriumPermissionRequest request) {
                Set<CefriumPermission> perms = request.getPermissions();
                boolean[] granted = new boolean[perms.size()];
                int idx = 0;
                for (CefriumPermission p : perms) {
                    onPrompt.accept(new PermissionPrompt(request, idx, granted));
                    idx++;
                }
            }
        };
    }

    CefriumChromeClient chromeClient() { return chromeClient; }

    record PermissionPrompt(CefriumPermissionRequest request, int index, boolean[] granted) {
        void grant() { request.grant(granted); }
        void deny() { request.deny(granted); }
    }
}
