package com.nexo.cefriumprototype;

import com.cefrium.CefriumCookie;
import com.cefrium.CefriumCookieManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Future-based wrapper over CefriumCookieManager (one per profile; incognito has its own). */
final class CefriumCookieStore {
    private final CefriumCookieManager manager;

    CefriumCookieStore(CefriumCookieManager manager) { this.manager = manager; }

    /** The Cookie header url would send: "a=1; b=2". */
    CompletableFuture<String> cookieHeader(String url) {
        CompletableFuture<String> future = new CompletableFuture<>();
        manager.getCookie(url, future::complete);
        return future;
    }

    CompletableFuture<List<CefriumCookie>> cookies(String url) {
        CompletableFuture<List<CefriumCookie>> future = new CompletableFuture<>();
        manager.getCookies(url, future::complete);
        return future;
    }

    /** setCookie is a Set-Cookie value, e.g. "id=42; Max-Age=3600; Path=/; Secure; SameSite=Lax". */
    CompletableFuture<Boolean> setCookie(String url, String setCookie) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        manager.setCookie(url, setCookie, future::complete);
        return future;
    }

    /** name null removes every cookie of url. Completes with how many were removed. */
    CompletableFuture<Integer> removeCookies(String url, String name) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        manager.removeCookies(url, name, future::complete);
        return future;
    }

    CompletableFuture<Integer> removeAll() {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        manager.removeAllCookies(future::complete);
        return future;
    }

    CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        manager.flush(() -> future.complete(null));
        return future;
    }
}
