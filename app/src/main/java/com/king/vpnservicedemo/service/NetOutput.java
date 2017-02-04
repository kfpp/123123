package com.king.vpnservicedemo.service;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

/**
 * Created by pencil-box on 16/6/30.
 * 发起实际的网络请求数据,tcp通过socketServer实现,udp待完成
 * 需要处理获取的虚拟网卡数据
 */
public class NetOutput extends Thread {


    private final static String TAG = "NetOutput";


    private volatile boolean mQuit = false;

    /**
     * 来自应用的请求的数据包
     */
    //从队列中读到的队列数据
    private BlockingQueue<Packet> mInputQueue;
    /**
     * 即将发送至应用的数据包
     */
    private BlockingQueue<ByteBuffer> mOutputQueue;


    //这个是用来监视多个channel状态,即实际的网络访问信息
    private Selector mChannelSelector;

    private Map<Integer, DatagramSocket> mUdpSockCache;
    private VpnService mVpnService;

    /**
     * 发起实际的网络请求数据,tcp通过socketServer实现,udp待完成
     * 需要处理获取的虚拟网卡数据
     * @param inputQueue
     * @param outputQueue
     * @param vpnService
     * @param selector
     * @param appCacheQueue
     */
    public NetOutput(BlockingQueue<Packet> inputQueue, BlockingQueue<ByteBuffer> outputQueue, VpnService vpnService, Selector selector, BlockingQueue<App> appCacheQueue) {
        mOutputQueue = outputQueue;
        mInputQueue = inputQueue;

        mVpnService = vpnService;
        mChannelSelector = selector;
//        mAppCacheQueue = appCacheQueue;
        mUdpSockCache = new HashMap<>();
    }


    public void quit() {
        mQuit = true;
        interrupt();
    }

    //采用NIO同步非阻塞模型

    /**
     * 需要获取相关的读到的数据,并做出数据反馈,还有就是outputQueue进行
     */
    @Override
    public void run() {
        Log.d(TAG, "start~");
        Packet currentPacket;
        while (true) {
            try {
                //阻塞等到有数据就处理
                currentPacket = mInputQueue.poll();
                if (currentPacket == null) {
                    Thread.sleep(15);
                    continue;
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "Stop");
                if (mQuit)
                    return;
                continue;
            }
            //实际要传的数据哟
            ByteBuffer payloadBuffer = currentPacket.backingBuffer;
            //payloadBuffer 其实也是可复用的吧
            currentPacket.backingBuffer = null;
            ByteBuffer responseBuffer = ByteBufferPool.acquire();

            //重要的信息记录下来咯
            InetAddress desAddress = currentPacket.ip4Header.destinationAddress;

            int sourcePort = 0;
            int desPort = 0;
            if (currentPacket.isTCP()) {
                sourcePort = currentPacket.tcpHeader.sourcePort;
                desPort = currentPacket.tcpHeader.destinationPort;
            } else if (currentPacket.isUDP()) {
                sourcePort = currentPacket.udpHeader.sourcePort;
                desPort = currentPacket.udpHeader.destinationPort;
            }

            String ipAndPort = desAddress.getHostAddress() + ":" + sourcePort + ":" + desPort;

//            if (currentPacket.isUDP()) {
//                transDataByUDP(desAddress, desPort, responseBuffer, ipAndPort);
//            }

            TCB tcb = TCBCachePool.getTCB(ipAndPort);

            if (tcb == null) {

                initTCB(ipAndPort, currentPacket, desAddress, desPort, responseBuffer);
            } else if (currentPacket.isTCP() && currentPacket.tcpHeader.isSYN()) {
                Log.d(TAG, "重复的SYN");

                dealDuplicatedSYN(tcb, ipAndPort, currentPacket, responseBuffer);

            } else if (currentPacket.isTCP() && currentPacket.tcpHeader.isFIN()) {
                Log.d(TAG, "---FIN---");

                //结束这条连接咯
                finishConnect(tcb, ipAndPort, currentPacket, responseBuffer);

                continue;
            } else if (currentPacket.isTCP() && currentPacket.tcpHeader.isACK()) {
                Log.d(TAG, "---ACK---");

                //传递数据咯,一般数据是带ACK的
                transData(ipAndPort, tcb, currentPacket, payloadBuffer, responseBuffer);
            } else {
                transData(ipAndPort, tcb, currentPacket, payloadBuffer, responseBuffer);
            }
//            else if (currentPacket.isUDP()){
//                transDataByUDP(desAddress,desPort,responseBuffer,ipAndPort);
//            }

            //复用传进来的ByteBuffer
            ByteBufferPool.release(payloadBuffer);

            Log.d(TAG, "tcpOutput执行一遍");


        }


    }


    //连接重置咯,即断开之前的连接
    private void sendRST(TCB tcb, String ipAndPort, int prevPayloadSize, ByteBuffer buffer) {
        Log.d(TAG, "sendRST");
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);


//        //PCapFilter.filterPacket(buffer, tcb.getAppId());

        mOutputQueue.offer(buffer);
        TCBCachePool.closeTCB(ipAndPort);
    }

    /**
     * 处理冗余的SYN
     *
     * @param tcb
     * @param ipAndPort
     * @param currentPacket
     * @param responseBuffer
     */
    private void dealDuplicatedSYN(TCB tcb, String ipAndPort, Packet currentPacket, ByteBuffer responseBuffer) {

        synchronized (tcb) {
            if (tcb.tcbStatus == TCB.TCB_STATUS_SYN_SENT) {

                //如果是SYN发送的状态,即还在与远程服务器建立连接ing..
                tcb.myAcknowledgementNum = currentPacket.tcpHeader.sequenceNumber + 1;
                return;
            }
        }

        sendRST(tcb, ipAndPort, 1, responseBuffer);

    }

    /**
     * 关闭连接咯
     *
     * @param ipAndPort
     * @param currentPacket
     * @param responseBuffer
     */
    private void finishConnect(TCB tcb, String ipAndPort, Packet currentPacket, ByteBuffer responseBuffer) {

        synchronized (tcb) {

            //标识已经被改变咯
            tcb.myAcknowledgementNum = currentPacket.tcpHeader.sequenceNumber + 1;

            tcb.tcbStatus = TCB.TCB_STATUS_LAST_ACK;
            currentPacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.FIN | Packet.TCPHeader.ACK),
                    tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            tcb.mySequenceNum++;
            // FIN counts as a byte

//            //PCapFilter.filterPacket(responseBuffer, tcb.getAppId());


        }
        TCBCachePool.closeTCB(ipAndPort);


        mOutputQueue.offer(responseBuffer);

    }

    /**
     * 传递实际的数据
     *
     * @param tcb
     */
    private void transData(String ipAndPort, TCB tcb, Packet currentPacket, ByteBuffer dataBuffer, ByteBuffer responseBuffer) {

        //1.发送ACK码 2.传递真实数据

        int payloadSize = dataBuffer.limit() - dataBuffer.position();

        //对tcb加锁,防止其有变动
        synchronized (tcb) {


            if (tcb.tcbStatus == TCB.TCB_STATUS_LAST_ACK) {
                //关闭通道
                Log.d(TAG, "close channel");
                TCBCachePool.closeTCB(ipAndPort);
                return;
            }


            //无数据的直接ignore了
            if (payloadSize == 0) {

                Log.d(TAG, "-------ack has no data-------");
                return;
            }


            Log.d(TAG, "传递的payloadSize为:" + payloadSize);

            //发送完数据咯,那么就执行真正的数据访问

            SelectionKey outKey = tcb.selectionKey;
            if (outKey == null) {
                Log.d(TAG, "outKey 为 null");
                return;
            }

            //监听读的状态咯
            if (tcb.tcbStatus == TCB.TCB_STATUS_SYN_RECEIVED) {
                tcb.tcbStatus = TCB.TCB_STATUS_ESTABLISHED;

            } else if (tcb.tcbStatus == TCB.TCB_STATUS_ESTABLISHED) {

                Log.d(TAG, "establish ing");


            } else {

                Log.d(TAG, "当前tcbStatus为" + tcb.tcbStatus);
                Log.d(TAG, "连接还没建立好");
                return;
            }
            if (outKey.channel() instanceof SocketChannel) {
                SocketChannel outChannel = (SocketChannel) outKey.channel();

                if (outChannel.isConnected() && outChannel.isOpen()) {
                    Log.d(TAG, "执行写channel操作");
                    try {
                        while (dataBuffer.hasRemaining()) {

                            outChannel.write(dataBuffer);
                        }

                        //记录发送数据
                        tcb.calculateTransBytes(payloadSize);

                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.d(TAG, "write data error");
                        //失败就告知连接中断
                        sendRST(tcb, ipAndPort, payloadSize, responseBuffer);

                    }

                } else {
                    Log.d(TAG, "channel都没准备好");
                }
            } else if (outKey.channel() instanceof DatagramChannel) {
                DatagramChannel outChannel = (DatagramChannel) outKey.channel();
                if (outChannel.isConnected()) {
                    Log.d(TAG, "执行写 udp channel 操作");
                    try {
                        while (dataBuffer.hasRemaining()) {

                            outChannel.write(dataBuffer);
                        }

                        //记录发送数据
                        tcb.calculateTransBytes(payloadSize);

                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.d(TAG, "write data error");
                        //失败就告知连接中断
//                        sendRST(tcb, ipAndPort, payloadSize, responseBuffer);

                    }
                }
            }


            currentPacket.swapSourceAndDestination();
            if (currentPacket.isTCP()) {
                tcb.myAcknowledgementNum = currentPacket.tcpHeader.sequenceNumber + payloadSize;
                currentPacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            } else if (currentPacket.isUDP()) {
                currentPacket.updateUDPBuffer(responseBuffer, 0);
            }

            Log.d(TAG, "transData responseBuffer limit:" + responseBuffer.limit() + " position:" + responseBuffer.position());

        }

//        //PCapFilter.filterPacket(responseBuffer, tcb.getAppId());
        //ack码
        mOutputQueue.offer(responseBuffer);


    }

    private void transDataByUDP(InetAddress desAddress, int desPort, ByteBuffer responseBuffer, String ipAndPort) {
        DatagramSocket socket = null;
        try {
            //创建DatagramSocket对象并指定一个端口号，注意，如果客户端需要接收服务器的返回数据,
            //还需要使用这个端口号来receive，所以一定要记住
            if (mUdpSockCache.containsKey(desPort)) {
                socket = mUdpSockCache.get(desPort);
            }
            if (socket == null || socket.isClosed() || !socket.isConnected()) {
                DatagramChannel channel = DatagramChannel.open();
//                channel.configureBlocking(false);
                mVpnService.protect(channel.socket());
                channel.connect(new InetSocketAddress(desAddress, desPort));
                socket = channel.socket();
                mUdpSockCache.put(desPort, socket);
                channel.register(mChannelSelector, SelectionKey.OP_READ, ipAndPort);
            }
            //创建一个DatagramPacket对象，用于发送数据。
            //参数一：要发送的数据  参数二：数据的长度  参数三：服务端的网络地址  参数四：服务器端端口号
            DatagramPacket packet = new DatagramPacket(responseBuffer.array(), responseBuffer.array().length, desAddress, desPort);
            socket.send(packet);//把数据发送到服务端。
            Log.d(TAG, "send data by udp");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                mUdpSockCache.remove(desPort);
                socket.close();
            }
        }
    }

    /**
     * 初始化TCB以及相关连接
     */
    private void initTCB(String ipAndPort, Packet referencePacket, InetAddress desAddress, int desPort, ByteBuffer responseBuffer) {


        Log.d(TAG, "initTcb ...");

        //TODO 找到对应的uid咯

        long passAppId = filterPacket(referencePacket);
        if (passAppId == -1) {
            //TODO 主动断开连接,拒绝访问咯

            return;
        }


//        //TODO 抓包咯,这样很不妥呀,还要根据相关信息构建包orz
        ByteBuffer sourceBuffer = ByteBufferPool.acquire();
        if (referencePacket.isTCP()) {
            referencePacket.updateTCPBuffer(sourceBuffer, (byte) Packet.TCPHeader.SYN, referencePacket.tcpHeader.sequenceNumber,
                    referencePacket.tcpHeader.acknowledgementNumber, 0);
        } else if (referencePacket.isUDP()) {
            referencePacket.updateUDPBuffer(sourceBuffer, 0);
        }
//        //PCapFilter.filterPacket(sourceBuffer,passAppId);
        ByteBufferPool.release(sourceBuffer);


        referencePacket.swapSourceAndDestination();
        TCB tcb = null;
        if (referencePacket.isTCP()) {
            tcb = new TCB(ipAndPort, new Random().nextInt(Short.MAX_VALUE), referencePacket.tcpHeader.sequenceNumber,
                    referencePacket.tcpHeader.sequenceNumber + 1, referencePacket.tcpHeader.acknowledgementNumber, referencePacket);
        } else if (referencePacket.isUDP()) {
            tcb = new TCB(ipAndPort, new Random().nextInt(Short.MAX_VALUE), 0,
                    1, 0, referencePacket);
        }
        //设置appId,方便记录流量包
        tcb.setAppId(passAppId);


        //存储起来先orz
        TCBCachePool.putTCB(ipAndPort, tcb);


        try {
            if (referencePacket.isUDP()) {
                DatagramChannel socketChannel = DatagramChannel.open();
                socketChannel.configureBlocking(false);
                mVpnService.protect(socketChannel.socket());
                socketChannel.connect(new InetSocketAddress(desAddress, desPort));
                tcb.tcbStatus = TCB.TCB_STATUS_SYN_RECEIVED;
                mChannelSelector.wakeup();
                tcb.selectionKey = socketChannel.register(mChannelSelector, SelectionKey.OP_READ, "UDP");

            } else {
                SocketChannel socketChannel = SocketChannel.open();
//            DatagramChannel socketChannel = Da
                socketChannel.configureBlocking(false);
                //保护本应用建立的通道,防止死循环
                mVpnService.protect(socketChannel.socket());
                socketChannel.connect(new InetSocketAddress(desAddress, desPort));


                //非阻塞状态连接需要判断是否连接成功
                if (socketChannel.finishConnect()) {
                    //连接成功,即已经实现握手了,此时虚拟网卡这边也要进行握手


                    //速度很快,都不用监听就完成了
                    Log.d(TAG, "socketChannel连接完成咯");
                    tcb.tcbStatus = TCB.TCB_STATUS_SYN_RECEIVED;
                    if (referencePacket.isTCP()) {
                        referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                        tcb.mySequenceNum++;
                    }

                    //TODO 不管it
                    mChannelSelector.wakeup();
                    tcb.selectionKey = socketChannel.register(mChannelSelector, SelectionKey.OP_READ, ipAndPort);


                } else {
                    //正在连接,发送了SYN

                    tcb.tcbStatus = TCB.TCB_STATUS_SYN_SENT;
                    mChannelSelector.wakeup();
                    SelectionKey key = socketChannel.register(mChannelSelector, SelectionKey.OP_CONNECT, ipAndPort);
//TODO 将selectionKey存储起来咯
                    tcb.selectionKey = key;

                    Log.d(TAG, "socketChannel 注册ing");


                    ByteBufferPool.release(responseBuffer);
                    return;
                    //还在连接咯,还没连接成功

                }
            }


        } catch (IOException e) {
            e.printStackTrace();
            ByteBufferPool.release(responseBuffer);
        }

        //PCapFilter.filterPacket(responseBuffer, tcb.getAppId());
        mOutputQueue.offer(responseBuffer);

    }

    /**
     * 过滤相关的包,返回的为appId,如果为-1,则说明禁止访问
     * ip,domain name
     * uid
     */

    private long filterPacket(Packet transPacket) {


        //TODO 根据域名拦截

//        if (BlockingPool.isBlockName) {
//
//            if (filterByDomain(transPacket.ip4Header.destinationAddress.getHostName())) {
//                Log.e(TAG, "数据包因为domainName段被拦截了");
//                return -1;
//            }
//
//        }
//
//
//        //TODO 根据ip段拦截
//        if (BlockingPool.isBlockIp) {
//            if (filterByIp(transPacket.ip4Header.destinationAddress.getHostAddress())) {
//                Log.e(TAG, "数据包因为IP段被拦截了");
//                return -1;
//            }
//        }
        int port = 0;
        if (transPacket.isTCP()) {
            port = transPacket.tcpHeader.sourcePort;
        } else if (transPacket.isUDP()) {
            port = transPacket.udpHeader.sourcePort;
        }
        int uid = NetUtils.readProcFile(port);
        Log.d(TAG, "uid为:" + uid);

        if (uid < 10000) {

            Log.e(TAG, "连接失败");
//            sendRST();
            return -1;
        }
        //过滤暂且没做呢


        //TODO 通过UID查找appId

        /*List<App> appList = DataSupport.where("uid = ?", String.valueOf(uid)).find(App.class);

        if (appList.size() != 1) {
            return -1;
        }
        Log.d(TAG, "app id 号为:" + appList.get(0).getId());


        //TODO 根据类型信息进行拦截


        boolean isPass = filterByAppSetting(appList.get(0));
        if(!isPass){
            return -1;
        }*/


       /* String name =   AppUtils.getPackageNameByUid(App.getContext(),uid);

        Log.e(TAG,"包名为:"+name+" &&Uid:" +uid);*/

//        return appList.get(0).getId();
        return uid;
    }


    /**
     * 根据app的设置信息进行拦截
     * @param app
     * @return
     */
    /*private boolean filterByAppSetting(App app) {



        if(NetChangeReceiver.sNetState == NetChangeReceiver.NET_STATE_MOBILE){



            switch (app.getMobileDataType()){

                case Constants.ACCESS_TYPE_ALLOW:



                    return true;
                case Constants.ACCESS_TYPE_DISALLOW:

                    return false;
                case Constants.ACCESS_TYPE_DISALLOW_BACK:

                    //TODO 如何判断手机待机呢
                    break;
                case Constants.ACCESS_TYPE_REMIND:

                    //通知有网络访问信息咯

                    mAppCacheQueue.offer(app);

                    return false;
                default:
                    break;

            }


        }
        if(NetChangeReceiver.sNetState == NetChangeReceiver.NET_STATE_WIFI){

            switch (app.getWifiType()){

                case Constants.ACCESS_TYPE_ALLOW:



                    return true;
                case Constants.ACCESS_TYPE_DISALLOW:

                    return false;
                case Constants.ACCESS_TYPE_DISALLOW_BACK:

                    //TODO 如何判断手机待机呢
                    break;
                case Constants.ACCESS_TYPE_REMIND:

                    //通知有网络访问信息咯
                    mAppCacheQueue.offer(app);

                    return false;
                default:
                    break;

            }


        }


        return false;
    }*/


    /**
     * 域名信息
     *
     * @param domainName
     * @return true 拦截成功
     */
    /*private boolean filterByDomain(String domainName) {

        Log.d(TAG, "--------filterByDomain---------");
        Log.d(TAG, "DomainName:" + domainName);

        ArrayList<BlockName> blockNames = BlockingPool.getNameList();
        for (int i = 0; i < blockNames.size(); i++) {

            if (blockNames.get(i).getcName().startsWith(domainName)) {

                return true;
            }

        }


        return false;
    }*/

    /**
     * 过滤Ip信息
     * 遍历阻断Ip信息,并查看是否在集合内
     *
     * @param hostAddress xx.xx.xx.xx的结构
     * @return true为已经过滤掉
     * false为不用被过滤
     */
    /*private boolean filterByIp(String hostAddress) {
        String[] ip = hostAddress.split("\\.");

        Log.d(TAG, "------filterByIp---------");
        Log.d(TAG, "hostAddress:" + hostAddress);

        ArrayList<BlockIp> blockIps = BlockingPool.getIpList();

        for (int i = 0; i < blockIps.size(); i++) {
            BlockIp blockIp = blockIps.get(i);

            String[] beginIp = blockIp.getOriginIp().split("\\.");
            String[] endIp = blockIp.getEndIp().split("\\.");


            if (matchBlockIp(ip, beginIp, endIp)) {


                return true;
            }

        }

        return false;

    }*/

    /**
     * 看是否匹配,匹配返回true
     *
     * @param ip
     * @param beginIp
     * @param endIp
     * @return
     */
    private boolean matchBlockIp(String[] ip, String[] beginIp, String[] endIp) {

        int diffIndex = findDiffIndex(beginIp, endIp);

        for (int i = 0; i < diffIndex; i++) {

            if (!ip[i].equals(beginIp[i])) {
                return false;
            }

        }

        if (Integer.parseInt(ip[diffIndex]) > Integer.parseInt(beginIp[diffIndex])
                && Integer.parseInt(ip[diffIndex]) < Integer.parseInt(endIp[diffIndex])) {
            return true;
        }
        return false;
    }

    //查找第几个不同的位,方便高位匹配
    private int findDiffIndex(String[] beginIp, String[] endIp) {

        for (int i = 0; i < 3; i++) {
            if (!beginIp[i].equals(endIp[i])) {
                return i;
            }
        }
        return 3;
    }
}
