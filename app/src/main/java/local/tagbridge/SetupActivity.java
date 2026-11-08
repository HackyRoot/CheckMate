package local.tagbridge;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

/** Native remote-friendly setup; no account, SDK or external service is required. */
public final class SetupActivity extends Activity {
    private EditText tag,providers;
    private TextView message;
    private LinearLayout form;
    private volatile boolean closed;
    private volatile java.net.HttpURLConnection importing;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(15,20,29));
        form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(48,24,48,36);scroll.addView(form);setContentView(scroll);
        label("CheckMate setup",28);
        label("Add your own provider manifests. CheckMate checks live streams locally on this TV. A Bluetooth button is optional.",18);
        label("Bluetooth tag address — optional",20);
        tag=edit("AA:BB:CC:DD:EE:FF",false);tag.setText(CheckMateConfig.tagAddress(this));
        label("Get a provider URL: CNCVerse Bridge example",20);
        label("1. On your phone or computer, open cncverse.dpdns.org.\n2. Choose or configure a profile containing live-TV sources.\n3. Use Copy Addon URL. Copy the complete manifest address, not the configuration page.\n4. Paste that address below and save. Keep any profile token private.\n\nIf CNCVerse Bridge runs on this same TV, copy the manifest address shown by its Server screen instead. Keep that bridge running.",17);
        label("Provider manifest URLs — one per line",20);
        providers=edit("https://your-provider.example/manifest.json",true);providers.setText(CheckMateConfig.providerLines(this));
        label("Use only providers you are authorized to access. Addresses and tokens stay in this app’s private device storage.",16);
        message=label("Save setup, then install the local addon in Nuvio. Enable CheckMate under Android accessibility settings for profile selection and playback-error handling.",17);
        button("Import installed providers from Nuvio",new View.OnClickListener(){public void onClick(View v){importFromNuvio((Button)v);}});
        label("To import: in Nuvio open Settings → Addons → Manage from phone, leave its QR screen open, then return here and import. Nothing is uploaded to CheckMate or its developer.",16);
        button("Save and start CheckMate",new View.OnClickListener(){public void onClick(View v){save();}});
        button("Copy local Nuvio addon URL",new View.OnClickListener(){public void onClick(View v){
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("CheckMate addon",LiveAddonServer.MANIFEST_URL));
            show("Copied local addon URL. Install it in Nuvio on this TV.",false);
        }});
        button("Open accessibility settings",new View.OnClickListener(){public void onClick(View v){
            try{startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            catch(RuntimeException missing){show("Open Android settings, then Accessibility, and enable CheckMate.",true);}
        }});
        button("Back to CheckMate",new View.OnClickListener(){public void onClick(View v){finish();}});
    }
    private void importFromNuvio(final Button button){
        button.setEnabled(false);show("Looking for Nuvio’s local addon configuration…",false);
        new Thread(new Runnable(){public void run(){
            String found=null;
            for(int port=8080;port<=8089&&!closed;port++){
                java.net.HttpURLConnection c=null;
                try{
                    c=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+"/api/addons").openConnection();importing=c;
                    c.setConnectTimeout(500);c.setReadTimeout(1200);c.setInstanceFollowRedirects(false);
                    if(c.getResponseCode()!=200)continue;
                    java.io.InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                    try{while((n=in.read(buffer))!=-1){if(closed||out.size()+n>2*1024*1024)throw new java.io.IOException();out.write(buffer,0,n);}}finally{in.close();}
                    org.json.JSONArray addons=new org.json.JSONArray(out.toString("UTF-8"));java.util.LinkedHashSet<String> urls=new java.util.LinkedHashSet<String>();
                    for(int i=0;i<addons.length();i++){
                        org.json.JSONObject addon=addons.optJSONObject(i);String url=addon==null?addons.optString(i,""):addon.optString("url","");
                        try{java.net.URI uri=new java.net.URI(url);String host=uri.getHost();if(("127.0.0.1".equals(host)||"localhost".equals(host))&&uri.getPort()==LiveAddonServer.PORT)continue;
                            org.json.JSONArray parsed=CheckMateConfig.parseProviderLines(url);if(parsed.length()==1)urls.add(parsed.getString(0));
                        }catch(Exception ignored){}
                    }
                    if(!urls.isEmpty()){StringBuilder lines=new StringBuilder();for(String url:urls){if(lines.length()>0)lines.append('\n');lines.append(url);}found=lines.toString();break;}
                }catch(Exception unavailable){}finally{if(c!=null)c.disconnect();importing=null;}
            }
            final String result=found;
            runOnUiThread(new Runnable(){public void run(){if(closed||isFinishing()||isDestroyed())return;button.setEnabled(true);
                if(result==null)show("Open Nuvio → Settings → Addons → Manage from phone, keep its QR screen open, then try Import again.",true);
                else{providers.setText(result);show("Imported "+CheckMateConfig.parseProviderLines(result).length()+" provider manifests locally. Review them, then Save. No provider data was sent off the TV by this import.",false);}
            }});
        }},"CheckMateNuvioImport").start();
    }
    @Override public void onDestroy(){closed=true;java.net.HttpURLConnection c=importing;if(c!=null)c.disconnect();super.onDestroy();}
    private TextView label(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(0,10,0,10);form.addView(t);return t;}
    private EditText edit(String hint,boolean multiline){
        EditText e=new EditText(this);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(Color.LTGRAY);e.setTextSize(18);e.setSelectAllOnFocus(false);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);e.setSingleLine(!multiline);if(multiline){e.setMinLines(3);e.setMaxLines(7);}form.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private void button(String title,View.OnClickListener action){Button b=new Button(this);b.setText(title);b.setTextSize(18);b.setAllCaps(false);b.setOnClickListener(action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=12;form.addView(b,p);}
    private void show(String value,boolean error){message.setText(value);message.setTextColor(error?Color.rgb(255,170,150):Color.rgb(160,230,190));}
    private void save(){
        try{
            CheckMateConfig.save(this,tag.getText().toString(),providers.getText().toString());
            startForegroundService(new Intent(this,BridgeService.class).setAction("local.tagbridge.RECONFIGURE"));
            int count=CheckMateConfig.liveProviders(this).length();
            show(count==0?"Setup saved. Add at least one provider manifest to load live channels.":"Saved "+count+" provider manifests. CheckMate restarted. Install or refresh its addon in Nuvio.",count==0);
        }catch(IllegalArgumentException invalid){show(invalid.getMessage(),true);}
        catch(IllegalStateException unavailable){show(unavailable.getMessage(),true);}
        catch(RuntimeException unavailable){show("Setup was saved, but the background service could not restart. Reopen CheckMate to try again.",true);}
    }
}
