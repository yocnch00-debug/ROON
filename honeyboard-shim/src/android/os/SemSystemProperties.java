package android.os;

public final class SemSystemProperties {
    private SemSystemProperties() {}

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String def) {
        if (key == null || key.isEmpty()) return def == null ? "" : def;
        try {
            String v = System.getProperty(key);
            return (v == null || v.isEmpty()) ? (def == null ? "" : def) : v;
        } catch (Throwable ignored) {
            return def == null ? "" : def;
        }
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = get(key, "");
        if (v.isEmpty()) return def;
        if ("1".equals(v) || "true".equalsIgnoreCase(v) || "y".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)) return true;
        if ("0".equals(v) || "false".equalsIgnoreCase(v) || "n".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)) return false;
        return def;
    }

    public static int getInt(String key, int def) {
        String v = get(key, "");
        if (v.isEmpty()) return def;
        try { return Integer.decode(v); }
        catch (Throwable ignored) { return def; }
    }

    public static String getSalesCode() {
        // Korea open-market CSC fallback; HoneyBoard only needs a stable non-null value.
        return "KOO";
    }
}
