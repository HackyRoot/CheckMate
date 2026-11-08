package local.tagbridge;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context context,Intent intent){if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))context.startForegroundService(new Intent(context,BridgeService.class));}
}
