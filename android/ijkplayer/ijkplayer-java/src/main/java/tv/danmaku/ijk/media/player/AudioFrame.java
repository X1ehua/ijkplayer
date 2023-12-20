package tv.danmaku.ijk.media.player;

public final class AudioSampleData {
    private final byte[] mByteArr;
    private final int    mArraySize;
    private       int    mDataSize;

    public AudioSampleData(int length) {
        mArraySize = length;
        mByteArr = new byte[mArraySize];
    }

    public byte[] array() {
        return mByteArr;
    }

    public int getArraySize() {
        return mArraySize;
    }

    public void setDataSize(int size) {
        mDataSize = size;
    }

    public int getDataSize() {
        return mDataSize;
    }
}
