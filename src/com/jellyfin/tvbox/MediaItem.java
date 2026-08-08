package com.jellyfin.tvbox;

import java.io.Serializable;

public class MediaItem implements Serializable {
    private String id;
    private String name;
    private String type;
    private String imageUrl;
    private String overview;
    private long runtimeTicks;
    private String mediaType;

    public MediaItem(String id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getImageUrl() { return imageUrl; }
    public String getOverview() { return overview; }
    public long getRuntimeTicks() { return runtimeTicks; }
    public String getMediaType() { return mediaType; }

    public void setImageUrl(String url) { this.imageUrl = url; }
    public void setOverview(String o) { this.overview = o; }
    public void setRuntimeTicks(long t) { this.runtimeTicks = t; }
    public void setMediaType(String m) { this.mediaType = m; }
}