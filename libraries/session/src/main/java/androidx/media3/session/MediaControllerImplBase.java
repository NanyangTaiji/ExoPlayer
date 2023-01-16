/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkIndex;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.session.MediaUtils.calculateBufferedPercentage;
import static androidx.media3.session.MediaUtils.intersect;
import static androidx.media3.session.MediaUtils.mergePlayerInfo;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.BundleListRetriever;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.IllegalSeekPositionException;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Commands;
import androidx.media3.common.Player.Events;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Rating;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Period;
import androidx.media3.common.Timeline.RemotableTimeline;
import androidx.media3.common.Timeline.Window;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.BundleableUtil;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaController.MediaControllerImpl;
import androidx.media3.session.PlayerInfo.BundlingExclusions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.NonNull;

@SuppressWarnings("FutureReturnValueIgnored") // TODO(b/138091975): Not to ignore if feasible
/* package */ class MediaControllerImplBase implements MediaControllerImpl {

  public static final String TAG = "MCImplBase";

  private static final long RELEASE_TIMEOUT_MS = 30_000;

  private final MediaController instance;
  protected final SequencedFutureManager sequencedFutureManager;
  protected final MediaControllerStub controllerStub;

  private final Context context;
  private final SessionToken token;
  private final Bundle connectionHints;
  private final IBinder.DeathRecipient deathRecipient;
  private final SurfaceCallback surfaceCallback;
  private final ListenerSet<Listener> listeners;
  private final FlushCommandQueueHandler flushCommandQueueHandler;
  private final ArraySet<Integer> pendingMaskingSequencedFutureNumbers;

  @Nullable private SessionToken connectedToken;
  @Nullable private SessionServiceConnection serviceConnection;
  private boolean released;
  private PlayerInfo playerInfo;
  @Nullable private PendingIntent sessionActivity;
  private SessionCommands sessionCommands;
  private Commands playerCommandsFromSession;
  private Commands playerCommandsFromPlayer;
  private Commands intersectedPlayerCommands;
  @Nullable private Surface videoSurface;
  @Nullable private SurfaceHolder videoSurfaceHolder;
  @Nullable private TextureView videoTextureView;
  private Size surfaceSize;
  @Nullable private IMediaSession iSession;
  private long lastReturnedCurrentPositionMs;
  private long lastSetPlayWhenReadyCalledTimeMs;
  @Nullable private PlayerInfo pendingPlayerInfo;
  @Nullable private BundlingExclusions pendingBundlingExclusions;

  public MediaControllerImplBase(
      Context context,
      @UnderInitialization MediaController instance,
      SessionToken token,
      Bundle connectionHints,
      Looper applicationLooper) {
    // Initialize default values.
    playerInfo = PlayerInfo.DEFAULT;
    surfaceSize = Size.UNKNOWN;
    sessionCommands = SessionCommands.EMPTY;
    playerCommandsFromSession = Commands.EMPTY;
    playerCommandsFromPlayer = Commands.EMPTY;
    intersectedPlayerCommands = Commands.EMPTY;
    listeners =
        new ListenerSet<>(
            applicationLooper,
            Clock.DEFAULT,
            (listener, flags) -> listener.onEvents(getInstance(), new Events(flags)));

    // Initialize members
    this.instance = instance;
    checkNotNull(context, "context must not be null");
    checkNotNull(token, "token must not be null");
    this.context = context;
    sequencedFutureManager = new SequencedFutureManager();
    controllerStub = new MediaControllerStub(this);
    pendingMaskingSequencedFutureNumbers = new ArraySet<>();
    this.token = token;
    this.connectionHints = connectionHints;
    deathRecipient =
        () ->
            MediaControllerImplBase.this
                .getInstance()
                .runOnApplicationLooper(MediaControllerImplBase.this.getInstance()::release);
    surfaceCallback = new SurfaceCallback();

    serviceConnection =
        (this.token.getType() == SessionToken.TYPE_SESSION)
            ? null
            : new SessionServiceConnection(connectionHints);
    flushCommandQueueHandler = new FlushCommandQueueHandler(applicationLooper);
    lastReturnedCurrentPositionMs = C.TIME_UNSET;
    lastSetPlayWhenReadyCalledTimeMs = C.TIME_UNSET;
  }

  /* package*/ MediaController getInstance() {
    return instance;
  }

  @Override
  public void connect(@UnderInitialization MediaControllerImplBase this) {
    boolean connectionRequested;
    if (this.token.getType() == SessionToken.TYPE_SESSION) {
      // Session
      serviceConnection = null;
      connectionRequested = requestConnectToSession(connectionHints);
    } else {
      serviceConnection = new SessionServiceConnection(connectionHints);
      connectionRequested = requestConnectToService();
    }
    if (!connectionRequested) {
      getInstance().runOnApplicationLooper(getInstance()::release);
    }
  }

  @Override
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  @Override
  public void stop() {
    if (!isPlayerCommandAvailable(Player.COMMAND_STOP)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.stop(controllerStub, seq));

    playerInfo =
        playerInfo.copyWithSessionPositionInfo(
            new SessionPositionInfo(
                playerInfo.sessionPositionInfo.positionInfo,
                playerInfo.sessionPositionInfo.isPlayingAd,
                /* eventTimeMs= */ SystemClock.elapsedRealtime(),
                playerInfo.sessionPositionInfo.durationMs,
                /* bufferedPositionMs= */ playerInfo.sessionPositionInfo.positionInfo.positionMs,
                /* bufferedPercentage= */ calculateBufferedPercentage(
                    playerInfo.sessionPositionInfo.positionInfo.positionMs,
                    playerInfo.sessionPositionInfo.durationMs),
                /* totalBufferedDurationMs= */ 0,
                playerInfo.sessionPositionInfo.currentLiveOffsetMs,
                playerInfo.sessionPositionInfo.contentDurationMs,
                /* contentBufferedPositionMs= */ playerInfo
                    .sessionPositionInfo
                    .positionInfo
                    .positionMs));

    if (playerInfo.playbackState != Player.STATE_IDLE) {
      playerInfo =
          playerInfo.copyWithPlaybackState(
              Player.STATE_IDLE, /* playerError= */ playerInfo.playerError);
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_STATE_CHANGED,
          listener -> listener.onPlaybackStateChanged(Player.STATE_IDLE));
      listeners.flushEvents();
    }
  }

  @Override
  public void release() {
    @Nullable IMediaSession iSession = this.iSession;
    if (released) {
      return;
    }
    released = true;
    connectedToken = null;
    flushCommandQueueHandler.release();
    this.iSession = null;
    if (iSession != null) {
      int seq = sequencedFutureManager.obtainNextSequenceNumber();
      try {
        iSession.asBinder().unlinkToDeath(deathRecipient, 0);
        iSession.release(controllerStub, seq);
      } catch (RemoteException e) {
        // No-op.
      }
    }
    listeners.release();
    sequencedFutureManager.lazyRelease(
        RELEASE_TIMEOUT_MS,
        () -> {
          if (serviceConnection != null) {
            context.unbindService(serviceConnection);
            serviceConnection = null;
          }
          controllerStub.destroy();
        });
  }

  @Override
  @Nullable
  public SessionToken getConnectedToken() {
    return connectedToken;
  }

  @Override
  public boolean isConnected() {
    return iSession != null;
  }

  /* package */ boolean isReleased() {
    return released;
  }

  /* @FunctionalInterface */
  private interface RemoteSessionTask {
    void run(IMediaSession iSession, int seq) throws RemoteException;
  }

  private void dispatchRemoteSessionTaskWithPlayerCommand(RemoteSessionTask task) {
    flushCommandQueueHandler.sendFlushCommandQueueMessage();
    dispatchRemoteSessionTask(iSession, task, /* addToPendingMaskingOperations= */ true);
  }

  private void dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(RemoteSessionTask task) {
    // Do not send a flush command queue message as we are actively waiting for task.
    ListenableFuture<SessionResult> future =
        dispatchRemoteSessionTask(iSession, task, /* addToPendingMaskingOperations= */ true);
    try {
      MediaUtils.getFutureResult(future, /* timeoutMs= */ 3_000);
    } catch (ExecutionException e) {
      // Never happens because future.setException will not be called.
      throw new IllegalStateException(e);
    } catch (TimeoutException e) {
      if (future instanceof SequencedFutureManager.SequencedFuture) {
        int sequenceNumber =
            ((SequencedFutureManager.SequencedFuture<SessionResult>) future).getSequenceNumber();
        pendingMaskingSequencedFutureNumbers.remove(sequenceNumber);
        sequencedFutureManager.setFutureResult(
            sequenceNumber, new SessionResult(SessionResult.RESULT_ERROR_UNKNOWN));
      }
      Log.w(TAG, "Synchronous command takes too long on the session side.", e);
      // TODO(b/188888693): Let developers know the failure in their code.
    }
  }

  private ListenableFuture<SessionResult> dispatchRemoteSessionTaskWithSessionCommand(
      @SessionCommand.CommandCode int commandCode, RemoteSessionTask task) {
    return dispatchRemoteSessionTaskWithSessionCommandInternal(
        commandCode, /* sessionCommand= */ null, task);
  }

  private ListenableFuture<SessionResult> dispatchRemoteSessionTaskWithSessionCommand(
      SessionCommand sessionCommand, RemoteSessionTask task) {
    return dispatchRemoteSessionTaskWithSessionCommandInternal(
        SessionCommand.COMMAND_CODE_CUSTOM, sessionCommand, task);
  }

  private ListenableFuture<SessionResult> dispatchRemoteSessionTaskWithSessionCommandInternal(
      @SessionCommand.CommandCode int commandCode,
      @Nullable SessionCommand sessionCommand,
      RemoteSessionTask task) {
    return dispatchRemoteSessionTask(
        sessionCommand != null
            ? getSessionInterfaceWithSessionCommandIfAble(sessionCommand)
            : getSessionInterfaceWithSessionCommandIfAble(commandCode),
        task,
        /* addToPendingMaskingOperations= */ false);
  }

  private ListenableFuture<SessionResult> dispatchRemoteSessionTask(
      @Nullable IMediaSession iSession,
      RemoteSessionTask task,
      boolean addToPendingMaskingOperations) {
    if (iSession != null) {
      SequencedFutureManager.SequencedFuture<SessionResult> result =
          sequencedFutureManager.createSequencedFuture(
              new SessionResult(SessionResult.RESULT_INFO_SKIPPED));
      int sequenceNumber = result.getSequenceNumber();
      if (addToPendingMaskingOperations) {
        pendingMaskingSequencedFutureNumbers.add(sequenceNumber);
      }
      try {
        task.run(iSession, sequenceNumber);
      } catch (RemoteException e) {
        Log.w(TAG, "Cannot connect to the service or the session is gone", e);
        pendingMaskingSequencedFutureNumbers.remove(sequenceNumber);
        sequencedFutureManager.setFutureResult(
            sequenceNumber, new SessionResult(SessionResult.RESULT_ERROR_SESSION_DISCONNECTED));
      }
      return result;
    } else {
      // Don't create Future with SequencedFutureManager.
      // Otherwise session would receive discontinued sequence number, and it would make
      // future work item 'keeping call sequence when session execute commands' impossible.
      return Futures.immediateFuture(
          new SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED));
    }
  }

  @Override
  public void play() {
    if (!isPlayerCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.play(controllerStub, seq));

    setPlayWhenReady(
        /* playWhenReady= */ true,
        Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Override
  public void pause() {
    if (!isPlayerCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.pause(controllerStub, seq));

    setPlayWhenReady(
        /* playWhenReady= */ false,
        Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Override
  public void prepare() {
    if (!isPlayerCommandAvailable(Player.COMMAND_PREPARE)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.prepare(controllerStub, seq));

    if (playerInfo.playbackState == Player.STATE_IDLE) {
      PlayerInfo playerInfo =
          this.playerInfo.copyWithPlaybackState(
              this.playerInfo.timeline.isEmpty() ? Player.STATE_ENDED : Player.STATE_BUFFERING,
              /* playerError= */ null);

      updatePlayerInfo(
          playerInfo,
          /* ignored */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
          /* ignored */ Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
          /* positionDiscontinuity= */ false,
          /* ignored */ Player.DISCONTINUITY_REASON_INTERNAL,
          /* mediaItemTransition= */ false,
          /* ignored */ Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT);
    }
  }

  @Override
  public void seekToDefaultPosition() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekToDefaultPosition(controllerStub, seq));

    seekToInternal(getCurrentMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
  }

  @Override
  public void seekToDefaultPosition(int mediaItemIndex) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) {
      return;
    }
    checkArgument(mediaItemIndex >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.seekToDefaultPositionWithMediaItemIndex(controllerStub, seq, mediaItemIndex));

    seekToInternal(mediaItemIndex, /* positionMs= */ C.TIME_UNSET);
  }

  @Override
  public void seekTo(long positionMs) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekTo(controllerStub, seq, positionMs));

    seekToInternal(getCurrentMediaItemIndex(), positionMs);
  }

  @Override
  public void seekTo(int mediaItemIndex, long positionMs) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) {
      return;
    }
    checkArgument(mediaItemIndex >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.seekToWithMediaItemIndex(controllerStub, seq, mediaItemIndex, positionMs));

    seekToInternal(mediaItemIndex, positionMs);
  }

  @Override
  public long getSeekBackIncrement() {
    return playerInfo.seekBackIncrementMs;
  }

  @Override
  public void seekBack() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_BACK)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekBack(controllerStub, seq));

    seekToInternalByOffset(-getSeekBackIncrement());
  }

  @Override
  public long getSeekForwardIncrement() {
    return playerInfo.seekForwardIncrementMs;
  }

  @Override
  public void seekForward() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekForward(controllerStub, seq));

    seekToInternalByOffset(getSeekForwardIncrement());
  }

  @Override
  public PendingIntent getSessionActivity() {
    return sessionActivity;
  }

  @Override
  public void setPlayWhenReady(boolean playWhenReady) {
    if (!isPlayerCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setPlayWhenReady(controllerStub, seq, playWhenReady));

    setPlayWhenReady(
        playWhenReady,
        Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Override
  public boolean getPlayWhenReady() {
    return playerInfo.playWhenReady;
  }

  @Override
  @Player.PlaybackSuppressionReason
  public int getPlaybackSuppressionReason() {
    return playerInfo.playbackSuppressionReason;
  }

  @Override
  @Nullable
  public PlaybackException getPlayerError() {
    return playerInfo.playerError;
  }

  @Override
  @Player.State
  public int getPlaybackState() {
    return playerInfo.playbackState;
  }

  @Override
  public boolean isPlaying() {
    return playerInfo.isPlaying;
  }

  @Override
  public boolean isLoading() {
    return playerInfo.isLoading;
  }

  @Override
  public long getDuration() {
    return playerInfo.sessionPositionInfo.durationMs;
  }

  @Override
  public long getCurrentPosition() {
    boolean receivedUpdatedPositionInfo =
        lastSetPlayWhenReadyCalledTimeMs < playerInfo.sessionPositionInfo.eventTimeMs;
    if (!playerInfo.isPlaying) {
      if (receivedUpdatedPositionInfo || lastReturnedCurrentPositionMs == C.TIME_UNSET) {
        lastReturnedCurrentPositionMs = playerInfo.sessionPositionInfo.positionInfo.positionMs;
      }
      return lastReturnedCurrentPositionMs;
    }

    if (!receivedUpdatedPositionInfo && lastReturnedCurrentPositionMs != C.TIME_UNSET) {
      // Need an updated current position in order to make a new position estimation
      return lastReturnedCurrentPositionMs;
    }

    long elapsedTimeMs =
        (getInstance().getTimeDiffMs() != C.TIME_UNSET)
            ? getInstance().getTimeDiffMs()
            : SystemClock.elapsedRealtime() - playerInfo.sessionPositionInfo.eventTimeMs;
    long estimatedPositionMs =
        playerInfo.sessionPositionInfo.positionInfo.positionMs
            + (long) (elapsedTimeMs * playerInfo.playbackParameters.speed);
    if (playerInfo.sessionPositionInfo.durationMs != C.TIME_UNSET) {
      estimatedPositionMs = min(estimatedPositionMs, playerInfo.sessionPositionInfo.durationMs);
    }
    lastReturnedCurrentPositionMs = estimatedPositionMs;
    return lastReturnedCurrentPositionMs;
  }

  @Override
  public long getBufferedPosition() {
    return playerInfo.sessionPositionInfo.bufferedPositionMs;
  }

  @Override
  public int getBufferedPercentage() {
    return playerInfo.sessionPositionInfo.bufferedPercentage;
  }

  @Override
  public long getTotalBufferedDuration() {
    return playerInfo.sessionPositionInfo.totalBufferedDurationMs;
  }

  @Override
  public long getCurrentLiveOffset() {
    return playerInfo.sessionPositionInfo.currentLiveOffsetMs;
  }

  @Override
  public long getContentDuration() {
    return playerInfo.sessionPositionInfo.contentDurationMs;
  }

  @Override
  public long getContentPosition() {
    if (!playerInfo.sessionPositionInfo.isPlayingAd) {
      return getCurrentPosition();
    }
    return playerInfo.sessionPositionInfo.positionInfo.contentPositionMs;
  }

  @Override
  public long getContentBufferedPosition() {
    return playerInfo.sessionPositionInfo.contentBufferedPositionMs;
  }

  @Override
  public boolean isPlayingAd() {
    return playerInfo.sessionPositionInfo.isPlayingAd;
  }

  @Override
  public int getCurrentAdGroupIndex() {
    return playerInfo.sessionPositionInfo.positionInfo.adGroupIndex;
  }

  @Override
  public int getCurrentAdIndexInAdGroup() {
    return playerInfo.sessionPositionInfo.positionInfo.adIndexInAdGroup;
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setPlaybackParameters(controllerStub, seq, playbackParameters.toBundle()));

    if (!playerInfo.playbackParameters.equals(playbackParameters)) {
      playerInfo = playerInfo.copyWithPlaybackParameters(playbackParameters);

      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          listener -> listener.onPlaybackParametersChanged(playbackParameters));
      listeners.flushEvents();
    }
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return playerInfo.playbackParameters;
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setPlaybackSpeed(controllerStub, seq, speed));

    if (playerInfo.playbackParameters.speed != speed) {
      PlaybackParameters newPlaybackParameters = playerInfo.playbackParameters.withSpeed(speed);
      playerInfo = playerInfo.copyWithPlaybackParameters(newPlaybackParameters);

      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          listener -> listener.onPlaybackParametersChanged(newPlaybackParameters));
      listeners.flushEvents();
    }
  }

  @Override
  public AudioAttributes getAudioAttributes() {
    return playerInfo.audioAttributes;
  }

  @Override
  public ListenableFuture<SessionResult> setRating(String mediaId, Rating rating) {
    return dispatchRemoteSessionTaskWithSessionCommand(
        SessionCommand.COMMAND_CODE_SESSION_SET_RATING,
        (iSession, seq) ->
            iSession.setRatingWithMediaId(controllerStub, seq, mediaId, rating.toBundle()));
  }

  @Override
  public ListenableFuture<SessionResult> setRating(Rating rating) {
    return dispatchRemoteSessionTaskWithSessionCommand(
        SessionCommand.COMMAND_CODE_SESSION_SET_RATING,
        (iSession, seq) -> iSession.setRating(controllerStub, seq, rating.toBundle()));
  }

  @Override
  public ListenableFuture<SessionResult> sendCustomCommand(SessionCommand command, Bundle args) {
    return dispatchRemoteSessionTaskWithSessionCommand(
        command,
        (iSession, seq) -> iSession.onCustomCommand(controllerStub, seq, command.toBundle(), args));
  }

  @Override
  public Timeline getCurrentTimeline() {
    return playerInfo.timeline;
  }

  @Override
  public void setMediaItem(MediaItem mediaItem) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setMediaItem(controllerStub, seq, mediaItem.toBundle()));

    setMediaItemsInternal(
        Collections.singletonList(mediaItem),
        /* startIndex= */ C.INDEX_UNSET,
        /* startPositionMs= */ C.TIME_UNSET,
        /* resetToDefaultPosition= */ true);
  }

  @Override
  public void setMediaItem(MediaItem mediaItem, long startPositionMs) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setMediaItemWithStartPosition(
                controllerStub, seq, mediaItem.toBundle(), startPositionMs));

    setMediaItemsInternal(
        Collections.singletonList(mediaItem),
        /* startIndex= */ C.INDEX_UNSET,
        /* startPositionMs= */ startPositionMs,
        /* resetToDefaultPosition= */ false);
  }

  @Override
  public void setMediaItem(MediaItem mediaItem, boolean resetPosition) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setMediaItemWithResetPosition(
                controllerStub, seq, mediaItem.toBundle(), resetPosition));

    setMediaItemsInternal(
        Collections.singletonList(mediaItem),
        /* startIndex= */ C.INDEX_UNSET,
        /* startPositionMs= */ C.TIME_UNSET,
        /* resetToDefaultPosition= */ resetPosition);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setMediaItems(
                controllerStub,
                seq,
                new BundleListRetriever(BundleableUtil.toBundleList(mediaItems))));

    setMediaItemsInternal(
        mediaItems,
        /* startIndex= */ C.INDEX_UNSET,
        /* startPositionMs= */ C.TIME_UNSET,
        /* resetToDefaultPosition= */ true);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setMediaItemsWithResetPosition(
                controllerStub,
                seq,
                new BundleListRetriever(BundleableUtil.toBundleList(mediaItems)),
                resetPosition));

    setMediaItemsInternal(
        mediaItems,
        /* startIndex= */ C.INDEX_UNSET,
        /* startPositionMs= */ C.TIME_UNSET,
        /* resetToDefaultPosition= */ resetPosition);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setMediaItemsWithStartIndex(
                controllerStub,
                seq,
                new BundleListRetriever(BundleableUtil.toBundleList(mediaItems)),
                startIndex,
                startPositionMs));

    setMediaItemsInternal(
        mediaItems, startIndex, startPositionMs, /* resetToDefaultPosition= */ false);
  }

  @Override
  public void setPlaylistMetadata(MediaMetadata playlistMetadata) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_MEDIA_ITEMS_METADATA)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setPlaylistMetadata(controllerStub, seq, playlistMetadata.toBundle()));

    if (!playerInfo.playlistMetadata.equals(playlistMetadata)) {
      playerInfo = playerInfo.copyWithPlaylistMetadata(playlistMetadata);
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYLIST_METADATA_CHANGED,
          listener -> listener.onPlaylistMetadataChanged(playlistMetadata));
      listeners.flushEvents();
    }
  }

  @Override
  public MediaMetadata getPlaylistMetadata() {
    return playerInfo.playlistMetadata;
  }

  @Override
  public void addMediaItem(MediaItem mediaItem) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.addMediaItem(controllerStub, seq, mediaItem.toBundle()));

    addMediaItemsInternal(
        getCurrentTimeline().getWindowCount(), Collections.singletonList(mediaItem));
  }

  @Override
  public void addMediaItem(int index, MediaItem mediaItem) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }
    checkArgument(index >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.addMediaItemWithIndex(controllerStub, seq, index, mediaItem.toBundle()));

    addMediaItemsInternal(index, Collections.singletonList(mediaItem));
  }

  @Override
  public void addMediaItems(List<MediaItem> mediaItems) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.addMediaItems(
                controllerStub,
                seq,
                new BundleListRetriever(BundleableUtil.toBundleList(mediaItems))));

    addMediaItemsInternal(getCurrentTimeline().getWindowCount(), mediaItems);
  }

  @Override
  public void addMediaItems(int index, List<MediaItem> mediaItems) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }
    checkArgument(index >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.addMediaItemsWithIndex(
                controllerStub,
                seq,
                index,
                new BundleListRetriever(BundleableUtil.toBundleList(mediaItems))));

    addMediaItemsInternal(index, mediaItems);
  }

  private void addMediaItemsInternal(int index, List<MediaItem> mediaItems) {
    if (mediaItems.isEmpty()) {
      return;
    }
    // Add media items to the end of the timeline if the index exceeds the window count.
    index = min(index, playerInfo.timeline.getWindowCount());

    Timeline oldTimeline = playerInfo.timeline;
    List<Window> newWindows = new ArrayList<>();
    List<Period> newPeriods = new ArrayList<>();
    for (int i = 0; i < oldTimeline.getWindowCount(); i++) {
      newWindows.add(oldTimeline.getWindow(i, new Window()));
    }
    for (int i = 0; i < mediaItems.size(); i++) {
      newWindows.add(i + index, createNewWindow(mediaItems.get(i)));
    }
    rebuildPeriods(oldTimeline, newWindows, newPeriods);
    Timeline newTimeline = createMaskingTimeline(newWindows, newPeriods);

    int newMediaItemIndex;
    int newPeriodIndex;
    if (playerInfo.timeline.isEmpty()) {
      newMediaItemIndex = 0;
      newPeriodIndex = 0;
    } else {
      newMediaItemIndex =
          (playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex >= index)
              ? playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex + mediaItems.size()
              : playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex;
      newPeriodIndex =
          (playerInfo.sessionPositionInfo.positionInfo.periodIndex >= index)
              ? playerInfo.sessionPositionInfo.positionInfo.periodIndex + mediaItems.size()
              : playerInfo.sessionPositionInfo.positionInfo.periodIndex;
    }
    PlayerInfo newPlayerInfo =
        maskTimelineAndPositionInfo(
            playerInfo,
            newTimeline,
            newMediaItemIndex,
            newPeriodIndex,
            Player.DISCONTINUITY_REASON_INTERNAL);
    updatePlayerInfo(
        newPlayerInfo,
        /* timelineChangeReason= */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* positionDiscontinuity= */ false,
        /* ignored */ Player.DISCONTINUITY_REASON_INTERNAL,
        /* mediaItemTransition= */ oldTimeline.isEmpty(),
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
  }

  @Override
  public void removeMediaItem(int index) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }
    checkArgument(index >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.removeMediaItem(controllerStub, seq, index));

    removeMediaItemsInternal(/* fromIndex= */ index, /* toIndex= */ index + 1);
  }

  @Override
  public void removeMediaItems(int fromIndex, int toIndex) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }
    checkArgument(fromIndex >= 0 && toIndex >= fromIndex);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.removeMediaItems(controllerStub, seq, fromIndex, toIndex));

    removeMediaItemsInternal(fromIndex, toIndex);
  }

  @Override
  public void clearMediaItems() {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.clearMediaItems(controllerStub, seq));

    removeMediaItemsInternal(/* fromIndex= */ 0, /* toIndex= */ Integer.MAX_VALUE);
  }

  private void removeMediaItemsInternal(int fromIndex, int toIndex) {
    Timeline oldTimeline = playerInfo.timeline;
    int playlistSize = playerInfo.timeline.getWindowCount();
    toIndex = min(toIndex, playlistSize);
    if (fromIndex >= playlistSize || fromIndex == toIndex) {
      return;
    }

    List<Window> newWindows = new ArrayList<>();
    List<Period> newPeriods = new ArrayList<>();
    for (int i = 0; i < oldTimeline.getWindowCount(); i++) {
      if (i < fromIndex || i >= toIndex) {
        newWindows.add(oldTimeline.getWindow(i, new Window()));
      }
    }
    rebuildPeriods(oldTimeline, newWindows, newPeriods);
    Timeline newTimeline = createMaskingTimeline(newWindows, newPeriods);

    int oldMediaItemIndex = getCurrentMediaItemIndex();
    int newMediaItemIndex = oldMediaItemIndex;
    int oldPeriodIndex = playerInfo.sessionPositionInfo.positionInfo.periodIndex;
    int newPeriodIndex = oldPeriodIndex;
    boolean currentItemRemoved =
        getCurrentMediaItemIndex() >= fromIndex && getCurrentMediaItemIndex() < toIndex;
    Window window = new Window();
    if (oldTimeline.isEmpty()) {
      // No masking required. Just forwarding command to session.
    } else {
      if (newTimeline.isEmpty()) {
        newMediaItemIndex = C.INDEX_UNSET;
        newPeriodIndex = 0;
      } else {
        if (currentItemRemoved) {
          int oldNextMediaItemIndex =
              resolveSubsequentMediaItemIndex(
                  getRepeatMode(),
                  getShuffleModeEnabled(),
                  oldMediaItemIndex,
                  oldTimeline,
                  fromIndex,
                  toIndex);
          if (oldNextMediaItemIndex == C.INDEX_UNSET) {
            newMediaItemIndex = newTimeline.getFirstWindowIndex(getShuffleModeEnabled());
          } else if (oldNextMediaItemIndex >= toIndex) {
            newMediaItemIndex = oldNextMediaItemIndex - (toIndex - fromIndex);
          } else {
            newMediaItemIndex = oldNextMediaItemIndex;
          }
          newPeriodIndex = newTimeline.getWindow(newMediaItemIndex, window).firstPeriodIndex;
        } else if (oldMediaItemIndex >= toIndex) {
          newMediaItemIndex -= (toIndex - fromIndex);
          newPeriodIndex =
              getNewPeriodIndexWithoutRemovedPeriods(
                  oldTimeline, oldPeriodIndex, fromIndex, toIndex);
        }
      }

      PlayerInfo newPlayerInfo;
      if (currentItemRemoved) {
        PositionInfo newPositionInfo;
        if (newMediaItemIndex == C.INDEX_UNSET) {
          newPositionInfo = SessionPositionInfo.DEFAULT_POSITION_INFO;
          newPlayerInfo =
              maskTimelineAndPositionInfo(
                  playerInfo,
                  newTimeline,
                  newPositionInfo,
                  SessionPositionInfo.DEFAULT,
                  Player.DISCONTINUITY_REASON_REMOVE);
        } else {
          Window newWindow = newTimeline.getWindow(newMediaItemIndex, new Window());
          long defaultPositionMs = newWindow.getDefaultPositionMs();
          long durationMs = newWindow.getDurationMs();
          newPositionInfo =
              new PositionInfo(
                  /* windowUid= */ null,
                  newMediaItemIndex,
                  newWindow.mediaItem,
                  /* periodUid= */ null,
                  newPeriodIndex,
                  /* positionMs= */ defaultPositionMs,
                  /* contentPositionMs= */ defaultPositionMs,
                  /* adGroupIndex= */ C.INDEX_UNSET,
                  /* adIndexInAdGroup= */ C.INDEX_UNSET);
          newPlayerInfo =
              maskTimelineAndPositionInfo(
                  playerInfo,
                  newTimeline,
                  newPositionInfo,
                  new SessionPositionInfo(
                      newPositionInfo,
                      /* isPlayingAd= */ false,
                      /* eventTimeMs= */ SystemClock.elapsedRealtime(),
                      /* durationMs= */ durationMs,
                      /* bufferedPositionMs= */ defaultPositionMs,
                      /* bufferedPercentage= */ calculateBufferedPercentage(
                          defaultPositionMs, durationMs),
                      /* totalBufferedDurationMs= */ 0,
                      /* currentLiveOffsetMs= */ C.TIME_UNSET,
                      /* contentDurationMs= */ durationMs,
                      /* contentBufferedPositionMs= */ defaultPositionMs),
                  Player.DISCONTINUITY_REASON_REMOVE);
        }
      } else {
        newPlayerInfo =
            maskTimelineAndPositionInfo(
                playerInfo,
                newTimeline,
                newMediaItemIndex,
                newPeriodIndex,
                Player.DISCONTINUITY_REASON_REMOVE);
      }

      // Player transitions to Player.STATE_ENDED if the current index is part of the removed tail.
      final boolean transitionsToEnded =
          newPlayerInfo.playbackState != Player.STATE_IDLE
              && newPlayerInfo.playbackState != Player.STATE_ENDED
              && fromIndex < toIndex
              && toIndex == oldTimeline.getWindowCount()
              && getCurrentMediaItemIndex() >= fromIndex;
      if (transitionsToEnded) {
        newPlayerInfo =
            newPlayerInfo.copyWithPlaybackState(Player.STATE_ENDED, /* playerError= */ null);
      }

      updatePlayerInfo(
          newPlayerInfo,
          /* timelineChangeReason= */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
          /* ignored */ Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
          /* positionDiscontinuity= */ currentItemRemoved,
          Player.DISCONTINUITY_REASON_REMOVE,
          /* mediaItemTransition= */ playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex
                  >= fromIndex
              && playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex < toIndex,
          Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
    }
  }

  @Override
  public void moveMediaItem(int currentIndex, int newIndex) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }
    checkArgument(currentIndex >= 0 && newIndex >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.moveMediaItem(controllerStub, seq, currentIndex, newIndex));

    moveMediaItemsInternal(
        /* fromIndex= */ currentIndex, /* toIndex= */ currentIndex + 1, newIndex);
  }

  @Override
  public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
    if (!isPlayerCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return;
    }
    checkArgument(fromIndex >= 0 && fromIndex <= toIndex && newIndex >= 0);

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.moveMediaItems(controllerStub, seq, fromIndex, toIndex, newIndex));

    moveMediaItemsInternal(fromIndex, toIndex, newIndex);
  }

  @Override
  public int getCurrentPeriodIndex() {
    return playerInfo.sessionPositionInfo.positionInfo.periodIndex;
  }

  @Override
  public int getCurrentMediaItemIndex() {
    return playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex == C.INDEX_UNSET
        ? 0
        : playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex;
  }

  // TODO(b/184479406): Get the index directly from Player rather than Timeline.
  @Override
  public int getPreviousMediaItemIndex() {
    return playerInfo.timeline.isEmpty()
        ? C.INDEX_UNSET
        : playerInfo.timeline.getPreviousWindowIndex(
            getCurrentMediaItemIndex(),
            convertRepeatModeForNavigation(playerInfo.repeatMode),
            playerInfo.shuffleModeEnabled);
  }

  // TODO(b/184479406): Get the index directly from Player rather than Timeline.
  @Override
  public int getNextMediaItemIndex() {
    return playerInfo.timeline.isEmpty()
        ? C.INDEX_UNSET
        : playerInfo.timeline.getNextWindowIndex(
            getCurrentMediaItemIndex(),
            convertRepeatModeForNavigation(playerInfo.repeatMode),
            playerInfo.shuffleModeEnabled);
  }

  @Override
  public boolean hasPreviousMediaItem() {
    return getPreviousMediaItemIndex() != C.INDEX_UNSET;
  }

  @Override
  public boolean hasNextMediaItem() {
    return getNextMediaItemIndex() != C.INDEX_UNSET;
  }

  @Override
  public void seekToPreviousMediaItem() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekToPreviousMediaItem(controllerStub, seq));

    if (getPreviousMediaItemIndex() != C.INDEX_UNSET) {
      seekToInternal(getPreviousMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
    }
  }

  @Override
  public void seekToNextMediaItem() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekToNextMediaItem(controllerStub, seq));

    if (getNextMediaItemIndex() != C.INDEX_UNSET) {
      seekToInternal(getNextMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
    }
  }

  @Override
  public void seekToPrevious() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekToPrevious(controllerStub, seq));

    Timeline timeline = getCurrentTimeline();
    if (timeline.isEmpty() || isPlayingAd()) {
      return;
    }
    boolean hasPreviousMediaItem = hasPreviousMediaItem();
    Window window = timeline.getWindow(getCurrentMediaItemIndex(), new Window());
    if (window.isDynamic && window.isLive()) {
      if (hasPreviousMediaItem) {
        seekToInternal(getPreviousMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
      }
    } else if (hasPreviousMediaItem && getCurrentPosition() <= getMaxSeekToPreviousPosition()) {
      seekToInternal(getPreviousMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
    } else {
      seekToInternal(getCurrentMediaItemIndex(), /* positionMs= */ 0);
    }
  }

  @Override
  public long getMaxSeekToPreviousPosition() {
    return playerInfo.maxSeekToPreviousPositionMs;
  }

  @Override
  public void seekToNext() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.seekToNext(controllerStub, seq));

    Timeline timeline = getCurrentTimeline();
    if (timeline.isEmpty() || isPlayingAd()) {
      return;
    }
    if (hasNextMediaItem()) {
      seekToInternal(getNextMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
    } else {
      Window window = timeline.getWindow(getCurrentMediaItemIndex(), new Window());
      if (window.isDynamic && window.isLive()) {
        seekToInternal(getCurrentMediaItemIndex(), /* positionMs= */ C.TIME_UNSET);
      }
    }
  }

  @Override
  public int getRepeatMode() {
    return playerInfo.repeatMode;
  }

  @Override
  public void setRepeatMode(@Player.RepeatMode int repeatMode) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setRepeatMode(controllerStub, seq, repeatMode));

    if (playerInfo.repeatMode != repeatMode) {
      playerInfo = playerInfo.copyWithRepeatMode(repeatMode);

      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_REPEAT_MODE_CHANGED,
          listener -> listener.onRepeatModeChanged(repeatMode));
      listeners.flushEvents();
    }
  }

  @Override
  public boolean getShuffleModeEnabled() {
    return playerInfo.shuffleModeEnabled;
  }

  @Override
  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setShuffleModeEnabled(controllerStub, seq, shuffleModeEnabled));

    if (playerInfo.shuffleModeEnabled != shuffleModeEnabled) {
      playerInfo = playerInfo.copyWithShuffleModeEnabled(shuffleModeEnabled);

      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
          listener -> listener.onShuffleModeEnabledChanged(shuffleModeEnabled));
      listeners.flushEvents();
    }
  }

  @Override
  public CueGroup getCurrentCues() {
    return playerInfo.cueGroup;
  }

  @Override
  public float getVolume() {
    return playerInfo.volume;
  }

  @Override
  public void setVolume(float volume) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VOLUME)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setVolume(controllerStub, seq, volume));

    if (playerInfo.volume != volume) {
      playerInfo = playerInfo.copyWithVolume(volume);
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_VOLUME_CHANGED,
          listener -> listener.onVolumeChanged(volume));
      listeners.flushEvents();
    }
  }

  @Override
  public DeviceInfo getDeviceInfo() {
    return playerInfo.deviceInfo;
  }

  @Override
  public int getDeviceVolume() {
    return playerInfo.deviceVolume;
  }

  @Override
  public boolean isDeviceMuted() {
    return playerInfo.deviceMuted;
  }

  @Override
  public void setDeviceVolume(int volume) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setDeviceVolume(controllerStub, seq, volume));

    if (playerInfo.deviceVolume != volume) {
      playerInfo = playerInfo.copyWithDeviceVolume(volume, playerInfo.deviceMuted);

      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_DEVICE_VOLUME_CHANGED,
          listener -> listener.onDeviceVolumeChanged(volume, playerInfo.deviceMuted));
      listeners.flushEvents();
    }
  }

  @Override
  public void increaseDeviceVolume() {
    if (!isPlayerCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.increaseDeviceVolume(controllerStub, seq));

    int newDeviceVolume = playerInfo.deviceVolume + 1;
    if (newDeviceVolume <= getDeviceInfo().maxVolume) {
      playerInfo = playerInfo.copyWithDeviceVolume(newDeviceVolume, playerInfo.deviceMuted);
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_DEVICE_VOLUME_CHANGED,
          listener -> listener.onDeviceVolumeChanged(newDeviceVolume, playerInfo.deviceMuted));
      listeners.flushEvents();
    }
  }

  @Override
  public void decreaseDeviceVolume() {
    if (!isPlayerCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.decreaseDeviceVolume(controllerStub, seq));

    int newDeviceVolume = playerInfo.deviceVolume - 1;
    if (newDeviceVolume >= getDeviceInfo().minVolume) {
      playerInfo = playerInfo.copyWithDeviceVolume(newDeviceVolume, playerInfo.deviceMuted);
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_DEVICE_VOLUME_CHANGED,
          listener -> listener.onDeviceVolumeChanged(newDeviceVolume, playerInfo.deviceMuted));
      listeners.flushEvents();
    }
  }

  @Override
  public void setDeviceMuted(boolean muted) {
    if (!isPlayerCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) -> iSession.setDeviceMuted(controllerStub, seq, muted));

    if (playerInfo.deviceMuted != muted) {
      playerInfo = playerInfo.copyWithDeviceVolume(playerInfo.deviceVolume, muted);
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_DEVICE_VOLUME_CHANGED,
          listener -> listener.onDeviceVolumeChanged(playerInfo.deviceVolume, muted));
      listeners.flushEvents();
    }
  }

  @Override
  public VideoSize getVideoSize() {
    return playerInfo.videoSize;
  }

  @Override
  public Size getSurfaceSize() {
    return surfaceSize;
  }

  @Override
  public void clearVideoSurface() {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    clearSurfacesAndCallbacks();
    /* surface= */ dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
        (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, null));
    maybeNotifySurfaceSizeChanged(/* width= */ 0, /* height= */ 0);
  }

  @Override
  public void clearVideoSurface(@Nullable Surface surface) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    if (surface == null || videoSurface != surface) {
      return;
    }
    clearVideoSurface();
  }

  @Override
  public void setVideoSurface(@Nullable Surface surface) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    clearSurfacesAndCallbacks();
    videoSurface = surface;
    dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
        (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, surface));
    int newSurfaceSize = surface == null ? 0 : C.LENGTH_UNSET;
    maybeNotifySurfaceSizeChanged(/* width= */ newSurfaceSize, /* height= */ newSurfaceSize);
  }

  @Override
  public void setVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    if (surfaceHolder == null) {
      clearVideoSurface();
      return;
    }

    if (videoSurfaceHolder == surfaceHolder) {
      return;
    }
    clearSurfacesAndCallbacks();
    videoSurfaceHolder = surfaceHolder;
    videoSurfaceHolder.addCallback(surfaceCallback);

    @Nullable Surface surface = surfaceHolder.getSurface();
    if (surface != null && surface.isValid()) {
      videoSurface = surface;
      dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, surface));
      Rect surfaceSize = surfaceHolder.getSurfaceFrame();
      maybeNotifySurfaceSizeChanged(surfaceSize.width(), surfaceSize.height());
    } else {
      videoSurface = null;
      /* surface= */ dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, null));
      maybeNotifySurfaceSizeChanged(/* width= */ 0, /* height= */ 0);
    }
  }

  @Override
  public void clearVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    if (surfaceHolder == null || videoSurfaceHolder != surfaceHolder) {
      return;
    }
    clearVideoSurface();
  }

  @Override
  public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    @Nullable SurfaceHolder surfaceHolder = surfaceView == null ? null : surfaceView.getHolder();
    setVideoSurfaceHolder(surfaceHolder);
  }

  @Override
  public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    @Nullable SurfaceHolder surfaceHolder = surfaceView == null ? null : surfaceView.getHolder();
    clearVideoSurfaceHolder(surfaceHolder);
  }

  @Override
  public void setVideoTextureView(@Nullable TextureView textureView) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    if (textureView == null) {
      clearVideoSurface();
      return;
    }

    if (videoTextureView == textureView) {
      return;
    }

    clearSurfacesAndCallbacks();
    videoTextureView = textureView;
    videoTextureView.setSurfaceTextureListener(surfaceCallback);

    @Nullable SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
    if (surfaceTexture == null) {
      /* surface= */ dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, null));
      maybeNotifySurfaceSizeChanged(/* width= */ 0, /* height= */ 0);
    } else {
      videoSurface = new Surface(surfaceTexture);
      dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, videoSurface));
      maybeNotifySurfaceSizeChanged(textureView.getWidth(), textureView.getHeight());
    }
  }

  @Override
  public void clearVideoTextureView(@Nullable TextureView textureView) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
      return;
    }

    if (textureView == null || videoTextureView != textureView) {
      return;
    }
    clearVideoSurface();
  }

  @Override
  public MediaMetadata getMediaMetadata() {
    return playerInfo.mediaMetadata;
  }

  @Override
  public Commands getAvailableCommands() {
    return intersectedPlayerCommands;
  }

  @Override
  public Tracks getCurrentTracks() {
    return playerInfo.currentTracks;
  }

  @Override
  public TrackSelectionParameters getTrackSelectionParameters() {
    return playerInfo.trackSelectionParameters;
  }

  @Override
  public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
    if (!isPlayerCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
      return;
    }

    dispatchRemoteSessionTaskWithPlayerCommand(
        (iSession, seq) ->
            iSession.setTrackSelectionParameters(controllerStub, seq, parameters.toBundle()));

    if (parameters != playerInfo.trackSelectionParameters) {
      playerInfo = playerInfo.copyWithTrackSelectionParameters(parameters);

      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
          listener -> listener.onTrackSelectionParametersChanged(parameters));
      listeners.flushEvents();
    }
  }

  @Override
  public SessionCommands getAvailableSessionCommands() {
    return sessionCommands;
  }

  @Override
  public Context getContext() {
    return context;
  }

  @Override
  @Nullable
  public MediaBrowserCompat getBrowserCompat() {
    return null;
  }

  private Timeline createMaskingTimeline(List<Window> windows, List<Period> periods) {
    return new RemotableTimeline(
        new ImmutableList.Builder<Window>().addAll(windows).build(),
        new ImmutableList.Builder<Period>().addAll(periods).build(),
        MediaUtils.generateUnshuffledIndices(windows.size()));
  }

  private void setMediaItemsInternal(
      List<MediaItem> mediaItems,
      int startIndex,
      long startPositionMs,
      boolean resetToDefaultPosition) {
    List<Window> windows = new ArrayList<>();
    List<Period> periods = new ArrayList<>();
    for (int i = 0; i < mediaItems.size(); i++) {
      windows.add(MediaUtils.convertToWindow(mediaItems.get(i), i));
      periods.add(MediaUtils.convertToPeriod(i));
    }

    Timeline newTimeline = createMaskingTimeline(windows, periods);
    if (!newTimeline.isEmpty() && startIndex >= newTimeline.getWindowCount()) {
      throw new IllegalSeekPositionException(newTimeline, startIndex, startPositionMs);
    }

    boolean correctedStartIndex = false;
    if (resetToDefaultPosition) {
      startIndex = newTimeline.getFirstWindowIndex(playerInfo.shuffleModeEnabled);
      startPositionMs = C.TIME_UNSET;
    } else if (startIndex == C.INDEX_UNSET) {
      startIndex = playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex;
      startPositionMs = playerInfo.sessionPositionInfo.positionInfo.positionMs;
      if (startIndex >= newTimeline.getWindowCount()) {
        correctedStartIndex = true;
        startIndex = newTimeline.getFirstWindowIndex(playerInfo.shuffleModeEnabled);
        startPositionMs = C.TIME_UNSET;
      }
    }
    PositionInfo newPositionInfo;
    SessionPositionInfo newSessionPositionInfo;
    @Nullable PeriodInfo periodInfo = getPeriodInfo(newTimeline, startIndex, startPositionMs);
    if (periodInfo == null) {
      // Timeline is empty.
      newPositionInfo =
          new PositionInfo(
              /* windowUid= */ null,
              startIndex,
              /* mediaItem= */ null,
              /* periodUid= */ null,
              /* periodIndex= */ 0,
              /* positionMs= */ startPositionMs == C.TIME_UNSET ? 0 : startPositionMs,
              /* contentPositionMs= */ startPositionMs == C.TIME_UNSET ? 0 : startPositionMs,
              /* adGroupIndex= */ C.INDEX_UNSET,
              /* adIndexInAdGroup= */ C.INDEX_UNSET);
      newSessionPositionInfo =
          new SessionPositionInfo(
              newPositionInfo,
              /* isPlayingAd= */ false,
              /* eventTimeMs= */ SystemClock.elapsedRealtime(),
              /* durationMs= */ C.TIME_UNSET,
              /* bufferedPositionMs= */ startPositionMs == C.TIME_UNSET ? 0 : startPositionMs,
              /* bufferedPercentage= */ 0,
              /* totalBufferedDurationMs= */ 0,
              /* currentLiveOffsetMs= */ C.TIME_UNSET,
              /* contentDurationMs= */ C.TIME_UNSET,
              /* contentBufferedPositionMs= */ startPositionMs == C.TIME_UNSET
                  ? 0
                  : startPositionMs);
    } else {
      newPositionInfo =
          new PositionInfo(
              /* windowUid= */ null,
              startIndex,
              mediaItems.get(startIndex),
              /* periodUid= */ null,
              periodInfo.index,
              /* positionMs= */ usToMs(periodInfo.periodPositionUs),
              /* contentPositionMs= */ usToMs(periodInfo.periodPositionUs),
              /* adGroupIndex= */ C.INDEX_UNSET,
              /* adIndexInAdGroup= */ C.INDEX_UNSET);
      newSessionPositionInfo =
          new SessionPositionInfo(
              newPositionInfo,
              /* isPlayingAd= */ false,
              /* eventTimeMs= */ SystemClock.elapsedRealtime(),
              /* durationMs= */ C.TIME_UNSET,
              /* bufferedPositionMs= */ usToMs(periodInfo.periodPositionUs),
              /* bufferedPercentage= */ 0,
              /* totalBufferedDurationMs= */ 0,
              /* currentLiveOffsetMs= */ C.TIME_UNSET,
              /* contentDurationMs= */ C.TIME_UNSET,
              /* contentBufferedPositionMs= */ usToMs(periodInfo.periodPositionUs));
    }
    PlayerInfo newPlayerInfo =
        maskTimelineAndPositionInfo(
            playerInfo,
            newTimeline,
            newPositionInfo,
            newSessionPositionInfo,
            Player.DISCONTINUITY_REASON_REMOVE);

    // Mask the playback state.
    int maskingPlaybackState = newPlayerInfo.playbackState;
    if (startIndex != C.INDEX_UNSET && newPlayerInfo.playbackState != Player.STATE_IDLE) {
      if (newTimeline.isEmpty() || correctedStartIndex) {
        // Setting an empty timeline or invalid seek transitions to ended.
        maskingPlaybackState = Player.STATE_ENDED;
      } else {
        maskingPlaybackState = Player.STATE_BUFFERING;
      }
    }
    newPlayerInfo =
        newPlayerInfo.copyWithPlaybackState(maskingPlaybackState, playerInfo.playerError);

    updatePlayerInfo(
        newPlayerInfo,
        /* timelineChangeReason= */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        /* positionDiscontinuity= */ !playerInfo.timeline.isEmpty(),
        Player.DISCONTINUITY_REASON_REMOVE,
        /* mediaItemTransition= */ !playerInfo.timeline.isEmpty()
            || !newPlayerInfo.timeline.isEmpty(),
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
  }

  private void moveMediaItemsInternal(int fromIndex, int toIndex, int newIndex) {
    Timeline oldTimeline = playerInfo.timeline;
    int playlistSize = playerInfo.timeline.getWindowCount();
    toIndex = min(toIndex, playlistSize);
    newIndex = min(newIndex, playlistSize - (toIndex - fromIndex));
    if (fromIndex >= playlistSize || fromIndex == toIndex || fromIndex == newIndex) {
      return;
    }

    List<Window> newWindows = new ArrayList<>();
    List<Period> newPeriods = new ArrayList<>();

    for (int i = 0; i < playlistSize; i++) {
      newWindows.add(oldTimeline.getWindow(i, new Window()));
    }
    Util.moveItems(newWindows, fromIndex, toIndex, newIndex);
    rebuildPeriods(oldTimeline, newWindows, newPeriods);
    Timeline newTimeline = createMaskingTimeline(newWindows, newPeriods);

    if (!newTimeline.isEmpty()) {
      int oldWindowIndex = getCurrentMediaItemIndex();
      int newWindowIndex = oldWindowIndex;
      if (oldWindowIndex >= fromIndex && oldWindowIndex < toIndex) {
        // if old window index was part of items that should be moved.
        newWindowIndex = (oldWindowIndex - fromIndex) + newIndex;
      } else {
        if (toIndex <= oldWindowIndex && newIndex > oldWindowIndex) {
          // if items were moved from before the old window index to after the old window index.
          newWindowIndex = oldWindowIndex - (toIndex - fromIndex);
        } else if (toIndex > oldWindowIndex && newIndex <= oldWindowIndex) {
          // if items were moved from after the old window index to before the old window index.
          newWindowIndex = oldWindowIndex + (toIndex - fromIndex);
        }
      }
      Window window = new Window();
      int oldPeriodIndex = playerInfo.sessionPositionInfo.positionInfo.periodIndex;
      int deltaFromFirstPeriodIndex =
          oldPeriodIndex - oldTimeline.getWindow(oldWindowIndex, window).firstPeriodIndex;
      int newPeriodIndex =
          newTimeline.getWindow(newWindowIndex, window).firstPeriodIndex
              + deltaFromFirstPeriodIndex;
      PlayerInfo newPlayerInfo =
          maskTimelineAndPositionInfo(
              playerInfo,
              newTimeline,
              newWindowIndex,
              newPeriodIndex,
              Player.DISCONTINUITY_REASON_INTERNAL);

      updatePlayerInfo(
          newPlayerInfo,
          /* timelineChangeReason= */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
          /* ignored */ Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
          /* positionDiscontinuity= */ false,
          /* ignored */ Player.DISCONTINUITY_REASON_INTERNAL,
          /* mediaItemTransition= */ false,
          /* ignored */ Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT);
    }
  }

  private void seekToInternalByOffset(long offsetMs) {
    long positionMs = getCurrentPosition() + offsetMs;
    long durationMs = getDuration();
    if (durationMs != C.TIME_UNSET) {
      positionMs = min(positionMs, durationMs);
    }
    positionMs = max(positionMs, 0);
    seekToInternal(getCurrentMediaItemIndex(), positionMs);
  }

  private void seekToInternal(int windowIndex, long positionMs) {
    Timeline timeline = playerInfo.timeline;
    if ((!timeline.isEmpty() && windowIndex >= timeline.getWindowCount()) || isPlayingAd()) {
      return;
    }

    @Player.State
    int newPlaybackState =
        getPlaybackState() == Player.STATE_IDLE ? Player.STATE_IDLE : Player.STATE_BUFFERING;
    PlayerInfo newPlayerInfo =
        playerInfo.copyWithPlaybackState(newPlaybackState, playerInfo.playerError);
    @Nullable PeriodInfo periodInfo = getPeriodInfo(timeline, windowIndex, positionMs);
    if (periodInfo == null) {
      // Timeline is empty.
      PositionInfo newPositionInfo =
          new PositionInfo(
              /* windowUid= */ null,
              windowIndex,
              /* mediaItem= */ null,
              /* periodUid= */ null,
              /* periodIndex= */ 0,
              /* positionMs= */ positionMs == C.TIME_UNSET ? 0 : positionMs,
              /* contentPositionMs= */ positionMs == C.TIME_UNSET ? 0 : positionMs,
              /* adGroupIndex= */ C.INDEX_UNSET,
              /* adIndexInAdGroup= */ C.INDEX_UNSET);
      newPlayerInfo =
          maskTimelineAndPositionInfo(
              playerInfo,
              playerInfo.timeline,
              newPositionInfo,
              new SessionPositionInfo(
                  newPositionInfo,
                  playerInfo.sessionPositionInfo.isPlayingAd,
                  /* eventTimeMs= */ SystemClock.elapsedRealtime(),
                  playerInfo.sessionPositionInfo.durationMs,
                  /* bufferedPositionMs= */ positionMs == C.TIME_UNSET ? 0 : positionMs,
                  /* bufferedPercentage= */ 0,
                  /* totalBufferedDurationMs= */ 0,
                  playerInfo.sessionPositionInfo.currentLiveOffsetMs,
                  playerInfo.sessionPositionInfo.contentDurationMs,
                  /* contentBufferedPositionMs= */ positionMs == C.TIME_UNSET ? 0 : positionMs),
              Player.DISCONTINUITY_REASON_SEEK);
    } else {
      newPlayerInfo = maskPositionInfo(newPlayerInfo, timeline, periodInfo);
    }
    boolean mediaItemTransition =
        !playerInfo.timeline.isEmpty()
            && newPlayerInfo.sessionPositionInfo.positionInfo.mediaItemIndex
                != playerInfo.sessionPositionInfo.positionInfo.mediaItemIndex;
    boolean positionDiscontinuity =
        mediaItemTransition
            || newPlayerInfo.sessionPositionInfo.positionInfo.positionMs
                != playerInfo.sessionPositionInfo.positionInfo.positionMs;
    if (!positionDiscontinuity) {
      return;
    }
    updatePlayerInfo(
        newPlayerInfo,
        /* ignored */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        /* ignored */ Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        positionDiscontinuity,
        /* positionDiscontinuityReason= */ Player.DISCONTINUITY_REASON_SEEK,
        mediaItemTransition,
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK);
  }

  private void setPlayWhenReady(
      boolean playWhenReady,
      @Player.PlaybackSuppressionReason int playbackSuppressionReason,
      @Player.PlayWhenReadyChangeReason int playWhenReadyChangeReason) {
    if (playerInfo.playWhenReady == playWhenReady
        && playerInfo.playbackSuppressionReason == playbackSuppressionReason) {
      return;
    }

    // Stop estimating content position until a new positionInfo arrives from the player
    lastSetPlayWhenReadyCalledTimeMs = SystemClock.elapsedRealtime();
    PlayerInfo playerInfo =
        this.playerInfo.copyWithPlayWhenReady(
            playWhenReady, playWhenReadyChangeReason, playbackSuppressionReason);
    updatePlayerInfo(
        playerInfo,
        /* ignored */ Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        playWhenReadyChangeReason,
        /* positionDiscontinuity= */ false,
        /* ignored */ Player.DISCONTINUITY_REASON_INTERNAL,
        /* mediaItemTransition= */ false,
        /* ignored */ Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT);
  }

  private void updatePlayerInfo(
      PlayerInfo newPlayerInfo,
      @Player.TimelineChangeReason int timelineChangeReason,
      @Player.PlayWhenReadyChangeReason int playWhenReadyChangeReason,
      boolean positionDiscontinuity,
      @Player.DiscontinuityReason int positionDiscontinuityReason,
      boolean mediaItemTransition,
      @Player.MediaItemTransitionReason int mediaItemTransitionReason) {
    // Assign player info immediately such that all getters return the right values, but keep
    // snapshot of previous and new states so that listener invocations are triggered correctly.
    PlayerInfo oldPlayerInfo = this.playerInfo;
    this.playerInfo = newPlayerInfo;

    if (mediaItemTransition) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_MEDIA_ITEM_TRANSITION,
          listener ->
              listener.onMediaItemTransition(
                  newPlayerInfo.getCurrentMediaItem(), mediaItemTransitionReason));
    }
    if (positionDiscontinuity) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_POSITION_DISCONTINUITY,
          listener ->
              listener.onPositionDiscontinuity(
                  newPlayerInfo.oldPositionInfo,
                  newPlayerInfo.newPositionInfo,
                  positionDiscontinuityReason));
    }
    if (!oldPlayerInfo.timeline.equals(newPlayerInfo.timeline)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_TIMELINE_CHANGED,
          listener -> listener.onTimelineChanged(newPlayerInfo.timeline, timelineChangeReason));
    }
    if (oldPlayerInfo.playbackState != newPlayerInfo.playbackState) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_STATE_CHANGED,
          listener -> listener.onPlaybackStateChanged(newPlayerInfo.playbackState));
    }
    if (oldPlayerInfo.playWhenReady != newPlayerInfo.playWhenReady) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAY_WHEN_READY_CHANGED,
          listener ->
              listener.onPlayWhenReadyChanged(
                  newPlayerInfo.playWhenReady, playWhenReadyChangeReason));
    }
    if (oldPlayerInfo.playbackSuppressionReason != newPlayerInfo.playbackSuppressionReason) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
          listener ->
              listener.onPlaybackSuppressionReasonChanged(newPlayerInfo.playbackSuppressionReason));
    }
    if (oldPlayerInfo.isPlaying != newPlayerInfo.isPlaying) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_IS_PLAYING_CHANGED,
          listener -> listener.onIsPlayingChanged(newPlayerInfo.isPlaying));
    }
    listeners.flushEvents();
  }

  private boolean requestConnectToService() {
    int flags =
        Util.SDK_INT >= 29
            ? Context.BIND_AUTO_CREATE | Context.BIND_INCLUDE_CAPABILITIES
            : Context.BIND_AUTO_CREATE;

    // Service. Needs to get fresh binder whenever connection is needed.
    Intent intent = new Intent(MediaSessionService.SERVICE_INTERFACE);
    intent.setClassName(token.getPackageName(), token.getServiceName());

    // Use bindService() instead of startForegroundService() to start session service for three
    // reasons.
    // 1. Prevent session service owner's stopSelf() from destroying service.
    //    With the startForegroundService(), service's call of stopSelf() will trigger immediate
    //    onDestroy() calls on the main thread even when onConnect() is running in another
    //    thread.
    // 2. Minimize APIs for developers to take care about.
    //    With bindService(), developers only need to take care about Service.onBind()
    //    but Service.onStartCommand() should be also taken care about with the
    //    startForegroundService().
    // 3. Future support for UI-less playback
    //    If a service wants to keep running, it should be either foreground service or
    //    bound service. But there had been request for the feature for system apps
    //    and using bindService() will be better fit with it.
    boolean result = context.bindService(intent, serviceConnection, flags);
    if (!result) {
      Log.w(TAG, "bind to " + token + " failed");
      return false;
    }
    return true;
  }

  private boolean requestConnectToSession(Bundle connectionHints) {
    IMediaSession iSession =
        IMediaSession.Stub.asInterface((IBinder) checkStateNotNull(token.getBinder()));
    int seq = sequencedFutureManager.obtainNextSequenceNumber();
    ConnectionRequest request =
        new ConnectionRequest(context.getPackageName(), Process.myPid(), connectionHints);
    try {
      iSession.connect(controllerStub, seq, request.toBundle());
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to call connection request.", e);
      return false;
    }
    return true;
  }

  private void clearSurfacesAndCallbacks() {
    if (videoTextureView != null) {
      videoTextureView.setSurfaceTextureListener(null);
      videoTextureView = null;
    }
    if (videoSurfaceHolder != null) {
      videoSurfaceHolder.removeCallback(surfaceCallback);
      videoSurfaceHolder = null;
    }
    if (videoSurface != null) {
      videoSurface = null;
    }
  }

  private void maybeNotifySurfaceSizeChanged(int width, int height) {
    if (surfaceSize.getWidth() != width || surfaceSize.getHeight() != height) {
      surfaceSize = new Size(width, height);
      listeners.sendEvent(
          /* eventFlag= */ Player.EVENT_SURFACE_SIZE_CHANGED,
          listener -> listener.onSurfaceSizeChanged(width, height));
    }
  }

  /** Returns session interface if the controller can send the predefined command. */
  @Nullable
  IMediaSession getSessionInterfaceWithSessionCommandIfAble(
      @SessionCommand.CommandCode int commandCode) {
    checkArgument(commandCode != SessionCommand.COMMAND_CODE_CUSTOM);
    if (!sessionCommands.contains(commandCode)) {
      Log.w(TAG, "Controller isn't allowed to call command, commandCode=" + commandCode);
      return null;
    }
    return iSession;
  }

  /** Returns session interface if the controller can send the custom command. */
  @Nullable
  IMediaSession getSessionInterfaceWithSessionCommandIfAble(SessionCommand command) {
    checkArgument(command.commandCode == SessionCommand.COMMAND_CODE_CUSTOM);
    if (!sessionCommands.contains(command)) {
      Log.w(TAG, "Controller isn't allowed to call custom session command:" + command.customAction);
      return null;
    }
    return iSession;
  }

  void notifyPeriodicSessionPositionInfoChanged(SessionPositionInfo sessionPositionInfo) {
    if (!isConnected()) {
      return;
    }
    updateSessionPositionInfoIfNeeded(sessionPositionInfo);
  }

  <T extends @NonNull Object> void setFutureResult(int seq, T futureResult) {
    // Don't set the future result on the application looper so that the result can be obtained by a
    // blocking future.get() on the application looper. But post a message to remove the pending
    // masking operation on the application looper to ensure it's executed in order with other
    // updates sent to the application looper.
    sequencedFutureManager.setFutureResult(seq, futureResult);
    getInstance().runOnApplicationLooper(() -> pendingMaskingSequencedFutureNumbers.remove(seq));
  }

  void onConnected(ConnectionState result) {
    if (iSession != null) {
      Log.e(
          TAG,
          "Cannot be notified about the connection result many times."
              + " Probably a bug or malicious app.");
      getInstance().release();
      return;
    }
    iSession = result.sessionBinder;
    sessionActivity = result.sessionActivity;
    sessionCommands = result.sessionCommands;
    playerCommandsFromSession = result.playerCommandsFromSession;
    playerCommandsFromPlayer = result.playerCommandsFromPlayer;
    intersectedPlayerCommands = intersect(playerCommandsFromSession, playerCommandsFromPlayer);
    playerInfo = result.playerInfo;
    try {
      // Implementation for the local binder is no-op,
      // so can be used without worrying about deadlock.
      result.sessionBinder.asBinder().linkToDeath(deathRecipient, 0);
    } catch (RemoteException e) {
      getInstance().release();
      return;
    }
    connectedToken =
        new SessionToken(
            token.getUid(),
            SessionToken.TYPE_SESSION,
            result.libraryVersion,
            result.sessionInterfaceVersion,
            token.getPackageName(),
            result.sessionBinder,
            result.tokenExtras);
    getInstance().notifyAccepted();
  }

  private void sendControllerResult(int seq, SessionResult result) {
    IMediaSession iSession = this.iSession;
    if (iSession == null) {
      return;
    }
    try {
      iSession.onControllerResult(controllerStub, seq, result.toBundle());
    } catch (RemoteException e) {
      Log.w(TAG, "Error in sending");
    }
  }

  private void sendControllerResultWhenReady(int seq, ListenableFuture<SessionResult> future) {
    future.addListener(
        () -> {
          SessionResult result;
          try {
            result = checkNotNull(future.get(), "SessionResult must not be null");
          } catch (CancellationException unused) {
            result = new SessionResult(SessionResult.RESULT_INFO_SKIPPED);
          } catch (ExecutionException | InterruptedException unused) {
            result = new SessionResult(SessionResult.RESULT_ERROR_UNKNOWN);
          }
          sendControllerResult(seq, result);
        },
        MoreExecutors.directExecutor());
  }

  void onCustomCommand(int seq, SessionCommand command, Bundle args) {
    if (!isConnected()) {
      return;
    }
    getInstance()
        .notifyControllerListener(
            listener -> {
              ListenableFuture<SessionResult> future =
                  checkNotNull(
                      listener.onCustomCommand(getInstance(), command, args),
                      "ControllerCallback#onCustomCommand() must not return null");
              sendControllerResultWhenReady(seq, future);
            });
  }

  @SuppressWarnings("deprecation") // Implementing and calling deprecated listener method.
  void onPlayerInfoChanged(PlayerInfo newPlayerInfo, BundlingExclusions bundlingExclusions) {
    if (!isConnected()) {
      return;
    }
    if (pendingPlayerInfo != null && pendingBundlingExclusions != null) {
      Pair<PlayerInfo, BundlingExclusions> mergedPlayerInfoUpdate =
          mergePlayerInfo(
              pendingPlayerInfo,
              pendingBundlingExclusions,
              newPlayerInfo,
              bundlingExclusions,
              intersectedPlayerCommands);
      newPlayerInfo = mergedPlayerInfoUpdate.first;
      bundlingExclusions = mergedPlayerInfoUpdate.second;
    }
    pendingPlayerInfo = null;
    pendingBundlingExclusions = null;
    if (!pendingMaskingSequencedFutureNumbers.isEmpty()) {
      // We are still waiting for all pending masking operations to be handled.
      pendingPlayerInfo = newPlayerInfo;
      pendingBundlingExclusions = bundlingExclusions;
      return;
    }
    PlayerInfo oldPlayerInfo = playerInfo;
    // Assigning class variable now so that all getters called from listeners see the updated value.
    // But we need to use a local final variable to ensure listeners get consistent parameters.
    playerInfo =
        mergePlayerInfo(
                oldPlayerInfo,
                /* oldBundlingExclusions= */ BundlingExclusions.NONE,
                newPlayerInfo,
                /* newBundlingExclusions= */ bundlingExclusions,
                intersectedPlayerCommands)
            .first;
    PlayerInfo finalPlayerInfo = playerInfo;
    PlaybackException oldPlayerError = oldPlayerInfo.playerError;
    PlaybackException playerError = finalPlayerInfo.playerError;
    boolean errorsMatch =
        oldPlayerError == playerError
            || (oldPlayerError != null && oldPlayerError.errorInfoEquals(playerError));
    if (!errorsMatch) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYER_ERROR,
          listener -> listener.onPlayerErrorChanged(finalPlayerInfo.playerError));
      if (finalPlayerInfo.playerError != null) {
        listeners.queueEvent(
            /* eventFlag= */ Player.EVENT_PLAYER_ERROR,
            listener -> listener.onPlayerError(finalPlayerInfo.playerError));
      }
    }
    MediaItem oldCurrentMediaItem = oldPlayerInfo.getCurrentMediaItem();
    MediaItem currentMediaItem = finalPlayerInfo.getCurrentMediaItem();
    if (!Util.areEqual(oldCurrentMediaItem, currentMediaItem)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_MEDIA_ITEM_TRANSITION,
          listener ->
              listener.onMediaItemTransition(
                  currentMediaItem, finalPlayerInfo.mediaItemTransitionReason));
    }
    if (!Util.areEqual(oldPlayerInfo.currentTracks, finalPlayerInfo.currentTracks)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_TRACKS_CHANGED,
          listener -> listener.onTracksChanged(finalPlayerInfo.currentTracks));
    }
    if (!Util.areEqual(oldPlayerInfo.playbackParameters, finalPlayerInfo.playbackParameters)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          listener -> listener.onPlaybackParametersChanged(finalPlayerInfo.playbackParameters));
    }
    if (oldPlayerInfo.repeatMode != finalPlayerInfo.repeatMode) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_REPEAT_MODE_CHANGED,
          listener -> listener.onRepeatModeChanged(finalPlayerInfo.repeatMode));
    }
    if (oldPlayerInfo.shuffleModeEnabled != finalPlayerInfo.shuffleModeEnabled) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
          listener -> listener.onShuffleModeEnabledChanged(finalPlayerInfo.shuffleModeEnabled));
    }
    if (!Util.areEqual(oldPlayerInfo.timeline, finalPlayerInfo.timeline)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_TIMELINE_CHANGED,
          listener ->
              listener.onTimelineChanged(
                  finalPlayerInfo.timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE));
    }
    if (!Util.areEqual(oldPlayerInfo.playlistMetadata, finalPlayerInfo.playlistMetadata)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYLIST_METADATA_CHANGED,
          listener -> listener.onPlaylistMetadataChanged(finalPlayerInfo.playlistMetadata));
    }
    if (oldPlayerInfo.volume != finalPlayerInfo.volume) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_VOLUME_CHANGED,
          listener -> listener.onVolumeChanged(finalPlayerInfo.volume));
    }
    if (!Util.areEqual(oldPlayerInfo.audioAttributes, finalPlayerInfo.audioAttributes)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_AUDIO_ATTRIBUTES_CHANGED,
          listener -> listener.onAudioAttributesChanged(finalPlayerInfo.audioAttributes));
    }
    if (!oldPlayerInfo.cueGroup.cues.equals(finalPlayerInfo.cueGroup.cues)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_CUES,
          listener -> listener.onCues(finalPlayerInfo.cueGroup.cues));
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_CUES,
          listener -> listener.onCues(finalPlayerInfo.cueGroup));
    }
    if (!Util.areEqual(oldPlayerInfo.deviceInfo, finalPlayerInfo.deviceInfo)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_DEVICE_INFO_CHANGED,
          listener -> listener.onDeviceInfoChanged(finalPlayerInfo.deviceInfo));
    }
    if (oldPlayerInfo.deviceVolume != finalPlayerInfo.deviceVolume
        || oldPlayerInfo.deviceMuted != finalPlayerInfo.deviceMuted) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_DEVICE_VOLUME_CHANGED,
          listener ->
              listener.onDeviceVolumeChanged(
                  finalPlayerInfo.deviceVolume, finalPlayerInfo.deviceMuted));
    }
    if (oldPlayerInfo.playWhenReady != finalPlayerInfo.playWhenReady) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAY_WHEN_READY_CHANGED,
          listener ->
              listener.onPlayWhenReadyChanged(
                  finalPlayerInfo.playWhenReady, finalPlayerInfo.playWhenReadyChangedReason));
    }
    if (oldPlayerInfo.playbackSuppressionReason != finalPlayerInfo.playbackSuppressionReason) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
          listener ->
              listener.onPlaybackSuppressionReasonChanged(
                  finalPlayerInfo.playbackSuppressionReason));
    }
    if (oldPlayerInfo.playbackState != finalPlayerInfo.playbackState) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_PLAYBACK_STATE_CHANGED,
          listener -> listener.onPlaybackStateChanged(finalPlayerInfo.playbackState));
    }
    if (oldPlayerInfo.isPlaying != finalPlayerInfo.isPlaying) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_IS_PLAYING_CHANGED,
          listener -> listener.onIsPlayingChanged(finalPlayerInfo.isPlaying));
    }
    if (oldPlayerInfo.isLoading != finalPlayerInfo.isLoading) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_IS_LOADING_CHANGED,
          listener -> listener.onIsLoadingChanged(finalPlayerInfo.isLoading));
    }
    if (!Util.areEqual(oldPlayerInfo.videoSize, finalPlayerInfo.videoSize)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_VIDEO_SIZE_CHANGED,
          listener -> listener.onVideoSizeChanged(finalPlayerInfo.videoSize));
    }
    if (!Util.areEqual(oldPlayerInfo.oldPositionInfo, finalPlayerInfo.oldPositionInfo)
        || !Util.areEqual(oldPlayerInfo.newPositionInfo, finalPlayerInfo.newPositionInfo)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_POSITION_DISCONTINUITY,
          listener ->
              listener.onPositionDiscontinuity(
                  finalPlayerInfo.oldPositionInfo,
                  finalPlayerInfo.newPositionInfo,
                  finalPlayerInfo.discontinuityReason));
    }
    if (!Util.areEqual(oldPlayerInfo.mediaMetadata, finalPlayerInfo.mediaMetadata)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_MEDIA_METADATA_CHANGED,
          listener -> listener.onMediaMetadataChanged(finalPlayerInfo.mediaMetadata));
    }
    if (oldPlayerInfo.seekBackIncrementMs != finalPlayerInfo.seekBackIncrementMs) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_SEEK_BACK_INCREMENT_CHANGED,
          listener -> listener.onSeekBackIncrementChanged(finalPlayerInfo.seekBackIncrementMs));
    }
    if (oldPlayerInfo.seekForwardIncrementMs != finalPlayerInfo.seekForwardIncrementMs) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_SEEK_FORWARD_INCREMENT_CHANGED,
          listener ->
              listener.onSeekForwardIncrementChanged(finalPlayerInfo.seekForwardIncrementMs));
    }
    if (oldPlayerInfo.maxSeekToPreviousPositionMs != finalPlayerInfo.maxSeekToPreviousPositionMs) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED,
          listener ->
              listener.onMaxSeekToPreviousPositionChanged(
                  finalPlayerInfo.maxSeekToPreviousPositionMs));
    }
    if (!Util.areEqual(
        oldPlayerInfo.trackSelectionParameters, finalPlayerInfo.trackSelectionParameters)) {
      listeners.queueEvent(
          /* eventFlag= */ Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
          listener ->
              listener.onTrackSelectionParametersChanged(finalPlayerInfo.trackSelectionParameters));
    }
    listeners.flushEvents();
  }

  void onAvailableCommandsChangedFromSession(
      SessionCommands sessionCommands, Commands playerCommands) {
    if (!isConnected()) {
      return;
    }
    boolean playerCommandsChanged = !Util.areEqual(playerCommandsFromSession, playerCommands);
    boolean sessionCommandsChanged = !Util.areEqual(this.sessionCommands, sessionCommands);
    if (!playerCommandsChanged && !sessionCommandsChanged) {
      return;
    }
    boolean intersectedPlayerCommandsChanged = false;
    if (playerCommandsChanged) {
      playerCommandsFromSession = playerCommands;
      Commands prevIntersectedPlayerCommands = intersectedPlayerCommands;
      intersectedPlayerCommands = intersect(playerCommandsFromSession, playerCommandsFromPlayer);
      intersectedPlayerCommandsChanged =
          !Util.areEqual(intersectedPlayerCommands, prevIntersectedPlayerCommands);
    }
    if (sessionCommandsChanged) {
      this.sessionCommands = sessionCommands;
    }
    if (intersectedPlayerCommandsChanged) {
      listeners.sendEvent(
          /* eventFlag= */ Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
          listener -> listener.onAvailableCommandsChanged(intersectedPlayerCommands));
    }
    if (sessionCommandsChanged) {
      getInstance()
          .notifyControllerListener(
              listener ->
                  listener.onAvailableSessionCommandsChanged(getInstance(), sessionCommands));
    }
  }

  void onAvailableCommandsChangedFromPlayer(Commands commandsFromPlayer) {
    if (!isConnected()) {
      return;
    }
    if (Util.areEqual(playerCommandsFromPlayer, commandsFromPlayer)) {
      return;
    }
    playerCommandsFromPlayer = commandsFromPlayer;
    Commands prevIntersectedPlayerCommands = intersectedPlayerCommands;
    intersectedPlayerCommands = intersect(playerCommandsFromSession, playerCommandsFromPlayer);
    boolean intersectedPlayerCommandsChanged =
        !Util.areEqual(intersectedPlayerCommands, prevIntersectedPlayerCommands);
    if (intersectedPlayerCommandsChanged) {
      listeners.sendEvent(
          /* eventFlag= */ Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
          listener -> listener.onAvailableCommandsChanged(intersectedPlayerCommands));
    }
  }

  void onSetCustomLayout(int seq, List<CommandButton> layout) {
    if (!isConnected()) {
      return;
    }
    List<CommandButton> validatedCustomLayout = new ArrayList<>();
    for (int i = 0; i < layout.size(); i++) {
      CommandButton button = layout.get(i);
      if (intersectedPlayerCommands.contains(button.playerCommand)
          || (button.sessionCommand != null && sessionCommands.contains(button.sessionCommand))
          || (button.playerCommand != Player.COMMAND_INVALID
              && sessionCommands.contains(button.playerCommand))) {
        validatedCustomLayout.add(button);
      }
    }
    getInstance()
        .notifyControllerListener(
            listener -> {
              ListenableFuture<SessionResult> future =
                  checkNotNull(
                      listener.onSetCustomLayout(getInstance(), validatedCustomLayout),
                      "MediaController.Listener#onSetCustomLayout() must not return null");
              sendControllerResultWhenReady(seq, future);
            });
  }

  public void onExtrasChanged(Bundle extras) {
    if (!isConnected()) {
      return;
    }
    getInstance()
        .notifyControllerListener(listener -> listener.onExtrasChanged(getInstance(), extras));
  }

  public void onRenderedFirstFrame() {
    listeners.sendEvent(
        /* eventFlag= */ Player.EVENT_RENDERED_FIRST_FRAME, Listener::onRenderedFirstFrame);
  }

  private void updateSessionPositionInfoIfNeeded(SessionPositionInfo sessionPositionInfo) {
    if (pendingMaskingSequencedFutureNumbers.isEmpty()
        && playerInfo.sessionPositionInfo.eventTimeMs < sessionPositionInfo.eventTimeMs) {
      playerInfo = playerInfo.copyWithSessionPositionInfo(sessionPositionInfo);
    }
  }

  @Player.RepeatMode
  private static int convertRepeatModeForNavigation(@Player.RepeatMode int repeatMode) {
    return repeatMode == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_OFF : repeatMode;
  }

  private boolean isPlayerCommandAvailable(@Player.Command int command) {
    if (!intersectedPlayerCommands.contains(command)) {
      Log.w(TAG, "Controller isn't allowed to call command= " + command);
      return false;
    }
    return true;
  }

  private PlayerInfo maskPositionInfo(
      PlayerInfo playerInfo, Timeline timeline, PeriodInfo periodInfo) {
    int oldPeriodIndex = playerInfo.sessionPositionInfo.positionInfo.periodIndex;
    int newPeriodIndex = periodInfo.index;
    Period oldPeriod = new Period();
    timeline.getPeriod(oldPeriodIndex, oldPeriod);
    Period newPeriod = new Period();
    timeline.getPeriod(newPeriodIndex, newPeriod);
    boolean playingPeriodChanged = oldPeriodIndex != newPeriodIndex;
    long newPositionUs = periodInfo.periodPositionUs;
    long oldPositionUs = Util.msToUs(getCurrentPosition()) - oldPeriod.getPositionInWindowUs();

    if (!playingPeriodChanged && newPositionUs == oldPositionUs) {
      // Period position remains unchanged.
      return playerInfo;
    }

    checkState(playerInfo.sessionPositionInfo.positionInfo.adGroupIndex == C.INDEX_UNSET);

    PositionInfo oldPositionInfo =
        new PositionInfo(
            /* windowUid= */ null,
            oldPeriod.windowIndex,
            playerInfo.sessionPositionInfo.positionInfo.mediaItem,
            /* periodUid= */ null,
            oldPeriodIndex,
            /* positionMs= */ usToMs(oldPeriod.positionInWindowUs + oldPositionUs),
            /* contentPositionMs= */ usToMs(oldPeriod.positionInWindowUs + oldPositionUs),
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET);

    timeline.getPeriod(newPeriodIndex, newPeriod);
    Window newWindow = new Window();
    timeline.getWindow(newPeriod.windowIndex, newWindow);
    PositionInfo newPositionInfo =
        new PositionInfo(
            /* windowUid= */ null,
            newPeriod.windowIndex,
            newWindow.mediaItem,
            /* periodUid= */ null,
            newPeriodIndex,
            /* positionMs= */ usToMs(newPeriod.positionInWindowUs + newPositionUs),
            /* contentPositionMs= */ usToMs(newPeriod.positionInWindowUs + newPositionUs),
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET);
    playerInfo =
        playerInfo.copyWithPositionInfos(
            oldPositionInfo, newPositionInfo, Player.DISCONTINUITY_REASON_SEEK);

    if (playingPeriodChanged || newPositionUs < oldPositionUs) {
      // The playing period changes or a backwards seek within the playing period occurs.
      playerInfo =
          playerInfo.copyWithSessionPositionInfo(
              new SessionPositionInfo(
                  newPositionInfo,
                  /* isPlayingAd= */ false,
                  /* eventTimeMs= */ SystemClock.elapsedRealtime(),
                  newWindow.getDurationMs(),
                  /* bufferedPositionMs= */ usToMs(newPeriod.positionInWindowUs + newPositionUs),
                  /* bufferedPercentage= */ calculateBufferedPercentage(
                      /* bufferedPositionMs= */ usToMs(
                          newPeriod.positionInWindowUs + newPositionUs),
                      newWindow.getDurationMs()),
                  /* totalBufferedDurationMs= */ 0,
                  /* currentLiveOffsetMs= */ C.TIME_UNSET,
                  /* contentDurationMs= */ C.TIME_UNSET,
                  /* contentBufferedPositionMs= */ usToMs(
                      newPeriod.positionInWindowUs + newPositionUs)));
    } else {
      // A forward seek within the playing period (timeline did not change).
      long maskedTotalBufferedDurationUs =
          max(
              0,
              Util.msToUs(playerInfo.sessionPositionInfo.totalBufferedDurationMs)
                  - (newPositionUs - oldPositionUs));
      long maskedBufferedPositionUs = newPositionUs + maskedTotalBufferedDurationUs;

      playerInfo =
          playerInfo.copyWithSessionPositionInfo(
              new SessionPositionInfo(
                  newPositionInfo,
                  /* isPlayingAd= */ false,
                  /* eventTimeMs= */ SystemClock.elapsedRealtime(),
                  newWindow.getDurationMs(),
                  /* bufferedPositionMs= */ usToMs(maskedBufferedPositionUs),
                  /* bufferedPercentage= */ calculateBufferedPercentage(
                      usToMs(maskedBufferedPositionUs), newWindow.getDurationMs()),
                  /* totalBufferedDurationMs= */ usToMs(maskedTotalBufferedDurationUs),
                  /* currentLiveOffsetMs= */ C.TIME_UNSET,
                  /* contentDurationMs= */ C.TIME_UNSET,
                  /* contentBufferedPositionMs= */ usToMs(maskedBufferedPositionUs)));
    }
    return playerInfo;
  }

  @Nullable
  private PeriodInfo getPeriodInfo(Timeline timeline, int windowIndex, long windowPositionMs) {
    if (timeline.isEmpty()) {
      return null;
    }
    Window window = new Window();
    Period period = new Period();
    if (windowIndex == C.INDEX_UNSET || windowIndex >= timeline.getWindowCount()) {
      // Use default position of timeline if window index still unset or if a previous initial seek
      // now turns out to be invalid.
      windowIndex = timeline.getFirstWindowIndex(getShuffleModeEnabled());
      windowPositionMs = timeline.getWindow(windowIndex, window).getDefaultPositionMs();
    }
    return getPeriodInfo(timeline, window, period, windowIndex, Util.msToUs(windowPositionMs));
  }

  @Nullable
  private PeriodInfo getPeriodInfo(
      Timeline timeline, Window window, Period period, int windowIndex, long windowPositionUs) {
    checkIndex(windowIndex, 0, timeline.getWindowCount());
    timeline.getWindow(windowIndex, window);
    if (windowPositionUs == C.TIME_UNSET) {
      windowPositionUs = window.getDefaultPositionUs();
      if (windowPositionUs == C.TIME_UNSET) {
        return null;
      }
    }
    int periodIndex = window.firstPeriodIndex;
    timeline.getPeriod(periodIndex, period);
    while (periodIndex < window.lastPeriodIndex
        && period.positionInWindowUs != windowPositionUs
        && timeline.getPeriod(periodIndex + 1, period).positionInWindowUs <= windowPositionUs) {
      periodIndex++;
    }
    timeline.getPeriod(periodIndex, period);
    long periodPositionUs = windowPositionUs - period.positionInWindowUs;
    return new PeriodInfo(periodIndex, periodPositionUs);
  }

  private PlayerInfo maskTimelineAndPositionInfo(
      PlayerInfo playerInfo,
      Timeline timeline,
      int newMediaItemIndex,
      int newPeriodIndex,
      int discontinuityReason) {
    PositionInfo newPositionInfo =
        new PositionInfo(
            /* windowUid= */ null,
            newMediaItemIndex,
            timeline.getWindow(newMediaItemIndex, new Window()).mediaItem,
            /* periodUid= */ null,
            newPeriodIndex,
            playerInfo.sessionPositionInfo.positionInfo.positionMs,
            playerInfo.sessionPositionInfo.positionInfo.contentPositionMs,
            playerInfo.sessionPositionInfo.positionInfo.adGroupIndex,
            playerInfo.sessionPositionInfo.positionInfo.adIndexInAdGroup);
    return maskTimelineAndPositionInfo(
        playerInfo,
        timeline,
        newPositionInfo,
        new SessionPositionInfo(
            newPositionInfo,
            playerInfo.sessionPositionInfo.isPlayingAd,
            /* eventTimeMs= */ SystemClock.elapsedRealtime(),
            playerInfo.sessionPositionInfo.durationMs,
            playerInfo.sessionPositionInfo.bufferedPositionMs,
            playerInfo.sessionPositionInfo.bufferedPercentage,
            playerInfo.sessionPositionInfo.totalBufferedDurationMs,
            playerInfo.sessionPositionInfo.currentLiveOffsetMs,
            playerInfo.sessionPositionInfo.contentDurationMs,
            playerInfo.sessionPositionInfo.contentBufferedPositionMs),
        discontinuityReason);
  }

  private PlayerInfo maskTimelineAndPositionInfo(
      PlayerInfo playerInfo,
      Timeline timeline,
      PositionInfo newPositionInfo,
      SessionPositionInfo newSessionPositionInfo,
      int discontinuityReason) {
    playerInfo =
        new PlayerInfo.Builder(playerInfo)
            .setTimeline(timeline)
            .setOldPositionInfo(playerInfo.sessionPositionInfo.positionInfo)
            .setNewPositionInfo(newPositionInfo)
            .setSessionPositionInfo(newSessionPositionInfo)
            .setDiscontinuityReason(discontinuityReason)
            .build();
    return playerInfo;
  }

  private Period getPeriodWithNewWindowIndex(Timeline timeline, int periodIndex, int windowIndex) {
    Period period = new Period();
    timeline.getPeriod(periodIndex, period);
    period.windowIndex = windowIndex;
    return period;
  }

  private int getNewPeriodIndexWithoutRemovedPeriods(
      Timeline timeline, int oldPeriodIndex, int fromIndex, int toIndex) {
    if (oldPeriodIndex == C.INDEX_UNSET) {
      return oldPeriodIndex;
    }
    int newPeriodIndex = oldPeriodIndex;
    for (int i = fromIndex; i < toIndex; i++) {
      Window window = new Window();
      timeline.getWindow(i, window);
      int size = window.lastPeriodIndex - window.firstPeriodIndex + 1;
      newPeriodIndex -= size;
    }
    return newPeriodIndex;
  }

  private static Window createNewWindow(MediaItem mediaItem) {
    return new Window()
        .set(
            /* uid= */ 0,
            mediaItem,
            /* manifest= */ null,
            /* presentationStartTimeMs= */ 0,
            /* windowStartTimeMs= */ 0,
            /* elapsedRealtimeEpochOffsetMs= */ 0,
            /* isSeekable= */ true,
            /* isDynamic= */ false,
            /* liveConfiguration= */ null,
            /* defaultPositionUs= */ 0,
            /* durationUs= */ C.TIME_UNSET,
            /* firstPeriodIndex= */ C.INDEX_UNSET,
            /* lastPeriodIndex= */ C.INDEX_UNSET,
            /* positionInFirstPeriodUs= */ 0);
  }

  private static Period createNewPeriod(int windowIndex) {
    return new Period()
        .set(
            /* id= */ null,
            /* uid= */ null,
            windowIndex,
            /* durationUs= */ C.TIME_UNSET,
            /* positionInWindowUs= */ 0,
            /* adPlaybackState= */ AdPlaybackState.NONE,
            /* isPlaceholder= */ true);
  }

  private void rebuildPeriods(
      Timeline oldTimeline, List<Window> newWindows, List<Period> newPeriods) {
    for (int i = 0; i < newWindows.size(); i++) {
      Window window = newWindows.get(i);
      int firstPeriodIndex = window.firstPeriodIndex;
      int lastPeriodIndex = window.lastPeriodIndex;
      if (firstPeriodIndex == C.INDEX_UNSET || lastPeriodIndex == C.INDEX_UNSET) {
        window.firstPeriodIndex = newPeriods.size();
        window.lastPeriodIndex = newPeriods.size();
        newPeriods.add(createNewPeriod(i));
      } else {
        window.firstPeriodIndex = newPeriods.size();
        window.lastPeriodIndex = newPeriods.size() + (lastPeriodIndex - firstPeriodIndex);
        for (int j = firstPeriodIndex; j <= lastPeriodIndex; j++) {
          newPeriods.add(
              getPeriodWithNewWindowIndex(oldTimeline, /* periodIndex= */ j, /* windowIndex= */ i));
        }
      }
    }
  }

  private static int resolveSubsequentMediaItemIndex(
      @Player.RepeatMode int repeatMode,
      boolean shuffleModeEnabled,
      int oldMediaItemIndex,
      Timeline oldTimeline,
      int fromIndex,
      int toIndex) {
    int newMediaItemIndex = C.INDEX_UNSET;
    int maxIterations = oldTimeline.getWindowCount();
    for (int i = 0; i < maxIterations; i++) {
      oldMediaItemIndex =
          oldTimeline.getNextWindowIndex(oldMediaItemIndex, repeatMode, shuffleModeEnabled);
      if (oldMediaItemIndex == C.INDEX_UNSET) {
        // We've reached the end of the old timeline.
        break;
      }
      if (oldMediaItemIndex < fromIndex || oldMediaItemIndex >= toIndex) {
        newMediaItemIndex = oldMediaItemIndex;
        break;
      }
    }
    return newMediaItemIndex;
  }

  // This will be called on the main thread.
  private class SessionServiceConnection implements ServiceConnection {

    private final Bundle connectionHints;

    public SessionServiceConnection(Bundle connectionHints) {
      this.connectionHints = connectionHints;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      boolean connectionRequested = false;
      try {
        // Note that it's always main-thread.
        if (!token.getPackageName().equals(name.getPackageName())) {
          Log.e(
              TAG,
              "Expected connection to "
                  + token.getPackageName()
                  + " but is"
                  + " connected to "
                  + name);
          return;
        }
        IMediaSessionService iService = IMediaSessionService.Stub.asInterface(service);
        if (iService == null) {
          Log.e(TAG, "Service interface is missing.");
          return;
        }
        ConnectionRequest request =
            new ConnectionRequest(getContext().getPackageName(), Process.myPid(), connectionHints);
        iService.connect(controllerStub, request.toBundle());
        connectionRequested = true;
      } catch (RemoteException e) {
        Log.w(TAG, "Service " + name + " has died prematurely");
      } finally {
        if (!connectionRequested) {
          getInstance().runOnApplicationLooper(getInstance()::release);
        }
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      // Temporal lose of the binding because of the service crash. System will automatically
      // rebind, but we'd better to release() here. Otherwise ControllerCallback#onConnected()
      // would be called multiple times, and the controller would be connected to the
      // different session everytime.
      getInstance().runOnApplicationLooper(getInstance()::release);
    }

    @Override
    public void onBindingDied(ComponentName name) {
      // Permanent lose of the binding because of the service package update or removed.
      // This SessionServiceRecord will be removed accordingly, but forget session binder here
      // for sure.
      getInstance().runOnApplicationLooper(getInstance()::release);
    }
  }

  private class SurfaceCallback
      implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener {

    // SurfaceHolder.Callback implementation

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
      if (videoSurfaceHolder != holder) {
        return;
      }
      videoSurface = holder.getSurface();
      dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, videoSurface));
      Rect surfaceSize = holder.getSurfaceFrame();
      maybeNotifySurfaceSizeChanged(surfaceSize.width(), surfaceSize.height());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      if (videoSurfaceHolder != holder) {
        return;
      }
      maybeNotifySurfaceSizeChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
      if (videoSurfaceHolder != holder) {
        return;
      }
      videoSurface = null;
      /* surface= */ dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, null));
      maybeNotifySurfaceSizeChanged(/* width= */ 0, /* height= */ 0);
    }

    // TextureView.SurfaceTextureListener implementation

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
      if (videoTextureView == null || videoTextureView.getSurfaceTexture() != surfaceTexture) {
        return;
      }
      videoSurface = new Surface(surfaceTexture);
      dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, videoSurface));
      maybeNotifySurfaceSizeChanged(width, height);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
      if (videoTextureView == null || videoTextureView.getSurfaceTexture() != surfaceTexture) {
        return;
      }
      maybeNotifySurfaceSizeChanged(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
      if (videoTextureView == null || videoTextureView.getSurfaceTexture() != surfaceTexture) {
        return true;
      }
      videoSurface = null;
      /* surface= */ dispatchRemoteSessionTaskWithPlayerCommandAndWaitForFuture(
          (iSession, seq) -> iSession.setVideoSurface(controllerStub, seq, null));
      maybeNotifySurfaceSizeChanged(/* width= */ 0, /* height= */ 0);
      return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
      // Do nothing.
    }
  }

  private class FlushCommandQueueHandler {

    private static final int MSG_FLUSH_COMMAND_QUEUE = 1;

    private final Handler handler;

    public FlushCommandQueueHandler(Looper looper) {
      handler = new Handler(looper, /* callback= */ this::handleMessage);
    }

    public void sendFlushCommandQueueMessage() {
      if (iSession != null && !handler.hasMessages(MSG_FLUSH_COMMAND_QUEUE)) {
        // Send message to notify the end of the transaction. It will be handled when the current
        // looper iteration is over.
        handler.sendEmptyMessage(MSG_FLUSH_COMMAND_QUEUE);
      }
    }

    public void release() {
      if (handler.hasMessages(MSG_FLUSH_COMMAND_QUEUE)) {
        flushCommandQueue();
      }
      handler.removeCallbacksAndMessages(/* token= */ null);
    }

    private boolean handleMessage(Message msg) {
      if (msg.what == MSG_FLUSH_COMMAND_QUEUE) {
        flushCommandQueue();
      }
      return true;
    }

    private void flushCommandQueue() {
      try {
        iSession.flushCommandQueue(controllerStub);
      } catch (RemoteException e) {
        Log.w(TAG, "Error in sending flushCommandQueue");
      }
    }
  }

  private static final class PeriodInfo {
    private final int index;
    private final long periodPositionUs;

    public PeriodInfo(int index, long periodPositionUs) {
      this.index = index;
      this.periodPositionUs = periodPositionUs;
    }
  }
}
