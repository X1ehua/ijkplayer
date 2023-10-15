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

public class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.ViewHolder> {
    private final Context mContext;
    private final ArrayList<ClipItem> mItemList;

    static final class ClipItem {
        String mUrl;
        String mName;

        public ClipItem(String url, String name) {
            mUrl = url;
            mName = name;
        }
    }

    public ClipAdapter(Context mContext) {
        this.mContext = mContext;
        mItemList = new ArrayList<>();
    }

    public void addItem(String url, String name) {
        mItemList.add(new ClipItem(url, name));
    }

    @NonNull
    @Override // 创建每个 video clip 的视图
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override // 将数据绑定到每个 ViewHolder 的视图
    public void onBindViewHolder(ViewHolder holder, @SuppressLint("RecyclerView") final int position) {
        final ClipItem item = mItemList.get(position);
        holder.mNameTextView.setText(item.mName);
        holder.mUrlTextView.setText(item.mUrl);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onItemClickListener != null) {
                    onItemClickListener.OnItemClick(v, item, position);
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

    interface OnItemClickListener {
        void OnItemClick(View view, ClipItem item, int position);
    }

    OnItemClickListener onItemClickListener;

    void setOnItemClickListener(OnItemClickListener listener) {
        onItemClickListener = listener;
    }
}