package com.onroonlink.nativev1.netshare;

final class PairInfo {
    String localHost;
    int localPort;
    String publicHost;
    int publicPort;
    String role;
    String password;
    String fp;

    static boolean validPassword(String p) {
        if (p == null || p.length() < 4 || p.length() > 8) return false;
        for (int i = 0; i < p.length(); i++) if (!Character.isDigit(p.charAt(i))) return false;
        return true;
    }

    static String[] splitHostPort(String s) {
        int k = s.lastIndexOf(':');
        if (k < 1) throw new IllegalArgumentException("주소 오류");
        return new String[]{s.substring(0, k), s.substring(k + 1)};
    }
}
