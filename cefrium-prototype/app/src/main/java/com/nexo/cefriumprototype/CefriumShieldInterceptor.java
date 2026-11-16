package com.nexo.cefriumprototype;

import android.util.Log;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumResourceInterceptor;

import java.net.URI;
import java.util.Locale;

/** Small, thread-safe network hook proving PhantomShield's Cefrium seam. Runs on the network thread. */
final class CefriumShieldInterceptor implements CefriumResourceInterceptor {
    private static final String[] TRACKING_HOSTS = {"doubleclick.net", "googlesyndication.com", "adservice.google.com"};

    @Override public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest request) {
        String host = hostOf(request.url);
        if (host == null) return false;
        for (String blocked : TRACKING_HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                Log.i(Nexo.TAG, "shield blocked " + request.method + " " + request.url);
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
