package tv.danmaku.ijk.media.player;

public interface IEncodeDataProvider {
    byte[] getYuvData();
    byte[] getSampleData();
}
