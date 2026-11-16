package com.nexo.cefriumprototype;

import com.cefrium.CefriumCookie;
import com.cefrium.CefriumCookieManager;

import java.util.concurrent.CompletableFuture;

final class CefriumCookieStore {
    private final CefriumCookieManager manager;
    CefriumCookieStore(CefriumCookieManager manager) { this.manager = manager; }
    CompletableFuture<CefriumCookie> getCookie(String url, String name) {
        CompletableFuture<CefriumCookie> future = new CompletableFuture<>();
        manager.getCookie(url, name, cookie -> future.complete(cookie));
        return future;
    }
    CompletableFuture<String> getCookies(String url) {
        CompletableFuture<String> future = new CompletableFuture<>();
        manager.getCookies(url, cookies -> future.complete(cookies == null ? "" : cookies));
        return future;
    }
    CompletableFuture<Void> setCookie(String url, String name, String value, boolean secure, boolean httpOnly, int sameSite) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        manager.setCookie(url, name, value, secure, httpOnly, sameSite, ok -> future.complete(null));
        return future;
    }
    CompletableFuture<Void> removeCookie(String url, String name) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        manager.removeCookie(url, name, future::complete);
        return future;
    }
    CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        manager.flush(future::complete);
        return future;
    }
}
