package org.dync.ijkplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * Created by KathLine on 2017/10/13.
 */

public class SampleMediaAdapter extends RecyclerView.Adapter<SampleMediaAdapter.ViewHolder> {
    private final Context mContext;
    private final ArrayList<SampleMediaItem> mItemList;

    static final class SampleMediaItem {
        String mUrl;
        String mName;

        public SampleMediaItem(String url, String name) {
            mUrl = url;
            mName = name;
        }
    }

    public SampleMediaAdapter(Context mContext) {
        this.mContext = mContext;
        mItemList = new ArrayList<>();
    }

    public void addItem(String url, String name) {
        mItemList.add(new SampleMediaItem(url, name));
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, @SuppressLint("RecyclerView") final int position) {
        final SampleMediaItem item = mItemList.get(position);
        holder.mNameTextView.setText(item.mName);
        holder.mUrlTextView.setText(item.mUrl);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onAdapterItemClickListener != null) {
                    onAdapterItemClickListener.OnAdapterItemClick(v, item, position);
                }
            }
        });
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return mItemList.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView mNameTextView;
        private final TextView mUrlTextView;

        ViewHolder(final View itemView) {
            super(itemView);
            mNameTextView = (TextView) itemView.findViewById(android.R.id.text1);
            mUrlTextView = (TextView) itemView.findViewById(android.R.id.text2);
        }
    }

    interface OnAdapterItemClickListener {
        void OnAdapterItemClick(View view, SampleMediaItem item, int position);
    }

    OnAdapterItemClickListener onAdapterItemClickListener;

    void setOnItemClickListener(OnAdapterItemClickListener listener) {
        onAdapterItemClickListener = listener;
    }
}