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

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.android.cognito.DatasetMetadata;
import com.amazonaws.android.cognito.Record;
import com.amazonaws.android.cognito.exceptions.DataConflictException;
import com.amazonaws.android.cognito.exceptions.DataLimitExceededException;
import com.amazonaws.android.cognito.exceptions.DataStorageException;
import com.amazonaws.android.cognito.exceptions.DatasetNotFoundException;
import com.amazonaws.android.cognito.exceptions.NetworkException;
import com.amazonaws.auth.CognitoCredentialsProvider;
import com.amazonaws.services.cognitosync.AmazonCognitoSyncServiceClient;
import com.amazonaws.services.cognitosync.model.DeleteDatasetRequest;
import com.amazonaws.services.cognitosync.model.DescribeDatasetRequest;
import com.amazonaws.services.cognitosync.model.DescribeDatasetResult;
import com.amazonaws.services.cognitosync.model.LimitExceededException;
import com.amazonaws.services.cognitosync.model.ListDatasetsRequest;
import com.amazonaws.services.cognitosync.model.ListDatasetsResult;
import com.amazonaws.services.cognitosync.model.ListRecordsRequest;
import com.amazonaws.services.cognitosync.model.ListRecordsResult;
import com.amazonaws.services.cognitosync.model.Operation;
import com.amazonaws.services.cognitosync.model.RecordPatch;
import com.amazonaws.services.cognitosync.model.ResourceConflictException;
import com.amazonaws.services.cognitosync.model.ResourceNotFoundException;
import com.amazonaws.services.cognitosync.model.UpdateRecordsRequest;
import com.amazonaws.services.cognitosync.model.UpdateRecordsResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Cognito remote storage powered by AWS Cognito Sync service
 */
public class CognitoSyncStorage implements RemoteDataStorage {

    private static final String USER_AGENT = "CognitoSyncClient";

    /**
     * Identity pool id
     */
    private final String identityPoolId;
    private final AmazonCognitoSyncServiceClient client;
    private final CognitoCredentialsProvider provider;

    public CognitoSyncStorage(String identityPoolId, CognitoCredentialsProvider provider) {
        this.identityPoolId = identityPoolId;
        this.provider = provider;
        ClientConfiguration config = new ClientConfiguration();
        config.setUserAgent(USER_AGENT);
        client = new AmazonCognitoSyncServiceClient(provider, config);
    }

    /*
     * (non-Javadoc)
     * @see com.amazonaws.cognitov2.RemoteStorage#listDatasets()
     */
    @Override
    public List<DatasetMetadata> getDatasets() {
        List<DatasetMetadata> datasets = new ArrayList<DatasetMetadata>();

        String nextToken = null;
        do {
            ListDatasetsRequest request = new ListDatasetsRequest();
            request.setIdentityPoolId(identityPoolId);
            request.setIdentityId(getIdentityId());
            // a large enough number to reduce # of requests
            request.setMaxResults("64");
            request.setNextToken(nextToken);

            ListDatasetsResult result = null;
            try {
                result = client.listDatasets(request);
            } catch (AmazonClientException ace) {
                throw handleException(ace, "Failed to list dataset metadata");
            }
            for (com.amazonaws.services.cognitosync.model.Dataset dataset : result.getDatasets()) {
                datasets.add(modelToDatasetMetadata(dataset));
            }

            nextToken = result.getNextToken();
        } while (nextToken != null);

        return datasets;
    }

    @Override
    public DatasetUpdates listUpdates(String datasetName, long lastSyncCount) {
        DatasetUpdatesImpl.Builder builder = new DatasetUpdatesImpl.Builder(datasetName);

        String nextToken = null;
        do {
            ListRecordsRequest request = new ListRecordsRequest();
            request.setIdentityPoolId(identityPoolId);
            request.setIdentityId(getIdentityId());
            request.setDatasetName(datasetName);
            request.setLastSyncCount(String.valueOf(lastSyncCount));
            // mark it large enough to reduce # of requests
            request.setMaxResults("1024");
            request.setNextToken(nextToken);

            ListRecordsResult result = null;
            try {
                result = client.listRecords(request);
            } catch (AmazonClientException ace) {
                throw handleException(ace, "Failed to list records in dataset: " + datasetName);
            }
            for (com.amazonaws.services.cognitosync.model.Record remoteRecord : result.getRecords()) {
                builder.addRecord(modelToRecord(remoteRecord));
            }
            builder.syncSessionToken(result.getSyncSessionToken())
                    .syncCount(result.getDatasetSyncCount())
                    .exists(result.isDatasetExists())
                    .deleted(result.isDatasetDeletedAfterRequestedSyncCount())
                    .mergedDatasetNameList(result.getMergedDatasetNames());

            // update last evaluated key
            nextToken = result.getNextToken();
        } while (nextToken != null);

        return builder.build();
    }

    /*
     * (non-Javadoc)
     * @see com.amazonaws.cognitov2.RemoteStorage#saveRecords(java.lang.String,
     * java.util.List)
     */
    @Override
    public List<Record> putRecords(String datasetName, List<Record> records, String syncSessionToken) {
        UpdateRecordsRequest request = new UpdateRecordsRequest();
        request.setDatasetName(datasetName);
        request.setIdentityPoolId(identityPoolId);
        request.setIdentityId(getIdentityId());
        request.setSyncSessionToken(syncSessionToken);

        // create patches
        List<RecordPatch> patches = new ArrayList<RecordPatch>();
        for (Record record : records) {
            patches.add(recordToPatch(record));
        }
        request.setRecordPatches(patches);

        List<Record> updatedRecords = new ArrayList<Record>();
        try {
            UpdateRecordsResult result = client.updateRecords(request);
            for (com.amazonaws.services.cognitosync.model.Record remoteRecord : result.getRecords()) {
                updatedRecords.add(modelToRecord(remoteRecord));
            }
        } catch (AmazonClientException ace) {
            throw handleException(ace, "Failed to update records in dataset: " + datasetName);
        }

        return updatedRecords;
    }

    @Override
    public void deleteDataset(String datasetName) {
        DeleteDatasetRequest request = new DeleteDatasetRequest();
        request.setIdentityPoolId(identityPoolId);
        request.setIdentityId(getIdentityId());
        request.setDatasetName(datasetName);

        try {
            client.deleteDataset(request);
        } catch (AmazonClientException ace) {
            throw handleException(ace, "Failed to delete dataset: " + datasetName);
        }
    }

    /**
     * Converts a record to a RecordPatch operation.
     * 
     * @param record
     * @return
     */
    RecordPatch recordToPatch(Record record) {
        RecordPatch patch = new RecordPatch();
        patch.setKey(record.getKey());
        patch.setValue(record.getValue());
        patch.setSyncCount(record.getSyncCount());
        patch.setOp(record.getValue() == null ? Operation.Remove : Operation.Replace);
        return patch;
    }

    /**
     * Converts a Cognito sync service Record object to generic Record object.
     * 
     * @param model a service model object
     * @return Record object
     */
    Record modelToRecord(com.amazonaws.services.cognitosync.model.Record model) {
        return new Record.Builder(model.getKey())
                .value(model.getValue())
                .syncCount(model.getSyncCount() == null ? 0 : model.getSyncCount())
                .lastModifiedBy(model.getLastModifiedBy())
                .lastModifiedDate(model.getLastModifiedDate() == null
                        ? new Date(0)
                        : model.getLastModifiedDate())
                .deviceLastModifiedDate(model.getDeviceLastModifiedDate() == null
                        ? new Date(0)
                        : model.getDeviceLastModifiedDate())
                .build();
    }

    @Override
    public DatasetMetadata getDatasetMetadata(String datasetName) throws DataStorageException {
        DescribeDatasetRequest request = new DescribeDatasetRequest();
        request.setIdentityPoolId(identityPoolId);
        request.setIdentityId(getIdentityId());
        request.setDatasetName(datasetName);

        DatasetMetadata dataset = null;
        try {
            DescribeDatasetResult result = client.describeDataset(request);
            dataset = modelToDatasetMetadata(result.getDataset());
        } catch (AmazonClientException ace) {
            throw new DataStorageException("Failed to get metadata of dataset: " + datasetName, ace);
        }
        return dataset;
    }

    /**
     * Translate AmazonClientException to DataStorageException.
     * 
     * @param ace an AmazonClientException
     * @param message extra message to include
     * @return an DataStorageException
     */
    DataStorageException handleException(AmazonClientException ace, String message) {
        if (ace instanceof ResourceNotFoundException) {
            return new DatasetNotFoundException(message);
        } else if (ace instanceof ResourceConflictException) {
            return new DataConflictException(message);
        } else if (ace instanceof LimitExceededException) {
            return new DataLimitExceededException(message);
        } else if (isNetworkException(ace)) {
            return new NetworkException(message);
        } else {
            return new DataStorageException(message, ace);
        }
    }

    String getIdentityId() {
        // identity id may change after provider.refresh()
        provider.refresh();
        return provider.getIdentityId();
    }

    /**
     * Test whether an AmazonClientException is caused by network problem.
     * 
     * @param ace an AmazonClientException
     * @return true if the exception is caused by network problem, false
     *         otherwise.
     */
    boolean isNetworkException(AmazonClientException ace) {
        return ace.getCause() instanceof IOException;
    }

    private DatasetMetadata modelToDatasetMetadata(
            com.amazonaws.services.cognitosync.model.Dataset model) {
        return new DatasetMetadata.Builder(model.getDatasetName())
                .creationDate(model.getCreationDate())
                .lastModifiedDate(model.getLastModifiedDate())
                .lastModifiedBy(model.getLastModifiedBy())
                .storageSizeBytes(model.getDataStorage())
                .recordCount(model.getNumRecords())
                .build();
    }

    static class DatasetUpdatesImpl implements DatasetUpdates {
        private final String datasetName;
        private final List<Record> records;
        private final long syncCount;
        private final String syncSessionToken;
        private final boolean exists;
        private final boolean deleted;
        private final List<String> mergedDatasetNameList;

        @Override
        public String getDatasetName() {
            return datasetName;
        }

        @Override
        public List<Record> getRecords() {
            return records;
        }

        @Override
        public long getSyncCount() {
            return syncCount;
        }

        @Override
        public String getSyncSessionToken() {
            return syncSessionToken;
        }

        @Override
        public boolean isExists() {
            return exists;
        }

        @Override
        public boolean isDeleted() {
            return deleted;
        }

        @Override
        public List<String> getMergedDatasetNameList() {
            return mergedDatasetNameList;
        }

        private DatasetUpdatesImpl(Builder builder) {
            this.datasetName = builder.datasetName;
            this.records = builder.records;
            this.syncCount = builder.syncCount;
            this.syncSessionToken = builder.syncSessionToken;
            this.exists = builder.exists;
            this.deleted = builder.deleted;
            this.mergedDatasetNameList = builder.mergedDatasetNameList;
        }

        static class Builder {
            private final String datasetName;
            private final List<Record> records = new ArrayList<Record>();
            private long syncCount = 0;
            private String syncSessionToken;
            private boolean exists = true;
            private boolean deleted = false;
            private final List<String> mergedDatasetNameList = new ArrayList<String>();

            Builder(String datasetName) {
                this.datasetName = datasetName;
            }

            Builder syncSessionToken(String syncSessionToken) {
                this.syncSessionToken = syncSessionToken;
                return this;
            }

            Builder syncCount(long syncCount) {
                this.syncCount = syncCount;
                return this;
            }

            Builder exists(boolean exists) {
                this.exists = exists;
                return this;
            }

            Builder deleted(boolean deleted) {
                this.deleted = deleted;
                return this;
            }

            Builder addRecord(Record record) {
                records.add(record);
                return this;
            }

            Builder mergedDatasetNameList(List<String> mergedDatasetNameList) {
                this.mergedDatasetNameList.addAll(mergedDatasetNameList);
                return this;
            }

            DatasetUpdates build() {
                return new DatasetUpdatesImpl(this);
            }
        }
    }
}