package local.tagbridge;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.widget.*;
import android.view.View;

/** Remote-friendly companion status. Button-triggered launches never open this screen. */
public class MainActivity extends Activity {
 private TextView status;
 private boolean permissionAsked;
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final Runnable refresh=new Runnable(){public void run(){
  if(status==null)return;
  android.content.SharedPreferences p=getSharedPreferences("tag_status",MODE_PRIVATE);
  int battery=p.getInt("battery",-1);long updated=p.getLong("updated",0);
  String tag=CheckMateConfig.tagAddress(MainActivity.this).isEmpty()?"No tag configured (optional)":p.getBoolean("connected",false)?"Tag connected":"Tag reconnecting";
  String age=updated==0?"":" · read at "+android.text.format.DateFormat.format("HH:mm",updated);
  String search=p.getString("search","");
  status.setText(tag+"\nBattery: "+(battery<0?"No reading yet":battery+"%"+age)+"\nProviders configured: "+CheckMateConfig.liveProviders(MainActivity.this).length()+"\n\n"+(search.isEmpty()?"Live TV: combined channels, providers first, quality first.":"Last action: "+search)+"\n\nTag: one click → Hungama · two clicks → SmartTube");
  ui.postDelayed(this,2000);
 }};
 @Override public void onCreate(Bundle state){
  super.onCreate(state);CheckMateConfig.migrateLegacy(this);
  if(getIntent().hasExtra("pauseButtons"))getSharedPreferences("tag_status",MODE_PRIVATE).edit().putBoolean("pause_buttons_for_test",getIntent().getBooleanExtra("pauseButtons",false)).apply();
  startForegroundService(new Intent(this,BridgeService.class));
  if(getIntent().getBooleanExtra("backgroundOnly",false)){finish();return;}
  ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(15,20,29));
  LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(48,28,48,28);scroll.addView(column);setContentView(scroll);
  TextView title=new TextView(this);title.setText("CheckMate");title.setTextSize(32);title.setTextColor(Color.WHITE);column.addView(title);
  status=new TextView(this);status.setTextColor(Color.LTGRAY);status.setTextSize(19);status.setPadding(0,18,0,18);column.addView(status);
  button(column,"Play Hungama in Nuvio",new View.OnClickListener(){public void onClick(View v){startForegroundService(new Intent(MainActivity.this,BridgeService.class).setAction("local.tagbridge.PLAY_HUNGAMA"));}});
  button(column,"Setup providers and optional Bluetooth tag",new View.OnClickListener(){public void onClick(View v){startActivity(new Intent(MainActivity.this,SetupActivity.class));}});
  button(column,"Copy Nuvio addon URL",new View.OnClickListener(){public void onClick(View v){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("CheckMate",LiveAddonServer.MANIFEST_URL));Toast.makeText(MainActivity.this,"Copied the local addon URL",Toast.LENGTH_SHORT).show();}});
  button(column,"Enable Nuvio playback automation",new View.OnClickListener(){public void onClick(View v){try{startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(RuntimeException unavailable){Toast.makeText(MainActivity.this,"Open Android settings → Accessibility → CheckMate",Toast.LENGTH_LONG).show();}}});

 }
 private void button(LinearLayout parent,String text,View.OnClickListener action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(action);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));}
 @Override public void onResume(){super.onResume();if(status!=null){ui.post(refresh);String permission=android.os.Build.VERSION.SDK_INT>=31?android.Manifest.permission.BLUETOOTH_CONNECT:android.Manifest.permission.ACCESS_FINE_LOCATION;if(!permissionAsked&&!CheckMateConfig.tagAddress(this).isEmpty()&&checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){permissionAsked=true;requestPermissions(new String[]{permission},1);}}}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==1&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startForegroundService(new Intent(this,BridgeService.class).setAction("local.tagbridge.RECONNECT"));}
 @Override public void onPause(){ui.removeCallbacks(refresh);super.onPause();}
}
