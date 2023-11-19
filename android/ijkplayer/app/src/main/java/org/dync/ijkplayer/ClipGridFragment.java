package org.dync.ijkplayer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.fragment.app.Fragment;

import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ClipGridFragment extends Fragment {
	final static String TAG = "ijkJava";
	protected AbsListView mListView;

	static List<String> sMp4Files = new ArrayList<>();
	static List<String> sJpgFiles = new ArrayList<>();

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fr_image_grid, container, false);
		mListView = (GridView) rootView.findViewById(R.id.grid);

		String clipDir = Environment.getExternalStorageDirectory().getPath() + "/DCIM/cc/";
		getClipFiles(clipDir);

		final Activity a = getActivity();
		ClipAdapter ca = new ClipAdapter(a);
		((GridView) mListView).setAdapter(ca);

		mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
				onClipItemClick((VideoActivity)a, pos);
			}
		});

		return rootView;
	}

	void getClipFiles(String clipDir) {
		File fd = new File(clipDir);
		File[] files = fd.listFiles();

		if (files == null) {
			Log.e(TAG, "listFiles() got null in " + clipDir);
			return;
		}

		for (File file : files) {
			if (file.isDirectory()) {
				getClipFiles(file.getAbsolutePath());
			} else if (file.getName().endsWith(".mp4")) {
				String mp4 = file.getAbsolutePath();
				String jpg = mp4.substring(0, mp4.length() - 3) + "jpg";
				if (new File(jpg).isFile()) {
					sMp4Files.add(mp4);
					sJpgFiles.add("file://" + jpg);
				}
				//else {
				//	Log.e(TAG, ">> No .jpg file found corresponding to " + mp4);
				//}
			}
		}
	}

	void onClipItemClick(VideoActivity va, int pos) {
		String uri = sMp4Files.get(pos);
		va.changeVideo(uri);
	}

	interface OnItemClickListener {
		void onItemClick(Context context, String videoUri);
	}

	OnItemClickListener mOnItemClickListener;

	void setOnItemClickListener(OnItemClickListener listener) {
		mOnItemClickListener = listener;
	}

	//String[] getClipList() { return }
	private static class ClipAdapter extends BaseAdapter {
		private final LayoutInflater inflater;

		ClipAdapter(Context context) {
			inflater = LayoutInflater.from(context);
		}

		@Override
		public int getCount() {
			return sMp4Files.size();
		}

		@Override
		public Object getItem(int pos) {
			return null;
		}

		@Override
		public long getItemId(int pos) {
			return pos;
		}

		@Override
		public View getView(int pos, View convertView, ViewGroup parent) {
			final ViewHolder holder;
			View view = convertView;
			if (view == null) {
				view = inflater.inflate(R.layout.item_grid_image, parent, false);
				holder = new ViewHolder();
				assert view != null;
				holder.imageView = (ImageView) view.findViewById(R.id.image);
				holder.progressBar = (ProgressBar) view.findViewById(R.id.progress);
				view.setTag(holder);
			} else {
				holder = (ViewHolder) view.getTag();
			}

			//
			ImageLoader imageLoader = ImageLoader.getInstance();
			DisplayImageOptions options = new DisplayImageOptions.Builder()
//					.showImageOnLoading(R.drawable.ic_stub)
//					.showImageForEmptyUri(R.drawable.ic_empty)
//					.showImageOnFail(R.drawable.ic_error)
					.cacheInMemory(true)
					.cacheOnDisk(true)
					.considerExifParams(true)
					.bitmapConfig(Bitmap.Config.RGB_565)
					.build();
			imageLoader.displayImage(sJpgFiles.get(pos), holder.imageView, options, new SimpleImageLoadingListener() {
				@Override
				public void onLoadingStarted(String imageUri, View view) {
					holder.progressBar.setProgress(0);
					holder.progressBar.setVisibility(View.VISIBLE);
				}

				@Override
				public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
					holder.progressBar.setVisibility(View.GONE);
				}

				@Override
				public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
					holder.progressBar.setVisibility(View.GONE);
				}
			}, new ImageLoadingProgressListener() {
				@Override
				public void onProgressUpdate(String imageUri, View view, int current, int total) {
					holder.progressBar.setProgress(Math.round(100.0f * current / total));
				}
			});
			//

			return view;
		}
	}

	static class ViewHolder {
		ImageView imageView;
		ProgressBar progressBar;
	}
}