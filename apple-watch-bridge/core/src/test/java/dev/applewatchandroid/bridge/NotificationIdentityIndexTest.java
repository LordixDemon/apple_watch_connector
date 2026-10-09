package dev.applewatchandroid.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationIdentityIndexTest {
    @Test public void twoChatsInOneAppHaveDistinctStableExactIds() {
        var index = new NotificationIdentityIndex(2);
        var a = index.register("chat-a", "com.chat");
        var b = index.register("chat-b", "com.chat");
        assertNotEquals(a.publisherId(), b.publisherId());
        assertSame(a, index.register("chat-a", "com.chat"));
        assertSame(b, index.match(b.publisherId(), b.recordId(), "com.chat"));
        assertNull(index.match(a.publisherId(), b.recordId(), "com.chat"));
        assertNull(index.match(a.publisherId(), a.recordId(), "other.app"));
        assertNull(index.match(null, null, "com.chat"));
        assertNull(index.match("unknown", null, "com.chat"));
    }
    @Test public void capacityAndRemovalNeverRedirectAnOldAction() {
        var index = new NotificationIdentityIndex(1);
        var a = index.register("chat-a", "com.chat");
        assertNull(index.register("chat-b", "com.chat"));
        assertSame(a, index.remove("chat-a"));
        var b = index.register("chat-a", "com.chat");
        assertNotEquals(a.publisherId(), b.publisherId());
        assertNull(index.match(a.publisherId(), a.recordId(), "com.chat"));
        assertSame(b, index.match(b.publisherId(), null, "com.chat"));
        index.clear();
        assertNull(index.match(b.publisherId(), null, "com.chat"));
    }
    @Test public void reusedAndroidKeyCannotCrossPackages() {
        var index = new NotificationIdentityIndex(1);
        index.register("key", "com.chat");
        assertNull(index.register("key", "other.app"));
        assertNull(index.register("", "com.chat"));
        assertNull(index.register(null, "com.chat"));
    }
    @Test public void lightsRequireExactTokenOrPublisherAndSection() {
        var index = new NotificationIdentityIndex(2);
        var a = index.register("a", "com.chat");
        var b = index.register("b", "com.chat");
        assertSame(a, index.matchLights(null, null, a.replyToken()));
        assertSame(b, index.matchLights(b.publisherId(), "com.chat", null));
        assertNull(index.matchLights(a.publisherId(), "com.chat", b.replyToken()));
        assertNull(index.matchLights(a.publisherId(), "other.app", a.replyToken()));
        assertNull(index.matchLights(null, null, null));
        index.remove("a");
        assertNull(index.matchLights(null, null, a.replyToken()));
    }
}
