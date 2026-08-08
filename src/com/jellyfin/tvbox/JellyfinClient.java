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
    private static final String DEVICE_ID = "jellyfin-tvbox-android";
    private static final String VERSION = "1.0.0";

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
        // strip trailing slash
        this.serverUrl = serverUrl;
        while (this.serverUrl.endsWith("/")) {
            this.serverUrl = this.serverUrl.substring(0, this.serverUrl.length() - 1);
        }
    }

    public String getToken() { return token; }
    public String getUserId() { return userId; }
    public String getServerUrl() { return serverUrl; }

    private String authHeader() {
        return "MediaBrowser Client=\"" + CLIENT + "\", Device=\"" + DEVICE +
               "\", DeviceId=\"" + DEVICE_ID + "\", Version=\"" + VERSION + "\"";
    }

    private String http(String method, String path, String body, boolean withToken) throws Exception {
        URL url = new URL(serverUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
        conn.disconnect();
        if (code >= 400) {
            throw new HttpException(code, sb.toString());
        }
        return sb.toString();
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
        String path = "/Users/" + userId + "/Items?ParentId=" + parentId +
                      "&Fields=Overview,PrimaryImageAspectRatio&Recursive=false&SortBy=SortName";
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
            if (o.optString("ImageTags").contains("Primary") ||
                o.has("ImageTags") && o.getJSONObject("ImageTags").has("Primary")) {
                m.setImageUrl(getPrimaryImageUrl(o.optString("Id")));
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
     * Build a transcoded stream URL with lower bitrate / resolution.
     * Use this when the network is slow and the user wants smooth playback.
     */
    public String getTranscodedStreamUrl(String itemId) {
        return serverUrl + "/Videos/" + itemId + "/stream?static=true&api_key=" + token
            + "&VideoCodec=h264&AudioCodec=aac"
            + "&MaxWidth=854&MaxHeight=480&MaxBitRate=2000000"
            + "&Level=-1&Cabac=true&SubtitleMethod=Encode";
    }
}