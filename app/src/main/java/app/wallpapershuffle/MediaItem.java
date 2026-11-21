package app.wallpapershuffle;

import java.io.File;

public final class MediaItem {
    public long id, added, modified, taken, size, position;
    public String name, mime, path;
    public int width, height, screens;
    public boolean video() { return mime != null && mime.startsWith("video/"); }
    public boolean animated() { return "image/gif".equals(mime) || "image/webp".equals(mime); }
    public File file() { return new File(path); }
    /** Bit mask: 1 = home screen, 2 = lock screen, 3 = both, 0 = hidden (kept in library, not shown). */
    public static final int HIDDEN = 0, HOME = 1, LOCK = 2, BOTH = 3;
    public boolean shown() { return screens != HIDDEN; }
}
