package tv.danmaku.ijk.media.player;

public final class AVRecordCache {
    private final byte[] mSampByteArr;
    private final byte[] mPictByteArr;
    private final int    mSampBuffSize;
    private final int    mPictBuffSize;
    private       int    mSampDataSize;
    private       int    mPictDataSize;
    private final int    mSampFrameSize;
    private final int    mPictFrameSize;

    /* AVRecordCache 的功能:
     * 存放 audio & video cache 的设置及数据
     * 设置包括:
     * 1. 单个 samp frame 的结构: 4字节 pts + 2048 字节的 sample data
     * 2. 单个 pict frame 的结构: 4字节 pts + w*h*1.5 字节的 dataYUV(YV12格式)
     * 数据包括:
     * 1. samp frames: SN 个 samp frames 连续存放在 mSampByteArr 数组中
     * 2. pict frames: PN 个 pict frames 连续存放在 mPictByteArr 数组中
     */
    public AVRecordCache(int sampFrameSize, int pictFrameSize, int aFPS, int vFPS, int duration) {
        int sampFrameNum = (int)(aFPS * duration * 1.5f); // 1.5倍: 多出 50% 作为安全空间
        int pictFrameNum = (int)(vFPS * duration * 1.1f); // 1.1倍: 多出 10% 作为安全空间
        mSampBuffSize    = sampFrameSize * sampFrameNum;
        mPictBuffSize    = pictFrameSize * pictFrameNum;
        mSampByteArr     = new byte[mSampBuffSize];
        mPictByteArr     = new byte[mPictBuffSize];
        mSampFrameSize   = sampFrameSize;
        mPictFrameSize   = pictFrameSize;
    }

    public int getPictFrameSize() {
        return mPictFrameSize;
    }
    public byte[] sampArray() {
        return mSampByteArr;
    }

    public int getSampBuffSize() {
        return mSampBuffSize;
    }

    public void setSampDataSize(int size) {
        mSampDataSize = size;
    }

    public int getSampDataSize() {
        return mSampDataSize;
    }

    public byte[] pictArray() {
        return mPictByteArr;
    }

    public int getPictBuffSize() {
        return mPictBuffSize;
    }

    public void setPictDataSize(int size) {
        mPictDataSize = size;
    }

    public int getPictDataSize() {
        return mPictDataSize;
    }
}
