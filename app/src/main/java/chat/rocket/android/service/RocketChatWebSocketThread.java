package chat.rocket.android.service;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import bolts.Continuation;
import bolts.Task;
import bolts.TaskCompletionSource;
import chat.rocket.android.api.DDPClientWrapper;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.helper.TextUtils;
import chat.rocket.android.log.RCLog;
import chat.rocket.android.model.ServerConfig;
import chat.rocket.android.model.internal.Session;
import chat.rocket.android.realm_helper.RealmHelper;
import chat.rocket.android.realm_helper.RealmStore;
import chat.rocket.android.service.ddp.base.ActiveUsersSubscriber;
import chat.rocket.android.service.ddp.base.LoginServiceConfigurationSubscriber;
import chat.rocket.android.service.ddp.base.UserDataSubscriber;
import chat.rocket.android.service.observer.CurrentUserObserver;
import chat.rocket.android.service.observer.FileUploadingToS3Observer;
import chat.rocket.android.service.observer.FileUploadingWithUfsObserver;
import chat.rocket.android.service.observer.GetUsersOfRoomsProcedureObserver;
import chat.rocket.android.service.observer.LoadMessageProcedureObserver;
import chat.rocket.android.service.observer.MethodCallObserver;
import chat.rocket.android.service.observer.NewMessageObserver;
import chat.rocket.android.service.observer.NotificationItemObserver;
import chat.rocket.android.service.observer.ReactiveNotificationManager;
import chat.rocket.android.service.observer.SessionObserver;
import chat.rocket.android.service.observer.TokenLoginObserver;
import chat.rocket.android_ddp.DDPClientCallback;
import hugo.weaving.DebugLog;

/**
 * Thread for handling WebSocket connection.
 */
public class RocketChatWebSocketThread extends HandlerThread {
  private static final Class[] REGISTERABLE_CLASSES = {
      LoginServiceConfigurationSubscriber.class,
      ActiveUsersSubscriber.class,
      UserDataSubscriber.class,
      TokenLoginObserver.class,
      MethodCallObserver.class,
      SessionObserver.class,
      LoadMessageProcedureObserver.class,
      GetUsersOfRoomsProcedureObserver.class,
      NewMessageObserver.class,
      CurrentUserObserver.class,
      ReactiveNotificationManager.class,
      NotificationItemObserver.class,
      FileUploadingToS3Observer.class,
      FileUploadingWithUfsObserver.class
  };
  private final Context appContext;
  private final String serverConfigId;
  private final RealmHelper defaultRealm;
  private final RealmHelper serverConfigRealm;
  private final ArrayList<Registrable> listeners = new ArrayList<>();
  private DDPClientWrapper ddpClient;
  private boolean listenersRegistered;

  private RocketChatWebSocketThread(Context appContext, String serverConfigId) {
    super("RC_thread_" + serverConfigId);
    this.appContext = appContext;
    this.serverConfigId = serverConfigId;
    defaultRealm = RealmStore.getDefault();
    serverConfigRealm = RealmStore.getOrCreate(serverConfigId);
  }

  /**
   * create new Thread.
   */
  @DebugLog
  public static Task<RocketChatWebSocketThread> getStarted(Context appContext,
                                                           ServerConfig config) {
    TaskCompletionSource<RocketChatWebSocketThread> task = new TaskCompletionSource<>();
    new RocketChatWebSocketThread(appContext, config.getServerConfigId()) {
      @Override
      protected void onLooperPrepared() {
        try {
          super.onLooperPrepared();
          task.setResult(this);
        } catch (Exception exception) {
          task.setError(exception);
        }
      }
    }.start();
    return task.getTask()
        .onSuccessTask(_task ->
            _task.getResult().connect().onSuccessTask(__task -> _task));
  }

  /**
   * destroy the thread.
   */
  @DebugLog
  public static void destroy(RocketChatWebSocketThread thread) {
    thread.quit();
  }

  @Override
  protected void onLooperPrepared() {
    super.onLooperPrepared();
    forceInvalidateTokens();
  }

  private void forceInvalidateTokens() {
    serverConfigRealm.executeTransaction(realm -> {
      Session session = Session.queryDefaultSession(realm).findFirst();
      if (session != null
          && !TextUtils.isEmpty(session.getToken())
          && (session.isTokenVerified() || !TextUtils.isEmpty(session.getError()))) {
        session.setTokenVerified(false);
        session.setError(null);
      }
      return null;
    }).continueWith(new LogcatIfError());
  }

  @Override
  public boolean quit() {
    if (isAlive()) {
      new Handler(getLooper()).post(() -> {
        RCLog.d("thread %s: quit()", Thread.currentThread().getId());
        unregisterListeners();
        RocketChatWebSocketThread.super.quit();
      });
      return true;
    } else {
      return super.quit();
    }
  }

  /**
   * synchronize the state of the thread with ServerConfig.
   */
  @DebugLog
  public void keepAlive() {
    if (ddpClient == null || !ddpClient.isConnected()) {
      defaultRealm.executeTransaction(realm -> {
        ServerConfig config = realm.where(ServerConfig.class)
            .equalTo("serverConfigId", serverConfigId)
            .findFirst();
        if (config != null && config.getState() == ServerConfig.STATE_CONNECTED) {
          config.setState(ServerConfig.STATE_READY);
          quit();
        }
        return null;
      });
    }
  }

  private void prepareWebSocket(String hostname) {
    if (ddpClient == null || !ddpClient.isConnected()) {
      ddpClient = DDPClientWrapper.create(hostname);
    }
  }

  @DebugLog
  private Task<Void> connect() {
    final ServerConfig config = defaultRealm.executeTransactionForRead(realm ->
        realm.where(ServerConfig.class).equalTo("serverConfigId", serverConfigId).findFirst());

    prepareWebSocket(config.getHostname());
    return ddpClient.connect(config.getSession()).onSuccessTask(task -> {
      final String session = task.getResult().session;
      defaultRealm.executeTransaction(realm ->
          realm.createOrUpdateObjectFromJson(ServerConfig.class, new JSONObject()
              .put("serverConfigId", serverConfigId)
              .put("session", session))
      ).onSuccess(_task -> serverConfigRealm.executeTransaction(realm -> {
        Session sessionObj = Session.queryDefaultSession(realm).findFirst();

        if (sessionObj == null) {
          realm.createOrUpdateObjectFromJson(Session.class,
              new JSONObject().put("sessionId", Session.DEFAULT_ID));
        }
        return null;
      })).continueWith(new LogcatIfError());
      return task;
    }).onSuccess(new Continuation<DDPClientCallback.Connect, Void>() {
      // TODO type detection doesn't work due to retrolambda's bug...
      @Override
      public Void then(Task<DDPClientCallback.Connect> task)
          throws Exception {
        registerListeners();

        // handling WebSocket#onClose() callback.
        task.getResult().client.getOnCloseCallback().onSuccess(_task -> {
          quit();
          return null;
        }).continueWithTask(_task -> {
          if (_task.isFaulted()) {
            ServerConfig.logConnectionError(serverConfigId, _task.getError());
          }
          return _task;
        });

        return null;
      }
    }).continueWithTask(task -> {
      if (task.isFaulted()) {
        Exception error = task.getError();
        if (error instanceof DDPClientCallback.Connect.Timeout) {
          ServerConfig.logConnectionError(serverConfigId, new Exception("Connection Timeout"));
        } else {
          ServerConfig.logConnectionError(serverConfigId, task.getError());
        }
      }
      return task;
    });
  }

  //@DebugLog
  private void registerListeners() {
    if (!Thread.currentThread().getName().equals("RC_thread_" + serverConfigId)) {
      // execute in Looper.
      new Handler(getLooper()).post(() -> {
        registerListeners();
      });
      return;
    }

    if (listenersRegistered) {
      return;
    }
    listenersRegistered = true;

    final ServerConfig config = defaultRealm.executeTransactionForRead(realm ->
        realm.where(ServerConfig.class).equalTo("serverConfigId", serverConfigId).findFirst());
    final String hostname = config.getHostname();

    for (Class clazz : REGISTERABLE_CLASSES) {
      try {
        Constructor ctor = clazz.getConstructor(Context.class, String.class, RealmHelper.class,
            DDPClientWrapper.class);
        Object obj = ctor.newInstance(appContext, hostname, serverConfigRealm, ddpClient);

        if (obj instanceof Registrable) {
          Registrable registrable = (Registrable) obj;
          registrable.register();
          listeners.add(registrable);
        }
      } catch (Exception exception) {
        RCLog.w(exception, "Failed to register listeners!!");
      }
    }
  }

  @DebugLog
  private void unregisterListeners() {
    if (!listenersRegistered) {
      return;
    }

    Iterator<Registrable> iterator = listeners.iterator();
    while (iterator.hasNext()) {
      Registrable registrable = iterator.next();
      registrable.unregister();
      iterator.remove();
    }
    if (ddpClient != null) {
      ddpClient.close();
      ddpClient = null;
    }
    listenersRegistered = false;
  }
}
