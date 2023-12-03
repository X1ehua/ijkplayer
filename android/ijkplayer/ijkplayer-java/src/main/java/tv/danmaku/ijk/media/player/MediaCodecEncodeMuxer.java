package tv.danmaku.ijk.media.player;

import java.io.IOException;
import java.nio.ByteBuffer;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

public class MediaCodecEncodeMuxer implements Runnable {
    private final static String TAG = "MediaCodec";
    private static final boolean VERBOSE = false;

    private static final int FRAME_RATE      = 24; // 24 FPS
    private static final int IFRAME_INTERVAL = 5;  // 2 seconds between I-frames

    private final int mWidth;
    private final int mHeight;
    private final int mBitRate;

    private byte[]     mInputYuvData = null;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private boolean    mMuxerStarted;
    private int        mTrackIndex;
    private MediaCodec.BufferInfo  mBufferInfo;
    private final IYuvDataProvider mYuvDataProvider;

    private boolean      mThreadRunning  = false;
    private long         mFrameIndex = 0;
//  private ByteBuffer[] mInputBuffers;

    private static final String OUTPUT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
                                            + "/DCIM/cc/t2.mp4";

    public MediaCodecEncodeMuxer(IYuvDataProvider provider, int width, int height) {
        mYuvDataProvider = provider;
        mWidth   = width;
        mHeight  = height;
        mBitRate = mWidth * mHeight * 4;
   }

    @SuppressWarnings("deprecation")
    private void prepareEncoder() throws Exception {
        mBufferInfo = new MediaCodec.BufferInfo();

        // Set some props. Failing to specify some of these can cause MediaCodec configure() throw unhelpful exception
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
        // TODO: 去掉 deprecation warning, COLOR_FormatYUV420SemiPlanar 换为 auto，看能否略过 NV21_to_NV12() CPU 计算
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        mEncoder = MediaCodec.createEncoderByType("video/avc");
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();

        mMuxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mTrackIndex = -1;
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
            prepareEncoder();
        }
        catch(Exception e) {
            Log.e(TAG, ">> prepareEncoder() failed:");
            e.printStackTrace();
        }

        //mInputBuffers  = mEncoder.getInputBuffers();
        mThreadRunning = true;

        while (mThreadRunning) {
            int ret = doFrame(); // queueInputBuffer & drainEncoder

            if (ret < 0) {
                mThreadRunning = false;
                Log.w(TAG, "doFrame() failed: " + ret);
                break;
            }
        }

        try {
            drainEncoder(true);
        }
        catch (Exception e) {
            Log.w(TAG, ">> drainEncoder(true) failed: " + e.toString());
        }

        releaseEncoder();
        Log.d(TAG, ">> releaseEncoder()");
    }

    int doFrame() {
        byte[] dataYUV = mYuvDataProvider.getYuvData();
        if (dataYUV != null && dataYUV.length > 0) {
            mInputYuvData = dataYUV;
        }

        if (mInputYuvData == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 0;
        }

        try {
            int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                long pts = 132 + mFrameIndex * 1000000 / FRAME_RATE; // 132: magic number ?
                //ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(mInputYuvData);
                    mEncoder.queueInputBuffer(inputBufferIndex, 0, mInputYuvData.length, pts, 0);
                }
                mFrameIndex += 1;
            }

            drainEncoder(false);
            return 0;
        }
        catch (Throwable t) {
            Log.e(TAG, "drainEncoder(false) failed:");
            t.printStackTrace();
            return -1;
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

            int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                //ByteBuffer inputBuffer = mInputBuffers[inputBufferIndex];
                ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer == null) {
                    throw new RuntimeException("getInputBuffer() returned null in drainEncoder(EOS: true)");
                }
                inputBuffer.clear();
                mEncoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }

        //ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int status_or_index = mEncoder.dequeueOutputBuffer(mBufferInfo, 10000);
            if (status_or_index == MediaCodec.INFO_TRY_AGAIN_LATER) { // no output available yet
                if (!endOfStream)
                    break; // out of while
                // else: no output available, spinning to await EOS
            }
            //else if (status_or_index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { // deprecated
            //}
            else if (status_or_index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted)
                    throw new RuntimeException("MediaCodec.output_format changed twice");

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack( mEncoder.getOutputFormat() );
                mMuxer.start();
                mMuxerStarted = true;
            }
            else if (status_or_index < 0) { // let's ignore it
                Log.w(TAG, "mEncoder.dequeueOutputBuffer() returned bad status: " + status_or_index);
            }
            else {
                ByteBuffer encodedData = mEncoder.getOutputBuffer(status_or_index);
                if (encodedData == null)
                    throw new RuntimeException("encoderOutputBuffer " + status_or_index + " was null");

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted)
                        throw new RuntimeException("muxer hasn't started");

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(status_or_index, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream)
                        Log.w(TAG, "reached end of stream unexpectedly");
                    else
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    break; // out of while
                }
            }
        }
    }

    private void releaseEncoder() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
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
