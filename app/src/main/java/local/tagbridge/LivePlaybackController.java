package local.tagbridge;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Iterator;
import org.json.JSONObject;

/** Owns only actual player marker requests, never background stream-list prefetches. */
public final class LivePlaybackController implements LiveAddonServer.Listener {
    private final Context context;
    private final LiveAddonServer server;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,LiveAddonServer.PlaybackSession> sessions = new LinkedHashMap<String,LiveAddonServer.PlaybackSession>();
    private LiveAddonServer.PlaybackSession active;
    private String candidateUrl;
    private long generation;
    private long cancellation;
    private long deadline;
    private boolean stopped;
    private boolean waitingForReplacement;
    private int failedAttempts;
    private String provenCandidate;
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (stopped || active == null) return;
            Log.i("ITagBridge", "Live source retry budget ended without further proven failure");
            cancelObservation();
        }
    };

    public LivePlaybackController(Context context, LiveAddonServer server) {
        this.context=context.getApplicationContext(); this.server=server;
    }

    @Override public void onSession(final LiveAddonServer.PlaybackSession session) {
        final long epoch=cancellation;
        if (Looper.myLooper()!=Looper.getMainLooper()) {
            main.post(new Runnable(){@Override public void run(){if(epoch==cancellation)onSession(session);}});return;
        }
        if(stopped||session==null||session.id==null||!session.id.startsWith("itaglive:")||session.sessionId==null)return;
        sessions.put(session.sessionId,session);
        while(sessions.size()>32){Iterator<String> keys=sessions.keySet().iterator();keys.next();keys.remove();}
        // Nuvio prefetches source lists while browsing. Only a known failed playback may reopen UI.
        if(active==null||!active.sessionId.equals(session.sessionId)||!waitingForReplacement)return;
        active=session;
        if(session.streams==null||session.streams.length()==0||SystemClock.elapsedRealtime()>=deadline){
            Log.i("ITagBridge","No checked sources or retry budget remain");waitingForReplacement=false;cancelObservation();return;
        }
        if(!ProfileSelector.isNuvioActive()){
            if(ProfileSelector.isOtherAppActive()){
                Log.i("ITagBridge","Retry discarded after leaving Nuvio");cancel();return;
            }
            final long token=generation;
            main.postDelayed(new Runnable(){@Override public void run(){
                if(!stopped&&epoch==cancellation&&token==generation)onSession(session);
            }},250);
            return;
        }
        waitingForReplacement=false;provenCandidate=null;
        Intent launch=new Intent();launch.setClassName("com.nuvio.tv","com.nuvio.tv.MainActivity");
        launch.putExtra("launchMode","stream");launch.putExtra("contentId",session.id);
        launch.putExtra("contentType",session.type);launch.putExtra("videoId",session.id);launch.putExtra("name","Live channel");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try{context.startActivity(launch);}catch(RuntimeException unavailable){Log.i("ITagBridge","Could not reopen live sources");cancel();}
    }

    @Override public void onPlaybackRequest(final String sessionId,final String originalUrl) {
        final long epoch=cancellation;
        if(Looper.myLooper()!=Looper.getMainLooper()){
            main.post(new Runnable(){@Override public void run(){if(epoch==cancellation)onPlaybackRequest(sessionId,originalUrl);}});return;
        }
        final LiveAddonServer.PlaybackSession session=sessions.get(sessionId);
        // A user may select a cached stream list long after it was fetched. The server
        // validates/restores the marker and publishes refreshed ownership before this callback.
        // Budget playback retries from the player GET, not from catalog/list prefetch time.
        if(stopped||session==null||originalUrl==null)return;
        JSONObject selected=null;
        for(int i=0;i<session.streams.length();i++){
            JSONObject source=session.streams.optJSONObject(i);
            if(source!=null&&originalUrl.equals(source.optString("url",""))){selected=source;break;}
        }
        if(selected==null)return;
        final boolean same=active!=null&&session.sessionId.equals(active.sessionId);
        if(same&&(waitingForReplacement||originalUrl.equals(provenCandidate)))return;
        if(!same){cancelObservation();active=session;deadline=SystemClock.elapsedRealtime()+60000;failedAttempts=0;}
        if(SystemClock.elapsedRealtime()>=deadline)return;
        final JSONObject source=selected;
        final long token=++generation;
        candidateUrl=originalUrl;
        // Compose can temporarily have no active tree when the player first opens.
        observeWhenAccessible(source,token,epoch,SystemClock.elapsedRealtime()+2500);
    }

    private void observeWhenAccessible(final JSONObject source,final long token,final long epoch,final long windowDeadline){
        if(stopped||token!=generation||epoch!=cancellation||active==null||SystemClock.elapsedRealtime()>=deadline)return;
        final String sessionId=active.sessionId, url=source.optString("url","");
        Runnable failure=new Runnable(){@Override public void run(){
            if(stopped||token!=generation||active==null||waitingForReplacement||SystemClock.elapsedRealtime()>=deadline
                    ||!sessionId.equals(active.sessionId)||!url.equals(candidateUrl))return;
            if(++failedAttempts>=12){Log.i("ITagBridge","Live source retry limit reached");cancelObservation();return;}
            waitingForReplacement=true;provenCandidate=null;
            Log.i("ITagBridge","Owned live source error; checking next source within remaining budget");
            if(!server.markFailed(sessionId,url)){waitingForReplacement=false;cancelObservation();}
        }};
        Runnable playing=new Runnable(){@Override public void run(){
            if(token==generation&&!stopped)Log.i("ITagBridge","Selected live source playback progress confirmed");
        }};
        if(ProfileSelector.monitorAutoPlayedProxySession(source.optString("name",""),source.optString("title",""),failure,playing)){
            provenCandidate=url;main.removeCallbacks(expire);main.postDelayed(expire,Math.max(1,deadline-SystemClock.elapsedRealtime()));
            Log.i("ITagBridge","Proxy player GET owned; observing explicit playback errors");return;
        }
        if(SystemClock.elapsedRealtime()<windowDeadline&&!ProfileSelector.isOtherAppActive()){
            main.postDelayed(new Runnable(){@Override public void run(){observeWhenAccessible(source,token,epoch,windowDeadline);}},200);
        }else{Log.i("ITagBridge","Player request not monitored: active Nuvio window unavailable");}
    }

    private void cancelObservation(){generation++;main.removeCallbacks(expire);ProfileSelector.cancelPlayback();}
    public void cancel(){cancellation++;cancelObservation();active=null;candidateUrl=null;provenCandidate=null;waitingForReplacement=false;sessions.clear();deadline=0;}
    public void stop(){stopped=true;cancel();}
}
