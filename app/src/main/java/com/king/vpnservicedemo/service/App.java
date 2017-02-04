package com.king.vpnservicedemo.service;

import android.app.Application;

/**
 * @description
 * @author: yezhihao
 * @date: 2016-12-22 14:59
 */
public class App extends Application {
    private static App sApp;

    public static App getContext() {
        return sApp;
    }
    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
    }
}
