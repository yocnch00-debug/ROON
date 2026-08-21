package com.onsharelink.host;
import android.content.*;import java.security.SecureRandom;
final class Pairing {
    static String code(Context c){SharedPreferences p=c.getSharedPreferences("sharelink",0);String v=p.getString("pairing_code",null);if(v==null||!v.matches("\\d{8}")){v=random();p.edit().putString("pairing_code",v).apply();}return v;}
    static String regenerate(Context c){String v=random();c.getSharedPreferences("sharelink",0).edit().putString("pairing_code",v).apply();return v;}
    static boolean setCode(Context c,String v){if(v==null||!v.matches("\\d{8}"))return false;c.getSharedPreferences("sharelink",0).edit().putString("pairing_code",v).apply();return true;}
    private static String random(){return String.format("%08d",new SecureRandom().nextInt(100000000));}
    private Pairing(){}
}
