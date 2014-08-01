/*
 * Copyright (c) 2014, De Novo Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.denovogroup.rangzen;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver; 
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.IOException;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Date;
import java.util.Map;

/**
 * This module exposes an API for the application to find out the current 
 * list of peers, to request that new peers be sought, and to request that
 * peers be garbage collected. The API and all the behavior of this class are 
 * independent of underlying protocols for those peers. 
 */
public class PeerManager {
  /** 
   * A static variable to hold the active instance of PeerManager so that other
   * app components can call methods in its API.
   */
  private static PeerManager sPeerManager;

  // TODO(lerner): I suspect we want to convert this to a Set eventually, since
  // that best represents the set of peers we can see (unordered, each is
  // unique).
  /** The most recent, up-to-date list of peers. */
  private List<Peer> mCurrentPeers;

  /** For app-local intent broadcasting/receiving of peer events. */
  private LocalBroadcastManager mBroadcastManager;

  /** Handle to the app's BluetoothSpeaker. */
  private BluetoothSpeaker mBluetoothSpeaker;

  /** Handle to the app's LocationStore. */
  private LocationStore mLocationStore;

  /** Handle to the app's ExchangeStore. */
  private ExchangeStore mExchangeStore;

  /** Remembers the last time we successfully had an exchange with a peer. */
  private Map<String, Date> exchangeTimes = new HashMap<String, Date>();

  /** 
   * The length of time (in milliseconds) we consider peers valid. 
   * TODO(lerner): Decide on an appropriate value for this.
   */
  public static final long PEER_TIMEOUT = 2 * 60 * 1000;

  /** 
   * The length of time we wait before talking to the same peer again, in ms.
   * TODO(lerner): Decide on an appropriate value for this.
   */
  private static final long MS_BETWEEN_EXCHANGES = 15 * 60 * 1000;


  /** Displayed in Android Monitor logs. */
  private static String TAG = "RangzenPeerManager";

  /**
   * Private constructor. Use PeerManager.getInstance() to obtain the app's
   * instance of the class.
   *
   * @param context A context object from the app.
   */
  private PeerManager(Context context) {
    mCurrentPeers = new ArrayList<Peer>();
    mBroadcastManager = LocalBroadcastManager.getInstance(context); 

    Log.d(TAG, "Finished PeerManager constructor.");
  }

  /**
   * Obtain the current instance of PeerManager.
   *
   * @param context A context object from the app.
   * @return The app's instance of PeerManager.
   */
  public static PeerManager getInstance(Context context) {
    if (sPeerManager == null) {
      sPeerManager = new PeerManager(context);
      Log.d(TAG, "Created instance of PeerManager");
    }
    return sPeerManager;
  }

  /**
   * This method garbage runs the peer garbage collector on all peers that
   * should be garbage collected. It runs synchronously and returns when done,
   * but should be very fast (deciding whether to garbage collect a peer is
   * not a complicated action).
  */
  public synchronized void garbageCollectPeers() {
    for (Peer p : mCurrentPeers) {
      if (shouldGarbageCollectPeer(p)) {
        garbageCollectPeer(p);
      }
    } 
  }

  /**
   * Check whether a peer is already in the peer list. 
   *
   * Peer equality is based on whether their PeerNetworks refer to the same
   * destinations, so two peers might be .equals() even if not ==.
   *
   * @param peer The Peer to find in the list.
   * @return True if the peer is in the list, false otherwise.
   * @see org.denovogroup.rangzen.Peer
   */
  public synchronized boolean isKnownPeer(Peer peer) {
    return mCurrentPeers.contains(peer);
  }

  /**
   * Update the last seen time of the peer.
   *
   * It is possible (indeed, frequent) to have two different peer objects
   * which logically refer to the same peer. This updates the canonical copy -
   * the one stored in the peer list - as well as the copy passed in.
   *
   * @param peer The Peer to update.
   */
  private synchronized void touchPeer(Peer peer) {
    Peer copyInList = getCanonicalPeer(peer);
    if (copyInList != null) {
      copyInList.touch();
    } 
   if (peer != null) {
      peer.touch();
    }
  }

  /**
   * If the peer given is known to the peer manager, return a canonical
   * Peer object which represents the peer and is .equals() to the peer
   * given. If the peer requested is not yet known, returns the peer
   * requested as its own canonical form.
   *
   * @param peer The peer to look up.
   * @return The canonical version of the given peer, which is the same
   * object if the peer is not yet known to the PeerManager.
   * @see org.denovogroup.rangzen.Peer
   */
  public synchronized Peer getCanonicalPeer(Peer peerDesired) {
    if (peerDesired == null) {
      return null;
    }
    for (Peer peerInList : mCurrentPeers) {
      if (peerDesired.equals(peerInList)) {
        return peerInList;
      }
    }
    // Add the peer to make it actually canonical.
    addPeer(peerDesired);
    return peerDesired;
  }

  /**
   * Check whether a peer is considered old enough to consider it unlikely
   * to return.
   */
  private synchronized boolean shouldGarbageCollectPeer(Peer peer) {
    Date lastSeen = peer.getLastSeen();
    Date now = new Date();

    long msSinceSeen = now.getTime() - lastSeen.getTime();

    // TODO(lerner): Use a more sophisticated mechanism than a simple
    // time threshold since last seen. For example, we may not want to evict
    // anyone if we haven't scanned for a while (or, maybe we do).
    return msSinceSeen > PEER_TIMEOUT;
  }

  /**
   * Invalidates a peer and removes it from the PeerManager's list of current
   * peers.
   */
  private synchronized void garbageCollectPeer(Peer peer) {
    Log.d(TAG, "Garbage collected peer " + peer);
    mCurrentPeers.remove(peer);
  }

  /**
   * Get a snapshot of the current list of peers. Peers are not guaranteed 
   * to be reachable or still in existence, and the snapshot may be outdated
   * as peers are sought.
   *
   * @return A copy of the list of currently known peers.
   */
  public synchronized List<Peer> getPeers() {
    List<Peer> copy = new ArrayList<Peer>();
    for (Peer p : mCurrentPeers) {
      copy.add(p);
    }
    return copy;
  }

  /**
   * Add list of peers to the current peers list, just as though they were
   * added individually with newPeer().
   *
   * @return The number of non-duplicate peers added.
   */
  public synchronized int addPeers(List<Peer> newPeers) {
    int nonDuplicateCount = 0;
    for (Peer p : newPeers) {
      if (addPeer(p)) {
        nonDuplicateCount++;
      }
    }
    return nonDuplicateCount;
  }

  /**
   * Remove all peers from the peer list.
   */
  public synchronized void forgetAllPeers() {
    mCurrentPeers.clear();
  }

  /**
   * Add a peer to the current list of peers. Used internally to add peers
   * discovered, but also can be called externally to add peers which use
   * very asynchronous mechanisms (e.g. SD card) which do not support
   * automatic discovery.
   *
   * @return True if the peer was added, false if the peer was a duplicate
   * and thus was already in the list.
   */
  public synchronized boolean addPeer(Peer p) {
    if (isKnownPeer(p)) {
      touchPeer(p);
      return false;
    } else {
      mCurrentPeers.add(p);
      return true;
    }
  }

  /**
   * Tell the PeerManager about the app's BluetoothSpeaker.
   *
   * @param speaker The app's instance of BluetoothSpeaker.
   */
  public void setBluetoothSpeaker(BluetoothSpeaker speaker) {
    mBluetoothSpeaker = speaker;
  }

  /**
   * Sets the location store to use.
   *
   * @param locationStore The location store to use.
   */
  /* package */ void setLocationStore(LocationStore locationStore) {
    this.mLocationStore = locationStore;
  }

  /**
   * Sets the exchange store to use.
   *
   * @param exchangeStore The exchange store to use.
   */
  /* package */ void setExchangeStore(ExchangeStore exchangeStore) {
    this.mExchangeStore = exchangeStore;
  }

  /**
   * Remember the time that this exchange occurred in a local map, in order to
   * prevent contacting the same peer repeatedly in a short time.
   *
   * @param peer The remote peer about whom we are remembering an exchange.
   * @param exchangetime The time at which we had an exchange with the peer.
   */
  private void recordExchangeTime(Peer peer, Date exchangeTime) {
    BluetoothDevice device = peer.getNetwork().getBluetoothDevice();
    if (device == null) {
      Log.e(TAG, "Recording exchange time of non-bluetooth peer! Can't do it.");
      return;
    } else {
      exchangeTimes.put(device.getAddress(), exchangeTime);
    }
  }

  /**
   * Return a date representing the last time we spoke to this peer. If we
   * don't remember ever speaking to the peer, the epoch (beginning of time).
   * Values of last exchange times are not persisted - they're only stored as
   * instance variables, so these times are reliable only within the same Rangzen
   * process.
   *
   *
   * @param peer The peer about which we are inquiring.
   * @return The Date at which the last known successful exchange with the peer
   * occurred, or the epoch if none is known.
   */
  private Date getLastExchangeTime(Peer peer) {
    BluetoothDevice device = peer.getNetwork().getBluetoothDevice();
    if (device == null) {
      Log.e(TAG, "Getting last exchange time of non-bluetooth peer! Can't do it!");
      return null;
    } else {
      Date when = exchangeTimes.get(device.getAddress());
      if (when == null) {
        return new Date(0);
      } else {
        return when;
      }
    }
  }

  /**
   * Check whether we've had an exchange with the given peer within the last 
   * MS_BETWEEN_EXCHANGES ms. Time since exchange isn't persisted, so this might
   * answer false incorrectly if Rangzen has been stopped and started.
   *
   * @return True if we've had an exchange with the peer within the threshold,
   * false otherwise.
   */
  private boolean recentlyExchangedWithPeer(Peer peer) {
    long now = (new Date()).getTime();
    long then = getLastExchangeTime(peer).getTime();
    return (now - then) < MS_BETWEEN_EXCHANGES;
  }

  /**
   * Run tasks, e.g. garbage collection of peers, speaker tasks, etc.
   */
  public void tasks() {
    Log.v(TAG, "Started PeerManager tasks.");

    mBluetoothSpeaker.tasks();
    
    for (Peer peer : mCurrentPeers) {
      if (!recentlyExchangedWithPeer(peer)) {
        Log.v(TAG, "Attempting to have an exchange with " + peer);
        try {
          SerializableLocation startLocation = mLocationStore.getLatestLocation();
          Exchange exchange = mBluetoothSpeaker.connectAndStartExchange(peer);
          if (exchange == null) {
            Log.e(TAG, "Couldn't have exchange with peer " + peer);
          } else {
            Log.i(TAG, "Completed connect and exchange with peer " + peer);
            Log.i(TAG, "The exchange: " + exchange);
            recordExchangeTime(peer, new Date(exchange.end_time));

            SerializableLocation endLocation = mLocationStore.getLatestLocation();
            exchange.start_location = startLocation;
            exchange.end_location = endLocation;

            mExchangeStore.addExchange(exchange);
          }

        } catch (IOException e) {
          Log.e(TAG, String.format("Couldn't have exchange with peer %s: %s", peer, e));
        }
      }
    }
    
    garbageCollectPeers();
    
    Log.v(TAG, "Finished with PeerManager tasks.");
  }
}