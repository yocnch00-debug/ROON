package com.onsharelink.host;
import android.content.*;import java.security.SecureRandom;
final class Pairing {
    static String code(Context c){SharedPreferences p=c.getSharedPreferences("sharelink",0);String v=p.getString("pairing_code",null);if(v==null||v.length()!=8){v=String.format("%08d",new SecureRandom().nextInt(100000000));p.edit().putString("pairing_code",v).apply();}return v;}
    static String regenerate(Context c){String v=String.format("%08d",new SecureRandom().nextInt(100000000));c.getSharedPreferences("sharelink",0).edit().putString("pairing_code",v).apply();return v;}
    private Pairing(){}
}
