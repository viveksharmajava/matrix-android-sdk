/*
 * Copyright 2015 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.crypto.algorithms.megolm;

import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.crypto.MXCrypto;
import org.matrix.androidsdk.crypto.MXCryptoAlgorithms;
import org.matrix.androidsdk.crypto.MXOlmDevice;
import org.matrix.androidsdk.crypto.algorithms.IMXEncrypting;
import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXOlmSessionResult;
import org.matrix.androidsdk.crypto.data.MXQueuedEncryption;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.util.JsonUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MXMegolmEncryption implements IMXEncrypting {
    private static final String LOG_TAG = "MXMegolmEncryption";

    private MXSession mSession;
    private MXCrypto mCrypto;

    // The id of the room we will be sending to.
    private String mRoomId;

    private String mDeviceId;

    // OutboundSessionInfo. Null if we haven't yet started setting one up. Note
    // that even if this is non-null, it may not be ready for use (in which
    // case outboundSession.shareOperation will be non-null.)
    private MXOutboundSessionInfo mOutboundSession;

    // true when there is an HTTP operation in progress
    private boolean mShareOperationIsProgress;

    private final ArrayList<MXQueuedEncryption> mPendingEncryptions = new ArrayList<>();

    // Session rotation periods
    private int mSessionRotationPeriodMsgs;
    private int mSessionRotationPeriodMs;

    @Override
    public void initWithMatrixSession(MXSession matrixSession, String roomId) {
        mSession = matrixSession;
        mCrypto = matrixSession.getCrypto();

        mRoomId = roomId;
        mDeviceId = matrixSession.getCredentials().deviceId;

        // Default rotation periods
        // TODO: Make it configurable via parameters
        mSessionRotationPeriodMsgs = 100;
        mSessionRotationPeriodMs = 7 * 24 * 3600 * 1000;
    }

    /**
     * @return a snapshot of the pending encryptions
     */
    private List<MXQueuedEncryption> getPendingEncryptions() {
        ArrayList<MXQueuedEncryption> list = new ArrayList<>();

        synchronized (mPendingEncryptions) {
            list.addAll(mPendingEncryptions);
        }

        return list;
    }

    @Override
    public void encryptEventContent(JsonElement eventContent, String eventType, Room room, ApiCallback<JsonElement> callback) {
        // Queue the encryption request
        // It will be processed when everything is set up
        MXQueuedEncryption queuedEncryption = new MXQueuedEncryption();

        queuedEncryption.mEventContent = eventContent;
        queuedEncryption.mEventType = eventType;
        queuedEncryption.mApiCallback = callback;

        synchronized (mPendingEncryptions) {
            mPendingEncryptions.add(queuedEncryption);
        }

        final long t0 = System.currentTimeMillis();
        Log.d(LOG_TAG, "## encryptEventContent () starts");

        ensureOutboundSessionInRoom(room, new ApiCallback<MXOutboundSessionInfo>() {
            @Override
            public void onSuccess(MXOutboundSessionInfo session) {
                Log.d(LOG_TAG, "## encryptEventContent () processPendingEncryptions after " + (System.currentTimeMillis() - t0) + "ms");
                processPendingEncryptions(session);
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG, "## encryptEventContent() : onNetworkError " + e.getMessage());
                List<MXQueuedEncryption> queuedEncryptions = getPendingEncryptions();

                for (MXQueuedEncryption queuedEncryption : queuedEncryptions) {
                    queuedEncryption.mApiCallback.onNetworkError(e);
                }

                synchronized (mPendingEncryptions) {
                    mPendingEncryptions.removeAll(queuedEncryptions);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG, "## encryptEventContent() : onMatrixError " + e.getMessage());

                List<MXQueuedEncryption> queuedEncryptions = getPendingEncryptions();

                for (MXQueuedEncryption queuedEncryption : queuedEncryptions) {
                    queuedEncryption.mApiCallback.onMatrixError(e);
                }

                synchronized (mPendingEncryptions) {
                    mPendingEncryptions.removeAll(queuedEncryptions);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG, "## onUnexpectedError() : onMatrixError " + e.getMessage());

                List<MXQueuedEncryption> queuedEncryptions = getPendingEncryptions();

                for (MXQueuedEncryption queuedEncryption : queuedEncryptions) {
                    queuedEncryption.mApiCallback.onUnexpectedError(e);
                }

                synchronized (mPendingEncryptions) {
                    mPendingEncryptions.removeAll(queuedEncryptions);
                }
            }
        });
    }

    @Override
    public void onRoomMembership(Event event, RoomMember member, String oldMembership) {
        String newMembership = member.membership;

        if (TextUtils.equals(newMembership, RoomMember.MEMBERSHIP_JOIN) || TextUtils.equals(oldMembership, RoomMember.MEMBERSHIP_INVITE)) {
            return;
        }

        // Otherwise we assume the user is leaving, and start a new outbound session.
        Log.d(LOG_TAG, "## onRoomMembership() :  Discarding outbound megolm session due to change in membership of " + member.getUserId () +  " "  + oldMembership + " -> " + newMembership);

        // This ensures that we will start a new session on the next message.
        mOutboundSession = null;
    }

    @Override
    public void onDeviceVerification(MXDeviceInfo device, int oldVerified) {
        if (device.mVerified == MXDeviceInfo.DEVICE_VERIFICATION_BLOCKED) {
            Log.d(LOG_TAG, "## onDeviceVerification() : Discarding outbound megolm session in " + mRoomId + "due to the blacklisting of " + device);
            mOutboundSession = null;
        }

        // In other cases, the key will be shared to this device on the next
        // message thanks to [self ensureOutboundSessionInRoom]
    }

    /**
     * Prepare a new session.
     * @return the session description
     */
    private MXOutboundSessionInfo prepareNewSessionInRoom() {
        MXOlmDevice olmDevice = mCrypto.getOlmDevice();
        final String sessionId = olmDevice.createOutboundGroupSession();

        HashMap<String, String> keysClaimedMap = new HashMap<>();
        keysClaimedMap.put("ed25519", olmDevice.getDeviceEd25519Key());

        olmDevice.addInboundGroupSession(sessionId, olmDevice.getSessionKey(sessionId), mRoomId, olmDevice.getDeviceCurve25519Key(), keysClaimedMap);

       return new MXOutboundSessionInfo(sessionId);
    }

    /**
     * Ensure the outbound session
     * @param room the room
     * @param callback the asynchronous callback.
     */
    private void ensureOutboundSessionInRoom(Room room, final ApiCallback<MXOutboundSessionInfo> callback) {
        MXOutboundSessionInfo session = mOutboundSession;

        // Need to make a brand new session?
        if ((null == session) || session.needsRotation(mSessionRotationPeriodMsgs, mSessionRotationPeriodMs)) {
            mOutboundSession = session = prepareNewSessionInRoom();
        }

        if (mShareOperationIsProgress) {
            Log.d(LOG_TAG, "## ensureOutboundSessionInRoom() : already in progress");
            // Key share already in progress
            return;
        }

        final MXOutboundSessionInfo fSession = session;

        final long t0 = System.currentTimeMillis();
        Log.d(LOG_TAG, "## ensureOutboundSessionInRoom() : starts");

        // No share in progress: check if we need to share with any devices
        getDevicesInRoom(room, new ApiCallback<MXUsersDevicesMap<MXDeviceInfo>>() {
            @Override
            public void onSuccess(MXUsersDevicesMap<MXDeviceInfo> devicesInRoom) {
                Log.d(LOG_TAG, "## ensureOutboundSessionInRoom() : getDevicesInRoom() succeeds after " + (System.currentTimeMillis() - t0) + " ms");

                HashMap<String, /* userId */ArrayList<MXDeviceInfo>> shareMap = new HashMap<>();

                List<String> userIds = devicesInRoom.getUserIds();

                for(String userId : userIds) {
                    List<String> deviceIds = devicesInRoom.getUserDeviceIds(userId);

                    for (String deviceId : deviceIds) {
                        MXDeviceInfo deviceInfo = devicesInRoom.getObject(deviceId, userId);

                        if (deviceInfo.mVerified == MXDeviceInfo.DEVICE_VERIFICATION_BLOCKED) {
                            continue;
                        }

                        if (TextUtils.equals(deviceInfo.identityKey(), mCrypto.getOlmDevice().getDeviceCurve25519Key())) {
                            // Don't bother sending to ourself
                            continue;
                        }

                        if (null == fSession.mSharedWithDevices.getObject(deviceId, userId)) {
                            if (!shareMap.containsKey(userId)) {
                                shareMap.put(userId, new ArrayList<MXDeviceInfo>());
                            }

                            shareMap.get(userId).add(deviceInfo);
                        }
                    }
                }

                shareKey(fSession, shareMap, new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void anything) {
                        mShareOperationIsProgress = false;
                        callback.onSuccess(fSession);
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        Log.e(LOG_TAG, "## ensureOutboundSessionInRoom() : shareKey onNetworkError " + e.getMessage());

                        if (null != callback) {
                            callback.onNetworkError(e);
                        }
                        mShareOperationIsProgress = false;
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        Log.e(LOG_TAG, "## ensureOutboundSessionInRoom() : shareKey onMatrixError " + e.getMessage());

                        if (null != callback) {
                            callback.onMatrixError(e);
                        }
                        mShareOperationIsProgress = false;
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        Log.e(LOG_TAG, "## ensureOutboundSessionInRoom() : shareKey onUnexpectedError " + e.getMessage());

                        if (null != callback) {
                            callback.onUnexpectedError(e);
                        }
                        mShareOperationIsProgress = false;
                    }
                });
            }

            @Override
            public void onNetworkError(Exception e) {
                if (null != callback) {
                    callback.onNetworkError(e);
                }
                mShareOperationIsProgress = false;
            }

            @Override
            public void onMatrixError(MatrixError e) {
                if (null != callback) {
                    callback.onMatrixError(e);
                }
                mShareOperationIsProgress = false;
            }

            @Override
            public void onUnexpectedError(Exception e) {
                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
                mShareOperationIsProgress = false;
            }
        });
    }

    /**
     * Share the device key to a list of users
     * @param session the session info
     * @param devicesByUsers the devices map
     * @param callback the asynchronous callback
     */
    private void shareKey(final MXOutboundSessionInfo session, final HashMap<String, ArrayList<MXDeviceInfo>> devicesByUsers, final ApiCallback<Void> callback) {
        // nothing to send, the task is done
        if (0 == devicesByUsers.size()) {
            Log.d(LOG_TAG, "## shareKey() : nothing more to do");

            if (null != callback) {
                callback.onSuccess(null);
            }

            return;
        }

        // reduce the map size to avoid request timeout when there are too devices (Users size  * devices per user)
        HashMap<String, ArrayList<MXDeviceInfo>> subMap = new HashMap<>();

        final ArrayList<String> userIds = new ArrayList<>();
        int devicesCount = 0;

        for(String userId : devicesByUsers.keySet()) {
            ArrayList<MXDeviceInfo> devicesList = devicesByUsers.get(userId);

            userIds.add(userId);
            subMap.put(userId, devicesList);

            devicesCount += devicesList.size();

            if (devicesCount > 100) {
                break;
            }
        }

        Log.d(LOG_TAG, "## shareKey() ; userId " + userIds);
        shareUserDevicesKey(session, subMap, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                for(String userId : userIds) {
                    devicesByUsers.remove(userId);
                }
                shareKey(session, devicesByUsers, callback);
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.d(LOG_TAG, "## shareKey() ; userIds " + userIds +  " failed " + e.getMessage());
                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.d(LOG_TAG, "## shareKey() ; userIds " + userIds +  " failed " + e.getMessage());
                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.d(LOG_TAG, "## shareKey() ; userIds " + userIds +  " failed " + e.getMessage());
                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    /**
     * Share the device keys of a an user
     * @param session the session info
     * @param devicesByUser the devices map
     * @param callback the asynchronous callback
     */
    private void shareUserDevicesKey(final MXOutboundSessionInfo session, final HashMap<String, ArrayList<MXDeviceInfo>> devicesByUser, final ApiCallback<Void> callback) {
        final String sessionKey = mCrypto.getOlmDevice().getSessionKey(session.mSessionId);
        final int chainIndex = mCrypto.getOlmDevice().getMessageIndex(session.mSessionId);

        HashMap<String, Object> submap = new HashMap<>();
        submap.put("algorithm", MXCryptoAlgorithms.MXCRYPTO_ALGORITHM_MEGOLM);
        submap.put("room_id", mRoomId);
        submap.put("session_id", session.mSessionId);
        submap.put("session_key", sessionKey);
        submap.put("chain_index", chainIndex);

        final HashMap<String, Object> payload = new HashMap<>();
        payload.put("type", Event.EVENT_TYPE_ROOM_KEY);
        payload.put("content", submap);

        final long t0 = System.currentTimeMillis();
        Log.d(LOG_TAG, "## shareUserDevicesKey() : starts");

        mCrypto.ensureOlmSessionsForDevices(devicesByUser, new ApiCallback<MXUsersDevicesMap<MXOlmSessionResult>>() {
            @Override
            public void onSuccess(final MXUsersDevicesMap<MXOlmSessionResult> results) {
                Log.d(LOG_TAG, "## shareUserDevicesKey() : ensureOlmSessionsForDevices succeeds after " + (System.currentTimeMillis() - t0) + " ms");

                new AsyncTask<Void, Void, Boolean>() {
                    MXUsersDevicesMap<Map<String, Object>> mContentMap;

                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        mContentMap = new MXUsersDevicesMap<>();

                        boolean haveTargets = false;
                        List<String> userIds = results.getUserIds();

                        for (String userId : userIds) {
                            ArrayList<MXDeviceInfo> devicesToShareWith = devicesByUser.get(userId);

                            for (MXDeviceInfo deviceInfo : devicesToShareWith) {
                                String deviceID = deviceInfo.deviceId;

                                MXOlmSessionResult sessionResult = results.getObject(deviceID, userId);

                                if ((null == sessionResult) || (null == sessionResult.mSessionId)) {
                                    // no session with this device, probably because there
                                    // were no one-time keys.
                                    //
                                    // we could send them a to_device message anyway, as a
                                    // signal that they have missed out on the key sharing
                                    // message because of the lack of keys, but there's not
                                    // much point in that really; it will mostly serve to clog
                                    // up to_device inboxes.
                                    //
                                    // ensureOlmSessionsForUsers has already done the logging,
                                    // so just skip it.
                                    continue;
                                }

                                Log.d(LOG_TAG, "## shareUserDevicesKey() : Sharing keys with device " + userId + ":" + deviceID);
                                //noinspection ArraysAsListWithZeroOrOneArgument,ArraysAsListWithZeroOrOneArgument
                                mContentMap.setObject(mCrypto.encryptMessage(payload, Arrays.asList(sessionResult.mDevice)), userId, deviceID);
                                haveTargets = true;
                            }
                        }

                        if (haveTargets && !mCrypto.hasBeenReleased()) {
                            mCrypto.mCryptoStore.flushSessions();
                        }

                        return haveTargets;
                    }

                    @Override
                    protected void onPostExecute(Boolean haveTargets) {
                        if (haveTargets && !mCrypto.hasBeenReleased()) {
                            final long t0 = System.currentTimeMillis();
                            Log.d(LOG_TAG, "## shareUserDevicesKey() : has target");

                            mSession.getCryptoRestClient().sendToDevice(Event.EVENT_TYPE_MESSAGE_ENCRYPTED, mContentMap, new ApiCallback<Void>() {
                                @Override
                                public void onSuccess(Void info) {
                                    Log.d(LOG_TAG, "## shareUserDevicesKey() : sendToDevice succeeds after " + (System.currentTimeMillis() - t0) + " ms");

                                    // Add the devices we have shared with to session.sharedWithDevices.
                                    // we deliberately iterate over devicesByUser (ie, the devices we
                                    // attempted to share with) rather than the contentMap (those we did
                                    // share with), because we don't want to try to claim a one-time-key
                                    // for dead devices on every message.
                                    for (String userId : devicesByUser.keySet()) {
                                        List<MXDeviceInfo> devicesToShareWith = devicesByUser.get(userId);

                                        for(MXDeviceInfo deviceInfo : devicesToShareWith) {
                                            session.mSharedWithDevices.setObject(chainIndex, userId, deviceInfo.deviceId);
                                        }
                                    }

                                    if (null != callback) {
                                        callback.onSuccess(null);
                                    }
                                }

                                @Override
                                public void onNetworkError(Exception e) {
                                    Log.d(LOG_TAG, "## shareUserDevicesKey() : sendToDevice onNetworkError " + e.getMessage());

                                    if (null != callback) {
                                        callback.onNetworkError(e);
                                    }
                                }

                                @Override
                                public void onMatrixError(MatrixError e) {
                                    Log.d(LOG_TAG, "## shareUserDevicesKey() : sendToDevice onMatrixError " + e.getMessage());

                                    if (null != callback) {
                                        callback.onMatrixError(e);
                                    }
                                }

                                @Override
                                public void onUnexpectedError(Exception e) {
                                    Log.d(LOG_TAG, "## shareUserDevicesKey() : sendToDevice onUnexpectedError " + e.getMessage());

                                    if (null != callback) {
                                        callback.onUnexpectedError(e);
                                    }
                                }
                            });
                        } else {
                            Log.d(LOG_TAG, "## shareUserDevicesKey() : no need to sharekey");

                            if (null != callback) {
                                callback.onSuccess(null);
                            }
                        }
                    }
                }.execute();
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.d(LOG_TAG, "## shareUserDevicesKey() : ensureOlmSessionsForDevices failed " + e.getMessage());

                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.d(LOG_TAG, "## shareUserDevicesKey() : ensureOlmSessionsForDevices failed " + e.getMessage());

                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.d(LOG_TAG, "## shareUserDevicesKey() : ensureOlmSessionsForDevices failed " + e.getMessage());

                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    /**
     * process the pending encryptions
     */
    private void processPendingEncryptions(MXOutboundSessionInfo session) {
        if (null != session) {

            List<MXQueuedEncryption> queuedEncryptions = getPendingEncryptions();

            // Everything is in place, encrypt all pending events
            for (MXQueuedEncryption queuedEncryption : queuedEncryptions) {
                HashMap<String, Object> payloadJson = new HashMap<>();

                payloadJson.put("room_id", mRoomId);
                payloadJson.put("type", queuedEncryption.mEventType);
                payloadJson.put("content", queuedEncryption.mEventContent);

                String payloadString = JsonUtils.convertToUTF8(JsonUtils.canonicalize(JsonUtils.getGson(false).toJsonTree(payloadJson)).toString());
                String ciphertext = mCrypto.getOlmDevice().encryptGroupMessage(session.mSessionId, payloadString);

                HashMap<String, Object> map = new HashMap<>();
                map.put("algorithm", MXCryptoAlgorithms.MXCRYPTO_ALGORITHM_MEGOLM);
                map.put("sender_key", mCrypto.getOlmDevice().getDeviceCurve25519Key());
                map.put("ciphertext", ciphertext);
                map.put("session_id", session.mSessionId);

                // Include our device ID so that recipients can send us a
                // m.new_device message if they don't have our session key.
                map.put("device_id", mDeviceId);

                queuedEncryption.mApiCallback.onSuccess(JsonUtils.getGson(false).toJsonTree(map));

                session.mUseCount++;
            }

            synchronized (mPendingEncryptions) {
                mPendingEncryptions.removeAll(queuedEncryptions);
            }
        }
    }

    /**
     * Get the list of devices for all users in the room.
     * @param room the room
     * @param callback the callback
     */
    private void getDevicesInRoom(Room room, ApiCallback<MXUsersDevicesMap<MXDeviceInfo>> callback) {
        // XXX what about rooms where invitees can see the content?
        ArrayList<String> joinedMemberIds = new ArrayList<>();
        Collection<RoomMember> joinedMembers = room.getJoinedMembers();

        for(RoomMember member : joinedMembers) {
            joinedMemberIds.add(member.getUserId());
        }

        mCrypto.downloadKeys(joinedMemberIds, false, callback);
    }
}
