package tv.danmaku.ijk.media.player;

import java.io.IOException;
import java.nio.ByteBuffer;

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

    private byte[]     mInputYuvData = null;
    private byte[]     mInputAudioData = null;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaMuxer mMuxer;
    private boolean    mMuxerStarted;
    private int        mTrackIndexVideo;
    private int        mTrackIndexAudio;
    private MediaCodec.BufferInfo     mVideoBufferInfo;
    private MediaCodec.BufferInfo     mAudioBufferInfo;
    private final IEncodeDataProvider mEncodeDataProvider;

    private boolean      mThreadRunning  = false;
    private long         mFrameIndex = 0;
//  private ByteBuffer[] mInputBuffers;

    private static final String OUTPUT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
                                            + "/DCIM/cc/t2.mp4";

    public MediaCodecEncodeMuxer(IEncodeDataProvider provider, int width, int height) {
        mEncodeDataProvider = provider;
        mWidth   = width;
        mHeight  = height;
        mBitRate = mWidth * mHeight * 4;
   }

    @SuppressWarnings("deprecation") // COLOR_FormatYUV420SemiPlanar should be specified
    private void prepareEncoder() throws Exception {
        mVideoBufferInfo = new MediaCodec.BufferInfo();
        mAudioBufferInfo = new MediaCodec.BufferInfo();

        // Set some props. Failing to specify some of these can cause MediaCodec configure() throw unhelpful exception
        MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
        /* COLOR_FormatYUV420SemiPlanar: Y plane + UV plane(UVUVUV)
         * COLOR_FormatYUV411Planar    : not supported (HUAWEI P30)
         */
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "videoFormat: " + videoFormat);

        MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, IjkMediaPlayer.SAMPLE_BUFF_SIZE); // 2048*8

        mVideoEncoder = MediaCodec.createEncoderByType("video/avc");
        mAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm"); // MediaCodec.MIMETYPE_AUDIO_AAC
        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mVideoEncoder.start();
        mAudioEncoder.start();

        mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mTrackIndexVideo = -1;
        mTrackIndexAudio = -1;
        mMuxerStarted = false;
    }

    public void startThread() {
        Thread thread = new Thread(this);
        thread.setName("EncodeMuxer");
        thread.start();
    }

    public void stopThread() {
        mThreadRunning = false;
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
        }

        //mInputBuffers  = mVideoEncoder.getInputBuffers();
        mThreadRunning = true;
        long startTime = System.nanoTime();

        //long t1 = System.nanoTime();
        //int fc = 0;
        while (mThreadRunning) { // FPS ~34.3
            fc++;
            int ret = doFrame(); // queueInputBuffer & drainEncoder

            if (ret < 0) {
                mThreadRunning = false;
                Log.w(TAG, "doFrame() failed: " + ret);
                break;
            }
            /*
            long t2 = System.nanoTime();
            fc++;
            if (t2 - t1 >= 1000000000) {
                Log.w(TAG, ">> FPS " + (1000000000.0f * fc / (t2-t1)));
            }
            */
        }

        try {
            drainEncoder(true);
        }
        catch (Exception e) {
            Log.w(TAG, ">> drainEncoder(true) failed: " + e.toString());
        }

        releaseEncoder();
        float dt = (System.nanoTime()-startTime)/1000000000.0f;
        Log.d(TAG, String.format(">> releaseEncoder(), time duration %.1fs", dt));
        try {
            Thread.sleep(50);
            throw new RuntimeException("exit");
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private final static long DEQUEUE_TIMEOUT_US = 5000L; // 5ms
    int doFrame() {
        long t1 = System.nanoTime();
        byte[] dataYUV   = mEncodeDataProvider.getYuvData();
        long t2 = System.nanoTime();
        byte[] dataAudio = mEncodeDataProvider.getSampleData();
        if (dataYUV != null && dataYUV.length > 0) {
            mInputYuvData = dataYUV;
        }
        if (dataAudio != null && dataAudio.length > 0) {
            mInputAudioData = dataAudio;
        }

        if (mInputYuvData == null && mInputAudioData == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.w(TAG, ">> doFrame #" + fc + " return 0 for both YuvData & AudioData are null");
            return 0;
        }

        try {
            int inputBufferIndex = -1;
            long pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
            if (mInputYuvData != null &&
                    (inputBufferIndex = mVideoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                //pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
                //ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                ByteBuffer inputBuffer = mVideoEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    //inputBuffer.clear();
                    inputBuffer.put(mInputYuvData);
                    mVideoEncoder.queueInputBuffer(inputBufferIndex, 0, mInputYuvData.length, pts, 0);
                }
                //mFrameIndex += 1;
            }
            else {
                Log.e(TAG, ">>>>>>>>>> inputBufferIndex of mVideoEncoder < 0: " + inputBufferIndex);
            }
            mFrameIndex += 1;
            long t3 = System.nanoTime();

            if (mInputAudioData != null &&
                    (inputBufferIndex = mAudioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                //ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                ByteBuffer inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    //inputBuffer.clear();
                    inputBuffer.put(mInputAudioData);
                    int sum = 0;
                    for (byte b: mInputAudioData) {
                        sum += b;
                    }
                    Log.e(TAG, ">> inputData sum: " + sum);
                    /*
                    int offset = 104;
                    String msg = String.format(">> inputData: %x %x %x %x %x %x %x %x",
                            mInputAudioData[offset+0],
                            mInputAudioData[offset+1],
                            mInputAudioData[offset+2],
                            mInputAudioData[offset+3],
                            mInputAudioData[offset+4],
                            mInputAudioData[offset+5],
                            mInputAudioData[offset+6],
                            mInputAudioData[offset+7]);
                    Log.e(TAG, msg);
                    */
                    if (mInputAudioData.length != inputBuffer.capacity()) {
                        Log.e(TAG, String.format("inputBuffer: cap %d, limit %d, put data.len %d",
                                inputBuffer.capacity(), inputBuffer.limit(), mInputAudioData.length));
                    }
                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, mInputAudioData.length, pts, 0);
                }
                else {
                    Log.e(TAG, "\t>> audioEncoder.getInputBuffer() returned null!");
                }
            }

            long t4 = System.nanoTime();
            drainEncoder(false);
            long t5 = System.nanoTime();
            // 9.2 + 5.5 + 13.1 = 27.8 ms
            Log.w(TAG, String.format(">> doFrame#%d: %d %d %d %d += %d", fc, (t2-t1)/1000000, (t3-t2)/1000000,
                                     (t4-t3)/1000000, (t5-t4)/1000000, (t5-t1)/1000000));
            return 0;
        }
        catch (Throwable t) {
            Log.e(TAG, "drainEncoder(false) failed:");
            t.printStackTrace();
            return -1;
        }
    }
    private int fc = 0;

    void inputEndOfStream(MediaCodec encoder) {
        int inputBufferIndex = encoder.dequeueInputBuffer(-1);
        if (inputBufferIndex >= 0) {
            //ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
            ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer == null) {
                throw new RuntimeException("getInputBuffer() returned null in drainEncoder(EOS: true)");
            }
            inputBuffer.clear();
            encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    /**
     * Extracts all pending data from the encoder.
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    private void drainEncoder(boolean endOfStream) {
        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");

            inputEndOfStream(mVideoEncoder);
        }

        //int wc = 0;
        while (mThreadRunning) {
            int ret1 = drainEncoderVideo(endOfStream);
            int ret2 = drainEncoderAudio(endOfStream);

            Log.i(TAG, String.format(">> drainEncoderVideo/Audio ret1 %d, ret2 %d", ret1, ret2));
            if (ret1 < 0 || ret2 < 0) {
                break;
            }
            else if (!mMuxerStarted && mTrackIndexVideo >= 0 && mTrackIndexAudio >= 0) {
            //else if (!mMuxerStarted && mTrackIndexVideo >= 0) {
                mMuxer.start();
                mMuxerStarted = true;
                Log.w(TAG, ">>>>>> Muxer started, videoTrack: " + mTrackIndexVideo + ", audioTrack: " + mTrackIndexAudio);
            }
            //wc++;
        }
        //Log.w(TAG, ">> while counter " + wc); // 0 0 4 1 1 1 ... 2
    }

    int drainEncoderVideo(boolean endOfStream)
    {
        int statusOrIndex = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, 10000);

        if (statusOrIndex == MediaCodec.INFO_TRY_AGAIN_LATER) { // no output available yet
            if (!endOfStream) {
                Log.e(TAG, "drainEncoderVideo -1 #101");
                return -1; //break; // out of while
            }
            // else: no output available, spinning to await EOS
        }
        //else if (statusOrIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { // deprecated
        //}
        else if (statusOrIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // should happen before receiving buffers, and should only happen once
            if (mMuxerStarted)
                throw new RuntimeException("MediaCodec.output_format changed twice");

            // now that we have the Magic Goodies, start the muxer
            MediaFormat videoFormat = mVideoEncoder.getOutputFormat();
            mTrackIndexVideo = mMuxer.addTrack( videoFormat );
            Log.d(TAG, "drainEncoderVideo 0 #1031 [SHOULD ONLY ONCE!]");
        }
        else if (statusOrIndex < 0) { // let's ignore it
            Log.e(TAG, ">>> mVideoEncoder.dequeueOutputBuffer() returned bad statusOrIndex: " + statusOrIndex);
        }
        else {
            ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(statusOrIndex);
            if (encodedData == null)
                throw new RuntimeException("encoderOutputBuffer " + statusOrIndex + " was null");

            if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // The codec config data was pulled out and fed to the muxer when we got
                // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                mVideoBufferInfo.size = 0;
            }

            //Log.w(TAG, ">> mVideoBufferInfo.size: " + mVideoBufferInfo.size);
            if (mVideoBufferInfo.size != 0) {
                if (!mMuxerStarted)
                    throw new RuntimeException("muxer hasn't started");

                // adjust the ByteBuffer values to match BufferInfo (not needed?)
                encodedData.position(mVideoBufferInfo.offset);
                encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);

                // 平均每秒 35.3 次
                mMuxer.writeSampleData(mTrackIndexVideo, encodedData, mVideoBufferInfo);
                //if (VERBOSE)
                    Log.d(TAG, ">> sent " + mVideoBufferInfo.size + " bytes to muxer");
            }

            mVideoEncoder.releaseOutputBuffer(statusOrIndex, false);

            if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (!endOfStream)
                    Log.w(TAG, "reached end of stream unexpectedly");
                else
                    if (VERBOSE) Log.d(TAG, "end of stream reached");
                Log.e(TAG, "drainEncoderVideo -1 #102");
                return -1; //break; // out of while
            }
            Log.e(TAG, "drainEncoderVideo 0 #1032");
        }

        return 0;
    }

    int drainEncoderAudio(boolean endOfStream)
    {
        int statusOrIndex = mAudioEncoder.dequeueOutputBuffer(mAudioBufferInfo, 10000);

        if (statusOrIndex == MediaCodec.INFO_TRY_AGAIN_LATER) { // no output available yet
            if (!endOfStream) {
                Log.d(TAG, "\tdrainEncoderAudio -1 #101");
                return -1; //break; // out of while
            }
            // else: no output available, spinning to await EOS
        }
        //else if (statusOrIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { // deprecated
        //}
        else if (statusOrIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // should happen before receiving buffers, and should only happen once
            if (mMuxerStarted)
                throw new RuntimeException("MediaCodec.output_format changed twice");

            // now that we have the Magic Goodies, start the muxer
            MediaFormat audioFormat = mAudioEncoder.getOutputFormat();
            mTrackIndexAudio = mMuxer.addTrack( audioFormat );
            Log.d(TAG, "\tdrainEncoderAudio 0 #1031 [SHOULD ONLY ONCE!]");
        }
        else if (statusOrIndex < 0) { // let's ignore it
            Log.e(TAG, ">>> mAudioEncoder.dequeueOutputBuffer() returned bad statusOrIndex: " + statusOrIndex);
        }
        else {
            ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(statusOrIndex);
            if (encodedData == null)
                throw new RuntimeException("encoderOutputBuffer " + statusOrIndex + " was null");

            if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // The codec config data was pulled out and fed to the muxer when we got
                // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                mAudioBufferInfo.size = 0;
            }

            Log.v(TAG, ">> mAudioBufferInfo.size: " + mAudioBufferInfo.size);
            if (mAudioBufferInfo.size != 0 && mMuxerStarted) {
                //if (!mMuxerStarted)
                    //throw new RuntimeException("muxer hasn't started");

                // adjust the ByteBuffer values to match BufferInfo (not needed?)
                encodedData.position(mAudioBufferInfo.offset);
                encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);

                mMuxer.writeSampleData(mTrackIndexAudio, encodedData, mAudioBufferInfo);
                if (VERBOSE) Log.d(TAG, "sent " + mAudioBufferInfo.size + " bytes to muxer");
            }

            mAudioEncoder.releaseOutputBuffer(statusOrIndex, false);

            if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (!endOfStream)
                    Log.w(TAG, "reached end of stream unexpectedly");
                else
                    if (VERBOSE) Log.d(TAG, "end of stream reached");
                Log.d(TAG, "\tdrainEncoderAudio -1 #102");
                return -1; //break; // out of while
            }
            Log.d(TAG, "\tdrainEncoderAudio 0 #1032");
        }

        return 0;
    }

    private void releaseEncoder() {
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    private void NV21_to_NV12(byte[] src_nv21, byte[] dst_nv12, int width, int height) {
        int frameSize = width * height;
        //long t1 = System.nanoTime();
        System.arraycopy(src_nv21, 0, dst_nv12, 0, frameSize);
        for (int i = 0; i < frameSize / 2; i += 2) {
            dst_nv12[frameSize + i - 1] = src_nv21[i + frameSize];
            dst_nv12[frameSize + i]     = src_nv21[i + frameSize - 1];
        }
        //dt_sum += System.nanoTime() - t1;
        //if (++cc % 100 == 0) // 640x480@24FPS 平均 2.0 ms, TODO: 改为 native 计算是否会有明显提升?
        //    Log.i(TAG, "NV21_to_NV12 dt " + (dt_sum / cc));
    }
    //private int cc = 0, dt_sum = 0;
}
