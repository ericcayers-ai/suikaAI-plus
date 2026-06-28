package dev.suika.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Loads and exposes the data-driven fruit ladder from {@code fruits.json}.
 *
 * <p>This is a zero-dependency JSON parser (simple enough for our fixed schema)
 * so the assets module remains lightweight without pulling in a JSON library.
 * If the schema grows, replace with Jackson/Gson.
 */
public final class AssetRegistry {

    private static final AssetRegistry INSTANCE = load();

    private final List<FruitDefinition>        fruits;
    private final Map<Integer, FruitDefinition> byTier;
    private final int                           doubleWatermelonBonus;

    private AssetRegistry(List<FruitDefinition> fruits, int doubleWatermelonBonus) {
        this.fruits = List.copyOf(fruits);
        LinkedHashMap<Integer, FruitDefinition> map = new LinkedHashMap<>();
        for (FruitDefinition d : fruits) map.put(d.tier(), d);
        this.byTier = Collections.unmodifiableMap(map);
        this.doubleWatermelonBonus = doubleWatermelonBonus;
    }

    public static AssetRegistry get() { return INSTANCE; }

    public List<FruitDefinition> fruits()               { return fruits; }
    public FruitDefinition       byTier(int tier)       { return byTier.get(tier); }
    public int                   doubleWatermelonBonus() { return doubleWatermelonBonus; }
    public int                   maxTier()              { return fruits.stream().mapToInt(FruitDefinition::tier).max().orElse(11); }

    public List<FruitDefinition> droppableFruits() {
        return fruits.stream().filter(FruitDefinition::droppable).toList();
    }

    // -------------------------------------------------------------------------
    // Simple hand-rolled JSON parser (no external deps)
    // -------------------------------------------------------------------------

    private static AssetRegistry load() {
        InputStream is = AssetRegistry.class.getResourceAsStream("/fruits.json");
        if (is == null) throw new IllegalStateException("fruits.json not found on classpath");
        String json;
        try (is; Scanner sc = new Scanner(is, StandardCharsets.UTF_8)) {
            json = sc.useDelimiter("\\A").next();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return parse(json);
    }

    private static AssetRegistry parse(String json) {
        int bonus = extractInt(json, "\"doubleWatermelonBonus\"");
        List<FruitDefinition> defs = parseFruits(json);
        return new AssetRegistry(defs, bonus);
    }

    private static List<FruitDefinition> parseFruits(String json) {
        int start = json.indexOf("\"fruits\"");
        int arrStart = json.indexOf('[', start);
        int arrEnd   = json.lastIndexOf(']');
        String arr   = json.substring(arrStart + 1, arrEnd);

        List<String> objects = splitObjects(arr);
        return objects.stream().map(AssetRegistry::parseFruitObject).toList();
    }

    private static List<String> splitObjects(String arr) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0) result.add(arr.substring(start, i + 1)); }
        }
        return result;
    }

    private static FruitDefinition parseFruitObject(String obj) {
        int    tier        = extractInt(obj, "\"tier\"");
        String name        = extractString(obj, "\"name\"");
        double radius      = extractDouble(obj, "\"radius\"");
        int    mergeScore  = extractInt(obj, "\"mergeScore\"");
        boolean droppable  = extractBool(obj, "\"droppable\"");
        double density     = extractDouble(obj, "\"density\"");
        return new FruitDefinition(tier, name, radius, mergeScore, droppable, density);
    }

    private static int extractInt(String s, String key) {
        int i     = s.indexOf(key) + key.length();
        int colon = s.indexOf(':', i);
        int start = colon + 1;
        while (start < s.length() && !Character.isDigit(s.charAt(start)) && s.charAt(start) != '-') start++;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        return Integer.parseInt(s.substring(start, end));
    }

    private static double extractDouble(String s, String key) {
        int i     = s.indexOf(key) + key.length();
        int colon = s.indexOf(':', i);
        int start = colon + 1;
        while (start < s.length() && !Character.isDigit(s.charAt(start)) && s.charAt(start) != '-') start++;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.' || s.charAt(end) == '-')) end++;
        return Double.parseDouble(s.substring(start, end));
    }

    private static String extractString(String s, String key) {
        int i     = s.indexOf(key) + key.length();
        int colon = s.indexOf(':', i);
        int q1    = s.indexOf('"', colon + 1);
        int q2    = s.indexOf('"', q1 + 1);
        return s.substring(q1 + 1, q2);
    }

    private static boolean extractBool(String s, String key) {
        int i     = s.indexOf(key) + key.length();
        int colon = s.indexOf(':', i);
        String rest = s.substring(colon + 1).stripLeading();
        return rest.startsWith("true");
    }
}
