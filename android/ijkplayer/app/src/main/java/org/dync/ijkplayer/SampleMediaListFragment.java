/*
 * Copyright (C) 2015 Bilibili
 * Copyright (C) 2015 Zhang Rui <bbcallen@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dync.ijkplayer;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class SampleMediaListFragment extends Fragment {
    private RecyclerView mRecyclerView;
    private SampleMediaAdapter mAdapter;

    public static SampleMediaListFragment newInstance() {
        SampleMediaListFragment f = new SampleMediaListFragment();
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup viewGroup = (ViewGroup) inflater.inflate(R.layout.fragment_video_url, container, false);
        mRecyclerView = (RecyclerView) viewGroup.findViewById(R.id.recyclerView);
        return viewGroup;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Activity activity = getActivity();

        mAdapter = new SampleMediaAdapter(activity);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mRecyclerView.setAdapter(mAdapter);

        mAdapter.setOnItemClickListener(new SampleMediaAdapter.OnItemClickListener() {
            @Override
            public void OnItemClick(View view, SampleMediaAdapter.SampleMediaItem item, int position) {
                String name = item.mName;
                String url = item.mUrl;
                if(onItemClickListener != null) {
                    onItemClickListener.OnItemClick(activity, url, name);
                }
            }
        });


        for (int i=0; i<URL_LIST.length; i++) {
            mAdapter.addItem(URL_LIST[i][1], URL_LIST[i][0]);
        }
    }

    public static String getDefaultURL() {
        return URL_LIST[0][1];
    }

    final static String URL_LIST[][] = {
            {"0", "rtmp://192.168.2.8:2023/vod/b01.mp4"},
            {"1", "rtmp://192.168.2.8:2023/vod/garfield.mp4"},
            {"2", "rtmp://192.168.2.8:2023/live/home"},
            {"3", "http://mozicode.com/garfield.mp4"},
            {"4", "rtmp://mozicode.com:2023/live/home"},
            {"5", "http://192.168.2.8/vod/b01.mp4"},
            {"6", "http://mozicode.com/b01.mp4"},
            {"7", "http://devimages.apple.com.edgekey.net/streaming/examples/bipbop_4x3/gear1/prog_index.m3u8"},
            {"双打0721-1", "http://mozicode.com/20230729-133932.mp4"},
    };
    /*
    String url0 = "http://mozicode.com/garfield.mp4";
    String url1 = "rtmp://mozicode.com:2023/live/home";
    String url2 = "rtmp://192.168.2.8:2023/live/home";
    String url3 = "rtmp://192.168.2.8:2023/vod/garfield.mp4";
    String url4 = "rtmp://192.168.2.8:2023/vod/b01.mp4";
    */

    interface OnItemClickListener {
        void OnItemClick(Context context, String videoPath, String videoTitle);
    }

    OnItemClickListener onItemClickListener;

    void setOnItemClickListener(OnItemClickListener listener) {
        onItemClickListener = listener;
    }
}
