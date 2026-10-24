package app.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android only lets an app list the active media sessions (what is playing in Netflix, YouTube, and
 * so on) if it has been granted notification access; this class is what that access is granted to.
 * It never reads, stores or sends any notification. While running it notices when an app starts
 * playing something and tells {@link History}, so "Recently watched" works even while TV Home is
 * not on screen.
 */
public class MediaListener extends NotificationListenerService {

    private MediaSessionManager manager;
    private final Map<MediaSession.Token, MediaController> controllers = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::watch;

    @Override
    public void onListenerConnected() {
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
