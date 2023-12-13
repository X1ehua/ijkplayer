package com.cclive.cc;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class BaseActivity extends AppCompatActivity implements View.OnClickListener {
    protected Context mContext;
    protected Activity mActivity;

    private int mLastClickedBtnId = -1;
    private long mLastClickTime = 0;
    
    private final String[] desiredPermissions = new String[] {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = this;
        mContext = this;
        checkPermissions();
    }

    private void checkPermissions() {
        if (isAllDesiredPermissionsGranted())
            return;

        final int PERMISSION_REQ_CODE = 10000;
        ActivityCompat.requestPermissions(this, desiredPermissions, PERMISSION_REQ_CODE);
    }

    private boolean isAllDesiredPermissionsGranted() {
        for (String permission : desiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onClick(View v) {
        final int MIN_CLICK_DELAY_TIME = 500;

        /* 禁止重复点击 */
        if (mLastClickedBtnId == -1) { // 第一次点击
            mLastClickTime = SystemClock.elapsedRealtime();
            mLastClickedBtnId = v.getId();
        }
        else if (v.getId() == mLastClickedBtnId) { // 第二次
            long time = SystemClock.elapsedRealtime();
            if (time - mLastClickTime < MIN_CLICK_DELAY_TIME) {
                v.setId(0);
            }
            mLastClickTime = time;
        }
        else if (v.getId() == 0) { // 第三次
            mLastClickTime = SystemClock.elapsedRealtime();
            v.setId(mLastClickedBtnId);
        }
    }
}
