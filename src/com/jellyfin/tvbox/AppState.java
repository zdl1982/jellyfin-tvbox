package com.jellyfin.tvbox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Simple singleton to share the JellyfinClient across activities.
 */
public class AppState {
    private static JellyfinClient client;

    public static void setClient(Context ctx, JellyfinClient c) {
        client = c;
        // persist server URL
        if (c != null) {
            ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
               .edit().putString("server", c.getServerUrl()).commit();
        }
    }

    public static JellyfinClient getClient(Context ctx) {
        if (client == null) {
            String server = ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
                               .getString("server", null);
            if (server != null) {
                client = new JellyfinClient(server);
            }
        }
        return client;
    }

    public static void clear() {
        client = null;
    }
}