package tv.danmaku.ijk.media.player;

import static tv.danmaku.ijk.media.player.IjkMediaPlayer.SAMP_FRAME_SIZE;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
import static tv.danmaku.ijk.media.player.IEncodeDataProvider.AVCacheType;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

@SuppressLint("DefaultLocale")
public class MediaCodecEncodeMuxer implements Runnable {
    private final static String TAG = "EncMuxer";
    private static final boolean VERBOSE = false;

    private static final int FRAME_RATE      = 24; // 24 FPS
    private static final int IFRAME_INTERVAL = 5;  // 2 seconds between I-frames

    private final int mWidth;
    private final int mHeight;
    private final int mBitRate;

    private MediaMuxer mMuxer = null;
    private boolean    mMuxerStarted = false;

    private final static int kVideo = 0;
    private final static int kAudio = 1;

    private final int[]                   mAVTrackIndices = new int[] { -1, -1 };
    private final MediaCodec[]            mAVEncoders     = new MediaCodec[2];
    private final MediaCodec.BufferInfo[] mAVBufferInfos  = new MediaCodec.BufferInfo[2];
    private final IEncodeDataProvider     mEncodeDataProvider;
    private IMediaPlayer.OnRecordListener mRecordListener;

    private final static long DEQUEUE_TIMEOUT_US = 1000L; // 1ms
//  private static boolean sThreadRunning  = false;

//  private long mFrameIndex     = 0; // video 与 audio 共用 pts 以保持音画同步
//  private long mAudioStartTime = -1;
    private int mLastPts = 0;

    public MediaCodecEncodeMuxer(IEncodeDataProvider provider, int width, int height) {
        mEncodeDataProvider = provider;
        mWidth   = width;
        mHeight  = height;
        mBitRate = mWidth * mHeight * 4;
    }

    @SuppressWarnings("deprecation") // 此处必须使用 COLOR_FormatYUV420SemiPlanar
    private void prepareEncodersAndMuxer() throws IOException {
        long t0 = System.nanoTime();
        mAVBufferInfos[kVideo] = new MediaCodec.BufferInfo();
        mAVBufferInfos[kAudio] = new MediaCodec.BufferInfo();
        long t1 = System.nanoTime();

        // Set some props. Failing to specify some of these can cause MediaCodec configure() throw unhelpful exception
        MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);

        /* COLOR_FormatYUV420SemiPlanar: Y plane + UV plane(UVUVUV), 此处须忽略 deprecation，指定此 format */
        /* 另经测试，COLOR_FormatYUV411Planar 在 HUAWEI P30 上面不被支持 */
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420SemiPlanar);

        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "videoFormat: " + videoFormat);
        long t2 = System.nanoTime();

        // TODO: 在 mp4 开始播放后，动态传入当前的 channel-count
        MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, /* channel-count */ 2);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
    //  audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMP_FRAME_SIZE * 10); // 2048*8 = 16384
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max_buffSize);
        long t3 = System.nanoTime();

        mAVEncoders[kVideo] = MediaCodec.createEncoderByType("video/avc"); // 14~42ms
        long t4 = System.nanoTime();
        mAVEncoders[kAudio] = MediaCodec.createEncoderByType("audio/mp4a-latm"); // 13~22ms
        long t5 = System.nanoTime();
        mAVEncoders[kVideo].configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAVEncoders[kAudio].configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        long t6 = System.nanoTime(); // 11~41ms

        mAVEncoders[kVideo].start(); // 92~185ms
        long t7 = System.nanoTime();

        mAVEncoders[kAudio].start(); // 7~20ms
        long t8 = System.nanoTime();

        mMuxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); // 2~8ms
        long t9 = System.nanoTime();

        String msg = String.format(">> prepareEncodersAndMuxer() time cost: 1:%d 2:%d 3:%d 4:%d 5:%d 6:%d 7:%d 8:%d 9:%d += %dms",
                (t1-t0)/1000000, (t2-t1)/1000000, (t3-t2)/1000000,
                (t4-t3)/1000000, (t5-t4)/1000000, (t6-t5)/1000000,
                (t7-t6)/1000000, (t8-t7)/1000000, (t9-t8)/1000000,
                (t8-t0)/1000000);
        Log.d(TAG, msg);
    }

    private String mOutputPath = null;

    //public void stopThread() {
    //    sThreadRunning = false;
    //    Log.w(TAG, ">> stopThread()");
    //}

    public void startRecord(IMediaPlayer.OnRecordListener listener, String outputPath) {
        if (sRecordingStarted) {
            Log.e(TAG, ">> recording in progress !");
            return;
        }

        /* 第3步. start encoders & muxer */
        mRecordListener = listener;
        mOutputPath = outputPath;

        mAVTrackIndices[kAudio] = -1;
        mAVTrackIndices[kVideo] = -1;
        mAVPtsOrigin = -1;
        writeCounter[0] = writeCounter[1] = 0; // debug

        Thread thread = new Thread(this);
        thread.setName("EncMuxer");
        thread.start();
    }

    private static boolean sRecordingStarted = false;

    @Override //@SuppressWarnings("deprecation")
    public void run() {
        /* 第1步. 第一时间将 is->record_cache 里的 audio/video 数据 COPY 过来 */
        mEncodeDataProvider.updateCache(AVCacheType.kAudioCache);
        // 获取 picture 数据较为耗时(约0.3s)，而获取 sample 数据则快很多，所以先取 audio 再取 video
        mEncodeDataProvider.updateCache(AVCacheType.kVideoCache);

        /* 第2点. 初始化 encoders & muxer，比较耗时，所以放在 fetch audio & video data 之后 */
        try {
            prepareEncodersAndMuxer();
        }
        catch (IOException e) {
            Log.w(TAG, e.toString());
            throw new RuntimeException("new MediaMuxer() failed"); // TODO: toast ?
        }

        sRecordingStarted = true;

        //sThreadRunning = true;
        long startTime = System.nanoTime();

        final boolean[] videoThreadRunning = {true};
        final boolean[] audioThreadRunning = {true};

        Thread videoThread = new Thread(new Runnable() {
            @Override
            public void run() {
                /*
                while (sThreadRunning) {
                    doVideoFrame();
                    sThreadRunning = false;
                }
                */
                videoEncodeAndWrite();
                videoThreadRunning[0] = false;
            }
        });
        videoThread.setName("VideoEncoder");
        videoThread.start();

        Thread audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                /*
                while (sThreadRunning) {
                    doAudioFrame();
                    sThreadRunning = false; // 读取 cache
                }
                */
                audioEncodeAndWrite();
                audioThreadRunning[0] = false;
            }
        });
        audioThread.setName("AudioEncoder");
        audioThread.start();

        /* 等待上面的 videoThread & audioThread 都结束，再走到后面 drainEncoder() 写入 EOS */
        while (videoThreadRunning[0] || audioThreadRunning[0]) {
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        drainEncoder(kVideo, true, -1);
        drainEncoder(kAudio, true, -1);

        // clean
        mEncodeDataProvider.getAVCache().reset();
        releaseEncodersAndMuxer();

        float dt = (System.nanoTime() - startTime) / 1000000000.0f;
        Log.e(TAG, String.format(">> record done, last pts %.2fs, cost %.2fs", mLastPts/1000000.0f, dt));

        sRecordingStarted = false;
        if (mRecordListener != null)
            mRecordListener.onFinished();
    }

    private void videoEncodeAndWrite() {
        AVRecordCache avCache = mEncodeDataProvider.getAVCache();
        byte[]        pictArr = avCache.pictArray();

        int frameSize = avCache.getPictFrameSize();
        int frameNum  = avCache.getPictDataSize() / frameSize;
        int frameMod  = avCache.getPictDataSize() % frameSize;
        String msg = String.format(">> video frameNum %d/%d, pts: ", frameNum, frameMod);
        //StringBuilder sb = new StringBuilder(msg);

        int pts = 0;
        byte[] dataYUV = new byte[frameSize - 4];
        for (int i = 0, offset = 0; i < frameNum; ++i, offset += frameSize) {
            // TODO: java 能否定义一个 ByteBuffer 之类的，直接指向 avCache.pictArray() + offset 的位置
            pts = pictArr[offset]   & 0xff        | (pictArr[offset+1] & 0xff) << 8 |
                 (pictArr[offset+2] & 0xff) << 16 | (pictArr[offset+3] & 0xff) << 24;
            //sb.append(pts); sb.append(','); // debug
            if (mAVPtsOrigin == -1)
                mAVPtsOrigin = pts;
            pts -= mAVPtsOrigin;

            System.arraycopy(avCache.pictArray(), offset, dataYUV, 0, frameSize - 4);

            int inputBufferIndex;
            //long pts = 0; // video 与 audio 共用 mAVPts 以保持音画同步
            if ((inputBufferIndex = mAVEncoders[kVideo].dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                //pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
                ByteBuffer inputBuffer = mAVEncoders[kVideo].getInputBuffer(inputBufferIndex);
                inputBuffer.put(dataYUV);
                mAVEncoders[kVideo].queueInputBuffer(inputBufferIndex, 0, frameSize - 4, pts, 0);
                //mFrameIndex += 1;
            } else {
                if (VERBOSE) Log.d(TAG, "videoEncoder's inputBuffer is not available");
            }

            drainEncoder(kVideo, false, i);
            //if (i < 50) Log.i(TAG, ">> drainEncoder(kVideo) " + i);

            try {
                /* video FPS 远不及 audio，所以需要通过 sleep 来避免 video enc & mux 过快导致的 FPS 异常 */
                Thread.sleep(20); // TODO: 当需要输出 60/120 FPS 的 mp4 时，这里需要作出调整
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mLastPts = pts;
        //Log.i(TAG, sb.toString());
        Log.i(TAG, ">> video last pts: " + new DecimalFormat("#.000").format(pts/1000000.0f));
    }

    int max_buffSize = (SAMP_FRAME_SIZE + 4) * (96*3/2 * 3); // 1.5倍
    byte[] sampBuff = new byte[max_buffSize];
    int mAVPtsOrigin = -1;

    private void audioEncodeAndWrite() {
        AVRecordCache avCache  = mEncodeDataProvider.getAVCache();
        byte[]        sampBuff = avCache.sampArray();
        int           dataSize = avCache.getSampDataSize();

        if (dataSize <= 0) {
            try {
                Thread.sleep(5);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        int offset = 0;
        int frameNum = dataSize/(SAMP_FRAME_SIZE + 4);
        StringBuilder sb = new StringBuilder(">> audio frameNum " + frameNum + ", pts: ");

        int pts = 0;
        for (int i = 0; i < frameNum; i++, offset += (SAMP_FRAME_SIZE + 4) ) {
            pts = sampBuff[offset]   & 0xff        | (sampBuff[offset+1] & 0xff) << 8 |
                 (sampBuff[offset+2] & 0xff) << 16 | (sampBuff[offset+3] & 0xff) << 24;
            //if (i > (int)(frameNum*0.8)) { sb.append(pts); sb.append(','); } // debug
            if (mAVPtsOrigin == -1)
                mAVPtsOrigin = pts;
            pts -= mAVPtsOrigin;

            int inputBufferIndex;
            if ((inputBufferIndex = mAVEncoders[kAudio].dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                ByteBuffer inputBuffer = mAVEncoders[kAudio].getInputBuffer(inputBufferIndex);
                inputBuffer.put(sampBuff);
                mAVEncoders[kAudio].queueInputBuffer(inputBufferIndex, offset+4, SAMP_FRAME_SIZE, pts, 0);
            } else {
                if (VERBOSE) Log.d(TAG, "audioEncoder's inputBuffer is not available");
            }

            // 将 encoder 编码产出的 encodedData 取出，交由 muxer 写至 out file
            drainEncoder(kAudio, false, i);
            //if (i % 100 < 30) Log.d(TAG, ">> drainEncoder(kAudio) " + i);
        }
        //Log.i(TAG, sb.toString());
        mLastPts = pts;
        Log.d(TAG, ">> audio last pts: " + new DecimalFormat("#.000").format(pts/1000000.0f));
    }

    int writtenSize = 0; // debug
    int[] writeCounter = new int[] {0, 0};

    /**
     * Extracts all pending data from the encoder.
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    void drainEncoder(int videoOrAudio, boolean endOfStream, int tc)
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
                    //if (tc<20) Log.w(TAG, encoderName + ">> break #101: INFO_TRY_AGAIN_LATER");
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

                //if (!mMuxerStarted && mAVTrackIndices[kAudio] >= 0) {
                //if (!mMuxerStarted && mAVTrackIndices[kVideo] >= 0) {
                if (!mMuxerStarted && mAVTrackIndices[kVideo] >= 0 && mAVTrackIndices[kAudio] >= 0) {
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
                    if (VERBOSE || writeCounter[videoOrAudio]++ < -20) {
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
                    Log.w(TAG, "break #102 drainEncoder " + encoderName);
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

    private void releaseEncodersAndMuxer() {
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
            mMuxerStarted = false;
        }
    }
}
