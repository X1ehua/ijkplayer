package tv.danmaku.ijk.media.player;

public interface IEncodeDataProvider {
    public static enum AVCacheType {
        kAudioCache,
        kVideoCache
    };
    void updateCache(AVCacheType type);
    AVRecordCache getAVCache();
}
