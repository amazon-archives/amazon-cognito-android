/**
 * Copyright 2013-2014 Amazon.com, 
 * Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Amazon Software License (the "License"). 
 * You may not use this file except in compliance with the 
 * License. A copy of the License is located at
 * 
 *     http://aws.amazon.com/asl/
 * 
 * or in the "license" file accompanying this file. This file is 
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 * CONDITIONS OF ANY KIND, express or implied. See the License 
 * for the specific language governing permissions and 
 * limitations under the License.
 */

package com.amazonaws.android.cognito;

import android.util.Log;

import com.amazonaws.AmazonClientException;
import com.amazonaws.android.auth.CognitoCredentialsProvider;
import com.amazonaws.android.cognito.exceptions.DataConflictException;
import com.amazonaws.android.cognito.exceptions.DataStorageException;
import com.amazonaws.android.cognito.internal.storage.CognitoSyncStorage;
import com.amazonaws.android.cognito.internal.storage.LocalStorage;
import com.amazonaws.android.cognito.internal.storage.RemoteDataStorage;
import com.amazonaws.android.cognito.internal.storage.RemoteDataStorage.DatasetUpdates;
import com.amazonaws.android.cognito.internal.storage.SQLiteLocalStorage;
import com.amazonaws.android.cognito.internal.util.DatasetUtils;
import com.amazonaws.android.cognito.internal.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link Dataset}. It uses {@link CognitoSyncStorage}
 * as remote storage and {@link SQLiteLocalStorage} as local storage.
 */
class DefaultDataset implements Dataset {

    private static final String TAG = "DefaultDataset";

    /**
     * Max number of retries during synchronize before it gives up.
     */
    private static final int MAX_RETRY = 3;

    /**
     * Non empty dataset name
     */
    private final String datasetName;
    /**
     * Local storage
     */
    private final LocalStorage local;
    /**
     * Remote storage
     */
    private final RemoteDataStorage remote;
    /**
     * Identity id
     */
    private final CognitoCredentialsProvider provider;

    /**
     * Constructs a DefaultDataset object
     * 
     * @param datasetName non empty dataset name
     * @param provider the credentials provider
     * @param local an instance of LocalStorage
     * @param remote an instance of RemoteDataStorage
     */
    public DefaultDataset(String datasetName, CognitoCredentialsProvider provider,
            LocalStorage local, RemoteDataStorage remote) {
        this.datasetName = datasetName;
        this.provider = provider;
        this.local = local;
        this.remote = remote;
    }

    @Override
    public void put(String key, String value) {
        local.putValue(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key), value);
    }

    @Override
    public void remove(String key) {
        local.putValue(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key), null);
    }

    @Override
    public String get(String key) {
        return local.getValue(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key));
    }

    @Override
    public void synchronize(final SyncCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback can't ben null");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "start to synchronize " + datasetName);

                List<String> mergedDatasets = getLocalMergedDatasets();
                if (!mergedDatasets.isEmpty()) {
                    Log.i(TAG, "defected merge datasets " + datasetName);
                    callback.onDatasetsMerged(DefaultDataset.this, mergedDatasets);
                }

                boolean result = synchronizeInternal(callback, MAX_RETRY);
                if (result) {
                    Log.d(TAG, "successfully synchronize " + datasetName);
                } else {
                    Log.d(TAG, "failed to synchronize " + datasetName);
                }
            }
        }).start();
    }

    /**
     * Internal method for synchronization.
     * 
     * @param callback callback during synchronization
     * @param retry number of retries before it's considered failure
     * @return true if synchronize successfully, false otherwise
     */
    boolean synchronizeInternal(final SyncCallback callback, int retry) {
        if (retry < 0) {
            Log.e(TAG, "synchronize failed because it exceeds maximum retry");
            return false;
        }

        long lastSyncCount = local.getLastSyncCount(getIdentityId(), datasetName);

        // if dataset is deleted locally, push it to remote
        if (lastSyncCount == -1) {
            try {
                remote.deleteDataset(datasetName);
                local.purgeDataset(getIdentityId(), datasetName);
                callback.onSuccess(DefaultDataset.this, Collections.<Record> emptyList());
                return true;
            } catch (DataStorageException dse) {
                callback.onFailure(dse);
                return false;
            }
        }

        // get latest modified records from remote
        Log.d(TAG, "get latest modified records since " + lastSyncCount);
        DatasetUpdates datasetUpdates = null;
        try {
            datasetUpdates = remote.listUpdates(datasetName, lastSyncCount);
        } catch (DataStorageException e) {
            callback.onFailure(e);
            return false;
        }

        if (!datasetUpdates.getMergedDatasetNameList().isEmpty()) {
            boolean resume = callback.onDatasetsMerged(DefaultDataset.this,
                    new ArrayList<String>(datasetUpdates.getMergedDatasetNameList()));
            if (resume) {
                return synchronizeInternal(callback, --retry);
            } else {
                callback.onFailure(new DataStorageException("Manual cancel"));
                return false;
            }
        }

        // if the dataset doesn't exist or is deleted, trigger onDelete
        if (lastSyncCount != 0 && !datasetUpdates.isExists()
                || datasetUpdates.isDeleted()) {
            boolean resume = callback
                    .onDatasetDeleted(DefaultDataset.this, datasetUpdates.getDatasetName());
            if (resume) {
                // remove both records and metadata
                local.deleteDataset(getIdentityId(), datasetName);
                local.purgeDataset(getIdentityId(), datasetName);
                callback.onSuccess(DefaultDataset.this, Collections.<Record> emptyList());
                return true;
            } else {
                callback.onFailure(new DataStorageException("Manual cancel"));
                return false;
            }
        }

        List<Record> remoteRecords = datasetUpdates.getRecords();
        if (!remoteRecords.isEmpty()) {
            // if conflict, prompt developer/user with callback
            List<SyncConflict> conflicts = new ArrayList<SyncConflict>();
            for (Record remoteRecord : remoteRecords) {
                Record localRecord = local.getRecord(getIdentityId(),
                        datasetName,
                        remoteRecord.getKey());
                // only when local is changed and its value is different
                if (localRecord != null && localRecord.isModified()
                        && !StringUtils.equals(localRecord.getValue(), remoteRecord.getValue())) {
                    conflicts.add(new SyncConflict(remoteRecord, localRecord));
                }
            }
            if (!conflicts.isEmpty()) {
                Log.i(TAG, String.format("%d records in conflict!", conflicts.size()));
                boolean resume = callback.onConflict(DefaultDataset.this, conflicts);
                return resume ? synchronizeInternal(callback, --retry) : resume;
            }

            // save to local
            Log.i(TAG, String.format("save %d records to local", remoteRecords.size()));
            local.putRecords(getIdentityId(), datasetName, remoteRecords);

            // new last sync count
            Log.i(TAG, String.format("updated sync count %d", datasetUpdates.getSyncCount()));
            local.updateLastSyncCount(getIdentityId(), datasetName,
                    datasetUpdates.getSyncCount());
        }

        // push changes to remote
        List<Record> localChanges = getModifiedRecords();
        if (!localChanges.isEmpty()) {
            Log.i(TAG, String.format("push %d records to remote", localChanges.size()));
            List<Record> result = null;
            try {
                result = remote.putRecords(datasetName, localChanges,
                        datasetUpdates.getSyncSessionToken());
            } catch (DataConflictException dce) {
                Log.i(TAG, "conflicts detected when pushing changes to remote.");
                return synchronizeInternal(callback, --retry);
            } catch (AmazonClientException e) {
                callback.onFailure(new DataStorageException(e));
                return false;
            }

            // update local meta data
            local.putRecords(getIdentityId(), datasetName, result);

            // verify the server sync count is increased exactly by one, aka no
            // other updates were made during this update.
            long newSyncCount = 0;
            for (Record record : result) {
                newSyncCount = newSyncCount < record.getSyncCount()
                        ? record.getSyncCount()
                        : newSyncCount;
            }
            if (newSyncCount == lastSyncCount + 1) {
                Log.i(TAG, String.format("updated sync count %d", newSyncCount));
                local.updateLastSyncCount(getIdentityId(), datasetName,
                        newSyncCount);
            }
        }

        // call back
        callback.onSuccess(DefaultDataset.this, remoteRecords);
        return true;
    }

    @Override
    public List<Record> getAllRecords() {
        return local.getRecords(getIdentityId(), datasetName);
    }

    @Override
    public long getTotalSizeInBytes() {
        long size = 0;
        for (Record record : local.getRecords(getIdentityId(), datasetName)) {
            size += DatasetUtils.computeRecordSize(record);
        }
        return size;
    }

    @Override
    public long getSizeInBytes(String key) {
        return DatasetUtils.computeRecordSize(local.getRecord(getIdentityId(),
                datasetName, DatasetUtils.validateRecordKey(key)));
    }

    @Override
    public boolean isChanged(String key) {
        Record record = local.getRecord(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key));
        return (record != null && record.isModified());
    }

    @Override
    public void delete() {
        local.deleteDataset(getIdentityId(), datasetName);
    }

    @Override
    public DatasetMetadata getDatasetMetadata() {
        return local.getDatasetMetadata(getIdentityId(), datasetName);
    }

    @Override
    public void resolve(List<Record> remoteRecords) {
        local.putRecords(getIdentityId(), datasetName, remoteRecords);
    }

    @Override
    public void putAll(Map<String, String> values) {
        for (String key : values.keySet()) {
            DatasetUtils.validateRecordKey(key);
        }
        local.putAllValues(getIdentityId(), datasetName, values);
    }

    @Override
    public Map<String, String> getAll() {
        Map<String, String> map = new HashMap<String, String>();
        for (Record record : local.getRecords(getIdentityId(), datasetName)) {
            if (!record.isDeleted()) {
                map.put(record.getKey(), record.getValue());
            }
        }
        return map;
    }

    String getIdentityId() {
        return DatasetUtils.getIdentityId(provider);
    }

    /**
     * Gets a list of records that have been modified (marking as deleted
     * included).
     * 
     * @return a list of locally modified records
     */
    List<Record> getModifiedRecords() {
        return local.getModifiedRecords(getIdentityId(), datasetName);
    }

    /**
     * Gets a list of merged datasets that are marked as merged but haven't been
     * processed.
     * 
     * @param datasetName dataset name
     * @return a list dataset names that are marked as merged
     */
    List<String> getLocalMergedDatasets() {
        List<String> mergedDatasets = new ArrayList<String>();
        String prefix = datasetName + ".";
        for (DatasetMetadata dataset : local.getDatasets(getIdentityId())) {
            if (dataset.getDatasetName().startsWith(prefix)) {
                mergedDatasets.add(dataset.getDatasetName());
            }
        }
        return mergedDatasets;
    }
}
