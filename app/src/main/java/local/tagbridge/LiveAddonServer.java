package local.tagbridge;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local Stremio facade for checked live channels. No LAN listener or credential logging. */
public final class LiveAddonServer {
    public static final int PORT = 18765;
    public static final String MANIFEST_URL = "http://127.0.0.1:18765/manifest.json";
    private static final String PREFIX = "itaglive:";
    private final java.util.concurrent.ExecutorService catalogPool = java.util.concurrent.Executors.newFixedThreadPool(4);
    private static final String ALL_LIVE = "itaglive:all", GROUP_PREFIX = "itaglive:group:";
    private final java.util.concurrent.ConcurrentHashMap<String, ChannelGroup> groups = new java.util.concurrent.ConcurrentHashMap<String, ChannelGroup>();
    private static final int MAX_JSON = 8 * 1024 * 1024;
    private final Context context;
    private final List<String> providers = new ArrayList<String>();
    private final java.util.Map<String, Integer> providerIndexes = new java.util.HashMap<String, Integer>();
    private final java.util.Map<String, JSONObject> manifestSnapshots = new java.util.concurrent.ConcurrentHashMap<String, JSONObject>();
    private final java.util.concurrent.ConcurrentHashMap<String, SessionState> sessions = new java.util.concurrent.ConcurrentHashMap<String, SessionState>();
    private static final class SessionState {
        final String sessionId;
        final String wrappedId; final JSONArray original;
        volatile JSONArray returned = new JSONArray();
        final Set<String> failed = new CopyOnWriteArraySet<String>();
        final Set<String> failedSources = new CopyOnWriteArraySet<String>();
        final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
        final long createdAt; volatile long deadline, refreshedAt;
        volatile long lastUsed;
        SessionState(String id, JSONArray original, long began) { this(java.util.UUID.randomUUID().toString(), id, original, began); }
        SessionState(String sessionId, String id, JSONArray original, long began) { this.sessionId=sessionId; wrappedId = id; this.original = original; createdAt = began; deadline = began + 60000; refreshedAt = began; lastUsed = android.os.SystemClock.elapsedRealtime(); }
    }
    private final Set<Socket> clients = new CopyOnWriteArraySet<Socket>();
    private final Set<HttpURLConnection> connections = new CopyOnWriteArraySet<HttpURLConnection>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(12));
    private final LiveStreamChecker checker;
    private volatile ServerSocket server;
    private volatile boolean stopped;
    private volatile long descriptorAttemptAt;
    private final Object descriptorLock = new Object();
    private static final class SearchCache {
        final long at=android.os.SystemClock.elapsedRealtime();
        final List<ChannelGroup> groups;
        SearchCache(List<ChannelGroup> groups){this.groups=new ArrayList<ChannelGroup>(groups);}
    }
    private final java.util.concurrent.ConcurrentHashMap<String,SearchCache> searches=new java.util.concurrent.ConcurrentHashMap<String,SearchCache>();
    private volatile Listener listener;
    private volatile PlaybackSession lastSession;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    public interface Listener {
        void onSession(PlaybackSession session);
        default void onPlaybackRequest(String sessionId, String originalUrl) {}
    }
    public static final class PlaybackSession {
        public final String id, type, sessionId;
        public final JSONArray streams;
        public final long createdAt;
        PlaybackSession(String sessionId, String id, JSONArray streams) {
            this.sessionId = sessionId; this.id = id; this.type = "series"; this.streams = streams;
            this.createdAt = android.os.SystemClock.elapsedRealtime();
        }
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public PlaybackSession getLastSession() { return lastSession; }
    /** Called only after Nuvio reports an explicit error for a source in this session. */
    public boolean markFailed(String sessionId, String candidateUrl) {
        final SessionState state = sessions.get(sessionId);
        if (state == null || stopped || candidateUrl == null || android.os.SystemClock.elapsedRealtime() >= state.deadline) return false;
        boolean known = false;
        for (int i = 0; i < state.original.length(); i++) {
            JSONObject stream = state.original.optJSONObject(i);
            if (stream != null && candidateUrl.equals(stream.optString("url"))) { known = true; state.failedSources.add(sourceKey(stream)); break; }
        }
        if (!known) return false;
        state.failed.add(candidateUrl); persistSessions(); final int generation = state.generation.incrementAndGet();
        try { requests.execute(new Runnable() { public void run() {
            try {
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < state.original.length(); i++) {
                    JSONObject stream = state.original.optJSONObject(i);
                    if (stream != null && !state.failed.contains(stream.optString("url")) && !state.failedSources.contains(sourceKey(stream))) remaining.put(stream);
                }
                long budget = state.deadline - android.os.SystemClock.elapsedRealtime();
                LiveStreamChecker.Result result = checker.check(budget > 0 ? remaining : new JSONArray(), Math.max(1, Math.min(45000, budget)));
                if (!stopped && state.generation.get() == generation) { state.returned = result.streams; publish(new PlaybackSession(state.sessionId, state.wrappedId, result.streams)); }
            } catch (Exception error) {
                if (!stopped && state.generation.get() == generation) publish(new PlaybackSession(state.sessionId, state.wrappedId, new JSONArray()));
            }
        }}); return true; } catch (Exception busy) { return false; }
    }
    private synchronized void persistSessions() {
        try {
            JSONArray saved=new JSONArray();long now=System.currentTimeMillis();
            for(SessionState state:sessions.values()) {
                JSONObject item=new JSONObject().put("sessionId",state.sessionId).put("id",state.wrappedId).put("original",state.original).put("returned",state.returned).put("savedAt",now);
                saved.put(item);
            }
            android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(context.getFilesDir(),"checked-live-sessions.json"));
            java.io.FileOutputStream out=file.startWrite();try{out.write(saved.toString().getBytes("UTF-8"));file.finishWrite(out);}catch(Exception error){file.failWrite(out);}
        }catch(Exception ignored){}
    }
    private void restoreSessions() {
        try {
            android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(context.getFilesDir(),"checked-live-sessions.json"));
            JSONArray saved=new JSONArray(new String(file.readFully(),"UTF-8"));
            for(int i=0;i<saved.length()&&sessions.size()<32;i++) {
                JSONObject item=saved.optJSONObject(i);if(item==null||System.currentTimeMillis()-item.optLong("savedAt",0)>7L*24*60*60*1000)continue;
                SessionState state=new SessionState(item.getString("sessionId"),item.getString("id"),item.getJSONArray("original"),android.os.SystemClock.elapsedRealtime());
                state.returned=item.optJSONArray("returned");if(state.returned==null)state.returned=new JSONArray();
                state.refreshedAt=android.os.SystemClock.elapsedRealtime()-121000;
                sessions.put(state.sessionId,state);
            }
        }catch(Exception absent){}
    }
    private static String sourceKey(JSONObject stream) {
        // Ignore expiring query tokens while keeping provider/quality/host/path identity.
        String name = stream.optString("itagOriginalName", stream.optString("name"));
        String title = stream.optString("title");
        Uri uri = Uri.parse(stream.optString("url"));
        return name + "\n" + title + "\n" + uri.getHost() + "\n" + uri.getPath();
    }
    private void publish(final PlaybackSession session) {
        SessionState state = sessions.get(session.sessionId);
        final int generation = state == null ? -1 : state.generation.get();
        main.post(new Runnable() { public void run() {
            SessionState currentState = sessions.get(session.sessionId);
            if (stopped || currentState == null || currentState.generation.get() != generation) return;
            lastSession = session;
            Listener current = listener; if (current != null) current.onSession(session);
        }});
    }

    public LiveAddonServer(Context context) { this.context = context.getApplicationContext(); checker = new LiveStreamChecker(this.context); }
    public synchronized void start() throws Exception {
        if (server != null && !server.isClosed()) return;
        if (stopped) throw new IllegalStateException("Create a new server after stop");
        JSONArray configured=CheckMateConfig.liveProviders(context);
        JSONArray snapshots=CheckMateConfig.manifestSnapshots(context);
        for(int i=0;i<snapshots.length();i++) {
            JSONObject entry=snapshots.optJSONObject(i);
            if(entry!=null&&entry.optJSONObject("manifest")!=null)manifestSnapshots.put(entry.optString("url"),entry.optJSONObject("manifest"));
        }
        providers.clear();
        for (int i = 0; i < configured.length(); i++) {
            String url = configured.optString(i, "");
            try{providers.add(CheckMateConfig.providerBase(url));}catch(IllegalArgumentException invalid){}
        }
        for (int i = 0; i < providers.size(); i++) providerIndexes.put(providerKey(providers.get(i)), i);
        if (providers.isEmpty()) throw new IllegalStateException("No configured providers");
        restoreSessions();
        server = new ServerSocket(PORT, 12, InetAddress.getByName("127.0.0.1"));
        new Thread(new Runnable() { public void run() { accept(); }}, "LiveAddonHTTP").start();
        Log.i("ITagBridge", "TV-local checked-live addon listening on loopback port " + PORT);
    }
    public synchronized void stop() {
        persistSessions();
        stopped = true;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        for (Socket socket : clients) try { socket.close(); } catch (Exception ignored) {}
        for (HttpURLConnection connection : connections) connection.disconnect();
        checker.stop(); requests.shutdownNow(); catalogPool.shutdownNow();
    }
    private void accept() {
        while (!stopped) {
            try {
                final Socket socket = server.accept(); clients.add(socket); socket.setSoTimeout(10000);
                try { requests.execute(new Runnable() { public void run() { handle(socket); }}); }
                catch (Exception busy) { send(socket, 503, "{\"error\":\"Checker busy; retry shortly\"}"); clients.remove(socket); socket.close(); }
            } catch (Exception error) { if (stopped) return; }
        }
    }
    private void handle(Socket socket) {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            String request = input.readLine();
            if (request == null || request.length() > 16384) { send(socket, 400, "{}"); return; }
            String[] requestParts = request.split(" ");
            if (requestParts.length != 3 || !"GET".equals(requestParts[0])) { send(socket, 405, "{}"); return; }
            // Bound all headers and ignore them; no upstream authentication is accepted from clients.
            int size = 0; String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) { size += line.length(); if (size > 32768) throw new java.io.IOException("Headers too large"); }
            String path = requestParts[1];
            if (!path.startsWith("/") || path.contains("..")) { send(socket, 400, "{}"); return; }
            if (path.startsWith("/play/")) { playbackRedirect(socket, path); return; }
            String result = route(path);
            send(socket, 200, result);
        } catch (Exception unavailable) {
            try { send(socket, 502, "{\"error\":\"Live provider unavailable; retry shortly\"}"); } catch (Exception ignored) {}
        } finally {
            clients.remove(socket); try { socket.close(); } catch (Exception ignored) {}
        }
    }
    private void playbackRedirect(Socket socket, String path) throws Exception {
        String[] parts = path.split("\\?", 2)[0].split("/");
        if (parts.length != 4 && parts.length != 5) { send(socket, 400, "{}"); return; }
        final SessionState state = sessions.get(parts[2]);
        if (state != null) state.lastUsed = android.os.SystemClock.elapsedRealtime();
        int index;
        try { index = Integer.parseInt(parts[3]); } catch (Exception invalid) { send(socket, 400, "{}"); return; }
        if (state == null || index < 0 || index >= state.original.length()) { send(socket, 404, "{}"); return; }
        JSONObject candidate = state.original.optJSONObject(index);
        if (state.wrappedId.startsWith(GROUP_PREFIX) && android.os.SystemClock.elapsedRealtime()-state.refreshedAt>120000) {
            synchronized(state) {
                if(android.os.SystemClock.elapsedRealtime()-state.refreshedAt>120000) {
                    long deadline=android.os.SystemClock.elapsedRealtime()+50000;
                    ChannelGroup group=freshGroup(state.wrappedId,deadline);
                    JSONArray fresh=groupedStreams(group,Math.min(deadline,android.os.SystemClock.elapsedRealtime()+12000));
                    LiveStreamChecker.Result checked=checker.check(fresh,Math.max(1,Math.min(20000,deadline-android.os.SystemClock.elapsedRealtime()-1000)));
                    JSONObject replacement=null;String wanted=candidate==null?"":sourceKey(candidate);
                    for(int i=0;i<checked.streams.length();i++){JSONObject source=checked.streams.optJSONObject(i);if(source!=null&&!source.optString("url").isEmpty()){if(replacement==null)replacement=source;if(sourceKey(source).equals(wanted)){replacement=source;break;}}}
                    if(replacement==null){send(socket,502,"{}");return;}
                    state.original.put(index,replacement);state.returned=checked.streams;state.failed.clear();state.failedSources.clear();state.generation.incrementAndGet();
                    state.refreshedAt=android.os.SystemClock.elapsedRealtime();state.deadline=state.refreshedAt+60000;
                    persistSessions();candidate=replacement;
                }
            }
        }
        // Restore/publish first, then deliver actual playback proof in main-thread FIFO order.
        publish(new PlaybackSession(state.sessionId,state.wrappedId,state.returned));
        final String original = candidate == null ? "" : candidate.optString("url");
        if ((!original.startsWith("https://") && !original.startsWith("http://")) || original.contains("\r") || original.contains("\n")) { send(socket, 400, "{}"); return; }
        main.post(new Runnable() { public void run() { Listener current = listener; if (!stopped && current != null) current.onPlaybackRequest(state.sessionId, original); }});
        String response = "HTTP/1.1 302 Found\r\nLocation: " + original + "\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(response.getBytes("UTF-8")); socket.getOutputStream().flush();
    }
    private JSONArray playbackMarkers(SessionState state, JSONArray streams) throws Exception {
        JSONArray marked = new JSONArray();
        for (int i = 0; i < streams.length(); i++) {
            JSONObject original = streams.optJSONObject(i); if (original == null) continue;
            JSONObject copy = new JSONObject(original.toString());
            String url = copy.optString("url");
            if (url.startsWith("http://") || url.startsWith("https://")) {
                int originalIndex = -1;
                for (int j = 0; j < state.original.length(); j++) {
                    JSONObject source = state.original.optJSONObject(j);
                    if (source != null && url.equals(source.optString("url"))) { originalIndex = j; break; }
                }
                String suffix = "";
                String originalPath = Uri.parse(url).getPath();
                if (originalPath != null) {
                    String lower = originalPath.toLowerCase(java.util.Locale.ROOT);
                    for (String extension : new String[]{".m3u8", ".mpd", ".mp4", ".mkv", ".ts"}) if (lower.endsWith(extension)) { suffix = "/source" + extension; break; }
                }
                if (originalIndex >= 0) copy.put("url", "http://127.0.0.1:" + PORT + "/play/" + state.sessionId + "/" + originalIndex + suffix);
            }
            marked.put(copy);
        }
        return marked;
    }
    private static void send(Socket socket, int status, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 503 ? "Service Unavailable" : "Bad Gateway";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.length + "\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream(); out.write(headers.getBytes("US-ASCII")); out.write(body); out.flush();
    }
    private JSONObject fetch(String url) throws Exception { return fetch(url, android.os.SystemClock.elapsedRealtime() + 45000); }
    private JSONObject fetch(String url, long deadline) throws Exception {
        if (stopped) throw new InterruptedException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(); connections.add(connection);
        int timeout = (int) Math.max(1, Math.min(3000, deadline - android.os.SystemClock.elapsedRealtime()));
        connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout); connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "iTag-TV-Live-Addon/1.0");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Upstream unavailable");
            return new JSONObject(read(connection.getInputStream(), deadline));
        } finally { connection.disconnect(); connections.remove(connection); }
    }
    private static String read(InputStream input) throws Exception { return read(input, Long.MAX_VALUE); }
    private static String read(InputStream input, long deadline) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new java.io.IOException("Provider timed out");
                if (out.size() + n > MAX_JSON) throw new java.io.IOException("Response too large");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } finally { input.close(); }
    }
    private static String providerKey(String base) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(base.getBytes("UTF-8"));
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 8; i++) key.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 255));
        return key.toString();
    }
    private String wrap(int provider, String type, String id) throws Exception {
        String payload = new JSONObject().put("type", type).put("id", id).toString();
        return PREFIX + providerKey(providers.get(provider)) + ":" + Base64.encodeToString(payload.getBytes("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
    private static final class RoutedId {
        final int provider; final String original, upstreamType;
        RoutedId(int provider, String original, String type) { this.provider = provider; this.original = original; this.upstreamType = type; }
    }
    private RoutedId unwrap(String wrapped) throws Exception {
        if (!wrapped.startsWith(PREFIX)) throw new java.io.IOException("Not a checked live ID");
        String[] parts = wrapped.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) throw new java.io.IOException("Invalid live ID");
        Integer mapped = providerIndexes.get(parts[0]);
        if (mapped == null) throw new java.io.IOException("Unknown provider");
        int provider = mapped;
        JSONObject payload = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "UTF-8"));
        String original = payload.getString("id"), type = payload.getString("type");
        if (original.length() == 0 || original.length() > 16384) throw new java.io.IOException("Invalid ID length");
        return new RoutedId(provider, original, type);
    }
    private static boolean liveCatalog(JSONObject catalog) {
        String type = catalog.optString("type").toLowerCase(java.util.Locale.ROOT);
        String name = catalog.optString("name").toLowerCase(java.util.Locale.ROOT);
        if (!(type.equals("tv") || type.equals("channel") || type.equals("iptv") || type.equals("live") || ((type.equals("series") || type.equals("other")) && name.contains("iptv")))) return false;
        // An upstream type=tv may still be VOD, replay clips or standalone sports events.
        if (name.matches(".*(highlights?|replays?|catch.?up|vod|movies?|series|live events?|sun.?nxt|tamilultra|stremverse xtra|jiohotstar).*")) return false;
        String meaningful = name.replace("(tv)", "").trim();
        return name.contains("📺") || meaningful.contains("iptv") || meaningful.contains("jiotv") ||
                meaningful.contains("jio tv") || meaningful.contains("jtv") || meaningful.contains("indiatv") ||
                meaningful.contains("india tv") || meaningful.contains("worldtv") || meaningful.contains("world tv") ||
                meaningful.matches(".*\\btv\\b.*");
    }
    private static final class LiveCatalog {
        final int provider; final String type, id;
        LiveCatalog(int provider, String type, String id) { this.provider=provider; this.type=type; this.id=id; }
    }
    private static final class Member {
        final int provider; final String type, id, catalogId;
        Member(int provider, String type, String id, String catalogId) { this.provider=provider; this.type=type; this.id=id; this.catalogId=catalogId; }
    }
    private static final class ChannelGroup {
        final String key; final JSONObject meta;
        final long discoveredAt=android.os.SystemClock.elapsedRealtime();
        final List<Member> members = new ArrayList<Member>();
        ChannelGroup(String key, JSONObject meta) { this.key=key; this.meta=meta; }
    }
    public static String normalizeChannelName(String name) {
        String s=java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        StringBuilder flags=new StringBuilder();
        for(int i=0;i<s.length();) { int cp=s.codePointAt(i); if(cp>=0x1f1e6&&cp<=0x1f1ff) flags.append((char)('a'+cp-0x1f1e6)); i+=Character.charCount(cp); }
        s=s.replaceAll("(?i)\\b(?:uhd|fhd|hd|sd|4k|8k|[0-9]{3,4}p)\\b", " ");
        s=s.replaceAll("(?i)(?:[ _.-]*(?:[0-9]{3,4}p|uhd|fhd|hd|sd|4k|8k))+$", " ");
        s=s.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
        s=s.replaceAll("(?:\\s+tv|(?<=hungama)tv)$", "").trim();
        if(flags.length()>0) s+=" region "+flags;
        return s;
    }
    private static String groupId(String key) throws Exception {
        return GROUP_PREFIX+Base64.encodeToString(key.getBytes("UTF-8"),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }
    private static String groupKey(String id) throws Exception {
        if(!id.startsWith(GROUP_PREFIX)) throw new java.io.IOException("Not a live group");
        String key=new String(Base64.decode(id.substring(GROUP_PREFIX.length()),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),"UTF-8");
        if(key.length()==0||key.length()>300) throw new java.io.IOException("Invalid channel key");
        return key;
    }
    /** Descriptor loading occurs on HTTP workers, never on the Android main thread. */
    private void ensureDescriptors(long deadline)throws Exception {
        long now=android.os.SystemClock.elapsedRealtime();
        if(descriptorAttemptAt>0&&now-descriptorAttemptAt<60000)return;
        synchronized(descriptorLock) {
            now=android.os.SystemClock.elapsedRealtime();if(descriptorAttemptAt>0&&now-descriptorAttemptAt<60000)return;
            final long loadDeadline=Math.min(deadline,now+12000);descriptorAttemptAt=now;
            List<java.util.concurrent.Future<JSONObject>> futures=new ArrayList<java.util.concurrent.Future<JSONObject>>();
            for(String base:providers){final String url=CheckMateConfig.providerResource(base,"manifest.json");if(manifestSnapshots.containsKey(url))continue;
                futures.add(catalogPool.submit(new java.util.concurrent.Callable<JSONObject>(){public JSONObject call(){try{JSONObject value=fetch(url,loadDeadline);manifestSnapshots.put(url,value);return value;}catch(Exception unavailable){return null;}}}));
            }
            try{for(java.util.concurrent.Future<JSONObject> future:futures){long remain=loadDeadline-android.os.SystemClock.elapsedRealtime();if(remain<=0)break;try{future.get(remain,TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException expired){break;}}}
            finally{for(java.util.concurrent.Future<JSONObject> future:futures)if(!future.isDone())future.cancel(true);}
        }
    }
    private int configuredProviderCount(ChannelGroup group){
        Set<String> unique=new HashSet<String>();for(Member member:group.members)unique.add(providers.get(member.provider));return unique.size();
    }
    private List<LiveCatalog> liveCatalogs() {
        List<LiveCatalog> result=new ArrayList<LiveCatalog>();Set<String> seen=new HashSet<String>();
        for(int p=0;p<providers.size();p++) {
            JSONObject descriptor=manifestSnapshots.get(CheckMateConfig.providerResource(providers.get(p),"manifest.json"));
            JSONArray catalogs=descriptor==null?null:descriptor.optJSONArray("catalogs");if(catalogs==null)continue;
            for(int i=0;i<catalogs.length();i++) { JSONObject c=catalogs.optJSONObject(i);if(c==null||!liveCatalog(c))continue;
                String type=c.optString("type"), id=c.optString("id");
                if(seen.add(providers.get(p)+"\n"+type+"\n"+id)) result.add(new LiveCatalog(p,type,id));
            }
        }
        return result;
    }
    private List<ChannelGroup> discover(final String search,long deadline) throws Exception {
        String cacheKey=search.trim().toLowerCase(java.util.Locale.ROOT);
        SearchCache cached=searches.get(cacheKey);
        if(cached!=null&&android.os.SystemClock.elapsedRealtime()-cached.at<30000)return new ArrayList<ChannelGroup>(cached.groups);
        ensureDescriptors(deadline);
        java.util.concurrent.ExecutorCompletionService<JSONObject> complete=new java.util.concurrent.ExecutorCompletionService<JSONObject>(catalogPool);
        List<java.util.concurrent.Future<JSONObject>> futures=new ArrayList<java.util.concurrent.Future<JSONObject>>();
        final long searchDeadline=Math.min(deadline,android.os.SystemClock.elapsedRealtime()+20000);
        for(final LiveCatalog catalog:liveCatalogs()) {
            futures.add(complete.submit(new java.util.concurrent.Callable<JSONObject>(){public JSONObject call(){
                try {
                    String resource="catalog/"+Uri.encode(catalog.type)+"/"+Uri.encode(catalog.id);
                    if(!search.isEmpty())resource+="/search="+Uri.encode(search);
                    JSONObject response=fetch(CheckMateConfig.providerResource(providers.get(catalog.provider),resource+".json"),searchDeadline);
                    return new JSONObject().put("provider",catalog.provider).put("type",catalog.type).put("catalogId",catalog.id).put("metas",response.optJSONArray("metas"));
                }catch(Exception unavailable){return null;}
            }}));
        }
        java.util.LinkedHashMap<String,ChannelGroup> merged=new java.util.LinkedHashMap<String,ChannelGroup>();
        try {
            for(int n=0;n<futures.size()&&!stopped;n++) {
                long remain=searchDeadline-android.os.SystemClock.elapsedRealtime();if(remain<=0)break;
                java.util.concurrent.Future<JSONObject> future=complete.poll(remain,TimeUnit.MILLISECONDS);if(future==null)break;
                JSONObject response=future.get();if(response==null)continue;
                JSONArray metas=response.optJSONArray("metas");if(metas==null)continue;
                int provider=response.getInt("provider");String fallbackType=response.getString("type");
                for(int i=0;i<metas.length();i++) {
                    JSONObject meta=metas.optJSONObject(i);if(meta==null)continue;
                    String name=meta.optString("name"),id=meta.optString("id"),type=meta.optString("type",fallbackType);
                    if(name.isEmpty()||id.isEmpty()||type.equals("movie"))continue;
                    String key=normalizeChannelName(name);if(key.isEmpty())continue;
                    ChannelGroup group=merged.get(key);if(group==null){group=new ChannelGroup(key,new JSONObject(meta.toString()));merged.put(key,group);}
                    boolean duplicate=false;for(Member member:group.members)if(member.provider==provider&&member.type.equals(type)&&member.id.equals(id)){duplicate=true;break;}
                    if(!duplicate)group.members.add(new Member(provider,type,id,response.optString("catalogId")));
                }
            }
        }finally{for(java.util.concurrent.Future<JSONObject> future:futures)if(!future.isDone())future.cancel(true);}
        List<ChannelGroup> result=new ArrayList<ChannelGroup>(merged.values());
        java.util.Collections.sort(result,new java.util.Comparator<ChannelGroup>(){public int compare(ChannelGroup a,ChannelGroup b){int count=configuredProviderCount(b)-configuredProviderCount(a);return count!=0?count:a.key.compareTo(b.key);}});
        for(ChannelGroup group:result) {
            java.util.Collections.sort(group.members,new java.util.Comparator<Member>(){public int compare(Member a,Member b){int av=a.catalogId.equals("cnc_JIOTVIND_tv")?0:1,bv=b.catalogId.equals("cnc_JIOTVIND_tv")?0:1;return av!=bv?av-bv:a.provider-b.provider;}});
            groups.put(group.key,group);
        }
        if(searches.size()>32)searches.clear();
        if(!result.isEmpty())searches.put(cacheKey,new SearchCache(result));
        return result;
    }
    private JSONObject groupedMeta(ChannelGroup group)throws Exception {
        JSONObject meta=new JSONObject(group.meta.toString());meta.put("id",groupId(group.key));meta.put("type","series");
        // Quality variants share one identity; retain language/region/Super text.
        meta.put("name",group.meta.optString("name").replaceAll("(?i)\\s*\\([^)]*(?:[0-9]{3,4}p|hd|sd)[^)]*\\)","").trim());
        meta.put("itagMemberCount",group.members.size()).put("itagProviderCount",configuredProviderCount(group));
        meta.remove("videos");return meta;
    }
    private ChannelGroup freshGroup(String id,long deadline)throws Exception {
        String key=groupKey(id);ChannelGroup result=null;
        for(ChannelGroup group:discover(key.replace(" region "," "),deadline))if(group.key.equals(key)){result=group;break;}
        if(result==null)throw new java.io.IOException("Live channel unavailable");return result;
    }
    private JSONArray groupedStreams(ChannelGroup group,final long deadline)throws Exception {
        java.util.concurrent.ExecutorCompletionService<JSONArray> complete=new java.util.concurrent.ExecutorCompletionService<JSONArray>(catalogPool);
        List<java.util.concurrent.Future<JSONArray>> futures=new ArrayList<java.util.concurrent.Future<JSONArray>>();
        java.util.Map<java.util.concurrent.Future<JSONArray>,Integer> positions=new java.util.HashMap<java.util.concurrent.Future<JSONArray>,Integer>();
        java.util.Map<Integer,JSONArray> responses=new java.util.HashMap<Integer,JSONArray>();
        for(final Member member:group.members) {
            java.util.concurrent.Future<JSONArray> future=complete.submit(new java.util.concurrent.Callable<JSONArray>(){public JSONArray call(){
                try{return fetch(CheckMateConfig.providerResource(providers.get(member.provider),"stream/"+Uri.encode(member.type)+"/"+Uri.encode(member.id)+".json"),deadline).optJSONArray("streams");}catch(Exception unavailable){return null;}
            }});
            positions.put(future,futures.size());futures.add(future);
        }
        try{for(int i=0;i<futures.size();i++){long remain=deadline-android.os.SystemClock.elapsedRealtime();if(remain<=0)break;java.util.concurrent.Future<JSONArray> future=complete.poll(remain,TimeUnit.MILLISECONDS);if(future==null)break;JSONArray response=future.get();if(response!=null)responses.put(positions.get(future),response);}}
        finally{for(java.util.concurrent.Future<JSONArray> future:futures)if(!future.isDone())future.cancel(true);}
        JSONArray streams=new JSONArray();Set<String> seen=new HashSet<String>();
        for(int i=0;i<futures.size();i++){JSONArray response=responses.get(i);if(response==null)continue;for(int j=0;j<response.length();j++){JSONObject stream=response.optJSONObject(j);if(stream==null)continue;String identity=stream.toString();if(seen.add(identity))streams.put(stream);}}
        return streams;
    }
    private JSONObject manifest()throws Exception {
        JSONObject manifest=new JSONObject();manifest.put("id","local.itag.checked-live").put("version","1.1.0").put("name","CheckMate").put("description","One live television catalog, deduplicated across configured providers; fresh sources checked on this TV.");
        JSONArray types=new JSONArray().put("series");
        JSONObject catalog=new JSONObject().put("id",ALL_LIVE).put("type","series").put("name","Live TV").put("extra",new JSONArray().put(new JSONObject().put("name","search").put("isRequired",false)).put(new JSONObject().put("name","skip").put("isRequired",false)));
        JSONArray resources=new JSONArray().put("catalog").put(new JSONObject().put("name","meta").put("types",types).put("idPrefixes",new JSONArray().put(PREFIX))).put(new JSONObject().put("name","stream").put("types",types).put("idPrefixes",new JSONArray().put(PREFIX)));
        return manifest.put("types",types).put("catalogs",new JSONArray().put(catalog)).put("resources",resources).put("behaviorHints",new JSONObject().put("configurable",false));
    }
    private JSONObject legacyManifest() throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("id", "local.itag.checked-live"); manifest.put("version", "1.0.1");
        manifest.put("name", "CheckMate");
        manifest.put("description", "Fresh live sources checked on this TV. Server failures are skipped; native-inconclusive sources remain available to Nuvio.");
        JSONArray catalogs = new JSONArray(); Set<String> types = new HashSet<String>();
        for (int i = 0; i < providers.size(); i++) {
            try {
                String manifestUrl = CheckMateConfig.providerResource(providers.get(i),"manifest.json");
                JSONObject descriptor = manifestSnapshots.get(manifestUrl);
                if (descriptor == null) { descriptor = fetch(manifestUrl); manifestSnapshots.put(manifestUrl, descriptor); }
                JSONArray upstream = descriptor.optJSONArray("catalogs");
                if (upstream == null) continue;
                for (int j = 0; j < upstream.length(); j++) {
                    JSONObject catalog = upstream.optJSONObject(j);
                    if (catalog == null || !liveCatalog(catalog)) continue;
                    JSONObject copy = new JSONObject(catalog.toString());
                    copy.put("id", wrap(i, catalog.optString("type"), catalog.optString("id")));
                    copy.put("type", "series");
                    copy.put("name", catalog.optString("name") + " · TV checked");
                    catalogs.put(copy); types.add("series");
                }
            } catch (Exception unavailable) { /* Other configured providers remain usable. */ }
        }
        if (catalogs.length() == 0) throw new java.io.IOException("No live catalogs");
        JSONArray mediaTypes = new JSONArray(); for (String type : types) mediaTypes.put(type);
        JSONArray resources = new JSONArray();
        resources.put("catalog");
        resources.put(new JSONObject().put("name", "meta").put("types", mediaTypes).put("idPrefixes", new JSONArray().put(PREFIX)));
        resources.put(new JSONObject().put("name", "stream").put("types", mediaTypes).put("idPrefixes", new JSONArray().put(PREFIX)));
        manifest.put("types", mediaTypes); manifest.put("catalogs", catalogs); manifest.put("resources", resources);
        manifest.put("behaviorHints", new JSONObject().put("configurable", false).put("configurationRequired", false));
        return manifest;
    }
    private JSONObject wrapMeta(JSONObject meta, int provider, String upstreamType) throws Exception {
        JSONObject copy = new JSONObject(meta.toString());
        String originalType = copy.optString("type", upstreamType);
        String id = copy.optString("id"); if (!id.isEmpty()) copy.put("id", wrap(provider, originalType, id));
        copy.put("type", "series");
        JSONArray videos = copy.optJSONArray("videos");
        if (videos != null) for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video != null && !video.optString("id").isEmpty()) video.put("id", wrap(provider, originalType, video.optString("id")));
        }
        return copy;
    }
    private String route(String target) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 52000;
        String path = target.split("\\?", 2)[0];
        if ("/manifest.json".equals(path)) return manifest().toString();
        if ("/health.json".equals(path)) return new JSONObject().put("running", true).put("providers", providers.size()).toString();
        if (!path.endsWith(".json")) throw new java.io.IOException("Unknown resource");
        String[] parts = path.substring(1, path.length() - 5).split("/");
        if (parts.length < 3) throw new java.io.IOException("Invalid request");
        String resource = parts[0], type = Uri.decode(parts[1]);
        String requestId=Uri.decode(parts[2]);
        if(resource.equals("catalog")&&requestId.equals(ALL_LIVE)) {
            String search="";int skip=0;
            if(parts.length>3){Uri extras=Uri.parse("http://localhost/?"+parts[3]);String q=extras.getQueryParameter("search");if(q!=null)search=q;try{skip=Math.max(0,Integer.parseInt(extras.getQueryParameter("skip")));}catch(Exception ignored){}}
            List<ChannelGroup> found=discover(search,deadline);JSONArray metas=new JSONArray();
            for(int i=skip;i<found.size()&&i<skip+200;i++)metas.put(groupedMeta(found.get(i)));
            return new JSONObject().put("metas",metas).put("cacheMaxAge",0).toString();
        }
        if(requestId.startsWith(GROUP_PREFIX)) {
            ChannelGroup group=groups.get(groupKey(requestId));
            if(!resource.equals("meta")||group==null||android.os.SystemClock.elapsedRealtime()-group.discoveredAt>60000)group=freshGroup(requestId,deadline);
            if(resource.equals("meta"))return new JSONObject().put("meta",groupedMeta(group)).put("cacheMaxAge",0).toString();
            if(!resource.equals("stream"))throw new java.io.IOException("Unsupported group resource");
            JSONObject response=new JSONObject().put("itagMemberCount",group.members.size()).put("itagProviderCount",configuredProviderCount(group)).put("streams",groupedStreams(group,Math.min(deadline,android.os.SystemClock.elapsedRealtime()+15000)));
            return checkedResponse(requestId,response,deadline).toString();
        }
        RoutedId routed;
        try { routed = unwrap(Uri.decode(parts[2])); }
        catch (Exception unowned) {
            // This addon never captures or modifies another addon's VOD requests.
            return new JSONObject().put("stream".equals(resource) ? "streams" : "metas", new JSONArray()).toString();
        }
        if (!resource.equals("catalog") && !resource.equals("meta") && !resource.equals("stream")) throw new java.io.IOException("Unknown resource");
        StringBuilder upstream = new StringBuilder(resource).append('/').append(Uri.encode(routed.upstreamType)).append('/').append(Uri.encode(routed.original));
        for (int i = 3; i < parts.length; i++) upstream.append('/').append(parts[i]);
        upstream.append(".json");
        JSONObject response = fetch(CheckMateConfig.providerResource(providers.get(routed.provider),upstream.toString()), deadline);
        if (resource.equals("catalog")) {
            JSONArray metas = response.optJSONArray("metas"), wrapped = new JSONArray();
            if (metas != null) for (int i = 0; i < metas.length(); i++) { JSONObject meta = metas.optJSONObject(i); if (meta != null) wrapped.put(wrapMeta(meta, routed.provider, routed.upstreamType)); }
            response.put("metas", wrapped);
        } else if (resource.equals("meta")) {
            JSONObject meta = response.optJSONObject("meta"); if (meta != null) response.put("meta", wrapMeta(meta, routed.provider, routed.upstreamType));
        } else {
            return checkedResponse(Uri.decode(parts[2]),response,deadline).toString();
        }
        response.put("cacheMaxAge", 0); response.put("staleRevalidate", 0); response.put("staleError", 0);
        return response.toString();
    }
    private JSONObject checkedResponse(String wrappedId,JSONObject response,long deadline)throws Exception {
            JSONArray streams = response.optJSONArray("streams");
            if (streams == null) streams = new JSONArray();
            long began = android.os.SystemClock.elapsedRealtime();
            
            SessionState state = null;
            for (SessionState previous : sessions.values()) {
                if (previous.wrappedId.equals(wrappedId) && !previous.failed.isEmpty() && android.os.SystemClock.elapsedRealtime() - previous.createdAt < 65000) { state = previous; break; }
            }
            if (state == null) state = new SessionState(wrappedId, streams, deadline - 52000);
            else synchronized (state.original) {
                // Append fresh source records, never reorder marker indexes from an active retry.
                Set<String> knownUrls = new HashSet<String>();
                for (int i = 0; i < state.original.length(); i++) { JSONObject source = state.original.optJSONObject(i); if (source != null) knownUrls.add(source.optString("url")); }
                for (int i = 0; i < streams.length(); i++) { JSONObject source = streams.optJSONObject(i); if (source != null && knownUrls.add(source.optString("url"))) state.original.put(source); }
            }
            JSONArray eligible = new JSONArray();
            for (int i = 0; i < streams.length(); i++) { JSONObject source = streams.optJSONObject(i); if (source != null && !state.failed.contains(source.optString("url")) && !state.failedSources.contains(sourceKey(source))) eligible.put(source); }
            int generation = state.generation.incrementAndGet();
            long budget = Math.min(deadline - android.os.SystemClock.elapsedRealtime() - 1000, state.deadline - android.os.SystemClock.elapsedRealtime());
            LiveStreamChecker.Result checked = checker.check(budget > 0 ? eligible : new JSONArray(), Math.max(1, Math.min(45000, budget)));
            response.put("streams", checked.streams);
            for (java.util.Map.Entry<String, SessionState> entry : sessions.entrySet()) {
                if (android.os.SystemClock.elapsedRealtime() - entry.getValue().createdAt > 300000) sessions.remove(entry.getKey());
            }
            if (sessions.size() > 32) {
                SessionState oldest = null;
                for (SessionState candidate : sessions.values()) {
                    if (candidate != state && (oldest == null || candidate.lastUsed < oldest.lastUsed)) oldest = candidate;
                }
                if (oldest != null) sessions.remove(oldest.sessionId, oldest);
            }
            state.lastUsed = android.os.SystemClock.elapsedRealtime();
            state.returned = checked.streams;
            sessions.put(state.sessionId, state);
            persistSessions();
            response.put("streams", playbackMarkers(state, checked.streams));
            if (state.generation.get() == generation) publish(new PlaybackSession(state.sessionId, state.wrappedId, checked.streams));
            response.put("cacheMaxAge", 0); response.put("staleRevalidate", 0); response.put("staleError", 0);
            Log.i("ITagBridge", "Live addon checked " + checked.tested + " sources; verified=" + checked.verified + ", serverFailures=" + checked.rejected + ", elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - began));
        
        response.put("cacheMaxAge",0).put("staleRevalidate",0).put("staleError",0);
        return response;
    }

}
