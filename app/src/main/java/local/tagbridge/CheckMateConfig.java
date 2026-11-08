package local.tagbridge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Device-local setup. Never copy configured provider addresses into logs or public source. */
public final class CheckMateConfig {
    private static final String PREFS="checkmate_config";
    private CheckMateConfig(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static boolean isConfigured(Context c){return prefs(c).getBoolean("configured",false);}
    public static String tagAddress(Context c){return prefs(c).getString("tag_mac","");}
    public static JSONArray liveProviders(Context c){
        SharedPreferences p=prefs(c);
        if(p.contains("live_providers"))return normalizeLegacy(parse(p.getString("live_providers","[]")));
        if(p.getBoolean("configured",false))return new JSONArray();
        JSONArray legacy=asset(c,"live-providers.json");
        return normalizeLegacy(legacy.length()>0?legacy:asset(c,"providers.json"));
    }
    public static JSONArray buttonProviders(Context c){
        SharedPreferences p=prefs(c);
        if(p.contains("button_providers"))return normalizeLegacy(parse(p.getString("button_providers","[]")));
        if(!p.getBoolean("configured",false)){
            JSONArray legacy=asset(c,"providers.json");if(legacy.length()>0)return normalizeLegacy(legacy);
        }
        return liveProviders(c);
    }
    public static JSONArray manifestSnapshots(Context c){
        SharedPreferences p=prefs(c);
        if(p.contains("manifest_snapshots"))return parse(p.getString("manifest_snapshots","[]"));
        return p.getBoolean("configured",false)?new JSONArray():asset(c,"live-provider-manifests.json");
    }
    public static JSONArray snapshots(Context c){return manifestSnapshots(c);}
    private static JSONArray parse(String value){try{return new JSONArray(value);}catch(Exception invalid){return new JSONArray();}}
    private static JSONArray asset(Context c,String name){
        try{InputStream in=c.getAssets().open(name);try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)return new JSONArray();out.write(b,0,n);}
            return parse(out.toString("UTF-8"));
        }finally{in.close();}}catch(Exception absent){return new JSONArray();}
    }
    public static void migrateLegacy(Context c){
        if(isConfigured(c))return;
        JSONArray live=liveProviders(c);if(live.length()==0)return;
        JSONArray button=buttonProviders(c),snap=manifestSnapshots(c);String address="";
        try{InputStream in=c.getAssets().open("device.json");ByteArrayOutputStream b=new ByteArrayOutputStream();int x;while((x=in.read())!=-1)b.write(x);in.close();address=normalizeTag(new org.json.JSONObject(b.toString("UTF-8")).optString("tag_mac",""));}catch(Exception absent){}
        prefs(c).edit().putString("tag_mac",address).putString("live_providers",live.toString()).putString("button_providers",button.toString()).putString("manifest_snapshots",snap.toString()).putBoolean("configured",true).commit();
    }
    /** Nuvio may export a base address with query configuration rather than a manifest path. */
    public static String manifestFromBase(String value){
        int q=value.indexOf('?');String path=q<0?value:value.substring(0,q),query=q<0?"":value.substring(q);
        while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        return path+(path.endsWith("/manifest.json")?"":"/manifest.json")+query;
    }
    private static JSONArray normalizeLegacy(JSONArray values){
        JSONArray normalized=new JSONArray();
        for(int i=0;i<values.length();i++){
            String value=values.optString(i,"");if(value.isEmpty())continue;
            int q=value.indexOf('?');
            if(q>=0&&!value.substring(0,q).endsWith("/manifest.json")&&value.endsWith("/manifest.json"))value=value.substring(0,value.length()-14);
            normalized.put(manifestFromBase(value));
        }
        return normalized;
    }
    public static String providerBase(String manifest){
        int q=manifest.indexOf('?');String path=q<0?manifest:manifest.substring(0,q);
        if(!path.endsWith("/manifest.json"))throw new IllegalArgumentException("Invalid provider manifest");
        return path.substring(0,path.length()-14)+(q<0?"":manifest.substring(q));
    }
    public static String providerResource(String base,String path){
        int q=base.indexOf('?');return (q<0?base:base.substring(0,q))+"/"+path+(q<0?"":base.substring(q));
    }
    public static String normalizeTag(String value){
        String tag=value==null?"":value.trim().toUpperCase(Locale.ROOT);
        if(tag.length()>0&&!tag.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))
            throw new IllegalArgumentException("Use a Bluetooth address such as AA:BB:CC:DD:EE:FF, or leave it empty.");
        return tag;
    }
    public static JSONArray parseProviderLines(String lines){
        LinkedHashSet<String> unique=new LinkedHashSet<String>();
        String[] rows=(lines==null?"":lines).split("\\r?\\n");
        for(int i=0;i<rows.length;i++){
            String value=rows[i].trim();if(value.length()==0)continue;
            try{
                if(value.length()>16384)throw new Exception();
                URI uri=new URI(value);
                String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
                if((!"http".equals(scheme)&&!"https".equals(scheme))||uri.getHost()==null
                        ||uri.getFragment()!=null||uri.getPath()==null||!uri.getPath().endsWith("/manifest.json")
                        ||uri.getPort()>65535||uri.getPort()==0)throw new Exception();
                String normalized=uri.normalize().toASCIIString();
                normalized=scheme+normalized.substring(normalized.indexOf(':'));
                unique.add(normalized);
            }catch(Exception invalid){throw new IllegalArgumentException("Provider line "+(i+1)+": enter a complete HTTP(S) URL ending in /manifest.json.");}
            if(unique.size()>64)throw new IllegalArgumentException("Use no more than 64 provider manifests.");
        }
        JSONArray providers=new JSONArray();for(String value:unique)providers.put(value);return providers;
    }
    public static String providerLines(Context c){
        JSONArray values=liveProviders(c);StringBuilder result=new StringBuilder();
        for(int i=0;i<values.length();i++){String url=values.optString(i,"");if(url.length()>0){if(result.length()>0)result.append('\n');result.append(url);}}
        return result.toString();
    }
    public static void save(Context c,String tag,String lines){
        String normalized=normalizeTag(tag);JSONArray providers=parseProviderLines(lines);
        if(!prefs(c).edit().putString("tag_mac",normalized).putString("live_providers",providers.toString())
                .remove("button_providers").remove("manifest_snapshots").putBoolean("configured",true).commit())
            throw new IllegalStateException("Could not save setup. Check available device storage and retry.");
    }
}
