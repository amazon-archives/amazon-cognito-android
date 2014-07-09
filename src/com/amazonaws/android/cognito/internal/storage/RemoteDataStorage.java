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

package com.amazonaws.android.cognito.internal.storage;

import com.amazonaws.android.cognito.Dataset;
import com.amazonaws.android.cognito.DatasetMetadata;
import com.amazonaws.android.cognito.Record;
import com.amazonaws.android.cognito.exceptions.DataConflictException;
import com.amazonaws.android.cognito.exceptions.DataStorageException;
import com.amazonaws.android.cognito.exceptions.DatasetNotFoundException;

import java.util.List;

/**
 * The interface of remote storage of {@link Dataset}.
 */
public interface RemoteDataStorage {

    /**
     * Gets a list of {@link DatasetMetadata}s.
     * 
     * @return a list of dataset metadata
     */
    public List<DatasetMetadata> getDatasets() throws DataStorageException;

    /**
     * Retrieves the metadata of a dataset.
     * 
     * @param datasetName dataset name
     * @return the corresponding metadata
     * @throws DataStorageException
     */
    public DatasetMetadata getDatasetMetadata(String datasetName) throws DataStorageException;

    /**
     * Gets a list of records which have been updated since lastSyncCount
     * (inclusive). If the value of a record equals null, then the record is
     * deleted. If you pass 0 as lastSyncCount, the full list of records will be
     * returned.
     * 
     * @param datasetName the dataset name
     * @param lastSyncCount non negative long integer
     * @return A list of records which have been updated since lastSyncCount.
     */
    public DatasetUpdates listUpdates(String datasetName, long lastSyncCount)
            throws DataStorageException;

    /**
     * Post updates to remote storage. Each record has a sync count. If the sync
     * count doesn't match what's on the remote storage, i.e. the record is
     * modified by a different device, this operation throws ConflictException.
     * Otherwise it returns a list of records that are updated successfully.
     * 
     * @param datasetName the dataset name
     * @param records list of records to be updated remotely
     * @param syncSessionToken the sync session token retrieved from
     *            {@link #listUpdates(String, long)}
     */
    public List<Record> putRecords(String datasetName, List<Record> records, String syncSessionToken)
            throws DatasetNotFoundException, DataConflictException;

    /**
     * Deletes a dataset.
     * 
     * @param datasetName
     */
    public void deleteDataset(String datasetName) throws DatasetNotFoundException;

    public interface DatasetUpdates {
        public String getDatasetName();

        public List<Record> getRecords();

        /**
         * Gets the sync session token
         * 
         * @return
         */
        public String getSyncSessionToken();

        public long getSyncCount();

        public boolean isExists();

        public boolean isDeleted();

        public List<String> getMergedDatasetNameList();

    }

}
