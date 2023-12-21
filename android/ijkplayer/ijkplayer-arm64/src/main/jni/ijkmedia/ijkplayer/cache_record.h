#ifndef CACHE_RECORD_H
#define CACHE_RECORD_H

#include "ff_ffplay_def.h"

void cache_samp_frame(VideoState *is, const Uint8 *buffer, int buffer_size);
void cache_pict_frame(VideoState *is, const AVFrame *frame, float fps);

#endif
