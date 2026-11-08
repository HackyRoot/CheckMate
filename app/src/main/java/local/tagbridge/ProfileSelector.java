package local.tagbridge;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Selects TV only on Nuvio's profile picker during a button-armed launch. */
public class ProfileSelector extends AccessibilityService {
    static volatile long pendingUntil;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static volatile String desiredStream;
    private static volatile Runnable failureCallback;
    private static volatile Runnable playbackCallback;
    private static volatile boolean selectedStream;
    private static volatile boolean observedFailureOnly;
    private static volatile ProfileSelector instance;
    private static volatile boolean waitForErrorClear;
    private static volatile long proofAt;
    private static volatile boolean playbackReported;
    private long observedPosition = -1;
    private long observedAt;
    private long observedRequest;

    private long lastClick;
    private long playClickedForRequest;
    private long lastScroll;

    /** Call immediately before launching the verified channel deep link. */
    public static void armPlayback(String exactStreamName) {
        desiredStream = exactStreamName;
        failureCallback = null;
        playbackCallback = null;
        selectedStream = false;
        playbackReported = false;
        waitForErrorClear = false;
        proofAt = 0;
        observedFailureOnly = false;
        pendingUntil = SystemClock.elapsedRealtime() + 90000;
    }

    public static void armPlayback(String exactStreamName, String streamTitle) {
        // Stream names are rendered verbatim by Nuvio's StreamCard.
        armPlayback(exactStreamName);
    }

    public static void armPlayback(String exactStreamName, String streamTitle,
            Runnable onFailure, Runnable onPlaybackStarted) {
        armPlayback(exactStreamName, streamTitle);
        failureCallback = onFailure;
        playbackCallback = onPlaybackStarted;
    }

    /** Does not report inconclusive selection timeouts as failed playback. */
    public static void armObservedPlayback(String exactName, String title,
            Runnable onFailure, Runnable onPlaybackStarted) {
        armPlayback(exactName, title, onFailure, onPlaybackStarted);
        observedFailureOnly = true;
    }

    /** Marker GET identifies the proxy source; it does not assert decoded/rendered video. */
    public static boolean monitorAutoPlayedProxySession(String name, String title,
            Runnable onFailure, Runnable onPlaybackStarted) {
        ProfileSelector service = instance;
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!belongsToNuvio(root)) return false;
        armObservedPlayback(name, title, onFailure, onPlaybackStarted);
        selectedStream = true;
        proofAt = SystemClock.elapsedRealtime();
        pendingUntil = proofAt + 60000;
        // A previous source's error overlay may still exist during a retry transition.
        waitForErrorClear = !service.findText(root, "Playback Error").isEmpty();
        return true;
    }

    public static boolean isNuvioActive() {
        ProfileSelector service=instance;
        if(service==null)return false;android.os.PowerManager power=(android.os.PowerManager)service.getSystemService(POWER_SERVICE);
        return (power==null||power.isInteractive())&&belongsToNuvio(service.getRootInActiveWindow());
    }

    public static boolean isOtherAppActive() {
        ProfileSelector service=instance;
        if(service==null)return false;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        return root!=null&&root.getPackageName()!=null&&!belongsToNuvio(root);
    }

    private void fail(String reason) {
        Log.i("ITagBridge", "Nuvio playback attempt failed: " + reason);
        Runnable callback = observedFailureOnly && !selectedStream ? null : failureCallback;
        cancelPlayback();
        if (callback != null) handler.post(callback);
    }

    private void confirmMediaPlayback(long request) {
        if(playbackReported)return;
        try {
            android.media.session.MediaSessionManager sessions =
                    (android.media.session.MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
            for (android.media.session.MediaController controller : sessions.getActiveSessions(null)) {
                if (!"com.nuvio.tv".equals(controller.getPackageName())) continue;
                android.media.session.PlaybackState state = controller.getPlaybackState();
                if (state == null || state.getState() != android.media.session.PlaybackState.STATE_PLAYING
                        || state.getPlaybackSpeed() <= 0) continue;
                long now = SystemClock.elapsedRealtime();
                if (observedRequest == request && observedPosition >= 0
                        && state.getPosition() > observedPosition + 250 && now-observedAt >= 1000) {
                    Log.i("ITagBridge", "Nuvio playback confirmed by media-session position advance");
                    Runnable callback = playbackCallback;
                    playbackReported=true;
                    playbackCallback=null;
                    if(callback!=null)handler.post(callback);
                    return;
                }
                if(observedRequest!=request || observedPosition<0) {
                    observedRequest=request;observedPosition=state.getPosition();observedAt=now;
                }
            }
        } catch(SecurityException unavailable) {
            // Ordinary sideloaded apps cannot read other apps' sessions. Never invent success.
        } catch(RuntimeException unavailable) {
            // A player/session restart is inconclusive rather than proof of a dead stream.
        }
    }

    public static void cancelPlayback() {
        pendingUntil = 0;
        desiredStream = null;
        failureCallback = null;
        playbackCallback = null;
        selectedStream = false;
        waitForErrorClear=false;
        proofAt=0;
        playbackReported=false;
    }

    private java.util.List<AccessibilityNodeInfo> findText(AccessibilityNodeInfo root, String wanted) {
        java.util.List<AccessibilityNodeInfo> result=new java.util.ArrayList<AccessibilityNodeInfo>();
        collect(root,wanted,result,0);return result;
    }
    private void collect(AccessibilityNodeInfo node,String wanted,java.util.List<AccessibilityNodeInfo> result,int depth){
        if(node==null||depth>60)return;
        CharSequence text=node.getText(),desc=node.getContentDescription();
        if((text!=null&&text.toString().contains(wanted))||(desc!=null&&desc.toString().contains(wanted)))result.add(node);
        for(int i=0;i<node.getChildCount();i++)collect(node.getChild(i),wanted,result,depth+1);
    }
    private boolean clickExact(AccessibilityNodeInfo root, String text) {
        for (AccessibilityNodeInfo match : findText(root, text)) {
            if (!exact(match.getText(), text) && !exact(match.getContentDescription(), text)) continue;
            AccessibilityNodeInfo card = match;
            while (card != null && !card.isClickable()) card = card.getParent();
            if (card != null && card.isEnabled() && card.isVisibleToUser()
                    && card.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.isVisibleToUser()
                && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if (child!=null && scrollForward(child)) return true;
        }
        return false;
    }

    private long clickedForRequest;
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            select();
            // Content events can arrive before Compose publishes its accessibility tree.
            handler.postDelayed(this, pendingUntil > SystemClock.elapsedRealtime() ? 350 : 1500);
        }
    };

    @Override protected void onServiceConnected() {
        instance = this;
        handler.removeCallbacks(retry);
        handler.post(retry);
        Log.i("ITagBridge", "TV profile selector ready");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (observedFailureOnly && selectedStream
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null
                && !"com.nuvio.tv".contentEquals(event.getPackageName())) {
            Log.i("ITagBridge", "Live playback observation cancelled on app/window change");
            cancelPlayback();
            return;
        }
        select();
    }

    private static boolean exact(CharSequence value, String wanted) {
        return value != null && wanted.contentEquals(value);
    }

    private static boolean belongsToNuvio(AccessibilityNodeInfo root) {
        return root != null && exact(root.getPackageName(), "com.nuvio.tv");
    }

    private AccessibilityNodeInfo nuvioRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (belongsToNuvio(root)) return root;
        if(root!=null)return null; // Never use a background Nuvio window over another foreground app.
        // Some Android TV/Compose transitions temporarily have no active root.
        for (AccessibilityWindowInfo window : getWindows()) {
            root = window.getRoot();
            if ((window.isActive()||window.isFocused())&&belongsToNuvio(root)) return root;
        }
        return null;
    }

    private void select() {
        long request = pendingUntil;
        long now = SystemClock.elapsedRealtime();
        if (request == 0) return;
        if (now > request) {
            if (!selectedStream) fail("selection timeout");
            else {
                Log.i("ITagBridge", "Playback monitoring ended without a confirmed error or session verdict");
                cancelPlayback();
            }
            return;
        }
        AccessibilityNodeInfo root = nuvioRoot();
        if (root == null) return;
        if (selectedStream) {
            if (proofAt != 0 && now-proofAt < 1000) return;
            // Returning to browsing ends ownership; never attribute a later VOD error to this source.
            if (observedFailureOnly && (!findText(root, "Continue Watching").isEmpty()
                    || (!findText(root, "Library").isEmpty()
                    && !findText(root, "Search").isEmpty()
                    && !findText(root, "Settings").isEmpty()))) {
                Log.i("ITagBridge", "Live playback observation cancelled on return to browsing");
                cancelPlayback();
                return;
            }
            boolean playbackError = !findText(root, "Playback Error").isEmpty();
            if (waitForErrorClear) {
                // Marker ownership proves this source was actually requested. Give the
                // previous overlay time to clear, but do not suppress a new immediate error forever.
                if (playbackError && now-proofAt < 3500) return;
                waitForErrorClear = false;
            }
            if (proofAt != 0 && now-proofAt < 1000) return;
            if (playbackError) {
                fail("explicit Playback Error screen");
                return;
            }
            confirmMediaPlayback(request);
            return;
        }
        boolean picker = !findText(root, "Who's watching?").isEmpty()
                || !findText(root, "Select a profile to continue").isEmpty();
        if (!picker) {
            if (clickedForRequest == request) {
                clickedForRequest = 0;
                Log.i("ITagBridge", "TV profile picker dismissed after selection");
                if (desiredStream == null) { pendingUntil = 0; return; }
            }
            if (desiredStream == null || now - lastClick < 1600) return;
            // Never click arbitrary first streams. Only the resolver's verified exact name.
            if (clickExact(root, desiredStream)) {
                lastClick = now;
                Log.i("ITagBridge", "Verified Hungama stream selection click accepted");
                selectedStream = true;
                // A successful click is not evidence that the stream actually plays.
                return;
            }
            // Only play a Hungama detail. Avoid unrelated Play controls or trailers.
            boolean hungama = !findText(root, "Hungama").isEmpty();
            if (hungama && playClickedForRequest != request && clickExact(root, "Play")) {
                lastClick = now;
                playClickedForRequest = request;
                Log.i("ITagBridge", "Hungama detail Play click accepted");
                return;
            }
            if (playClickedForRequest == request && now-lastScroll > 2200
                    && (!findText(root, "Streams").isEmpty()
                    || !findText(root, "streams").isEmpty())) {
                if (scrollForward(root)) lastScroll = now;
            }
            return;
        }
        if (now - lastClick < 2000) return;
        for (AccessibilityNodeInfo match : findText(root, "TV")) {
            // Substring matches include NuvioTV: never select those.
            if (!exact(match.getText(), "TV") && !exact(match.getContentDescription(), "TV")) continue;
            AccessibilityNodeInfo card = match;
            while (card != null && !card.isClickable()) card = card.getParent();
            if (card != null && card.isEnabled() && card.isVisibleToUser()
                    && card.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClick = now;
                clickedForRequest = request;
                // Keep the request armed until the picker actually disappears.
                Log.i("ITagBridge", "TV profile selection click accepted");
                return;
            }
        }
    }

    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        instance = null;
        handler.removeCallbacks(retry);
        super.onDestroy();
    }
}
