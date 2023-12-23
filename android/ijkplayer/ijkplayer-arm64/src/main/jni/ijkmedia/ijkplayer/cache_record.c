#include "cache_record.h"
#include "ff_ffplay.h"

long get_microsec_timestamp()
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == -1) {
        ALOGE("clock_gettime() failed in %s(), returned 0L as current timestamp.", __FUNCTION__);
        return 0L;
    }
    return ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

void cache_pict_frame(VideoState *is, const AVFrame *frame, float fps)
{
    pthread_mutex_lock(&is->record_cache.mutex);

    RecordCache *rc = &is->record_cache;
    long now_microsec = get_microsec_timestamp();
    if (!rc->pict_fifo) {
        //rc->max_duration = 1000000 * 5; // 5 秒的值待由 initRecordCache() 传入
        //rc->bottom_pts = 0;
        //rc->origin_pts = now_microsec;  // audio FPS 比 video 高很多，所以这些共用属性我们放在 audio 的部分初始化
        rc->width  = frame->width;
        rc->height = frame->height;
        int frame_num = ceil(fps * rc->max_duration / 1000000);
        unsigned int size = frame_num * sizeof(PictFrame);
        rc->pict_fifo = av_fifo_alloc(size);
        ALOGI(">> pict_fifo: frame_num %d", frame_num);
    }

    PictFrame pict_frame;
    pict_frame.pts = now_microsec - rc->origin_pts;
    static bool logged = false;
    if (!logged) {
        ALOGI(">> first pict_frame.pts: %d", pict_frame.pts);
        logged = true;
    }

    if (pict_frame.pts - rc->bottom_pts >= rc->max_duration) {
        PictFrame drain_pict_frame;
        av_fifo_generic_read(rc->pict_fifo, &drain_pict_frame, sizeof(PictFrame), NULL);
        rc->bottom_pts = drain_pict_frame.pts;
        free(drain_pict_frame.dataY); // TODO: 这些 dataY|U|V 放入回收站里待后面复用，减少 free/malloc
        free(drain_pict_frame.dataU);
        free(drain_pict_frame.dataV);
    }

    int space = av_fifo_space(rc->pict_fifo);
    if (space < sizeof(PictFrame) * 10) {
        av_fifo_grow(rc->pict_fifo, sizeof(PictFrame) * 10);
        ALOGI(">> av_fifo_grow(pict_fifo, PictFrame * %d)", 10);
    }

    int sizeY = frame->width * frame->height;
    pict_frame.dataY = (Uint8 *)malloc(sizeY);
    pict_frame.dataU = (Uint8 *)malloc(sizeY/4);
    pict_frame.dataV = (Uint8 *)malloc(sizeY/4);
    memcpy(pict_frame.dataY, frame->data[0], sizeY);
    memcpy(pict_frame.dataU, frame->data[1], sizeY/4);
    memcpy(pict_frame.dataV, frame->data[2], sizeY/4);

    av_fifo_generic_write(rc->pict_fifo, &pict_frame, sizeof(PictFrame), NULL);

    pthread_mutex_unlock(&is->record_cache.mutex);
}

void cache_samp_frame(VideoState *is, const Uint8 *buffer, int buffer_size)
{
//#define Initiative_Offer
//#define DEBUG_BUFF_COUNTER

#ifdef DEBUG_BUFF_COUNTER
    static int bc = 0;
    bc++;
    buffer[0] = bc & 0xff;
    buffer[1] = (bc & 0xff00) >> 8;
#endif

#ifdef Initiative_Offer
    /* 通过 JNI 将此 stream 数据 offer 到 java 侧 ArrayBlockingQueue 里面 */
    if (is->audio_sample_offer_callback) {
        is->audio_sample_offer_callback(buffer, buffer_size);
    }

#else
    pthread_mutex_lock(&is->record_cache.mutex);

# ifdef DEBUG_BUFF_COUNTER
    static int   bc_index  = 0;
    static short bc_arr[NB_SAMP_BUFFS] = {0};
    bc_arr[bc_index++] = bc;
    if (bc_index == NB_SAMP_BUFFS) {
        ALOGE(">> buff-counter #1: %d %d %d %d %d %d %d %d %d %d", // NB_SAMP_BUFFS: 10
              bc_arr[0], bc_arr[1], bc_arr[2], bc_arr[3], bc_arr[4], bc_arr[5], bc_arr[6], bc_arr[7], bc_arr[8], bc_arr[9]);
        bc_index = 0;
    }
# endif

    const int SAMPLE_FPS = 96; // 粗略的参考值，只用于初始化。不够用则会 realloc
    RecordCache *rc = &is->record_cache;
    long now_microsec = get_microsec_timestamp();
    if (!rc->samp_fifo) { // 初始化 audio 部分
        rc->max_duration = 1000000 * 3; // 5 秒的值待由 initRecordCache() 传入
        rc->bottom_pts = 0;
        rc->origin_pts = now_microsec;  // audio FPS 比 video 高很多，所以这些共用属性我们放在 audio 的部分初始化

        unsigned int size = (SAMPLE_FPS * rc->max_duration / 1000000) * sizeof(SampFrame);
        rc->samp_fifo = av_fifo_alloc(size);
        ALOGI(">> samp_fifo initialized, size %d", size);
    }

    SampFrame samp_frame;
    samp_frame.pts = now_microsec - rc->origin_pts;
    // if (bc < 30) {
    //     ALOGD("%d %d", bc, (int)samp_frame.pts);
    // }

    if (samp_frame.pts - rc->bottom_pts >= rc->max_duration) {
        SampFrame drain_samp_frame;
        av_fifo_generic_read(rc->samp_fifo, &drain_samp_frame, sizeof(SampFrame), NULL);
        rc->bottom_pts = drain_samp_frame.pts;
    }

    int space = av_fifo_space(rc->samp_fifo);
    if (space < sizeof(SampFrame) * 10) {
        av_fifo_grow(rc->samp_fifo, sizeof(SampFrame) * 10);
        ALOGI(">> av_fifo_grow(samp_fifo, SampFrame * %d)", 10);
    }

    memcpy(samp_frame.samp_data, buffer, buffer_size); // TODO: buffer_size(2048) 必须等于 SAMP_BUFF_SIZE
    av_fifo_generic_write(rc->samp_fifo, &samp_frame, sizeof(SampFrame), NULL);

    pthread_mutex_unlock(&is->record_cache.mutex);
#endif
}

void free_record_cache(RecordCache *rc)
{
    pthread_mutex_lock(&rc->mutex);
    if (rc->samp_fifo) {
        av_fifo_free(rc->samp_fifo);
    }

    PictFrame *pict_frames = NULL;
    int size = 0;
    if (rc->pict_fifo) {
        size = av_fifo_size(rc->pict_fifo);
        pict_frames = (PictFrame *)malloc(size);
        av_fifo_generic_peek(rc->pict_fifo, pict_frames, size, NULL);
        av_fifo_free(rc->pict_fifo);
    }
    pthread_mutex_unlock(&rc->mutex);

    if (!pict_frames)
        return;

    for (int i=0; i < size / sizeof(PictFrame); ++i) {
        PictFrame *pict_frame = &pict_frames[i];
        free(pict_frame->dataY);
        free(pict_frame->dataU);
        free(pict_frame->dataV);
    }
    free(pict_frames);
}