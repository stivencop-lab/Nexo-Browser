package com.necko.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumChromeClient;
import com.cefrium.CefriumPermission;
import com.cefrium.CefriumPermissionRequest;

final class CefriumPermissionHandler {
    private final CefriumChromeClient chromeClient;

    CefriumPermissionHandler(Consumer<PermissionPrompt> onPrompt) {
        this.chromeClient = new CefriumChromeClient() {
            @Override public void onPermissionRequest(CefriumBrowser browser, CefriumPermissionRequest request) {
                String[] perms = request.getPermissions();
                boolean[] granted = new boolean[perms.length];
                onPrompt.accept(new PermissionPrompt(request, granted));
            }
        };
    }

    CefriumChromeClient chromeClient() { return chromeClient; }

    record PermissionPrompt(CefriumPermissionRequest request, boolean[] response) {
        void grant(int index) { response[index] = true; }
        void deny(int index) { response[index] = false; }
        void answer() { request.grant(response); }
    }
}
