package com.cclive.cc.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Created by KathLine on 2017/9/25.
 */
@SuppressWarnings("deprecation")
public class NetworkUtils {

    /**
     * 判断是否有网络连接
     * @param context
     * @return
     */
    public static boolean isNetworkConnected(Context context) {
        if (context != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                    if (capabilities != null) {
                        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    }
                }
            }
            else {
                NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                if (networkInfo != null) {
                    return networkInfo.isAvailable();
                }
            }
        }
        return false;
    }

    /**
     * 判断WIFI网络是否可用
     * @param context
     * @return
     */
    public static boolean isWifiConnected(Context context) {
        if (context != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                    if (capabilities != null) {
                        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    }
                }
            }
            else {
                NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (ni != null) {
                    return ni.isAvailable();
                }
            }
        }
        return false;
    }


    /**
     * 判断 cellular 网络是否可用
     * @param context
     * @return
     */
    public static boolean isCellularConnected(Context context) {
        if (context != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                    if (capabilities != null) {
                        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    }
                }
            }
            else {
                NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
                if (ni != null) {
                    return ni.isAvailable();
                }
            }
        }
        return false;
    }


    /**
     * 获取当前网络连接的类型信息
     * @param context
     * @return
     */
    public static int getConnectedType(Context context) {
        if (context != null) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            if (networkInfo != null && networkInfo.isAvailable()) {
                return networkInfo.getType();
            }
        }
        return -1;
    }


    /**
     * 获取当前的网络状态：没有网络-1，WIFI 网络1
     *
     * @param context
     * @return
     */
    public static int getAPNType(Context context) {
        int netType = -1;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        if (networkInfo == null) {
            return netType;
        }
        int nType = networkInfo.getType();
        if (nType == ConnectivityManager.TYPE_WIFI) {
            netType = 1; // WiFi
        } else if (nType == ConnectivityManager.TYPE_MOBILE) {
            int nSubType = networkInfo.getSubtype();
            TelephonyManager mTelephony = (TelephonyManager) context
                    .getSystemService(Context.TELEPHONY_SERVICE);
            netType = nSubType; // 手机网络
        }
        return netType;
    }

    /**
     * 获取 Mac 地址
     */
    public static String getMacAddr() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                if (!nif.getName().equalsIgnoreCase("eth0")) continue; // wlan0

                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null) {
                    return "";
                }

                StringBuilder res1 = new StringBuilder();
                for (byte b : macBytes) {
                    res1.append(String.format("%02X:", b));
                }

                if (res1.length() > 0) {
                    res1.deleteCharAt(res1.length() - 1);
                }
                return res1.toString();
            }
        } catch (Exception ex) {
            android.util.Log.e("ijkJava", "getMacAddr() got exception: " + ex.toString());
        }

        return "02:00:00:00:00:00";
    }
}
