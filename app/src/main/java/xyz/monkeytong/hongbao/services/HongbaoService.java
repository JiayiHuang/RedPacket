package xyz.monkeytong.hongbao.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.apkfuns.logutils.LogUtils;

import java.util.List;

import xyz.monkeytong.hongbao.utils.HongbaoSignature;
import xyz.monkeytong.hongbao.utils.PowerUtil;

public class HongbaoService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "HongbaoService";
    private static final String WECHAT_DETAILS_EN = "Details";
    private static final String WECHAT_BETTER_LUCK_EN = "Better luck next time!";
    private static final String WECHAT_DETAILS_CH = "红包详情";
    private static final String WECHAT_BETTER_LUCK_CH = "手慢了，红包派完了";
    private static final String WECHAT_EXPIRES_CH = "已超过24小时";
    private static final String WECHAT_VIEW_SELF_CH = "查看红包";
    private static final String WECHAT_VIEW_OTHERS_CH = "领取红包";
    private static final String WECHAT_NOTIFICATION_TIP = "[微信红包]";// 通知栏内，有红包时，必有的内容在头部
    //    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = "LuckyMoneyReceiveUI";
    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = "com.tencent.mm";
    private static final String WECHAT_LUCKMONEY_DETAIL_ACTIVITY = "LuckyMoneyDetailUI";
    private static final String WECHAT_LUCKMONEY_GENERAL_ACTIVITY = "LauncherUI";
    private static final String WECHAT_LUCKMONEY_CHATTING_ACTIVITY = "com.tencent.mm/.ui.LauncherUI";
    private String currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;

    /*

    会话页面 –红包开启页面：
        com.tencent.mm/.plugin.luckymoney.ui.LuckyMoneyReceiveUI

    红包领取详情页：
        com.tencent.mm/.plugin.luckymoney.ui.LuckyMoneyDetailUI

     */
    /**
     * 根据页面的ActivityName进行判断当前页面，进行相应的操作。因为微信的红包打开页面 getRootInActiveWindow 获取Node为Null
     */
    private static String ACTIVITY_NAME_WAIT_OPEN = "LuckyMoneyReceiveUI";
    /**
     * 根据页面的ActivityName进行判断当前页面，进行相应的操作。因为微信的红包领取详情 getRootInActiveWindow 获取Node为Null
     */
    private static String ACTIVITY_NAME_DETAIL_LIST = "LuckyMoneyDetailUI";
    private AccessibilityNodeInfo rootNodeInfo;
    /**
     * 已点开的红包，可领取，但未领取
     */
    private AccessibilityNodeInfo mUnpackNode;
    /**
     * 会话列表中接受到可领取的节点，显示可领取
     */
    private AccessibilityNodeInfo mReceiveNode;
    /**
     * 判断红包是否可领取(可能被领完或已过期)
     */
    private boolean mLuckyMoneyPicked;
    /**
     * 是否接受到可领取的红包
     */
    private boolean mLuckyMoneyReceived;
    /**
     * 可领取的红包数，未打开的
     */
    private int mUnpackCount = 0;
    /**
     * 是否有可抢红包,但不知道是否已被抢完或过期
     */
    private boolean mHasToDoRedPacket = false;
    private boolean mListMutex = false;
    /**
     * 是否正在检查聊天消息
     */
    private boolean mIsWatching = false;
    /**
     * 最后一个红包是否已被领完或过期（不可领取状态）
     * 此变量依据为当前页面红包数量，不可靠
     */
//    private boolean isLastOutOrExpire = false;
    private int currentPackNum = 0;
    private HongbaoSignature signature = new HongbaoSignature();

    private PowerUtil powerUtil;
    private SharedPreferences sharedPreferences;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (sharedPreferences == null) return;
        Log.i(TAG, ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> New Event <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
        setCurrentActivityName(event);

        checkActivityNameChangedOrNot();


        // 是否从红包领取详情页返回
        if (sharedPreferences.getBoolean("pref_back_from_receive_list", true)
                && event.getClassName().toString().contains(ACTIVITY_NAME_DETAIL_LIST)) {
            String timeDelayStr = sharedPreferences.getString("pref_back_from_receive_list_delay", "100");
            int timeDelay = 100;
            try {
                timeDelay = Integer.parseInt(timeDelayStr);
            } catch (Exception ignored) {

            } finally {
                timeDelay = timeDelay <= 100 ? 100 : timeDelay;
            }
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    back2List();
                }
            }, timeDelay);
            return;
        }

        if (currentActivityName.contains(ACTIVITY_NAME_WAIT_OPEN)
                && android.os.Build.VERSION.SDK_INT > 23) {
            //当前在红包待开页面，去拆红包
            openPacket();
        }
        /* 检测通知消息 */
        if (!mHasToDoRedPacket) {
            // pref_watch_notification：读取消息通知中的红包提示并进入聊天页
            if (sharedPreferences.getBoolean("pref_watch_notification", false) && watchNotifications(event))
                return;
            // pref_watch_list：读取聊天列表中的红包提示并进入聊天页 -- 新版本失效
//            if (sharedPreferences.getBoolean("pref_watch_list", false) && watchList(event)) return;
            mListMutex = false;
        }

        if (!mIsWatching) {
            mIsWatching = true;
            // pref_watch_list：自动拆红包
//            if (sharedPreferences.getBoolean("pref_watch_chat", false))
//                watchChat(event); // 根据配置判断是否自动拆红包
            watchChat(event); // 一直自动拆红包
            mIsWatching = false;
        }

    }

    /**
     * 23版本以上，微信红包领取相关页面，调用 getRootInActiveWindow 方式获取不到 Node，
     * 所以采用匹配ActivityName的方式来判断当前页面属于哪个阶段。
     */
    private void checkActivityNameChangedOrNot() {
        String waitOpenActivityName = sharedPreferences.getString("pref_activity_name_wait_open", "");
        String receiveDetailActivityName = sharedPreferences.getString("pref_activity_name_receive_list", "");
        if (!TextUtils.isEmpty(waitOpenActivityName)) {
            LogUtils.wtf("ACTIVITY_NAME_WAIT_OPEN : " + ACTIVITY_NAME_WAIT_OPEN + "change to ===>> " + waitOpenActivityName);
            ACTIVITY_NAME_WAIT_OPEN = waitOpenActivityName;
        }
        if (!TextUtils.isEmpty(receiveDetailActivityName)) {
            LogUtils.wtf("ACTIVITY_NAME_DETAIL_LIST : " + ACTIVITY_NAME_DETAIL_LIST + "change to ===>> " + receiveDetailActivityName);
            ACTIVITY_NAME_DETAIL_LIST = receiveDetailActivityName;
        }
    }


    private void watchChat(AccessibilityEvent event) {
        this.rootNodeInfo = getRootInActiveWindow();
        Log.i(TAG, "watchChat: ------------------------------------------------- rootNodeInfo==null?" + (rootNodeInfo == null));
        int packNum = getPackNum(WECHAT_VIEW_OTHERS_CH);
        if (currentActivityName.equals("com.tencent.mm/.ui.LauncherUI")) {
//            Log.d(TAG, "packNum == " + packNum + ", prePackNum == " + currentPackNum + ", isLastOutOrExpire == " + isLastOutOrExpire);
            if (packNum != 0 && packNum != currentPackNum) {
//                isLastOutOrExpire = false;
                currentPackNum = packNum;
            }
        }

        if (rootNodeInfo == null) {
            return;
        }

        mReceiveNode = null;
        mUnpackNode = null;

        checkNodeInfo(event);

        /* 如果已经接收到红包并且还没有戳开 */
        if (mLuckyMoneyReceived && !mLuckyMoneyPicked && (mReceiveNode != null)) {
            mHasToDoRedPacket = true;
            // 点开聊天列表的红包
            mReceiveNode.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            mLuckyMoneyReceived = false;
            mLuckyMoneyPicked = true;
            mUnpackCount = 1;
        }
        /* 如果戳开但还未领取 */
//        Log.i(TAG, "watchChat # mUnpackCount === " + mUnpackCount + ", mUnpackNode == null : " + (mUnpackNode == null));
        if (mUnpackCount == 1 && (mUnpackNode != null)) {
            int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;
            new android.os.Handler().postDelayed(
                    new Runnable() {
                        public void run() {
                            try {
                                openPacket();
                            } catch (Exception e) {
                                mHasToDoRedPacket = false;
                                mLuckyMoneyPicked = false;
                                mUnpackCount = 0;
                            }
                        }
                    },
                    delayFlag);
        }
        Log.i(TAG, ">>>>>>>>>>>>>>>>\n mLuckyMoneyPicked == " + mLuckyMoneyPicked
                + ", \n mHasToDoRedPacket == " + mHasToDoRedPacket
                + ", \n currentActivityName == " + currentActivityName
                + ", \n mUnpackCount == " + mUnpackCount
        );
    }

    private void openPacket() {
        Log.i(TAG, "openPacket: -----------------------------------------------------------");
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float dpi = metrics.density;
        if (android.os.Build.VERSION.SDK_INT <= 23) {
            mUnpackNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else {
            if (android.os.Build.VERSION.SDK_INT > 23) {
                Path path = new Path();

//                386 1018 694 1326
//
//
//                if (640 == dpi) {
//                    path.moveTo(720, 1575);
//                } else {
//                    path.moveTo(540, 1060);
//                }
//                180 * 3 391*3
                path.moveTo(180 * dpi, 391 * dpi);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 100, 50)).build();
                dispatchGesture(gestureDescription, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.i(TAG, "onCompleted");
                        mHasToDoRedPacket = false;
                        super.onCompleted(gestureDescription);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.i(TAG, "onCancelled");
                        mHasToDoRedPacket = false;
                        super.onCancelled(gestureDescription);
                    }
                }, null);

            }
        }
    }

    /**
     * 获取当前ActivityName
     */
    private void setCurrentActivityName(AccessibilityEvent event) {
        // 界面内打开了一个PopupWindow、Menu、Dialog等，不是这些事件，那就肯定与红包无关，当前ActivityName也不会变
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        try {
            ComponentName componentName = new ComponentName(
                    event.getPackageName().toString(),
                    event.getClassName().toString()
            );

            getPackageManager().getActivityInfo(componentName, 0);
            currentActivityName = componentName.flattenToShortString();
        } catch (PackageManager.NameNotFoundException e) {
            currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;
        }
        LogUtils.wtf("CurrentActivityName === " + currentActivityName + "\n");
    }

    /**
     * 读取聊天列表中的红包提示并进入聊天页, Api 23+ findAccessibilityNodeInfosByText 此方法不可用了
     */
    private boolean watchList(AccessibilityEvent event) {
        if (mListMutex) return false;
        mListMutex = true;
        AccessibilityNodeInfo eventSource = event.getSource();
        // Not a message
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventSource == null)
            return false;
        // 23+ 此api不可用了，findAccessibilityNodeInfosByText
        List<AccessibilityNodeInfo> nodes = eventSource.findAccessibilityNodeInfosByText(WECHAT_NOTIFICATION_TIP);
        for (int i = 0; i < nodes.size(); i++) {
            if (!nodes.get(i).getText().toString().contains(WECHAT_NOTIFICATION_TIP)) {
                nodes.remove(i);
            }

        }
        //增加条件判断currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY)
        //避免当订阅号中出现标题为“[微信红包]拜年红包”（其实并非红包）的信息时误判
        if (!nodes.isEmpty() && currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY)) {
            AccessibilityNodeInfo nodeToClick = nodes.get(0);
            if (nodeToClick == null) return false;
            CharSequence contentDescription = nodeToClick.getContentDescription();
            if (contentDescription != null && !signature.getContentDescription().equals(contentDescription)) {
                nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                signature.setContentDescription(contentDescription.toString());
                return true;
            }
        }
        return false;
    }

    /**
     * 读取消息通知中的红包提示并进入聊天页
     */
    private boolean watchNotifications(AccessibilityEvent event) {
        // Not a notification
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            return false;
//        isLastOutOrExpire = false;
        // Not a hongbao
        String tip = event.getText().toString();
        if (!tip.contains(WECHAT_NOTIFICATION_TIP)) return true;

        Parcelable parcelable = event.getParcelableData();
        if (parcelable instanceof Notification) {
            Notification notification = (Notification) parcelable;
            try {
                /* 清除signature,避免进入会话后误判 */
                signature.cleanSignature();

                notification.contentIntent.send();
            } catch (PendingIntent.CanceledException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    @Override
    public void onInterrupt() {

    }

    /**
     * 已经戳开红包，但未点击开红包，通过遍历来找“開”的按钮Button
     */
    private AccessibilityNodeInfo findOpenButton(AccessibilityNodeInfo node) {
        Log.i(TAG, "findOpenButton ======================>>>>>>>>>>>>>");
        if (node == null)
            return null;

        //非layout元素
        Log.i(TAG, "findOpenButton # node.getChildCount == " + (node.getChildCount()));
        if (node.getChildCount() == 0) {
            if ("android.widget.Button".equals(node.getClassName()))
                return node;
            else
                return null;
        }

        //layout元素，遍历找button
        AccessibilityNodeInfo button;
        for (int i = 0; i < node.getChildCount(); i++) {
//            String nodeClassName = "";
//            try {
//                nodeClassName = (String) node.getChild(i).getClassName();
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//            Log.i(TAG, "findOpenButton # nodeClassName[" + i + "] == " + (nodeClassName));

            button = findOpenButton(node.getChild(i));
            if (button != null)
                return button;
        }
        return null;
    }

    private void checkNodeInfo(AccessibilityEvent event) {
//        Log.i(TAG, "checkNodeInfo # isLastOutOrExpire === " + isLastOutOrExpire);
//        if (isLastOutOrExpire) return;

        if (this.rootNodeInfo == null) return;

        if (signature.commentString != null) {// 判断自动回复
            sendComment();
            signature.commentString = null;
        }

        /* 聊天会话窗口，遍历节点匹配“领取红包”和"查看红包" */
//        AccessibilityNodeInfo node1 = (sharedPreferences.getBoolean("pref_watch_self", false)) ? // 是否监听自己发的红包
//                this.getTheLastNode(WECHAT_VIEW_OTHERS_CH, WECHAT_VIEW_SELF_CH) : this.getTheLastNode(WECHAT_VIEW_OTHERS_CH);
        AccessibilityNodeInfo node1 = this.getTheLastNode(WECHAT_VIEW_OTHERS_CH);// 只监听别人发的红包

        Log.i(TAG, "checkNodeInfo # node1 == null : " + (node1 == null));
        Log.i(TAG, "checkNodeInfo # currentActivityName === " + currentActivityName);
        //聊天窗口的回话列表中，有可领取的红包
        if (node1 != null && (currentActivityName.contains(WECHAT_LUCKMONEY_CHATTING_ACTIVITY) || currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY))) {
            String excludeWords = sharedPreferences.getString("pref_watch_exclude_words", "");
            if (this.signature.generateSignature(rootNodeInfo, node1, excludeWords)) {
                long eventTime = event.getEventTime();
                Log.i(TAG, "checkNodeInfo # eventTime === " + eventTime + ", mReceiveNode 可领取的红包 node 赋值");
                mLuckyMoneyReceived = true;
                mLuckyMoneyPicked = false;
                mReceiveNode = node1;
                Log.d(TAG, this.signature.toString());
            }
            return;
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        Log.i(TAG, "findOpenButton ======================>>>>>>>>>>>>> from checkNodeInfo");
//        findProgressBar(this.rootNodeInfo)
        AccessibilityNodeInfo node2 = findOpenButton(this.rootNodeInfo);
        Log.i(TAG, "checkNodeInfo # node2 == null : " + (node2 == null));
        if (node2 != null && "android.widget.Button".equals(node2.getClassName()) && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)) {
            Log.i(TAG, "checkNodeInfo # node2.getClassName === " + node2.getClassName());
            mUnpackNode = node2;
            mUnpackCount += 1;
            return;
        }

        /* 戳开红包，红包已被抢完或过期，遍历节点匹配“红包详情”和“手慢了”  这个方法也失效了*/
        boolean hasNodes = this.hasOneOfThoseNodes(
                WECHAT_BETTER_LUCK_CH, WECHAT_DETAILS_CH,
                WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH);
        if (mHasToDoRedPacket && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && hasNodes
                && (currentActivityName.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)
                || currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY))) {
            Log.i(TAG, "checkNodeInfo # 无效红包 mLuckyMoneyPicked == " + mLuckyMoneyPicked);
            back2List();
        }
    }

    private void back2List() {
        Log.i(TAG, "back2List: ----------------------------------------------------");
        mHasToDoRedPacket = false;
        mLuckyMoneyPicked = false;
        mUnpackCount = 0;
        performGlobalAction(GLOBAL_ACTION_BACK);
        signature.commentString = generateCommentString();

//        isLastOutOrExpire = false;
    }

    private boolean findProgressBar(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        //非layout元素
        if (node.getChildCount() == 0) {
            if ("android.widget.ProgressBar".equals(node.getClassName()))
                return true;
            else
                return false;
        }
        boolean result = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findProgressBar(node.getChild(i))) {

            }
        }
        return false;
    }


    private void sendComment() {
        try {
            AccessibilityNodeInfo outNode =
                    getRootInActiveWindow().getChild(0).getChild(0);
            AccessibilityNodeInfo nodeToInput = outNode.getChild(outNode.getChildCount() - 1).getChild(0).getChild(1);

            if ("android.widget.EditText".equals(nodeToInput.getClassName())) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, signature.commentString);
                nodeToInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
        } catch (Exception e) {
            // Not supported
        }
    }


    private boolean hasOneOfThoseNodes(String... texts) {
        List<AccessibilityNodeInfo> nodes;
        for (String text : texts) {
            if (text == null) continue;

            nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);

            if (nodes != null && !nodes.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 获取当前页面指定节点的数量
     * （当前为了获取红包数量，为了区别已被领完和过期两种没有提示信息的红包，防止该两种一直被打开）
     *
     * @return
     */
    private int getPackNum(String text) {
        if (rootNodeInfo != null) {
            List<AccessibilityNodeInfo> packs = rootNodeInfo.findAccessibilityNodeInfosByText(text);
            Log.i(TAG, "getPackNum: packageSize === " + packs.size());
            return packs == null ? 0 : packs.size();
        } else return 0;
    }

    protected AccessibilityNodeInfo getTheLastNode(String... texts) {
        int bottom = 0;
        AccessibilityNodeInfo lastNode = null, tempNode;
        List<AccessibilityNodeInfo> nodes;

        for (String text : texts) {
            if (text == null) continue;

            nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) {
                tempNode = nodes.get(nodes.size() - 1);
                if (tempNode == null) return null;
                Rect bounds = new Rect();
                tempNode.getBoundsInScreen(bounds);
                if (bounds.bottom > bottom) {
                    bottom = bounds.bottom;
                    lastNode = tempNode;
                    signature.others = text.equals(WECHAT_VIEW_OTHERS_CH);
                }
            }
        }
        return lastNode;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        this.watchFlagsFromPreference();
    }

    private void watchFlagsFromPreference() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        this.powerUtil = new PowerUtil(this);
        Boolean watchOnLockFlag = sharedPreferences.getBoolean("pref_watch_on_lock", false);
        this.powerUtil.handleWakeLock(watchOnLockFlag);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("pref_watch_on_lock")) {
            Boolean changedValue = sharedPreferences.getBoolean(key, false);
            this.powerUtil.handleWakeLock(changedValue);
        }
    }

    @Override
    public void onDestroy() {
        this.powerUtil.handleWakeLock(false);
        super.onDestroy();
    }

    private String generateCommentString() {
        if (!signature.others) return null;

        Boolean needComment = sharedPreferences.getBoolean("pref_comment_switch", false);
        if (!needComment) return null;

        String[] wordsArray = sharedPreferences.getString("pref_comment_words", "").split(" +");
        if (wordsArray.length == 0) return null;

        Boolean atSender = sharedPreferences.getBoolean("pref_comment_at", false);
        if (atSender) {
            return "@" + signature.sender + " " + wordsArray[(int) (Math.random() * wordsArray.length)];
        } else {
            return wordsArray[(int) (Math.random() * wordsArray.length)];
        }
    }
}
