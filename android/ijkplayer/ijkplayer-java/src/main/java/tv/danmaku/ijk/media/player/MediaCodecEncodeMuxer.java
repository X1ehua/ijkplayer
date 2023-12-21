package tv.danmaku.ijk.media.player;

import static tv.danmaku.ijk.media.player.IjkMediaPlayer.SAMP_FRAME_SIZE;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

public class MediaCodecEncodeMuxer implements Runnable {
    private final static String TAG = "EncMuxer";
    private static final boolean VERBOSE = false;

    private static final int FRAME_RATE      = 24; // 24 FPS
    private static final int IFRAME_INTERVAL = 5;  // 2 seconds between I-frames

    private final int mWidth;
    private final int mHeight;
    private final int mBitRate;

    private MediaMuxer mMuxer;
    private boolean    mMuxerStarted;

    private final static int kVideo = 0;
    private final static int kAudio = 1;

    private final int[]                   mAVTrackIndices = new int[2];
    private final MediaCodec[]            mAVEncoders     = new MediaCodec[2];
    private final MediaCodec.BufferInfo[] mAVBufferInfos  = new MediaCodec.BufferInfo[2];
    private final IEncodeDataProvider     mEncodeDataProvider;

    private final static long DEQUEUE_TIMEOUT_US = 1000L; // 1ms
    private static boolean sThreadRunning  = false;

//  private long mFrameIndex     = 0; // video 与 audio 共用 pts 以保持音画同步
//  private long mAudioStartTime = -1;
    private int mEndPts = 0;

    private static final String OUTPUT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
                                            + "/DCIM/cc/t2.m4a";

    public MediaCodecEncodeMuxer(IEncodeDataProvider provider, int width, int height) {
        mEncodeDataProvider = provider;
        mWidth   = width;
        mHeight  = height;
        mBitRate = mWidth * mHeight * 4;
    }

    @SuppressWarnings("deprecation") // 此处必须使用 COLOR_FormatYUV420SemiPlanar
    private void prepareEncoder() throws Exception {
        mAVBufferInfos[kVideo] = new MediaCodec.BufferInfo();
        mAVBufferInfos[kAudio] = new MediaCodec.BufferInfo();

        // Set some props. Failing to specify some of these can cause MediaCodec configure() throw unhelpful exception
        MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);

        /* COLOR_FormatYUV420SemiPlanar: Y plane + UV plane(UVUVUV), 此处须忽略 deprecation，指定此 format */
        /* 另经测试，COLOR_FormatYUV411Planar 在 HUAWEI P30 上面不被支持 */
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420SemiPlanar);

        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "videoFormat: " + videoFormat);

        // TODO: 在 mp4 开始播放后，动态传入当前的 channel-count
        MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, /* channel-count */ 2);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
    //  audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMP_FRAME_SIZE * 10); // 2048*8 = 16384
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max_buffSize);

        mAVEncoders[kVideo] = MediaCodec.createEncoderByType("video/avc");
        mAVEncoders[kAudio] = MediaCodec.createEncoderByType("audio/mp4a-latm"); // MediaCodec.MIMETYPE_AUDIO_AAC
        mAVEncoders[kVideo].configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAVEncoders[kAudio].configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAVEncoders[kVideo].start();
        mAVEncoders[kAudio].start();

        try {
            mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        catch (FileNotFoundException e) {
            Log.w(TAG, e.toString());
            throw new RuntimeException("new MediaMuxer() failed");
        }

        mAVTrackIndices[kVideo] = -1;
        mAVTrackIndices[kAudio] = -1;
        mMuxerStarted = false;
    }

    public void startThread() {
        Thread thread = new Thread(this);
        thread.setName("EncodeMuxer");
        thread.start();
    }

    public void stopThread() {
        sThreadRunning = false;
        Log.w(TAG, ">> stopThread()");
    }

    @Override //@SuppressWarnings("deprecation")
    public void run() {
        try {
            long t0 = System.nanoTime();
            prepareEncoder(); // 耗时较长，放在主线程会导致阻塞/卡顿
            Log.d(TAG, ">> prepareEncoder() time cost: " + (System.nanoTime()-t0)/1000000 + "ms");
        }
        catch(Exception e) {
            Log.e(TAG, ">> prepareEncoder() failed:");
            e.printStackTrace();
            return;
        }

        sThreadRunning = true;
        long startTime = System.nanoTime();

        final boolean[] videoThreadRunning = {true};
        final boolean[] audioThreadRunning = {true};

        Thread videoThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sThreadRunning) {
                    doVideoFrame();
                    sThreadRunning = false; // 读取 cache
                }
                videoThreadRunning[0] = false;
            }
        });
        videoThread.setName("VideoEncoder");
        // videoThread.start();

        Thread audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sThreadRunning) {
                    try {
                        doAudioFrame();
                        sThreadRunning = false; // 读取 cache
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                audioThreadRunning[0] = false;
            }
        });
        audioThread.setName("AudioEncoder");
        audioThread.start();

        do {
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        // while (videoThreadRunning[0] || audioThreadRunning[0]);
        while (audioThreadRunning[0]);
        // while (videoThreadRunning[0]);
        /* 确保 videoThread & audioThread 均已结束后，再写入 EOS: */

        try {
            // drainEncoder(kVideo, true);
            drainEncoder(kAudio, true);
        }
        catch (Exception e) {
            Log.w(TAG, ">> drainEncoder(true) failed: " + e.toString());
        }

        releaseEncoder();
        float dt = (System.nanoTime()-startTime)/1000000000.0f;
        Log.i(TAG, String.format(">> releaseEncoder(), duration %.2fs, cost %.2fs", mEndPts/1000000.0f, dt));
        try {
            Thread.sleep(1000);
            throw new RuntimeException("exit");
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void doVideoFrame() {
        vfc++;
        long t0 = System.nanoTime();
        AVRecordCache avCache = mEncodeDataProvider.getAVCache(IEncodeDataProvider.AVCacheType.kVideoCache);
        byte[]        pictBuff = avCache.pictArray();
        int           dataSize = avCache.getPictDataSize();

        long t1 = System.nanoTime();
        /*
        if (dataYUV == null || dataYUV.length == 0) {
            try {
                Thread.sleep(50);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }
        */

        int frameSize = avCache.getPictFrameSize();
        int frameNum = dataSize / frameSize;

        byte[] dataYUV = new byte[frameSize];
        for (int i=0; i<frameNum; ++i) {
            // TODO: java 能否定义一个 ByteBuffer 之类的，直接指向 avCache.pictArray() + offset 的位置
            System.arraycopy(avCache.pictArray(), frameSize * i, dataYUV, 0, frameSize);
            int pts = dataYUV[0]&0xff | (dataYUV[1]&0xff)<<8 | (dataYUV[2]&0xff)<<16 | (dataYUV[3]&0xff)<<24;
            pts -= mAVPtsOrigin;
            mEndPts = pts;

            int inputBufferIndex;
            //long pts = 0; // video 与 audio 共用 mAVPts 以保持音画同步
            if ((inputBufferIndex = mAVEncoders[kVideo].dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                //pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
                ByteBuffer inputBuffer = mAVEncoders[kVideo].getInputBuffer(inputBufferIndex);
                inputBuffer.put(dataYUV);
                mAVEncoders[kVideo].queueInputBuffer(inputBufferIndex, 4, dataYUV.length, pts, 0);
                //mFrameIndex += 1;
            } else {
                if (VERBOSE) Log.d(TAG, "videoEncoder's inputBuffer is not available");
            }

            drainEncoder(kVideo, false);
        }
        long t2 = System.nanoTime();

        if (VERBOSE) {
            @SuppressLint("DefaultLocale") String msg = String.format(">> doVideoFrame#%d: dt %d %d %d += %d",
                    vfc, (t1-t0)/1000000, (t2-t1)/1000000, (t2-t0)/1000000);
            Log.w(TAG, msg);
        }
    }

    int vfc = 0; // debug
    int afc = 0;
    int tbc = 0; // total buff-count
    long lastPts = 0;

    int max_buffSize = (SAMP_FRAME_SIZE + 4) * (96*3/2 * 5); // 1.5倍
    byte[] sampBuff = new byte[max_buffSize];
    int mAVPtsOrigin = -1;

    private void doAudioFrame() throws InterruptedException {
        afc++;

        // <!--
        long t0 = System.nanoTime();

        AVRecordCache avCache  = mEncodeDataProvider.getAVCache(IEncodeDataProvider.AVCacheType.kAudioCache);
        byte[]        sampBuff = avCache.sampArray();
        int           dataSize = avCache.getSampDataSize();

        // int dataSize = mEncodeDataProvider.native_copyAudioData(sampBuff, max_buffSize);
        int frameNum = dataSize/(SAMP_FRAME_SIZE + 4);

        StringBuilder sbc = new StringBuilder();
        // StringBuilder sbt = new StringBuilder();
        // for (int i=0; i<frame_num; i++) {
        //     int pts = sampBuff[offset]&0xff | (sampBuff[offset+1]&0xff)<<8 | (sampBuff[offset+2]&0xff)<<16 | (sampBuff[offset+3]&0xff)<<24;
        //     int bc  = sampBuff[offset+4]&0xff | (sampBuff[offset+5]&0xff)<<8;
        //     offset += SAMP_FRAME_SIZE + 4;
        //     sbc.append(bc);
        //     sbc.append(' ');
        //     sbt.append(pts);
        //     sbt.append(' ');
        // }

        long t1 = System.nanoTime();

        // if (audioDataQueue == null || dataSize <= 0) {
        if (dataSize <= 0) {
            try {
                Thread.sleep(5);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        // if (dataSize % SAMP_FRAME_SIZE != 0) {
        //     @SuppressLint("DefaultLocale")
        //     String msg = String.format("audio dataSize %d %% %d != 0", dataSize, SAMP_FRAME_SIZE);
        //     throw new RuntimeException(msg);
        // }

        //String msg = ">> buff-counter #3: ";

        // if (mAudioStartTime == -1) {
        //     mAudioStartTime = System.nanoTime();
        // }

        int offset = 0;

        // for (int i = 0; i < dataSize / SAMP_FRAME_SIZE; i++) {
        for (int i = 0; i < frameNum; i++, offset += (SAMP_FRAME_SIZE + 4) ) {
            int pts = sampBuff[offset]&0xff | (sampBuff[offset+1]&0xff)<<8 | (sampBuff[offset+2]&0xff)<<16 | (sampBuff[offset+3]&0xff)<<24;
            if (mAVPtsOrigin == -1) {
                mAVPtsOrigin = pts;
            }

            int bc = sampBuff[offset+4]&0xff | (sampBuff[offset+5]&0xff)<<8;
            sbc.append(bc);
            sbc.append(' ');

            //byte[] dataAudio = new byte[SAMP_FRAME_SIZE];
            //System.arraycopy(audioDataQueue, i * SAMP_FRAME_SIZE, dataAudio, 0, SAMP_FRAME_SIZE);
            ///Log.d(TAG, ">> buff-counter: " + ((dataAudio[0] & 0xff) | ((dataAudio[1] & 0xff) << 8)));
            //msg += ((dataAudio[0] & 0xff) | ((dataAudio[1] & 0xff) << 8)) + " ";
            tbc++;

            // -->
            //byte[] dataAudio = sAudioSampleQueue.take(); // c 代码中通过 jni 往 java 侧 offer 的方式

            // mAVPts = (System.nanoTime() - mAudioStartTime) / 1000;
            pts -= mAVPtsOrigin;
            mEndPts = pts;

            //long dt = pts - lastPts; // TBC: dts 分布不均匀，解码时已是如此?
            //Log.i(TAG, String.format(">> dts %.2f", dts / 1000.0f));
            //lastPts = pts;
            //Log.i(TAG, ">> audio pts " + mAVPts / 1000);

            int inputBufferIndex;
            if ((inputBufferIndex = mAVEncoders[kAudio].dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                ByteBuffer inputBuffer = mAVEncoders[kAudio].getInputBuffer(inputBufferIndex);
                // inputBuffer.put(audioDataQueue);
                // mAVEncoders[kAudio].queueInputBuffer(inputBufferIndex, i * SAMP_FRAME_SIZE, SAMP_FRAME_SIZE, mAVPts, 0);
                inputBuffer.put(sampBuff);
                mAVEncoders[kAudio].queueInputBuffer(inputBufferIndex, offset+4, SAMP_FRAME_SIZE, pts, 0);
            } else {
                if (VERBOSE) Log.d(TAG, "audioEncoder's inputBuffer is not available");
            }
            //offeredSize += SAMP_FRAME_SIZE;
            //if (++oc % 10 == 0) Log.w(TAG, ">> offeredSize " + offeredSize);
            //Log.d(TAG, String.format(">> doAudioFrame#%d: pts %d ms", afc, pts/1000));

            // 将 encoder 编码产出的 encodedData 取出，交由 muxer 写至 out file
            drainEncoder(kAudio, false);
        }
        //Log.d(TAG, msg + ", tbc " + tbc); // buff-counter: ...
        Log.i(TAG, sbc.toString());
    }

    int writtenSize = 0; // debug
    int offeredSize = 0;
    int oc = 0;

    /**
     * Extracts all pending data from the encoder.
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    void drainEncoder(int videoOrAudio, boolean endOfStream)
    {
        MediaCodec encoder = mAVEncoders[videoOrAudio];
        MediaCodec.BufferInfo bufferInfo = mAVBufferInfos[videoOrAudio];
        String encoderName = videoOrAudio == kAudio ? "audio" : "video";

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to " + encoderName + "Encoder");
            inputEndOfStream(encoder);
        }

        int wc = 0; // debug
        while (true) {
            wc ++;
            int statusOrIndex = encoder.dequeueOutputBuffer(bufferInfo, 100);

            if (statusOrIndex == MediaCodec.INFO_TRY_AGAIN_LATER) { // no output available yet
                if (!endOfStream) {
                    break; // out of while
                }
                else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            }
            else if (statusOrIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted)
                    throw new RuntimeException("MediaCodec.output_format changed twice");

                // now that we have the Magic Goodies, start the muxer
                MediaFormat format = encoder.getOutputFormat();
                mAVTrackIndices[videoOrAudio] = mMuxer.addTrack(format);

                if (!mMuxerStarted && mAVTrackIndices[kAudio] >= 0) {
                // if (!mMuxerStarted && mAVTrackIndices[kVideo] >= 0) {
                // if (!mMuxerStarted && mAVTrackIndices[kVideo] >= 0 && mAVTrackIndices[kAudio] >= 0) {
                    mMuxer.start();
                    mMuxerStarted = true;
                    Log.e(TAG, ">> Muxer started, videoTrack: " + mAVTrackIndices[kVideo]
                                             + ", audioTrack: " + mAVTrackIndices[kAudio]);
                }
            }
            else if (statusOrIndex < 0) { // let's ignore it
                Log.w(TAG, ">> " + encoderName + "Encoder.dequeueOutputBuffer() bad statusOrIndex: " + statusOrIndex);
            }
            else {
                ByteBuffer encodedData = encoder.getOutputBuffer(statusOrIndex);
                if (encodedData == null)
                    throw new RuntimeException("encoder.getOutputBuffer(" + statusOrIndex + ") got null");

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0 && mMuxerStarted) {
                    //if (!mMuxerStarted)
                    //  throw new RuntimeException("muxer hasn't started");

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);

                    // 1. 平均每秒 35.3 次  2. PCM -> AAC 的压缩比为 18.3% [written_size / offered_size]
                    mMuxer.writeSampleData(mAVTrackIndices[videoOrAudio], encodedData, bufferInfo);
                    writtenSize += bufferInfo.size; // debug
                    if (VERBOSE) {
                        Log.i(TAG, String.format(">> %s.encodedData(size %d) sent to muxer, wc#%d, writtenSize %d",
                              encoderName, bufferInfo.size, wc, writtenSize));
                    }
                }

                encoder.releaseOutputBuffer(statusOrIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream)
                        Log.w(TAG, "reached end of stream unexpectedly");
                    else if (VERBOSE)
                        Log.d(TAG, "end of stream reached");
                    Log.d(TAG, "break #102 drainEncoder " + encoderName);
                    break; // out of while
                }
            }
        }
    }

    void inputEndOfStream(MediaCodec encoder) {
        int inputBufferIndex = encoder.dequeueInputBuffer(10000); // 10ms
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer == null) {
                throw new RuntimeException("getInputBuffer() returned null in drainEncoder(EOS: true)");
            }
            inputBuffer.clear();
            encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    private void releaseEncoder() {
        for (MediaCodec encoder : mAVEncoders) {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }
}
