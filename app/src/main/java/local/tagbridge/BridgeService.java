package local.tagbridge;
import android.app.*;
import android.content.Intent;
import android.os.*;
import android.bluetooth.*;
import android.widget.TextView;
import android.util.Log;
import java.util.*;

// GATT access is gated by canUseBluetooth(); permissions may be revoked independently.
@android.annotation.SuppressLint("MissingPermission")
public class BridgeService extends Service {
 private LiveAddonServer liveAddonServer;
 private LivePlaybackController livePlaybackController;
 private BluetoothGatt gatt; private long lastPress;
 private boolean stopping; private int retryCount; private int discoveryCount;
 private boolean screenSlept;
 private final android.content.BroadcastReceiver wakeReceiver=new android.content.BroadcastReceiver(){
  public void onReceive(android.content.Context context,Intent intent){
   String action=intent.getAction();
   if(Intent.ACTION_SCREEN_OFF.equals(action)){
    screenSlept=true;singlePending=false;
    handler.removeCallbacks(singleClick);
    if(livePlaybackController!=null)livePlaybackController.cancel();ProfileSelector.cancelPlayback();return;
   }
   if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)){
    int state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
    if(state==BluetoothAdapter.STATE_ON){report("Bluetooth restored; rebuilding tag connection");retryCount=0;retry("Bluetooth restarted",false);}
    else if(state==BluetoothAdapter.STATE_OFF){getSharedPreferences("tag_status",MODE_PRIVATE).edit().putBoolean("connected",false).apply();retry("Bluetooth switched off",false);}
   }else if(Intent.ACTION_SCREEN_ON.equals(action)&&screenSlept){screenSlept=false;retryCount=0;retry("TV woke; reconnecting tag",false);}
  }
 };
 private final Runnable reconnect=new Runnable(){public void run(){connectTag();}};
 private final Runnable timeout=new Runnable(){public void run(){retry("Connection/subscription timed out",false);}};
 private final Handler handler=new Handler(Looper.getMainLooper());
 private void report(final String s){Log.i("ITagBridge",s);}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"local.tagbridge.RECONNECT".equals(i.getAction()))retry("Reconnect requested",true);if(i!=null&&"local.tagbridge.RECONFIGURE".equals(i.getAction())){if(livePlaybackController!=null)livePlaybackController.stop();if(liveAddonServer!=null)liveAddonServer.stop();startLiveAddon();retryCount=0;retry("Configuration updated",false);}if(i!=null&&"local.tagbridge.PLAY_HUNGAMA".equals(i.getAction()))launchHungama();return START_STICKY;}
 @Override public void onCreate(){super.onCreate();
  CheckMateConfig.migrateLegacy(this);
  searchStatus("");
  NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  nm.createNotificationChannel(new NotificationChannel("bridge","iTag connection",NotificationManager.IMPORTANCE_LOW));
  startForeground(1,new Notification.Builder(this,"bridge").setContentTitle("CheckMate").setContentText("Tag buttons and live TV checking").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build());
  android.content.IntentFilter wakeFilter=new android.content.IntentFilter();
  wakeFilter.addAction(Intent.ACTION_SCREEN_ON);wakeFilter.addAction(Intent.ACTION_SCREEN_OFF);wakeFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
  registerReceiver(wakeReceiver,wakeFilter);
  startLiveAddon();
  connectTag();
 }
 private void startLiveAddon(){
  try{liveAddonServer=new LiveAddonServer(this);livePlaybackController=new LivePlaybackController(this,liveAddonServer);liveAddonServer.setListener(livePlaybackController);liveAddonServer.start();report("Live TV checker addon running on loopback");}catch(Exception e){report("Live addon start failed: "+e.getClass().getSimpleName());}
 }
 private final Runnable batteryPoll=new Runnable(){public void run(){readBattery();}};
 private void readBattery(){
  if(stopping||gatt==null||!canUseBluetooth())return;
  BluetoothGattService service=gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"));
  BluetoothGattCharacteristic level=service==null?null:service.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"));
  if(level!=null&&!gatt.readCharacteristic(level))report("Battery read busy; will retry");
  handler.removeCallbacks(batteryPoll);handler.postDelayed(batteryPoll,300000);
 }
 private final java.util.ArrayDeque<BluetoothGattCharacteristic> quietWrites=new java.util.ArrayDeque<BluetoothGattCharacteristic>();
 private void configureQuiet(){
  if(stopping||gatt==null||!canUseBluetooth())return;
  quietWrites.clear();
  String[] services={"0000ffe0","00001803","00001802"};String[] chars={"0000ffe2","00002a06","00002a06"};
  for(int i=0;i<services.length;i++){
   BluetoothGattService service=gatt.getService(UUID.fromString(services[i]+"-0000-1000-8000-00805f9b34fb"));
   BluetoothGattCharacteristic c=service==null?null:service.getCharacteristic(UUID.fromString(chars[i]+"-0000-1000-8000-00805f9b34fb"));
   if(c!=null)quietWrites.add(c);
  }
  nextQuietWrite();
 }
 private void nextQuietWrite(){
  if(stopping||gatt==null||!canUseBluetooth())return;
  BluetoothGattCharacteristic c=quietWrites.poll();
  if(c==null){handler.postDelayed(batteryPoll,300);return;}
  c.setWriteType((c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
  c.setValue(new byte[]{0});
  if(!gatt.writeCharacteristic(c)){report("Quiet-setting write busy");nextQuietWrite();}
 }
 private boolean canUseBluetooth(){return Build.VERSION.SDK_INT<31||checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
 private void connectTag(){
  if(stopping)return;
  if(!canUseBluetooth()){report("Bluetooth permission required; addon remains available");return;}
  handler.removeCallbacks(reconnect);handler.removeCallbacks(timeout);
  String address=CheckMateConfig.tagAddress(this);
  if(address.length()==0){report("No tag configured; live TV addon remains available");return;}
  BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
  BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
  if(adapter==null||!adapter.isEnabled()){report("Bluetooth unavailable; waiting");handler.postDelayed(reconnect,5000);return;}
  discoveryCount=0;
  report("Connecting configured Bluetooth tag");
  try{gatt=adapter.getRemoteDevice(address).connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);}catch(SecurityException denied){report("Bluetooth permission is required for tag control");return;}
  handler.postDelayed(timeout,30000);
 }
 private void retry(final String reason,final boolean clearCache){handler.post(new Runnable(){public void run(){
  if(stopping)return;
  handler.removeCallbacks(timeout);handler.removeCallbacks(reconnect);quietWrites.clear();
  getSharedPreferences("tag_status",MODE_PRIVATE).edit().putBoolean("connected",false).apply();
  handler.removeCallbacks(batteryPoll);
  BluetoothGatt old=gatt;gatt=null;
  if(old!=null){try{old.disconnect();if(clearCache){old.getClass().getMethod("refresh").invoke(old);report("Cleared stale GATT cache");}}catch(Exception e){report("GATT cleanup: "+e.getClass().getSimpleName());}finally{try{old.close();}catch(SecurityException ignored){}}}
  long delay=Math.min(30000,3000L*(1L<<Math.min(retryCount++,3)));
  report(reason+"; reconnect in "+delay+"ms");handler.postDelayed(reconnect,delay);
 }});}
 private final BluetoothGattCallback callback=new BluetoothGattCallback(){
  @Override public void onConnectionStateChange(final BluetoothGatt g,int status,int state){
   report("Connection status="+status+" state="+state);
   if(g!=gatt||stopping||!canUseBluetooth())return;
   if(status==0&&state==BluetoothProfile.STATE_CONNECTED){handler.postDelayed(new Runnable(){public void run(){if(g==gatt&&!stopping){discoveryCount++;if(!g.discoverServices())retry("Discovery rejected",false);}}},700);}
   else if(state==BluetoothProfile.STATE_DISCONNECTED||status!=0)retry("Tag disconnected/status="+status,false);
  }
  @Override public void onServicesDiscovered(BluetoothGatt g,int status){if(g!=gatt||stopping||!canUseBluetooth())return;report("Services status="+status+" count="+g.getServices().size());if(status!=0){retry("Discovery failed/status="+status,false);return;}
   for(BluetoothGattService s:g.getServices())for(BluetoothGattCharacteristic c:s.getCharacteristics())report("Characteristic "+s.getUuid()+" / "+c.getUuid()+" properties="+c.getProperties());
   BluetoothGattService s=g.getService(UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"));
   BluetoothGattCharacteristic c=s==null?null:s.getCharacteristic(UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"));
   if(c==null){
    if(discoveryCount<2){final BluetoothGatt connected=g;handler.postDelayed(new Runnable(){public void run(){if(connected==gatt&&!stopping){discoveryCount++;if(!connected.discoverServices())retry("Discovery retry rejected",true);}}},1500);}
    else retry("Button characteristic missing after discovery",true);
    return;
   }
   if(!g.setCharacteristicNotification(c,true)){retry("Local notification registration rejected",false);return;}
   BluetoothGattDescriptor d=c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
   if(d!=null){d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);if(!g.writeDescriptor(d))retry("Subscription rejected",false);}else{handler.removeCallbacks(timeout);retryCount=0;report("No CCCD; local notifications active");}
  }
  @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){if(g!=gatt||stopping||!canUseBluetooth())return;if(status==0){handler.removeCallbacks(timeout);retryCount=0;report("Subscription status=0. Press tag button now.");getSharedPreferences("tag_status",MODE_PRIVATE).edit().putBoolean("connected",true).apply();handler.postDelayed(new Runnable(){public void run(){configureQuiet();}},300);}else retry("Subscription failed/status="+status,false);}
  @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
   if(g!=gatt||stopping||!canUseBluetooth())return;
   report("Quiet setting "+c.getService().getUuid()+" / "+c.getUuid()+" write status="+status);
   handler.post(new Runnable(){public void run(){nextQuietWrite();}});
  }
  @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
   if(g!=gatt||stopping||status!=0||!c.getUuid().equals(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")))return;
   byte[] value=c.getValue();if(value==null||value.length==0)return;int level=value[0]&255;
   if(level<=100){getSharedPreferences("tag_status",MODE_PRIVATE).edit().putInt("battery",level).putLong("updated",System.currentTimeMillis()).apply();report("Tag battery="+level+"%");}
  }
  @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(g!=gatt||stopping||!canUseBluetooth())return;report("BUTTON "+c.getUuid()+" value="+Arrays.toString(c.getValue()));
   byte[] value=c.getValue();if(value==null||value.length==0)return;
   final int code=value[0]&255;
   handler.post(new Runnable(){public void run(){handlePress(code);}});

  }
 };
 private long ignorePressUntil;
 private long lastButtonNotification;
 private boolean singlePending;
 private final Runnable singleClick=new Runnable(){public void run(){singlePending=false;launchHungama();}};
 private void handlePress(int code){
  long now=SystemClock.elapsedRealtime();
  android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);if(power!=null&&!power.isInteractive())return;
  if(getSharedPreferences("tag_status",MODE_PRIVATE).getBoolean("pause_buttons_for_test",false))return;
  if(code==1&&now-lastButtonNotification<80)return;
  lastButtonNotification=now;
  if(now<ignorePressUntil)return;
  if(code==2){handler.removeCallbacks(singleClick);singlePending=false;launchSmartTube();lastPress=now;return;}
  if(code!=1)return;
  if(singlePending&&now-lastPress<=450){handler.removeCallbacks(singleClick);singlePending=false;lastPress=now;launchSmartTube();return;}
  handler.removeCallbacks(singleClick);singlePending=true;lastPress=now;handler.postDelayed(singleClick,450);
 }
 private void launchSmartTube(){
  if(livePlaybackController!=null)livePlaybackController.cancel();
  ignorePressUntil=SystemClock.elapsedRealtime()+1000;
  handler.removeCallbacks(singleClick);singlePending=false;
  ProfileSelector.cancelPlayback();
  Intent launch=getPackageManager().getLeanbackLaunchIntentForPackage("org.smarttube.beta");
  if(launch==null)launch=getPackageManager().getLaunchIntentForPackage("org.smarttube.beta");
  if(launch==null){report("SmartTube launch activity unavailable");return;}
  launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);report("Double click: SmartTube launched");
 }
 private void searchStatus(String message){getSharedPreferences("tag_status",MODE_PRIVATE).edit().putString("search",message).apply();}
 private void launchHungama(){
  if(livePlaybackController!=null)livePlaybackController.cancel();
  ignorePressUntil=SystemClock.elapsedRealtime()+1000;ProfileSelector.cancelPlayback();
  if(liveAddonServer==null||!liveAddonServer.isRunning()){android.widget.Toast.makeText(this,"Configure your provider manifests in CheckMate first",android.widget.Toast.LENGTH_LONG).show();return;}
  try{
   String id=LiveAddonServer.channelIdForName("Hungama");ProfileSelector.pendingUntil=SystemClock.elapsedRealtime()+60000;
   Intent launch=new Intent();launch.setClassName("com.nuvio.tv","com.nuvio.tv.MainActivity");
   launch.putExtra("contentId",id);launch.putExtra("contentType","series");launch.putExtra("videoId",id);launch.putExtra("name","Hungama");launch.putExtra("launchMode","stream");
   launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(launch);
   searchStatus("Opened Hungama through the combined CheckMate catalog");report("Button uses the aggregated live channel pipeline");
  }catch(Exception unavailable){ProfileSelector.cancelPlayback();report("Could not open Nuvio: "+unavailable.getClass().getSimpleName());android.widget.Toast.makeText(this,"Nuvio is unavailable",android.widget.Toast.LENGTH_LONG).show();}
 }
 @Override public void onDestroy(){if(livePlaybackController!=null)livePlaybackController.stop();if(liveAddonServer!=null)liveAddonServer.stop();try{unregisterReceiver(wakeReceiver);}catch(Exception ignored){}stopping=true;handler.removeCallbacksAndMessages(null);getSharedPreferences("tag_status",MODE_PRIVATE).edit().putBoolean("connected",false).apply();
  handler.removeCallbacks(batteryPoll);
  BluetoothGatt old=gatt;gatt=null;if(old!=null){try{old.disconnect();old.close();}catch(SecurityException ignored){}}super.onDestroy();}
}
