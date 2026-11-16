package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumResourceInterceptor;

import java.util.Locale;

/** Small, thread-safe network hook proving PhantomShield's Cefrium seam. */
final class CefriumShieldInterceptor implements CefriumResourceInterceptor {
    private static final String[] TRACKING_HOSTS = {"doubleclick.net", "googlesyndication.com", "adservice.google.com"};

    @Override public boolean shouldBlockRequest(CefriumBrowser.ResourceRequest request) {
        String url = request.url == null ? "" : request.url.toLowerCase(Locale.ROOT);
        for (String host : TRACKING_HOSTS) {
            if (url.contains(host)) return true;
        }
        return false;
    }
}
