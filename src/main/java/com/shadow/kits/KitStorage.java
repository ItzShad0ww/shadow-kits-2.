package com.shadow.kits;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads and writes kits to config/shadowkits.json. */
public final class KitStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private KitStorage() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("shadowkits.json");
    }

    private static JsonObject read() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class);
                if (o != null) return o;
            }
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    private static boolean write(JsonObject o) {
        try {
            Files.writeString(file(), GSON.toJson(o));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean put(String name, JsonArray slots) {
        JsonObject o = read();
        o.add(key(name), slots);
        return write(o);
    }

    public static JsonArray get(String name) {
        JsonObject o = read();
        return o.has(key(name)) ? o.getAsJsonArray(key(name)) : null;
    }

    public static boolean remove(String name) {
        JsonObject o = read();
        if (!o.has(key(name))) return false;
        o.remove(key(name));
        return write(o);
    }

    public static List<String> names() {
        return new ArrayList<>(read().keySet());
    }
}
