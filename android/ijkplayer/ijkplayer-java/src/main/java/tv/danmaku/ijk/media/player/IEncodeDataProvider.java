package tv.danmaku.ijk.media.player;

public interface IEncodeDataProvider {
    public static enum AVCacheType {
        kAudioCache,
        kVideoCache
    };
    AVRecordCache getAVCache(AVCacheType type);
}
