package com.king.vpnservicedemo.service;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.LinkedBlockingQueue;

import static android.content.ContentValues.TAG;

public class VpnServiceDemo extends VpnService implements
        Runnable {


    //VPN转发的IP地址咯
    public static String VPN_ADDRESS = "10.1.10.1";


    //从虚拟网卡拿到的文件描述符
    private ParcelFileDescriptor mInterface;

    /**
     * 来自应用的请求的数据包
     */
    private LinkedBlockingQueue<Packet> mInputQueue;

    /**
     * 即将发送至应用的数据包
     */
    private LinkedBlockingQueue<ByteBuffer> mOutputQueue;

    //缓存的appInfo队列,请求被拦截的队列
    private LinkedBlockingQueue<App> mCacheAppInfo;
    //网络访问通知线程
//	private NetNotifyThread mNetNotify;

    //网络输入输出
    private NetInput mNetInput;
    private NetOutput mNetOutput;

    private Selector mChannelSelector;


    public static volatile boolean isRunning = false;
    public static volatile boolean isCalledByUser = false;


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");


    }

    //建立vpn
    private void setupVpn() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            try {
//                mInterface = builder.setSession("NetKnight").setBlocking(false)
//                        .addAddress(VPN_ADDRESS, 32).addAllowedApplication("com.devjiang").addRoute("0.0.0.0", 0).establish();
//            } catch (PackageManager.NameNotFoundException e) {
//                e.printStackTrace();
//            }
//        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Builder builder = new Builder();
            mInterface = builder.setSession("NetKnight").setBlocking(false).addAddress(VPN_ADDRESS, 32).addRoute("0.0.0.0", 0).establish();

        } else {
            Log.e("NetKnightService", "当前版本的android 不支持vpnservice");
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        setupVpn();
        try {
            mChannelSelector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mCacheAppInfo = new LinkedBlockingQueue<>();
//		mNetNotify = new NetNotifyThread(this,mCacheAppInfo);
        mInputQueue = new LinkedBlockingQueue<>();
        mOutputQueue = new LinkedBlockingQueue<>();

        mNetInput = new NetInput(mOutputQueue, mChannelSelector);
        //这个传参要不要等init好再传呢
        mNetOutput = new NetOutput(mInputQueue, mOutputQueue, this, mChannelSelector, mCacheAppInfo);

        //还是直接start呢?
//        ExecutorService executorService = Executors.newFixedThreadPool(3);
//        executorService.execute(mNetInput);
//        executorService.execute(mNetOutput);
//        executorService.execute(this);

//        Log.d(TAG,fd.toString());
//		mNetNotify.start();
        mNetOutput.start();
        mNetInput.start();
        new Thread(this).start();

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void run() {
        Log.d(TAG, "start");
        isRunning = true;

        FileChannel vpnInput = new FileInputStream(mInterface.getFileDescriptor()).getChannel();
        FileChannel vpnOutput = new FileOutputStream(mInterface.getFileDescriptor()).getChannel();

        ByteBuffer buffer4Net;
        ByteBuffer buffer2Net = null;
        boolean isDataSend = true;
//        int sleepTime = 1;
        try {
            while (true) {
                if (!isRunning) {
                    Log.d("NetKnight", "isRunning is false");
                    if (isCalledByUser) {
//                      mHandler.sendEmptyMessage(MSG_STOP_VPN_SERVICE);
                        close();
                        Log.d("NetKnight", "stopSelf");
                    }
                    break;
                }
                //数据发送出去了,就get 新的咯
                if (isDataSend) {
                    buffer2Net = ByteBufferPool.acquire();
                } else {
                    //未有数据发送,据清空咯
                    buffer2Net.clear();
                }
                int inputSize = vpnInput.read(buffer2Net);

                if (inputSize > 0) {
                    Log.d(TAG, "-----readData:-------size:" + inputSize);
                    //flip切换状态,由写状态转换成可读状态
                    buffer2Net.flip();

                    //从应用中发送的包
                    Packet packet2net = new Packet(buffer2Net);
                    Log.d(TAG, "--------data read----------size:" + packet2net.getPayloadSize());
                    Log.d(TAG, packet2net.toString());

                    if (packet2net.isTCP()) {
                        //目前支持TCP
                        /*InetAddress desAddress = packet2net.ip4Header.destinationAddress;
                        int sourcePort = packet2net.tcpHeader.sourcePort;
                        int desPort = packet2net.tcpHeader.destinationPort;
                        String ipAndPort = desAddress.getHostAddress() + ":" + sourcePort + ":" + desPort;
                        //实现抓包功能咯
                        TCB tcb = TCBCachePool.getTCB(ipAndPort);
                        if (tcb != null) {
                            //方便包过滤使
                            //注意position 和 limit的位置,执行new Packet操作, position是到tcp头的位置的
                            int curPostion = buffer2Net.position();
                            int curLimit = buffer2Net.limit();

                            buffer2Net.position(buffer2Net.limit());
                            buffer2Net.limit(buffer2Net.capacity());

							PCapFilter.filterPacket(buffer2Net,tcb.getAppId());

                            buffer2Net.position(curPostion);
                            buffer2Net.limit(curLimit);
                        }*/
                        mInputQueue.offer(packet2net);
                        isDataSend = true;
                    } else if (packet2net.isUDP()) {
                        Log.e(TAG, "UDP");
                        mInputQueue.offer(packet2net);
                        isDataSend = true;
                    } else {
                        Log.d(TAG, "暂时不支持其他类型数据!!");
                        isDataSend = false;
                    }
                } else {
                    //与其release 还不如直接复用
                    isDataSend = false;
//                    ByteBufferPool.release(buffer2Net);

                }


                //将数据返回到应用中
                buffer4Net = mOutputQueue.poll();
                if (buffer4Net != null) {
                    //将limit=position position = 0 开始读操作
                    buffer4Net.flip();
                    while (buffer4Net.hasRemaining()) {
                        vpnOutput.write(buffer4Net);
                    }
                    ByteBufferPool.release(buffer4Net);
                }


                //可减少内存抖动??
                if (!isDataSend) {
                    Thread.sleep(1);
                }

            }

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            try {
                vpnInput.close();
                vpnOutput.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }


    /**
     * 关闭相关资源
     */
    public void close() {


        isRunning = false;
        mNetInput.quit();
        mNetOutput.quit();
//		mNetNotify.quit();


        try {
            mChannelSelector.close();
            mInterface.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}