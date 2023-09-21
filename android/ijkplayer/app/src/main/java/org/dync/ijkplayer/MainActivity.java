package org.dync.ijkplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import org.dync.ijkplayerlib.widget.receiver.NetWorkControl;
import org.dync.ijkplayerlib.widget.receiver.NetworkChangedReceiver;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity {
    private Context mContext;
    private final String TAG = "MainActivity";

    @BindView(R.id.btn_setting)
    Button btnSetting;
    @BindView(R.id.btn_ijkPlayer)
    Button btnIjkPlayer;
//  @BindView(R.id.btn_exoPlayer)
//  Button btnExoPlayer;

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        mContext = this;

        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        /*
        NetworkChangedReceiver register = NetWorkControl.register(TAG, this);
        register.setNetWorkChangeListener(new NetWorkControl.NetWorkChangeListener() {
            @Override
            public boolean isConnected(boolean wifiConnected, boolean wifiAvailable, boolean mobileConnected, boolean mobileAvailable) {

                return false;
            }
        });
        */
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NetWorkControl.unRegister(TAG, this);
    }

//  @OnClick({R.id.btn_setting, R.id.btn_ijkPlayer, R.id.btn_exoPlayer})
    @SuppressLint("NonConstantResourceId")
    @OnClick({R.id.btn_setting, R.id.btn_ijkPlayer})
    public void onViewClicked(View view) {
        switch (view.getId()) {
            case R.id.btn_setting:
                startActivity(new Intent(mContext, SettingActivity.class));
                break;
            case R.id.btn_ijkPlayer: // TODO: WHY label not ijkPlayer but IJKPLAYER ?
                String url = SampleMediaListFragment.getDefaultURL();
                VideoActivity.intentTo(mContext, url, "Default Video");
                break;
            //case R.id.btn_exoPlayer:
            //  startActivity(new Intent(mContext, SimpleActivity.class));
            //  break;
        }
    }
}
