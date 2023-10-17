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

import static org.dync.ijkplayer.VideoActivity.URI_LIST;

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

    public static SampleMediaListFragment newInstance() {
        return new SampleMediaListFragment();
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

        SampleMediaAdapter sma = new SampleMediaAdapter(activity);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mRecyclerView.setAdapter(sma);

        sma.setOnItemClickListener(new SampleMediaAdapter.OnAdapterItemClickListener() {
            @Override
            public void OnAdapterItemClick(View view, SampleMediaAdapter.SampleMediaItem item, int position) {
                String name = item.mName;
                String url = item.mUrl;
                if (onItemClickListener != null) {
                    onItemClickListener.OnItemClick(activity, url, name);
                }
            }
        });

        for (int i=0; i<URI_LIST.length; i++) {
            sma.addItem(URI_LIST[i][1], URI_LIST[i][0]);
        }
    }

    interface OnItemClickListener {
        void OnItemClick(Context context, String videoPath, String videoTitle);
    }

    OnItemClickListener onItemClickListener;

    void setOnItemClickListener(OnItemClickListener listener) {
        onItemClickListener = listener;
    }
}
