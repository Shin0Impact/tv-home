package app.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Intentionally empty. Android only lets an app list the active media sessions (what is playing in
 * Netflix, YouTube, and so on) if it has been granted notification access. This class is the thing
 * that access is granted to; it never reads, stores or sends any notification.
 */
public class MediaListener extends NotificationListenerService {
}
