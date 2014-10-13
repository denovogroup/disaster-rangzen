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

import android.content.Context;

import java.io.IOException;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Storage for friends that uses StorageBase underneath. 
 */
public class FriendStore {
  /** A handle for the underlying store */
  private StorageBase store;
  
  /** The internal key used in the underlying store for Rangzen location data. */
  private static final String FRIENDS_STORE_KEY = "RangzenFriend-";

  /**
   * Creates a Rangzen friend store, with a consistent application of encryption of that stored
   * data, as specified.
   *
   * @param context A context in which to do storage.
   * @param encryptionMode The encryption mode to use for all calls using this instance.
   */
  public FriendStore(Context context, int encryptionMode) throws IllegalArgumentException {
    store = new StorageBase(context, encryptionMode);
  }

  /**
   * Adds the given friend.
   *
   * @param msg The friend to add.
   *
   * @return Returns true if the friend was stored, false if the friend was already
   * stored.
   */
  public boolean addFriend(String friend) {
    Set<String> friends = store.getSet(FRIENDS_STORE_KEY);
    if (friends == null) {
      friends = new HashSet<String>();
    }
    if (friends.contains(friend)) {
      // Friend already stored.
      return false;
    }
    friends.add(friend);
    store.putSet(FRIENDS_STORE_KEY, friends);
    return true;
  }

  /**
   * Delete the given friend ID from the friend store, if it exists.
   *
   * @param friend The friend ID to delete.
   *
   * @return True if the friend existed and was deleted, false otherwise.
   */
  public boolean deleteFriend(String friend) {
    Set<String> friends = store.getSet(FRIENDS_STORE_KEY);
    if (friends == null) {
      // No friends known, so deleting a friend should always fail.
      return false;
    }
    // Friend known, so delete it.
    if (friends.contains(friend)) {
      friends.remove(friend);
      store.putSet(FRIENDS_STORE_KEY, friends);
      return true;
    }
    // Friends not empty but given friend to delete not known.
    return false;
  }

  /**
   * Get a list of all friends stored on this device.
   *
   * @return A set of friends ids.
   */
  public Set<String> getAllFriends() {
    Set<String> friends = store.getSet(FRIENDS_STORE_KEY);
    if (friends == null) {
      return new HashSet<String>();
    }
    return friends;
  }
}
