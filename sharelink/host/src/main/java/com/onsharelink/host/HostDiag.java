package com.onsharelink.host;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class HostDiag {
    private static final String TAG="ONShareLinkHost";
    private static final long MAX=192*1024L;

    static synchronized void log(Context c,String s){
        Log.i(TAG,s);
        try{
            File f=new File(c.getFilesDir(),"sharelink-host.log");
            if(f.exists()&&f.length()>MAX){File old=new File(c.getFilesDir(),"sharelink-host.log.1");if(old.exists())old.delete();f.renameTo(old);}
            String ts=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());
            try(FileWriter w=new FileWriter(f,true)){w.write(ts+" "+s+"\n");}
        }catch(Exception ignored){}
    }

    static synchronized String tail(Context c){
        StringBuilder out=new StringBuilder();
        appendTail(out,new File(c.getFilesDir(),"sharelink-host.log.1"),7000);
        appendTail(out,new File(c.getFilesDir(),"sharelink-host.log"),18000);
        return out.toString();
    }

    private static void appendTail(StringBuilder out,File f,int chars){
        if(!f.exists())return;
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            long len=r.length(),start=Math.max(0,len-chars);r.seek(start);byte[] b=new byte[(int)(len-start)];r.readFully(b);out.append(new String(b,"UTF-8"));
        }catch(Exception ignored){}
    }
    private HostDiag(){}
}
