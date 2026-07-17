package dev.suika.game;

/**
 * Loads the displayed product version from the Gradle-generated
 * {@code suika-version.properties} resource so {@link Theme#VERSION} cannot drift
 * from {@code build.gradle.kts}. Fallback keeps IDE runs working before a build.
 */
final class SuikaVersion {

    private SuikaVersion() {}

    /** Fallback only — production builds overwrite via resource generation. */
    static final String FALLBACK = "0.19.0";

    static String current() {
        try (var in = SuikaVersion.class.getResourceAsStream("/suika-version.properties")) {
            if (in == null) return FALLBACK;
            var props = new java.util.Properties();
            props.load(in);
            String v = props.getProperty("version", "").trim();
            if (v.isEmpty() || v.contains("${") || v.equals("@VERSION@")) return FALLBACK;
            return v;
        } catch (Exception e) {
            return FALLBACK;
        }
    }
}
