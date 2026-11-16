package com.nexo.cefriumprototype;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.cefrium.CefriumBrowser;

/** Minimal surface-mode smoke app kept separate from Nexo's current Gradle build. */
public final class MainActivity extends Activity {
    private CefriumBrowser browser;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        browser = CefriumBrowser.createWithSurface(this);
        View surface = browser.getSurfaceContainer();
        FrameLayout root = new FrameLayout(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        browser.loadUrl("https://example.com/");
    }
    @Override protected void onResume() { super.onResume(); if (browser != null) browser.onResume(); }
    @Override protected void onPause() { if (browser != null) browser.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (browser != null) browser.close(); super.onDestroy(); }
}
