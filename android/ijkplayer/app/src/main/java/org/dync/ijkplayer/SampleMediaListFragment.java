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

        final String url2 = "rtmp://ns8.indexforce.com/home/mystream";
        final String url1 = "rtmp://mozicode.com:2023/live/home";
        //mAdapter.addItem(url1, "CCLive");
        mAdapter.addItem(url2, "CCLive");
        //mAdapter.addItem("http://mozicode.com/20230729-133932.mp4", "双打0721-1");
    }

    interface OnItemClickListener {
        void OnItemClick(Context context, String videoPath, String videoTitle);
    }

    OnItemClickListener onItemClickListener;

    void setOnItemClickListener(OnItemClickListener listener) {
        onItemClickListener = listener;
    }
}
