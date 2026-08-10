package com.jellyfin.tvbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {

    private JellyfinClient client;
    private List<MediaItem> libraries = new ArrayList<MediaItem>();
    private List<MediaItem> items = new ArrayList<MediaItem>();
    private String currentParentId;
    private String currentParentName;

    private LinearLayout libraryContainer;
    private TextView libraryTitle;
    private GridView mediaGrid;
    private TextView emptyText;
    private ProgressBar loadingBar;
    private TextView headerTitle;
    private MediaAdapter adapter;
    private ImageCache imageCache = new ImageCache();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        client = AppState.getClient(this);
        if (client == null || client.getUserId() == null) {
            // not logged in, go back to login
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        headerTitle = (TextView) findViewById(R.id.headerTitle);
        libraryContainer = (LinearLayout) findViewById(R.id.libraryContainer);
        libraryTitle = (TextView) findViewById(R.id.libraryTitle);
        mediaGrid = (GridView) findViewById(R.id.mediaGrid);
        emptyText = (TextView) findViewById(R.id.emptyText);
        loadingBar = (ProgressBar) findViewById(R.id.loadingBar);

        Button logout = (Button) findViewById(R.id.logoutButton);
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppState.clear();
                startActivity(new Intent(MainActivity.this, LoginActivity.class));
                finish();
            }
        });

        adapter = new MediaAdapter();
        mediaGrid.setAdapter(adapter);
        mediaGrid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onItemSelected((MediaItem) parent.getItemAtPosition(position));
            }
        });

        loadLibraries();
    }

    private void loadLibraries() {
        setLoading(true);
        new AsyncTask<Void, Void, List<MediaItem>>() {
            @Override
            protected List<MediaItem> doInBackground(Void... p) {
                try {
                    return client.getLibraries();
                } catch (Exception e) {
                    return null;
                }
            }
            @Override
            protected void onPostExecute(List<MediaItem> libs) {
                setLoading(false);
                if (libs == null) {
                    emptyText.setText("无法加载媒体库");
                    emptyText.setVisibility(View.VISIBLE);
                    return;
                }
                libraries = libs;
                renderLibraries();
                if (!libs.isEmpty()) {
                    // auto-open first library
                    openLibrary(libs.get(0));
                }
            }
        }.execute();
    }

    private void renderLibraries() {
        libraryContainer.removeAllViews();
        for (final MediaItem lib : libraries) {
            Button b = new Button(this);
            b.setText(lib.getName());
            b.setTextSize(16);
            b.setTextColor(0xFFFFFFFF);
            b.setBackgroundResource(R.drawable.btn_primary);
            b.setPadding(24, 12, 24, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 16;
            b.setLayoutParams(lp);
            b.setFocusable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openLibrary(lib);
                }
            });
            libraryContainer.addView(b);
        }
    }

    private void openLibrary(MediaItem lib) {
        // If it's a folder/library, load its items
        currentParentId = lib.getId();
        currentParentName = lib.getName();
        headerTitle.setText(lib.getName());
        libraryTitle.setText(lib.getName());
        // Libraries and top-level folders: sort by name
        loadItems(lib.getId(), "SortName");
    }

    private void loadItems(String parentId, String sortBy) {
        setLoading(true);
        emptyText.setVisibility(View.GONE);
        final String pid = parentId;
        final String sort = sortBy;
        new AsyncTask<Void, Void, List<MediaItem>>() {
            @Override
            protected List<MediaItem> doInBackground(Void... p) {
                try {
                    return client.getItems(pid, sort);
                } catch (Exception e) {
                    return null;
                }
            }
            @Override
            protected void onPostExecute(List<MediaItem> result) {
                setLoading(false);
                if (result == null) {
                    emptyText.setText("加载失败，请检查网络");
                    emptyText.setVisibility(View.VISIBLE);
                    return;
                }
                items = result;
                adapter.notifyDataSetChanged();
                if (items.isEmpty()) {
                    emptyText.setVisibility(View.VISIBLE);
                }
            }
        }.execute();
    }

    private void onItemSelected(MediaItem m) {
        String type = m.getType();
        if ("Folder".equals(type) || "CollectionFolder".equals(type) ||
            "Series".equals(type) || "Season".equals(type) ||
            "BoxSet".equals(type)) {
            // navigate into it
            currentParentId = m.getId();
            currentParentName = m.getName();
            headerTitle.setText(m.getName());
            libraryTitle.setText(m.getName());
            // A season contains episodes — sort by episode index, not name,
            // so S01E02 comes before S01E10 (alphabetical order would put
            // S01E10 before S01E2).
            String sortBy = "Season".equals(type) ? "IndexNumber,ParentIndexNumber" : "SortName";
            loadItems(m.getId(), sortBy);
        } else if ("Movie".equals(type) || "Episode".equals(type) ||
                   "Video".equals(type)) {
            // play it
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("itemId", m.getId());
            intent.putExtra("title", m.getName());
            startActivity(intent);
        } else {
            // unknown type, try playing if it's a video
            if ("Video".equals(m.getMediaType())) {
                Intent intent = new Intent(this, PlayerActivity.class);
                intent.putExtra("itemId", m.getId());
                intent.putExtra("title", m.getName());
                startActivity(intent);
            }
        }
    }

    private void setLoading(boolean loading) {
        loadingBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Drop cached bitmaps so the Activity can be GC'd promptly.
        imageCache.clear();
    }

    @Override
    public void onBackPressed() {
        // If we're nested in a folder, go back to top-level libraries
        // NOTE: libraries may be empty if loading failed — guard against NPE
        if (!libraries.isEmpty()
            && currentParentId != null
            && !currentParentId.equals(libraries.get(0).getId())) {
            headerTitle.setText("Jellyfin TV");
            libraryTitle.setText("媒体库");
            items.clear();
            adapter.notifyDataSetChanged();
            openLibrary(libraries.get(0));
        } else {
            super.onBackPressed();
        }
    }

    private class MediaAdapter extends BaseAdapter {
        @Override
        public int getCount() { return items.size(); }
        @Override
        public Object getItem(int pos) { return items.get(pos); }
        @Override
        public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            View v;
            if (convertView == null) {
                v = getLayoutInflater().inflate(R.layout.item_media, parent, false);
            } else {
                v = convertView;
            }
            MediaItem m = items.get(pos);
            TextView title = (TextView) v.findViewById(R.id.itemTitle);
            final ImageView image = (ImageView) v.findViewById(R.id.itemImage);
            title.setText(m.getName());

            image.setImageResource(android.R.color.transparent);
            if (m.getImageUrl() != null) {
                Bitmap bm = imageCache.get(m.getId());
                if (bm != null) {
                    image.setImageBitmap(bm);
                } else {
                    loadImage(m.getId(), m.getImageUrl(), image);
                }
            } else {
                image.setImageBitmap(null);
            }
            return v;
        }
    }

    /**
     * Load an image asynchronously into the given ImageView.
     *
     * - Cancels any previous load in flight for the same ImageView (tag-based),
     *   so fast scrolling can't pile up stale tasks or download the same image
     *   twice.
     * - Holds only a WeakReference to the ImageView, so a recycled/scrolled-away
     *   view is not written to (avoids cross-images) and the task doesn't leak
     *   the Activity.
     */
    private void loadImage(final String id, final String url, ImageView image) {
        // Cancel any task already bound to this view
        Object tag = image.getTag();
        if (tag instanceof AsyncTask) {
            ((AsyncTask) tag).cancel(true);
        }

        final ImageTask task = new ImageTask(id, image);
        image.setTag(task);
        task.execute(url);
    }

    /** AsyncTask that decodes an image and applies it to a WeakReference'd ImageView. */
    private class ImageTask extends AsyncTask<String, Void, Bitmap> {
        private final String id;
        private final java.lang.ref.WeakReference<ImageView> imageRef;

        ImageTask(String id, ImageView image) {
            this.id = id;
            this.imageRef = new java.lang.ref.WeakReference<ImageView>(image);
        }

        @Override
        protected Bitmap doInBackground(String... u) {
            if (isCancelled()) return null;
            try {
                URL uu = new URL(u[0]);
                HttpURLConnection conn = (HttpURLConnection) uu.openConnection();
                try {
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + client.getToken() + "\"");
                    InputStream is = conn.getInputStream();
                    Bitmap bm = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bm != null) imageCache.put(id, bm);
                    return bm;
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bm) {
            if (bm == null || isCancelled()) return;
            ImageView image = imageRef.get();
            // Only apply if this task is still the one bound to the view
            if (image != null && image.getTag() == this) {
                image.setImageBitmap(bm);
            }
        }
    }

    /**
     * Bounded image cache. Uses SoftReferences so the GC can reclaim bitmaps
     * under memory pressure, but caps the number of entries so very large
     * libraries don't hold unbounded totals.
     */
    private static class ImageCache {
        private static final int MAX_ENTRIES = 120;
        private final HashMap<String, SoftReference<Bitmap>> map = new HashMap<String, SoftReference<Bitmap>>();
        private final java.util.LinkedList<String> order = new java.util.LinkedList<String>();

        public Bitmap get(String k) {
            SoftReference<Bitmap> r = map.get(k);
            return r == null ? null : r.get();
        }

        public void put(String k, Bitmap b) {
            if (map.containsKey(k)) {
                map.put(k, new SoftReference<Bitmap>(b));
                order.remove(k);
                order.addFirst(k);
                return;
            }
            // Evict oldest entry once we exceed the cap
            while (map.size() >= MAX_ENTRIES && !order.isEmpty()) {
                String oldest = order.removeLast();
                map.remove(oldest);
            }
            map.put(k, new SoftReference<Bitmap>(b));
            order.addFirst(k);
        }

        /** Drop all cached bitmaps (called from onDestroy). */
        public void clear() {
            map.clear();
            order.clear();
        }
    }
}