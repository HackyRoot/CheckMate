package local.tagbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

/** Fresh provider discovery and muted, decoded-video probes executed entirely on the TV. */
public final class HungamaResolver {
    private static final long TOTAL_MS = 60000;
    private static final long PROBE_MS = 8000;
    private static final int MAX_CANDIDATES = 40;
    private static final Semaphore DECODER_SLOTS = new Semaphore(4);
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private HungamaResolver() {}

    public interface Listener {
        void onProgress(String safeMessage);
        void onSelected(Selection selection);
        void onFailure(String safeMessage);
    }
    public static final class Selection {
        public final String channelName, type, id, streamName, streamTitle, url;
        public final Map<String, String> headers;
        public volatile boolean nativeVerified;
        Selection(String name, String type, String id, JSONObject stream) {
            this.channelName = name; this.type = type; this.id = id;
            streamName = stream.optString("name", "");
            streamTitle = stream.optString("title", "");
            url = stream.optString("url", "");
            headers = new LinkedHashMap<String, String>();
            JSONObject hints = stream.optJSONObject("behaviorHints");
            JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
            JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
            if (request != null) {
                java.util.Iterator<String> keys = request.keys();
                while (keys.hasNext()) {
                    String key = keys.next(), value = request.optString(key, "");
                    if (!key.contains("\r") && !key.contains("\n") && !value.contains("\r") && !value.contains("\n")) headers.put(key, value);
                }
            }
        }
        public String deepLink() { return "nuvio://detail/" + Uri.encode(type) + "/" + Uri.encode(id); }
    }
    private static final class Provider {
        final String base; final JSONObject manifest;
        Provider(String base, JSONObject manifest) { this.base = base; this.manifest = manifest; }
    }
    public static final class Job {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<HttpURLConnection> connections = new CopyOnWriteArraySet<HttpURLConnection>();
        private Set<String> excludedUrls = Collections.emptySet();
        private final Set<Runnable> abortProbes = new CopyOnWriteArraySet<Runnable>();
        private Thread worker;
        public void cancel() {
            cancelled.set(true);
            for (HttpURLConnection c : connections) c.disconnect();
            for (Runnable abort : abortProbes) abort.run();
            if (worker != null) worker.interrupt();
        }
        public boolean isCancelled() { return cancelled.get(); }
    }
    public static Job start(Context context, Listener listener) {
        return start(context, listener, Collections.<String>emptySet());
    }
    public static Job start(Context context, Listener listener, Set<String> excludedUrls) {
        final Context app = context.getApplicationContext();
        final Job job = new Job();
        job.excludedUrls = new HashSet<String>(excludedUrls == null ? Collections.<String>emptySet() : excludedUrls);
        final Handler main = new Handler(Looper.getMainLooper());
        job.worker = new Thread(new Runnable() { public void run() {
            try { HungamaResolver.run(app, listener, main, job); }
            catch (Exception error) { failure(listener, main, job, "Channel search failed; please try again."); }
        }}, "HungamaResolver");
        job.worker.start();
        return job;
    }
    private static void progress(final Listener l, Handler main, final Job job, final String message) {
        main.post(new Runnable() { public void run() { if (!job.isCancelled()) l.onProgress(message); }});
    }
    private static void failure(final Listener l, Handler main, final Job job, final String message) {
        main.post(new Runnable() { public void run() { if (!job.isCancelled()) l.onFailure(message); }});
    }
    private static boolean active(Job job, long deadline) {
        return !job.isCancelled() && SystemClock.elapsedRealtime() < deadline;
    }
    private static String text(InputStream in) throws Exception { return text(in, null, Long.MAX_VALUE); }
    private static String text(InputStream in, Job job, long deadline) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (job != null && !active(job, deadline)) throw new InterruptedException();
                if (out.size() + n > MAX_JSON_BYTES) throw new java.io.IOException("JSON too large");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } finally { in.close(); }
    }
    private static JSONObject fetch(String url, Job job, long deadline) throws Exception {
        if (!active(job, deadline)) throw new InterruptedException();
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new java.io.IOException("Unsupported provider");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        job.connections.add(c);
        int remaining = (int) Math.max(1, Math.min(4000, deadline - SystemClock.elapsedRealtime()));
        c.setConnectTimeout(remaining); c.setReadTimeout(remaining);
        c.setRequestProperty("User-Agent", "iTag-TV-Bridge/1.0");
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("Provider unavailable");
            return new JSONObject(text(c.getInputStream(), job, deadline));
        } finally { c.disconnect(); job.connections.remove(c); }
    }
    private static String endpoint(String base,String... parts){
        StringBuilder path=new StringBuilder();for(String part:parts){if(path.length()>0)path.append('/');path.append(Uri.encode(part));}
        return CheckMateConfig.providerResource(base,path.append(".json").toString());
    }

    private static boolean channelName(String name) {
        return name.trim().matches("(?i)hungama(?:\\s*(?:tv|hd|sd|india|hindi|live|\\(.*?\\)|[-|].*))*");
    }
    private static boolean searchable(JSONObject catalog) {
        JSONArray extras = catalog.optJSONArray("extra");
        if (extras == null) return false;
        for (int i = 0; i < extras.length(); i++) {
            Object value = extras.opt(i);
            if ("search".equals(value) || (value instanceof JSONObject && "search".equals(((JSONObject) value).optString("name")))) return true;
        }
        return false;
    }
    private static List<JSONObject> ordered(JSONArray array, final String key, final String preferred) {
        List<JSONObject> result = new ArrayList<JSONObject>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i); if (item != null) result.add(item);
        }
        if (preferred.length() > 0) Collections.sort(result, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return (preferred.equals(a.optString(key)) ? 0 : 1) - (preferred.equals(b.optString(key)) ? 0 : 1);
            }
        });
        return result;
    }
    /** Two bounded candidate tasks and at most two native decoders. */
    private static final class ProbeQueue {
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        final ExecutorCompletionService<Selection> completed = new ExecutorCompletionService<Selection>(executor);
        final AtomicReference<Selection> firstRendered = new AtomicReference<Selection>();
        final AtomicReference<Selection> firstInconclusive = new AtomicReference<Selection>();
        final List<Future<Selection>> tasks = new ArrayList<Future<Selection>>();
        int pending;
        void submit(final Context app, final Selection selection, final Job job, final long deadline) {
            pending++;
            tasks.add(completed.submit(new Callable<Selection>() {
                public Selection call() {
                    if (!reachable(selection, job, deadline)) return null;
                    if (probe(app, selection, job, deadline, firstRendered)) { selection.nativeVerified = true; return selection; }
                    if (active(job, deadline)) firstInconclusive.compareAndSet(null, selection);
                    return null;
                }
            }));
        }
        Selection take(Job job, long deadline, boolean wait) throws Exception {
            if (firstRendered.get() != null) return firstRendered.get();
            if (pending == 0 || !active(job, deadline)) return null;
            Future<Selection> result = wait ? completed.poll(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) : completed.poll();
            if (result == null) return null;
            pending--;
            Selection candidate = result.get();
            return firstRendered.get() != null ? firstRendered.get() : candidate;
        }
        void close() {
            for (Future<Selection> task : tasks) if (!task.isDone()) task.cancel(true);
            executor.shutdownNow();
            try { executor.awaitTermination(2500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
    private static void selected(final Context app, final Listener l, Handler main, final Job job, final Selection selection) {
        if (job.isCancelled()) return;
        // Remember only source labels; fresh IDs, URLs and credentials are never cached.
        app.getSharedPreferences("hungama_resolver", Context.MODE_PRIVATE).edit()
            .putString("channel_name", selection.channelName).putString("stream_name", selection.streamName).apply();
        main.post(new Runnable() { public void run() { if (!job.isCancelled()) l.onSelected(selection); }});
    }
    private static void run(Context app, Listener l, Handler main, Job job) throws Exception {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + TOTAL_MS;
        progress(l, main, job, "Searching providers for fresh Hungama channels…");
        SharedPreferences preferences = app.getSharedPreferences("hungama_resolver", Context.MODE_PRIVATE);
        String preferredChannel = preferences.getString("channel_name", "");
        String preferredStream = preferences.getString("stream_name", "");
        JSONArray configured = CheckMateConfig.buttonProviders(app);
        List<Provider> providers = new ArrayList<Provider>();
        for (int i = 0; i < configured.length() && active(job, deadline); i++) {
            String manifest = configured.optString(i, "");
            if (manifest.split("\\?",2)[0].endsWith("/manifest.json")) {
                try { providers.add(new Provider(CheckMateConfig.providerBase(manifest), fetch(manifest, job, deadline))); }
                catch (Exception unavailable) { /* Try remaining installed providers. */ }
            }
        }
        Set<String> seenChannels = new HashSet<String>();
        Set<String> seenUrls = new HashSet<String>(job.excludedUrls);
        Selection preferred = preferredFirst(providers, job, deadline);
        if (preferred != null && active(job, deadline)) {
            seenUrls.add(preferred.url);
            progress(l, main, job, "Testing JIO TV (IND) > Hungama first…");
            if (reachable(preferred, job, deadline)) {
                preferred.nativeVerified = probe(app, preferred, job, deadline, new AtomicReference<Selection>());
                if (active(job, deadline)) {
                    progress(l, main, job, preferred.nativeVerified ? "Preferred Hungama video verified on TV." : "Native check inconclusive; trying preferred Hungama in Nuvio…");
                    selected(app, l, main, job, preferred); return;
                }
            }
        }
        int checked = 0;
        ProbeQueue queue = new ProbeQueue();
        Selection winner = null;
        try {
          discovery:
          for (Provider provider : providers) {
            JSONArray catalogs = provider.manifest.optJSONArray("catalogs");
            if (catalogs == null) continue;
            for (JSONObject catalog : ordered(catalogs, "id", "cnc_JIOTVIND_tv")) {
                if (!active(job, deadline)) break discovery;
                if (catalog == null || !searchable(catalog)) continue;
                String type = catalog.optString("type");
                if ("movie".equals(type) || "movies".equals(type)) continue;
                JSONArray metas;
                try { metas = fetch(endpoint(provider.base, "catalog", type, catalog.optString("id"), "search=Hungama").replace("search%3DHungama.json", "search=Hungama.json"), job, deadline).optJSONArray("metas"); }
                catch (Exception unavailable) { continue; }
                for (JSONObject meta : ordered(metas, "name", "cnc_JIOTVIND_tv".equals(catalog.optString("id")) ? "Hungama" : preferredChannel)) {
                    if (!active(job, deadline)) break discovery;
                    if (!channelName(meta.optString("name"))) continue;
                    String mediaType = meta.optString("type", type), id = meta.optString("id");
                    if (id.length() == 0 || !seenChannels.add(mediaType + "\n" + id)) continue;
                    for (Provider streamProvider : providers) {
                        if (!active(job, deadline)) break discovery;
                        winner = queue.take(job, deadline, false);
                        if (winner != null) break discovery;
                        JSONArray streams;
                        try { streams = fetch(endpoint(streamProvider.base, "stream", mediaType, id), job, deadline).optJSONArray("streams"); }
                        catch (Exception unavailable) { continue; }
                        for (JSONObject stream : ordered(streams, "name", preferredStream)) {
                            if (!active(job, deadline)) break discovery;
                            String url = stream.optString("url");
                            if ((!url.startsWith("http://") && !url.startsWith("https://")) || !seenUrls.add(url)) continue;
                            while (queue.pending >= 4 && active(job, deadline)) {
                                winner = queue.take(job, deadline, true);
                                if (winner != null) break discovery;
                            }
                            if (checked >= MAX_CANDIDATES) break discovery;
                            checked++;
                            progress(l, main, job, "Testing Hungama stream " + checked + " on TV (up to 4 at once)…");
                            queue.submit(app, new Selection(meta.optString("name"), mediaType, id, stream), job, deadline);
                        }
                    }
                }
            }
          }
          while (winner == null && queue.pending > 0 && active(job, deadline)) winner = queue.take(job, deadline, true);
        } finally { queue.close(); }
        if (winner == null && !job.isCancelled()) winner = queue.firstInconclusive.get();
        if (winner != null && !job.isCancelled()) {
            long elapsed = SystemClock.elapsedRealtime() - started;
            android.util.Log.i("ITagBridge", "TV candidate ready after " + elapsed + "ms; " + checked + " candidates; nativeVerified=" + winner.nativeVerified);
            progress(l, main, job, winner.nativeVerified ? "Working video verified on TV in " + ((elapsed + 500) / 1000) + " seconds." : "Native check inconclusive; trying this stream in Nuvio…");
            selected(app, l, main, job, winner); return;
        }
        failure(l, main, job, active(job, deadline) ? "No available Hungama source found; providers returned errors or no new streams." : "Hungama search timed out. Playback remains unverified.");
    }
    /** Fresh exact catalog and exact title; never use the similarly named JIO IND catalog. */
    private static Selection preferredFirst(List<Provider> providers, Job job, long deadline) {
        for (Provider provider : providers) {
            JSONArray catalogs = provider.manifest.optJSONArray("catalogs");
            if (catalogs == null) continue;
            for (int i = 0; i < catalogs.length() && active(job, deadline); i++) {
                JSONObject catalog = catalogs.optJSONObject(i);
                if (catalog == null || !"cnc_JIOTVIND_tv".equals(catalog.optString("id")) || !"tv".equals(catalog.optString("type"))) continue;
                try {
                    JSONArray metas = fetch(endpoint(provider.base, "catalog", "tv", "cnc_JIOTVIND_tv", "search=Hungama").replace("search%3DHungama.json", "search=Hungama.json"), job, deadline).optJSONArray("metas");
                    if (metas == null) continue;
                    for (int m = 0; m < metas.length() && active(job, deadline); m++) {
                        JSONObject meta = metas.optJSONObject(m);
                        if (meta == null || !"Hungama".equalsIgnoreCase(meta.optString("name").trim())) continue;
                        String id = meta.optString("id"); if (id.length() == 0) continue;
                        JSONArray streams = fetch(endpoint(provider.base, "stream", "tv", id), job, deadline).optJSONArray("streams");
                        if (streams == null) continue;
                        for (int j = 0; j < streams.length(); j++) {
                            JSONObject stream = streams.optJSONObject(j); if (stream == null) continue;
                            String url = stream.optString("url");
                            if ((url.startsWith("http://") || url.startsWith("https://")) && !job.excludedUrls.contains(url)) return new Selection(meta.optString("name"), "tv", id, stream);
                        }
                    }
                } catch (Exception unavailable) { /* Continue with fresh fallback discovery. */ }
            }
        }
        return null;
    }
    /** A failed native decoder is inconclusive; reject only explicit server-side failures. */
    private static boolean reachable(Selection selection, Job job, long deadline) {
        if (!active(job, deadline)) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(selection.url).openConnection();
            job.connections.add(connection);
            int timeout = (int) Math.max(1, Math.min(3000, deadline - SystemClock.elapsedRealtime()));
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : selection.headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            int status = connection.getResponseCode();
            return !(status == 404 || status == 410 || status >= 500);
        } catch (Exception inconclusive) { return active(job, deadline); }
        finally { if (connection != null) { connection.disconnect(); job.connections.remove(connection); } }
    }
    private static boolean probe(Context app, final Selection selection, Job job, long deadline, final AtomicReference<Selection> firstRendered) {
        final long waitMs = Math.min(PROBE_MS, deadline - SystemClock.elapsedRealtime());
        if (waitMs <= 0 || job.isCancelled()) return false;
        try { if (!DECODER_SLOTS.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return false; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
        if (job.isCancelled()) { DECODER_SLOTS.release(); return false; }
        final HandlerThread thread = new HandlerThread("HungamaVideoProbe"); thread.start();
        final Handler h = new Handler(thread.getLooper());
        final CountDownLatch done = new CountDownLatch(1), released = new CountDownLatch(1);
        final AtomicBoolean rendered = new AtomicBoolean(false);
        final MediaPlayer[] player = new MediaPlayer[1];
        final SurfaceTexture[] texture = new SurfaceTexture[1];
        final Surface[] surface = new Surface[1];
        final Runnable abort = new Runnable() { public void run() { done.countDown(); }};
        job.abortProbes.add(abort);
        h.post(new Runnable() { public void run() {
            try {
                if (job.isCancelled()) { done.countDown(); return; }
                texture[0] = new SurfaceTexture(false); texture[0].setDefaultBufferSize(320, 180);
                surface[0] = new Surface(texture[0]);
                final MediaPlayer p = new MediaPlayer(); player[0] = p;
                p.setSurface(surface[0]); p.setVolume(0f, 0f);
                p.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer mp, int what, int extra) { done.countDown(); return true; }});
                p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer mp) { done.countDown(); }});
                p.setOnInfoListener(new MediaPlayer.OnInfoListener() { public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { rendered.set(true); selection.nativeVerified = true; firstRendered.compareAndSet(null, selection); done.countDown(); }
                    return false;
                }});
                p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { public void onPrepared(MediaPlayer mp) {
                    try { if (!job.isCancelled()) mp.start(); else done.countDown(); }
                    catch (Exception error) { done.countDown(); }
                }});
                p.setDataSource(app, Uri.parse(selection.url), selection.headers);
                p.prepareAsync();
            } catch (Exception error) { done.countDown(); }
        }});
        try { done.await(waitMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        finally {
            job.abortProbes.remove(abort);
            h.post(new Runnable() { public void run() {
                try { if (player[0] != null) player[0].release(); } catch (Exception ignored) {}
                try { if (surface[0] != null) surface[0].release(); } catch (Exception ignored) {}
                try { if (texture[0] != null) texture[0].release(); } catch (Exception ignored) {}
                DECODER_SLOTS.release(); released.countDown(); thread.quitSafely();
            }});
            try { released.await(2000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return rendered.get() && !job.isCancelled();
    }
}
