package com.king.vpnservicedemo.service;

import android.util.Log;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

/**
 * Created by pencil-box on 16/6/30.
 * 监听来自网络的数据,并将相关数据解析丢回给虚拟网卡去处理
 */
public class NetInput extends Thread {


    private final static String TAG = "NetInput";

    //使线程退出
    private volatile boolean mQuit = false;


    private Selector mChannelSelector;

    /**
     * 标志位退出,即使调用interrupt不一定会退出,双重保证
     */
    public void quit() {
        mQuit = true;
        interrupt();

    }


    private BlockingQueue<ByteBuffer> mOutputQueue;

    /**
     * 监听来自网络的数据,并将相关数据解析丢回给虚拟网卡去处理
     * @param queue
     * @param channelSelector
     */
    public NetInput(BlockingQueue<ByteBuffer> queue, Selector channelSelector) {

        mOutputQueue = queue;
        mChannelSelector = channelSelector;

    }


    /**
     * 需要获取实际网络数据,并把相关的数据写入outputQueue
     */
    @Override
    public void run() {

        Log.d(TAG, "NetInput start");


        while (true) {
            try {
                Thread.sleep(1);

//                ByteBuffer readBuffer = mOutputQueue.poll();

//                Log.d(TAG,Thread.currentThread().getName()+" runing");


            } catch (InterruptedException e) {
//                e.printStackTrace();
                Log.d(TAG, "Stop");
                if (mQuit)
                    return;
                continue;

            }

            try {
                int readyChannels = mChannelSelector.select();

//                Log.d(TAG,"完成select");
                if (readyChannels == 0) {
                    continue;
                }

                Set selectionKeys = mChannelSelector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();

                    //将ipAndPort从队列中取出来,若不存在,说明该调度已经结束啦
                    String ipAndPort = (String) key.attachment();
                    boolean isUdp = ipAndPort.equalsIgnoreCase("UDP");
                    //直接将
                    TCB tcb = TCBCachePool.getTCB(ipAndPort);
                    if (tcb == null) {
                        //通道关闭咯
                        Log.e(TAG, "channel is closed:" + ipAndPort);
                        key.channel().close();
                        keyIterator.remove();
                        continue;
                    }

                    if (key.isConnectable() && !isUdp) {
                        Log.d(TAG, "channel is connectable");
                        buildConnection(tcb, key);
                    } else if (key.isAcceptable()) {
//                        Log.d(TAG,"channel is acceptable");

                    } else if (key.isReadable()) {
                        //感兴趣是这里才有咯?
//                        Log.d(TAG,"channel is readable");
                        transData(tcb, key, isUdp);


                    } else if (key.isWritable()) {
//                        Log.d(TAG,"channel is writable");
                    }
                    keyIterator.remove();

                }

            } catch (IOException e) {
                e.printStackTrace();
            }


        }


    }

    /**
     * 传输数据咯,写回实际返回数据到虚拟网卡
     *
     * @param tcb
     * @param key
     */
    private void transData(TCB tcb, SelectionKey key, boolean isUdp) {


//        if(tcb.tcbStatus != TCB.TCB_STATUS_ESTABLISHED){
//
//            Log.d(TAG,"还未建立成功");
//            return ;
//        }
        ByteBuffer responseBuffer = ByteBufferPool.acquire();
//        Log.d(TAG,"Position before,responseBuffer: Limit:"+responseBuffer.limit()+" position:"+responseBuffer.position());
//        Log.d(TAG,"Position after,responseBuffer: Limit:"+responseBuffer.limit()+" position:"+responseBuffer.position());
        int readBytes = 0;
        //这样实际数据就能写道里面去了
        try {
            if (!isUdp) {
                responseBuffer.position(Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE);
                SocketChannel channel = (SocketChannel) key.channel();
                readBytes = channel.read(responseBuffer);
            } else if (key.channel() instanceof DatagramChannel) {
                responseBuffer.position(Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE);
                DatagramChannel channel = (DatagramChannel) key.channel();
                readBytes = channel.read(responseBuffer);
            }
        } catch (IOException e) {
            e.printStackTrace();

            ByteBufferPool.release(responseBuffer);
//            throw new RuntimeException(e);
        }
//        if(responseBuffer==null){
//            Log.d(TAG,"responseBuffer 为空");
//            return;
//        }
//        int paylaodSize = responseBuffer.limit() - responseBuffer.position();
        synchronized (tcb) {
            Packet responsePacket = tcb.referencePacket;
            Log.d(TAG, "获取回来的数据大小为" + readBytes);
            if (readBytes == -1) {
                //执行完咯,发送完成的包咯
                key.interestOps(0);//不感兴趣咯
                tcb.tcbStatus = TCB.TCB_STATUS_LAST_ACK;
//            Log.d(TAG,"responseBuffer:Before FIN Limit:"+responseBuffer.limit()+" position:"+responseBuffer.position());
                if (!isUdp) {
                    responsePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.FIN | Packet.TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                }
//            Log.d(TAG,"responseBuffer:After FIN Limit:"+responseBuffer.limit()+" position:"+responseBuffer.position());
                tcb.mySequenceNum++;
//                PCapFilter.filterPacket(responseBuffer,tcb.getAppId());
                mOutputQueue.offer(responseBuffer);
                Log.d(TAG, "数据读取完毕");
                return;
            }
            tcb.calculateTransBytes(readBytes);
            Log.d(TAG, "responseBuffer:Before Limit:" + responseBuffer.limit() + " position:" + responseBuffer.position());
            Log.d(TAG, "sequenceNum" + tcb.mySequenceNum);
            Log.d(TAG, "responseBuffer:After Limit:" + responseBuffer.limit() + " position:" + responseBuffer.position());
            if (!isUdp) {
                responsePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.ACK | Packet.TCPHeader.PSH), tcb.mySequenceNum, tcb.myAcknowledgementNum, readBytes);
                tcb.mySequenceNum = tcb.mySequenceNum + readBytes;
                //TODO 这个真让人疑惑
                //之前position之后就不会移动了么,真是神奇~
                responseBuffer.position(Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE + readBytes);
            } else {
                responsePacket.updateUDPBuffer(responseBuffer, readBytes);
                responseBuffer.position(Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE + readBytes);
            }
        }
//        PCapFilter.filterPacket(responseBuffer,tcb.getAppId());
        mOutputQueue.offer(responseBuffer);
    }

    /**
     * 非阻塞状态时,实际的channel建立不是立马完成的
     * 虚拟网卡的握手必须等实际channel建立完成
     *
     * @param tcb
     * @param key
     */
    private void buildConnection(TCB tcb, SelectionKey key) {


        try {
            if (key.channel() instanceof SocketChannel) {
                if (!((SocketChannel) key.channel()).finishConnect()) {
                    Log.d(TAG, "onConnectState 未建立完成");
                    return;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        //读数据咯
        try {
            key.interestOps(SelectionKey.OP_READ);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        Packet responsePacket = tcb.referencePacket;

        ByteBuffer responseBuffer = ByteBufferPool.acquire();
        if (key.channel() instanceof SocketChannel) {
            responsePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
        } /*else {
            responsePacket.updateUDPBuffer(responseBuffer, 0);
        }*/
        //TODO mySequenceNum 并发操作会发生诡异的事情么??

        tcb.tcbStatus = TCB.TCB_STATUS_SYN_RECEIVED;
        tcb.mySequenceNum++;

//        PCapFilter.filterPacket(responseBuffer,tcb.getAppId());

        mOutputQueue.offer(responseBuffer);

    }

}
