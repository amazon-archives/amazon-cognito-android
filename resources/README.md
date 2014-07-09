# Amazon Cognito for Android

Amazon Cognito allows mobile developers to securely store user-specific app data in the AWS cloud. App users can access their data across any of their iOS and Android devices. Developers use the API to write data records expressed as simple key value pairs, and read a data record by key. Amazon Cognito writes user data to a local data store, so it functions even when the device is offline. Amazon Cognito monitors the device's connectivity and sync with the cloud when the device regains connectivity. In the event of data changes across multiple devices, Amazon Cognito resolves data sync conflicts using a "last writer wins" algorithm. Developers can also configure a custom conflict resolution algorithm.

Amazon Cognito takes advantage of [web identity federation](http://mobile.awsblog.com/post/Tx3UKF4SV4V0LV3/Announcing-Web-Identity-Federation), allowing app users to authenticate with third-party social identity providers Facebook, Amazon, or Google. Amazon Cognito uses [Amazon DynamoDB](http://aws.amazon.com/dynamodb/) for the remote data store, and [fine-grained access controls](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/FGAC_DDB.html) with [AWS IAM](http://aws.amazon.com/iam/) to authorize data access.

## Prerequisites

Amazon Cognito for Android is dependent on the following components:

* API Level 10 or later
* The [AWS SDK for Android](http://aws.amazon.com/sdkforandroid/) version 1.8.0 or later
* A [DynamoDB](http://console.aws.amazon.com/dynamodb/) table that conforms to the Amazon Cognito schema.
* The library uses [web identity federation](http://mobile.awsblog.com/post/Tx3UKF4SV4V0LV3/Announcing-Web-Identity-Federation) for login, which requires configuring an application with one of the support identity providers
	* [Facebook](http://developers.facebook.com/)
	* [Google](http://plus.google.com/)
	* [Amazon](http://login.amazon.com/)
* An [AWS IAM Role](http://console.aws.amazon.com/iam/) configured with fine-grain access control that links the DynamoDB table to the application with the identity provider.

## Sample App

Our samples repository includes a sample app that shows off various operations supported by `CognitoSharedPreferences` to store values associated with the current user. Please visit [our samples repository](https://github.com/awslabs/aws-sdk-android-samples) for more information.

## DynamoDB table schema

By default, Amazon Cognito expects a DynamoDB table named `CognitoData` to be available with the following constraints:

* Hash key named `UserId` of type String
* Range key named `RecordId` of type String
* Local secondary index on the attribute `LastWritten` (of type Number) named `LastWritten-index`. The index should project across all attributes.

If you are creating the table via the [Amazon DynamoDB console](http://console.aws.amazon.com/dynamodb/), take note of the region in which you created your table. 

## Setup Script

Included with Amazon Cognito is a command line script and [AWS CloudFormation](http://aws.amazon.com/cloudformation/) template that automates the creation of the DynamoDB table and IAM role necessary to use Amazon Cognito.

## Accessing CognitoSharedPreferences

Amazon Cognito leverages web identity federation to handle data authorization and isolation, so when initializing `CognitoSharedPreferences` we must include an implementation of `CognitoCredentialsProvider` which generates its credentials with web identity federation. We encapsulate this provider, and other options in Amazon Cognito, with `CognitoConfig`.

	// create a web identity federation provider
	WebIdentityFederationSessionCredentialsProvider webIdentityProvider = 
		new WebIdentityFederationSessionCredentialsProvider(SESSION_TOKEN, PROVIDER, ROLE_ARN);
	
	// create an instance of a CognitoCredentialsProvider
	MyCognitoCredentialsProvider cognitoProvider = 
    	new MyCognitoCredentialsProvider( webIdentityProvider );
    
    // create a default config
    CognitoConfig cognitoConfig = new CognitoConfig();
    
    // setup CognitoSharedPreferences
    CognitoSharedPreferences.setup( cognitoProvider, context, cognitoConfig );

After calling setup, by default Cognito will initiate a full sync and create our local data store which `CognitoSharedPreferences` will access. 

## Configuring Amazon Cognito

You configure Amazon Cognito via the `CognitoConfig` object. The `CognitoConfig` object has default values for all options of the config that you can override. The most common values you might choose to override are:

* `ddbEndpoint` The DynamoDB endpoint Amazon Cognito uses. Choose the endpoint for the region where you created your table.
* `ddbTableName` The name of the DynamoDB table to use as the remote data store. This table must conform to the table schema mentioned above.
* `clientId` The name that will be populated in the `lastModifiedBy` attribute in the DynamoDB table. By default, Amazon Cognito will populate this with a random GUID.
* `handler` An object that adhers to the `CognitoConflictResolutionHandler` interface for handling conflict resolution during sync. By default, the Amazon Cognito will take the record with the most recent `LastModified`.

Please consult the API documentation for the full set of options available for the `CognitoConfig`.

## Offline Access

Once you have configured Amazon Cognito, a local data store is initialized on the device. This data store receives data record changes, even if the device is offline: in airplane mode, or otherwise lacking reliable Internet connectivity. The local data store is keyed off of the user id provided by the identity provider used to login (Facebook, Google, or Amazon), so for offline mode you have to provide a saved value "hint" to Amazon Cognito. The included samples save this information in the Android's `SharedPreferences`, and provide it to `CognitoConfig`. You can alternatively implement the `CognitoCredentialsProvider` interface and ensure you are supplying a valid `userId` there.

	// The userId selector of a CognitoCredentialsProvider
	public String getUserId() {
		if (wif.getSubjectFromWIF() == null) {
			return SAVED_USERID;
		}
		
		return wif.getSubjectFromWIF();
	}

## Storing Data

`CognitoSharedPreferences` mimics Android's `SharedPreferences` APIs. Storing data becomes a simple matter of calling the appropriate method for our data type with an associated key:

	final CognitoSharedPreferences cognito = CognitoSharedPreferences.getInstance();
        
    // store an integer value
    cognito.putInt( "HighScore", 1000 ); 
        
    // store a string value
    cognito.putString( "DisplayName", "Bob" );
        
    // store a set of values
    Set<String> favorites = new HashSet<String>();
    favorites.add( "Blue" );
    favorites.add( "Green" );
    favorites.add( "Black" );
    cognito.putStringSet( "FavoriteColors", favorites );
		

## Getting Data

Getting data is equally as simple with `CognitoSharedPreferences`; we just need to call the appropiate accessor for the data type:

	// get an integer value
	int highScore = cognito.getInt( "HighScore", 0 );
        
	// get a string value
	String displayName = cognito.getString( "DisplayName", null ); 
        
	// get a set of values
	Set<String> favorites = cognito.getStringSet( "FavoriteColors", null ); 
	
	
You can also get all keys stored in `CognitoSharedPreferences` as a single `Set<String>`:

	Set<String> allKeys = cognito.getAllKeys();
        
	// loop through data
	for (String *key in allKeys) {
		// do something
		...
	} 

## Removing Data

If you want to remove data from `CognitoSharedPreferences` simply call the method with the key to remove:

	// remove a value from the store
	cognito.remove( "FavoriteColors" );
	
## Synchronizing Data

Amazon Cognito uses opportunistic syncing in the background when the device is online on a set schedule (which is configurable via the `CognitoConfig`). If you need to ensure that your data is synchronized, `CognitoSharedPreferences` has two options for causing a manual synchronization:

	// synchronize a specific value
	cognito.syncByKey( "HighScore" );
	
	// synchronize all data
	cognito.synchronize();

Take note:
syncByKey(String key) will throw an exception if Amazon Cognito determines the device is offline, or an error occurs.	

synchronize() will never throw an exception, including if the device is offline. Instead, in order to see exceptions, you must register a handler via the registerSyncHandler(Handler handler) method. This handle will receive messages from manual calls to synchronization() and from periodic background synchronizations.

## Exception

Occasionally errors will occur during the synchronization process. Some of these errors are temporary and will eventually resolve themselves, but others will require some amount of intervention:

* `CognitoRemoteDataStorageException` - The Amazon DynamoDB call temporarily failed. No intervention should be necessary as a subsequent sync should solve the problem.
* `CognitoInvalidDataException` - The Amazon DynamoDB call failed. The value for the key is invalid and has been deleted from the local database. Consider displaying an error to the user letting them know that the data has been removed.
* `CognitoUserDataSizeLimitExceededException` - The Amazon DynamoDB call failed. An item collection is too large. For every user, the total sizes of all table and index items cannot exceed 10 GB. Consider displaying an error to the user letting them know that that they are using too much data.
* `CognitoLocalDataStorageException` - The SQLite call failed. The local SQLite store may need to be reset.
* `CognitoRecordNotFoundException` - An operation failed because it targeted a key that does not exist.
* `CognitoTokenException` - Thrown when the CognitoCredentialsProvider refresh method fails.

## Limitations

* Data stored per key is limited to the size of a DynamoDB item (64KB).
* Each user can store up to the maximum of a DynamoDB item collection (10GB).
* All methods are synchronous and may block, so care should be taken with calling methods on the UI thread.
* If storing an Set, all objects in the set must be of the same type (String or Double).
* The key used to store data must meet DynamoDB's range key size restriction (1kB).
* See DynamoDB's limitations page for additional limitations

## Other Supported Platforms

Amazon Cognito is also available for iOS as part of the [AWS SDK for iOS](http://aws.amazon.com/sdkforios/) and has been tested to be cross-platform compatible.

