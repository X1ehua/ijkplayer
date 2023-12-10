package tv.danmaku.ijk.media.player;

import java.io.IOException;
import java.nio.ByteBuffer;

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

    private boolean mThreadRunning  = false;
    private long    mFrameIndex = 0;

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
        mAVBufferInfos[kVideo] = new MediaCodec.BufferInfo();
        mAVBufferInfos[kAudio] = new MediaCodec.BufferInfo();

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

        mAVEncoders[kVideo] = MediaCodec.createEncoderByType("video/avc");
        mAVEncoders[kAudio] = MediaCodec.createEncoderByType("audio/mp4a-latm"); // MediaCodec.MIMETYPE_AUDIO_AAC
        mAVEncoders[kVideo].configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAVEncoders[kAudio].configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAVEncoders[kVideo].start();
        mAVEncoders[kAudio].start();

        mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

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
            doFrame(); // queueInputBuffer & drainEncoder

//            if (ret < 0) {
//                mThreadRunning = false;
//                Log.w(TAG, "doFrame() failed: " + ret);
//                break;
//            }
        }

        try {
            drainEncoder(kVideo, true);
//            drainEncoder(kAudio, true);
        }
        catch (Exception e) {
            Log.w(TAG, ">> drainEncoder(true) failed: " + e.toString());
        }

        releaseEncoder();
        float dt = (System.nanoTime()-startTime)/1000000000.0f;
        Log.d(TAG, String.format(">> releaseEncoder(), time duration %.1fs", dt));
        try {
            Thread.sleep(1000);
            throw new RuntimeException("exit");
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private final static long DEQUEUE_TIMEOUT_US = 1000L; // 1ms
    long mAudioStartTime;

    private void doFrame() {
        long t0 = System.nanoTime();
        byte[] dataYUV   = mEncodeDataProvider.getYuvData();
        long t1 = System.nanoTime();
//        byte[] dataAudio = mEncodeDataProvider.getSampleData();

//        if (dataAudio != null && dataAudio.length > 0) {
//            long pts = (System.nanoTime() - mAudioStartTime) / 1000;
//            int inputBufferIndex;
//            if ((inputBufferIndex = mAVEncoders[kAudio].dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
//                ByteBuffer inputBuffer = mAVEncoders[kAudio].getInputBuffer(inputBufferIndex);
//                if (inputBuffer != null) {
//                    //inputBuffer.clear();
//                    inputBuffer.put(dataAudio);
////                    int sum = 0;
////                    for (byte b: dataAudio) {
////                        sum += b;
////                    }
////                    Log.e(TAG, ">> inputData sum: " + sum);
//                    if (dataAudio.length != inputBuffer.capacity()) {
//                        Log.e(TAG, String.format("inputBuffer: cap %d, limit %d, put data.len %d",
//                                inputBuffer.capacity(), inputBuffer.limit(), dataAudio.length));
//                    }
//                    mAVEncoders[kAudio].queueInputBuffer(inputBufferIndex, 0, dataAudio.length, pts, 0);
//                }
//                else {
//                    Log.e(TAG, "\t>> audioEncoder.getInputBuffer() returned null!");
//                }
//            }
//
//            drainEncoder(kAudio, false);
//        }

        if (dataYUV != null && dataYUV.length > 0) {
            int inputBufferIndex;
            long pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
//            mFrameIndex += 1;
            if ((inputBufferIndex = mAVEncoders[kVideo].dequeueInputBuffer(DEQUEUE_TIMEOUT_US)) >= 0) {
                //pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
                //ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                ByteBuffer inputBuffer = mAVEncoders[kVideo].getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    //inputBuffer.clear();
                    inputBuffer.put(dataYUV);
                    mAVEncoders[kVideo].queueInputBuffer(inputBufferIndex, 0, dataYUV.length, pts, 0);
                }
                else
                    Log.w(TAG, ">>> videoEncoder.getInputBuffer() returned null");
                mFrameIndex += 1;
            }
            else {
                Log.e(TAG, ">>>>>>>>>> inputBufferIndex of mVideoEncoder < 0: " + inputBufferIndex);
            }
            drainEncoder(kVideo, false);
            long t2 = System.nanoTime();

            long t3 = System.nanoTime();

            long t4 = System.nanoTime();
            // 9.2 + 5.5 + 13.1 = 27.8 ms
            @SuppressLint("DefaultLocale") String msg = String.format(">> doFrame#%d: %d %d %d %d += %d",
                    fc, (t1-t0)/1000000, (t2-t1)/1000000, (t3-t2)/1000000, (t4-t3)/1000000, (t4-t0)/1000000);
            Log.w(TAG, msg);
        }
    }
    private int fc = 0;

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
            if (VERBOSE) Log.d(TAG, "sending EOS to mVideoEncoder");
            inputEndOfStream(encoder);
        }

        while (true) {
            int statusOrIndex = encoder.dequeueOutputBuffer(bufferInfo, 1000);

            if (statusOrIndex == MediaCodec.INFO_TRY_AGAIN_LATER) { // no output available yet
                if (!endOfStream) {
                    Log.e(TAG, "break #101 drainEncoder " + encoderName);
                    break; // out of while
                }
                else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            }
            else if (statusOrIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { // deprecated
                Log.e(TAG, ">>>>>> deprecated MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
            }
            else if (statusOrIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted)
                    throw new RuntimeException("MediaCodec.output_format changed twice");

                // now that we have the Magic Goodies, start the muxer
                MediaFormat format = encoder.getOutputFormat();
                mAVTrackIndices[videoOrAudio] = mMuxer.addTrack(format);

                //if (!mMuxerStarted && mAVTrackIndices[kVideo] >= 0 && mAVTrackIndices[kAudio] >= 0) {
                if (!mMuxerStarted && mAVTrackIndices[kVideo] >= 0) {
                    mMuxer.start();
                    mMuxerStarted = true;
                    mAudioStartTime = System.nanoTime();
                    Log.w(TAG, ">>>>>> Muxer started, videoTrack: " + mAVTrackIndices[kVideo]
                                                 + ", audioTrack: " + mAVTrackIndices[kAudio]);
                }
            }
            else if (statusOrIndex < 0) { // let's ignore it
                Log.e(TAG, ">> " + encoderName + "Encoder.dequeueOutputBuffer() bad statusOrIndex: " + statusOrIndex);
            }
            else {
                ByteBuffer encodedData = encoder.getOutputBuffer(statusOrIndex);
                if (encodedData == null)
                    throw new RuntimeException("encoder.getOutputBuffer(" + statusOrIndex + ") returned null");

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0 && mMuxerStarted) {
                    //if (!mMuxerStarted)
                    //throw new RuntimeException("muxer hasn't started");

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);

                    // 平均每秒 35.3 次
                    mMuxer.writeSampleData(mAVTrackIndices[videoOrAudio], encodedData, bufferInfo);
                    if (!VERBOSE) {
                        Log.i(TAG, String.format(">> %s.encodedData(pos %d, size %d, cap %d) sent to muxer",
                                encoderName, bufferInfo.offset, bufferInfo.size, encodedData.capacity()));
                    }
                }

                encoder.releaseOutputBuffer(statusOrIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream)
                        Log.w(TAG, "reached end of stream unexpectedly");
                    else if (VERBOSE)
                        Log.d(TAG, "end of stream reached");
                    Log.e(TAG, "break #102 drainEncoder " + encoderName);
                    break; // out of while
                }
            }
        }
    }

    void inputEndOfStream(MediaCodec encoder) {
        int inputBufferIndex = encoder.dequeueInputBuffer(10000); // 10ms
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
