package com.politichell.hud;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Крошечный встроенный веб-сервер: раздаёт ресурспак ИЗ САМОГО ПЛАГИНА (пак лежит внутри jar, поэтому он всегда той же версии, что и плагин).
 * Клиент Minecraft скачивает пак по ссылке http://адрес:порт/PoliticHellHUD.zip. Ничего ставить и настраивать не надо.
 */
public class PackServer {
    private HttpServer server;
    private volatile byte[] data = new byte[0];
    private int port;

    public synchronized void start(int port, byte[] data) throws IOException {
        this.data = data;
        if (server != null && this.port == port) return;           // уже работает — только обновили содержимое
        stop();
        this.port = port;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            byte[] d = this.data;
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            if ("HEAD".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(200, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, d.length);
            try (var out = ex.getResponseBody()) { out.write(d); }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "PoliticHell-pack-http"); t.setDaemon(true); return t; }));
        server.start();
    }

    public synchronized void update(byte[] data) { this.data = data; }

    public synchronized void stop() {
        if (server != null) { server.stop(0); server = null; }
    }

    public boolean running() { return server != null; }
    public int port() { return port; }
}
