package chat.rocket.android.model.internal;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * ViewData model for notification.
 */
public class NotificationItem extends RealmObject {
  @PrimaryKey private String roomId;
  private String title;
  private String description;
  private int unreadCount;
  private String senderName;
  private long contentUpdatedAt; //subscription._updatedAt
  private long lastSeenAt; //max(notification dismissed, subscription.ls)

  public String getRoomId() {
    return roomId;
  }

  public void setRoomId(String roomId) {
    this.roomId = roomId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public int getUnreadCount() {
    return unreadCount;
  }

  public void setUnreadCount(int unreadCount) {
    this.unreadCount = unreadCount;
  }

  public String getSenderName() {
    return senderName;
  }

  public void setSenderName(String senderName) {
    this.senderName = senderName;
  }

  public long getContentUpdatedAt() {
    return contentUpdatedAt;
  }

  public void setContentUpdatedAt(long contentUpdatedAt) {
    this.contentUpdatedAt = contentUpdatedAt;
  }

  public long getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(long lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }
}
