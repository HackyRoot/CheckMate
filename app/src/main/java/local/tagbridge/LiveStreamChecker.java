package local.tagbridge;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** TV-local live-source screening. Unsupported native playback remains inconclusive. */
public final class LiveStreamChecker {
    private static final int MAX_TESTS = 24;
    private static final Semaphore NETWORK = new Semaphore(4);
    private static final Semaphore DECODERS = new Semaphore(2);
    private final Context context;
    private final ExecutorService probes = Executors.newFixedThreadPool(4);
    private final Set<Run> active = new CopyOnWriteArraySet<Run>();
    private volatile boolean stopped;
    public LiveStreamChecker(Context context) { this.context = context.getApplicationContext(); }
    public void stop() {
        stopped = true;
        for (Run run : active) run.cancel();
        probes.shutdownNow();
    }
    public static final class Result {
        public final JSONArray streams;
        public final int tested, verified, rejected;
        public final boolean timedOut;
        Result(JSONArray streams, int tested, int verified, int rejected, boolean timedOut) {
            this.streams = streams; this.tested = tested; this.verified = verified;
            this.rejected = rejected; this.timedOut = timedOut;
        }
    }
    private static final class Candidate {
        final int index; final JSONObject stream;
        volatile boolean complete;
        volatile int verdict; // 0 untested, 1 inconclusive/reachable, 2 rendered, -1 definitive HTTP failure.
        Candidate(int index, JSONObject stream) { this.index = index; this.stream = stream; }
    }
    private static final class Run {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Set<HttpURLConnection> connections = new CopyOnWriteArraySet<HttpURLConnection>();
        final Set<Runnable> aborts = new CopyOnWriteArraySet<Runnable>();
        void cancel() {
            cancelled.set(true);
            for (HttpURLConnection c : connections) c.disconnect();
            for (Runnable abort : aborts) abort.run();
        }
    }
    public static int qualityFromText(String text) {
        String value=text==null?"":text.toLowerCase(java.util.Locale.ROOT);
        if(value.matches("(?s).*(?:2160|\\b4k\\b|\\buhd\\b).*"))return 2160;
        if(value.contains("1440"))return 1440;
        if(value.matches("(?s).*(?:1080|\\bfhd\\b|full[ ._-]*hd).*"))return 1080;
        if(value.contains("720"))return 720;
        if(value.contains("576"))return 576;
        if(value.contains("480"))return 480;
        if(value.contains("360"))return 360;
        return 0;
    }
    private static int quality(JSONObject stream) {
        JSONObject hints=stream.optJSONObject("behaviorHints");
        return qualityFromText(stream.optString("name")+" "+stream.optString("title")+" "+(hints==null?"":hints.optString("filename")));
    }
    private static boolean http(Candidate candidate) { String url=candidate.stream.optString("url"); return url.startsWith("http://")||url.startsWith("https://"); }
    private static int compareCandidates(Candidate a,Candidate b) {
        if(http(a)!=http(b))return http(a)?-1:1;
        int difference=quality(b.stream)-quality(a.stream);
        if(difference!=0)return difference;
        return a.index-b.index;
    }
    public Result check(JSONArray original, long timeoutMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + Math.min(60000, Math.max(1000, timeoutMs));
        final Run run = new Run(); active.add(run);
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (int i = 0; i < original.length(); i++) {
            JSONObject stream = original.optJSONObject(i);
            if (stream != null) candidates.add(new Candidate(i, stream));
        }
        Collections.sort(candidates,new Comparator<Candidate>(){public int compare(Candidate a,Candidate b){return compareCandidates(a,b);}});
        ExecutorCompletionService<Candidate> completions = new ExecutorCompletionService<Candidate>(probes);
        List<Future<Candidate>> futures = new ArrayList<Future<Candidate>>();
        int submitted = 0, pending = 0, tested = 0;
        boolean found = false;
        try {
            int next = 0;
            while (!stopped && !run.cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                while (pending < 4 && next < candidates.size() && submitted < MAX_TESTS) {
                    final Candidate candidate = candidates.get(next++);
                    String url = candidate.stream.optString("url");
                    if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                    futures.add(completions.submit(new Callable<Candidate>() { public Candidate call() {
                        try {
                            candidate.verdict = 1; // Native failure does not prove Nuvio cannot play it.
                            if (reachable(candidate.stream, run, deadline)) {
                                if (render(candidate.stream, run, deadline)) candidate.verdict = 2;
                            } else if (!run.cancelled.get()) candidate.verdict = -1;
                            return candidate;
                        } finally { candidate.complete=true; }
                    }}));
                    submitted++; pending++;
                }
                if (pending == 0) break;
                Future<Candidate> complete = completions.poll(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                if (complete == null) break;
                pending--; tested++;
                Candidate candidate = complete.get();
                Candidate verified=null;
                for(Candidate possible:candidates)if(possible.verdict==2){verified=possible;break;}
                if(verified!=null){
                    boolean higherPending=false;
                    for(Candidate possible:candidates)if(quality(possible.stream)>quality(verified.stream)&&!possible.complete){higherPending=true;break;}
                    if(!higherPending){found=true;break;}
                }
            }
        } finally {
            run.cancel();
            for (Future<Candidate> future : futures) if (!future.isDone()) future.cancel(true);
            active.remove(run);
        }
        // Verified winner goes first; original ordering is retained among all backup candidates.
        Collections.sort(candidates, new Comparator<Candidate>() { public int compare(Candidate a, Candidate b) { return compareCandidates(a,b); }});
        JSONArray streams = new JSONArray(); int verified = 0, rejected = 0;
        for (Candidate candidate : candidates) {
            if (candidate.verdict == -1) { rejected++; continue; }
            if (candidate.verdict == 2) verified++;
            JSONObject forwarded = new JSONObject(candidate.stream.toString());
            String originalName = forwarded.optString("name", "Live source");
            forwarded.put("itagOriginalName", originalName);
            forwarded.put("itagNativeVerified", candidate.verdict == 2);
            forwarded.put("itagCheckState", candidate.verdict == 2 ? "rendered" : candidate.verdict == 1 ? "inconclusive" : "untested");
            forwarded.put("name", "TV source #" + (candidate.index + 1) + " · " + originalName);
            streams.put(forwarded);
        }
        return new Result(streams, tested, verified, rejected, !found && SystemClock.elapsedRealtime() >= deadline);
    }
    private static Map<String, String> headers(JSONObject stream) {
        Map<String, String> result = new HashMap<String, String>();
        JSONObject hints = stream.optJSONObject("behaviorHints");
        JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
        JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
        if (request != null) {
            java.util.Iterator<String> keys = request.keys();
            while (keys.hasNext()) {
                String key = keys.next(), value = request.optString(key, "");
                if (!key.contains("\r") && !key.contains("\n") && !value.contains("\r") && !value.contains("\n")) result.put(key, value);
            }
        }
        return result;
    }
    private static boolean reachable(JSONObject stream, Run run, long deadline) {
        HttpURLConnection c = null; boolean permit = false;
        try {
            permit = NETWORK.tryAcquire(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            if (!permit || run.cancelled.get()) return true;
            c = (HttpURLConnection) new URL(stream.optString("url")).openConnection(); run.connections.add(c);
            int timeout = (int) Math.max(1, Math.min(3000, deadline - SystemClock.elapsedRealtime()));
            c.setConnectTimeout(timeout); c.setReadTimeout(timeout); c.setUseCaches(false);
            for (Map.Entry<String, String> h : headers(stream).entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            return !(code == 404 || code == 410 || code >= 500);
        } catch (Exception inconclusive) { return true; }
        finally { if (c != null) { c.disconnect(); run.connections.remove(c); } if (permit) NETWORK.release(); }
    }
    private boolean render(JSONObject stream, final Run run, long deadline) {
        long wait = Math.min(8000, deadline - SystemClock.elapsedRealtime());
        if (wait <= 0 || run.cancelled.get()) return false;
        try { if (!DECODERS.tryAcquire(wait, TimeUnit.MILLISECONDS)) return false; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
        if (run.cancelled.get()) { DECODERS.release(); return false; }
        final HandlerThread thread = new HandlerThread("LiveAddonVideoProbe"); thread.start();
        Handler handler = new Handler(thread.getLooper());
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean rendered = new AtomicBoolean();
        final MediaPlayer[] player = new MediaPlayer[1];
        final SurfaceTexture[] texture = new SurfaceTexture[1];
        final Surface[] surface = new Surface[1];
        final String url = stream.optString("url"); final Map<String, String> requestHeaders = headers(stream);
        final Runnable abort = new Runnable() { public void run() { done.countDown(); }};
        run.aborts.add(abort);
        handler.post(new Runnable() { public void run() {
            try {
                if (run.cancelled.get()) { done.countDown(); return; }
                texture[0] = new SurfaceTexture(false); texture[0].setDefaultBufferSize(320, 180);
                surface[0] = new Surface(texture[0]);
                MediaPlayer p = new MediaPlayer(); player[0] = p;
                p.setSurface(surface[0]); p.setVolume(0, 0);
                p.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer mp, int what, int extra) { done.countDown(); return true; }});
                p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer mp) { done.countDown(); }});
                p.setOnInfoListener(new MediaPlayer.OnInfoListener() { public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { rendered.set(true); done.countDown(); } return false;
                }});
                p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { public void onPrepared(MediaPlayer mp) {
                    try { if (!run.cancelled.get()) mp.start(); else done.countDown(); } catch (Exception error) { done.countDown(); }
                }});
                p.setDataSource(context, Uri.parse(url), requestHeaders); p.prepareAsync();
            } catch (Exception error) { done.countDown(); }
        }});
        try { done.await(wait, TimeUnit.MILLISECONDS); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        finally {
            run.aborts.remove(abort);
            handler.post(new Runnable() { public void run() {
                try { if (player[0] != null) player[0].release(); } catch (Exception ignored) {}
                try { if (surface[0] != null) surface[0].release(); } catch (Exception ignored) {}
                try { if (texture[0] != null) texture[0].release(); } catch (Exception ignored) {}
                DECODERS.release(); thread.quitSafely();
            }});
        }
        return rendered.get() && !run.cancelled.get();
    }
}
