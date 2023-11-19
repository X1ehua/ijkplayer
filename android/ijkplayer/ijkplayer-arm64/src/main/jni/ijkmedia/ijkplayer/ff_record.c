
#include "libavcodec/avcodec.h"
#include "libavcodec/mathops.h"
#include "libavformat/avformat.h"
#include "libavutil/intreadwrite.h"
#include "libavutil/timestamp.h"

#include "ff_ffplay.h"
#include "ff_ffplay_def.h"
#include "ff_record.h"

int do_video_out(OutputFile *of, OutputStream *ost, AVFrame *next_picture) //, double sync_ipts)
{
    AVPacket           pkt;
    AVCodecContext    *enc     = ost->enc_ctx;
    AVCodecParameters *mux_par = ost->st->codecpar;
    //AVFilterContext *filter  = ost->filter->filter; // not used
    //InputStream     *ist     = NULL;
    int ret, format_video_sync, nb_frames, /*nb0_frames,*/ i;
    //double delta, delta0, duration = 0; // 目前观察无影响，去掉所有相关代码

    //if (ost->source_index >= 0) ist = input_streams[ost->source_index];
    // static int c = 0;
    //if (sync_ipts) av_log(NULL, AV_LOG_ERROR, ">>>>> sync_ipts %d:%f\n", c++, sync_ipts);
    //double sync_ipts = next_picture ? c++ : AV_NOPTS_VALUE;
    //av_log(NULL, AV_LOG_ERROR, ">>>>> next_pict %d:%d\n", c++, !!next_picture);

    if (!next_picture) { //end, flushing
        /*nb0_frames =*/ nb_frames = mid_pred(ost->last_nb0_frames[0], ost->last_nb0_frames[1], ost->last_nb0_frames[2]); // 0
    } else { // 设置 format_video_sync，以及 nb_frames nb0_frames delta delta0
        /* by default, we output a single frame */
        nb_frames = 1;
        //nb0_frames = 0; // 跟踪上一帧应被复制的次数，主要用于可变帧率（VFR），删除相关代码

        format_video_sync = VSYNC_CFR; // 对于我们的情形中，根据 of.ctx.ofmt.flags 得出 VSYNC_CFR [1]
                                       // VSYNC_CFR: Video Synchronization using Committed Frame Rate 使用已经承诺的帧率
        ost->is_cfr = (format_video_sync == VSYNC_CFR || format_video_sync == VSYNC_VSCFR);

        if (format_video_sync != VSYNC_CFR) { // not reached
            av_log(NULL, AV_LOG_ERROR, ">>> format_video_sync %d != VSYNC_CFR %d", format_video_sync, VSYNC_CFR);
        }
        else { // FIXME set to 0.5 after we fix some dts/pts bugs like in avidec.c
            // frame_drop_threshold == 0，且 (delta < -1.1 || delta > 1.1) 都不曾发生。故删之
        }
    }

    nb_frames = FFMIN(nb_frames, ost->max_frames - ost->frame_number); // 1 or 0[end]

    /* duplicate frame if needed */
    for (i = 0; i < nb_frames; i++)
    {
        AVFrame *in_picture;
        av_init_packet(&pkt);
        pkt.data = NULL;
        pkt.size = 0;

        //if (i < nb0_frames && ost->last_frame) { in_picture = ost->last_frame; } else // ✖️ not reached
        in_picture = next_picture;

        // if (!in_picture) return; // ✖️ not reached
        in_picture->pts = ost->sync_opts; // AVFrame to be written into out.mp4
        // av_log(NULL, AV_LOG_ERROR, ">>>>>> pts %lld = %lld\n", in_picture->pts, ost->sync_opts);

        // if (!check_recording_time(ost)) return 0; // ✖️ not reached // TODO: 实现此函数并打开注释

        // 剔除 FF_API_LAVF_FMT_RAWPICTURE 的代码 [RawPicture 格式]
        // AV_CODEC_FLAG_INTERLACED_DCT | AV_CODEC_FLAG_INTERLACED_ME 代码删除 [Interlaced 隔行扫描]

        if (!in_picture->interlaced_frame) { // ✅ reached
            mux_par->field_order = AV_FIELD_PROGRESSIVE;
        } // else { ... } // ✖️ not reached, Interlaced/隔行扫描的情况，代码删除

        in_picture->quality   = enc->global_quality;
        in_picture->pict_type = 0;

        // 删除 30 行代码：ost->forced_keyframes && forced_keyframe 为 true 的情况 // ✖️ not reached
        // 删除 debug_ts log
        ost->frames_encoded++;

        // AVFrame >> AVPacket 关键步骤
        ret = avcodec_send_frame(enc, in_picture); // AVFrame 发送至 encoder (AVCodecContex*, const AVFrame*)
        if (ret < 0) goto error; // not reached

        int pkts1 = 0, pkts2 = 0;
        while (1)
        {
            ret = avcodec_receive_packet(enc, &pkt); // 接收 encoded AVPacket
            pkts1 ++;
            if (ret == AVERROR(EAGAIN)) { // EAGAIN 35: Resource temporarily unavailable
                // av_log(NULL, AV_LOG_ERROR, ">> avcodec_receive_packet(enc, &pkt) ret %d\n", ret);
                break; // reached
            }
            if (ret < 0)
                goto error; // ✖️ not reached

            // 删除 debug_ts log
            if (pkt.pts == AV_NOPTS_VALUE && !(enc->codec->capabilities & AV_CODEC_CAP_DELAY))
                pkt.pts = ost->sync_opts; // not reached

            av_packet_rescale_ts(&pkt, enc->time_base, ost->mux_timebase);
            // 删除 debug_ts log

            // do_video_out() 调用多次后，才到这里
            //output_packet(of, &pkt, ost, 0);
            write_packet(of, &pkt, ost, 0);
            pkts2 ++;
        }
        //av_log(NULL, AV_LOG_ERROR, ">>> pkts count: %d, %d\n", pkts1, pkts2);

        ost->sync_opts++; // 下一个 frame: in_picture->pts 等于此处 ++ 之后的 ost->sync_opts

        /* For video, number of frames in == number of packets out. But there may be reordering, so we can't
           throw away frames on encoder flush, we need to limit them here, before they go into encoder. */
        ost->frame_number++;
    }

    if (!ost->last_frame)
        ost->last_frame = av_frame_alloc();
    av_frame_unref(ost->last_frame);
    if (next_picture && ost->last_frame)
        av_frame_ref(ost->last_frame, next_picture);
    else
        av_frame_free(&ost->last_frame);

    return 0;
error:
    // exit_program(1); // av_log(NULL, AV_LOG_FATAL, "Video encoding failed\n"); // not reached
    return -1;
}

#if 0 // TODO:
void close_all_output_streams(OutputStream *ost, OSTFinished this_stream, OSTFinished others)
{
    int i;
    for (i = 0; i < nb_output_streams; i++) {
        OutputStream *ost2 = output_streams[i];
        ost2->finished |= ost == ost2 ? this_stream : others;
    }
}
#endif

#if 0 // TODO:
int check_recording_time(OutputStream *ost)
{
    OutputFile *of = output_files[ost->file_index];

    if (of->recording_time != INT64_MAX && av_compare_ts(ost->sync_opts - ost->first_pts, ost->enc_ctx->time_base, of->recording_time, AV_TIME_BASE_Q) >= 0) {
        close_output_stream(ost);
        return 0;
    }
    return 1;
}

void close_output_stream(OutputStream *ost)
{
    OutputFile *of = output_files[ost->file_index];
    ost->finished |= ENCODER_FINISHED;
    if (of->shortest) {
        int64_t end = av_rescale_q(ost->sync_opts - ost->first_pts, ost->enc_ctx->time_base, AV_TIME_BASE_Q);
        of->recording_time = FFMIN(of->recording_time, end);
    }
}
#endif

void print_error(const char *filename, int err);
#if 0 // 使用 ffmpeg.c 里面的定义
{
    char errbuf[128];
    const char *errbuf_ptr = errbuf;

    if (av_strerror(err, errbuf, sizeof(errbuf)) < 0)
        errbuf_ptr = strerror(AVUNERROR(err));
    av_log(NULL, AV_LOG_ERROR, "%s: %s\n", filename, errbuf_ptr);
}
#endif

int write_packet(OutputFile *of, AVPacket *pkt, OutputStream *ost, int unqueue)
{
    AVFormatContext *s = of->ctx;
    AVStream *st = ost->st;
    int ret, main_return_code = 0;

    /*
     * Audio encoders may split the packets --  #frames in != #packets out.
     * But there is no reordering, so we can limit the number of output packets by simply dropping them here.
     * Counting encoded video frames needs to be done separately because of reordering, see do_video_out().
     * Do not count the packet when unqueued because it has been counted when queued.
     */
    if (!(st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && ost->encoding_needed) && !unqueue) {
        if (ost->frame_number >= ost->max_frames) {
            av_packet_unref(pkt);
            return main_return_code;
        }
        ost->frame_number++;
    }

    if (!of->header_written) {
        AVPacket tmp_pkt = {0};
        /* the muxer is not initialized yet, buffer the packet */
        if (!av_fifo_space(ost->muxing_queue)) {
            int new_size = FFMIN(2 * av_fifo_size(ost->muxing_queue), ost->max_muxing_queue_size);
            if (new_size <= av_fifo_size(ost->muxing_queue)) {
                av_log(NULL, AV_LOG_ERROR, "Too many packets buffered for output stream %d:%d.\n", ost->file_index, ost->st->index);
                // exit_program(1);
                return -1;
            }
            ret = av_fifo_realloc2(ost->muxing_queue, new_size);
            if (ret < 0)
                // exit_program(1);
                return ret;
        }
        ret = av_packet_ref(&tmp_pkt, pkt);
        if (ret < 0)
            // exit_program(1);
            return ret;
        av_fifo_generic_write(ost->muxing_queue, &tmp_pkt, sizeof(tmp_pkt), NULL);
        av_packet_unref(pkt);
        return main_return_code;
    }

    // if ((st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && video_sync_method == VSYNC_DROP) || // video_sync: VSYNC_AUTO -1
    //     (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && audio_sync_method < 0))             // VSYNC_DROP: 0xff
    //     pkt->pts = pkt->dts = AV_NOPTS_VALUE;

    if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
        int i;
        uint8_t *sd = av_packet_get_side_data(pkt, AV_PKT_DATA_QUALITY_STATS, NULL);
        ost->quality = sd ? AV_RL32(sd) : -1;
        ost->pict_type = sd ? sd[4] : AV_PICTURE_TYPE_NONE;

        for (i = 0; i<FF_ARRAY_ELEMS(ost->error); i++) {
            if (sd && i < sd[5])
                ost->error[i] = AV_RL64(sd + 8 + 8*i);
            else
                ost->error[i] = -1;
        }

        if (ost->frame_rate.num && ost->is_cfr) {
            if (pkt->duration > 0)
                av_log(NULL, AV_LOG_WARNING, "Overriding packet duration by frame rate, this should not happen\n");
            pkt->duration = av_rescale_q(1, av_inv_q(ost->frame_rate), ost->mux_timebase);
        }
    }

    av_packet_rescale_ts(pkt, ost->mux_timebase, ost->st->time_base); // reached [ffmpeg -i in -ss 0:10 -t 3 -c copy o3]

    if (!(s->oformat->flags & AVFMT_NOTIMESTAMPS)) {
        if (pkt->dts != AV_NOPTS_VALUE && pkt->pts != AV_NOPTS_VALUE && pkt->dts > pkt->pts) {
            av_log(s, AV_LOG_WARNING, "Invalid DTS: %ld PTS: %ld in output stream %d:%d, replacing by guess\n",
                   pkt->dts, pkt->pts, ost->file_index, ost->st->index);
            pkt->pts =
            pkt->dts = pkt->pts + pkt->dts + ost->last_mux_dts + 1
                     - FFMIN3(pkt->pts, pkt->dts, ost->last_mux_dts + 1)
                     - FFMAX3(pkt->pts, pkt->dts, ost->last_mux_dts + 1);
        }
        if ((st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO || st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) &&
            pkt->dts != AV_NOPTS_VALUE && !(st->codecpar->codec_id == AV_CODEC_ID_VP9 && ost->stream_copy) && ost->last_mux_dts != AV_NOPTS_VALUE)
        {
            int64_t max = ost->last_mux_dts + !(s->oformat->flags & AVFMT_TS_NONSTRICT);
            if (pkt->dts < max) {
                int loglevel = max - pkt->dts > 2 || st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO ? AV_LOG_WARNING : AV_LOG_DEBUG;
                av_log(s, loglevel, "Non-monotonous DTS in output stream %d:%d; previous: %ld, current: %ld; ",
                       ost->file_index, ost->st->index, ost->last_mux_dts, pkt->dts);

                /*
                if (exit_on_error) {
                    av_log(NULL, AV_LOG_FATAL, "aborting.\n");
                    exit_program(1);
                }
                */

                av_log(s, loglevel, "changing to %ld. This may result in incorrect timestamps in the output file.\n", max);
                if (pkt->pts >= pkt->dts)
                    pkt->pts = FFMAX(pkt->pts, max);
                pkt->dts = max;
            }
        }
    }
    ost->last_mux_dts = pkt->dts; // record todo ?

    ost->data_size += pkt->size;
    ost->packets_written++;

    pkt->stream_index = ost->index;

#if 0
    if (debug_ts) {
        av_log(NULL, AV_LOG_INFO, "muxer <- type:%s pkt_pts:%s pkt_pts_time:%s pkt_dts:%s pkt_dts_time:%s size:%d\n",
                av_get_media_type_string(ost->enc_ctx->codec_type),
                av_ts2str(pkt->pts), av_ts2timestr(pkt->pts, &ost->st->time_base),
                av_ts2str(pkt->dts), av_ts2timestr(pkt->dts, &ost->st->time_base),
                pkt->size
              );
    }
#endif

    ret = av_interleaved_write_frame(s, pkt);
    if (ret < 0) {
        print_error("av_interleaved_write_frame()", ret);
        main_return_code = 1;
        //close_all_output_streams(ost, MUXER_FINISHED | ENCODER_FINISHED, ENCODER_FINISHED);
        av_log(NULL, AV_LOG_ERROR, ">>>>>>> TODO: close_all_output_streams() missed in %s", __FUNCTION__);
    }
    av_packet_unref(pkt);
    return main_return_code;
}

// int main(int argc, const char** argv) { return 0; }
