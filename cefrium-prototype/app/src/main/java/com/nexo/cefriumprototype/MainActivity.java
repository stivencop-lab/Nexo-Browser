package com.nexo.cefriumprototype;

import android.app.Activity;
import android.os.Bundle;

public final class MainActivity extends Activity {
    private static final String STATE_CEF = "cefrium_state";
    private CefriumSession session;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        session = CefriumSession.create(this, false);
        setContentView(session.view());
        if (state != null) session.restoreState(state.getBundle(STATE_CEF));
        else session.loadUrl("https://example.com/");
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (session != null) state.putBundle(STATE_CEF, session.saveState());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { if (session != null) session.close(); super.onDestroy(); }
}
