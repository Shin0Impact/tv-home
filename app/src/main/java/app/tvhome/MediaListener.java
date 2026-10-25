package app.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android only lets an app list the active media sessions (what is playing in Netflix, YouTube, and
 * so on) if it has been granted notification access; this class is what that access is granted to.
 * While running it notices when an app starts playing something and tells {@link History}, so
 * "Recently watched" works even while TV Home is not on screen. The same access lets the quick
 * settings panel list the notifications that are showing right now; they are read only when the
 * panel opens, never stored and never sent anywhere.
 */
public class MediaListener extends NotificationListenerService {

    /** The connected listener, if notification access is on; the panel reads notifications through it. */
    private static volatile MediaListener instance;

    /** One notification, reduced to what the panel shows. */
    static final class Note {
        String key;
        String pkg;
        String label;
        CharSequence title;
        CharSequence text;
        PendingIntent intent;
        Drawable icon;
        boolean clearable;
    }

    static boolean connected() {
        return instance != null;
    }

    /** The notifications showing right now, newest first. Empty when access is off. */
    static List<Note> notes(Context ctx) {
        List<Note> out = new ArrayList<>();
        MediaListener me = instance;
        if (me == null) {
            return out;
        }
        try {
            StatusBarNotification[] all = me.getActiveNotifications();
            if (all == null) {
                return out;
            }
            PackageManager pm = ctx.getPackageManager();
            for (StatusBarNotification sbn : all) {
                Notification n = sbn.getNotification();
                if (n == null || ctx.getPackageName().equals(sbn.getPackageName())
                        || Notification.CATEGORY_TRANSPORT.equals(n.category)
                        || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                        || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                    continue;
                }
                CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (text == null || text.length() == 0) {
                    text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                }
                if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) {
                    continue;
                }
                Note note = new Note();
                note.key = sbn.getKey();
                note.pkg = sbn.getPackageName();
                note.title = title;
                note.text = text;
                note.intent = n.contentIntent;
                note.clearable = sbn.isClearable();
                String pkg = sbn.getPackageName();
                String label = pkg.substring(pkg.lastIndexOf('.') + 1);
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) {
                    // not visible to us: keep the short package name
                }
                note.label = label;
                try {
                    if (n.getSmallIcon() != null) {
                        note.icon = n.getSmallIcon().loadDrawable(ctx);
                    }
                } catch (Exception ignored) {
                    // no icon
                }
                out.add(note);
            }
        } catch (Exception ignored) {
            // access went away while reading
        }
        java.util.Collections.reverse(out);
        return out;
    }

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        HomeActivity home = HomeActivity.current;
        if (home != null) {
            home.updateBadges();
        }
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        HomeActivity home = HomeActivity.current;
        if (home != null) {
            home.updateBadges();
        }
    }

    static int count(Context ctx) {
        return notes(ctx).size();
    }

    static void dismiss(String key) {
        MediaListener me = instance;
        if (me != null) {
            try {
                me.cancelNotification(key);
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    static void dismissAll() {
        MediaListener me = instance;
        if (me != null) {
            try {
                me.cancelAllNotifications();
            } catch (Exception ignored) {
                // nothing to clear
            }
        }
    }

    private MediaSessionManager manager;
    private final Map<MediaSession.Token, MediaController> controllers = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::watch;

    @Override
    public void onListenerConnected() {
        instance = this;
        try {
            manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaListener.class);
            manager.addOnActiveSessionsChangedListener(sessionsListener, me);
            watch(manager.getActiveSessions(me));
        } catch (Exception ignored) {
            // no access yet
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) {
            instance = null;
        }
        try {
            if (manager != null) {
                manager.removeOnActiveSessionsChangedListener(sessionsListener);
            }
        } catch (Exception ignored) {
            // nothing to do
        }
        for (Map.Entry<MediaSession.Token, MediaController.Callback> e : callbacks.entrySet()) {
            MediaController c = controllers.get(e.getKey());
            if (c != null) {
                c.unregisterCallback(e.getValue());
            }
        }
        callbacks.clear();
        controllers.clear();
    }

    private void watch(List<MediaController> list) {
        if (list == null) {
            return;
        }
        Set<MediaSession.Token> live = new HashSet<>();
        for (final MediaController c : list) {
            final MediaSession.Token token = c.getSessionToken();
            live.add(token);
            if (callbacks.containsKey(token)) {
                continue;
            }
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    History.recordAsync(MediaListener.this, c);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    History.recordAsync(MediaListener.this, c);
                }
            };
            c.registerCallback(cb);
            callbacks.put(token, cb);
            controllers.put(token, c);
            History.recordAsync(this, c);
        }
        Iterator<Map.Entry<MediaSession.Token, MediaController.Callback>> it = callbacks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MediaSession.Token, MediaController.Callback> e = it.next();
            if (!live.contains(e.getKey())) {
                MediaController gone = controllers.remove(e.getKey());
                if (gone != null) {
                    gone.unregisterCallback(e.getValue());
                }
                it.remove();
            }
        }
    }
}
