package xyz.monkeytong.hongbao.utils;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Created by Zhongyi on 1/21/16.
 */
public class HongbaoSignature {
    private static final String TAG = "HongbaoSignature";
    public String sender, content, time, contentDescription = "", commentString;
    public boolean others;

    /**
     * 判断当前Node是否为红包，并且是否可抢（不包含指定的屏蔽词）
     */
    public boolean generateSignature(AccessibilityNodeInfo rootNodeInfo, AccessibilityNodeInfo node, String excludeWords) {
        try {
            /* The hongbao container node. It should be a LinearLayout. By specifying that, we can avoid text messages. */
            AccessibilityNodeInfo hongbaoNode = node.getParent();
            if (hongbaoNode == null || !"android.widget.LinearLayout".equals(hongbaoNode.getClassName()))
                return false;

            /* The text in the hongbao. Should mean something. */
            String hongbaoContent = hongbaoNode.getChild(0).getText().toString();
            if ("查看红包".equals(hongbaoContent)) return false;

            /* Check the user's exclude words list. */
            String[] excludeWordsArray = excludeWords.split(" +");
            for (String word : excludeWordsArray) {
                if (word.length() > 0 && hongbaoContent.contains(word)) return false;
            }

            /* The container node for a piece of message. It should be inside the screen.
                Or sometimes it will get opened twice while scrolling. */
            AccessibilityNodeInfo messageNode = hongbaoNode.getParent();

            Rect bounds = new Rect();
            messageNode.getBoundsInScreen(bounds);
            if (bounds.top < 0) return false; // 判断如果红包已经滑到顶部了，那就断定为不可领取的红包
            /* The sender and possible timestamp. Should mean something too. */
            String[] hongbaoInfo = getSenderContentDescriptionFromNode(messageNode);
            // 利用红包位置和最后一条提示信息“你领取了**的红包”来判断是否领取，取代下面的判断方式，否则同一个人连发的红包领不到（连发，时间戳会被隐藏）
            // 通过判断最后个红包和最后一条提示信息“你领取了黄Sir的红包”在屏幕上的位置，确定最后一个红包是否被领取
            // 两条信息是挨着的，红包的bottom >= 提示信息的top

//            if (this.getSignature(hongbaoInfo[0], hongbaoContent, hongbaoInfo[1]).equals(this.toString()))
//                return false;
            Rect lastPickedTipNodeBounds = getLastPickedTipNodeBounds(rootNodeInfo, "你领取了" + hongbaoInfo[0] + "的红包");
            if (lastPickedTipNodeBounds != null && lastPickedTipNodeBounds.top >= bounds.bottom) {
                return false;
            }

            /* So far we make sure it's a valid new coming hongbao. */
            this.sender = hongbaoInfo[0];
            this.time = hongbaoInfo[1];
            this.content = hongbaoContent;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private Rect getLastPickedTipNodeBounds(AccessibilityNodeInfo rootNodeInfo, String text) {
        AccessibilityNodeInfo tempNode;
        List<AccessibilityNodeInfo> nodes;

        if (text == null) return null;

        nodes = rootNodeInfo.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            tempNode = nodes.get(nodes.size() - 1);
            if (tempNode == null) return null;
            Rect bounds = new Rect();
            tempNode.getBoundsInScreen(bounds);
            return bounds;
        }
        return null;
    }

    @Override
    public String toString() {
        return this.getSignature(this.sender, this.content, this.time);
    }

    private String getSignature(String... strings) {
        String signature = "";
        for (String str : strings) {
            if (str == null) return null;
            signature += str + "|";
        }

        return signature.substring(0, signature.length() - 1);
    }

    public String getContentDescription() {
        return this.contentDescription;
    }

    public void setContentDescription(String description) {
        this.contentDescription = description;
    }

    /**
     * 获取红包信息：发送者，发送时间
     */
    private String[] getSenderContentDescriptionFromNode(AccessibilityNodeInfo node) {
        int count = node.getChildCount();
        String[] result = {"unknownSender", "unknownTime"};
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo thisNode = node.getChild(i);
            if ("android.widget.ImageView".equals(thisNode.getClassName()) && "unknownSender".equals(result[0])) {
                CharSequence contentDescription = thisNode.getContentDescription();
                if (contentDescription != null) //黄Sir头像
                    result[0] = contentDescription.toString().replaceAll("头像$", "");
            } else if ("android.widget.TextView".equals(thisNode.getClassName()) && "unknownTime".equals(result[1])) {
                CharSequence thisNodeText = thisNode.getText();//下午4:27
                if (thisNodeText != null) result[1] = thisNodeText.toString();
            }
        }
        return result;
    }

    public void cleanSignature() {
        this.content = "";
        this.time = "";
        this.sender = "";
    }
}
