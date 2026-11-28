package com.nexo.cefriumprototype;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class SmokeServer implements AutoCloseable {
    private final ServerSocket server;
    volatile boolean controlReached;
    volatile boolean filteredReached;

    SmokeServer(int port) throws IOException {
        server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 8);
        } catch (IOException error) {
            server.close();
            throw error;
        }
        Thread worker = new Thread(this::serve, "NexoSmokeHttp");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return server.getLocalPort(); }
    String origin() { return "http://127.0.0.1:" + port(); }
    String foreignOrigin() { return "http://localhost:" + server.getLocalPort(); }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(1500);
                BufferedReader input = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
                String request = input.readLine();
                if (request == null || !request.startsWith("GET ")) continue;
                String path = request.split(" ", 3)[1];
                for (int i = 0; i < 64; i++) {
                    String header = input.readLine();
                    if (header == null || header.isEmpty()) break;
                }
                if ("/control".equals(path)) controlReached = true;
                if ("/filtered".equals(path)) filteredReached = true;
                byte[] body = ("<!doctype html><meta charset=utf-8><title>Cefrium local</title>"
                        + "<h1>Cefrium local</h1><p>Prueba de navegación y permisos</p>"
                        + "<button onclick=\"navigator.mediaDevices.getUserMedia({audio:true})"
                        + ".then(s=>s.getTracks().forEach(t=>t.stop())).catch(console.error)\">Micrófono</button>")
                        .getBytes(StandardCharsets.UTF_8);
                OutputStream output = peer.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: " + body.length
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(body);
            } catch (IOException error) {
                if (!server.isClosed()) Log.w(Nexo.TAG, "Local smoke HTTP request failed", error);
            }
        }
    }

    @Override public void close() {
        try {
            server.close();
        } catch (IOException error) {
            Log.w(Nexo.TAG, "Local smoke server shutdown failed", error);
        }
    }
}
