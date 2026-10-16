package com.politichell.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Склеивает наш пак с паком карты NMinimap (plugins/NMinimap/built-pack.zip) в один zip — игроку приходит ОДИН пак.
 * Файлы карты лежат в своих папках (assets/nminimap/…, nminimap_*​/…), с нашими не пересекаются;
 * pack.mcmeta — наш, к нему добавляются overlays карты (в них — шейдеры под разные версии клиента).
 */
final class PackMerge {
    private PackMerge() {}

    static byte[] merge(byte[] ours, byte[] nm) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, byte[]> theirs = new LinkedHashMap<>();
        read(ours, out);
        read(nm, theirs);
        JsonObject mc = JsonParser.parseString(new String(out.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] nmMeta = theirs.remove("pack.mcmeta");
        if (nmMeta != null) {
            JsonObject their = JsonParser.parseString(new String(nmMeta, StandardCharsets.UTF_8)).getAsJsonObject();
            if (their.has("overlays")) {
                JsonObject ov = mc.has("overlays") ? mc.getAsJsonObject("overlays") : new JsonObject();
                JsonArray entries = ov.has("entries") ? ov.getAsJsonArray("entries") : new JsonArray();
                for (JsonElement e : their.getAsJsonObject("overlays").getAsJsonArray("entries")) entries.add(e);
                ov.add("entries", entries);
                mc.add("overlays", ov);
            }
        }
        out.put("pack.mcmeta", mc.toString().getBytes(StandardCharsets.UTF_8));
        for (var e : theirs.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (var e : out.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0L);                       // детерминированный sha1 при одинаковом содержимом
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    private static void read(byte[] zip, Map<String, byte[]> into) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                into.put(e.getName(), in.readAllBytes());
            }
        }
    }
}
