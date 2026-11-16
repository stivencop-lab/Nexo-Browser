package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import org.json.JSONObject;

/** Origin-restricted public Cefrium JS bridge; no Android JS interface is used. */
final class CefriumPageBridge implements CefriumBrowser.QueryHandler {
    interface Listener { void onMessage(String type, JSONObject payload); }
    private final Listener listener;
    CefriumPageBridge(Listener listener) { this.listener = listener; }

    @Override public boolean onQuery(long id, String request, String origin, CefriumBrowser.QueryCallback callback) {
        return handle(id, request, origin, true, callback);
    }

    @Override public boolean onQuery(long id, String request, String origin, boolean mainFrame, CefriumBrowser.QueryCallback callback) {
        return handle(id, request, origin, mainFrame, callback);
    }

    private boolean handle(long id, String request, String origin, boolean mainFrame, CefriumBrowser.QueryCallback callback) {
        if (!mainFrame || listener == null) { callback.failure(-403, "main frame required"); return true; }
        try {
            JSONObject message = new JSONObject(request);
            String type = message.optString("type", "");
            listener.onMessage(type, message);
            callback.success(new JSONObject().put("ok", true).toString());
        } catch (Exception error) {
            callback.failure(-400, "invalid bridge message");
        }
        return true;
    }
}
