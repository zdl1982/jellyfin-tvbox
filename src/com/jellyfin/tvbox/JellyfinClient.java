package com.jellyfin.tvbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

/**
 * Jellyfin API client. Uses TlsHelper to enable TLS 1.2 on Android 4.x
 * and trusts all SSL certs for self-signed / proxy certificates common
 * on media servers.
 */
public class JellyfinClient {
    private static final String CLIENT = "Jellyfin TV Box";
    private static final String DEVICE = "AndroidTV";
    private static final String VERSION = "1.0.0";

    private final String deviceId;
    private String serverUrl;
    private String token;
    private String userId;

    static {
        // Apply TLS 1.2-capable socket factory globally
        HttpsURLConnection.setDefaultSSLSocketFactory(TlsHelper.getSocketFactory());
        // Trust all hostnames (self-signed certs, proxy, etc.)
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String h, SSLSession s) { return true; }
        });
    }

    public JellyfinClient(String serverUrl) {
        this(serverUrl, null);
    }

    /**
     * @param serverUrl Jellyfin server base URL
     * @param deviceId  unique per-device ID. Jellyfin distinguishes clients by
     *                  DeviceId — a hardcoded shared value would make multiple
     *                  devices/users kick each other's sessions. Pass a unique
     *                  value (ANDROID_ID or a persisted random UUID).
     */
    public JellyfinClient(String serverUrl, String deviceId) {
        // strip trailing slash
        this.serverUrl = serverUrl;
        while (this.serverUrl.endsWith("/")) {
            this.serverUrl = this.serverUrl.substring(0, this.serverUrl.length() - 1);
        }
        this.deviceId = (deviceId == null || deviceId.isEmpty())
            ? "jellyfin-tvbox-" + Math.abs((serverUrl + System.currentTimeMillis()).hashCode())
            : deviceId;
    }

    public String getToken() { return token; }
    public String getUserId() { return userId; }
    public String getServerUrl() { return serverUrl; }

    private String authHeader() {
        return "MediaBrowser Client=\"" + CLIENT + "\", Device=\"" + DEVICE +
               "\", DeviceId=\"" + deviceId + "\", Version=\"" + VERSION + "\"";
    }

    private String http(String method, String path, String body, boolean withToken) throws Exception {
        URL url = new URL(serverUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Emby-Authorization", authHeader());
            if (withToken && token != null) {
                conn.setRequestProperty("X-Emby-Token", token);
            }
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) is = conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) {
                throw new HttpException(code, sb.toString());
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    public static class HttpException extends Exception {
        public int code;
        public HttpException(int c, String m) { super(m); code = c; }
    }

    /** Login and obtain token. */
    public void login(String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("Username", username);
        body.put("Pw", password);
        String resp = http("POST", "/Users/AuthenticateByName", body.toString(), false);
        JSONObject json = new JSONObject(resp);
        token = json.getString("AccessToken");
        userId = json.getJSONObject("User").getString("Id");
    }

    /** Get list of media libraries (views). */
    public List<MediaItem> getLibraries() throws Exception {
        String resp = http("GET", "/Users/" + userId + "/Views", null, true);
        return parseItems(new JSONObject(resp));
    }

    /** Get items in a folder/library. */
    public List<MediaItem> getItems(String parentId) throws Exception {
        return getItems(parentId, "SortName");
    }

    /** Get items in a folder/library with custom sort order.
     *  @param sortBy Jellyfin sort field, e.g. "SortName", "IndexNumber,ParentIndexNumber"
     */
    public List<MediaItem> getItems(String parentId, String sortBy) throws Exception {
        String path = "/Users/" + userId + "/Items?ParentId=" + parentId +
                      "&Fields=Overview,PrimaryImageAspectRatio&Recursive=false&SortBy=" + sortBy;
        String resp = http("GET", path, null, true);
        return parseItems(new JSONObject(resp));
    }

    /** Get children of an item (e.g. episodes of a season, media of a folder). */
    public List<MediaItem> getChildren(String parentId) throws Exception {
        return getItems(parentId);
    }

    private List<MediaItem> parseItems(JSONObject root) throws Exception {
        List<MediaItem> list = new ArrayList<MediaItem>();
        JSONArray items = root.optJSONArray("Items");
        if (items == null) return list;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            MediaItem m = new MediaItem(
                o.optString("Id"),
                o.optString("Name", "(无标题)"),
                o.optString("Type", "Unknown")
            );
            m.setOverview(o.optString("Overview", ""));
            m.setRuntimeTicks(o.optLong("RunTimeTicks", 0));
            m.setMediaType(o.optString("MediaType", ""));
            // ImageTags is a JSONObject like {"Primary": "…", "Thumb": "…"}.
            // Check if it has a "Primary" key — the safe way is via optJSONObject.
            if (o.has("ImageTags")) {
                org.json.JSONObject tags = o.optJSONObject("ImageTags");
                if (tags != null && tags.has("Primary")) {
                    m.setImageUrl(getPrimaryImageUrl(o.optString("Id")));
                }
            }
            list.add(m);
        }
        return list;
    }

    public String getPrimaryImageUrl(String itemId) {
        return serverUrl + "/Items/" + itemId + "/Images/Primary?enableImageEnhancers=false&maxWidth=400";
    }

    /** Build a direct stream URL for an item (original quality). */
    public String getStreamUrl(String itemId) {
        return serverUrl + "/Videos/" + itemId + "/stream?static=true&api_key=" + token;
    }

    /**
     * Query the default subtitle stream index for an item.
     * Returns -1 if no subtitle track is found or marked as default.
     */
    private int getDefaultSubtitleStreamIndex(String itemId) {
        try {
            String resp = http("GET", "/Users/" + userId + "/Items/" + itemId
                + "?Fields=MediaSources", null, true);
            JSONObject item = new JSONObject(resp);
            JSONArray sources = item.optJSONArray("MediaSources");
            if (sources != null && sources.length() > 0) {
                JSONArray streams = sources.getJSONObject(0).optJSONArray("MediaStreams");
                if (streams != null) {
                    for (int i = 0; i < streams.length(); i++) {
                        JSONObject s = streams.getJSONObject(i);
                        if ("Subtitle".equals(s.optString("Type"))
                            && s.optBoolean("IsDefault", false)) {
                            return s.optInt("Index", -1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Cannot query subtitles, proceed without them
        }
        return -1;
    }

    /**
     * Build a transcoded stream URL at a given target resolution/bitrate.
     *
     * NOTE: Uses Container=ts (MPEG-TS) instead of mp4 because Jellyfin's
     * live transcode with Container=mp4 outputs fragmented MP4 (fMP4) which
     * Android 4's MediaPlayer cannot parse (it buffers forever). MPEG-TS is
     * a continuous stream format natively supported by Android 4's MediaPlayer.
     *
     * However, Android 4's MediaPlayer also fails to play progressive MPEG-TS
     * via HTTP (error 1/-1004). These URLs are STILL USED by the proxy's
     * /stream route (for backward compatibility) but the main player now uses
     * HLS (getHlsPlaylistUrl) for fluid and medium modes.
     *
     * Also queries the default subtitle stream index and appends it to the
     * transcode URL so that subtitles are burned into the video.
     */
    private String buildTranscodeUrl(String itemId, int maxWidth, int maxHeight, int maxBitRate) {
        // NOTE: do NOT use static=true here — it forces direct streaming of the
        // original file and ignores all transcode params.
        String url = serverUrl + "/Videos/" + itemId + "/stream?api_key=" + token
            + "&VideoCodec=h264&AudioCodec=aac"
            + "&MaxWidth=" + maxWidth + "&MaxHeight=" + maxHeight
            + "&MaxBitRate=" + maxBitRate
            + "&Level=-1&Cabac=true&SubtitleMethod=Encode"
            + "&Container=ts";
        // Query default subtitle track and append to URL
        int subIdx = getDefaultSubtitleStreamIndex(itemId);
        if (subIdx >= 0) {
            url += "&SubtitleStreamIndex=" + subIdx;
        }
        return url;
    }

    /** Fluid mode: 480p transcode, low bitrate (2 Mbps). */
    public String getFluidStreamUrl(String itemId) {
        return buildTranscodeUrl(itemId, 854, 480, 2000000);
    }

    /** Medium mode: 720p transcode, medium bitrate (4 Mbps). */
    public String getMediumStreamUrl(String itemId) {
        return buildTranscodeUrl(itemId, 1280, 720, 4000000);
    }

    // ===== HLS (HTTP Live Streaming) =====
    //
    // Android 4's MediaPlayer supports HLS natively via the
    // application/vnd.apple.mpegurl MIME type. Jellyfin's /main.m3u8
    // endpoint returns a VOD playlist with 3-second TS segments.
    //
    // This avoids both the fragmented MP4 issue (fMP4 unparseable on
    // Android 4) and the progressive MPEG-TS issue (error 1/-1004).
    // The proxy rewrites the playlist URLs so all segments go through
    // the local proxy (for HTTPS compatibility).

    /**
     * Build the shared HLS transcode parameters for a given quality setting.
     * Returns the query-string portion (including the leading "&").
     */
    private String buildHlsTranscodeParams(int maxWidth, int maxHeight, int maxBitRate) {
        String params = "&VideoCodec=h264&AudioCodec=aac"
            + "&MaxWidth=" + maxWidth + "&MaxHeight=" + maxHeight
            + "&MaxBitRate=" + maxBitRate
            + "&Container=ts&SubtitleMethod=Encode&RequireAvc=true";
        return params;
    }

    /**
     * Build the HLS playlist URL (main.m3u8) for a given quality.
     * The proxy will fetch this, rewrite the segment URLs, and serve it.
     */
    public String getHlsPlaylistUrl(String itemId, String quality) {
        int maxWidth, maxHeight, maxBitRate;
        if ("medium".equals(quality)) {
            maxWidth = 1280; maxHeight = 720; maxBitRate = 4000000;
        } else {
            maxWidth = 854; maxHeight = 480; maxBitRate = 2000000;
        }

        String url = serverUrl + "/Videos/" + itemId + "/main.m3u8?api_key=" + token
            + buildHlsTranscodeParams(maxWidth, maxHeight, maxBitRate);

        // Query default subtitle track and append to URL
        int subIdx = getDefaultSubtitleStreamIndex(itemId);
        if (subIdx >= 0) {
            url += "&SubtitleStreamIndex=" + subIdx;
        }
        return url;
    }
}