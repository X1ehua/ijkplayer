#ifndef FF_RECORD_H
#define FF_RECORD_H

#include "ffmpeg.h"

int write_packet(OutputFile *of, AVPacket *pkt, OutputStream *ost, int unqueue);
int do_video_out(OutputFile *of, OutputStream *ost, AVFrame *next_picture); //, double sync_ipts);

//int check_recording_time(OutputStream *ost);
//void close_all_output_streams(OutputStream *ost, OSTFinished this_stream, OSTFinished others);
//void close_output_stream(OutputStream *ost);

#endif
