/*
 * ijkplayer.c
 *
 * Copyright (c) 2013 Bilibili
 * Copyright (c) 2013 Zhang Rui <bbcallen@gmail.com>
 *
 * This file is part of ijkPlayer.
 *
 * ijkPlayer is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * ijkPlayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with ijkPlayer; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "ijkplayer.h"
#include "ijkplayer_internal.h"
#include "ijkversion.h"

#define MP_RET_IF_FAILED(ret) \
    do { \
        int retval = ret; \
        if (retval != 0) return (retval); \
    } while(0)

#define MPST_RET_IF_EQ_INT(real, expected, errcode) \
    do { \
        if ((real) == (expected)) return (errcode); \
    } while(0)

#define MPST_RET_IF_EQ(real, expected) \
    MPST_RET_IF_EQ_INT(real, expected, EIJK_INVALID_STATE)

inline static void ijkmp_destroy(IjkMediaPlayer *mp)
{
    if (!mp)
        return;

    ffp_destroy_p(&mp->ffplayer);
    if (mp->msg_thread) {
        SDL_WaitThread(mp->msg_thread, NULL);
        mp->msg_thread = NULL;
    }

    pthread_mutex_destroy(&mp->mutex);

    freep((void**)&mp->data_source);
    memset(mp, 0, sizeof(IjkMediaPlayer));
    freep((void**)&mp);
}

inline static void ijkmp_destroy_p(IjkMediaPlayer **pmp)
{
    if (!pmp)
        return;

    ijkmp_destroy(*pmp);
    *pmp = NULL;
}

void ijkmp_global_init()
{
    ffp_global_init();
}

void ijkmp_global_uninit()
{
    ffp_global_uninit();
}

void ijkmp_global_set_log_report(int use_report)
{
    ffp_global_set_log_report(use_report);
}

void ijkmp_global_set_log_level(int log_level)
{
    ffp_global_set_log_level(log_level);
}

void ijkmp_global_set_inject_callback(ijk_inject_callback cb)
{
    ffp_global_set_inject_callback(cb);
}

const char *ijkmp_version()
{
    return IJKPLAYER_VERSION;
}

void ijkmp_io_stat_register(void (*cb)(const char *url, int type, int bytes))
{
    ffp_io_stat_register(cb);
}

void ijkmp_io_stat_complete_register(void (*cb)(const char *url,
                                                int64_t read_bytes, int64_t total_size,
                                                int64_t elpased_time, int64_t total_duration))
{
    ffp_io_stat_complete_register(cb);
}

void ijkmp_change_state_l(IjkMediaPlayer *mp, int new_state)
{
    mp->mp_state = new_state;
    ffp_notify_msg1(mp->ffplayer, FFP_MSG_PLAYBACK_STATE_CHANGED);
}

IjkMediaPlayer *ijkmp_create(int (*msg_loop)(void*))
{
    IjkMediaPlayer *mp = (IjkMediaPlayer *) mallocz(sizeof(IjkMediaPlayer));
    if (!mp)
        goto fail;

    mp->ffplayer = ffp_create();
    if (!mp->ffplayer)
        goto fail;

    mp->msg_loop = msg_loop;

    ijkmp_inc_ref(mp);
    pthread_mutex_init(&mp->mutex, NULL);

    return mp;

    fail:
    ijkmp_destroy_p(&mp);
    return NULL;
}

void *ijkmp_set_inject_opaque(IjkMediaPlayer *mp, void *opaque)
{
    assert(mp);

    // MPTRACE("%s(%p)\n", __func__, opaque);
    void *prev_weak_thiz = ffp_set_inject_opaque(mp->ffplayer, opaque);
    // MPTRACE("%s()=void\n", __func__);
    return prev_weak_thiz;
}

void ijkmp_set_frame_at_time(IjkMediaPlayer *mp, const char *path, int64_t start_time, int64_t end_time, int num, int definition)
{
    assert(mp);

    MPTRACE("%s(%s,%lld,%lld,%d,%d)\n", __func__, path, (long long int)start_time, (long long int)end_time, num, definition);
    ffp_set_frame_at_time(mp->ffplayer, path, start_time, end_time, num, definition);
    MPTRACE("%s()=void\n", __func__);
}


void *ijkmp_set_ijkio_inject_opaque(IjkMediaPlayer *mp, void *opaque)
{
    assert(mp);

    // MPTRACE("%s(%p)\n", __func__, opaque);
    void *prev_weak_thiz = ffp_set_ijkio_inject_opaque(mp->ffplayer, opaque);
    // MPTRACE("%s()=void\n", __func__);
    return prev_weak_thiz;
}

void ijkmp_set_option(IjkMediaPlayer *mp, int opt_category, const char *name, const char *value)
{
    assert(mp);

    // MPTRACE("%s(%s, %s)\n", __func__, name, value);
    pthread_mutex_lock(&mp->mutex);
    ffp_set_option(mp->ffplayer, opt_category, name, value);
    pthread_mutex_unlock(&mp->mutex);
    // MPTRACE("%s()=void\n", __func__);
}

void ijkmp_set_option_int(IjkMediaPlayer *mp, int opt_category, const char *name, int64_t value)
{
    assert(mp);

    // MPTRACE("%s(%s, %"PRId64")\n", __func__, name, value);
    pthread_mutex_lock(&mp->mutex);
    ffp_set_option_int(mp->ffplayer, opt_category, name, value);
    pthread_mutex_unlock(&mp->mutex);
    // MPTRACE("%s()=void\n", __func__);
}

int ijkmp_get_video_codec_info(IjkMediaPlayer *mp, char **codec_info)
{
    assert(mp);

    MPTRACE("%s\n", __func__);
    pthread_mutex_lock(&mp->mutex);
    int ret = ffp_get_video_codec_info(mp->ffplayer, codec_info);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("%s()=void\n", __func__);
    return ret;
}

int ijkmp_get_audio_codec_info(IjkMediaPlayer *mp, char **codec_info)
{
    assert(mp);

    MPTRACE("%s\n", __func__);
    pthread_mutex_lock(&mp->mutex);
    int ret = ffp_get_audio_codec_info(mp->ffplayer, codec_info);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("%s()=void\n", __func__);
    return ret;
}

void ijkmp_set_playback_rate(IjkMediaPlayer *mp, float rate)
{
    assert(mp);

    MPTRACE("%s(%f)\n", __func__, rate);
    pthread_mutex_lock(&mp->mutex);
    ffp_set_playback_rate(mp->ffplayer, rate);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("%s()=void\n", __func__);
}

void ijkmp_set_playback_volume(IjkMediaPlayer *mp, float volume)
{
    assert(mp);

    MPTRACE("%s(%f)\n", __func__, volume);
    pthread_mutex_lock(&mp->mutex);
    ffp_set_playback_volume(mp->ffplayer, volume);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("%s()=void\n", __func__);
}

int ijkmp_set_stream_selected(IjkMediaPlayer *mp, int stream, int selected)
{
    assert(mp);

    MPTRACE("%s(%d, %d)\n", __func__, stream, selected);
    pthread_mutex_lock(&mp->mutex);
    int ret = ffp_set_stream_selected(mp->ffplayer, stream, selected);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("%s(%d, %d)=%d\n", __func__, stream, selected, ret);
    return ret;
}

float ijkmp_get_property_float(IjkMediaPlayer *mp, int id, float default_value)
{
    assert(mp);

    pthread_mutex_lock(&mp->mutex);
    float ret = ffp_get_property_float(mp->ffplayer, id, default_value);
    pthread_mutex_unlock(&mp->mutex);
    return ret;
}

void ijkmp_set_property_float(IjkMediaPlayer *mp, int id, float value)
{
    assert(mp);

    pthread_mutex_lock(&mp->mutex);
    ffp_set_property_float(mp->ffplayer, id, value);
    pthread_mutex_unlock(&mp->mutex);
}

int64_t ijkmp_get_property_int64(IjkMediaPlayer *mp, int id, int64_t default_value)
{
    assert(mp);

    pthread_mutex_lock(&mp->mutex);
    int64_t ret = ffp_get_property_int64(mp->ffplayer, id, default_value);
    pthread_mutex_unlock(&mp->mutex);
    return ret;
}

void ijkmp_set_property_int64(IjkMediaPlayer *mp, int id, int64_t value)
{
    assert(mp);

    pthread_mutex_lock(&mp->mutex);
    ffp_set_property_int64(mp->ffplayer, id, value);
    pthread_mutex_unlock(&mp->mutex);
}

IjkMediaMeta *ijkmp_get_meta_l(IjkMediaPlayer *mp)
{
    assert(mp);

    MPTRACE("%s\n", __func__);
    IjkMediaMeta *ret = ffp_get_meta_l(mp->ffplayer);
    MPTRACE("%s()=void\n", __func__);
    return ret;
}

void ijkmp_shutdown_l(IjkMediaPlayer *mp)
{
    assert(mp);

    MPTRACE("ijkmp_shutdown_l()\n");
    if (mp->ffplayer) {
        ffp_stop_l(mp->ffplayer);
        ffp_wait_stop_l(mp->ffplayer);
    }
    MPTRACE("ijkmp_shutdown_l()=void\n");
}

void ijkmp_shutdown(IjkMediaPlayer *mp)
{
    return ijkmp_shutdown_l(mp);
}

void ijkmp_inc_ref(IjkMediaPlayer *mp)
{
    assert(mp);
    __sync_fetch_and_add(&mp->ref_count, 1);
}

void ijkmp_dec_ref(IjkMediaPlayer *mp)
{
    if (!mp)
        return;

    int ref_count = __sync_sub_and_fetch(&mp->ref_count, 1);
    if (ref_count == 0) {
        MPTRACE("ijkmp_dec_ref(): ref=0\n");
        ijkmp_shutdown(mp);
        ijkmp_destroy_p(&mp);
    }
}

void ijkmp_dec_ref_p(IjkMediaPlayer **pmp)
{
    if (!pmp)
        return;

    ijkmp_dec_ref(*pmp);
    *pmp = NULL;
}

static int ijkmp_set_data_source_l(IjkMediaPlayer *mp, const char *url)
{
    assert(mp);
    assert(url);

    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_IDLE);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_INITIALIZED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_ASYNC_PREPARING);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_PREPARED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_STARTED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_PAUSED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_COMPLETED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_STOPPED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_ERROR);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_END);

    freep((void**)&mp->data_source);
    mp->data_source = strdup(url);
    if (!mp->data_source)
        return EIJK_OUT_OF_MEMORY;

    ijkmp_change_state_l(mp, MP_STATE_INITIALIZED);
    return 0;
}

int ijkmp_set_data_source(IjkMediaPlayer *mp, const char *url)
{
    assert(mp);
    assert(url);
    // MPTRACE("ijkmp_set_data_source(url=\"%s\")\n", url);
    pthread_mutex_lock(&mp->mutex);
    int retval = ijkmp_set_data_source_l(mp, url);
    pthread_mutex_unlock(&mp->mutex);
    // MPTRACE("ijkmp_set_data_source(url=\"%s\")=%d\n", url, retval);
    return retval;
}

static int ijkmp_msg_loop(void *arg)
{
    IjkMediaPlayer *mp = arg;
    int ret = mp->msg_loop(arg);
    return ret;
}

static int ijkmp_prepare_async_l(IjkMediaPlayer *mp)
{
    assert(mp);

    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_IDLE);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_INITIALIZED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_ASYNC_PREPARING);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_PREPARED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_STARTED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_PAUSED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_COMPLETED);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_STOPPED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_ERROR);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_END);

    assert(mp->data_source);

    ijkmp_change_state_l(mp, MP_STATE_ASYNC_PREPARING);

    msg_queue_start(&mp->ffplayer->msg_queue);

    // released in msg_loop
    ijkmp_inc_ref(mp);
    mp->msg_thread = SDL_CreateThreadEx(&mp->_msg_thread, ijkmp_msg_loop, mp, "ff_msg_loop");
    // msg_thread is detached inside msg_loop
    // TODO: 9 release weak_thiz if pthread_create() failed;

    int retval = ffp_prepare_async_l(mp->ffplayer, mp->data_source);
    if (retval < 0) {
        ijkmp_change_state_l(mp, MP_STATE_ERROR);
        return retval;
    }

    return 0;
}

int ijkmp_prepare_async(IjkMediaPlayer *mp)
{
    assert(mp);
    // MPTRACE("ijkmp_prepare_async()\n");
    pthread_mutex_lock(&mp->mutex);
    int retval = ijkmp_prepare_async_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    // MPTRACE("ijkmp_prepare_async()=%d\n", retval);
    return retval;
}

static int ikjmp_chkst_start_l(int mp_state)
{
    MPST_RET_IF_EQ(mp_state, MP_STATE_IDLE);
    MPST_RET_IF_EQ(mp_state, MP_STATE_INITIALIZED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_ASYNC_PREPARING);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_PREPARED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_STARTED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_PAUSED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_COMPLETED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_STOPPED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_ERROR);
    MPST_RET_IF_EQ(mp_state, MP_STATE_END);

    return 0;
}

static int ijkmp_start_l(IjkMediaPlayer *mp)
{
    assert(mp);

    MP_RET_IF_FAILED(ikjmp_chkst_start_l(mp->mp_state));

    ffp_remove_msg(mp->ffplayer, FFP_REQ_START);
    ffp_remove_msg(mp->ffplayer, FFP_REQ_PAUSE);
    ffp_notify_msg1(mp->ffplayer, FFP_REQ_START);

    return 0;
}

int ijkmp_start(IjkMediaPlayer *mp)
{
    assert(mp);
    // MPTRACE("ijkmp_start()\n");
    pthread_mutex_lock(&mp->mutex);
    int retval = ijkmp_start_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    // MPTRACE("ijkmp_start()=%d\n", retval);
    return retval;
}

static int ikjmp_chkst_pause_l(int mp_state)
{
    MPST_RET_IF_EQ(mp_state, MP_STATE_IDLE);
    MPST_RET_IF_EQ(mp_state, MP_STATE_INITIALIZED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_ASYNC_PREPARING);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_PREPARED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_STARTED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_PAUSED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_COMPLETED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_STOPPED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_ERROR);
    MPST_RET_IF_EQ(mp_state, MP_STATE_END);

    return 0;
}

static int ijkmp_pause_l(IjkMediaPlayer *mp)
{
    assert(mp);

    MP_RET_IF_FAILED(ikjmp_chkst_pause_l(mp->mp_state));

    ffp_remove_msg(mp->ffplayer, FFP_REQ_START);
    ffp_remove_msg(mp->ffplayer, FFP_REQ_PAUSE);
    ffp_notify_msg1(mp->ffplayer, FFP_REQ_PAUSE);

    return 0;
}

int ijkmp_pause(IjkMediaPlayer *mp)
{
    assert(mp);
    MPTRACE("ijkmp_pause()\n");
    pthread_mutex_lock(&mp->mutex);
    int retval = ijkmp_pause_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("ijkmp_pause()=%d\n", retval);
    return retval;
}

static int ijkmp_stop_l(IjkMediaPlayer *mp)
{
    assert(mp);

    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_IDLE);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_INITIALIZED);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_ASYNC_PREPARING);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_PREPARED);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_STARTED);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_PAUSED);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_COMPLETED);
    // MPST_RET_IF_EQ(mp->mp_state, MP_STATE_STOPPED);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_ERROR);
    MPST_RET_IF_EQ(mp->mp_state, MP_STATE_END);

    ffp_remove_msg(mp->ffplayer, FFP_REQ_START);
    ffp_remove_msg(mp->ffplayer, FFP_REQ_PAUSE);
    int retval = ffp_stop_l(mp->ffplayer);
    if (retval < 0) {
        return retval;
    }

    ijkmp_change_state_l(mp, MP_STATE_STOPPED);
    return 0;
}

int ijkmp_stop(IjkMediaPlayer *mp)
{
    assert(mp);
    MPTRACE("ijkmp_stop()\n");
    pthread_mutex_lock(&mp->mutex);
    int retval = ijkmp_stop_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("ijkmp_stop()=%d\n", retval);
    return retval;
}

bool ijkmp_is_playing(IjkMediaPlayer *mp)
{
    assert(mp);
    if (mp->mp_state == MP_STATE_PREPARED ||
        mp->mp_state == MP_STATE_STARTED) {
        return true;
    }

    return false;
}

static int ikjmp_chkst_seek_l(int mp_state)
{
    MPST_RET_IF_EQ(mp_state, MP_STATE_IDLE);
    MPST_RET_IF_EQ(mp_state, MP_STATE_INITIALIZED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_ASYNC_PREPARING);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_PREPARED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_STARTED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_PAUSED);
    // MPST_RET_IF_EQ(mp_state, MP_STATE_COMPLETED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_STOPPED);
    MPST_RET_IF_EQ(mp_state, MP_STATE_ERROR);
    MPST_RET_IF_EQ(mp_state, MP_STATE_END);

    return 0;
}

int ijkmp_seek_to_l(IjkMediaPlayer *mp, long msec)
{
    assert(mp);

    MP_RET_IF_FAILED(ikjmp_chkst_seek_l(mp->mp_state));

    mp->seek_req = 1;
    mp->seek_msec = msec;
    ffp_remove_msg(mp->ffplayer, FFP_REQ_SEEK);
    ffp_notify_msg2(mp->ffplayer, FFP_REQ_SEEK, (int)msec);
    // TODO: 9 64-bit long?

    return 0;
}

int ijkmp_seek_to(IjkMediaPlayer *mp, long msec)
{
    assert(mp);
    MPTRACE("ijkmp_seek_to(%ld)\n", msec);
    pthread_mutex_lock(&mp->mutex);
    int retval = ijkmp_seek_to_l(mp, msec);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("ijkmp_seek_to(%ld)=%d\n", msec, retval);

    return retval;
}

int ijkmp_get_state(IjkMediaPlayer *mp)
{
    return mp->mp_state;
}

static long ijkmp_get_current_position_l(IjkMediaPlayer *mp)
{
    if (mp->seek_req)
        return mp->seek_msec;
    return ffp_get_current_position_l(mp->ffplayer);
}

long ijkmp_get_current_position(IjkMediaPlayer *mp)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    long retval;
    if (mp->seek_req)
        retval = mp->seek_msec;
    else
        retval = ijkmp_get_current_position_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    return retval;
}

static long ijkmp_get_duration_l(IjkMediaPlayer *mp)
{
    return ffp_get_duration_l(mp->ffplayer);
}

long ijkmp_get_duration(IjkMediaPlayer *mp)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    long retval = ijkmp_get_duration_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    return retval;
}

static long ijkmp_get_playable_duration_l(IjkMediaPlayer *mp)
{
    return ffp_get_playable_duration_l(mp->ffplayer);
}

long ijkmp_get_playable_duration(IjkMediaPlayer *mp)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    long retval = ijkmp_get_playable_duration_l(mp);
    pthread_mutex_unlock(&mp->mutex);
    return retval;
}

void ijkmp_set_loop(IjkMediaPlayer *mp, int loop)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    ffp_set_loop(mp->ffplayer, loop);
    pthread_mutex_unlock(&mp->mutex);
}

int ijkmp_get_loop(IjkMediaPlayer *mp)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    int loop = ffp_get_loop(mp->ffplayer);
    pthread_mutex_unlock(&mp->mutex);
    return loop;
}

void *ijkmp_get_weak_thiz(IjkMediaPlayer *mp)
{
    return mp->weak_thiz;
}

void *ijkmp_set_weak_thiz(IjkMediaPlayer *mp, void *weak_thiz)
{
    void *prev_weak_thiz = mp->weak_thiz;

    mp->weak_thiz = weak_thiz;

    return prev_weak_thiz;
}

/* need to call msg_free_res for freeing the resouce obtained in msg */
int ijkmp_get_msg(IjkMediaPlayer *mp, AVMessage *msg, int block)
{
    assert(mp);
    while (1) {
        int continue_wait_next_msg = 0;
        int retval = msg_queue_get(&mp->ffplayer->msg_queue, msg, block);
        if (retval <= 0)
            return retval;

        switch (msg->what) {
        case FFP_MSG_PREPARED:
            // MPTRACE("ijkmp_get_msg: FFP_MSG_PREPARED\n");
            pthread_mutex_lock(&mp->mutex);
            if (mp->mp_state == MP_STATE_ASYNC_PREPARING) {
                ijkmp_change_state_l(mp, MP_STATE_PREPARED);
            } else {
                // FIXME: 1: onError() ?
                av_log(mp->ffplayer, AV_LOG_DEBUG, "FFP_MSG_PREPARED: expecting mp_state==MP_STATE_ASYNC_PREPARING\n");
            }
            if (!mp->ffplayer->start_on_prepared) {
                ijkmp_change_state_l(mp, MP_STATE_PAUSED);
            }
            pthread_mutex_unlock(&mp->mutex);
            break;

        case FFP_MSG_COMPLETED:
            // MPTRACE("ijkmp_get_msg: FFP_MSG_COMPLETED\n");

            pthread_mutex_lock(&mp->mutex);
            mp->restart = 1;
            mp->restart_from_beginning = 1;
            ijkmp_change_state_l(mp, MP_STATE_COMPLETED);
            pthread_mutex_unlock(&mp->mutex);
            break;

        case FFP_MSG_SEEK_COMPLETE:
            // MPTRACE("ijkmp_get_msg: FFP_MSG_SEEK_COMPLETE\n");

            pthread_mutex_lock(&mp->mutex);
            mp->seek_req = 0;
            mp->seek_msec = 0;
            pthread_mutex_unlock(&mp->mutex);
            break;

        case FFP_REQ_START:
            // MPTRACE("ijkmp_get_msg: FFP_REQ_START\n");
            continue_wait_next_msg = 1;
            pthread_mutex_lock(&mp->mutex);
            if (0 == ikjmp_chkst_start_l(mp->mp_state)) {
                // FIXME: 8 check seekable
                if (mp->restart) {
                    if (mp->restart_from_beginning) {
                        av_log(mp->ffplayer, AV_LOG_DEBUG, "ijkmp_get_msg: FFP_REQ_START: restart from beginning\n");
                        retval = ffp_start_from_l(mp->ffplayer, 0);
                        if (retval == 0)
                            ijkmp_change_state_l(mp, MP_STATE_STARTED);
                    } else {
                        av_log(mp->ffplayer, AV_LOG_DEBUG, "ijkmp_get_msg: FFP_REQ_START: restart from seek pos\n");
                        retval = ffp_start_l(mp->ffplayer);
                        if (retval == 0)
                            ijkmp_change_state_l(mp, MP_STATE_STARTED);
                    }
                    mp->restart = 0;
                    mp->restart_from_beginning = 0;
                } else {
                    av_log(mp->ffplayer, AV_LOG_DEBUG, "ijkmp_get_msg: FFP_REQ_START: start on fly\n");
                    retval = ffp_start_l(mp->ffplayer);
                    if (retval == 0)
                        ijkmp_change_state_l(mp, MP_STATE_STARTED);
                }
            }
            pthread_mutex_unlock(&mp->mutex);
            break;

        case FFP_REQ_PAUSE:
            MPTRACE("ijkmp_get_msg: FFP_REQ_PAUSE\n");
            continue_wait_next_msg = 1;
            pthread_mutex_lock(&mp->mutex);
            if (0 == ikjmp_chkst_pause_l(mp->mp_state)) {
                int pause_ret = ffp_pause_l(mp->ffplayer);
                if (pause_ret == 0)
                    ijkmp_change_state_l(mp, MP_STATE_PAUSED);
            }
            pthread_mutex_unlock(&mp->mutex);
            break;

        case FFP_REQ_SEEK:
            MPTRACE("ijkmp_get_msg: FFP_REQ_SEEK\n");
            continue_wait_next_msg = 1;

            pthread_mutex_lock(&mp->mutex);
            if (0 == ikjmp_chkst_seek_l(mp->mp_state)) {
                mp->restart_from_beginning = 0;
                if (0 == ffp_seek_to_l(mp->ffplayer, msg->arg1)) {
                    av_log(mp->ffplayer, AV_LOG_DEBUG, "ijkmp_get_msg: FFP_REQ_SEEK: seek to %d\n", (int)msg->arg1);
                }
            }
            pthread_mutex_unlock(&mp->mutex);
            break;
        }
        if (continue_wait_next_msg) {
            msg_free_res(msg);
            continue;
        }

        return retval;
    }

    return -1;
}

#if 0
int ijkmp_start_record(IjkMediaPlayer *mp, const char *file_name)
{
    assert(mp && mp->ffplayer && file_name);
    // MPTRACE("ijkmp_startRecord()\n");
    pthread_mutex_lock(&mp->mutex);
    ALOGW(">>> mp.mutex %p", &mp->mutex);
    // 改为只是向 read_thread 发消息，在 read_thread 里面调用 ffp_start_record()
    // int retval = ffp_start_record(mp->ffplayer, file_name);
    mp->ffplayer->record_scheduled = 1;
    strcpy(mp->ffplayer->record_file, file_name);
    // pthread_t tid = pthread_self();
    pthread_mutex_unlock(&mp->mutex);
    // MPTRACE("ijkmp_startRecord()=%d, tid %ld\n", retval, (intptr_t)tid);
    return 0; // retval;
}

int ijkmp_stop_record(IjkMediaPlayer *mp)
{
    assert(mp);
    MPTRACE("ijkmp_stopRecord()\n");
    pthread_mutex_lock(&mp->mutex);
    int retval = ffp_stop_record(mp->ffplayer);
    pthread_mutex_unlock(&mp->mutex);
    MPTRACE("ijkmp_stopRecord()=%d\n", retval);
    return retval;
}

int ffp_get_video_resolution(FFPlayer *ffp, int *width, int *height)
{
    VideoState *is = ffp->is;
    Frame *vp  = &is->pictq.queue[ is->pictq.rindex ];
    if (!vp || !vp->bmp) {
        ALOGE(">> vp || vp->bmp is null in %s()", __FUNCTION__);
        return -1;
    }

    *width  = vp->bmp->w;
    *height = vp->bmp->h;
    return 0;
}

int ijkmp_get_video_resolution(IjkMediaPlayer *mp, int *width, int *height)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    int ret = ffp_get_video_resolution(mp->ffplayer, width, height);
    pthread_mutex_unlock(&mp->mutex);
    return ret;
}
#endif // 0

int clamp(int x)
{
	if(x <   0) return   0;
	if(x > 255) return 255;
	return x;
}

/* YUV to RGB: http://en.wikipedia.org/wiki/YUV
 * buffRGB 由于是写入 bitmap，所以需要每行 16 个像素对齐
 */
void YV12_to_RGB32_aligned_16(Uint8 *buffRGB, Uint8 **dataYUV, int width, int height)
{
    Uint8 *dataY = dataYUV[0];
    Uint8 *dataU = dataYUV[1];
    Uint8 *dataV = dataYUV[2];

    int x, y, Y, U, V, posRowX0 = 0;
    int padding = (int)(ceil(width / 16.0) * 16) - width;
    ALOGW(">>>>>>>> %s(): padding %d", __FUNCTION__, padding);
    for (y = 0; y < height; y++)
    {
        for (x = 0; x < width; x++)
        {
            Y = dataY[posRowX0 + x];
            U = dataU[(y >> 1) * (width >> 1) + (x >> 1)] - 128;
            V = dataV[(y >> 1) * (width >> 1) + (x >> 1)] - 128;

            *buffRGB++ = clamp(Y + 2.03211 * U);                   // R
            *buffRGB++ = clamp(Y - (0.39466 * U) - (0.58060 * V)); // G
            *buffRGB++ = clamp(Y + 1.13983 * V);                   // B
            buffRGB++;
        }
        posRowX0 += width;
        buffRGB  += padding * 4;
    }
}

void ffp_snapshot(FFPlayer *ffp, Uint8 *frame_buf)
{
    VideoState *is = ffp->is;
    int i = 0;
    while (is->pictq.rindex + i < FRAME_QUEUE_SIZE) {
        int idx = (is->pictq.rindex + i + is->pictq.rindex_shown) % is->pictq.max_size;
        Frame *vp  = &is->pictq.queue[idx];
        if (!vp || !vp->bmp) {
            ALOGW(">>> vp null or vp->bmp null, i %d", i);
            i++;
            continue;
        }
        if (!vp->bmp->pixels || !vp->bmp->pitches) {
            ALOGW(">>> vp->bmp->pixels null or vp->bmp->pitches null, i %d", i);
            i++;
            continue;
        }

        int width  = vp->bmp->w;
        int height = vp->bmp->h;

        // copy data to java Bitmap.pixels
        YV12_to_RGB32_aligned_16(frame_buf, vp->bmp->pixels, width, height);
        return;
    }
}

int ijkmp_snapshot(IjkMediaPlayer *mp, Uint8 *frame_buf)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    ffp_snapshot(mp->ffplayer, frame_buf);
    pthread_mutex_unlock(&mp->mutex);
    return 0;
}

int ffp_copy_YV12_data(FFPlayer *ffp, Uint8 *buff_YV12, int width, int height)
{
    VideoState *is = ffp->is;
    int i = 0;
    while (is->pictq.rindex + i < FRAME_QUEUE_SIZE) {
        int idx = (is->pictq.rindex + i + is->pictq.rindex_shown) % is->pictq.max_size;
        Frame *vp  = &is->pictq.queue[idx];
        if (!vp || !vp->bmp || !vp->bmp->pixels || !vp->bmp->pitches) {
            ALOGE(">>> vp->bmp->pixels null or vp->bmp->pitches null, i %d", i);
            return -1;
        }

        if (vp->bmp->w != width || vp->bmp->h != height) {
            ALOGE(">>> vp->bmp->w|h %d|%d != width|height %d|%d", vp->bmp->w, vp->bmp->h, width, height);
            return -2;
        }

        static void* last_bmp = NULL;
        if (vp->bmp == last_bmp) {
            return -1; // already polled
        }
        last_bmp = vp->bmp;

        Uint8 *dataY = vp->bmp->pixels[0];
        Uint8 *dataU = vp->bmp->pixels[1];
        Uint8 *dataV = vp->bmp->pixels[2];
        size_t sizePlaneY = width * height;

        memcpy(buff_YV12, dataY, sizePlaneY); // Y plane

#if 0   /* COLOR_FormatYUV411Planar */
        memcpy(buff_YV12 + sizePlaneY,   dataU, sizePlaneY / 4); // Y plane
        memcpy(buff_YV12 + sizePlaneY*2, dataV, sizePlaneY / 4); // Y plane
#endif

#if 1   /* COLOR_FormatYUV420SemiPlanar: Y plane + UV plane(UVUVUV) */
        Uint8 *ptrUV = buff_YV12 + sizePlaneY;
        for (int j = 0; j < sizePlaneY / 4; ++j) {
            *ptrUV++ = *dataV++;
            *ptrUV++ = *dataU++;
        }
#endif

        return 0;
    }

    return -1; // should not reach
}

int ijkmp_copy_YV12_data(IjkMediaPlayer *mp, Uint8 *buff_YV12, int width, int height)
{
    assert(mp);
    pthread_mutex_lock(&mp->mutex);
    int ret = ffp_copy_YV12_data(mp->ffplayer, buff_YV12, width, height);
    pthread_mutex_unlock(&mp->mutex);
    return ret;
}

#if 0
int ffp_copy_audio_data(FFPlayer *ffp, Uint8 *buff_sample, int length)
{
    VideoState *is = ffp->is;
    if (!is->samp_queue)
        return -1;

    if (length < is->samp_available_len) {
        ALOGE("ffp_copy_audio_data() >> should not happen: dest buff len %d < samp_available_len %d",
              length, is->samp_available_len);
        return -1;
    }

    pthread_mutex_lock(&is->samp_mutex);
    av_fifo_generic_read(is->samp_queue, buff_sample, is->samp_available_len, NULL);
    av_fifo_drain(is->samp_queue, is->samp_available_len);
    pthread_mutex_unlock(&is->samp_mutex);
    return is->samp_available_len;

    //#if 0
    SampBuffQueue *samp_q = &is->samp_buff_q;
    if (!samp_q->initialized)
        return -1;

    if (length < samp_q->buff_len * NB_SAMP_BUFFS ) {
        ALOGE(">> native_copyAudioData() error: dest buff size %d < %d [samp_buff_q->buff_len %d * NB_SAMP_BUFFS %d]",
            length, samp_q->buff_len * NB_SAMP_BUFFS, samp_q->buff_len, NB_SAMP_BUFFS);
        return -1;
    }

    if (samp_q->buff_stats[ samp_q->rindex ] != SAMP_BUFF_STAT_FILLED) {
        ALOGE(">>> samp_buff_q.buff_stats[ rindex %d ] %d != SAMP_BUFF_STAT_FILLED %d",
              samp_q->rindex, samp_q->buff_stats[ samp_q->rindex ], SAMP_BUFF_STAT_FILLED);
        return -1;
    }

    /* 将 aout_thread 以更高频率调用 sdl_audio_produce_callback() 所填入的 samp_buff_q.buffs 数据
     * poll 到 java 侧交给 MediaCodecEncodeMuxer:
     * 由于 java 侧 EncodeMuxer.doFrame() 的频率要低于 aout_thread 中 audio_produce_callback()
     * 测试数据为 doFrame FPS 约 35.4，而 sdl_audio_produce_callback 的 FPS 约为 93.6
     * 所以，每次在调用本函数时，须把所有状态为 SAMP_BUFF_STAT_FILLED 的 samp_buff_q.buffs 都合并后取走，
     * 然后将这些 buffs 对应的 buff_stat 都置为 SAMP_BUFF_STAT_POLLED
     * 具体又分两种情况:
     * 1. windex > rindex, 则直接从 rindex 读取到 wrindex-1, 然后将这些 buff_stat 都置为 polled
     * 2. windex <=rindex, 则先读取 rindex 到 index = buff_len - 1，再从 index = 0 读到 windex - 1
     */
    int buff_offset = 0;
    if (samp_q->windex > samp_q->rindex) {
        for (int i=samp_q->rindex; i<samp_q->windex; i++) {
            if (samp_q->buff_stats[i] == SAMP_BUFF_STAT_POLLED) {
                ALOGE(">> should not happen: samp_q->buff_stats[%d] %d != SAMP_BUFF_STAT_POLLED %d",
                      i, samp_q->buff_stats[i], SAMP_BUFF_STAT_POLLED);
                return -1;
            }
            memcpy(buff_sample + buff_offset,
                   samp_q->buffs[i],
                   samp_q->buff_len);
            samp_q->buff_stats[i] = SAMP_BUFF_STAT_POLLED;
            buff_offset += samp_q->buff_len;
        }
        samp_q->rindex = samp_q->windex;
        ALOGW(">> buff_offset %d, i %d, r/w-idx %d %d [%d %d %d %d %d %d %d %d]", buff_offset, i, samp_q->rindex, samp_q->windex,
              samp_q->buff_stats[0],
              samp_q->buff_stats[1],
              samp_q->buff_stats[2],
              samp_q->buff_stats[3],
              samp_q->buff_stats[4],
              samp_q->buff_stats[5],
              samp_q->buff_stats[6],
              samp_q->buff_stats[7]
        );
    }
    else {
        for (int i=samp_q->rindex; i<samp_q->buff_len; i++) {
            if (samp_q->buff_stats[i] == SAMP_BUFF_STAT_POLLED) {
                ALOGE(">> should not happen: samp_q->buff_stats[%d] %d != SAMP_BUFF_STAT_POLLED %d",
                      i, samp_q->buff_stats[i], SAMP_BUFF_STAT_POLLED);
                return -1;
            }
            ALOGW(">> buff_offset %d, i %d, r/w-idx %d %d [%d %d %d %d %d %d %d %d]", buff_offset, i, samp_q->rindex, samp_q->windex,
                  samp_q->buff_stats[0],
                  samp_q->buff_stats[1],
                  samp_q->buff_stats[2],
                  samp_q->buff_stats[3],
                  samp_q->buff_stats[4],
                  samp_q->buff_stats[5],
                  samp_q->buff_stats[6],
                  samp_q->buff_stats[7]
            );
            memcpy(buff_sample + buff_offset,
                   samp_q->buffs[i],
                   samp_q->buff_len);
            samp_q->buff_stats[i] = SAMP_BUFF_STAT_POLLED;
            buff_offset += samp_q->buff_len;
        }
        samp_q->rindex = 0;
        for (int i=0; i<samp_q->windex; i++) {
            if (samp_q->buff_stats[i] == SAMP_BUFF_STAT_POLLED) {
                ALOGE(">> should not happen: samp_q->buff_stats[%d] %d != SAMP_BUFF_STAT_POLLED %d",
                      i, samp_q->buff_stats[i], SAMP_BUFF_STAT_POLLED);
                return -1;
            }
            memcpy(buff_sample + buff_offset,
                   samp_q->buffs[i],
                   samp_q->buff_len);
            samp_q->buff_stats[i] = SAMP_BUFF_STAT_POLLED;
            buff_offset += samp_q->buff_len;
        }
    }

    ALOGW(">> buff polled %d, rindex %d, windex %d", buff_offset, samp_q->rindex, samp_q->windex);
    return buff_offset;
}
#endif

int ijkmp_copy_audio_data(IjkMediaPlayer *mp, Uint8 *buff_sample, int length)
{
    assert(mp && mp->ffplayer && mp->ffplayer->is);
    VideoState *is = mp->ffplayer->is;
    if (!is->samp_queue)
        return -1;

    pthread_mutex_lock(&is->samp_mutex);

    if (length < is->samp_available_len) {
        ALOGE("ffp_copy_audio_data() >> should not happen: dest buff len %d < samp_available_len %d",
              length, is->samp_available_len);
        pthread_mutex_unlock(&is->samp_mutex);
        return -1;
    }

    int read_len = is->samp_available_len;

#if 0
    assert(read_len % SAMP_BUFF_SIZE == 0);
    int n = read_len / SAMP_BUFF_SIZE;
    char msg[512] = "";
    for (int i=0; i<n; i++) {
        av_fifo_generic_read(is->samp_queue, buff_sample + i * SAMP_BUFF_SIZE, SAMP_BUFF_SIZE, NULL);
        //av_fifo_drain(is->samp_queue, SAMP_BUFF_SIZE);
        Uint8 *ptr = buff_sample + i * SAMP_BUFF_SIZE;
        int bc = ptr[0] | ptr[1] << 8;
        char tmp[80] = {0};
        sprintf(tmp, " %d", bc);
        strcat(msg, tmp);
    }
    if (n > 0) ALOGI(">> buff-counter #2:%s", msg);

    if (read_len != is->samp_written_len_sum)
        ALOGE(">> native_copyAudioData: read_len %d != %d samp_written_len_sum", read_len, is->samp_written_len_sum);
    is->samp_available_len = 0;
    is->samp_written_len_sum = 0;
#endif

    if (read_len > 0) {
        av_fifo_generic_read(is->samp_queue, buff_sample, read_len, NULL);
        // int sum = 0;                            // <!-- debug
        // for (int i=0; i<read_len; i++)
        //     sum += (signed char)buff_sample[i]; //   --> debug
        // ALOGD(">> fifo_read sum: %d, samp_written N %.1f", sum, is->samp_written_len_sum/2048.0f);

        //av_fifo_drain(is->samp_queue, read_len);
        if (read_len != is->samp_written_len_sum)
            ALOGE(">> native_copyAudioData: read_len %d != %d samp_written_len_sum", read_len, is->samp_written_len_sum);
        is->samp_available_len = 0;
        is->samp_written_len_sum = 0;
    }

    pthread_mutex_unlock(&is->samp_mutex);
    return read_len;
}
