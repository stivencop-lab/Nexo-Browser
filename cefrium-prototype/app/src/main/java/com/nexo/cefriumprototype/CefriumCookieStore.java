package com.nexo.cefriumprototype;

import com.cefrium.CefriumCookie;
import com.cefrium.CefriumCookieManager;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.Consumer;

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
        manager.getCookies(url, list -> {
            StringBuilder b = new StringBuilder();
            for (CefriumCookie c : list) {
                if (b.length() > 0) b.append("; ");
                b.append(c.getName()).append('=').append(c.getValue());
            }
            future.complete(b.toString());
        });
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
