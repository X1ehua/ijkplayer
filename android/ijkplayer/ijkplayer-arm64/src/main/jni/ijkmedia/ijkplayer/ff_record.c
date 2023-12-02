
#include "libavcodec/avcodec.h"
#include "libavcodec/mathops.h"
#include "libavformat/avformat.h"
#include "libavutil/intreadwrite.h"
#include "libavutil/timestamp.h"

#include "ff_ffplay.h"
#include "ff_ffplay_def.h"
#include "ff_record.h"
#include "cmdutils.h"

InputStream **input_streams = NULL;
int        nb_input_streams = 0;
InputFile   **input_files   = NULL;
int        nb_input_files   = 0;

OutputStream **output_streams = NULL;
int         nb_output_streams = 0;
OutputFile   **output_files   = NULL;
int         nb_output_files   = 0;

FilterGraph **filtergraphs;
int        nb_filtergraphs;

static int run_as_daemon  = 0;
static int nb_frames_dup = 0;
static unsigned dup_warning = 1000;
static int nb_frames_drop = 0;
static int64_t decode_error_stat[2];

int copy_ts = 0;
int debug_ts = 0;
int video_sync_method = 0;
float frame_drop_threshold = 0.0f;
float dts_error_threshold = 0.0f;
char* vstats_filename = "";

static void update_benchmark(const char *fmt, ...) {}
#define ff_dlog

static void close_output_stream(OutputStream *ost)
{
    OutputFile *of = output_files[ost->file_index];

    ost->finished |= ENCODER_FINISHED;
    if (of->shortest) {
        int64_t end = av_rescale_q(ost->sync_opts - ost->first_pts, ost->enc_ctx->time_base, AV_TIME_BASE_Q);
        of->recording_time = FFMIN(of->recording_time, end);
    }
}

static int check_recording_time(OutputStream *ost)
{
    OutputFile *of = output_files[ost->file_index];

    if (of->recording_time != INT64_MAX &&
        av_compare_ts(ost->sync_opts - ost->first_pts, ost->enc_ctx->time_base, of->recording_time,
                      AV_TIME_BASE_Q) >= 0) {
        close_output_stream(ost);
        return 0;
    }
    return 1;
}
static void output_packet(OutputFile *of, AVPacket *pkt, OutputStream *ost, int eof)
{
    int ret = 0;

    /* apply the output bitstream filters, if any */
    if (ost->nb_bitstream_filters) {
        int idx;

        ret = av_bsf_send_packet(ost->bsf_ctx[0], eof ? NULL : pkt);
        if (ret < 0)
            goto finish;

        eof = 0;
        idx = 1;
        while (idx) {
            /* get a packet from the previous filter up the chain */
            ret = av_bsf_receive_packet(ost->bsf_ctx[idx - 1], pkt);
            if (ret == AVERROR(EAGAIN)) {
                ret = 0;
                idx--;
                continue;
            } else if (ret == AVERROR_EOF) {
                eof = 1;
            } else if (ret < 0)
                goto finish;

            /* send it to the next filter down the chain or to the muxer */
            if (idx < ost->nb_bitstream_filters) {
                ret = av_bsf_send_packet(ost->bsf_ctx[idx], eof ? NULL : pkt);
                if (ret < 0)
                    goto finish;
                idx++;
                eof = 0;
            } else if (eof)
                goto finish;
            else
                // main_return_code = write_packet(of, pkt, ost, 0);
                write_packet(of, pkt, ost, 0);
        }
    } else if (!eof)
        // main_return_code = write_packet(of, pkt, ost, 0);
        write_packet(of, pkt, ost, 0);

finish:
    if (ret < 0 && ret != AVERROR_EOF) {
        av_log(NULL, AV_LOG_ERROR, "Error applying bitstream filters to an output packet for stream #%d:%d.\n", ost->file_index, ost->index);
        // if(exit_on_error)
        //     exit_program(1);
    }
}

void do_video_out(OutputFile *of,
                         OutputStream *ost,
                         AVFrame *next_picture,
                         double sync_ipts)
{
    int ret, format_video_sync;
    AVPacket pkt;
    AVCodecContext *enc = ost->enc_ctx;
    AVCodecParameters *mux_par = ost->st->codecpar;
    AVRational frame_rate;
    int nb_frames, nb0_frames, i;
    double delta, delta0;
    double duration = 0;
    int frame_size = 0;
    InputStream *ist = NULL;
    AVFilterContext *filter = ost->filter->filter;

//    if (ost->source_index >= 0)
//        ist = input_streams[ost->source_index];

    // frame_rate = av_buffersink_get_frame_rate(filter); // 🔸
    if (frame_rate.num > 0 && frame_rate.den > 0)
        duration = 1/(av_q2d(frame_rate) * av_q2d(enc->time_base));

    if(ist && ist->st->start_time != AV_NOPTS_VALUE && ist->st->first_dts != AV_NOPTS_VALUE && ost->frame_rate.num)
        duration = FFMIN(duration, 1/(av_q2d(ost->frame_rate) * av_q2d(enc->time_base)));

    if (!ost->filters_script &&
        !ost->filters &&
        next_picture &&
        ist &&
        lrintf(next_picture->pkt_duration * av_q2d(ist->st->time_base) / av_q2d(enc->time_base)) > 0) {
        duration = lrintf(next_picture->pkt_duration * av_q2d(ist->st->time_base) / av_q2d(enc->time_base));
    }

    if (!next_picture) {
        //end, flushing
        nb0_frames = nb_frames = mid_pred(ost->last_nb0_frames[0],
                                          ost->last_nb0_frames[1],
                                          ost->last_nb0_frames[2]);
    } else {
        delta0 = sync_ipts - ost->sync_opts; // delta0 is the "drift" between the input frame (next_picture) and where it would fall in the output.
        delta  = delta0 + duration;

        /* by default, we output a single frame */
        nb0_frames = 0; // tracks the number of times the PREVIOUS frame should be duplicated, mostly for variable framerate (VFR)
        nb_frames = 1;

        format_video_sync = video_sync_method;
        if (format_video_sync == VSYNC_AUTO) {
            if(!strcmp(of->ctx->oformat->name, "avi")) {
                format_video_sync = VSYNC_VFR;
            } else
                format_video_sync = (of->ctx->oformat->flags & AVFMT_VARIABLE_FPS) ? ((of->ctx->oformat->flags & AVFMT_NOTIMESTAMPS) ? VSYNC_PASSTHROUGH : VSYNC_VFR) : VSYNC_CFR;
            if (   ist
                && format_video_sync == VSYNC_CFR
                && input_files[ist->file_index]->ctx->nb_streams == 1
                && input_files[ist->file_index]->input_ts_offset == 0) {
                format_video_sync = VSYNC_VSCFR;
            }
            if (format_video_sync == VSYNC_CFR && copy_ts) {
                format_video_sync = VSYNC_VSCFR;
            }
        }
        ost->is_cfr = (format_video_sync == VSYNC_CFR || format_video_sync == VSYNC_VSCFR);

        if (delta0 < 0 &&
            delta > 0 &&
            format_video_sync != VSYNC_PASSTHROUGH &&
            format_video_sync != VSYNC_DROP) {
            if (delta0 < -0.6) {
                av_log(NULL, AV_LOG_WARNING, "Past duration %f too large\n", -delta0);
            } else
                av_log(NULL, AV_LOG_DEBUG, "Clipping frame in rate conversion by %f\n", -delta0);
            sync_ipts = ost->sync_opts;
            duration += delta0;
            delta0 = 0;
        }

        switch (format_video_sync) {
        case VSYNC_VSCFR:
            if (ost->frame_number == 0 && delta0 >= 0.5) {
                av_log(NULL, AV_LOG_DEBUG, "Not duplicating %d initial frames\n", (int)lrintf(delta0));
                delta = duration;
                delta0 = 0;
                ost->sync_opts = lrint(sync_ipts);
            }
        case VSYNC_CFR:
            // FIXME set to 0.5 after we fix some dts/pts bugs like in avidec.c
            if (frame_drop_threshold && delta < frame_drop_threshold && ost->frame_number) {
                nb_frames = 0;
            } else if (delta < -1.1)
                nb_frames = 0;
            else if (delta > 1.1) {
                nb_frames = lrintf(delta);
                if (delta0 > 1.1)
                    nb0_frames = lrintf(delta0 - 0.6);
            }
            break;
        case VSYNC_VFR:
            if (delta <= -0.6)
                nb_frames = 0;
            else if (delta > 0.6)
                ost->sync_opts = lrint(sync_ipts);
            break;
        case VSYNC_DROP:
        case VSYNC_PASSTHROUGH:
            ost->sync_opts = lrint(sync_ipts);
            break;
        default:
            av_assert0(0);
        }
    }

    nb_frames = FFMIN(nb_frames, ost->max_frames - ost->frame_number);
    nb0_frames = FFMIN(nb0_frames, nb_frames);

    memmove(ost->last_nb0_frames + 1,
            ost->last_nb0_frames,
            sizeof(ost->last_nb0_frames[0]) * (FF_ARRAY_ELEMS(ost->last_nb0_frames) - 1));
    ost->last_nb0_frames[0] = nb0_frames;

    if (nb0_frames == 0 && ost->last_dropped) {
        nb_frames_drop++;
        av_log(NULL, AV_LOG_VERBOSE,
               "*** dropping frame %d from stream %d at ts %lld\n",
               ost->frame_number, ost->st->index, ost->last_frame->pts);
    }
    if (nb_frames > (nb0_frames && ost->last_dropped) + (nb_frames > nb0_frames)) {
        if (nb_frames > dts_error_threshold * 30) {
            av_log(NULL, AV_LOG_ERROR, "%d frame duplication too large, skipping\n", nb_frames - 1);
            nb_frames_drop++;
            return;
        }
        nb_frames_dup += nb_frames - (nb0_frames && ost->last_dropped) - (nb_frames > nb0_frames);
        av_log(NULL, AV_LOG_VERBOSE, "*** %d dup!\n", nb_frames - 1);
        if (nb_frames_dup > dup_warning) {
            av_log(NULL, AV_LOG_WARNING, "More than %d frames duplicated\n", dup_warning);
            dup_warning *= 10;
        }
    }
    ost->last_dropped = nb_frames == nb0_frames && next_picture;

    /* duplicates frame if needed */
    for (i = 0; i < nb_frames; i++) {
        AVFrame *in_picture;
        av_init_packet(&pkt);
        pkt.data = NULL;
        pkt.size = 0;

        if (i < nb0_frames && ost->last_frame) {
            in_picture = ost->last_frame;
        } else
            in_picture = next_picture;

        if (!in_picture)
            return;

        in_picture->pts = ost->sync_opts; // avframe to be written into out.mp4

    #if 1
        if (!check_recording_time(ost))
    #else
        if (ost->frame_number >= ost->max_frames)
    #endif
            return;

    #if FF_API_LAVF_FMT_RAWPICTURE
        if (of->ctx->oformat->flags & AVFMT_RAWPICTURE &&
            enc->codec->id == AV_CODEC_ID_RAWVIDEO) {
            /* raw pictures are written as AVPicture structure to
            avoid any copies. We support temporarily the older
            method. */
            if (in_picture->interlaced_frame)
                mux_par->field_order = in_picture->top_field_first ? AV_FIELD_TB:AV_FIELD_BT;
            else
                mux_par->field_order = AV_FIELD_PROGRESSIVE;
            pkt.data   = (uint8_t *)in_picture;
            pkt.size   =  sizeof(AVPicture);
            pkt.pts    = av_rescale_q(in_picture->pts, enc->time_base, ost->mux_timebase);
            pkt.flags |= AV_PKT_FLAG_KEY;

            output_packet(of, &pkt, ost, 0);
        } else
    #endif
        {
            int forced_keyframe = 0;
            double pts_time;

            if (enc->flags & (AV_CODEC_FLAG_INTERLACED_DCT | AV_CODEC_FLAG_INTERLACED_ME) &&
                ost->top_field_first >= 0)
                in_picture->top_field_first = !!ost->top_field_first;

            if (in_picture->interlaced_frame) {
                if (enc->codec->id == AV_CODEC_ID_MJPEG)
                    mux_par->field_order = in_picture->top_field_first ? AV_FIELD_TT:AV_FIELD_BB;
                else
                    mux_par->field_order = in_picture->top_field_first ? AV_FIELD_TB:AV_FIELD_BT;
            } else
                mux_par->field_order = AV_FIELD_PROGRESSIVE; // reached

            in_picture->quality = enc->global_quality;
            in_picture->pict_type = 0;

            pts_time = in_picture->pts != AV_NOPTS_VALUE ?
                in_picture->pts * av_q2d(enc->time_base) : NAN;
            if (ost->forced_kf_index < ost->forced_kf_count &&
                in_picture->pts >= ost->forced_kf_pts[ost->forced_kf_index]) {
                ost->forced_kf_index++;
                forced_keyframe = 1;
            } else if (ost->forced_keyframes_pexpr) {
                double res;
                ost->forced_keyframes_expr_const_values[FKF_T] = pts_time;
                res = av_expr_eval(ost->forced_keyframes_pexpr,
                                ost->forced_keyframes_expr_const_values, NULL);
                ff_dlog(NULL, "force_key_frame: n:%f n_forced:%f prev_forced_n:%f t:%f prev_forced_t:%f -> res:%f\n",
                        ost->forced_keyframes_expr_const_values[FKF_N],
                        ost->forced_keyframes_expr_const_values[FKF_N_FORCED],
                        ost->forced_keyframes_expr_const_values[FKF_PREV_FORCED_N],
                        ost->forced_keyframes_expr_const_values[FKF_T],
                        ost->forced_keyframes_expr_const_values[FKF_PREV_FORCED_T],
                        res);
                if (res) {
                    forced_keyframe = 1;
                    ost->forced_keyframes_expr_const_values[FKF_PREV_FORCED_N] =
                        ost->forced_keyframes_expr_const_values[FKF_N];
                    ost->forced_keyframes_expr_const_values[FKF_PREV_FORCED_T] =
                        ost->forced_keyframes_expr_const_values[FKF_T];
                    ost->forced_keyframes_expr_const_values[FKF_N_FORCED] += 1;
                }

                ost->forced_keyframes_expr_const_values[FKF_N] += 1;
            } else if (   ost->forced_keyframes
                    && !strncmp(ost->forced_keyframes, "source", 6)
                    && in_picture->key_frame==1) {
                forced_keyframe = 1;
            }

            if (forced_keyframe) {
                in_picture->pict_type = AV_PICTURE_TYPE_I;
                av_log(NULL, AV_LOG_DEBUG, "Forced keyframe at time %f\n", pts_time);
            }

            update_benchmark(NULL);
            if (debug_ts) {
                av_log(NULL, AV_LOG_INFO, "encoder <- type:video "
                    "frame_pts:%s frame_pts_time:%s time_base:%d/%d\n",
                    av_ts2str(in_picture->pts), av_ts2timestr(in_picture->pts, &enc->time_base),
                    enc->time_base.num, enc->time_base.den);
            }

            ost->frames_encoded++;

            ret = avcodec_send_frame(enc, in_picture); // avcodec_send_frame(AVCodecContex*, const AVFrame*)
            if (ret < 0)
                goto error;

            while (1) {
                ret = avcodec_receive_packet(enc, &pkt);
                update_benchmark("encode_video %d.%d", ost->file_index, ost->index);
                if (ret == AVERROR(EAGAIN))
                    break;
                if (ret < 0)
                    goto error;

                if (debug_ts) {
                    av_log(NULL, AV_LOG_INFO, "encoder -> type:video "
                        "pkt_pts:%s pkt_pts_time:%s pkt_dts:%s pkt_dts_time:%s\n",
                        av_ts2str(pkt.pts), av_ts2timestr(pkt.pts, &enc->time_base),
                        av_ts2str(pkt.dts), av_ts2timestr(pkt.dts, &enc->time_base));
                }

                if (pkt.pts == AV_NOPTS_VALUE && !(enc->codec->capabilities & AV_CODEC_CAP_DELAY))
                    pkt.pts = ost->sync_opts;

                av_packet_rescale_ts(&pkt, enc->time_base, ost->mux_timebase);

                if (debug_ts) {
                    av_log(NULL, AV_LOG_INFO, "encoder -> type:video "
                        "pkt_pts:%s pkt_pts_time:%s pkt_dts:%s pkt_dts_time:%s\n",
                        av_ts2str(pkt.pts), av_ts2timestr(pkt.pts, &ost->mux_timebase),
                        av_ts2str(pkt.dts), av_ts2timestr(pkt.dts, &ost->mux_timebase));
                }

                frame_size = pkt.size;
                output_packet(of, &pkt, ost, 0);

                /* if two pass, output log */
                if (ost->logfile && enc->stats_out) {
                    fprintf(ost->logfile, "%s", enc->stats_out);
                }
            }
        }
        ost->sync_opts++;
        /*
        * For video, number of frames in == number of packets out.
        * But there may be reordering, so we can't throw away frames on encoder
        * flush, we need to limit them here, before they go into encoder.
        */
        ost->frame_number++;

        // if (vstats_filename && frame_size)
        //     do_video_stats(ost, frame_size);
    }

    if (!ost->last_frame)
        ost->last_frame = av_frame_alloc();
    av_frame_unref(ost->last_frame);
    if (next_picture && ost->last_frame)
        av_frame_ref(ost->last_frame, next_picture);
    else
        av_frame_free(&ost->last_frame);

    return;
error:
    av_log(NULL, AV_LOG_FATAL, "Video encoding failed\n");
    exit_program(1);
}

static OutputStream *new_output_stream(OptionsContext *o, AVFormatContext *oc, enum AVMediaType type, int source_index);

void print_error(const char *filename, int err); // implemented in ffmpeg.c

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

static void uninit_options(OptionsContext *o)
{
    const OptionDef *po = options;
    int i;

    /* all OPT_SPEC and OPT_STRING can be freed in generic way */
    while (po->name) {
        void *dst = (uint8_t*)o + po->u.off;

        if (po->flags & OPT_SPEC) {
            SpecifierOpt **so = dst;
            int i, *count = (int*)(so + 1);
            for (i = 0; i < *count; i++) {
                av_freep(&(*so)[i].specifier);
                if (po->flags & OPT_STRING)
                    av_freep(&(*so)[i].u.str);
            }
            av_freep(so);
            *count = 0;
        } else if (po->flags & OPT_OFFSET && po->flags & OPT_STRING)
            av_freep(dst);
        po++;
    }

    for (i = 0; i < o->nb_stream_maps; i++)
        av_freep(&o->stream_maps[i].linklabel);
    av_freep(&o->stream_maps);
    av_freep(&o->audio_channel_maps);
    av_freep(&o->streamid_map);
    av_freep(&o->attachments);
}

static void init_options(OptionsContext *o)
{
    memset(o, 0, sizeof(*o));

    o->stop_time = INT64_MAX;
    o->mux_max_delay  = 0.7;
    o->start_time     = AV_NOPTS_VALUE;
    o->start_time_eof = AV_NOPTS_VALUE;
    o->recording_time = INT64_MAX;
    o->limit_filesize = UINT64_MAX;
    o->chapters_input_file = INT_MAX;
    o->accurate_seek  = 1;
}

// int main(int argc, const char** argv) { return 0; }

enum OptGroup {
    GROUP_OUTFILE,
    GROUP_INFILE,
};

static const OptionGroupDef groups[] = {
    [GROUP_OUTFILE] = { "output url",  NULL, OPT_OUTPUT },
    [GROUP_INFILE]  = { "input url",   "i",  OPT_INPUT },
};

static int open_files(OptionGroupList *l, const char *inout,
                      int (*open_file)(OptionsContext*, const char*))
{
    int i, ret;

    for (i = 0; i < l->nb_groups; i++) {
        OptionGroup *g = &l->groups[i];
        OptionsContext o;

        init_options(&o);
        o.g = g;

        ret = parse_optgroup(&o, g);
        if (ret < 0) {
            av_log(NULL, AV_LOG_ERROR, "Error parsing options for %s file "
                   "%s.\n", inout, g->arg);
            return ret;
        }

        av_log(NULL, AV_LOG_DEBUG, "Opening an %s file: %s.\n", inout, g->arg);
        ret = open_file(&o, g->arg);
        uninit_options(&o);
        if (ret < 0) {
            av_log(NULL, AV_LOG_ERROR, "Error opening %s file %s.\n",
                   inout, g->arg);
            return ret;
        }
        av_log(NULL, AV_LOG_DEBUG, "Successfully opened the file.\n");
    }

    return 0;
}

void check_filter_outputs(void)
{
    int i;
    for (i = 0; i < nb_filtergraphs; i++) {
        int n;
        for (n = 0; n < filtergraphs[i]->nb_outputs; n++) {
            OutputFilter *output = filtergraphs[i]->outputs[n];
            if (!output->ost) {
                av_log(NULL, AV_LOG_FATAL, "Filter %s has an unconnected output\n", output->name);
                exit_program(1);
            }
        }
    }
}


static OutputStream *new_video_stream(OptionsContext *o, AVFormatContext *oc, int source_index)
{
    AVStream *st;
    OutputStream *ost;
    AVCodecContext *video_enc;
    char *frame_rate = NULL, *frame_aspect_ratio = NULL;

    ost = new_output_stream(o, oc, AVMEDIA_TYPE_VIDEO, source_index);
    st  = ost->st;
    video_enc = ost->enc_ctx;

    // MATCH_PER_STREAM_OPT(frame_rates, str, frame_rate, oc, st);
    // if (frame_rate && av_parse_video_rate(&ost->frame_rate, frame_rate) < 0) {
    //     av_log(NULL, AV_LOG_FATAL, "Invalid framerate value: %s\n", frame_rate);
    //     exit_program(1);
    // }
    // if (frame_rate && video_sync_method == VSYNC_PASSTHROUGH)
    //     av_log(NULL, AV_LOG_ERROR, "Using -vsync 0 and -r can produce invalid output files\n");

    // MATCH_PER_STREAM_OPT(frame_aspect_ratios, str, frame_aspect_ratio, oc, st);
    // if (frame_aspect_ratio) {
    //     AVRational q;
    //     if (av_parse_ratio(&q, frame_aspect_ratio, 255, 0, NULL) < 0 ||
    //         q.num <= 0 || q.den <= 0) {
    //         av_log(NULL, AV_LOG_FATAL, "Invalid aspect ratio: %s\n", frame_aspect_ratio);
    //         exit_program(1);
    //     }
    //     ost->frame_aspect_ratio = q;
    // }

    // MATCH_PER_STREAM_OPT(filter_scripts, str, ost->filters_script, oc, st);
    // MATCH_PER_STREAM_OPT(filters,        str, ost->filters,        oc, st);

    if (!ost->stream_copy) {
        const char *p = NULL;
        char *frame_size = NULL;
        char *frame_pix_fmt = NULL;
        char *intra_matrix = NULL, *inter_matrix = NULL;
        char *chroma_intra_matrix = NULL;
        int do_pass = 0;
        int i;

        // MATCH_PER_STREAM_OPT(frame_sizes, str, frame_size, oc, st);
        // if (frame_size && av_parse_video_size(&video_enc->width, &video_enc->height, frame_size) < 0) {
        //     av_log(NULL, AV_LOG_FATAL, "Invalid frame size: %s.\n", frame_size);
        //     exit_program(1);
        // }

        // video_enc->bits_per_raw_sample = frame_bits_per_raw_sample;
        // MATCH_PER_STREAM_OPT(frame_pix_fmts, str, frame_pix_fmt, oc, st);
        // if (frame_pix_fmt && *frame_pix_fmt == '+') {
        //     ost->keep_pix_fmt = 1;
        //     if (!*++frame_pix_fmt)
        //         frame_pix_fmt = NULL;
        // }
        // if (frame_pix_fmt && (video_enc->pix_fmt = av_get_pix_fmt(frame_pix_fmt)) == AV_PIX_FMT_NONE) {
        //     av_log(NULL, AV_LOG_FATAL, "Unknown pixel format requested: %s.\n", frame_pix_fmt);
        //     exit_program(1);
        // }
        st->sample_aspect_ratio = video_enc->sample_aspect_ratio;

        // if (intra_only)
        //     video_enc->gop_size = 0;
        // MATCH_PER_STREAM_OPT(intra_matrices, str, intra_matrix, oc, st);
        // if (intra_matrix) {
        //     if (!(video_enc->intra_matrix = av_mallocz(sizeof(*video_enc->intra_matrix) * 64))) {
        //         av_log(NULL, AV_LOG_FATAL, "Could not allocate memory for intra matrix.\n");
        //         exit_program(1);
        //     }
        //     parse_matrix_coeffs(video_enc->intra_matrix, intra_matrix);
        // }
        // MATCH_PER_STREAM_OPT(chroma_intra_matrices, str, chroma_intra_matrix, oc, st);
        // if (chroma_intra_matrix) {
        //     uint16_t *p = av_mallocz(sizeof(*video_enc->chroma_intra_matrix) * 64);
        //     if (!p) {
        //         av_log(NULL, AV_LOG_FATAL, "Could not allocate memory for intra matrix.\n");
        //         exit_program(1);
        //     }
        //     av_codec_set_chroma_intra_matrix(video_enc, p);
        //     parse_matrix_coeffs(p, chroma_intra_matrix);
        // }
        // MATCH_PER_STREAM_OPT(inter_matrices, str, inter_matrix, oc, st);
        // if (inter_matrix) {
        //     if (!(video_enc->inter_matrix = av_mallocz(sizeof(*video_enc->inter_matrix) * 64))) {
        //         av_log(NULL, AV_LOG_FATAL, "Could not allocate memory for inter matrix.\n");
        //         exit_program(1);
        //     }
        //     parse_matrix_coeffs(video_enc->inter_matrix, inter_matrix);
        // }

        // MATCH_PER_STREAM_OPT(rc_overrides, str, p, oc, st);
        for (i = 0; p; i++) {
            int start, end, q;
            int e = sscanf(p, "%d,%d,%d", &start, &end, &q);
            if (e != 3) {
                av_log(NULL, AV_LOG_FATAL, "error parsing rc_override\n");
                exit_program(1);
            }
            video_enc->rc_override =
                av_realloc_array(video_enc->rc_override,
                                 i + 1, sizeof(RcOverride));
            if (!video_enc->rc_override) {
                av_log(NULL, AV_LOG_FATAL, "Could not (re)allocate memory for rc_override.\n");
                exit_program(1);
            }
            video_enc->rc_override[i].start_frame = start;
            video_enc->rc_override[i].end_frame   = end;
            if (q > 0) {
                video_enc->rc_override[i].qscale         = q;
                video_enc->rc_override[i].quality_factor = 1.0;
            }
            else {
                video_enc->rc_override[i].qscale         = 0;
                video_enc->rc_override[i].quality_factor = -q/100.0;
            }
            p = strchr(p, '/');
            if (p) p++;
        }
        video_enc->rc_override_count = i;

#if 0
        if (do_psnr)
            video_enc->flags|= AV_CODEC_FLAG_PSNR;

        /* two pass mode */
        MATCH_PER_STREAM_OPT(pass, i, do_pass, oc, st);
        if (do_pass) {
            if (do_pass & 1) {
                video_enc->flags |= AV_CODEC_FLAG_PASS1;
                av_dict_set(&ost->encoder_opts, "flags", "+pass1", AV_DICT_APPEND);
            }
            if (do_pass & 2) {
                video_enc->flags |= AV_CODEC_FLAG_PASS2;
                av_dict_set(&ost->encoder_opts, "flags", "+pass2", AV_DICT_APPEND);
            }
        }

        MATCH_PER_STREAM_OPT(passlogfiles, str, ost->logfile_prefix, oc, st);
        if (ost->logfile_prefix &&
            !(ost->logfile_prefix = av_strdup(ost->logfile_prefix)))
            exit_program(1);

        if (do_pass) {
            char logfilename[1024];
            FILE *f;

            snprintf(logfilename, sizeof(logfilename), "%s-%d.log",
                     ost->logfile_prefix ? ost->logfile_prefix :
                                           DEFAULT_PASS_LOGFILENAME_PREFIX,
                     i);
            if (!strcmp(ost->enc->name, "libx264")) {
                av_dict_set(&ost->encoder_opts, "stats", logfilename, AV_DICT_DONT_OVERWRITE);
            } else {
                if (video_enc->flags & AV_CODEC_FLAG_PASS2) {
                    char  *logbuffer = read_file(logfilename);

                    if (!logbuffer) {
                        av_log(NULL, AV_LOG_FATAL, "Error reading log file '%s' for pass-2 encoding\n",
                               logfilename);
                        exit_program(1);
                    }
                    video_enc->stats_in = logbuffer;
                }
                if (video_enc->flags & AV_CODEC_FLAG_PASS1) {
                    f = av_fopen_utf8(logfilename, "wb");
                    if (!f) {
                        av_log(NULL, AV_LOG_FATAL,
                               "Cannot write log file '%s' for pass-1 encoding: %s\n",
                               logfilename, strerror(errno));
                        exit_program(1);
                    }
                    ost->logfile = f;
                }
            }
        }

        MATCH_PER_STREAM_OPT(forced_key_frames, str, ost->forced_keyframes, oc, st);
        if (ost->forced_keyframes)
            ost->forced_keyframes = av_strdup(ost->forced_keyframes);

        MATCH_PER_STREAM_OPT(force_fps, i, ost->force_fps, oc, st);

        ost->top_field_first = -1;
        MATCH_PER_STREAM_OPT(top_field_first, i, ost->top_field_first, oc, st);


        ost->avfilter = get_ost_filters(o, oc, ost);
        if (!ost->avfilter)
            exit_program(1);
#endif

    } else {
        // MATCH_PER_STREAM_OPT(copy_initial_nonkeyframes, i, ost->copy_initial_nonkeyframes, oc ,st);
    }

    // if (ost->stream_copy)
        // check_streamcopy_filters(o, oc, ost, AVMEDIA_TYPE_VIDEO);

    return ost;
}

static OutputStream *new_audio_stream(OptionsContext *o, AVFormatContext *oc, int source_index)
{
#if 0
    int n;
    AVStream *st;
    OutputStream *ost;
    AVCodecContext *audio_enc;

    ost = new_output_stream(o, oc, AVMEDIA_TYPE_AUDIO, source_index);
    st  = ost->st;

    audio_enc = ost->enc_ctx;
    audio_enc->codec_type = AVMEDIA_TYPE_AUDIO;

    MATCH_PER_STREAM_OPT(filter_scripts, str, ost->filters_script, oc, st);
    MATCH_PER_STREAM_OPT(filters,        str, ost->filters,        oc, st);

    if (!ost->stream_copy) {
        char *sample_fmt = NULL;

        MATCH_PER_STREAM_OPT(audio_channels, i, audio_enc->channels, oc, st);

        MATCH_PER_STREAM_OPT(sample_fmts, str, sample_fmt, oc, st);
        if (sample_fmt &&
            (audio_enc->sample_fmt = av_get_sample_fmt(sample_fmt)) == AV_SAMPLE_FMT_NONE) {
            av_log(NULL, AV_LOG_FATAL, "Invalid sample format '%s'\n", sample_fmt);
            exit_program(1);
        }

        MATCH_PER_STREAM_OPT(audio_sample_rate, i, audio_enc->sample_rate, oc, st);

        MATCH_PER_STREAM_OPT(apad, str, ost->apad, oc, st);
        ost->apad = av_strdup(ost->apad);

        ost->avfilter = get_ost_filters(o, oc, ost);
        if (!ost->avfilter)
            exit_program(1);

        /* check for channel mapping for this audio stream */
        for (n = 0; n < o->nb_audio_channel_maps; n++) {
            AudioChannelMap *map = &o->audio_channel_maps[n];
            if ((map->ofile_idx   == -1 || ost->file_index == map->ofile_idx) &&
                (map->ostream_idx == -1 || ost->st->index  == map->ostream_idx)) {
                InputStream *ist;

                if (map->channel_idx == -1) {
                    ist = NULL;
                } else if (ost->source_index < 0) {
                    av_log(NULL, AV_LOG_FATAL, "Cannot determine input stream for channel mapping %d.%d\n",
                           ost->file_index, ost->st->index);
                    continue;
                } else {
                    ist = input_streams[ost->source_index];
                }

                if (!ist || (ist->file_index == map->file_idx && ist->st->index == map->stream_idx)) {
                    if (av_reallocp_array(&ost->audio_channels_map,
                                          ost->audio_channels_mapped + 1,
                                          sizeof(*ost->audio_channels_map)
                                          ) < 0 )
                        exit_program(1);

                    ost->audio_channels_map[ost->audio_channels_mapped++] = map->channel_idx;
                }
            }
        }
    }

    if (ost->stream_copy)
        check_streamcopy_filters(o, oc, ost, AVMEDIA_TYPE_AUDIO);

    return ost;
#endif
    return NULL;
}

static OutputStream *new_data_stream(OptionsContext *o, AVFormatContext *oc, int source_index)
{
    return NULL;
#if 0
    OutputStream *ost;

    ost = new_output_stream(o, oc, AVMEDIA_TYPE_DATA, source_index);
    if (!ost->stream_copy) {
        av_log(NULL, AV_LOG_FATAL, "Data stream encoding not supported yet (only streamcopy)\n");
        exit_program(1);
    }

    return ost;
#endif
}



static AVCodec *find_codec_or_die(const char *name, enum AVMediaType type, int encoder)
{
    const AVCodecDescriptor *desc;
    const char *codec_string = encoder ? "encoder" : "decoder";
    AVCodec *codec;

    codec = encoder ?
        avcodec_find_encoder_by_name(name) :
        avcodec_find_decoder_by_name(name);

    if (!codec && (desc = avcodec_descriptor_get_by_name(name))) {
        codec = encoder ? avcodec_find_encoder(desc->id) :
                          avcodec_find_decoder(desc->id);
        if (codec)
            av_log(NULL, AV_LOG_VERBOSE, "Matched %s '%s' for codec '%s'.\n",
                   codec_string, codec->name, desc->name);
    }

    if (!codec) {
        av_log(NULL, AV_LOG_FATAL, "Unknown %s '%s'\n", codec_string, name);
        exit_program(1);
    }
    if (codec->type != type) {
        av_log(NULL, AV_LOG_FATAL, "Invalid %s type '%s'\n", codec_string, name);
        exit_program(1);
    }
    return codec;
}

static int choose_encoder(OptionsContext *o, AVFormatContext *s, OutputStream *ost)
{
    // codec_name: h264, codec_type: AVMEDIA_TYPE_VIDEO
    ost->st->codecpar->codec_type = AVMEDIA_TYPE_VIDEO;
    ost->enc = find_codec_or_die("h264", ost->st->codecpar->codec_type, 1);
    ost->st->codecpar->codec_id = ost->enc->id;

    return 0;
}

#if 1
static OutputStream *new_output_stream(OptionsContext *o, AVFormatContext *oc, enum AVMediaType type, int source_index)
{
    OutputStream *ost;
    AVStream *st = avformat_new_stream(oc, NULL);
    int idx      = oc->nb_streams - 1, ret = 0;
    const char *bsfs = NULL, *time_base = NULL;
    char *next, *codec_tag = NULL;
    double qscale = -1;
    int i;

    if (!st) {
        av_log(NULL, AV_LOG_FATAL, "Could not alloc stream.\n");
        exit_program(1);
    }

    if (oc->nb_streams - 1 < o->nb_streamid_map)
        st->id = o->streamid_map[oc->nb_streams - 1]; // st->id = 0

    GROW_ARRAY(output_streams, nb_output_streams);
    if (!(ost = av_mallocz(sizeof(*ost))))
        exit_program(1);
    output_streams[nb_output_streams - 1] = ost;

    ost->file_index = nb_output_files - 1;
    ost->index      = idx;
    ost->st         = st;
    st->codecpar->codec_type = type;

    // ost->enc = find_codec_or_die("h264", AVMEDIA_TYPE_VIDEO, 1); // TBCOPY
    // ost->st->codecpar->codec_id = ost->enc->id;
    ret = choose_encoder(o, oc, ost);
    if (ret < 0) {
        av_log(NULL, AV_LOG_FATAL, "Error selecting an encoder for stream "
               "%d:%d\n", ost->file_index, ost->index);
        exit_program(1);
    }

    ost->enc_ctx = avcodec_alloc_context3(ost->enc);
    if (!ost->enc_ctx) {
        av_log(NULL, AV_LOG_ERROR, "Error allocating the encoding context.\n");
        exit_program(1);
    }
    ost->enc_ctx->codec_type = type;

    ost->ref_par = avcodec_parameters_alloc();
    if (!ost->ref_par) {
        av_log(NULL, AV_LOG_ERROR, "Error allocating the encoding parameters.\n");
        exit_program(1);
    }

    if (ost->enc) {
        AVIOContext *s = NULL;
        char *buf = NULL, *arg = NULL, *preset = NULL;

        ost->encoder_opts  = filter_codec_opts(o->g->codec_opts, ost->enc->id, oc, st, ost->enc); // NULL

    } else { // not reached
        ost->encoder_opts = filter_codec_opts(o->g->codec_opts, AV_CODEC_ID_NONE, oc, st, NULL);
    }

    ost->max_frames = INT64_MAX;

    ost->copy_prior_start = -1;

    // MATCH_PER_STREAM_OPT(disposition, str, ost->disposition, oc, st);
    // ost->disposition = av_strdup(ost->disposition);

    ost->max_muxing_queue_size = 128;
    // MATCH_PER_STREAM_OPT(max_muxing_queue_size, i, ost->max_muxing_queue_size, oc, st);
    ost->max_muxing_queue_size *= sizeof(AVPacket);

    if (oc->oformat->flags & AVFMT_GLOBALHEADER)
        ost->enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

    av_dict_copy(&ost->sws_dict, o->g->sws_dict, 0); // ost.sws_dict: [{"flags": "bicubic"}]

    av_dict_copy(&ost->swr_opts, o->g->swr_opts, 0); // ost.swr_opts: NULL
    if (ost->enc && av_get_exact_bits_per_sample(ost->enc->id) == 24) // not reached
        av_dict_set(&ost->swr_opts, "output_sample_bits", "24", 0);

    av_dict_copy(&ost->resample_opts, o->g->resample_opts, 0); // ost.resample_opts: NULL

    ost->source_index = source_index; // 0
    if (source_index >= 0) {
        //ost->sync_ist = input_streams[source_index];
        //input_streams[source_index]->discard = 0;
        //input_streams[source_index]->st->discard = input_streams[source_index]->user_set_discard;
    }
    ost->last_mux_dts = AV_NOPTS_VALUE;

    ost->muxing_queue = av_fifo_alloc(8 * sizeof(AVPacket));
    if (!ost->muxing_queue)
        exit_program(1);

    return ost;
}
#endif



static void init_output_filter(OutputFilter *ofilter, OptionsContext *o,
                               AVFormatContext *oc)
{
    OutputStream *ost;

    switch (ofilter->type) {
    case AVMEDIA_TYPE_VIDEO: ost = new_video_stream(o, oc, -1); break;
    case AVMEDIA_TYPE_AUDIO: ost = new_audio_stream(o, oc, -1); break;
    default:
        av_log(NULL, AV_LOG_FATAL, "Only video and audio filters are supported "
               "currently.\n");
        exit_program(1);
    }

    ost->source_index = -1;
    ost->filter       = ofilter;

    ofilter->ost      = ost;
    ofilter->format   = -1;

    if (ost->stream_copy) {
        av_log(NULL, AV_LOG_ERROR, "Streamcopy requested for output stream %d:%d, "
               "which is fed from a complex filtergraph. Filtering and streamcopy "
               "cannot be used together.\n", ost->file_index, ost->index);
        exit_program(1);
    }

    if (ost->avfilter && (ost->filters || ost->filters_script)) {
        const char *opt = ost->filters ? "-vf/-af/-filter" : "-filter_script";
        av_log(NULL, AV_LOG_ERROR,
               "%s '%s' was specified through the %s option "
               "for output stream %d:%d, which is fed from a complex filtergraph.\n"
               "%s and -filter_complex cannot be used together for the same stream.\n",
               ost->filters ? "Filtergraph" : "Filtergraph script",
               ost->filters ? ost->filters : ost->filters_script,
               opt, ost->file_index, ost->index, opt);
        exit_program(1);
    }

    avfilter_inout_free(&ofilter->out_tmp);
}


int init_simple_filtergraph(InputStream *ist, OutputStream *ost)
{
    FilterGraph *fg = av_mallocz(sizeof(*fg));

    if (!fg)
        exit_program(1);
    fg->index = nb_filtergraphs;

    GROW_ARRAY(fg->outputs, fg->nb_outputs);
    if (!(fg->outputs[0] = av_mallocz(sizeof(*fg->outputs[0]))))
        exit_program(1);
    fg->outputs[0]->ost   = ost;
    fg->outputs[0]->graph = fg;
    fg->outputs[0]->format = -1;

    ost->filter = fg->outputs[0];

    GROW_ARRAY(fg->inputs, fg->nb_inputs);
    if (!(fg->inputs[0] = av_mallocz(sizeof(*fg->inputs[0]))))
        exit_program(1);
    fg->inputs[0]->ist   = ist;
    fg->inputs[0]->graph = fg;
    fg->inputs[0]->format = -1;

    fg->inputs[0]->frame_queue = av_fifo_alloc(8 * sizeof(AVFrame*));
    if (!fg->inputs[0]->frame_queue)
        exit_program(1);

//    GROW_ARRAY(ist->filters, ist->nb_filters);
//    ist->filters[ist->nb_filters - 1] = fg->inputs[0];

    GROW_ARRAY(filtergraphs, nb_filtergraphs);
    filtergraphs[nb_filtergraphs - 1] = fg;

    return 0;
}

static int open_output_file(OptionsContext *o, const char *filename)
{
    AVFormatContext *oc;
    int i, j, err;
    AVOutputFormat *file_oformat;
    OutputFile *of;
    OutputStream *ost;
    InputStream  *ist;
    AVDictionary *unused_opts = NULL;
    AVDictionaryEntry *e = NULL;
    int format_flags = 0;

    if (o->stop_time != INT64_MAX && o->recording_time != INT64_MAX) {
        o->stop_time = INT64_MAX;
        av_log(NULL, AV_LOG_WARNING, "-t and -to cannot be used together; using -t.\n");
    }

    if (o->stop_time != INT64_MAX && o->recording_time == INT64_MAX) {
        int64_t start_time = o->start_time == AV_NOPTS_VALUE ? 0 : o->start_time;
        if (o->stop_time <= start_time) {
            av_log(NULL, AV_LOG_ERROR, "-to value smaller than -ss; aborting.\n");
            exit_program(1);
        } else {
            o->recording_time = o->stop_time - start_time;
        }
    }

    GROW_ARRAY(output_files, nb_output_files);
    of = av_mallocz(sizeof(*of));
    if (!of)
        exit_program(1);
    output_files[nb_output_files - 1] = of;

    of->ost_index      = nb_output_streams;
    of->recording_time = o->recording_time;
    of->start_time     = o->start_time;
    of->limit_filesize = o->limit_filesize;
    of->shortest       = o->shortest;
    av_dict_copy(&of->opts, o->g->format_opts, 0);

    if (!strcmp(filename, "-"))
        filename = "pipe:";

    err = avformat_alloc_output_context2(&oc, NULL, o->format, filename);
    if (!oc) {
        print_error(filename, err);
        exit_program(1);
    }

    of->ctx = oc;
    if (o->recording_time != INT64_MAX)
        oc->duration = o->recording_time;

    file_oformat= oc->oformat;
    // oc->interrupt_callback = int_cb;

    e = av_dict_get(o->g->format_opts, "fflags", NULL, 0);
    if (e) {
        const AVOption *o = av_opt_find(oc, "fflags", NULL, 0, 0);
        av_opt_eval_flags(oc, o, e->value, &format_flags);
    }

    /* create streams for all unlabeled output pads */
    for (i = 0; i < nb_filtergraphs; i++) { // nb_filtergraphs: 0
        // removed
    }

    if (!o->nb_stream_maps) { // reached [2023-10-29]
        char *subtitle_codec_name = NULL;
        /* pick the "best" stream of each type */

        /* video: highest resolution */
        if (!o->video_disable && av_guess_codec(oc->oformat, NULL, filename, NULL, AVMEDIA_TYPE_VIDEO) != AV_CODEC_ID_NONE) {
#if 0
            int area = 0, idx = -1;
            int qcr = avformat_query_codec(oc->oformat, oc->oformat->video_codec, 0);
            for (i = 0; i < nb_input_streams; i++) { // 3: input_streams[i].st.codec.codec_type: VIDEO AUDIO DATA
                int new_area;
                ist = input_streams[i];
                new_area = ist->st->codecpar->width * ist->st->codecpar->height + 100000000*!!ist->st->codec_info_nb_frames;
                if((qcr!=MKTAG('A', 'P', 'I', 'C')) && (ist->st->disposition & AV_DISPOSITION_ATTACHED_PIC))
                    new_area = 1;
                if (ist->st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO &&
                    new_area > area) {
                    if((qcr==MKTAG('A', 'P', 'I', 'C')) && !(ist->st->disposition & AV_DISPOSITION_ATTACHED_PIC))
                        continue;
                    area = new_area; // reached
                    idx = i;
                }
            }
            if (idx >= 0)
                new_video_stream(o, oc, idx);
#endif
            int idx = 0;
            new_video_stream(o, oc, idx);
        }

        /* audio: most channels */
        if (!o->audio_disable && av_guess_codec(oc->oformat, NULL, filename, NULL, AVMEDIA_TYPE_AUDIO) != AV_CODEC_ID_NONE) {
            int best_score = 0, idx = -1;
            for (i = 0; i < nb_input_streams; i++) {
                int score;
                ist = input_streams[i];
                score = ist->st->codecpar->channels + 100000000*!!ist->st->codec_info_nb_frames;
                if (ist->st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO &&
                    score > best_score) {
                    best_score = score;
                    idx = i;
                }
            }
            if (idx >= 0)
                new_audio_stream(o, oc, idx);
        }

        /* Data only if codec id match */
        if (!o->data_disable ) {
            enum AVCodecID codec_id = av_guess_codec(oc->oformat, NULL, filename, NULL, AVMEDIA_TYPE_DATA);
            for (i = 0; codec_id != AV_CODEC_ID_NONE && i < nb_input_streams; i++) {
                if (input_streams[i]->st->codecpar->codec_type == AVMEDIA_TYPE_DATA
                    && input_streams[i]->st->codecpar->codec_id == codec_id )
                    new_data_stream(o, oc, i);
            }
        }
    } else {
        for (i = 0; i < o->nb_stream_maps; i++) {
            StreamMap *map = &o->stream_maps[i];

            if (map->disabled)
                continue;

            if (map->linklabel) {
                FilterGraph *fg;
                OutputFilter *ofilter = NULL;
                int j, k;

                for (j = 0; j < nb_filtergraphs; j++) {
                    fg = filtergraphs[j];
                    for (k = 0; k < fg->nb_outputs; k++) {
                        AVFilterInOut *out = fg->outputs[k]->out_tmp;
                        if (out && !strcmp(out->name, map->linklabel)) {
                            ofilter = fg->outputs[k];
                            goto loop_end;
                        }
                    }
                }
loop_end:
                if (!ofilter) {
                    av_log(NULL, AV_LOG_FATAL, "Output with label '%s' does not exist "
                           "in any defined filter graph, or was already used elsewhere.\n", map->linklabel);
                    exit_program(1);
                }
                init_output_filter(ofilter, o, oc);
            } else {
                int src_idx = input_files[map->file_index]->ist_index + map->stream_index;

                ist = input_streams[input_files[map->file_index]->ist_index + map->stream_index];
                if(o->subtitle_disable && ist->st->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE)
                    continue;
                if(o->   audio_disable && ist->st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
                    continue;
                if(o->   video_disable && ist->st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO)
                    continue;
                if(o->    data_disable && ist->st->codecpar->codec_type == AVMEDIA_TYPE_DATA)
                    continue;

                ost = NULL;
                switch (ist->st->codecpar->codec_type) {
                case AVMEDIA_TYPE_VIDEO:      ost = new_video_stream     (o, oc, src_idx); break;
                case AVMEDIA_TYPE_AUDIO:      ost = new_audio_stream     (o, oc, src_idx); break;
                //case AVMEDIA_TYPE_SUBTITLE:   ost = new_subtitle_stream  (o, oc, src_idx); break;
                //case AVMEDIA_TYPE_DATA:       ost = new_data_stream      (o, oc, src_idx); break;
                //case AVMEDIA_TYPE_ATTACHMENT: ost = new_attachment_stream(o, oc, src_idx); break;
                case AVMEDIA_TYPE_UNKNOWN:
                    av_log(NULL, AV_LOG_ERROR, ">>> AVMEDIA_TYPE_UNKNOWN in %s()", __FUNCTION__);
                    break;
                default:
                    av_log(NULL, AV_LOG_FATAL, "Cannot map stream #%d:%d - unsupported type.\n", map->file_index, map->stream_index);
                }
                if (ost)
                    ost->sync_ist = input_streams[  input_files[map->sync_file_index]->ist_index
                                                  + map->sync_stream_index];
            }
        }
    }

    /* handle attached files */
    // removed

#if FF_API_LAVF_AVCTX
    for (i = nb_output_streams - oc->nb_streams; i < nb_output_streams; i++) { //for all streams of this output file
        AVDictionaryEntry *e;
        ost = output_streams[i];

        if ((ost->stream_copy || ost->attachment_filename)
            && (e = av_dict_get(o->g->codec_opts, "flags", NULL, AV_DICT_IGNORE_SUFFIX))
            && (!e->key[5] || check_stream_specifier(oc, ost->st, e->key+6)))
            if (av_opt_set(ost->st->codec, "flags", e->value, 0) < 0)
                exit_program(1);
    }
#endif

    if (!oc->nb_streams && !(oc->oformat->flags & AVFMT_NOSTREAMS)) {
        av_dump_format(oc, nb_output_files - 1, oc->filename, 1);
        av_log(NULL, AV_LOG_ERROR, "Output file #%d does not contain any stream\n", nb_output_files - 1);
        exit_program(1);
    }

    /* check if all codec options have been used */
#if 0
    unused_opts = strip_specifiers(o->g->codec_opts);
    for (i = of->ost_index; i < nb_output_streams; i++) {
        e = NULL;
        while ((e = av_dict_get(output_streams[i]->encoder_opts, "", e,
                                AV_DICT_IGNORE_SUFFIX)))
            av_dict_set(&unused_opts, e->key, NULL, 0);
    }

    e = NULL;
    while ((e = av_dict_get(unused_opts, "", e, AV_DICT_IGNORE_SUFFIX))) { // ❌ not reached
        const AVClass *class = avcodec_get_class();
        const AVOption *option = av_opt_find(&class, e->key, NULL, 0,
                                             AV_OPT_SEARCH_CHILDREN | AV_OPT_SEARCH_FAKE_OBJ);
        const AVClass *fclass = avformat_get_class();
        const AVOption *foption = av_opt_find(&fclass, e->key, NULL, 0,
                                              AV_OPT_SEARCH_CHILDREN | AV_OPT_SEARCH_FAKE_OBJ);
        if (!option || foption)
            continue;


        if (!(option->flags & AV_OPT_FLAG_ENCODING_PARAM)) {
            av_log(NULL, AV_LOG_ERROR, "Codec AVOption %s (%s) specified for "
                   "output file #%d (%s) is not an encoding option.\n", e->key,
                   option->help ? option->help : "", nb_output_files - 1,
                   filename);
            exit_program(1);
        }

        // gop_timecode is injected by generic code but not always used
        if (!strcmp(e->key, "gop_timecode"))
            continue;

        av_log(NULL, AV_LOG_WARNING, "Codec AVOption %s (%s) specified for "
               "output file #%d (%s) has not been used for any stream. The most "
               "likely reason is either wrong type (e.g. a video option with "
               "no video streams) or that it is a private option of some encoder "
               "which was not actually used for any stream.\n", e->key,
               option->help ? option->help : "", nb_output_files - 1, filename);
    }
    av_dict_free(&unused_opts);
#endif

    /* set the decoding_needed flags and create simple filtergraphs */
    for (i = of->ost_index; i < nb_output_streams; i++) {
        OutputStream *ost = output_streams[i];

        ost->encoding_needed = 1; // TODO
        if (ost->encoding_needed && ost->source_index >= 0) {
            InputStream *ist = NULL; //input_streams[ost->source_index];
            //ist->decoding_needed |= DECODING_FOR_OST;

            if (ost->st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO ||
                ost->st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                err = init_simple_filtergraph(ist, ost);
                if (err < 0) {
                    av_log(NULL, AV_LOG_ERROR,
                           "Error initializing a simple filtergraph between streams "
                           "%d:%d->%d:%d\n", ist->file_index, ost->source_index,
                           nb_output_files - 1, ost->st->index);
                    exit_program(1);
                }
            }
        }

        /* set the filter output constraints */
        if (ost->filter) {
            OutputFilter *f = ost->filter;
            int count;
            switch (ost->enc_ctx->codec_type) {
            case AVMEDIA_TYPE_VIDEO:
                f->frame_rate = ost->frame_rate;
                f->width      = ost->enc_ctx->width;
                f->height     = ost->enc_ctx->height;
                if (ost->enc_ctx->pix_fmt != AV_PIX_FMT_NONE) {
                    f->format = ost->enc_ctx->pix_fmt;
                } else if (ost->enc->pix_fmts) {
                    count = 0;
                    while (ost->enc->pix_fmts[count] != AV_PIX_FMT_NONE)
                        count++;
                    f->formats = av_mallocz_array(count + 1, sizeof(*f->formats));
                    if (!f->formats)
                        exit_program(1);
                    memcpy(f->formats, ost->enc->pix_fmts, (count + 1) * sizeof(*f->formats));
                }
                break;
            case AVMEDIA_TYPE_AUDIO:
                if (ost->enc_ctx->sample_fmt != AV_SAMPLE_FMT_NONE) {
                    f->format = ost->enc_ctx->sample_fmt;
                } else if (ost->enc->sample_fmts) {
                    count = 0;
                    while (ost->enc->sample_fmts[count] != AV_SAMPLE_FMT_NONE)
                        count++;
                    f->formats = av_mallocz_array(count + 1, sizeof(*f->formats));
                    if (!f->formats)
                        exit_program(1);
                    memcpy(f->formats, ost->enc->sample_fmts, (count + 1) * sizeof(*f->formats));
                }
                if (ost->enc_ctx->sample_rate) {
                    f->sample_rate = ost->enc_ctx->sample_rate;
                } else if (ost->enc->supported_samplerates) {
                    count = 0;
                    while (ost->enc->supported_samplerates[count])
                        count++;
                    f->sample_rates = av_mallocz_array(count + 1, sizeof(*f->sample_rates));
                    if (!f->sample_rates)
                        exit_program(1);
                    memcpy(f->sample_rates, ost->enc->supported_samplerates,
                           (count + 1) * sizeof(*f->sample_rates));
                }
                if (ost->enc_ctx->channels) {
                    f->channel_layout = av_get_default_channel_layout(ost->enc_ctx->channels);
                } else if (ost->enc->channel_layouts) {
                    count = 0;
                    while (ost->enc->channel_layouts[count])
                        count++;
                    f->channel_layouts = av_mallocz_array(count + 1, sizeof(*f->channel_layouts));
                    if (!f->channel_layouts)
                        exit_program(1);
                    memcpy(f->channel_layouts, ost->enc->channel_layouts,
                           (count + 1) * sizeof(*f->channel_layouts));
                }
                break;
            }
        }
    }

    /* check filename in case of an image number is expected */
    if (oc->oformat->flags & AVFMT_NEEDNUMBER) {
        if (!av_filename_number_test(oc->filename)) {
            print_error(oc->filename, AVERROR(EINVAL));
            exit_program(1);
        }
    }

    if (!(oc->oformat->flags & AVFMT_NOSTREAMS)) { // && !input_stream_potentially_available) {
        av_log(NULL, AV_LOG_ERROR, "No input streams but output needs an input stream\n");
        //exit_program(1);
    }

    if (!(oc->oformat->flags & AVFMT_NOFILE)) {
        /* test if it already exists to avoid losing precious files */
        // assert_file_overwrite(filename);

        /* open the file */
        if ((err = avio_open2(&oc->pb, filename, AVIO_FLAG_WRITE,
                              &oc->interrupt_callback,
                              &of->opts)) < 0) {
            print_error(filename, err);
            exit_program(1);
        }
    }
    // else if (strcmp(oc->oformat->name, "image2")==0 && !av_filename_number_test(filename))
    //     assert_file_overwrite(filename);

    if (o->mux_preload) {
        av_dict_set_int(&of->opts, "preload", o->mux_preload*AV_TIME_BASE, 0);
    }
    oc->max_delay = (int)(o->mux_max_delay * AV_TIME_BASE);

    /* copy metadata */
    for (i = 0; i < o->nb_metadata_map; i++) { // nb_metadata_map: 0
        // removed
    }

    /* copy chapters */
    if (o->chapters_input_file >= nb_input_files) {
        if (o->chapters_input_file == INT_MAX) {
            /* copy chapters from the first input file that has them*/
            o->chapters_input_file = -1;
            for (i = 0; i < nb_input_files; i++)
                if (input_files[i]->ctx->nb_chapters) {
                    o->chapters_input_file = i;
                    break;
                }
        } else {
            av_log(NULL, AV_LOG_FATAL, "Invalid input file index %d in chapter mapping.\n",
                   o->chapters_input_file);
            exit_program(1);
        }
    }
    // if (o->chapters_input_file >= 0)
    //     copy_chapters(input_files[o->chapters_input_file], of,
    //                   !o->metadata_chapters_manual);

    /* copy global metadata by default */
    if (!o->metadata_global_manual && nb_input_files){
        av_dict_copy(&oc->metadata, input_files[0]->ctx->metadata,
                     AV_DICT_DONT_OVERWRITE);
        if(o->recording_time != INT64_MAX)
            av_dict_set(&oc->metadata, "duration", NULL, 0);
        av_dict_set(&oc->metadata, "creation_time", NULL, 0);
    }

#if 0
    if (!o->metadata_streams_manual)
        for (i = of->ost_index; i < nb_output_streams; i++) {
            InputStream *ist;
            if (output_streams[i]->source_index < 0)         /* this is true e.g. for attached files */
                continue;
            ist = input_streams[output_streams[i]->source_index];
            av_dict_copy(&output_streams[i]->st->metadata, ist->st->metadata, AV_DICT_DONT_OVERWRITE);
            if (!output_streams[i]->stream_copy) {
                av_dict_set(&output_streams[i]->st->metadata, "encoder", NULL, 0);
            }
        }
#endif
    const char* meta[12] = {
        "language", "und",
        "handler_name", "Core Media Video",
        "timecode", "00:00:00:00",
        "language", "zho",
        "handler_name", "Core Media Audio",
        "timecode", "00:00:00:00",
    };
    for (int m=0; m<6; m++) {
        av_dict_set(&output_streams[0]->st->metadata, meta[m*2], meta[m*2+1], AV_DICT_DONT_OVERWRITE);
    }

    /* process manually set programs */
    for (i = 0; i < o->nb_program; i++) { // nb_program: 0
        // removed
    }

    /* process manually set metadata */
    for (i = 0; i < o->nb_metadata; i++) { // nb_metadata: 0
        // removed
    }

    return 0;
}

static InputStream *get_input_stream(OutputStream *ost)
{
    // if (ost->source_index >= 0)
    //     return input_streams[ost->source_index];
    return NULL;
}

static void set_encoder_id(OutputFile *of, OutputStream *ost)
{
    AVDictionaryEntry *e;

    uint8_t *encoder_string;
    int encoder_string_len;
    int format_flags = 0;
    int codec_flags = 0;

    if (av_dict_get(ost->st->metadata, "encoder",  NULL, 0))
        return;

    e = av_dict_get(of->opts, "fflags", NULL, 0);
    if (e) {
        const AVOption *o = av_opt_find(of->ctx, "fflags", NULL, 0, 0);
        if (!o)
            return;
        av_opt_eval_flags(of->ctx, o, e->value, &format_flags);
    }
    e = av_dict_get(ost->encoder_opts, "flags", NULL, 0);
    if (e) {
        const AVOption *o = av_opt_find(ost->enc_ctx, "flags", NULL, 0, 0);
        if (!o)
            return;
        av_opt_eval_flags(ost->enc_ctx, o, e->value, &codec_flags);
    }

    encoder_string_len = sizeof(LIBAVCODEC_IDENT) + strlen(ost->enc->name) + 2;
    encoder_string     = av_mallocz(encoder_string_len);
    if (!encoder_string)
        exit_program(1);

    if (!(format_flags & AVFMT_FLAG_BITEXACT) && !(codec_flags & AV_CODEC_FLAG_BITEXACT))
        av_strlcpy(encoder_string, LIBAVCODEC_IDENT " ", encoder_string_len);
    else
        av_strlcpy(encoder_string, "Lavc ", encoder_string_len);
    av_strlcat(encoder_string, ost->enc->name, encoder_string_len);
    av_dict_set(&ost->st->metadata, "encoder",  encoder_string,
                AV_DICT_DONT_STRDUP_VAL | AV_DICT_DONT_OVERWRITE);
}

static void init_encoder_time_base(OutputStream *ost, AVRational default_time_base)
{
    InputStream *ist = get_input_stream(ost);
    AVCodecContext *enc_ctx = ost->enc_ctx;
    AVFormatContext *oc;

    if (ost->enc_timebase.num > 0) {
        enc_ctx->time_base = ost->enc_timebase;
        return;
    }

    if (ost->enc_timebase.num < 0) {
        if (ist) {
            enc_ctx->time_base = ist->st->time_base;
            return;
        }

        oc = output_files[ost->file_index]->ctx;
        av_log(oc, AV_LOG_WARNING, "Input stream data not available, using default time base\n");
    }

    enc_ctx->time_base = default_time_base;
}

void assert_avoptions(AVDictionary *m)
{
    AVDictionaryEntry *t;
    if ((t = av_dict_get(m, "", NULL, AV_DICT_IGNORE_SUFFIX))) {
        av_log(NULL, AV_LOG_FATAL, "Option %s not found.\n", t->key);
        exit_program(1);
    }
}

// static int decode_interrupt_cb(void *ctx); // implemented in ff_ffplay.c
// const AVIOInterruptCB int_cb = { decode_interrupt_cb, NULL };

/* open the muxer when all the streams are initialized */
static int check_init_output_file(OutputFile *of, int file_index)
{
    int ret, i;

    for (i = 0; i < of->ctx->nb_streams; i++) {
        OutputStream *ost = output_streams[of->ost_index + i];
        if (!ost->initialized)
            return 0;
    }

    // of->ctx->interrupt_callback = int_cb;

    ret = avformat_write_header(of->ctx, &of->opts);
    if (ret < 0) {
        av_log(NULL, AV_LOG_ERROR,
               "Could not write header for output file #%d "
               "(incorrect codec parameters ?): %s\n",
               file_index, av_err2str(ret));
        return ret;
    }
    //assert_avoptions(of->opts);
    of->header_written = 1;

    av_dump_format(of->ctx, file_index, of->ctx->filename, 1);

    // if (sdp_filename || want_sdp)
    //     print_sdp();

    /* flush the muxing queues */
    for (i = 0; i < of->ctx->nb_streams; i++) {
        OutputStream *ost = output_streams[of->ost_index + i];

        /* try to improve muxing time_base (only possible if nothing has been written yet) */
        if (!av_fifo_size(ost->muxing_queue))
            ost->mux_timebase = ost->st->time_base; // ✳️

        while (av_fifo_size(ost->muxing_queue)) {
            AVPacket pkt;
            av_fifo_generic_read(ost->muxing_queue, &pkt, sizeof(pkt), NULL);
            ret = write_packet(of, &pkt, ost, 1);
            if (ret < 0)
                return ret;
        }
    }

    return 0;
}

static int init_output_stream_encode(OutputStream *ost)
{
    InputStream *ist = get_input_stream(ost);
    AVCodecContext *enc_ctx = ost->enc_ctx;
    AVCodecContext *dec_ctx = NULL;
    AVFormatContext *oc = output_files[ost->file_index]->ctx;
    int j, ret;

    set_encoder_id(output_files[ost->file_index], ost);

    // Muxers use AV_PKT_DATA_DISPLAYMATRIX to signal rotation. On the other
    // hand, the legacy API makes demuxers set "rotate" metadata entries,
    // which have to be filtered out to prevent leaking them to output files.
    av_dict_set(&ost->st->metadata, "rotate", NULL, 0);

    if (ist) {
        //ost->st->disposition          = ist->st->disposition;
        dec_ctx = ist->dec_ctx;
        enc_ctx->chroma_sample_location = dec_ctx->chroma_sample_location;
    }
    // else { // ❌
    //     for (j = 0; j < oc->nb_streams; j++) {
    //         AVStream *st = oc->streams[j];
    //         if (st != ost->st && st->codecpar->codec_type == ost->st->codecpar->codec_type)
    //             break;
    //     }
    //     if (j == oc->nb_streams)
    //         if (ost->st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO ||
    //             ost->st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO)
    //             ost->st->disposition = AV_DISPOSITION_DEFAULT;
    // }

    if (enc_ctx->codec_type == AVMEDIA_TYPE_VIDEO) {
        if (!ost->frame_rate.num) {
            // ost->frame_rate = av_buffersink_get_frame_rate(ost->filter->filter);
            ost->frame_rate.num = 24000;
            ost->frame_rate.den = 1001;
        }
        if (ist && !ost->frame_rate.num)
            ost->frame_rate = ist->framerate;
        if (ist && !ost->frame_rate.num)
            ost->frame_rate = ist->st->r_frame_rate;
        // if (ist && !ost->frame_rate.num) { // ❌ frame_rate: {num 24000, den 1001}
        //     ost->frame_rate = (AVRational){25, 1};
        //     av_log(NULL, AV_LOG_WARNING,
        //            "No information about the input framerate is available. Falling back to a default value of"
        //            "25fps for output stream #%d:%d. Use the -r option if you want a different framerate.\n",
        //            ost->file_index, ost->index);
        // }
//      ost->frame_rate = ist->st->avg_frame_rate.num ? ist->st->avg_frame_rate : (AVRational){25, 1};
        //if (ost->enc->supported_framerates && !ost->force_fps) { // ❌ supported_framerates NULL, force_fps 0
        //    int idx = av_find_nearest_q_idx(ost->frame_rate, ost->enc->supported_framerates);
        //    ost->frame_rate = ost->enc->supported_framerates[idx];
        //}
        /* reduce frame rate for mpeg4 to be within the spec limits */
//        if (enc_ctx->codec_id == AV_CODEC_ID_MPEG4) {
//            av_reduce(&ost->frame_rate.num, &ost->frame_rate.den,
//                      ost->frame_rate.num, ost->frame_rate.den, 65535);
//        }
    }

    switch (enc_ctx->codec_type) {
    case AVMEDIA_TYPE_AUDIO:
        enc_ctx->sample_fmt     = av_buffersink_get_format(ost->filter->filter);
        if (dec_ctx)
            enc_ctx->bits_per_raw_sample = FFMIN(dec_ctx->bits_per_raw_sample,
                                                 av_get_bytes_per_sample(enc_ctx->sample_fmt) << 3);
        enc_ctx->sample_rate    = av_buffersink_get_sample_rate(ost->filter->filter);
        enc_ctx->channel_layout = av_buffersink_get_channel_layout(ost->filter->filter);
        enc_ctx->channels       = av_buffersink_get_channels(ost->filter->filter);

        init_encoder_time_base(ost, av_make_q(1, enc_ctx->sample_rate));
        break;

    case AVMEDIA_TYPE_VIDEO:
        init_encoder_time_base(ost, av_inv_q(ost->frame_rate));

#if 0
        if (!(enc_ctx->time_base.num && enc_ctx->time_base.den))
            enc_ctx->time_base = av_buffersink_get_time_base(ost->filter->filter);
        if (av_q2d(enc_ctx->time_base) < 0.001 && video_sync_method != VSYNC_PASSTHROUGH
           && (video_sync_method == VSYNC_CFR || video_sync_method == VSYNC_VSCFR || (video_sync_method == VSYNC_AUTO && !(oc->oformat->flags & AVFMT_VARIABLE_FPS)))){
            av_log(oc, AV_LOG_WARNING, "Frame rate very high for a muxer not efficiently supporting it.\n"
                                       "Please consider specifying a lower framerate, a different muxer or -vsync 2\n");
        }
        for (j = 0; j < ost->forced_kf_count; j++)
            ost->forced_kf_pts[j] = av_rescale_q(ost->forced_kf_pts[j], AV_TIME_BASE_Q, enc_ctx->time_base);
#endif

        enc_ctx->width  = 1920; //av_buffersink_get_w(ost->filter->filter);
        enc_ctx->height = 1080; //av_buffersink_get_h(ost->filter->filter);
        enc_ctx->sample_aspect_ratio = ost->st->sample_aspect_ratio = (AVRational){.num=1, .den=1};
            // av_buffersink_get_sample_aspect_ratio(ost->filter->filter);
            //ost->filter->filter->inputs[0]->sample_aspect_ratio;

        enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P; // av_buffersink_get_format(ost->filter->filter);
        // if (dec_ctx) // no effect
        //    enc_ctx->bits_per_raw_sample = FFMIN(dec_ctx->bits_per_raw_sample, av_pix_fmt_desc_get(enc_ctx->pix_fmt)->comp[0].depth);

        enc_ctx->framerate = ost->frame_rate;

        ost->st->avg_frame_rate = ost->frame_rate;

        // if (!dec_ctx || enc_ctx->width   != dec_ctx->width  || enc_ctx->height  != dec_ctx->height ||
            // enc_ctx->pix_fmt != dec_ctx->pix_fmt) { .. }

        // if (ost->forced_keyframes) { ... }
        break;

    case AVMEDIA_TYPE_SUBTITLE:
    case AVMEDIA_TYPE_DATA:
        av_log(NULL, AV_LOG_ERROR, ">>> NOT Supported: AVMEDIA_TYPE_SUBTITILE & TYPE_DATA");
        break;
    default:
        abort();
        break;
    }

    ost->mux_timebase = enc_ctx->time_base;

    return 0;
}

static int init_output_stream(OutputStream *ost, char *error, int error_len)
{
    int ret = 0;

    /* 另一套无须 encode 的 stream_copy 逻辑，此处只保留 encoding_needed 的逻辑 */
    //if (!ost->encoding_needed && ost->stream_copy) { ... } else {

    AVCodec      *codec = ost->enc;
    AVCodecContext *dec = NULL;
    InputStream *ist;

    ret = init_output_stream_encode(ost);
    if (ret < 0)
        return ret;

    if ((ist = get_input_stream(ost)))
        dec = ist->dec_ctx;

    if (!av_dict_get(ost->encoder_opts, "threads", NULL, 0))
        av_dict_set(&ost->encoder_opts, "threads", "auto", 0);

    if ((ret = avcodec_open2(ost->enc_ctx, codec, &ost->encoder_opts)) < 0) {
        snprintf(error, error_len,
                    "Error while opening encoder for output stream #%d:%d - "
                    "maybe incorrect parameters such as bit_rate, rate, width or height",
                ost->file_index, ost->index);
        return ret;
    }

    if (ost->enc->type == AVMEDIA_TYPE_AUDIO && !(ost->enc->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE))
        av_buffersink_set_frame_size(ost->filter->filter, ost->enc_ctx->frame_size);

    assert_avoptions(ost->encoder_opts);
    if (ost->enc_ctx->bit_rate && ost->enc_ctx->bit_rate < 1000)
        av_log(NULL, AV_LOG_WARNING, "The bitrate parameter is set too low. It takes bits/s as arg, not kbits/s\n");

    ret = avcodec_parameters_from_context(ost->st->codecpar, ost->enc_ctx); // ✳️
    if (ret < 0) {
        av_log(NULL, AV_LOG_FATAL, "Error initializing the output stream codec context.\n");
        exit_program(1);
    }

    // copy timebase while removing common factors
    if (ost->st->time_base.num <= 0 || ost->st->time_base.den <= 0)
        ost->st->time_base = av_add_q(ost->enc_ctx->time_base, (AVRational){0, 1});

    // copy estimated duration as a hint to the muxer
    if (ost->st->duration <= 0 && ist && ist->st->duration > 0)
        ost->st->duration = av_rescale_q(ist->st->duration, ist->st->time_base, ost->st->time_base);


    ost->initialized = 1;

    ret = check_init_output_file(output_files[ost->file_index], ost->file_index);
    if (ret < 0)
        return ret;

    return ret;
}


int init_opt_ctx_and_open_output_file(const char* out_filepath)
{
    #define ARG_C 7
    const int argc = ARG_C;
    const char* argv[ARG_C] = {
        "",
        "-c:v", "h264",
        "-c:a", "aac",
        "-y"
    };
    argv[ARG_C-1] = out_filepath;

    OptionParseContext octx;
    char error[128];
    int ret;

    memset(&octx, 0, sizeof(octx));

    /* split the commandline into an internal representation */
    ret = split_commandline(&octx, argc, argv, options, groups, FF_ARRAY_ELEMS(groups));
    if (ret < 0) {
        av_log(NULL, AV_LOG_FATAL, "Error splitting the argument list: ");
        goto fail;
    }

    /* apply global options */
    ret = parse_optgroup(NULL, &octx.global_opts);
    if (ret < 0) {
        av_log(NULL, AV_LOG_FATAL, "Error parsing global options: ");
        goto fail;
    }

    /* configure terminal and setup signal handlers */
    // term_init();

    /* open input files */
    // ret = open_files(&octx.groups[GROUP_INFILE], "input", open_input_file);
    // if (ret < 0) {
    //     av_log(NULL, AV_LOG_FATAL, "Error opening input files: ");
    //     goto fail;
    // }

    /* create the complex filtergraphs */
    // ret = init_complex_filters(); // nb_filtergraphs = 0 for input
    // if (ret < 0) {
    //     av_log(NULL, AV_LOG_FATAL, "Error initializing complex filters.\n");
    //     goto fail;
    // }

    /* open output files */
    ret = open_files(&octx.groups[GROUP_OUTFILE], "output", open_output_file); // o.start_time
    if (ret < 0) {
        av_log(NULL, AV_LOG_FATAL, "Error opening output files: ");
        goto fail;
    }

    check_filter_outputs(); // nb_filtergraphs = 2 for output

    for (int i=0; i<nb_output_streams; i++) {
        OutputStream *ost = output_streams[i];
        if (!ost->initialized) {
            char error[1024] = "";
            ret = init_output_stream(ost, error, sizeof(error));
            if (ret < 0) {
                av_log(NULL, AV_LOG_ERROR, "Error initializing output stream %d:%d -- %s\n",
                        ost->file_index, ost->index, error);
                exit_program(1);
            }
        }
    }

fail:
    uninit_parse_context(&octx);
    if (ret < 0) {
        av_strerror(ret, error, sizeof(error));
        av_log(NULL, AV_LOG_FATAL, "%s\n", error);
    }
    return ret;
}

// from ffmpeg_opt.c
static int file_overwrite     = 0;

#define OFFSET(x) offsetof(OptionsContext, x)
const OptionDef options[] = {
    /* main options */
    //CMDUTILS_COMMON_OPTIONS
    { "y",              OPT_BOOL,                                    {              &file_overwrite },
        "overwrite output files" },
    { "c",              HAS_ARG | OPT_STRING | OPT_SPEC |
                        OPT_INPUT | OPT_OUTPUT,                      { .off       = OFFSET(codec_names) },
        "codec name", "codec" },

    /* video options */
    { NULL, },
};
