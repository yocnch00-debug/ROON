package com.onsharelink.client;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class Diag {
    private static final String TAG="ONShareLink";
    private static final long MAX=192*1024L;
    static synchronized void log(Context c,String s){
        Log.i(TAG,s);
        try{
            File f=new File(c.getFilesDir(),"sharelink-client.log");
            if(f.exists()&&f.length()>MAX){File old=new File(c.getFilesDir(),"sharelink-client.log.1");if(old.exists())old.delete();f.renameTo(old);}
            String ts=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());
            try(FileWriter w=new FileWriter(f,true)){w.write(ts+" "+s+"\n");}
        }catch(Exception ignored){}
    }
    static synchronized String tail(Context c){
        StringBuilder out=new StringBuilder();
        appendTail(out,new File(c.getFilesDir(),"sharelink-client.log"),18000);
        File hev=new File(c.getFilesDir(),"hev-sharelink.log");
        if(hev.exists()){out.append("\n--- HEV ---\n");appendTail(out,hev,12000);}
        return out.toString();
    }
    private static void appendTail(StringBuilder out,File f,int chars){
        if(!f.exists())return;
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            long len=r.length(), start=Math.max(0,len-chars);r.seek(start);byte[] b=new byte[(int)(len-start)];r.readFully(b);out.append(new String(b,"UTF-8"));
        }catch(Exception ignored){}
    }
    private Diag(){}
}
